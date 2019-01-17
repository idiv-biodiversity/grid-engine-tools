package grid.engine

import cats.instances.string._
import java.util.Date
import scala.sys.process._

object qjend extends GETool {

  // --------------------------------------------------------------------------
  // main
  // --------------------------------------------------------------------------

  def run(implicit conf: Conf): Unit = {
    val cmd = ListBuffer("qstat", "-xml", "-s", "r")

    if (conf.users.nonEmpty) {
      for (user ← conf.users) {
        cmd += "-u" += user
      }
    } else {
      cmd += "-u" += "*"
    }

    val xml_running = XML.loadString(cmd.!!)

    // ------------------------------------------------------------------------
    // getting job start
    // ------------------------------------------------------------------------

    val formatter = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

    val jobs: Seq[Job] = for {
      job <- xml_running \\ "job_list"

      qi <- QueueInstance.unapply((job \ "queue_name").text)
      if conf.hosts.isEmpty || conf.hosts.contains(qi.host)

      owner <- emptyStringOption((job \ "JB_owner").text)

      id = (job \ "JB_job_number").text
      task = emptyStringOption((job \ "tasks").text)
      start = (job \ "JAT_start_time").text
    } yield Job(id, task, owner, formatter.parse(start).getTime)

    // ------------------------------------------------------------------------
    // getting runtime resources
    // ------------------------------------------------------------------------

    // id -> runtime
    val runtimes: Map[String, Long] = (for {
      id <- jobs.map(_.id).distinct.par
      qstatjxml <- Try(XML.loadString(s"""qstat -xml -j $id""".!!)) match {
        case Success(value) => Some(value)
        case Failure(error) =>
          Console.err.println(s"""excluded $id due to: $error""")
          None
      }
      hardRequests = qstatjxml \\ "JB_hard_resource_list" \\ "element"
      runtime <- hardRequests
        .find(el => (el \ "CE_name").text === "h_rt")
        .map(el => (el \ "CE_stringval").text.toLong)
    } yield (id, runtime)).seq.toMap

    // ------------------------------------------------------------------------
    // getting job end
    // ------------------------------------------------------------------------

    val job_ends = for {
      job @ Job(id, task, owner, start) ← jobs
      runtime ← runtimes.get(id)
      end = new Date(start + runtime * 1000)
    } yield job → end

    val table = Table(Sized("end", "job", "user"))

    for ((job, end) ← job_ends.sortBy(_._2)) {
      import job._
      table.rows += Sized(s"$end", identification, owner)
    }

    table.print()
  }

  // --------------------------------------------------------------------------
  // data
  // --------------------------------------------------------------------------

  final case class Job (
    id: String,
    task: Option[String],
    owner: String,
    start: Long
  ) extends Utils.Job.Identifiable

  // --------------------------------------------------------------------------
  // util
  // --------------------------------------------------------------------------

  def emptyStringOption(s: String): Option[String] =
    if (s.isEmpty) None else Some(s)

  // --------------------------------------------------------------------------
  // configuration
  // --------------------------------------------------------------------------

  def app = "qjend"

  final case class Conf (
    debug: Boolean = false,
    verbose: Boolean = false,
    hosts: Seq[String] = Vector(),
    users: Vector[String] = Vector(),
  ) extends Config

  object Conf extends ConfCompanion {
    def default = Conf()
  }

  def parser = new OptionParser[Conf](app) {
    head(app, BuildInfo.version)

    note("Show when running jobs end.\n")

    note("FILTER\n")

    opt[String]('h', "host")
      .valueName("<name>")
      .unbounded()
      .action((host, c) => c.copy(hosts = c.hosts :+ host))
      .text("hosts to check, defaults to all")

    opt[String]('u', "user")
      .valueName("<name>")
      .unbounded()
      .action((user, c) => c.copy(users = c.users :+ user))
      .text("users to check, defaults to all")

    note("\nOUTPUT MODES\n")

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
