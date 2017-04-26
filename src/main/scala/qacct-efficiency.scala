package grid.engine

import cats.instances.all._

object `qacct-efficiency` extends App with Accounting with Signal {

  exit on SIGPIPE

  // -----------------------------------------------------------------------------------------------
  // help / usage
  // -----------------------------------------------------------------------------------------------

  if (List("-?", "-h", "-help", "--help") exists args.contains) {
    Console.println(s"""
      |Usage: qacct -j ... | qacct-efficiency
      |
      |Displays job efficiency:
      |
      |    efficiency = cpu / slots / ru_wallclock * 100%
      |
      |  -? | -h | -help | --help            print this help
      |  -s | --success                      only print efficiencies for successful jobs
    """.stripMargin)
    sys exit 0
  }

  // -------------------------------------------------------------------------------------------------
  // config
  // -------------------------------------------------------------------------------------------------

  case class Conf(successful: Boolean)

  val conf = {
    def accumulate(conf: Conf)(args: List[String]): Conf = args match {
      case Nil =>
        conf

      case "-s" :: tail =>
        accumulate(conf.copy(successful = true))(tail)

      case "--success" :: tail =>
        accumulate(conf.copy(successful = true))(tail)

      case x :: tail =>
        Console.err.println(s"""Don't know what to do with argument "$x".""")
        accumulate(conf)(tail)
    }

    accumulate(Conf(false))(args.toList)
  }

  // -----------------------------------------------------------------------------------------------
  // main
  // -----------------------------------------------------------------------------------------------

  def prefiltered: Iterator[String] = if (conf.successful)
    successful.flatten
  else
    accounting

  def filtered: Iterator[String] = prefiltered filter { line =>
    val first = line.split(" ").filter(_.nonEmpty)(0)
    first === "slots" || first === "ru_wallclock" || first === "cpu"
  }

  def slotsWallclockCPU: Iterator[Seq[Double]] = filtered.map({ line =>
    line.split(" ").filter(_.nonEmpty).last.toDouble
  }).grouped(3)

  def efficiencies: Iterator[Double] = slotsWallclockCPU.map({
    case Seq(slots, wallclock, cpu) =>
      (cpu / slots / wallclock * 10000).round / 100.0

    case _ =>
      0.0 // TODO should never happen
  })

  efficiencies foreach println

}
