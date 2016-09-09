package grid.engine

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
    """.stripMargin)
    sys exit 0
  }

  // -----------------------------------------------------------------------------------------------
  // main
  // -----------------------------------------------------------------------------------------------

  def filtered = accounting filter { line =>
    val first = line.split(" ").filter(_.nonEmpty)(0)
    first == "slots" || first == "ru_wallclock" || first == "cpu"
  }

  def slotsWallclockCPU = filtered.map({ line =>
    line.split(" ").filter(_.nonEmpty).last.toDouble
  }).grouped(3)

  def efficiencies = slotsWallclockCPU.map({
    case slots :: wallclock :: cpu :: Nil =>
      (cpu / slots / wallclock * 10000).round / 100.0

    case _ =>
      0.0 // TODO should never happen
  })

  efficiencies foreach println

}
