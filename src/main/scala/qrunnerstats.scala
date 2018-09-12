package grid.engine

import cats.Eq
import cats.instances.string._
import scala.annotation.tailrec
import scala.collection.immutable.TreeMap
import scala.sys.process._
import scala.xml._

object qrunnerstats extends App {
  def optLast[A](opts: String*)(convert: String => Option[A])(implicit args: List[String]): Option[A] = {
    val longOpts = opts filter { _ startsWith "--" }
    val longOptsRE = longOpts.mkString("^(","|",")=(.+)").r

    @tailrec def optLastInternal(current: Option[A], args: List[String]): Option[A] = args match {
      case Nil =>
        current

      case opt :: arg :: tail if opts contains opt =>
        optLastInternal(convert(arg), tail)

      case longOptsRE(_, arg) :: tail =>
        optLastInternal(convert(arg), tail)

      case _ :: tail =>
        optLastInternal(current, tail)
    }

    optLastInternal(None, args)
  }

  implicit val arguments = args.toList

  object conf {
    val project: Option[String] = optLast("-p", "--project") {
      arg => Some(arg)
    }
  }

  val cmd = "qstat -xml -ext -s r -u *"
  val qstat = XML.loadString(cmd.!!)

  val userStats: TreeMap[String, Stats] =
    (qstat \\ "job_list").foldLeft(TreeMap[String, Stats]()) {
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

  userStats foreach {
    case (user, stats) =>
      println(f"""$user%-12s ${stats.jobs}%10d ${stats.slots}%10d""")
  }

  // -------------------------------------------------------------------------------------------------
  // ADT
  // -------------------------------------------------------------------------------------------------

  final case class Stats(jobs: Int, slots: Int) {
    def +(other: Stats): Stats = Stats(jobs + other.jobs, slots + other.slots)
  }
}
