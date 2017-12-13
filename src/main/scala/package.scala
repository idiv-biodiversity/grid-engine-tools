package grid

import cats.syntax.EqSyntax

package object engine extends EqSyntax {

  type Seq[A] = scala.collection.immutable.Seq[A]
  val  Seq    = scala.collection.immutable.Seq

  type IndexedSeq[A] = scala.collection.immutable.IndexedSeq[A]
  val  IndexedSeq    = scala.collection.immutable.IndexedSeq

}
