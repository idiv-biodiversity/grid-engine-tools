package grid.engine

import cats.instances.all._
import sys.process._
import xml._
import java.util.Date

object qjend extends App {

  // -----------------------------------------------------------------------------------------------
  // help / usage
  // -----------------------------------------------------------------------------------------------

  if (List("-?", "-h", "--help") exists args.contains) {
    println(s"""|usage: qjend [host...]
                |
                |Shows when running jobs end.
                |
                |FILTERING
                |
                |  host...                     checks only the specified hosts
                |
                |OTHER
                |
                |  -? | -h | --help            print this help
                |
                |""".stripMargin)
    sys exit 0
  }

  // -----------------------------------------------------------------------------------------------
  // config
  // -----------------------------------------------------------------------------------------------

  final case class Conf(hosts: List[String])

  object Conf {
    def default: Conf =
      Conf(hosts = Nil)
  }

  val conf: Conf = {
    def accumulate(conf: Conf)(args: List[String]): Conf = args match {
      case Nil =>
        conf.copy(hosts = conf.hosts.reverse)

      case host :: tail =>
        accumulate(conf.copy(hosts = host :: conf.hosts))(tail)
    }

    accumulate(Conf.default)(args.toList)
  }

  // -----------------------------------------------------------------------------------------------
  // core data structures this app uses
  // -----------------------------------------------------------------------------------------------

  final case class Job(id: String, task: Option[String], owner: String, start: Long)

  // -------------------------------------------------------------------------------------------------
  // getting job start
  // -------------------------------------------------------------------------------------------------

  val formatter = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

  val jobs: Seq[Job] = for {
    job <- XML.loadString("""qstat -xml -s r -u *""".!!) \\ "job_list"
    qi <- QueueInstance.unapply((job \ "queue_name").text)
    if conf.hosts.isEmpty || conf.hosts.contains(qi.host)
    owner <- emptyStringOption((job \ "JB_owner").text)
    id = (job \ "JB_job_number").text
    task = emptyStringOption((job \ "tasks").text)
    start = (job \ "JAT_start_time").text
  } yield Job(id, task, owner, formatter.parse(start).getTime)

  // -------------------------------------------------------------------------------------------------
  // getting runtime resources
  // -------------------------------------------------------------------------------------------------

  // id -> runtime
  val runtimes: Map[String,Long] = (for {
    id <- jobs.map(_.id).distinct.par
    qstatjxml <- Try(XML.loadString(s"""qstat -j $id -xml""".!!)) match {
      case Success(value) => Some(value)
      case Failure(error) =>
        Console.err.println(s"""excluded $id due to: $error""")
        None
    }
    hardRequests = qstatjxml \\ "JB_hard_resource_list" \\ "element"
    runtime <- hardRequests.find(el => (el \ "CE_name").text === "h_rt").map(el => (el \ "CE_stringval").text.toLong)
  } yield (id,runtime)).seq.toMap

  // -------------------------------------------------------------------------------------------------
  // getting job end
  // -------------------------------------------------------------------------------------------------

  jobs.flatMap({ job =>
    runtimes.get(job.id) map { runtime =>
      job -> new Date(job.start + runtime * 1000)
    }
  }).sortBy(_._2) foreach {
    case (job, end) =>
      val id = job.task.fold(job.id)(task => s"""${job.id}.$task""")
      println(f"""$end        $id%-20s        ${job.owner}""")
  }

  // -------------------------------------------------------------------------------------------------
  // util
  // -------------------------------------------------------------------------------------------------

  def emptyStringOption(s: String): Option[String] =
    if (s.isEmpty) None else Some(s)
}
