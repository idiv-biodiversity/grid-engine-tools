package grid.engine

import cats.instances.int._
import cats.instances.string._
import scala.sys.process._

import Utils.XML._

object qfreeresources extends GETool {

  // --------------------------------------------------------------------------
  // main
  // --------------------------------------------------------------------------

  def run(implicit conf: Conf): Unit = {
    val cmd = ListBuffer("qhost", "-xml", "-q", "-F", "h_vmem,slots")

    if (conf.resources.nonEmpty) {
      cmd += "-l"
      cmd += conf.resources.map({ case (k, v) => s"$k=$v" }).mkString(",")
    }

    log.debug(s"""qhost cmd: ${cmd.mkString(" ")}""")

    val xml = XML.loadString(cmd.!!)

    val qis = for {
      xml_host ← xml \ "host"

      host = (xml_host \ "@name").text
      if host =!= "global"

      xml_queue ← xml_host \ "queue"

      queue = (xml_queue \ "@name").text

      states = QueueState.fromQHostXML(xml_queue)
      if conf.full || states.forall(_ === QueueState.ok)

      slots ← QHostResource(xml_host)("slots").map(_.toDouble.toInt)
      if conf.full || slots > 0

      memory ← QHostResource(xml_host)("h_vmem").map(
        _.dropRight(1).toDouble.toInt
      )
      if conf.full || memory > 0

      qi = QIFreeStatus(queue, host, slots, memory)
      if conf.full || qi.gPerCore > 0
    } yield qi

    // TODO bring back color
    val table = Table(Sized("queue", "host", "slots", "memory", "mem/slot"))

    table.alignments(2) = Table.Alignment.Right
    table.alignments(3) = Table.Alignment.Right
    table.alignments(4) = Table.Alignment.Right

    for (qi <- qis.sortBy(- _.slots)) {
      import qi._

      table.rows += Sized(
        queue, host, s"$slots", s"${memory}G", s"${gPerCore}G"
      )
    }

    table.print()
  }

  // --------------------------------------------------------------------------
  // data
  // --------------------------------------------------------------------------

  final case class QIFreeStatus (
    queue: String,
    host: String,
    slots: Int,
    memory: Int
  ) {
    lazy val gPerCore = if (slots === 0) {
      0
    } else {
      memory / slots
    }
  }

  // --------------------------------------------------------------------------
  // configuration
  // --------------------------------------------------------------------------

  def app = "qfreeresources"

  final case class Conf (
    resources: Map[String, String] = Map.empty,
    full: Boolean = false,
    debug: Boolean = false,
    verbose: Boolean = false,
  ) extends Config

  object Conf extends ConfCompanion {
    def default = Conf()
  }

  def parser = new OptionParser[Conf](app) {
    head(app, BuildInfo.version)

    note("Show free resources.\n")

    note("FILTER\n")

    opt[Map[String, String]]('l', "resource-filter")
      .valueName("resource=value,...")
      .unbounded()
      .action((x, c) => c.copy(resources = c.resources ++ x))
      .text("resource filters, forwarded to qhost")

    note("\nOUTPUT MODES\n")

    opt[Unit]('f', "full")
      .action((_, c) => c.copy(full = true))
      .text("full output, i.e. also print full queue instances")

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
