package grid.engine

import cats.Eq

trait Config {

  sealed trait Output
  object Output {
    case object CLI    extends Output
    case object Nagios extends Output
    implicit val eq: Eq[Output] = Eq.fromUniversalEquals
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
