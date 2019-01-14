package grid.engine

import cats.Eq
import enumeratum.values._

object Nagios extends Nagios

trait Nagios {

  sealed abstract class Severity(val value: Int) extends IntEnumEntry {
    def exit(): Nothing
    def prefix: String

    final def println(msg: String): this.type = {
      Console.out.println(s"$prefix $msg")
      this
    }
  }

  object Severity extends IntEnum[Severity] {
    val values = findValues

    case object OK extends Severity(0) {
      def prefix = "OK"
      def exit: Nothing =
        Nagios.exit.ok
    }

    case object WARNING  extends Severity(1) {
      def prefix = "WARNING"
      def exit(): Nothing =
        Nagios.exit.warning
    }

    case object CRITICAL extends Severity(2) {
      def prefix = "CRITICAL"
      def exit(): Nothing =
        Nagios.exit.critical
    }
  }

  object exit {
    def ok: Nothing =
      sys exit 0

    def warning: Nothing =
      sys exit 1

    def critical: Nothing =
      sys exit 2

    def unknown: Nothing =
      sys exit 3
  }

  // --------------------------------------------------------------------------
  // for command line arguments
  // --------------------------------------------------------------------------

  sealed trait Output

  object Output {
    case object CLI    extends Output
    case object Nagios extends Output

    implicit val eq: Eq[Output] = Eq.fromUniversalEquals

    implicit val OutputRead: scopt.Read[Output] =
      scopt.Read reads {
        _.toLowerCase match {
          case "nagios" => Output.Nagios
          case "cli" => Output.CLI
        }
      }
  }

}
