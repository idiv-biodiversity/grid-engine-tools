package grid.engine

import collection.JavaConverters._
import com.sun.grid.jgdi._

object qfreeresources extends App with JGDI with Signal {

  exit on SIGPIPE

  // -----------------------------------------------------------------------------------------------
  // help / usage
  // -----------------------------------------------------------------------------------------------

  if (List("-?", "-h", "--help") exists args.contains) {
    println(s"""|usage: qfreeresources
                |
                |Shows free resources.
                |
                |VERBOSITY
                |
                |  -q                          disables verbose output (default)
                |  -v                          enables verbose output
                |
                |OTHER
                |
                |  -? | -h | --help            print this help
                |
                |""".stripMargin)
    sys exit 0
  }

  // -----------------------------------------------------------------------------------------------
  // config
  // -----------------------------------------------------------------------------------------------

  final case class Conf(verbose: Boolean)

  object Conf {
    def default: Conf =
      Conf(verbose = false)
  }

  val conf: Conf = {
    def accumulate(conf: Conf)(args: List[String]): Conf = args match {
      case Nil =>
        conf

      case "-q" :: tail =>
        accumulate(conf.copy(verbose = false))(tail)

      case "-v" :: tail =>
        accumulate(conf.copy(verbose = true))(tail)

      case x :: tail =>
        if (conf.verbose) {
          Console.err.println(s"ignoring argument: $x")
        }

        accumulate(conf)(tail)
    }

    accumulate(Conf.default)(args.toList)
  }

  // -----------------------------------------------------------------------------------------------
  // core data structures this app uses
  // -----------------------------------------------------------------------------------------------

  final case class QIFreeStatus(name: String, state: String, slots: Int, h_vmem_g: Int, psm_nfreectxts: Int) {
    lazy val gPerCore = h_vmem_g / slots

    override def toString: String = {
      val color = if (gPerCore >= 5) Console.GREEN else ""
      f"""$color$name%24s   $slots%6d   $h_vmem_g%6dG   ~$gPerCore%5dG/core   $psm_nfreectxts%4d${Console.RESET}"""
    }
  }

  object QIFreeStatus {
    def apply(qi: monitoring.QueueInstanceSummary): QIFreeStatus = {
      val name = qi.getName
      val state = qi.getState
      val slots = Option(qi.getResourceValue("hc", "slots")).getOrElse({
        if (conf.verbose) {
          Console.err.println(s"""warning: $name: hc:slots undefined, falling back to num_proc""")
        }

        qi.getResourceValue("hl", "num_proc")
      }).toInt

      // TODO parse memory value
      def memPF(mem: String): Int = mem match {
        case null =>
          if (conf.verbose) {
            Console.err.println(s"""warning: $name: hc:h_vmem undefined, falling back to mem_total""")
          }

          val memTotal = qi.getResourceValue("hl", "mem_total")
          memTotal.dropRight(1).toDouble.floor.toInt

        case "0.000" =>
          0

        case mem if mem endsWith "G" =>
          mem.dropRight(1).toDouble.floor.toInt

        case mem if mem endsWith "M" =>
          0
      }

      val memory = memPF(qi.getResourceValue("hc", "h_vmem"))
      val psm_nfreectxts = Option {
        qi.getResourceValue("hl", "psm_nfreectxts")
      } map { _.toInt } getOrElse 0

      new QIFreeStatus(name, state, slots, memory, psm_nfreectxts)
    }
  }

  // -----------------------------------------------------------------------------------------------
  // main
  // -----------------------------------------------------------------------------------------------

  val JGDI = jgdi

  // TODO psm_nfreectxts is custom
  // TODO make tracked resources customizable
  val raf = monitoring.filter.ResourceAttributeFilter.parse("h_vmem,slots,psm_nfreectxts,num_proc,mem_total")
  val qisummaryopts = new monitoring.QueueInstanceSummaryOptions
  qisummaryopts.setResourceAttributeFilter(raf)

  JGDI.getQueueInstanceSummary(qisummaryopts).getQueueInstanceSummary.asScala.map(QIFreeStatus(_)) filter { status =>
    import status._
    slots > 0 && h_vmem_g > 0 && gPerCore > 0 && !state.contains("u") && !state.contains("d")
  } sortBy { - _.slots } foreach println

  JGDI.close()

}
