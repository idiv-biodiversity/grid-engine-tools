package grid.engine

import java.io.PrintStream

/** A generic grid engine tool application. */
abstract class GETool extends App {

  /** Returns the application name. */
  def app: String

  /** Runs this application. */
  def run(implicit conf: Conf): Unit

  // --------------------------------------------------------------------------
  // configuration
  // --------------------------------------------------------------------------

  /** The basic configuration type. */
  trait Config {
    def debug: Boolean
    def verbose: Boolean
  }

  /** The concrete configuration type. */
  type Conf <: Config

  /** The configuration companion type. */
  trait ConfCompanion {
    def default: Conf
  }

  /** Returns the configuration companion object. */
  def Conf: ConfCompanion

  // --------------------------------------------------------------------------
  // command line argument parsing
  // --------------------------------------------------------------------------

  /** Returns the command line argument parser. */
  def parser: OptionParser[Conf]

  // parses command line arguments and runs the application
  parser.parse(args, Conf.default) match {
    case Some(conf) =>
      run(conf)

    case None =>
  }

  // --------------------------------------------------------------------------
  // logging
  // --------------------------------------------------------------------------

  /** Returns the logging object. */
  object log {

    /** Prints message if debug output is enabled. */
    def debug(message: String, stream: PrintStream = Console.err)
      (implicit conf: Conf): Unit =
      if (conf.debug) stream.println(s"""[debug] $message""")

    /** Prints message if verbose output is enabled. */
    def verbose(message: String, stream: PrintStream = Console.err)
      (implicit conf: Conf): Unit =
      if (conf.verbose) stream.println(s"""$app: $message""")

  }

}
