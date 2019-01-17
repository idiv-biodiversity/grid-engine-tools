package grid.engine

import cats.instances.string._
import scala.sys.process._

import Utils.RichDouble

object qjdays extends GETool {

  // --------------------------------------------------------------------------
  // main
  // --------------------------------------------------------------------------

  def run(implicit conf: Conf): Unit = {
    val cmd = ListBuffer("qstat", "-xml", "-ext", "-s", "r")

    if (conf.users.nonEmpty) {
      for (user ← conf.users) {
        cmd += "-u" += user
      }
    } else {
      cmd += "-u" += "*"
    }

    log.debug(s"""qstat cmd: ${cmd.mkString(" ")}""")

    val xml = XML.loadString(cmd.!!)

    val ids = for {
      job ← xml \\ "job_list"

      project = (job \ "JB_project").text
      if conf.project.fold(true)(_ === project)

      id = (job \ "JB_job_number").text
    } yield id

    val table = Table(Sized("runtime", "in days", "users"))

    table.alignments(0) = Table.Alignment.Right
    table.alignments(1) = Table.Alignment.Right

    ids.sorted.distinct
      .flatMap(jobAnalysis)
      .groupBy(_.runtime)
      .mapValues(_.map(_.owner).distinct)
      .toSeq.sortBy(- _._1) foreach {
        case (runtime, users) =>
          val days = (runtime.toDouble / 60 / 60 / 24).roundTo(1)

          table.rows += Sized (
            s"$runtime", s"$days", users.mkString(" ")
          )
      }

    table.print()
  }

  final case class ADT(owner: String, runtime: Int)

  object ADT {
    def apply(owner: String, runtime: Option[Int]): Option[ADT] = runtime match {
      case Some(runtime) => Some(ADT(owner, runtime))
      case None          => None
    }
  }

  def emptyStringOption(s: String): Option[String] =
    if (s.isEmpty) None else Some(s)

  def jobAnalysis(id: String): Option[ADT] = for {
    qstatjxml <- Try(XML.loadString(s"""qstat -j $id -xml""".!!)) match {
      case Success(value) => Some(value)
      case Failure(error) =>
        Console.err.println(s"""excluded $id due to: $error""")
        None
    }
    owner <- emptyStringOption((qstatjxml \\ "JB_owner").text)
    hardRequests = qstatjxml \\ "JB_hard_resource_list" \\ "element"
    runtime <- hardRequests.find(el => (el \ "CE_name").text === "h_rt").map(el => (el \ "CE_stringval").text.toInt)
  } yield ADT(owner, runtime)

  // --------------------------------------------------------------------------
  // configuration
  // --------------------------------------------------------------------------

  def app = "qjdays"

  final case class Conf (
    debug: Boolean = false,
    verbose: Boolean = false,
    project: Option[String] = None,
    users: Vector[String] = Vector(),
  ) extends Config

  object Conf extends ConfCompanion {
    def default = Conf()
  }

  def parser = new OptionParser[Conf](app) {
    head(app, BuildInfo.version)

    note("Show job runtimes in days.\n")

    note("FILTER\n")

    opt[String]('p', "project")
      .valueName("<name>")
      .action((name, c) => c.copy(project = Some(name), users = Vector()))
      .text("filter by project")

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
