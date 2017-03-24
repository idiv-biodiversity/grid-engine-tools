package grid.engine

import enumeratum._

sealed abstract class QueueState(override val entryName: String) extends EnumEntry

object QueueState extends Enum[QueueState] {
  val values = findValues

  def fromStateString(s: String): Seq[QueueState] = s match {
    case "" =>
      List(ok)

    case s =>
      for {
        c <- s
        state <- withNameOption(c.toString)
      } yield state
  }

  case object ok extends QueueState("ok")

  case object disabled extends QueueState("d") {
    override def toString: String = "(d)isabled"
  }

  case object error extends QueueState("E") {
    override def toString: String = "(E)rror"
  }

  case object orphaned extends QueueState("o") {
    override def toString: String = "(o)rphaned"
  }

  case object suspended extends QueueState("s") {
    override def toString: String = "(s)uspended"
  }

  case object unknown extends QueueState("u") {
    override def toString: String = "(u)nknown"
  }

  case object alarm_load extends QueueState("a") {
    override def toString: String = "load threshold exceeded (a)larm"
  }

  case object alarm_suspend extends QueueState("A") {
    override def toString: String = "suspend threshold exceeded (A)larm"
  }

  case object calendar_disabled extends QueueState("D") {
    override def toString: String = "calendar (D)isabled"
  }

  case object calendar_suspended extends QueueState("C") {
    override def toString: String = "(C)alendar suspended"
  }

  case object configuration_ambiguous extends QueueState("c") {
    override def toString: String = "(c)onfiguration ambiguous"
  }

  case object subordinate_suspended extends QueueState("S") {
    override def toString: String = "(S)ubordinate suspended"
  }
}
