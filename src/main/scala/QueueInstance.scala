package grid.engine

import cats.Eq
import scopt.Read

final case class QueueInstance(queue: String, host: String) {
  override def toString: String =
    s"$queue@$host"
}

object QueueInstance {
  val regex = """(\S+)@(\S+)""".r

  def unapply(s: String): Option[QueueInstance] = s match {
    case regex(q, h) =>
      Some(QueueInstance(q, h))

    case _ =>
      None
  }

  def unsafe(s: String): QueueInstance = s match {
    case regex(q, h) =>
      QueueInstance(q, h)
  }

  implicit val eq: Eq[QueueInstance] = Eq.fromUniversalEquals

  implicit val QueueInstanceRead: Read[QueueInstance] =
    Read.reads(unsafe)
}
