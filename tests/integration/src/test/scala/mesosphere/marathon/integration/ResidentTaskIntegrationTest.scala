package mesosphere.marathon
package integration

import com.mesosphere.utils.http.RestResult
import com.mesosphere.utils.mesos.MesosFacade.{ITMesosState, ITResources}
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.api.RestResource
import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.facades.{AppMockFacade, ITEnrichedTask}
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest
import mesosphere.marathon.raml.{App, AppUpdate, Network, NetworkMode, PortDefinition}
import mesosphere.marathon.state.AbsolutePathId

import scala.collection.immutable.Seq
import scala.util.Try

class ResidentTaskIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  import Fixture._

  "ResidentTaskIntegrationTest" should {

    "resident task can be deployed and write to persistent volume" in new Fixture {
      Given("An app that writes into a persistent volume")
      val containerPath = "persistent-volume"
      val app = residentApp(
        id = appId("resident-task-can-be-deployed-and-write-to-persistent-volume"),
        containerPath = containerPath,
        cmd = s"""echo "data" > $containerPath/data"""
      )

      When("A task is launched")
      val result = createAsynchronously(app)
      val deploymentId = result.originalResponse.headers.find(_.name == RestResource.DeploymentHeader).map(_.value)

      Then("It writes successfully to the persistent volume and finishes")
      // Since the task fails immediately after it starts, the order of event seen on the event bus is arbitrary
      val waitingFor = Map[String, CallbackEvent => Boolean](
        "status_update_event" -> (_.taskStatus == "TASK_RUNNING"),
        "status_update_event" -> (_.taskStatus == "TASK_FINISHED"),
        "deployment_success" -> (_.id == deploymentId.value)
      )
      waitForEventsWith(s"waiting for the task to start and finish and ${app.id} to be successfully deployed", waitingFor)
    }

    "resident task can be deployed along with constraints" in new Fixture {
      // background: Reserved tasks may not be considered while making sure constraints are met, because they
      // would prevent launching a task because there `is` already a task (although not launched)
      Given("A resident app that uses a hostname:UNIQUE constraints")
      val containerPath = "persistent-volume"
      val unique = raml.Constraints("hostname" -> "UNIQUE")

      val app = residentApp(
        id = appId("resident-task-that-uses-hostname-unique"),
        containerPath = containerPath,
        cmd = """sleep 1""",
        constraints = unique
      )

      When("A task is launched")
      val result = createAsynchronously(app)

      Then("It it successfully launched")
      waitForStatusUpdates(StatusUpdate.TASK_RUNNING)
      waitForDeployment(result)
    }

    "persistent volume will be re-attached and keep state" in new Fixture {
      Given("An app that writes into a persistent volume")
      val containerPath = "persistent-volume"
      val app = residentApp(
        id = appId("resident-task-with-persistent-volume-will-be-reattached-and-keep-state"),
        containerPath = containerPath,
        cmd = s"""echo data > $containerPath/data && sleep 1000"""
      )

      When("deployment is successful")
      val result = createAsynchronously(app)

      Then("it successfully writes to the persistent volume and then finishes")
      waitForStatusUpdates(StatusUpdate.TASK_RUNNING)
      waitForDeployment(result)

      And("default residency values are set")
      val deployedApp = marathon.app(AbsolutePathId(app.id))
      val residency = deployedApp.value.app.residency.get
      residency.taskLostBehavior shouldEqual raml.TaskLostBehavior.WaitForever
      residency.relaunchEscalationTimeoutSeconds shouldEqual 3600L

      When("the app is suspended")
      suspendSuccessfully(AbsolutePathId(app.id))

      And("a new task is started that checks for the previously written file")
      // deploy a new version that checks for the data written the above step
      val update = marathon.updateApp(
        AbsolutePathId(app.id),
        AppUpdate(
          instances = Some(1),
          cmd = Some(s"""test -e $containerPath/data && sleep 2""")
        )
      )
      update should be(OK)
      // we do not wait for the deployment to finish here to get the task events

      waitForStatusUpdates(StatusUpdate.TASK_RUNNING)
      waitForDeployment(update)
      waitForStatusUpdates(StatusUpdate.TASK_FINISHED)
    }

    "persistent volume will be re-attached after task failure" in new Fixture {
      Given("An app that writes into a persistent volume")
      val containerPath = "persistent-volume"
      val id = appId("resident-task-with-persistent-volume-will-reattach-after-failure")
      val cmd = s"""echo hello >> $containerPath/data && ${appMockCmd(id, "v1")}"""
      val healthCheck = appProxyHealthCheck().copy(path = Some("/ping"))
      val app = residentApp(id = id, containerPath = containerPath, cmd = cmd, portDefinitions = Seq(PortDefinition(name = Some("http"))))
        .copy(networks = Seq(Network(mode = NetworkMode.Host)), backoffSeconds = 1, healthChecks = Set(healthCheck))

      When("a task is launched")
      createSuccessfully(app)

      And("the app dies")
      val tasks = marathon.tasks(id).value
      tasks should have size (1)
      val failedTask = tasks.head
      val failedAppMock = AppMockFacade(failedTask)
      failedAppMock.get(s"/$containerPath/data").futureValue.entityString should be("hello\n")
      failedAppMock.suicide().futureValue

      Then("the failed task is restarted")
      val newTask = eventually {
        val newTasks = marathon.tasks(id).value
        newTasks should have size (1)
        val newTask = newTasks.head
        newTask.state should be("TASK_RUNNING")
        newTask.healthCheckResults.head.alive should be(true)
        newTask.id should not be (failedTask.id)
        newTask
      }

      And("the data survived")
      eventually {
        AppMockFacade(newTask).get(s"/$containerPath/data").futureValue.entityString should be("hello\nhello\n")
      }
    }

    "resident task is launched completely on reserved resources" in new Fixture {
      Given("A clean state of the cluster since we check reserved resources")
      cleanUp()

      And("A resident app")
      val app = residentApp(id = appId("resident-task-is-launched-completely-on-reserved-resources"))

      When("A task is launched")
      createSuccessfully(app)

      Then("used and reserved resources correspond to the app")
      val state: RestResult[ITMesosState] = mesosFacade.state

      withClue("used_resources") {
        state.value.agents.head.usedResources should equal(itMesosResources)
      }
      withClue("reserved_resources") {
        state.value.agents.head.reservedResourcesByRole.get("foo") should equal(Some(itMesosResources))
      }

      When("the app is suspended")
      suspendSuccessfully(AbsolutePathId(app.id))

      Then("there are no used resources anymore but there are the same reserved resources")
      val state2: RestResult[ITMesosState] = mesosFacade.state

      withClue("used_resources") {
        state2.value.agents.head.usedResources should be(empty)
      }
      withClue("reserved_resources") {
        state2.value.agents.head.reservedResourcesByRole.get("foo") should equal(Some(itMesosResources))
      }

      // we check for a blank slate of mesos reservations after each test
      // TODO: Once we wait for the unreserves before finishing the StopApplication deployment step,
      // we should test that here
    }

    "Scale Up" in new Fixture {
      Given("A resident app with 0 instances")
      val app = createSuccessfully(residentApp(id = appId("scale-up-resident-app-with-zero-instances"), instances = 0))

      When("We scale up to 5 instances")
      scaleToSuccessfully(AbsolutePathId(app.id), 5)

      Then("exactly 5 tasks have been created")
      val all = allTasks(AbsolutePathId(app.id))
      all.count(_.launched) shouldBe 5 withClue (s"Found ${all.size}/5 tasks: ${all}")
    }

    "Scale Down" in new Fixture {
      Given("a resident app with 5 instances")
      val app = createSuccessfully(residentApp(id = appId("scale-down-resident-app-with-five-instances"), instances = 5))

      When("we scale down to 0 instances")
      suspendSuccessfully(AbsolutePathId(app.id))

      Then("all tasks are suspended")
      val all = allTasks(AbsolutePathId(app.id))
      all.size shouldBe 5 withClue (s"Found ${all.size}/5 tasks: ${all}")
      all.count(_.launched) shouldBe 0 withClue (s"${all.count(_.launched)} launched tasks (should be 0)")
      all.count(_.suspended) shouldBe 5 withClue (s"${all.count(_.suspended)} suspended tasks (should be 5)")
    }

    "Restart" in new Fixture {
      Given("a resident app with 5 instances")
      val app = createSuccessfully(
        residentApp(
          id = appId("restart-resident-app-with-five-instances"),
          instances = 5
        )
      )

      val launchedTasks = allTasks(AbsolutePathId(app.id))
      launchedTasks should have size 5

      When("we restart the app")
      val newVersion = restartSuccessfully(app) withClue ("The app did not restart.")
      val all = allTasks(AbsolutePathId(app.id))

      logger.info("tasks after relaunch: {}", all.mkString(";"))

      Then("no extra task was created")
      all.size shouldBe 5 withClue (s"Found ${all.size}/5 tasks: ${all}")

      And("exactly 5 instances are running")
      all.count(_.launched) shouldBe 5 withClue (s"${all.count(_.launched)} launched tasks (should be 5)")

      And("all 5 tasks are restarted and of the new version")
      all
        .map(_.version)
        .forall(_.contains(newVersion)) shouldBe true withClue (s"5 launched tasks should have new version ${newVersion}: ${all}")
    }

    "Config Change" in new Fixture {
      Given("a resident app with 5 instances")
      val app = createSuccessfully(
        residentApp(
          id = appId("config-change-resident-app-with-five-instances"),
          instances = 5
        )
      )

      val launchedTasks = allTasks(AbsolutePathId(app.id))
      launchedTasks should have size 5

      When("we change the config")
      val newVersion = updateSuccessfully(AbsolutePathId(app.id), AppUpdate(cmd = Some("sleep 1234"))).toString
      val all = allTasks(AbsolutePathId(app.id))

      logger.info("tasks after config change: {}", all.mkString(";"))

      Then("no extra task was created")
      all should have size 5

      And("exactly 5 instances are running")
      all.filter(_.launched) should have size 5

      And("all 5 tasks are of the new version")
      all.map(_.version).forall(_.contains(newVersion)) shouldBe true
    }
  }

  class Fixture {

    val cpus: Double = 0.001
    val mem: Double = 1.0
    val disk: Double = 1.0
    val gpus: Double = 0.0
    val persistentVolumeSize = 2L

    val itMesosResources = ITResources(
      "mem" -> mem,
      "cpus" -> cpus,
      "disk" -> (disk + persistentVolumeSize),
      "gpus" -> gpus
    )

    def appId(suffix: String): AbsolutePathId = AbsolutePathId(s"/$testBasePath/app-$suffix")

    def createSuccessfully(app: App): App = {
      waitForDeployment(createAsynchronously(app))
      app
    }

    def createAsynchronously(app: App): RestResult[App] = {
      val result = marathon.createAppV2(app)
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      result
    }

    def scaleToSuccessfully(appId: AbsolutePathId, instances: Int): Seq[ITEnrichedTask] = {
      val result = marathon.updateApp(appId, AppUpdate(instances = Some(instances)))
      result should be(OK)
      waitForDeployment(result)
      waitForTasks(appId, instances)
    }

    def suspendSuccessfully(appId: AbsolutePathId): Seq[ITEnrichedTask] = scaleToSuccessfully(appId, 0)

    def updateSuccessfully(appId: AbsolutePathId, update: AppUpdate): VersionString = {
      val result = marathon.updateApp(appId, update)
      result should be(OK)
      waitForDeployment(result)
      result.value.version.toString
    }

    def restartSuccessfully(app: App): VersionString = {
      val result = marathon.restartApp(AbsolutePathId(app.id))
      result should be(OK)
      waitForDeployment(result)
      result.value.version.toString
    }

    def allTasks(appId: AbsolutePathId): Seq[ITEnrichedTask] = {
      Try(marathon.tasks(appId)).map(_.value).getOrElse(Nil)
    }

    def launchedTasks(appId: AbsolutePathId): Seq[ITEnrichedTask] = allTasks(appId).filter(_.launched)

    def suspendedTasks(appId: AbsolutePathId): Seq[ITEnrichedTask] = allTasks(appId).filter(_.suspended)
  }

  object Fixture {
    type VersionString = String

    object StatusUpdate {
      val TASK_FINISHED = "TASK_FINISHED"
      val TASK_RUNNING = "TASK_RUNNING"
      val TASK_FAILED = "TASK_FAILED"
    }

    /**
      * Resident Tasks reside in the TaskTracker even after they terminate and after the associated app is deleted.
      * To prevent spurious state in the above test cases, each test case should use a unique appId.
      */
    object IdGenerator {
      private[this] var index: Int = 0
      def generate(): String = {
        index += 1
        index.toString
      }
    }
  }
}
