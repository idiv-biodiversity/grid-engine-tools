package grid.engine

import annotation.tailrec
import collection.immutable.TreeMap
import sys.process._

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

  val projectArg: Option[String] = optLast("-p", "--project") {
    arg => Some(arg)
  }

  val cmd = projectArg match {
    case Some(project) =>
      "qstat -ext -s r -u *" #| Seq("awk","""NR > 2 && $6 == "%s" { print $5, $19 }""".format(project))

    case None =>
      "qstat -s r -u *" #| Seq("awk","NR > 2 { print $4, $9 }")
  }

  // TODO tasks are not considered
  val userStats: Map[String,Stats] = cmd.lineStream.foldLeft(TreeMap[String,Stats]()) { case (acc,line) =>
    val tokens = line split " "
    val user = tokens(0)
    val slots = tokens(1).toInt

    val stats = Stats(1, slots)

    val merged = acc.get(user).foldLeft(stats)(_ + _)

    acc.updated(user,merged)
  }

  userStats foreach {
    case (user,stats) =>
      println(f"""$user%-12s ${stats.jobs}%10d ${stats.slots}%10d""")
  }

  // -------------------------------------------------------------------------------------------------
  // ADT
  // -------------------------------------------------------------------------------------------------

  case class Stats(jobs: Int, slots: Int) {
    def +(other: Stats) = Stats(jobs + other.jobs, slots + other.slots)
  }
}
