package grid.engine

import sys.process._
import util.Try
import xml._

object qload extends App with Signal {

  exit on SIGPIPE

  object Default {
    val lower = 0.8
    val upper = 1.1
  }

  // -------------------------------------------------------------------------------------------------
  // help / usage
  // -------------------------------------------------------------------------------------------------

  if (List("-?", "-h", "-help", "--help") exists args.contains) {
    Console.println(s"""
  |Usage: qload [-s] [-l mod] [-u mod] [node [node [..]]]
  |
  |List overloaded and underutilized compute nodes.
  |
  |    -? | -h | -help | --help            print this help
  |    -s                                  short output (affected nodes only)
  |    -l mod                              underutilization threshold modifier
  |                                        default: ${Default.lower}
  |    -u mod                              overload threshold modifier
  |                                        default: ${Default.upper}
  |    node                                apply to this node, multiple allowed
  |                                        default: all
  """.stripMargin)
    sys exit 0
  }

  // -------------------------------------------------------------------------------------------------
  // config
  // -------------------------------------------------------------------------------------------------

  case class Conf(short: Boolean, nodes: Seq[String], lower: Double, upper: Double)

  object Conf {
    object Double {
      def unapply(s: String): Option[Double] =
        Try(s.toDouble).toOption
    }
  }

  val conf = {
    def accumulate(conf: Conf)(args: List[String]): Conf = args match {
      case Nil ⇒
        conf

      case "-s" :: tail ⇒
        accumulate(conf.copy(short = true))(tail)

      case "-l" :: Conf.Double(mod) :: tail ⇒
        accumulate(conf.copy(lower = mod))(tail)

      case "-u" :: Conf.Double(mod) :: tail ⇒
        accumulate(conf.copy(upper = mod))(tail)

      case node :: tail ⇒
        accumulate(conf.copy(nodes = conf.nodes :+ node))(tail)
    }

    accumulate(Conf(false, Vector(), Default.lower, Default.upper))(args.toList)
  }

  // -------------------------------------------------------------------------------------------------
  // main
  // -------------------------------------------------------------------------------------------------

  val qhost = if (conf.nodes.isEmpty)
    """qhost -xml -j -F load_short"""
  else
    s"""qhost -xml -j -F load_short -h ${conf.nodes.mkString(",")}"""

  val qhostxml = XML.loadString(qhost.!!)

  if (!conf.short)
    Console.println("""STAT NODE          LOAD TASKS PERCENT""")

  for {
    node ← qhostxml \ "host"
    name = (node \ "@name").text
    if name != "global"
    tasks = (node \ "job").size
    load ← Resource(node)("load_short").map(_.toDouble)
    if tasks > 0 || load > 1
  } {
    // overloaded
    if (load > tasks * conf.upper) {
      val out = if (conf.short) Output.short else Output.human("OVER")
      Console.println(out(name, load, tasks))
    }

    // underutilized
    if (load < tasks * conf.lower) {
      val out = if (conf.short) Output.short else Output.human("WARN")
      Console.println(out(name, load, tasks))
    }
  }

  object Output {
    val human = (stat: String) ⇒ (name: String, load: Double, tasks: Int) ⇒ {
      val percent = load / tasks * 100
      f"""$stat%-4s $name%-10s $load%7.2f $tasks%5d $percent%7.2f"""
    }

    val short = (name: String, _: Double, _: Int) ⇒
    name
  }

  object Resource {
    def apply(node: Node)(name: String): Option[String] = {
      val data = for {
        value ← node \ "resourcevalue"
        vname = (value \ "@name").text
        if vname == name
      } yield value.text

      data.headOption
    }
  }
}
