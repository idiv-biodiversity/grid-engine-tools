package grid.engine

import cats.instances.all._
import sys.process._
import xml._

object qload extends App with Config with Signal {

  exit on SIGPIPE

  object Default {
    val underutilization = 0.8
    val overload = 1.1
  }

  // -------------------------------------------------------------------------------------------------
  // help / usage
  // -------------------------------------------------------------------------------------------------

  if (List("-?", "-h", "--help") exists args.contains) {
    println(s"""|usage: qload [-s] [-l t] [-u t] [host...]
                |
                |List overloaded and underutilized compute nodes.
                |
                |FILTERING
                |
                |  host...                     checks only the specified hosts
                |
                |THRESHOLDS
                |
                |  -l threshold                underutilization threshold in percent
                |                              should be between 0 and 1
                |                              default: ${Default.underutilization}
                |
                |  -u threshold                overload threshold in percent
                |                              should be greater than 1
                |                              default: ${Default.overload}
                |
                |OUTPUT
                |
                |  -s                          short output (affected nodes only)
                |
                |OTHER
                |
                |  -? | -h | --help            print this help
                |
                |EXAMPLES
                |
                |  Take a look at all problematic hosts:
                |
                |    qhost -j -h $(qload -s) | less
                |
                |""".stripMargin)
    sys exit 0
  }

  // -------------------------------------------------------------------------------------------------
  // config
  // -------------------------------------------------------------------------------------------------

  final case class Conf(short: Boolean, nodes: Seq[String], underutilization: Double, overload: Double)

  val conf = {
    def accumulate(conf: Conf)(args: List[String]): Conf = args match {
      case Nil ⇒
        conf

      case "-s" :: tail ⇒
        accumulate(conf.copy(short = true))(tail)

      case "-l" :: ConfigParse.Double(mod) :: tail ⇒
        accumulate(conf.copy(underutilization = mod))(tail)

      case "-u" :: ConfigParse.Double(mod) :: tail ⇒
        accumulate(conf.copy(overload = mod))(tail)

      case node :: tail ⇒
        accumulate(conf.copy(nodes = conf.nodes :+ node))(tail)
    }

    accumulate(Conf(false, Vector(), Default.underutilization, Default.overload))(args.toList)
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
    if name =!= "global"
    tasks = (node \ "job").size
    load ← Resource(node)("load_short").map(_.toDouble)
    if tasks > 0 || load > 1
  } {
    // overloaded
    if (load > tasks * conf.overload) {
      val out = if (conf.short) Print.short else Print.human("OVER")
      Console.println(out(name, load, tasks))
    }

    // underutilized
    if (load < tasks * conf.underutilization) {
      val out = if (conf.short) Print.short else Print.human("WARN")
      Console.println(out(name, load, tasks))
    }
  }

  object Print {
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
        if vname === name
      } yield value.text

      data.headOption
    }
  }
}
