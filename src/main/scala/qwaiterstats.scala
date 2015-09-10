package grid.engine

import collection.immutable.TreeMap
import sys.process._
import util.control.Exception._
import annotation.tailrec

object qwaiterstats extends App with Signal {

  exit on SIGPIPE

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
      "qstat -ext -s p -u *" #| Seq("awk","""NR > 2 && $6 == "%s" && $8 !~ /^(h|E)qw$/ { print $5, $15, $16 }""".format(project))

    case None =>
      "qstat -s p -u *" #| Seq("awk","NR > 2 && $5 !~ /^(h|E)qw$/ { print $4, $8, $9 }")
  }

  val runners = ("qstat -s r -u *" #| Seq("awk","NR > 2 { print $4 }") #| Seq("sort","-u")).lineStream.toArray

  val userStats: Map[String,Stats] = cmd.lineStream.foldLeft(TreeMap[String,Stats]()) { case (acc,line) =>
    val tokens = line split " "
    val user = tokens(0)
    val slots = tokens(1).toInt

    val tasks = catching(classOf[ArrayIndexOutOfBoundsException]) opt tokens(2) collect {
      case Tasks(from, to, incr) =>
        Range.count(from, to, incr, true)
    } getOrElse 1

    val stats = Stats(tasks, slots * tasks)

    val merged = acc.get(user).foldLeft(stats)(_ + _)

    acc.updated(user,merged)
  }

  userStats foreach {
    case (user,stats) =>
      val color = if (!runners.contains(user))
        Console.RED
      else
        ""

      println(f"""$color$user%-12s ${stats.jobs}%10d ${stats.slots}%10d${Console.RESET}""")
  }

  // -------------------------------------------------------------------------------------------------
  // ADT
  // -------------------------------------------------------------------------------------------------

  case class Stats(jobs: Int, slots: Int) {
    def +(other: Stats) = Stats(jobs + other.jobs, slots + other.slots)
  }

  // -------------------------------------------------------------------------------------------------
  // extractors
  // -------------------------------------------------------------------------------------------------

  object Tasks {
    val Regex = """(\d+)-(\d+):(\d+)""".r

    def unapply(s: String): Option[(Int,Int,Int)] = s match {
      case Regex(from, to, incr) =>
        Some((from.toInt, to.toInt, incr.toInt))

      case _ =>
        None
    }
  }
}
