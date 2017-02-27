package grid.engine

object `qacct-success` extends App with Accounting with Signal {

  exit on SIGPIPE

  // -----------------------------------------------------------------------------------------------
  // help / usage
  // -----------------------------------------------------------------------------------------------

  if (List("-?", "-h", "-help", "--help") exists args.contains) {
    Console.println(s"""
      |Usage: qacct -j ... | qacct-success
      |
      |Displays only successful jobs.
      |
      |  -? | -h | -help | --help            print this help
    """.stripMargin)
    sys exit 0
  }

  // -----------------------------------------------------------------------------------------------
  // main
  // -----------------------------------------------------------------------------------------------

  successful.flatten foreach println

}
