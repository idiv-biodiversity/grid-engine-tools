package grid.engine

import enumeratum.values._

object Nagios extends Nagios

trait Nagios {

  sealed abstract class Severity(val value: Int) extends IntEnumEntry {
    def exit(): Unit
  }

  object Severity extends IntEnum[Severity] {
    val values = findValues

    case object OK       extends Severity(0) { def exit = Nagios.exit.ok       }
    case object WARNING  extends Severity(1) { def exit = Nagios.exit.warning  }
    case object CRITICAL extends Severity(2) { def exit = Nagios.exit.critical }
  }

  object exit {
    def ok       = sys exit 0
    def warning  = sys exit 1
    def critical = sys exit 2
    def unknown  = sys exit 3
  }

}
