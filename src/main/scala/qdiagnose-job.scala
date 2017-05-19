package grid.engine

import cats.Eq
import cats.instances.all._
import collection.mutable.ListBuffer
import sys.process._
import util.{ Try, Success, Failure }
import xml._

object `qdiagnose-job` extends App with Environment {

  // -----------------------------------------------------------------------------------------------
  // help / usage
  // -----------------------------------------------------------------------------------------------

  if (List("-?", "-h", "-help", "--help") exists args.contains) {
    println(s"""|usage: qdiagnose-job id...
                |
                |Shows why jobs failed.
                |
                |  -o [normal|short|full|mail]         different kinds of output format
                |  -q                                  quiet - less output
                |  -v                                  verbose - more output
                |  -? | -h | -help | --help            print this help
                |""".stripMargin)
    sys exit 0
  }

  // -----------------------------------------------------------------------------------------------
  // configuration
  // -----------------------------------------------------------------------------------------------

  sealed trait Output
  object Output {
    case object FullMarkdownMail extends Output
    case object Full             extends Output
    case object ShortOrNothing   extends Output
    case object ShortIfPossible  extends Output
  }

  sealed trait Verbosity
  object Verbosity {
    case object Quiet   extends Verbosity
    case object Verbose extends Verbosity

    implicit val eq: Eq[Verbosity] = Eq.fromUniversalEquals
  }

  case class Conf(verbosity: Verbosity, output: Output, ids: List[String]) {
    def verbose: Boolean =
      verbosity === Verbosity.Verbose
  }

  implicit val conf = {
    def accumulate(conf: Conf)(args: List[String]): Conf = args match {
      case Nil =>
        conf.copy(ids = conf.ids.reverse)

      case "-q" :: tail =>
        accumulate(conf.copy(verbosity = Verbosity.Quiet))(tail)

      case "-v" :: tail =>
        accumulate(conf.copy(verbosity = Verbosity.Verbose))(tail)

      case "-o" :: thing :: tail =>
        val o = thing match {
          case "normal" => Output.ShortIfPossible
          case "short"  => Output.ShortOrNothing
          case "full"   => Output.Full
          case "mail"   => Output.FullMarkdownMail
        }

        accumulate(conf.copy(output = o))(tail)

      case x :: tail =>
        accumulate(conf.copy(ids = x :: conf.ids))(tail)
    }

    accumulate(Conf(Verbosity.Quiet, Output.ShortIfPossible, Nil))(args.toList)
  }

  // -----------------------------------------------------------------------------------------------
  // functions
  // -----------------------------------------------------------------------------------------------

  /** Body should not print anything or output in console will be ugly. */
  def checking[R](name: String)(body: => Either[String,Seq[R]])(implicit conf: Conf): Seq[R] = {
    if (conf.verbose)
      Console.err.print(s"checking $name ... ")

    body match {
      case Left(message) =>
        if (conf.verbose)
          Console.err.println(s"""$message.""")
        Seq()

      case Right(data) =>
        if (conf.verbose)
          Console.err.println("done.")
        data
    }
  }

  def checkQstat(id: String): Either[String,Seq[String]] = try {
    val cmd = s"""qstat -xml -j $id"""
    val x = XML.loadString(cmd.!!)

    val data = for {
      task <- x \\ "JB_ja_tasks" \ "element"
      taskid = (task \ "JAT_task_number").text
      messageElement <- task \ "JAT_message_list" \ "element"
      message = (messageElement \ "QIM_message").text
    } yield s"""$id.$taskid: $message"""

    if (data.nonEmpty)
      Right(data)
    else
      Left("not found")
  } catch {
    case e: org.xml.sax.SAXParseException =>
      Left(e.getMessage)
  }

  case class QacctInfo(job: String, task: Int, failed: String, exit: String) {
    def isFailure: Boolean =
      !isSuccess

    def isSuccess: Boolean =
      failed === "0" &&
        exit === "0"

    override def toString = {
      if (isSuccess)
        s"""$job.$task was successful"""
      else if (failed.split(" ").headOption.exists(_ === "100") && exit.toInt > 128)
        s"""$job.$task received signal ${exit.toInt - 128}"""
      else
        s"""$job.$task exit: $exit failed: $failed"""
    }
  }

  def checkQacct(id: String): Either[String,Seq[QacctInfo]] = {
    val cmd = s"""qacct -j $id"""
    val errors = ListBuffer[String]()
    val pl = ProcessLogger(_ => (), errors += _)

    val raw = for {
      line <- cmd.lineStream_!(pl)
      if line.startsWith("taskid") ||
         line.startsWith("failed") ||
         line.startsWith("exit_status")
    } yield line

    val data = raw.grouped(3).collect({
      case Seq(task, failed, exit) =>
        val traw = task.split(" ").filter(_.nonEmpty).drop(1).mkString(" ")
        val t = Try(traw.toInt) match {
          case Success(v) =>
            v
          case Failure(e) =>
            if (traw =!= "undefined") Console.err.println(e.getMessage)
            1
        }

        QacctInfo (
          job    = id,
          task   = t,
          failed = failed.split(" ").filter(_.nonEmpty).drop(1).mkString(" "),
          exit   = exit  .split(" ").filter(_.nonEmpty).drop(1).mkString(" ")
        )
    }).toList

    if (errors.isEmpty)
      Right(data)
    else {
      if (errors.exists(_.matches("""^error: job id \d+ not found""")))
        Left("not found")
      else
        Left("unknown error")
    }
  }

  def checkExecd(id: String): Either[String,Seq[String]] = {
    val cmd = s"""find $SGE_ROOT/$SGE_CELL/spool -mindepth 2 -maxdepth 2 -name messages""" #|
              s"""xargs grep -hw $id"""

    val messages = ListBuffer[String]()
    val errors = ListBuffer[String]()

//       if line matches s""".*[^.:=(]\\b$id\\b.*"""

    val blacklist = Seq(
      """.+\|I\|SIGNAL.+""",
      """.+\|removing unreferenced job \d+\.\d+ without job report from ptf""",
      """.+\|reaping job "\d+" job usage retrieval complains: Job does not exist""",
      """.+\|process \(pid=\d+\) is blocking additional group id \d+""",
      """.+\|reaping job "\d+" job usage retrieval complains: Job does not exist""",
      """.+\|cleanup of slave tasks for job \d+\.\d+""",
      """.+\|found directory of job "active_jobs/\d+\.\d+"""",
      """.+\|can't open usage file "active_jobs/\d+\.\d+/usage" for job \d+\.\d+:.+""",
      """.+\|shepherd for job active_jobs/\d+\.\d+ has pid "\d+" and is not alive""",
      // category: has nothing to do with job success/failure
      """.+\|sending job [^ ]+ mail to user.+""",
      """.+\|additional group id \d+ was used by job_id \d+""",
      """.+\|skipping currently blocked additional group id \d+""",
      """.+\|there is no additional info about last usage of additional group id \d+ available""",
      s""".+\\|could not find pid $id in job list""",
      s""".+\\|PDC: could not read group entries from file /proc/$id/status"""
    )

    val fout: String => Unit = line => {
      val hit = blacklist.foldLeft(false)(_ || line.matches(_))
      if (!hit) messages += line
    }

    val ferr: String => Unit = line => errors += line

    val pl = ProcessLogger(fout, ferr)

    cmd.!(pl) match { // matches exit status
      case 0 =>
        Right(messages.toList)

      case 123 =>
        if (messages.nonEmpty)
          Right(messages.toList)
        else
          Left("not found")

      case i =>
        if (errors.nonEmpty)
          Left(s"""unexpected exit status: $i: ${errors.mkString(", ")}""")
        else
          Left(s"unexpected exit status: $i")
    }
  }

  def checkQmaster(id: String): Either[String,Seq[String]] = {
    val cmd = s"""grep -E [^.:=(]\\b$id\\b $SGE_ROOT/$SGE_CELL/spool/messages"""
    val messages = ListBuffer[String]()
    val errors = ListBuffer[String]()

    val blacklist = Seq(
      """.+\|removing trigger to terminate job \d+\.\d+""",
      """.+\|task .+ at .+ of job \d+\.\d+ finished""",
      """.+\|dispatching job .+ took .+ \(reservation=true\)""",
      """.+\|scheduler tries to change tickets of a non running job \d+ task \d+\(state 0\)""",
      """.+\|\w+@\S+ modified "\d+" in Job list""",
      """.+\|ignoring start order of jobs \d+\.\d+ because it was modified""",
      """.+\|job \d+\.\d+ finished on host [^ ]+""",
      """.+\|job \d+\.\d+ is already in deletion""",
      """.+\|job \d+\.\d+ should have finished since \d+s""",
      // category: has nothing to do with job
      """.+\|P\|PROF:.+""",
      """.+\|commlib info: got [^ ]+ error.+""",
      """.+\|received old load report (.+) from exec host ".+"""",
      """.+\|sending job [^ ]+ mail to user.+"""
    )

    val fout: String => Unit = line => {
      val hit = blacklist.foldLeft(false)(_ || line.matches(_))
      if (!hit) messages += line
    }

    val ferr: String => Unit = line => errors += line

    val pl = ProcessLogger(fout, ferr)

    cmd.!(pl) match { // matches exit status
      case 0 =>
        Right(messages.toList)

      case 1 =>
        Left("not found")

      case i =>
        if (errors.nonEmpty)
          Left(s"""unexpected exit status: $i: ${errors.mkString(", ")}""")
        else
          Left(s"unexpected exit status: $i")
    }
  }

  def analyze(id: String, qstat: Seq[String], qacct: Seq[QacctInfo], execd: Seq[String], qmaster: Seq[String]): Seq[String] = {
    val checklogs = qacct collect {
      case QacctInfo(job, task, failed, exit) if failed === "0" && exit =!= "0" =>
        s"""job $job.$task exited with an error: check your log/output/error files to find out what went wrong"""
    }

    val h_rt = """.*job (\d+\.\d+) exceeded hard wallclock time.*""".r
    val h_vmem = """.*job (\d+\.\d+) exceeds job master hard limit "h_vmem".*""".r

    val es = execd collect {
      case h_rt(job) =>
        s"""$job exceeded hard runtime limit (h_rt)"""

      case h_vmem(job) =>
        s"""$job exceeded hard memory limit (h_vmem)"""
    }

    val del = """.*\|(\w+) has deleted job (\d+)""".r
    val delforce = """.*\|warning: (\w+) forced the deletion of job (\d+)""".r
    val deljob = """.*\|(\w+) has registered the job (\d+) for deletion""".r
    val deltask = """.*\|(\w+) has registered the job-array task (\d+\.\d+) for deletion""".r
    val inoutfile = """.*job (\d+\.\d+) failed on host \S+ general opening input/output file because: (.+)""".r

    def delmsg(user: String, job: String) =
      s"""job $job has been deleted by $user"""

    val qs = qmaster collect {
      case del(user, job) =>
        delmsg(user, job)

      case delforce(user, job) =>
        delmsg(user, job)

      case deljob(user, job) =>
        delmsg(user, job)

      case deltask(user, job) =>
        delmsg(user, job)

      case inoutfile(job, reason) =>
        s"""job $job had input/output error: $reason"""
    }

    val rescheduled = qmaster.count(_ matches """.*\|W\|rescheduling job (\d+\.\d+)""")
    val rs = if (rescheduled === 1)
      List(s"job $id has been rescheduled once")
    else if (rescheduled > 1)
      List(s"job $id has been rescheduled $rescheduled times")
    else
      Nil

    checklogs ++ rs ++ es ++ qs
  }

  // -----------------------------------------------------------------------------------------------
  // main
  // -----------------------------------------------------------------------------------------------

  def shortenQacct(qacct: Seq[QacctInfo]): Seq[String] = for {
    (success,jobs) <- Utils.group(qacct)(_.isSuccess)

    (task, verb, failure) = jobs.size match {
      case 1 => (s"${jobs.head.task}",                   "was",  "a failure")
      case n => (s"${jobs.head.task}-${jobs.last.task}", "were", "failures")
    }
  } yield
    if (success) s"${jobs.head.job}.$task $verb successful"
    else         s"${jobs.head.job}.$task $verb $failure"

  def printFull(qstat: Seq[String], qacct: Seq[QacctInfo], execd: Seq[String], qmaster: Seq[String]): Unit = {
    qstat map { "qstat " + _ } foreach println
    shortenQacct(qacct) map { "qacct " + _ } foreach println
    execd map { "execd " + _ } foreach println
    qmaster map { "qmaster " + _ } foreach println
  }

  for (id <- conf.ids) {
    val qstat = checking("active job database")(checkQstat(id))
    val qacct = checking("accounting database")(checkQacct(id)).sortBy(_.task)
    val execd = checking("execution daemon messages")(checkExecd(id))
    val qmaster = checking("master daemon messages")(checkQmaster(id))

    val analysis = analyze(id, qstat, qacct, execd, qmaster)

    conf.output match {
      case Output.ShortIfPossible =>
        if (analysis.nonEmpty)
          analysis foreach println
        else
          printFull(qstat, qacct, execd, qmaster)

      case Output.Full =>
        analysis foreach println
        printFull(qstat, qacct, execd, qmaster)

      case Output.ShortOrNothing =>
        if (analysis.nonEmpty)
          analysis foreach println
        else
          println("all ok / couldn't find anything / don't know how to interpret yet")

      case Output.FullMarkdownMail =>
        print(s"""|## Analysis for Job $id
                  |
                  |""".stripMargin)

        if (analysis.nonEmpty)
          print(s"""|${analysis.mkString(start = "- ", sep = "\n- ", end = "")}
                    |
                    |""".stripMargin)

        if (qstat.nonEmpty)
          print(s"""|### Live System Messages
                    |
                    |The base command used to fetch this information was: `qstat -j $id`
                    |
                    |```
                    |${qstat.mkString("\n")}
                    |```
                    |
                    |""".stripMargin)

        if (qacct.nonEmpty)
          print(s"""|### Accounting Information
                    |
                    |The base command used to fetch this information was: `qacct -j $id`
                    |
                    |```
                    |${shortenQacct(qacct).mkString("\n")}
                    |```
                    |
                    |""".stripMargin)

        if (execd.nonEmpty)
          print(s"""|### Execution Daemon Messages
                    |
                    |The base command used to fetch this information was: `egrep '[^.]\\b$id\\b' $SGE_ROOT/$SGE_CELL/spool/*/messages`
                    |
                    |```
                    |${execd.mkString("\n")}
                    |```
                    |
                    |""".stripMargin)

        if (qmaster.nonEmpty)
          print(s"""|### Master Daemon Messages
                    |
                    |The base command used to fetch this information was: `egrep '[^.]\\b$id\\b' $SGE_ROOT/$SGE_CELL/spool/messages`
                    |
                    |```
                    |${qmaster.mkString("\n")}
                    |```
                    |
                    |""".stripMargin)
    }
  }

}
