package grid.engine

import scala.sys.process._
import scalax.cli.Memory

object `qquota-nice` extends GETool {

  // --------------------------------------------------------------------------
  // main
  // --------------------------------------------------------------------------

  def run(implicit conf: Conf): Unit = {
    val cmd = ListBuffer("qquota", "-xml")

    if (conf.users.nonEmpty) {
      for (user ← conf.users) {
        cmd += "-u" += user
      }
    }

    log.debug(s"""qquota cmd: ${cmd.mkString(" ")}""")

    val xml = XML.loadString(cmd.!!)

    val entries: Seq[(String, ResourceEntry)] = for {
      rule <- xml \\ "qquota_rule"
      rulename = (rule \ "@name").text
      user = (rule \ "users").text
      limitxml = (rule \ "limit")
      resource = (limitxml \ "@resource").text
      limit = (limitxml \ "@limit").text
      value = (limitxml \ "@value").text
    } yield {
      val entry = resource match {
        case "h_rt" =>
          new TimeEntry(user, resource, limit, value)

        case "h_vmem" =>
          new MemoryEntry(user, resource, limit, value)

        case "slots" =>
          new NumberEntry(user, resource, limit.toDouble, value.toDouble)
      }

      (rulename, entry)
    }

    for ((rule, entries) ← entries.groupBy(_._1).toSeq.sortBy(_._1)) {
      val table = Table(Sized(rule, "resource", "current", "limit"))

      table.alignments(2) = Table.Alignment.Right
      table.alignments(3) = Table.Alignment.Right

      // TODO add colors
      for ((_, entry) ← entries.sortBy(- _._2.value)) {
        import entry._
        table.rows += Sized(user, resource, humanValue, humanLimit)
      }

      table.print()

      println()
    }
  }

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
    def humanLimit: String
    def humanValue: String
  }

  final case class NumberEntry (
    user: String,
    resource: String,
    limit: Double,
    value: Double,
  ) extends ResourceEntry {
    def humanLimit: String = s"${limit.round}"
    def humanValue: String = s"${value.round}"
  }

  final case class MemoryEntry (
    user: String,
    resource: String,
    inputLimit: String,
    inputValue: String,
  ) extends ResourceEntry {
    lazy val value: Double = Memory.dehumanize(inputValue).getOrElse(0.0)
    lazy val limit: Double = Memory.dehumanize(inputLimit).getOrElse(0.0)
    lazy val humanLimit = Memory.humanize(limit.round)
    lazy val humanValue = Memory.humanize(value.round)
  }

  final case class TimeEntry (
    user: String,
    resource: String,
    humanLimit: String,
    humanValue: String,
  ) extends ResourceEntry {
    lazy val limit = {
      val tokens = humanLimit.split(":").toList.map(_.toLong).reverse
      tokens.zip(TimeEntry.timeSteps).map(ab => ab._1 * ab._2).sum.toDouble
    }

    lazy val value = {
      val tokens = humanValue.split(":").toList.map(_.toLong).reverse
      tokens.zip(TimeEntry.timeSteps).map(ab => ab._1 * ab._2).sum.toDouble
    }
  }

  object TimeEntry {
    val timeSteps: List[Long] = List(1L, 60L, 3600L, 86400L)
  }

  // --------------------------------------------------------------------------
  // configuration
  // --------------------------------------------------------------------------

  def app = "qquota-nice"

  final case class Conf (
    debug: Boolean = false,
    verbose: Boolean = false,
    users: Vector[String] = Vector(),
  ) extends Config

  object Conf extends ConfCompanion {
    def default = Conf()
  }

  def parser = new OptionParser[Conf](app) {
    head(app, BuildInfo.version)

    note("Show quotas.\n")

    opt[String]('u', "user")
      .unbounded()
      .action((user, c) => c.copy(users = c.users :+ user))
      .text("users to check, defaults to all")

    opt[Unit]("verbose")
      .action((_, c) => c.copy(verbose = true))
      .text("show verbose output")

    note("\nOTHER OPTIONS\n")

    opt[Unit]("debug")
      .hidden()
      .action((_, c) => c.copy(debug = true))
      .text("show debug output")

    help('?', "help").text("show this usage text")

    version("version").text("show version")

    note("")
  }

}
