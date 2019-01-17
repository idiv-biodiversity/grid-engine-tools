package grid.engine

import cats.instances.string._

import Utils.RichDouble

object `qacct-efficiency` extends GETool with Accounting with Signal {

  exit on SIGPIPE

  // --------------------------------------------------------------------------
  // main
  // --------------------------------------------------------------------------

  def run(implicit conf: Conf): Unit = {
    def prefiltered: Iterator[String] =
      if (conf.successful)
        successful.flatten
      else
        accounting

    def filtered: Iterator[String] =
      prefiltered filter { line =>
        val first = line.split(" ").filter(_.nonEmpty)(0)
        first === "slots" || first === "ru_wallclock" || first === "cpu"
      }

    def slotsWallclockCPU: Iterator[collection.Seq[Double]] =
      filtered.map({ line =>
        line.split(" ").filter(_.nonEmpty).last.toDouble
      }).grouped(3)

    def efficiencies: Iterator[Double] =
      slotsWallclockCPU.map({
        case Seq(slots, wallclock, cpu) if wallclock > 0.0 =>
          (cpu / slots / wallclock).percent(decimals = 2)

        case _ =>
          0.0 // TODO should never happen
      })

    efficiencies foreach println
  }

  // --------------------------------------------------------------------------
  // configuration
  // --------------------------------------------------------------------------

  def app = "qacct-efficiency"

  final case class Conf (
    debug: Boolean = false,
    verbose: Boolean = false,
    successful: Boolean = false,
  ) extends Config

  object Conf extends ConfCompanion {
    def default = Conf()
  }

  def parser = new OptionParser[Conf](app) {
    head(app, BuildInfo.version)

    note("Show job efficiency:")
    note("")
    note("  efficiency = cputime / runtime * 100%")

    note("\nFILTER\n")

    opt[Unit]("successful")
      .action((_, c) => c.copy(successful = true))
      .text("only for successful jobs")

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
    note("  This command is intended to be used in a qacct pipe. Filtering")
    note("  should be done in qacct.")
    note("")
    note("    qacct -j -o $USER | qacct-efficiency")
    note("")
  }

}
