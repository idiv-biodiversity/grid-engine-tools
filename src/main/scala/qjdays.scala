package grid.engine

import cats.instances.all._
import sys.process._
import util.{Try, Success, Failure}
import xml._

object qjdays extends App {
  final case class ADT(owner: String, runtime: Int)

  object ADT {
    def apply(owner: String, runtime: Option[Int]): Option[ADT] = runtime match {
      case Some(runtime) => Some(ADT(owner, runtime))
      case None          => None
    }
  }

  def emptyStringOption(s: String): Option[String] =
    if (s.isEmpty) None else Some(s)

  def jobIDs: Stream[String] =
    ("""qstat -s r -u *"""             #|
     Seq("awk", "NR > 2 { print $1 }") #|
     """sort -u""").lineStream

  def jobAnalysis(id: String): Option[ADT] = for {
    qstatjxml <- Try(XML.loadString(s"""qstat -j $id -xml""".!!)) match {
      case Success(value) => Some(value)
      case Failure(error) =>
        Console.err.println(s"""excluded $id due to: $error""")
        None
    }
    owner <- emptyStringOption((qstatjxml \\ "JB_owner").text)
    hardRequests = qstatjxml \\ "JB_hard_resource_list" \\ "element"
    runtime <- hardRequests.find(el => (el \ "CE_name").text === "h_rt").map(el => (el \ "CE_stringval").text.toInt)
  } yield ADT(owner, runtime)

  jobIDs.map(jobAnalysis).seq.flatten.groupBy(_.runtime).mapValues {
    _.map(_.owner).distinct
  }.toSeq.sortBy(_._1) map {
    case (seconds,owners) =>
      val days = (seconds.toDouble / 60 / 60 / 24).round
      val ownerList = owners.mkString(", ")
      s"""h_rt = $seconds\tdays ~ $days\tusers = $ownerList"""
  } foreach println
}
