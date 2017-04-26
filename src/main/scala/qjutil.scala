package grid.engine

import cats.instances.all._
import sys.process._
import util._
import xml._

object qjutil extends App with Signal {

  exit on SIGPIPE

  // -----------------------------------------------------------------------------------------------
  // configuration defaults
  // -----------------------------------------------------------------------------------------------

  object Default {
    val lower = 0.8
    val upper = 1.01
    val tolerance = 0
    val user = sys.props get "user.name" filter { _ =!= "root" } getOrElse "*"
  }

  // -----------------------------------------------------------------------------------------------
  // help / usage
  // -----------------------------------------------------------------------------------------------

  if (List("-?", "-h", "-help", "--help") exists args.contains) {
    Console.println(s"""
      |Usage: qjutil [-f|-s] [-l mod] [-o mod] [-us slots] [-u user] [jobID [jobID [..]]]
      |
      |Display job utilization.
      |
      |  -? | -h | -help | --help            print this help
      |  -f                                  full output (even proper utilization)
      |  -s                                  short output (affected job ID, task ID pairs only)
      |  -l mod                              underutilization threshold modifier
      |                                      default: ${Default.lower}
      |  -o mod                              overload threshold modifier
      |                                      default: ${Default.upper}
      |  -t seconds                          tolerance time (apply to jobs running longer only)
      |                                      default: ${Default.tolerance}
      |  -u user                             apply to jobs of this user only
      |                                      default is ${Default.user}
      |  -us slots                           ignore jobs with slots greater than
      |  jobID                               apply to this jobID only, multiple
      |                                      allowed, default is all, what not
      |                                      passes as Int gets silently dropped
    """.stripMargin)
    sys exit 0
  }

  // -----------------------------------------------------------------------------------------------
  // config
  // -----------------------------------------------------------------------------------------------

  case class Conf (
    short: Boolean,
    full: Boolean,
    jids: Seq[Int],
    lower: Double,
    upper: Double,
    user:  String,
    tolerance: Int,
    ignoreSlotsGreater: Option[Int]
  )

  object Conf {
    object Int {
      def unapply(s: String): Option[Int] =
        Try(s.toInt).toOption
    }

    object Double {
      def unapply(s: String): Option[Double] =
        Try(s.toDouble).toOption
    }
  }

  val conf = {
    def accumulate(conf: Conf)(args: List[String]): Conf = args match {
      case Nil ⇒
        conf

      case "-f" :: tail ⇒
        accumulate(conf.copy(full = true, short = false))(tail)

      case "-s" :: tail ⇒
        accumulate(conf.copy(full = false, short = true))(tail)

      case "-us" :: Conf.Int(slots) :: tail ⇒
        accumulate(conf.copy(ignoreSlotsGreater = Some(slots)))(tail)

      case "-l" :: Conf.Double(mod) :: tail ⇒
        accumulate(conf.copy(lower = mod))(tail)

      case "-o" :: Conf.Double(mod) :: tail ⇒
        accumulate(conf.copy(upper = mod))(tail)

      case "-t" :: Conf.Int(seconds) :: tail ⇒
        accumulate(conf.copy(tolerance = seconds))(tail)

      case "-u" :: user :: tail ⇒
        accumulate(conf.copy(user = user))(tail)

      case Conf.Int(jid) :: tail ⇒
        accumulate(conf.copy(jids = conf.jids :+ jid, user = "*"))(tail)

      case x :: tail ⇒
        Console.err.println(s"""Don't know what to do with argument "$x".""")
        accumulate(conf)(tail)
    }

    accumulate(Conf(false, false, Vector(), Default.lower, Default.upper, Default.user, Default.tolerance, None))(args.toList)
  }

  // -----------------------------------------------------------------------------------------------
  // main
  // -----------------------------------------------------------------------------------------------

  case class Job (jid: Int, tid: String) {
    override def toString =
      if (tid.isEmpty) s"""$jid""" else s"""$jid.$tid"""
  }

  val qstat =
    s"""qstat -xml -s r -u ${conf.user}"""

  def qstatj(id: Int): String =
    s"""qstat -xml -j $id"""

  val qstatxml = XML.loadString(qstat.!!)

  val now = new java.util.Date().getTime
  val df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

  if (!conf.short)
    Console.println("""STAT JOB                  SLOTS    CPUTIME    OPTIMUM    PERCENT""")

  for {
    job <- qstatxml \\ "job_list"
    jid = (job \ "JB_job_number").text.toInt
    if conf.jids.isEmpty || conf.jids.contains(jid)
    start = df.parse((job \ "JAT_start_time").text).getTime
    run = (now - start) / 1000
    if conf.tolerance < run
    tid = (job \ "tasks").text
    slots = (job \ "slots").text.toInt
    if conf.ignoreSlotsGreater.isEmpty || conf.ignoreSlotsGreater.get >= slots
    jobdetail <- Try(XML.loadString(qstatj(jid).!!)) match {
      case s @ Success(_) => s
      case f @ Failure(e) =>
        Console.err.println(s"""$jid: $e""")
        f
    }
    task <- jobdetail \\ "JB_ja_tasks" \\ "element"
    id = (task \ "JAT_task_number").text
    if tid.isEmpty | id === tid
    scaled <- task \\ "JAT_scaled_usage_list" \\ "Events"
    name = (scaled \\ "UA_name").text
    if name === "cpu"
    cpu = (scaled \\ "UA_value").text
    if cpu.nonEmpty
    optimum = run * slots
    out = if (conf.short) Output.short else Output.human
  } out(Job(jid,tid), slots, cpu.toDouble.round, optimum, run)

  object Output {
    val human = (job: Job, slots: Int, cpu: Long, optimum: Long, run: Long) ⇒ {
      val stat = if (cpu > optimum * conf.upper)
        "OVER"
      else if (slots > 1 && cpu < run)
        "CRIT"
      else if (cpu < optimum * conf.lower)
        "WARN"
      else
        "OK"

      if (conf.full || stat =!= "OK") {
        val percent = cpu.toDouble / optimum * 100
        Console.println(f"""$stat%-4s $job%-15s $slots%10d $cpu%10d $optimum%10d $percent%10.2f""")
      }
    }

    val short = (job: Job, slots: Int, cpu: Long, optimum: Long, run: Long) ⇒
    if ( (cpu > optimum * conf.upper) || (slots > 1 && cpu < run) || (cpu < optimum * conf.lower) )
      Console.println(s"""$job""")
  }
}
