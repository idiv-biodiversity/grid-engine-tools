package grid.engine

import sys.process._
import util.Try
import xml._

// TODO default thresholds
// TODO colors instead of prefix
object qmemload extends App with Memory {

  // -------------------------------------------------------------------------------------------------
  // help / usage
  // -------------------------------------------------------------------------------------------------

  if (List("-?", "-h", "-help", "--help") exists args.contains) {
    Console.println("""
  |Usage: qload [-c|-s] [-l mod] [-u mod] [node [node [..]]]
  |
  |List overloaded and underutilized compute nodes.
  |
  |    -? | -h | -help | --help            print this help
  |    -s                                  short output (affected nodes only)
  |    -l mod                              underutilization threshold modifier
  |    -u mod                              overload threshold modifier
  |       node                             apply to this node, multiple allowed, default is all
  """.stripMargin)
    sys exit 0
  }

  // -------------------------------------------------------------------------------------------------
  // config
  // -------------------------------------------------------------------------------------------------

  case class Conf(short: Boolean, nodes: Seq[String], lower: Option[Double], upper: Option[Double])

  val conf = {
    def accumulate(conf: Conf)(args: List[String]): Conf = args match {
      case Nil =>
        conf

      case "-s" :: tail =>
        accumulate(conf.copy(short = true))(tail)

      case "-l" :: mod :: tail =>
        accumulate(conf.copy(lower = Try(dehumanize(mod)).toOption))(tail)

      case "-u" :: mod :: tail =>
        accumulate(conf.copy(upper = Try(dehumanize(mod)).toOption))(tail)

      case node :: tail =>
        accumulate(conf.copy(nodes = conf.nodes :+ node))(tail)
    }

    accumulate(Conf(false, Nil, None, None))(args.toList)
  }

  // -------------------------------------------------------------------------------------------------
  // main
  // -------------------------------------------------------------------------------------------------

  val qhost = if (conf.nodes.isEmpty)
    """qhost -xml -j -F h_vmem,virtual_free"""
  else
    s"""qhost -xml -j -F h_vmem,virtual_free -h ${conf.nodes.mkString(",")}"""

  val qhostxml = XML.loadString(qhost.!!)

  if (!conf.short)
    Console.println("""STAT NODE         PHYSICAL CONSUMABLE""")

  for {
    node <- qhostxml \ "host"
    name = Name(node)
    if name != "global"
    virtual_free <- Resource(node)("virtual_free") map dehumanize.apply
    h_vmem <- Resource(node)("h_vmem") map dehumanize.apply
    lower = if (conf.lower.isDefined) conf.lower.get else 0.0
    upper = if (conf.upper.isDefined) conf.upper.get else 0.0
  } if (virtual_free + upper < h_vmem) { // overloaded
    val out = if (conf.short) Output.short else Output.human("OVER")
    Console.println(out(name, humanize(virtual_free), humanize(h_vmem)))
  } else if (virtual_free - lower > h_vmem) { // underutilized
    val out = if (conf.short) Output.short else Output.human("WARN")
    Console.println(out(name, humanize(virtual_free), humanize(h_vmem)))
  }

  object Output {
    val human = (status: String) => (name: String, load: String, tasks: String) =>
      f"""$status%-4s $name%-10s $load%10s $tasks%10s"""

    val short = (name: String, _: String, _: String) =>
      name
  }

  object Name {
    def apply(node: Node): String = (node \ "@name").text
  }

  object Resource {
    def apply(node: Node)(name: String): Option[String] = {
      val data = for {
        value <- node \ "resourcevalue"
        vname = (value \ "@name").text
        if vname == name
      } yield value.text

      data.headOption
    }
  }
}
