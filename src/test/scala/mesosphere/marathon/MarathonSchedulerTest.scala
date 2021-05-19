package mesosphere.marathon

import akka.Done
import akka.event.EventStream
import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.launcher.OfferProcessor
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.update.TaskStatusUpdateProcessor
import mesosphere.marathon.state.Region
import mesosphere.marathon.storage.repository.{AppRepository, FrameworkIdRepository}
import mesosphere.marathon.test.{MarathonTestHelper, TestCrashStrategy}
import mesosphere.mesos.LibMesos
import mesosphere.util.state.{FrameworkId, MutableMesosLeaderInfo}
import org.apache.mesos.Protos.DomainInfo.FaultDomain
import org.apache.mesos.Protos.DomainInfo.FaultDomain.{RegionInfo, ZoneInfo}
import org.apache.mesos.Protos._
import org.apache.mesos.SchedulerDriver
import org.mockito.ArgumentCaptor

import scala.concurrent.{Future, Promise}

class MarathonSchedulerTest extends AkkaUnitTest {
  class Fixture {
    val repo: AppRepository = mock[AppRepository]
    val queue: LaunchQueue = mock[LaunchQueue]
    val frameworkIdRepository: FrameworkIdRepository = mock[FrameworkIdRepository]
    val mesosLeaderInfo: MutableMesosLeaderInfo = new MutableMesosLeaderInfo
    mesosLeaderInfo.onNewMasterInfo(MasterInfo.getDefaultInstance)
    val config: AllConf = MarathonTestHelper.defaultConfig(maxInstancesPerOffer = 10)
    val probe: TestProbe = TestProbe()
    val eventBus: EventStream = system.eventStream
    val taskStatusProcessor: TaskStatusUpdateProcessor = mock[TaskStatusUpdateProcessor]
    val offerProcessor: OfferProcessor = mock[OfferProcessor]
    val crashStrategy = new TestCrashStrategy
    val marathonScheduler: MarathonScheduler = new MarathonScheduler(
      eventBus,
      offerProcessor = offerProcessor,
      taskStatusProcessor = taskStatusProcessor,
      frameworkIdRepository,
      mesosLeaderInfo,
      config,
      crashStrategy,
      Promise[FrameworkID]
    ) {}
  }

  "MarathonScheduler" should {
    "Publishes event when registered" in new Fixture {
      val driver = mock[SchedulerDriver]
      val frameworkId = FrameworkID.newBuilder
        .setValue("some_id")
        .build()

      val masterInfo = MasterInfo
        .newBuilder()
        .setVersion(LibMesos.MesosMasterMinimumVersion.toString)
        .setId("")
        .setIp(0)
        .setPort(5050)
        .setHostname("some_host")
        .build()

      frameworkIdRepository.store(any) returns Future.successful(Done)

      eventBus.subscribe(probe.ref, classOf[SchedulerRegisteredEvent])

      marathonScheduler.registered(driver, frameworkId, masterInfo)

      try {
        val msg = probe.expectMsgType[SchedulerRegisteredEvent]

        assert(msg.frameworkId == frameworkId.getValue)
        assert(msg.master == masterInfo.getHostname)
        assert(msg.eventType == "scheduler_registered_event")
        assert(mesosLeaderInfo.currentLeaderUrl.get == "http://some_host:5050/")
        verify(frameworkIdRepository).store(FrameworkId.fromProto(frameworkId))
        noMoreInteractions(frameworkIdRepository)
      } finally {
        eventBus.unsubscribe(probe.ref)
      }
    }

    "Publishes event when reregistered" in new Fixture {
      val driver = mock[SchedulerDriver]
      val masterInfo = MasterInfo
        .newBuilder()
        .setVersion(LibMesos.MesosMasterMinimumVersion.toString)
        .setId("")
        .setIp(0)
        .setPort(5050)
        .setHostname("some_host")
        .build()

      eventBus.subscribe(probe.ref, classOf[SchedulerReregisteredEvent])

      marathonScheduler.reregistered(driver, masterInfo)

      try {
        val msg = probe.expectMsgType[SchedulerReregisteredEvent]

        assert(msg.master == masterInfo.getHostname)
        assert(msg.eventType == "scheduler_reregistered_event")
        assert(mesosLeaderInfo.currentLeaderUrl.get == "http://some_host:5050/")
      } finally {
        eventBus.unsubscribe(probe.ref)
      }
    }

    // Currently does not work because of the injection used in MarathonScheduler.callbacks
    "Publishes event when disconnected" in new Fixture {
      val driver = mock[SchedulerDriver]

      eventBus.subscribe(probe.ref, classOf[SchedulerDisconnectedEvent])

      marathonScheduler.disconnected(driver)

      try {
        val msg = probe.expectMsgType[SchedulerDisconnectedEvent]

        assert(msg.eventType == "scheduler_disconnected_event")
      } finally {
        eventBus.unsubscribe(probe.ref)
      }

      // we **heavily** rely on driver.stop to delegate enforcement of leadership abdication,
      // so it's worth testing that this behavior isn't lost.
      verify(driver, times(1)).stop(true)

      noMoreInteractions(driver)
    }

    "Suicide with an unknown error will not remove the framework id" in new Fixture {
      Given("A suicide call trap")
      val driver = mock[SchedulerDriver]

      When("An error is reported")
      marathonScheduler.error(driver, "some weird mesos message")

      Then("Marathon crashes")
      crashStrategy.crashed shouldBe true
    }

    "Suicide with a framework error will remove the framework id" in new Fixture {
      Given("A suicide call trap")
      val driver = mock[SchedulerDriver]

      When("An error is reported")
      marathonScheduler.error(driver, "Framework has been removed")

      Then("Marathon crashes")
      crashStrategy.crashed shouldBe true
    }

    "Restrict status update message length" in new Fixture {
      Given(s"A status update larger than ${MarathonScheduler.MAX_STATUS_MESSAGE_LENGTH} characters")
      val driver = mock[SchedulerDriver]
      val message = "X" * (MarathonScheduler.MAX_STATUS_MESSAGE_LENGTH + 100)

      val status = TaskStatus
        .newBuilder()
        .setTaskId(TaskID.newBuilder().setValue("taskId"))
        .setState(TaskState.TASK_FAILED)
        .setMessage(message)
        .build()

      val captor = ArgumentCaptor.forClass(classOf[TaskStatus])

      taskStatusProcessor.publish(captor.capture()) returns Future.successful(())

      When("The TaskStatus is delivered")
      marathonScheduler.statusUpdate(driver, status)

      Then("The forwarded status should have a restricted message size")
      captor.getValue.getMessage should have length (MarathonScheduler.MAX_STATUS_MESSAGE_LENGTH)
    }

    "Not restrict status update message length if it's under the limit" in new Fixture {
      Given(s"A status update smaller than ${MarathonScheduler.MAX_STATUS_MESSAGE_LENGTH} characters")
      val driver = mock[SchedulerDriver]
      val message = "X" * 200

      val status = TaskStatus
        .newBuilder()
        .setTaskId(TaskID.newBuilder().setValue("taskId"))
        .setState(TaskState.TASK_FAILED)
        .setMessage(message)
        .build()

      val captor = ArgumentCaptor.forClass(classOf[TaskStatus])

      taskStatusProcessor.publish(captor.capture()) returns Future.successful(())

      When("The TaskStatus is delivered")
      marathonScheduler.statusUpdate(driver, status)

      Then("The forwarded status should have the original message size")
      captor.getValue.getMessage should have length (message.length)
    }

    "Store default region when registered" in new Fixture {
      val driver = mock[SchedulerDriver]
      val frameworkId = FrameworkID.newBuilder
        .setValue("some_id")
        .build()

      val regionName = "some_region"
      val zoneName = "some_zone"

      val masterInfo = MasterInfo
        .newBuilder()
        .setVersion(LibMesos.MesosMasterMinimumVersion.toString)
        .setId("")
        .setIp(0)
        .setPort(5050)
        .setHostname("some_host")
        .setDomain(
          DomainInfo
            .newBuilder()
            .setFaultDomain(
              FaultDomain
                .newBuilder()
                .setRegion(RegionInfo.newBuilder().setName(regionName).build())
                .setZone(ZoneInfo.newBuilder().setName(zoneName).build())
                .build()
            )
            .build()
        )
        .build()

      frameworkIdRepository.store(any) returns Future.successful(Done)

      marathonScheduler.registered(driver, frameworkId, masterInfo)

      marathonScheduler.getLocalRegion shouldEqual Some(Region(regionName))
    }

    "Store default region when reregistered" in new Fixture {
      val driver = mock[SchedulerDriver]

      val regionName = "some_region"
      val zoneName = "some_zone"

      val masterInfo = MasterInfo
        .newBuilder()
        .setVersion(LibMesos.MesosMasterMinimumVersion.toString)
        .setId("")
        .setIp(0)
        .setPort(5050)
        .setHostname("some_host")
        .setDomain(
          DomainInfo
            .newBuilder()
            .setFaultDomain(
              FaultDomain
                .newBuilder()
                .setRegion(RegionInfo.newBuilder().setName(regionName).build())
                .setZone(ZoneInfo.newBuilder().setName(zoneName).build())
                .build()
            )
            .build()
        )
        .build()

      marathonScheduler.reregistered(driver, masterInfo)

      marathonScheduler.getLocalRegion shouldEqual Some(Region(regionName))

    }
  }
}
