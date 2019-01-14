package grid.engine

import cats.Eq
import cats.instances.string._
import scala.collection.immutable.TreeMap
import scala.sys.process._

object qrunnerstats extends GETool {

  def run(implicit conf: Conf): Unit = {
    val qstat = "qstat -xml -ext -s r -u *"
    val xml = XML.loadString(qstat.!!)

    val userStats: TreeMap[String, Stats] =
      (xml \\ "job_list").foldLeft(TreeMap[String, Stats]()) {
        case (acc, job) =>
          val project = (job \ "JB_project").text

          if (conf.project.fold(true)(_ === project)) {
            val owner = (job \ "JB_owner").text
            val slots = (job \ "slots").text.toInt

            val stats = Stats(1, slots)

            val merged = acc.get(owner).foldLeft(stats)(_ + _)

            acc.updated(owner, merged)
          } else {
            acc
          }
      }

    val table = Table(Sized("user", "jobs", "cores"))

    table.alignments(1) = Table.Alignment.Right
    table.alignments(2) = Table.Alignment.Right

    for ((user, Stats(jobs, slots)) ← userStats.toSeq.sortBy(- _._2.slots)) {
      table.rows += Sized(user, s"$jobs", s"$slots")
    }

    table.print()
  }

  // --------------------------------------------------------------------------
  // data
  // --------------------------------------------------------------------------

  final case class Stats(jobs: Int, slots: Int) {
    def +(other: Stats): Stats = Stats(jobs + other.jobs, slots + other.slots)
  }

  // --------------------------------------------------------------------------
  // configuration
  // --------------------------------------------------------------------------

  def app = "qrunnerstats"

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

    note("List running job statistics.\n")

    note("FILTERS\n")

    opt[String]('p', "project")
      .valueName("<name>")
      .action((name, c) => c.copy(project = Some(name)))
      .text("filter by project")

    note("\nOTHER OPTIONS\n")

    help('?', "help").text("show this usage text")

    version("version").text("show version")

    note("")
  }

}
