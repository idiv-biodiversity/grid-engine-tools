package grid.engine

import cats.Eq

object Utils {

  /** Returns grouped partitions.
    *
    * {{{
    * scala> import cats.implicits._
    *
    * scala> Utils.group(Vector.tabulate(0)(x => (x,x % 2)).sortBy(_._2))(_._2)
    * res0: List[(Int, Seq[(Int, Int)])] = List()
    *
    * scala> Utils.group(Vector.tabulate(1)(x => (x,x % 2)).sortBy(_._2))(_._2)
    * res1: List[(Int, Seq[(Int, Int)])] = List((0,Vector((0,0))))
    *
    * scala> Utils.group(Vector.tabulate(8)(x => (x,x % 2)).sortBy(_._2))(_._2)
    * res2: List[(Int, Seq[(Int, Int)])] = List((0,Vector((0,0), (2,0), (4,0), (6,0))), (1,Vector((1,1), (3,1), (5,1), (7,1))))
    * }}}
    */
  // TODO no universal equals
  def group[A, B: Eq](as: Seq[A])(p: A => B): List[(B,Seq[A])] = {
    val s = as.size

    var i = 0

    val buf = collection.mutable.ListBuffer[(B,Seq[A])]()

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

}
