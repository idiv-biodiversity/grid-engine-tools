package grid.engine

import cats.instances.string._
import scala.collection.immutable.TreeMap
import scala.sys.process._

object qwaiterstats extends GETool {

  def run(implicit conf: Conf): Unit = {
    val qstat = "qstat -xml -ext -s p -u *"
    val xml = XML.loadString(qstat.!!)

    val userStats: TreeMap[String, Stats] =
      (xml \\ "job_list")
        .flatMap(Stats.fromXML)
        .foldLeft(TreeMap[String, Stats]()) {
          case (acc, (user, stats)) =>
            val merged = acc.get(user).foldLeft(stats)(_ + _)
            acc.updated(user, merged)
        }

    val runners = (
      "qstat -s r -u *" #|
        Seq("awk", "NR > 2 { print $4 }") #|
        Seq("sort", "-u")
    ).lineStream.toArray

    val table = Table(Sized("user", "jobs", "cores", "runners"))

    table.alignments(1) = Table.Alignment.Right
    table.alignments(2) = Table.Alignment.Right

    for ((user, Stats(jobs, slots)) ← userStats.toSeq.sortBy(- _._2.slots)) {
      // TODO when fansi in scala-cli-tools
      // val u = if (runners contains user)
      //   user
      // else
      //   s"""${Console.RED}${user}${Console.RESET}"""

      val status = if (runners contains user) "" else "no"

      table.rows += Sized(user, s"$jobs", s"$slots", status)
    }

    table.print()
  }

  // --------------------------------------------------------------------------
  // data
  // --------------------------------------------------------------------------

  final case class Stats(jobs: Int, slots: Int) {
    def +(other: Stats): Stats = Stats(jobs + other.jobs, slots + other.slots)
  }

  object Stats {
    def fromXML(xml: Node)(implicit conf: Conf): Option[(String, Stats)] = {
      val project = (xml \ "JB_project").text
      val state = (xml \ "state").text

      if (
        conf.project.fold(true)(_ === project) &&
          !state.startsWith("h") &&
          !state.startsWith("E")
      ) {
        val owner = (xml \ "JB_owner").text
        val slots = (xml \ "slots").text.toInt

        val tasks = (xml \ "tasks").text match {
          case Tasks(from, to, increment) ⇒
            Range.count(from, to, increment, isInclusive = true)

          case _ ⇒
            1
        }

        val stats = Stats(tasks, slots * tasks)

        Some(owner → stats)
      } else {
        None
      }
    }
  }

  // --------------------------------------------------------------------------
  // extractors
  // --------------------------------------------------------------------------

  object Tasks {
    val Regex = """(\d+)-(\d+):(\d+)""".r

    def unapply(s: String): Option[(Int, Int, Int)] = s match {
      case Regex(from, to, incr) =>
        Some((from.toInt, to.toInt, incr.toInt))

      case _ =>
        None
    }
  }

  // --------------------------------------------------------------------------
  // configuration
  // --------------------------------------------------------------------------

  def app = "qwaiterstats"

  final case class Conf (
    debug: Boolean = false,
    verbose: Boolean = false,
    project: Option[String] = None,
  ) extends Config

  object Conf extends ConfCompanion {
    def default = Conf()
  }

  def parser = new OptionParser[Conf](app) {
    head(app, BuildInfo.version)

    note("List waiting job statistics.\n")

    note("FILTERS\n")

    opt[String]('p', "project")
      .valueName("<name>")
      .action((name, c) => c.copy(project = Some(name)))
      .text("filter by project")

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
