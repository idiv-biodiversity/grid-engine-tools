package grid.engine

import cats.instances.string._
import scala.sys.process._

import Utils.RichDouble

object qjutil extends GETool with Signal {

  exit on SIGPIPE

  // --------------------------------------------------------------------------
  // main
  // --------------------------------------------------------------------------

  def run(implicit conf: Conf): Unit = {
    val cmd = ListBuffer("qstat", "-xml", "-ext", "-urg", "-s", "r")

    for (user ← conf.user) {
      cmd += "-u" += user
    }

    log.debug(s"""qstat cmd: ${cmd.mkString(" ")}""")

    val now = System.currentTimeMillis / 1000

    val xml = XML.loadString(cmd.!!)

    val jobs: Seq[Job] = for {
      node <- xml \\ "job_list"
      job <- Job.fromXML(node, now) if matches(job)
    } yield job

    if (conf.short)
      show.short(jobs)
    else
      show.human(jobs)
  }

  def matches(job: Job)(implicit conf: Conf): Boolean = {
    (conf.jids.isEmpty || conf.jids.contains(job.id)) &&
    (conf.hosts.isEmpty || conf.hosts.contains(job.host)) &&
    (conf.project.fold(true)(_ === job.project)) &&
    (job.runtime > conf.tolerance) &&
    (conf.ignoreSlotsGreaterThan.fold(true)(_ >= job.slots))
  }

  object show {
    def human(jobs: Seq[Job])(implicit conf: Conf): Unit = {
      val table = Table(Sized(
        "stat", "id", "name", "user", "department", "host", "slots", "cputime",
        "optimum", "util"
      ))

      table.alignments(6) = Table.Alignment.Right
      table.alignments(7) = Table.Alignment.Right
      table.alignments(8) = Table.Alignment.Right
      table.alignments(9) = Table.Alignment.Right

      for (job <- jobs.sortBy(_.utilization)) {
        import job._

        if (conf.full || status =!= "OK") {
          table.rows += Sized (
            status, identification, name, user, department, host, s"$slots",
            s"$cputime", s"$optimum", utilization_human
          )
        }
      }

      table.print()
    }

    def short(jobs: Seq[Job])(implicit conf: Conf): Unit = {
      for (job <- jobs) {
        if (job.status =!= "OK") {
          Console.println(job.identification)
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // data
  // --------------------------------------------------------------------------

  final case class Job (
    id: String,
    task: Option[String],
    name: String,
    user: String,
    department: String,
    host: String,
    project: String,
    slots: Int,
    cputime: Long,
    runtime: Long
  ) extends Utils.Job.Identifiable {
    lazy val utilization: Double = {
      cputime.toDouble / slots / runtime
    }

    lazy val utilization_human: String = {
      utilization.percent(decimals = 1).toString + "%"
    }

    lazy val optimum: Long = runtime * slots

    def status(implicit conf: Conf): String = {
      if (cputime > optimum * conf.upper)
        // oversubscribes
        "OVER"
      else if (slots > 1 && cputime < runtime)
        // parallel job that uses less than 1 CPU core
        "CRIT"
      else if (cputime < optimum * conf.lower)
        // bad performance
        "WARN"
      else
        "OK"
    }
  }

  object Job {
    val df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

    def fromXML(xml: Node, now: Long)(implicit conf: Conf): Option[Job] = {
      val id = (xml \ "JB_job_number").text
      val task = Option((xml \ "tasks").text).filter(_ =!= "")
      val name = (xml \ "JB_name").text
      val user = (xml \ "JB_owner").text
      val department = (xml \ "JB_department").text
      val host = QueueInstance.unsafe((xml \ "queue_name").text).host
      val project = (xml \ "JB_project").text
      val slots = (xml \ "slots").text.toInt
      val start = df.parse((xml \ "JAT_start_time").text).getTime / 1000
      val runtime = now - start
      val cputime = Option((xml \ "cpu_usage").text)
        .filter(_ =!= "")
        .map(_.toLong)

      val state = (xml \ "state").text

      if (state contains "d") {
        val job = Utils.Job.fullID(id, task)
        log.verbose(s"""dropping $job: marked deleted (state=$state)""")
        None
      } else {
        cputime match {
          case Some(cputime) =>
            Some(Job(id, task, name, user, department, host, project, slots,
              cputime, runtime))

          case None =>
            val job = Utils.Job.fullID(id, task)
            log.verbose(s"""dropping $job: no "cpu_usage" value""")
            None
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // configuration
  // --------------------------------------------------------------------------

  def app = "qjutil"

  final case class Conf (
    debug: Boolean = false,
    verbose: Boolean = false,
    hosts: Seq[String] = Vector(),
    project: Option[String] = None,
    short: Boolean = false,
    full: Boolean = false,
    jids: Seq[Int] = Vector(),
    lower: Double = Conf.Default.lower,
    upper: Double = Conf.Default.upper,
    user: Option[String] = None,
    tolerance: Int = Conf.Default.tolerance,
    ignoreSlotsGreaterThan: Option[Int] = None,
  ) extends Config

  object Conf extends ConfCompanion {
    def default = Conf()

    object Default {
      val lower = 0.8
      val upper = 1.01
      val tolerance = 0
    }
  }

  def parser = new OptionParser[Conf](app) {
    head(app, BuildInfo.version)

    note("Monitor job CPU utilization.\n")

    arg[Int]("<job-id>...")
      .unbounded()
      .optional()
      .action((jid, c) => c.copy(jids = c.jids :+ jid, user = Some("*")))
      .text("job ids to check, defaults to all jobs")
      .validate(x =>
        if (x > 0) success
        else failure("<job-id> must be positive integer"))

    note("\nTHRESHOLDS\n")

    opt[Double]('l', "underutilization-threshold")
      .valueName("<threshold>")
      .action((threshold, c) => c.copy(lower = threshold))
      .text(s"underutilization threshold, defaults to ${Conf.Default.lower}")

    opt[Double]('o', "overload-threshold")
      .valueName("<threshold>")
      .action((threshold, c) => c.copy(upper = threshold))
      .text(s"overload threshold, defaults to ${Conf.Default.upper}")

    opt[Int]('t', "tolerance")
      .valueName("<seconds>")
      .action((seconds, c) => c.copy(tolerance = seconds))
      .text(s"ignore short jobs, defaults to ${Conf.Default.tolerance}")

    note("\nOTHER FILTERS\n")

    opt[String]('h', "host")
      .unbounded()
      .valueName("<name>")
      .action((host, c) => c.copy(hosts = c.hosts :+ host, user = Some("*")))
      .text("filter by host")

    opt[String]('p', "project")
      .valueName("<name>")
      .action((name, c) => c.copy(project = Some(name), user = Some("*")))
      .text("filter by project")

    opt[String]('u', "user")
      .valueName("<name>")
      .action((name, c) => c.copy(user = Some(name), project = None))
      .text("filter by user, defaults to qstat behavior, i.e. ~/.sge_qstat")

    note("\nOUTPUT MODES\n")

    opt[Unit]('f', "full")
      .action((_, c) => c.copy(full = true, short = false))
      .text("full output, i.e. print even proper utilization")

    opt[Unit]("short")
      .action((_, c) => c.copy(full = false, short = true))
      .text("short output, i.e. print only job ID")

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
  }

}
