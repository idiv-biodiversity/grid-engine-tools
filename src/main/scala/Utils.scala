package grid.engine

import cats.Eq
import cats.instances.string._
import scala.xml.Node

object Utils {

  implicit final class RichDouble(val value: Double) extends AnyVal {
    /** Returns value in percent.
      *
      * {{{
      * scala> import Utils.RichDouble
      *
      * scala> 0.2468.percent(decimals = 1)
      * res0: Double = 24.7
      * }}}
      */
    def percent(decimals: Int): Double =
      (value * 100).roundTo(decimals)

    /** Returns value rounded to one decimal.
      *
      * {{{
      * scala> import Utils.RichDouble
      *
      * scala> 20.06.roundTo(1)
      * res0: Double = 20.1
      * }}}
      */
    def roundTo(decimals: Int): Double = {
      val factor = 10 * decimals
      (value * factor).round.toDouble / factor
    }
  }

  /** Returns grouped partitions.
    *
    * {{{
    * scala> import cats.implicits._
    *
    * scala> Utils.group(Vector.tabulate(0)(x => (x,x % 2)).sortBy(_._2))(_._2)
    * res0: List[(Int, IndexedSeq[(Int, Int)])] = List()
    *
    * scala> Utils.group(Vector.tabulate(1)(x => (x,x % 2)).sortBy(_._2))(_._2)
    * res1: List[(Int, IndexedSeq[(Int, Int)])] = List((0,Vector((0,0))))
    *
    * scala> Utils.group(Vector.tabulate(8)(x => (x,x % 2)).sortBy(_._2))(_._2)
    * res2: List[(Int, IndexedSeq[(Int, Int)])] = List((0,Vector((0,0), (2,0), (4,0), (6,0))), (1,Vector((1,1), (3,1), (5,1), (7,1))))
    * }}}
    */
  def group[A, B: Eq](as: IndexedSeq[A])(p: A => B): List[(B, IndexedSeq[A])] = {
    val s = as.size

    var i = 0

    val buf = collection.mutable.ListBuffer[(B, IndexedSeq[A])]()

    while (i < s) {
      val current = as(i)
      val predicate = p(current)
      val len = as.segmentLength(p(_) === predicate, i)
      val slice = as.slice(from = i, until = i + len)
      buf += (predicate -> slice)
      i += len
    }

    buf.toList
  }

  object XML {
    object QHostAttributeFilter {
      def apply(node: Node)(tag: String, name: String): Option[String] = {
        val data = for {
          value ← node \ tag
          vname = (value \ "@name").text
          if vname === name
        } yield value.text

        data.headOption
      }
    }

    object QHostJob {
      def apply(node: Node)(name: String): Option[String] =
        QHostAttributeFilter(node)("jobvalue", name)
    }

    object QHostQueue {
      def apply(node: Node)(name: String): Option[String] =
        QHostAttributeFilter(node)("queuevalue", name)
    }

    object QHostResource {
      def apply(node: Node)(name: String): Option[String] =
        QHostAttributeFilter(node)("resourcevalue", name)
    }
  }

}
