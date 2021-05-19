package mesosphere.marathon
package state

import java.util.UUID

import org.apache.mesos.{Protos => mesos}

object TaskFailureTestHelper {
  val taskFailureId = s"failedtask.${UUID.randomUUID()}"
  lazy val taskFailure = TaskFailure(
    appId = AbsolutePathId("/group/app"),
    taskId = mesos.TaskID.newBuilder.setValue(taskFailureId).build,
    state = mesos.TaskState.TASK_FAILED,
    message = "Process exited with status [1]",
    host = "slave5.mega.co",
    version = Timestamp(1000),
    timestamp = Timestamp(2000)
  )
}
