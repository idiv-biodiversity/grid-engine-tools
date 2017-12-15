package grid.engine

import cats.instances.all._

object Accounting extends Accounting

trait Accounting {

  /** Returns qacct entry separator line. */
  val seperatorLine = "=============================================================="

  def accounting: Iterator[String] =
    Source.stdin.getLines

  // TODO this is inefficient in memory processing, will explode with large accounting databases
  def grouped: Iterator[List[String]] = {
    import collection.mutable.ListBuffer

    val buf = ListBuffer[List[String]]()
    var cur = ListBuffer[String]()

    accounting foreach { line =>
      if (line.startsWith(seperatorLine)) {
        if (cur.nonEmpty)
          buf += cur.toList

        cur = new ListBuffer[String]()
      }

      cur += line
    }

    if (cur.nonEmpty)
      buf += cur.toList

    buf.toIterator
  }

  def failure(job: (Iterable[String])): Boolean =
    !success(job)

  def success(job: Iterable[String]): Boolean = {
    def a = job exists { line =>
      val split = line.split(" ").filter(_.nonEmpty)
      split.head === "exit_status" && split.last === "0"
    }

    def b = job exists { line =>
      val split = line.split(" ").filter(_.nonEmpty)
      split.head === "failed" && split.last === "0"
    }

    a && b
  }

  def failed: Iterator[List[String]] =
    grouped filter failure

  def successful: Iterator[List[String]] =
    grouped filter success

}
