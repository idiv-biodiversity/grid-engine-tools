package grid.engine

import sys.process._
import util.{Try, Success, Failure}
import xml._
import java.util.Date

object qjend extends App {
  val formatter = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

  // -------------------------------------------------------------------------------------------------
  // getting job start
  // -------------------------------------------------------------------------------------------------

  case class Job(id: String, task: Option[String], owner: String, start: Long, end: Option[Date])

  val jobs: Seq[Job] = for {
    job <- XML.loadString("""qstat -xml -s r -u *""".!!) \\ "job_list"
    owner <- emptyStringOption((job \ "JB_owner").text)
    id = (job \ "JB_job_number").text
    task = emptyStringOption((job \ "tasks").text)
    start = (job \ "JAT_start_time").text
  } yield Job(id, task, owner, formatter.parse(start).getTime, None)

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
    runtime <- hardRequests.find(el => (el \ "CE_name").text == "h_rt").map(el => (el \ "CE_stringval").text.toLong)
  } yield (id,runtime)).seq.toMap

  // -------------------------------------------------------------------------------------------------
  // getting job end
  // -------------------------------------------------------------------------------------------------

  jobs.flatMap({ job =>
    runtimes.get(job.id) map { runtime =>
      job.copy(end = Some(new Date(job.start + runtime * 1000)))
    }
  }).sortBy(_.end) foreach { job =>
    val id = job.task.fold(job.id)(task => s"""${job.id}.$task""")
    println(f"""${job.end.get}        $id%-20s        ${job.owner}""")
  }

  // -------------------------------------------------------------------------------------------------
  // util
  // -------------------------------------------------------------------------------------------------

  def emptyStringOption(s: String): Option[String] =
    if (s.isEmpty) None else Some(s)
}
