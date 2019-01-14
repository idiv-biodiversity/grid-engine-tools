package grid.engine

import cats.instances.string._
import scala.sys.process._

import Utils.RichDouble
import Utils.XML.QHostResource

object qload extends GETool with Signal {

  exit on SIGPIPE

  // --------------------------------------------------------------------------
  // main
  // --------------------------------------------------------------------------

  def run(implicit conf: Conf): Unit = {
    val cmd = ListBuffer("qhost", "-xml", "-j", "-F", "load_short")

    if (conf.hosts.nonEmpty) {
      cmd += "-h" += conf.hosts.mkString(",")
    }

    log.debug(s"""qstat cmd: ${cmd.mkString(" ")}""")

    val xml = XML.loadString(cmd.!!)

    val hosts: Seq[Host] = for {
      node ← xml \ "host"

      name = (node \ "@name").text
      if name =!= "global"

      tasks = (node \ "job").size
      load ← QHostResource(node)("load_short").map(_.toDouble)
      if tasks > 0 || load > 1
    } yield Host(name, load, tasks)

    if (conf.short)
      show.short(hosts)
    else
      show.human(hosts)
  }

  object show {
    def human(hosts: Seq[Host])(implicit conf: Conf): Unit = {
      val table = Table(Sized("stat", "host", "load", "tasks", "util"))

      table.alignments(2) = Table.Alignment.Right
      table.alignments(3) = Table.Alignment.Right
      table.alignments(4) = Table.Alignment.Right

      for (host <- hosts) {
        import host._

        if (conf.full || status =!= "OK") {
          table.rows += Sized (
            status, name, s"${load.roundTo(1)}", s"$tasks", utilization
          )
        }
      }

      table.print()
    }

    def short(hosts: Seq[Host])(implicit conf: Conf): Unit = {
      for (host <- hosts) {
        if (host.status =!= "OK") {
          Console.println(host.name)
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // data
  // --------------------------------------------------------------------------

  final case class Host (
    name: String,
    load: Double,
    tasks: Int
  ) {
    def status(implicit conf: Conf): String = {
      if (load > tasks * conf.upper)
        // overloaded
        "OVER"
      else if (load < tasks * conf.lower)
        // bad performance
        "WARN"
      else
        "OK"
    }

    def utilization: String = {
      tasks match {
        case 0 => "-"
        case _ => (load / tasks).percent(decimals = 1).toString + "%"
      }
    }
  }

  // --------------------------------------------------------------------------
  // configuration
  // --------------------------------------------------------------------------

  def app = "qload"

  final case class Conf (
    debug: Boolean = false,
    verbose: Boolean = false,
    short: Boolean = false,
    full: Boolean = false,
    lower: Double = Conf.Default.lower,
    upper: Double = Conf.Default.upper,
    hosts: Seq[String] = Vector(),
  ) extends Config

  object Conf extends ConfCompanion {
    def default = Conf()

    object Default {
      val lower = 0.8
      val upper = 1.1
    }
  }

  def parser = new OptionParser[Conf](app) {
    head(app, BuildInfo.version)

    note("List overloaded and underutilized hosts.\n")

    arg[String]("<host>...")
      .unbounded()
      .optional()
      .action((host, c) => c.copy(hosts = c.hosts :+ host))
      .text("hosts to check, defaults to all")

    note("\nTHRESHOLDS\n")

    opt[Double]('l', "underutilization-threshold")
      .valueName("<threshold>")
      .action((threshold, c) => c.copy(lower = threshold))
      .text(s"underutilization threshold, defaults to ${Conf.Default.lower}")

    opt[Double]('o', "overload-threshold")
      .valueName("<threshold>")
      .action((threshold, c) => c.copy(upper = threshold))
      .text(s"overload threshold, defaults to ${Conf.Default.upper}")

    note("\nOUTPUT MODES\n")

    opt[Unit]('f', "full")
      .action((_, c) => c.copy(full = true, short = false))
      .text("full output, i.e. print even proper utilization")

    opt[Unit]("short")
      .action((_, c) => c.copy(full = false, short = true))
      .text("short output, i.e. print only host name")

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
