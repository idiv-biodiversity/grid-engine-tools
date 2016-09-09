package grid.engine

object `qacct-failed` extends App with Accounting with Signal {

  exit on SIGPIPE

  // -----------------------------------------------------------------------------------------------
  // help / usage
  // -----------------------------------------------------------------------------------------------

  if (List("-?", "-h", "-help", "--help") exists args.contains) {
    Console.println(s"""
      |Usage: qacct -j ... | qacct-failed
      |
      |Displays only failed jobs.
      |
      |  -? | -h | -help | --help            print this help
    """.stripMargin)
    sys exit 0
  }

  // -----------------------------------------------------------------------------------------------
  // main
  // -----------------------------------------------------------------------------------------------

  failed.flatten foreach println

}
