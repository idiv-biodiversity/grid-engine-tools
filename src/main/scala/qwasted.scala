package grid.engine

import cats.instances.string._
import scala.sys.process._

import Utils.XML.QHostResource

object qwasted extends GETool with Nagios {

  // --------------------------------------------------------------------------
  // core data structures this app uses
  // --------------------------------------------------------------------------

  final case class Host (
    name: String,
    cores: Long,
    free: Long,
    run: Long
  )

  // --------------------------------------------------------------------------
  // main
  // --------------------------------------------------------------------------

  def run(implicit conf: Conf): Unit = {
    val hosts = parse()

    val wasteds: Seq[(String, Double)] = if (conf.summary) {
      final case class Summary(used: Long, run: Long)

      val sum = hosts.foldLeft(Summary(0, 0)) { (sum, host) =>
        val hused = host.cores - host.free
        sum.copy(
          used = sum.used + hused,
          run = sum.run + host.run
        )
      }

      val wasted: Double = sum.used match {
        case 0 => 1.0
        case _ => (1 - sum.run.toDouble / sum.used).max(0)
      }

      List("all" -> wasted)
    } else {
      hosts map {
        case Host(name, cores, free, run) =>
          val used = cores - free

          val wasted: Double = used match {
            case 0 => 0
            case _ => (1 - run.toDouble / used).max(0)
          }

          name -> wasted
      }
    }

    def fmt(a: Double): String = s"${(a * 100).round}% wasted"

    val status: Seq[Severity] = wasteds map {
      case (host, wasted) =>
        if (wasted >= conf.threshold * 2) {
          Severity.CRITICAL.println(s"$host ${fmt(wasted)}")
        } else if (wasted >= conf.threshold) {
          Severity.WARNING.println(s"$host ${fmt(wasted)}")
        } else if (conf.full || conf.output === Output.Nagios || conf.summary) {
          Severity.OK.println(s"$host ${fmt(wasted)}")
        } else {
          Severity.OK
        }
    }

    conf.output match {
      case Output.Nagios =>
        status.sortBy(_.value).lastOption.getOrElse(Severity.OK).exit

      case Output.CLI =>
        exit.ok
    }
  }

  // --------------------------------------------------------------------------
  // helpers
  // --------------------------------------------------------------------------

  def parseDoubleRounded(text: String): Either[String, Long] = {
    try {
      Right(text.toDouble.round)
    } catch {
      case _: NumberFormatException =>
        Left(s"not a number: $text")
    }
  }

  def getR[A]
    (xhost: Node, name: String)
    (f: String => Either[String, A])
      : Either[String, A] = {
    QHostResource(xhost)(name) match {
      case Some(text) =>
        f(text)
      case None =>
        Left(s"no resource value for $name")
    }
  }

  def getResource[A]
    (xhost: Node, host: String, name: String)
    (f: String => Either[String, A])
    (implicit conf: Conf)
      : List[A] = {
    getR(xhost, name)(f) match {
      case Left(message) =>
        if (conf.verbose)
          Console.err.println(s"qwasted: $host: $message")
        Nil
      case Right(value) =>
        List(value)
    }
  }

  /** Converts `qhost -xml -q -j` to data structure. It parses the entire XML
    * data structure in one go.
    *
    * Filtering is done here.
    */
  def parse()(implicit conf: Conf): Seq[Host] = {
    // TODO allow different resource for running_proc
    val qhost = """qhost -xml -F num_proc,slots,running_proc"""

    val xml = XML.loadString(qhost.!!)

    for {
      host <- xml \ "host"
      name = (host  \ "@name").text
      if name =!= "global"
      if conf.hosts.isEmpty || (conf.hosts contains name)
      cores <- getResource(host, name, "num_proc"    )(parseDoubleRounded)
      free  <- getResource(host, name, "slots"       )(parseDoubleRounded)
      run   <- getResource(host, name, "running_proc")(parseDoubleRounded)
    } yield Host(name, cores, free, run)
  }

  // --------------------------------------------------------------------------
  // configuration
  // --------------------------------------------------------------------------

  def app = "qwasted"

  final case class Conf (
    debug: Boolean = false,
    verbose: Boolean = false,
    output: Output = Output.CLI,
    hosts: List[String] = Nil,
    threshold: Double = Conf.Default.threshold,
    full: Boolean = false,
    summary: Boolean = false,
  ) extends Config

  object Conf extends ConfCompanion {
    def default = Conf()

    object Default {
      val threshold = 0.2
    }
  }

  def parser = new OptionParser[Conf](app) {
    head(app, BuildInfo.version)

    note("Shows how much CPU resources are wasted. This tool is Nagios-aware,")
    note("see example below.\n")

    arg[String]("<host>...")
      .unbounded()
      .optional()
      .action((host, c) => c.copy(hosts = c.hosts :+ host))
      .text("hosts to check, defaults to all")

    note("\nTHRESHOLDS\n")

    opt[Int]('t', "threshold")
      .valueName("<value>")
      .action((threshold, c) => c.copy(threshold = threshold))
      .text(
        s"wasted threshold in percent, defaults to ${Conf.Default.threshold}"
      )

    note("\nOUTPUT MODES\n")

    opt[Output]('o', "output")
      .action((output, c) => c.copy(output = output))
      .text("""output mode, "cli" or "nagios", defaults to "cli"""")

    opt[Unit]('f', "full")
      .action((_, c) => c.copy(full = true))
      .text("full output, i.e. print even proper utilization")

    opt[Unit]("summary")
      .action((_, c) => c.copy(summary = true))
      .text("sums up hosts")

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
    note("  Checks all hosts, individually:")
    note("")
    note("    qwasted")
    note("")
    note("  Checks only node001")
    note("")
    note("    qwasted node001")
    note("")
    note("  Checks all hosts but sums up:")
    note("")
    note("    qwasted --summary")
    note("")
    note("  Checks only node001 and uses an exit status that Nagios-like")
    note("  monitoring systems can interpret:")
    note("")
    note("    qwasted -o nagios node001")
    note("")
  }

}
