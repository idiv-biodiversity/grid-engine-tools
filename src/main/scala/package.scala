package grid

package object engine extends cats.syntax.EqSyntax {

  type Codec = scala.io.Codec
  val  Codec = scala.io.Codec

  type Source = scala.io.Source
  val  Source = scala.io.Source

  type Try[A] = scala.util.Try[A]
  val  Try    = scala.util.Try

  type Success[A] = scala.util.Success[A]
  val  Success    = scala.util.Success

  type Failure[A] = scala.util.Failure[A]
  val  Failure    = scala.util.Failure

  type Seq[A] = scala.collection.immutable.Seq[A]
  val  Seq    = scala.collection.immutable.Seq

  type IndexedSeq[A] = scala.collection.immutable.IndexedSeq[A]
  val  IndexedSeq    = scala.collection.immutable.IndexedSeq

}
