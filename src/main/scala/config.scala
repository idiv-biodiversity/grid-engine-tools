package grid.engine

import cats.Eq

trait Config {

  sealed trait Output
  object Output {
    case object CLI    extends Output
    case object Nagios extends Output
    implicit val eq: Eq[Output] = Eq.fromUniversalEquals
  }

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

  object ConfigParse {
    object Int {
      def unapply(s: String): Option[Int] =
        Try(s.toInt).toOption
    }

    object Double {
      def unapply(s: String): Option[Double] =
        Try(s.toDouble).toOption
    }
  }

}
