package grid.engine

import collection.JavaConversions._
import sys.process._
import util._
import xml.XML

import com.sun.grid.jgdi._

object qfreeresources extends App with JGDI with Signal {

  exit on SIGPIPE

  val JGDI = jgdi

  // TODO psm_nfreectxts is custom
  // TODO make tracked resources customizable
  val raf = monitoring.filter.ResourceAttributeFilter.parse("h_vmem,slots,psm_nfreectxts")
  val qisummaryopts = new monitoring.QueueInstanceSummaryOptions
  qisummaryopts.setResourceAttributeFilter(raf)

  JGDI.getQueueInstanceSummary(qisummaryopts).getQueueInstanceSummary.map(QIFreeStatus(_)) filter { status =>
    import status._
    slots > 0 && h_vmem_g > 0 && gPerCore > 0 && !state.contains("u") && !state.contains("d")
  } sortBy { - _.slots } foreach println

  JGDI.close()

  // -----------------------------------------------------------------------------------------------
  // ADT
  // -----------------------------------------------------------------------------------------------

  case class QIFreeStatus(name: String, state: String, slots: Int, h_vmem_g: Int, psm_nfreectxts: Int) {
    lazy val gPerCore = h_vmem_g / slots

    override def toString = {
      val color = if (gPerCore >= 5) Console.GREEN else ""
      f"""$color$name%24s   $slots%6d   $h_vmem_g%6dG   ~$gPerCore%5dG/core   $psm_nfreectxts%4d${Console.RESET}"""
    }
  }

  object QIFreeStatus {
    private def qhostmemcmd(node: String) = s"""qhost -xml -h $node -F h_vmem"""

    def apply(qi: monitoring.QueueInstanceSummary): QIFreeStatus = {
      val name = qi.getName
      val state = qi.getState
      val slots = qi.getResourceValue("hc", "slots").toInt

      def memPF(mem: String): Int = mem match {
        case null if slots == 0 =>
          0

        case null =>
          // Console.err.println(s"working excluding $slots slots of $name because of null memory value")
          val node = name.split("@")(1)
          val xml = XML.loadString(qhostmemcmd(node).!!)
          memPF((xml \\ "resourcevalue").text)

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
}
