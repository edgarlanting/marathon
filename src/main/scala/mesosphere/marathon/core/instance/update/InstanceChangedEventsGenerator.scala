package mesosphere.marathon
package core.instance.update

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.event.{InstanceChanged, MarathonEvent, MesosStatusUpdateEvent}
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.TaskState

import scala.collection.immutable.Seq

object InstanceChangedEventsGenerator extends StrictLogging {
  def events(instance: Instance, task: Option[Task], now: Timestamp, previousState: Option[InstanceState]): Seq[MarathonEvent] = {
    val stateChanged = previousState.fold(true) { previous =>
      previous.condition != instance.state.condition ||
      previous.goal != instance.state.goal
    }
    val runSpecId = instance.runSpecId
    val version = instance.runSpecVersion

    val instanceEvent: Seq[MarathonEvent] = if (stateChanged) {
      Seq(
        InstanceChanged(
          id = instance.instanceId,
          runSpecVersion = version,
          runSpecId = runSpecId,
          condition = instance.state.condition,
          instance = instance
        )
      )
    } else Nil

    task.fold(instanceEvent) { task =>
      val maybeTaskStatus = task.status.mesosStatus
      val ports = task.status.networkInfo.hostPorts
      val host = instance.hostname.getOrElse("unknown")
      val ipAddresses = task.status.networkInfo.ipAddresses
      val slaveId = maybeTaskStatus.fold("")(_.getSlaveId.getValue)
      val message = maybeTaskStatus.fold("")(status => if (status.hasMessage) status.getMessage else "")
      val state =
        task.status.mesosStatus
          .map(_.getState)
          .getOrElse(
            TaskState.TASK_STAGING
          ) // should return TASK_KILLED when resident task is killed... but TASK_STAGING if state not yet known

      val taskEvent = MesosStatusUpdateEvent(
        slaveId,
        task.taskId,
        state,
        message,
        appId = runSpecId,
        host,
        ipAddresses,
        ports = ports,
        version = version.toString,
        timestamp = now.toString
      )
      taskEvent +: instanceEvent
    }
  }

  def updatedCondition(instance: Instance): InstanceChanged =
    InstanceChanged(
      id = instance.instanceId,
      runSpecVersion = instance.runSpecVersion,
      runSpecId = instance.runSpecId,
      condition = instance.state.condition,
      instance = instance
    )
}
