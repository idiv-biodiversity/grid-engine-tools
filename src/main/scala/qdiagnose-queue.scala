package grid.engine

import cats.Eq
import cats.instances.all._
import sys.process._
import xml._

object `qdiagnose-queue` extends App with Environment with Nagios {

  // -----------------------------------------------------------------------------------------------
  // help / usage
  // -----------------------------------------------------------------------------------------------

  if (List("-?", "--help") exists args.contains) {
    println(s"""|usage: qdiagnose-queue [-o {cli|nagios}] [-h host] [qi...]
                |
                |Shows status of queue instances and tries to identify the problems. This tool
                |is Nagios-aware, see example below.
                |
                |FILTERING
                |
                |  queue@host                  checks only this specific queue instance
                |
                |  -h host                     checks all queue instances registered at host
                |
                |OUTPUT
                |
                |  -o {cli|nagios}             output type
                |
                |     cli                      suitable for command line usage (default)
                |     nagios                   suitable for use as a nagios check
                |
                |OTHER
                |
                |  -? | --help                 print this help
                |
                |EXAMPLES
                |
                |  Checks all queue instances:
                |
                |    qdiagnose-queue
                |
                |  Checks all queue instances on host node001 and the queue instance
                |  all.q@node002:
                |
                |    qdiagnose-queue -h node001 all.q@node002
                |
                |  Checks all queue instances on host node001, outputs a single-line message and
                |  uses an exit status that Nagios-like monitoring systems can interpret (OK,
                |  WARNING, CRITICAL, UNKNOWN):
                |
                |    qdiagnose-queue -o nagios -h node001
                |
                |""".stripMargin)
    sys exit 0
  }

  // -----------------------------------------------------------------------------------------------
  // configuration
  // -----------------------------------------------------------------------------------------------

  sealed trait Output
  object Output {
    case object CLI    extends Output
    case object Nagios extends Output
    implicit val eq: Eq[Output] = Eq.fromUniversalEquals
  }

  case class QueueInstance(queue: String, host: String) {
    override def toString =
      s"$queue@$host"
  }

  object QueueInstance {
    val regex = """(\S+)@(\S+)""".r

    def unapply(s: String): Option[QueueInstance] = s match {
      case regex(q, h) =>
        Some(QueueInstance(q, h))

      case _ =>
        None
    }

    implicit val eq: Eq[QueueInstance] = Eq.fromUniversalEquals
  }

  case class Conf(output: Output, hosts: List[String], qis: List[QueueInstance])
  object Conf {
    def default: Conf =
      Conf(Output.CLI, Nil, Nil)
  }

  implicit val conf: Conf = {
    def accumulate(conf: Conf)(args: List[String]): Conf = args match {
      case Nil =>
        // need to reverse both lists because we built them up backwards
        conf.copy(hosts = conf.hosts.reverse, qis = conf.qis.reverse)

      case "-o" :: output :: tail =>
        val o = output match {
          case "cli"    => Output.CLI
          case "nagios" => Output.Nagios
        }

        accumulate(conf.copy(output = o))(tail)

      case "-h" :: host :: tail =>
        accumulate(conf.copy(hosts = host :: conf.hosts))(tail)

      case QueueInstance(qi) :: tail =>
        accumulate(conf.copy(qis = qi :: conf.qis))(tail)

      case x :: tail =>
        Console.err.println(s"don't know what to do with $x")
        exit.critical
    }

    accumulate(Conf.default)(args.toList)
  }

  // -----------------------------------------------------------------------------------------------
  // core data structures this app uses
  // -----------------------------------------------------------------------------------------------

  /** Represents a snapshot of the current cluster. */
  case class ClusterTree(hosts: Seq[HostNode]) {
    override def toString = {
      s"""cluster:${hosts.mkString("\n  ","\n  ","\n")}"""
    }
  }

  case class HostNode(name: String, qis: Seq[QueueInstanceNode]) {
    override def toString = {
      s"""host: $name${qis.mkString("\n    ","\n    ","\n")}"""
    }
  }

  case class QueueInstanceNode(qi: QueueInstance, status: String, jobs: Seq[JobNode]) {
    def jobOwnerMap: Map[String,Seq[String]] = {
      jobs.distinct.groupBy(_.owner).mapValues(_.map(_.id))
    }
    override def toString = {
      s"""queue: $qi $status${jobs.mkString("\n      ","\n      ","")}"""
    }
  }

  case class JobNode(id: String, owner: String)

  // -----------------------------------------------------------------------------------------------
  // functions
  // -----------------------------------------------------------------------------------------------

  /** Converts `qhost -xml -q -j` output to a cluster tree. It parses the entire XML data structure
    * in one go.
    *
    * Filtering is done here.
    */
  def snapshotFromQhost(implicit conf: Conf): ClusterTree = {
    val cmd = """qhost -xml -q -j"""

    val xqhost: Elem = XML.loadString(cmd.!!)

    // do we need to filter?
    val nofilter = conf.hosts.isEmpty && conf.qis.isEmpty

    val hosts = for {
      xhost <- xqhost \ "host"
      hostname = (xhost  \ "@name").text
      if hostname =!= "global"

      qis = for {
        xqueue <- xhost  \ "queue"
        queuename = (xqueue \ "@name").text

        qi = QueueInstance(queuename, hostname)

        if nofilter || (conf.hosts contains hostname) || (conf.qis contains qi)

        status <- (xqueue \ "queuevalue") collectFirst {
          case x if (x \ "@name").text === "state_string" => x.text
        }

        jobs = for {
          xjob <- xhost \ "job"

          QueueInstance(jqi) <- (xjob \ "jobvalue") collectFirst {
            case x if (x \ "@name").text === "qinstance_name" => x.text
          }

          if qi === jqi

          id = (xjob \ "@name").text

          owner <- (xjob \ "jobvalue") collectFirst {
            case x if (x \ "@name").text === "job_owner" => x.text
          }
        } yield JobNode(id, owner)
      } yield QueueInstanceNode(qi, status, jobs)
      if qis.nonEmpty
    } yield HostNode(hostname, qis)

    ClusterTree(hosts)
  }

  /** Returns a list of messages to explain the given state. */
  def explain(qi: QueueInstance, state: QueueState): Seq[String] = state match {

    case QueueState.ok =>
      List("no problems detected")

    case QueueState.disabled =>
      val regex = """.*admin msg: (.+)""".r

      // TODO this doesn't work with -xml - that's a bug
      // qstat -xml -explain m -q foo@bar shows no messages
      s"qstat -explain m -q $qi".lineStream collect {
        case regex(message) =>
          s"admin message: $message"
      }

    case QueueState.error =>
      case class Crash(queue: String, job: String, host: String)

      object CrashExtractor {
        val regex = """queue (\S+) marked QERROR as result of job (\d+)'s failure at host (\S+)""".r

        def unapply(s: String): Option[Crash] = s match {
          case regex(queue, job, host) =>
            Some(Crash(queue, job, host))

          case _ =>
            None
        }
      }

      def checkExecd(crash: Crash) = for {
        line <- scala.io.Source.fromFile(s"$SGE_ROOT/$SGE_CELL/spool/${crash.host}/messages").getLines
        log = line.split("\\|").drop(4).mkString("|")
        if log matches s""".*\\b${crash.job}\\b.*"""
        if ! (log matches """additional group id \d+ was used by job_id \d+""")
        if ! (log matches """job \d+\.\d+ exceeded hard wallclock time - initiate terminate method""")
        if ! (log matches """process \(pid=\d+\) is blocking additional group id \d+""")
        if ! (log matches """reaping job "\d+" job usage retrieval complains: Job does not exist""")
        if ! (log matches """removing unreferenced job \d+\.\d+ without job report from ptf""")
        if ! (log matches """spooling job \d+\.\d+ took \d+ seconds""")
        if ! (log matches """sending job .+ mail to user .+""")
        message = classify(log)
      } yield message

      object Classification {
        val prolog = """shepherd of job (\d+\.\d+) exited with exit status = 8""".r
      }

      def classify(line: String) = line match {
        case Classification.prolog(job) =>
          s"""prolog failed for job $job (shepherd exit status 8)"""

        case _ =>
          line
      }

      val cmd = s"""qstat -xml -explain E -q $qi"""

      for {
        qlist <- XML.loadString(cmd.!!) \ "queue_info" \ "Queue-List"
        report_qi = (qlist \ "name").text
        if report_qi === qi.toString
        messages = (qlist \ "message").map(_.text).distinct
        if messages.nonEmpty
        crash <- messages collect { case CrashExtractor(crash) => crash }
        log <- checkExecd(crash)
      } yield log

    case QueueState.orphaned =>
      List("queue instance has been deleted, remains because running jobs are still associated with it")

    case QueueState.suspended =>
      List("has been suspended")

    case QueueState.unknown =>
      List("usually means execd crashed")

    case QueueState.alarm_load =>
      val cmd = s"""qstat -xml -explain a -q $qi"""

      for {
        qlist <- XML.loadString(cmd.!!) \ "queue_info" \ "Queue-List"
        report_qi = (qlist \ "name").text
        if report_qi === qi.toString
        message <- (qlist \ "load-alarm-reason").map(_.text).distinct
      } yield message

    case QueueState.alarm_suspend =>
      val cmd = s"""qstat -xml -explain A -q $qi"""

      for {
        qlist <- XML.loadString(cmd.!!) \ "queue_info" \ "Queue-List"
        report_qi = (qlist \ "name").text
        if report_qi === qi.toString
        message <- (qlist \ "load-alarm-reason").map(_.text).distinct
      } yield message

    case QueueState.calendar_disabled =>
      List("calendar has been disabled")

    case QueueState.calendar_suspended =>
      List("calendar is suspended")

    case QueueState.configuration_ambiguous =>
      List("configuration ambiguity, check queue configuration")

    case QueueState.subordinate_suspended =>
      List("suspended by higher-priority queue instance")
  }

  // TODO make configurable
  def severity(state: QueueState): Severity = state match {
    case QueueState.ok        => Severity.OK
    case QueueState.disabled  => Severity.WARNING
    case QueueState.error     => Severity.CRITICAL
    case QueueState.orphaned  => Severity.WARNING
    case QueueState.suspended => Severity.WARNING
    case QueueState.unknown   => Severity.CRITICAL

    case QueueState.alarm_load    => Severity.WARNING
    case QueueState.alarm_suspend => Severity.WARNING

    case QueueState.calendar_disabled  => Severity.OK
    case QueueState.calendar_suspended => Severity.OK

    case QueueState.configuration_ambiguous => Severity.WARNING

    case QueueState.subordinate_suspended => Severity.OK
  }

  // -----------------------------------------------------------------------------------------------
  // main
  // -----------------------------------------------------------------------------------------------

  val cluster = snapshotFromQhost

  // bail out early with warning for Nagios

  if (conf.output === Output.Nagios && cluster.hosts.isEmpty) {
    val message = conf.hosts.size match {
      case 0 =>
        s"""usage: qdiagnose-queue -o nagios -h host"""

      case 1 =>
        s"""no queue instances registered at host ${conf.hosts.head}"""

      case n =>
        s"""no queue instances registered at hosts ${conf.hosts.mkString(" ")}"""
    }

    println(message)
    exit.warning
  }

  // output

  def needsJobMessage(explanations: Seq[(QueueState, Seq[String])]): Boolean =
    explanations exists {
      case (state,_) =>
        state === QueueState.error ||
        state === QueueState.disabled ||
        state === QueueState.orphaned ||
        state === QueueState.alarm_load ||
        state === QueueState.alarm_suspend
    }

  val severities: Seq[Severity] = for {
    qinode <- cluster.hosts.flatMap(_.qis)
    qi     = qinode.qi
    status = qinode.status
    jobs   = qinode.jobOwnerMap

    stateExplanations =
      for (state <- QueueState.fromStateString(status)) yield
        state -> explain(qi, state)

    // here output and getting severities happen at the same time
    severity <- conf.output match {
      case Output.CLI =>
        for {
          (state, explanations) <- stateExplanations
          if state =!= QueueState.ok // TODO make configurable
        } {
          if (explanations.isEmpty)
            println(s"$qi $state")
          else for (explanation <- explanations)
            println(s"$qi $state $explanation")
        }

        if (needsJobMessage(stateExplanations)) {
          val jobmessage = if (jobs.isEmpty)
            "no jobs"
          else {
            val joblist = jobs.mapValues(_.mkString("(", ", ", ")")).mkString(", ")
            s"""jobs: $joblist"""
          }

          println(s"$qi $jobmessage")
        }

        Nil // no need to return severities for CLI output

      case Output.Nagios =>
        val combined = for {
          (state, explanations) <- stateExplanations
          if state =!= QueueState.ok
        } yield {
          if (explanations.nonEmpty) {
            val message = explanations.mkString(start = "(", sep = ", ", end = ")")
            s"""state=$state $message"""
          } else
            s"""state=$state"""
        }

        val jobMessages = if (needsJobMessage(stateExplanations)) {
          val message = if (jobs.isEmpty)
            "no jobs"
          else {
            val joblist = jobs.mapValues(_.mkString("(", ", ", ")")).mkString(", ")
            s"""jobs: $joblist"""
          }

          List(message)
        } else Nil

        val _severities = (for ((state,_) <- stateExplanations) yield
          severity(state)).sortBy(_.value)

        val _severity = _severities.lastOption.getOrElse("")

        println(s"""$qi ${_severity} ${(combined ++ jobMessages).mkString("; ")}""".trim)

        _severities
    }
  } yield severity

  // exit status for Nagios-like monitoring systems

  conf.output match {
    case Output.CLI =>
      exit.ok

    case Output.Nagios =>
      severities.sortBy(_.value).lastOption.getOrElse(Severity.OK).exit()
  }
}
