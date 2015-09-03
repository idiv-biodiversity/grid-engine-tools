package grid.engine

import sun.misc.{ Signal => JSignal }
import sun.misc.{ SignalHandler => JSignalHandler }

object Signal extends Signal

trait Signal {
  val SIGPIPE = new JSignal("PIPE")

  object exit {
    val ExitHandler: JSignalHandler = new JSignalHandler {
      def handle(signal: JSignal): Unit =
        sys.exit(128 + signal.getNumber)
    }

    def on(signal: JSignal): Unit =
      JSignal.handle(signal, ExitHandler)
  }
}
