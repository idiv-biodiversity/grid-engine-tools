package grid.engine

object Memory extends Memory

trait Memory {
  val kibi = math.pow(2,10)
  val mebi = math.pow(2,20)
  val gibi = math.pow(2,30)
  val tebi = math.pow(2,40)
  val pebi = math.pow(2,50)
  val exbi = math.pow(2,60)
  val zebi = math.pow(2,70)

  object humanize {
    def apply(bytes: Double): String = bytes match {
      case _ if bytes.abs < kibi => f"""${bytes.round}%5d    """
      case _ if bytes.abs < mebi => f"""${(bytes/kibi).round}%5d KiB"""
      case _ if bytes.abs < gibi => f"""${(bytes/mebi).round}%5d MiB"""
      case _ if bytes.abs < tebi => f"""${(bytes/gibi).round}%5d GiB"""
      case _ if bytes.abs < pebi => f"""${(bytes/tebi).round}%5d TiB"""
      case _ if bytes.abs < exbi => f"""${(bytes/pebi).round}%5d PiB"""
      case _ if bytes.abs < zebi => f"""${(bytes/exbi).round}%5d EiB"""
      case _                     => f"""${(bytes/zebi).round}%5d ZiB"""
    }
  }

  object dehumanize {
    val k = """(-?[0123456789.]+)K""".r
    val m = """(-?[0123456789.]+)M""".r
    val g = """(-?[0123456789.]+)G""".r
    val t = """(-?[0123456789.]+)T""".r
    val p = """(-?[0123456789.]+)P""".r
    val e = """(-?[0123456789.]+)E""".r
    val z = """(-?[0123456789.]+)Z""".r

    def apply(s: String): Double = s match {
      case "0.000"  => 0.0
      case k(value) => value.toDouble * kibi
      case m(value) => value.toDouble * mebi
      case g(value) => value.toDouble * gibi
      case t(value) => value.toDouble * tebi
      case p(value) => value.toDouble * pebi
      case e(value) => value.toDouble * exbi
      case z(value) => value.toDouble * zebi
    }
  }
}
