package grid.engine

import cats.instances.string._
import scala.sys.process._

import Utils.XML._

object `qdiagnose-queue` extends GETool with Environment with Nagios {

  // --------------------------------------------------------------------------
  // main
  // --------------------------------------------------------------------------

  def run(implicit conf: Conf): Unit = {
    val cluster = snapshotFromQhost

    // bail out early with warning for Nagios

    if (conf.output === Output.Nagios && cluster.hosts.isEmpty) {
      val message = conf.hosts.size match {
        case 0 =>
          s"""usage: qdiagnose-queue -o nagios -h host"""

        case _ =>
          s"""no queue instances registered at ${conf.hosts.mkString(" ")}"""
      }

      println(message)
      exit.warning
    }

    // output

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

  // --------------------------------------------------------------------------
  // functions
  // --------------------------------------------------------------------------

  /** Converts `qhost -xml -q -j` output to a cluster tree. It parses the
    * entire XML data structure in one go.
    *
    * Filtering is done here.
    */
  def snapshotFromQhost(implicit conf: Conf): ClusterTree = {
    val cmd = """qhost -xml -q -j"""

    val xqhost = XML.loadString(cmd.!!)

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

        status <- QHostQueue(xqueue)("state_string")

        jobs = for {
          xjob <- xhost \ "job"

          QueueInstance(jqi) ← QHostJob(xjob)("qinstance_name")

          if qi === jqi

          id = (xjob \ "@name").text

          owner ← QHostJob(xjob)("job_owner")
        } yield JobNode(id, owner)
      } yield QueueInstanceNode(qi, status, jobs)
      if qis.nonEmpty
    } yield HostNode(hostname, qis)

    ClusterTree(hosts)
  }

  /** Returns a list of messages to explain the given state. */
  def explain(qi: QueueInstance, state: QueueState)
    (implicit conf: Conf): Seq[String] = state match {

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
      final case class Crash(queue: String, job: String, host: String)

      object CrashExtractor {
        val regex = """queue (\S+) marked QERROR as result of job (\d+)'s failure at host (\S+)""".r

        def unapply(s: String): Option[Crash] = s match {
          case regex(queue, job, host) =>
            Some(Crash(queue, job, host))

          case _ =>
            None
        }
      }

      def checkExecd(crash: Crash): List[String] = {
        val messages = for {
          codec <- List(Codec.UTF8, Codec.ISO8859)
        } yield Try {
          val source = Source.fromFile(s"$SGE_ROOT/$SGE_CELL/spool/${crash.host}/messages")(codec)

          try {
            val messages: Iterator[String] = for {
              line <- source.getLines
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

            messages.toList
          } finally {
            source.close()
          }
        } match {
          case Success(messages) =>
            messages

          case Failure(error) =>
            if (conf.verbose) {
              Console.err.println(s"""[check execd] $error""")
            }

            Nil
        }

        messages.flatten.distinct
      }

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

  def needsJobMessage(explanations: Seq[(QueueState, Seq[String])]): Boolean =
    explanations exists {
      case (state,_) =>
        state === QueueState.error ||
        state === QueueState.disabled ||
        state === QueueState.orphaned ||
        state === QueueState.alarm_load ||
        state === QueueState.alarm_suspend
    }

  // -----------------------------------------------------------------------------------------------
  // data
  // -----------------------------------------------------------------------------------------------

  /** Represents a snapshot of the current cluster. */
  final case class ClusterTree(hosts: Seq[HostNode]) {
    override def toString: String = {
      s"""cluster:${hosts.mkString("\n  ","\n  ","\n")}"""
    }
  }

  final case class HostNode(name: String, qis: Seq[QueueInstanceNode]) {
    override def toString: String = {
      s"""host: $name${qis.mkString("\n    ","\n    ","\n")}"""
    }
  }

  final case class QueueInstanceNode (
    qi: QueueInstance,
    status: String,
    jobs: Seq[JobNode]
  ) {
    def jobOwnerMap: Map[String,Seq[String]] = {
      jobs.distinct.groupBy(_.owner).mapValues(_.map(_.id))
    }

    override def toString: String = {
      s"""queue: $qi $status${jobs.mkString("\n      ","\n      ","")}"""
    }
  }

  final case class JobNode(id: String, owner: String)

  // --------------------------------------------------------------------------
  // configuration
  // --------------------------------------------------------------------------

  def app = "qdiagnose-queue"

  final case class Conf (
    debug: Boolean = false,
    verbose: Boolean = false,
    output: Output = Output.CLI,
    hosts: Vector[String] = Vector(),
    qis: Vector[QueueInstance] = Vector(),
  ) extends Config

  object Conf extends ConfCompanion {
    def default = Conf()
  }

  def parser = new OptionParser[Conf](app) {
    head(app, BuildInfo.version)

    note("Shows status of queue instances and tries to identify the problems.")
    note("")
    note("This tool is Nagios-aware, see examples below.")

    note("\nFILTER\n")

    arg[QueueInstance]("<qi>...")
      .unbounded()
      .optional()
      .action((qi, c) => c.copy(qis = c.qis :+ qi))
      .text("queue instances to check, defaults to all")

    opt[String]('h', "host")
      .valueName("<name>")
      .unbounded()
      .action((host, c) => c.copy(hosts = c.hosts :+ host))
      .text("hosts to check, defaults to all")

    note("\nOUTPUT MODES\n")

    opt[Output]('o', "output")
      .action((output, c) => c.copy(output = output))
      .text("""output mode, "cli" or "nagios", defaults to "cli"""")

    opt[Unit]("verbose")
      .action((_, c) => c.copy(verbose = true))
      .text("show verbose output")

    note("\nOTHER OPTIONS\n")

    opt[Unit]("debug")
      .hidden()
      .action((_, c) => c.copy(debug = true))
      .text("show debug output")

    help('?', "help").text("show this usage text")

    version("version").text("show version")

    note("")
    note("EXAMPLES")
    note("")
    note("  Checks all queue instances:")
    note("")
    note("    qdiagnose-queue")
    note("")
    note("  Checks all queue instances on host node001 and the queue instance")
    note("  all.q@node002:")
    note("")
    note("    qdiagnose-queue -h node001 all.q@node002")
    note("")
    note("  Checks all queue instances on host node001, outputs a single-line")
    note("  message and uses an exit status that Nagios-like monitoring")
    note("  systems can interpret (OK, WARNING, CRITICAL, UNKNOWN):")
    note("")
    note("    qdiagnose-queue -o nagios -h node001")
    note("")
  }

}
