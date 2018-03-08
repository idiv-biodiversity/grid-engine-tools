package grid.engine

import cats.Eq

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

  implicit val eq: Eq[QueueInstance] = Eq.fromUniversalEquals
}
