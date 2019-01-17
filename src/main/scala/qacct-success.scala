package grid.engine

object `qacct-success` extends GETool with Accounting with Signal {

  exit on SIGPIPE

  // --------------------------------------------------------------------------
  // main
  // --------------------------------------------------------------------------

  def run(implicit conf: Conf): Unit = {
    successful.flatten foreach println
  }

  // --------------------------------------------------------------------------
  // configuration
  // --------------------------------------------------------------------------

  def app = "qacct-success"

  final case class Conf (
    debug: Boolean = false,
    verbose: Boolean = false,
  ) extends Config

  object Conf extends ConfCompanion {
    def default = Conf()
  }

  def parser = new OptionParser[Conf](app) {
    head(app, BuildInfo.version)

    note("Show only successful jobs.")

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
    note("EXAMPLES")
    note("")
    note("  This command is intended to be used in a qacct pipe:")
    note("")
    note("    qacct -j -o $USER | qacct-success")
    note("")
  }

}
