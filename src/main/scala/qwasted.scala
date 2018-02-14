package grid.engine

import cats.instances.all._
import sys.process._
import xml._

object qwasted extends App with Config with Nagios {

  object Default {
    val threshold = 0.2
  }

  // -----------------------------------------------------------------------------------------------
  // help / usage
  // -----------------------------------------------------------------------------------------------

  if (List("-?", "-h", "--help") exists args.contains) {
    println(s"""|usage: qwasted [-t value] [--summary] [host...]
                |
                |Shows how much CPU resources are wasted. This tool is Nagios-aware,
                |see example below.
                |
                |FILTERING
                |
                |  host...                     checks only the specified hosts
                |
                |THRESHOLDS
                |
                |  -t value                    wasted threshold in percent
                |                              should be between 0 and 0.5
                |                              default: ${Default.threshold}
                |
                |OUTPUT
                |
                |  -f                          full output even if within threshold
                |
                |  --summary                   sums up hosts
                |
                |  -o {cli|nagios}             output type
                |
                |     cli                      suitable for command line usage (default)
                |     nagios                   suitable for use as a nagios check
                |
                |VERBOSITY
                |
                |  -q                          disables verbose output (default)
                |  -v                          enables verbose output
                |
                |OTHER
                |
                |  -? | -h | --help            print this help
                |
                |EXAMPLES
                |
                |  Checks all hosts, individually:
                |
                |    qwasted
                |
                |  Checks only node001:
                |
                |    qwasted node001
                |
                |  Checks all hosts, but sums up:
                |
                |    qwasted --summary
                |
                |  Checks only node001 and uses an exit status that Nagios-like
                |  monitoring systems can interpret:
                |
                |    qwasted -o nagios node001
                |
                |""".stripMargin)
    sys exit 0
  }

  // -----------------------------------------------------------------------------------------------
  // config
  // -----------------------------------------------------------------------------------------------

  final case class Conf (
    output: Output,
    hosts: List[String],
    threshold: Double,
    full: Boolean,
    summary: Boolean,
    verbose: Boolean
  )

  object Conf {
    def default: Conf =
      Conf(Output.CLI, Nil, Default.threshold, false, false, false)
  }

  val conf: Conf = {
    def accumulate(conf: Conf)(args: List[String]): Conf = args match {
      case Nil =>
        // need to reverse both lists because we built them up backwards
        conf.copy(hosts = conf.hosts.reverse)

      case "-o" :: output :: tail =>
        val o = output match {
          case "cli"    => Output.CLI
          case "nagios" => Output.Nagios
        }

        accumulate(conf.copy(output = o))(tail)

      case "--summary" :: tail =>
        accumulate(conf.copy(summary = true))(tail)

      case "-t" :: ConfigParse.Double(mod) :: tail =>
        accumulate(conf.copy(threshold = mod))(tail)

      case "-f" :: tail =>
        accumulate(conf.copy(full = true))(tail)

      case "-q" :: tail =>
        accumulate(conf.copy(verbose = false))(tail)

      case "-v" :: tail =>
        accumulate(conf.copy(verbose = true))(tail)

      case host :: tail =>
        accumulate(conf.copy(hosts = host :: conf.hosts))(tail)
    }

    accumulate(Conf.default)(args.toList)
  }

  // -----------------------------------------------------------------------------------------------
  // core data structures this app uses
  // -----------------------------------------------------------------------------------------------

  final case class Host(name: String, cores: Long, free: Long, run: Long)

  // -----------------------------------------------------------------------------------------------
  // functions
  // -----------------------------------------------------------------------------------------------

  def parseDoubleRounded(text: String): Either[String, Long] = {
    try {
      Right(text.toDouble.round)
    } catch {
      case e: NumberFormatException =>
        Left(s"not a number: $text")
    }
  }

  def getR[A](xhost: Node, name: String)(f: String => Either[String, A]): Either[String, A] = {
    (xhost \ "resourcevalue") collectFirst {
      case x if (x \ "@name").text === name => x.text
    } match {
      case Some(text) =>
        f(text)
      case None =>
        Left(s"no resource value for $name")
    }
  }

  def getResource[A](xhost: Node, host: String, name: String)(f: String => Either[String, A]): List[A] = {
    getR(xhost, name)(f) match {
      case Left(message) =>
        if (conf.verbose)
          Console.err.println(s"qwasted: $host: $message")
        Nil
      case Right(value) =>
        List(value)
    }
  }

  /** Converts `qhost -xml -q -j` to data structure. It parses the entire XML data structure in one
    * go.
    *
    * Filtering is done here.
    */
  def parse(): Seq[Host] = {
    // TODO allow different resource for running_proc
    val cmd = """qhost -xml -F num_proc,slots,running_proc"""

    val xqhost: Elem = XML.loadString(cmd.!!)

    for {
      xhost <- xqhost \ "host"
      hostname = (xhost  \ "@name").text
      if hostname =!= "global"
      if conf.hosts.isEmpty || (conf.hosts contains hostname)
      cores <- getResource(xhost, hostname, "num_proc"    )(parseDoubleRounded)
      free  <- getResource(xhost, hostname, "slots"       )(parseDoubleRounded)
      run   <- getResource(xhost, hostname, "running_proc")(parseDoubleRounded)
    } yield Host(hostname, cores, free, run)
  }

  // -----------------------------------------------------------------------------------------------
  // main
  // -----------------------------------------------------------------------------------------------

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
