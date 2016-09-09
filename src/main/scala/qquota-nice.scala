package grid.engine

import sys.process._
import xml.XML

object `qquota-nice` extends App with Memory {
  val qquota = """qquota -xml -u *"""

  val xml = XML.loadString(qquota.!!)

  val timeSteps = List(1, 60, 60*60, 60*60*24).map(_.toLong)

  def colorFor(limit: Double, value: Double): String = {
    if (value >= limit)
      Console.RED
    else if (value >= 0.8 * limit)
      Console.MAGENTA
    else if (value >= 0.5 * limit)
      Console.YELLOW
    else
      ""
  }

  trait ResourceEntry {
    def user: String
    def resource: String
    def limit: Double
    def value: Double
  }

  case class NumberEntry(user: String, resource: String, limit: Double, value: Double) extends ResourceEntry {
    override def toString: String = {
      val color = colorFor(limit.toDouble, value.toDouble)

      f"""$color$user%-8s $resource%10s ${value.toLong}%15s ${limit.toLong}%15s${Console.RESET}"""
    }
  }

  case class MemoryEntry(user: String, resource: String, prettyLimit: String, prettyValue: String) extends ResourceEntry {
    lazy val limit: Double = Memory.dehumanize(prettyLimit)
    lazy val value: Double = Memory.dehumanize(prettyValue)

    override def toString: String = {
      val color = colorFor(limit, value)

      f"""$color$user%-8s $resource%10s $prettyValue%15s $prettyLimit%15s${Console.RESET}"""
    }
  }

  case class TimeEntry(user: String, resource: String, prettyLimit: String, prettyValue: String) extends ResourceEntry {
    lazy val limit = {
      val tokens = prettyLimit.split(":").toList.map(_.toLong).reverse
      tokens.zip(timeSteps).map(ab => ab._1 * ab._2).sum.toDouble
    }

    lazy val value = {
      val tokens = prettyValue.split(":").toList.map(_.toLong).reverse
      tokens.zip(timeSteps).map(ab => ab._1 * ab._2).sum.toDouble
    }

    override def toString: String = {
      val color = colorFor(limit, value)

      f"""$color$user%-8s $resource%10s $prettyValue%15s $prettyLimit%15s${Console.RESET}"""
    }
  }

  val entries: Seq[(String,ResourceEntry)] = for {
    rule <- xml \\ "qquota_rule"
    rulename = (rule \ "@name").text
    user = (rule \ "users").text
    limitxml = (rule \ "limit")
    resource = (limitxml \ "@resource").text
    limit = (limitxml \ "@limit").text
    value = (limitxml \ "@value").text
  } yield {
    val entry = resource match {
      case "h_rt" => new TimeEntry(user, resource, limit, value)
      case "h_vmem" => new MemoryEntry(user, resource, limit, value)
      case "slots" => new NumberEntry(user, resource, limit.toDouble, value.toDouble)
    }

    (rulename,entry)
  }

  entries.groupBy(_._1).toSeq.sortBy(_._1) foreach { case (rulename,entries) =>
    println(s"""$rulename:""")
    entries.sortBy(- _._2.value) foreach { case (_,entry) => println(entry) }
    println
  }
}
