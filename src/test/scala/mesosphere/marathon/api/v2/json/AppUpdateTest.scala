package mesosphere.marathon
package api.v2.json

import com.wix.accord.Validator
import mesosphere.{UnitTest, ValidationTestLike}
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.api.v2.validation.AppValidation
import mesosphere.marathon.api.v2.{AppHelpers, AppNormalization}
import mesosphere.marathon.core.readiness.ReadinessCheckTestHelper
import mesosphere.marathon.raml.{
  AppUpdate,
  Artifact,
  Container,
  ContainerPortMapping,
  DockerContainer,
  EngineType,
  Environment,
  Network,
  NetworkMode,
  Raml,
  UpgradeStrategy
}
import mesosphere.marathon.state._
import play.api.libs.json.Json

import scala.collection.immutable.Seq

class AppUpdateTest extends UnitTest with ValidationTestLike {

  val runSpecId = AbsolutePathId("/test")

  implicit val appUpdateValidator: Validator[AppUpdate] = AppValidation.validateAppUpdateVersion

  /**
    * @return an [[AppUpdate]] that's been normalized to canonical form
    */
  private[this] def fromJsonString(json: String): AppUpdate = {
    val update: AppUpdate = Json.fromJson[AppUpdate](Json.parse(json)).get
    AppNormalization
      .forDeprecatedUpdates(AppNormalization.Configuration(None, "bridge-name", Set(), ResourceRole.Unreserved))
      .normalized(update)
  }

  "AppUpdate" should {

    "SerializationRoundtrip for empty definition" in {
      val update0 = AppUpdate(container = Some(Container(EngineType.Mesos)))
      JsonTestHelper.assertSerializationRoundtripWorks(update0)
      JsonTestHelper.assertSerializationRoundtripWithJacksonWorks(update0)
    }

    "SerializationRoundtrip for extended definition" in {
      val update1 = AppUpdate(
        cmd = Some("sleep 60"),
        args = None,
        user = Some("nobody"),
        env = Some(Environment("LANG" -> "en-US")),
        instances = Some(16),
        cpus = Some(2.0),
        mem = Some(256.0),
        disk = Some(1024.0),
        executor = Some("/opt/executors/bin/some.executor"),
        constraints = Some(Set.empty),
        fetch = Some(Seq(Artifact(uri = "http://dl.corp.org/prodX-1.2.3.tgz"))),
        backoffSeconds = Some(2),
        backoffFactor = Some(1.2),
        maxLaunchDelaySeconds = Some(60),
        container = Some(
          Container(
            EngineType.Docker,
            docker = Some(
              DockerContainer(
                image = "docker:///group/image"
              )
            ),
            portMappings = Option(
              Seq(
                ContainerPortMapping(containerPort = 80, name = Some("http"))
              )
            )
          )
        ),
        healthChecks = Some(Set.empty),
        taskKillGracePeriodSeconds = Some(2),
        dependencies = Some(Set.empty),
        upgradeStrategy = Some(UpgradeStrategy(1, 1)),
        labels = Some(
          Map(
            "one" -> "aaa",
            "two" -> "bbb",
            "three" -> "ccc"
          )
        ),
        networks = Some(
          Seq(
            Network(
              mode = NetworkMode.Container,
              labels = Map(
                "foo" -> "bar",
                "baz" -> "buzz"
              )
            )
          )
        ),
        unreachableStrategy = Some(raml.UnreachableEnabled(998, 999))
      )
      JsonTestHelper.assertSerializationRoundtripWorks(update1)
      JsonTestHelper.assertSerializationRoundtripWithJacksonWorks(update1)
    }

    "Serialization result of empty container" in {
      val update2 = AppUpdate(container = None)
      val json2 =
        """
      {
        "cmd": null,
        "user": null,
        "env": null,
        "instances": null,
        "cpus": null,
        "mem": null,
        "disk": null,
        "executor": null,
        "constraints": null,
        "uris": null,
        "ports": null,
        "backoffSeconds": null,
        "backoffFactor": null,
        "container": null,
        "healthChecks": null,
        "dependencies": null,
        "version": null
      }
    """
      val readResult2 = fromJsonString(json2)
      assert(readResult2 == update2)
    }

    "Serialization result of empty ipAddress" in {
      val update2 = AppUpdate(ipAddress = None)
      val json2 =
        """
      {
        "cmd": null,
        "user": null,
        "env": null,
        "instances": null,
        "cpus": null,
        "mem": null,
        "disk": null,
        "executor": null,
        "constraints": null,
        "uris": null,
        "ports": null,
        "backoffSeconds": null,
        "backoffFactor": null,
        "container": null,
        "healthChecks": null,
        "dependencies": null,
        "ipAddress": null,
        "version": null
      }
      """
      val readResult2 = fromJsonString(json2)
      assert(readResult2 == update2)
    }

    "Empty json corresponds to default instance" in {
      val update3 = AppUpdate()
      val json3 = "{}"
      val readResult3 = fromJsonString(json3)
      assert(readResult3 == update3)
    }

    "Args are correctly read" in {
      val update4 = AppUpdate(args = Some(Seq("a", "b", "c")))
      val json4 = """{ "args": ["a", "b", "c"] }"""
      val readResult4 = fromJsonString(json4)
      assert(readResult4 == update4)
    }

    "acceptedResourceRoles of update is only applied when != None" in {
      val app = AppDefinition(id = AbsolutePathId("/withAcceptedRoles"), role = "*", acceptedResourceRoles = Set("a"))

      val unchanged = Raml.fromRaml(Raml.fromRaml((AppUpdate(), app))).copy(versionInfo = app.versionInfo)
      assert(unchanged == app)

      val changed =
        Raml.fromRaml(Raml.fromRaml((AppUpdate(acceptedResourceRoles = Some(Set("b"))), app))).copy(versionInfo = app.versionInfo)
      assert(changed == app.copy(acceptedResourceRoles = Set("b")))
    }

    "update JSON serialization preserves readiness checks" in {
      val update = AppUpdate(
        id = Some("/test"),
        readinessChecks = Some(Seq(ReadinessCheckTestHelper.alternativeHttpsRaml))
      )
      val json = Json.toJson(update)
      val reread = json.as[AppUpdate]
      assert(reread == update)
    }

    "update readiness checks are applied to app" in {
      val update = AppUpdate(
        id = Some("/test"),
        readinessChecks = Some(Seq(ReadinessCheckTestHelper.alternativeHttpsRaml))
      )
      val app = AppDefinition(id = AbsolutePathId("/test"), role = "*")
      val updated = Raml.fromRaml(Raml.fromRaml((update, app)))

      assert(update.readinessChecks.map(_.map(Raml.fromRaml(_))).contains(updated.readinessChecks))
    }

    "empty app updateStrategy on persistent volumes" in {
      val json =
        """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "home",
              "mode": "RW",
              "persistent": {
                "size": 100
                }
              }]
        },
        "residency": {
          "relaunchEscalationTimeoutSeconds": 10,
          "taskLostBehavior": "WAIT_FOREVER"
        }
      }
      """

      val update = fromJsonString(json)
      val strategy = AppHelpers.withoutPriorAppDefinition(update, AbsolutePathId("/foo")).upgradeStrategy
      assert(
        strategy.contains(
          raml.UpgradeStrategy(
            minimumHealthCapacity = 0.5,
            maximumOverCapacity = 0
          )
        )
      )
    }

    "empty app unreachableStrategy on resident app" in {
      val json =
        """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "home",
              "mode": "RW",
              "persistent": {
                "size": 100
                }
              }]
        }
      }
      """

      val update = fromJsonString(json)
      val strategy = AppHelpers.withoutPriorAppDefinition(update, AbsolutePathId("/foo")).unreachableStrategy
      strategy.get should be(raml.UnreachableDisabled.DefaultValue)
    }

    "empty app unreachableStrategy on non-resident app" in {
      val json =
        """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS"
        }
      }
      """

      val update = fromJsonString(json)
      val strategy = AppHelpers.withoutPriorAppDefinition(update, AbsolutePathId("/foo")).unreachableStrategy
      strategy.get should be(raml.UnreachableEnabled.Default)
    }

    "empty app updateStrategy" in {
      val json =
        """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "home",
              "mode": "RW",
              "persistent": {
                "size": 100
                }
              }]
        },
        "residency": {
          "relaunchEscalationTimeoutSeconds": 10,
          "taskLostBehavior": "WAIT_FOREVER"
        }
      }
      """

      val update = fromJsonString(json)
      val strategy = AppHelpers.withoutPriorAppDefinition(update, AbsolutePathId("/foo")).upgradeStrategy
      assert(
        strategy.contains(
          raml.UpgradeStrategy(
            minimumHealthCapacity = 0.5,
            maximumOverCapacity = 0
          )
        )
      )
    }

    "empty app persists container" in {
      val json =
        """
        {
          "id": "/payload-id",
          "role": "*",
          "args": [],
          "container": {
            "type": "DOCKER",
            "volumes": [
              {
                "containerPath": "data",
                "mode": "RW",
                "persistent": {
                  "size": 100
                }
              }
            ],
            "docker": {
              "image": "anImage"
            }
          },
          "residency": {
            "taskLostBehavior": "WAIT_FOREVER",
            "relaunchEscalationTimeoutSeconds": 3600
          }
        }
      """

      val update = fromJsonString(json)
      val createdViaUpdate = Raml.fromRaml(AppHelpers.withoutPriorAppDefinition(update, AbsolutePathId("/put-path-id")))
      assert(update.container.isDefined)
      assert(
        createdViaUpdate.container.contains(
          state.Container.Docker(
            volumes = Seq(
              VolumeWithMount(
                volume = PersistentVolume(name = None, persistent = PersistentVolumeInfo(size = 100)),
                mount = VolumeMount(volumeName = None, mountPath = "data", readOnly = false)
              )
            ),
            image = "anImage"
          )
        ),
        createdViaUpdate.container
      )
    }

    "empty app persists existing upgradeStrategy" in {
      val json =
        """
        {
          "id": "/app",
          "role": "*",
          "args": [],
          "container": {
            "type": "DOCKER",
            "volumes": [
              {
                "containerPath": "data",
                "mode": "RW",
                "persistent": {
                  "size": 100
                }
              }
            ],
            "docker": {
              "image": "anImage"
            }
          },
          "residency": {
            "taskLostBehavior": "WAIT_FOREVER",
            "relaunchEscalationTimeoutSeconds": 1234
          },
          "upgradeStrategy": {
            "minimumHealthCapacity": 0.1,
            "maximumOverCapacity": 0.0
          }
        }
      """

      val update = fromJsonString(json)
      val create = Raml.fromRaml(AppHelpers.withoutPriorAppDefinition(update, AbsolutePathId("/app")))
      assert(update.upgradeStrategy.isDefined)
      assert(update.upgradeStrategy.map(Raml.fromRaml(_)).contains(create.upgradeStrategy))
    }

    "empty app persists existing residency" in {

      val json = """
        {
          "id": "/app",
          "role": "*",
          "args": [],
          "container": {
            "type": "DOCKER",
            "volumes": [
              {
                "containerPath": "data",
                "mode": "RW",
                "persistent": {
                  "size": 100
                }
              }
            ],
            "docker": {
              "image": "anImage"
            }
          },
          "residency": {
            "taskLostBehavior": "WAIT_FOREVER",
            "relaunchEscalationTimeoutSeconds": 1234
          }
        }
      """

      val update = fromJsonString(json)
      Raml.fromRaml(AppHelpers.withoutPriorAppDefinition(update, AbsolutePathId("/app")))
      assert(update.residency.isDefined)
    }

    "empty app update strategy on external volumes" in {
      val json =
        """
      {
        "cmd": "sleep 1000",
        "role": "*",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "/docker_storage",
              "mode": "RW",
              "external": {
                "name": "my-external-volume",
                "provider": "dvdi",
                "size": 1234
                }
              }]
        }
      }
      """

      val update = fromJsonString(json)
      val strategy = Raml.fromRaml(AppHelpers.withoutPriorAppDefinition(update, AbsolutePathId("/foo"))).upgradeStrategy
      assert(strategy == state.UpgradeStrategy.forResidentTasks)
    }

    "container change in AppUpdate should be stored" in {
      val appDef = AppDefinition(id = runSpecId, role = "*", container = Some(state.Container.Docker(image = "something")))
      // add port mappings..
      val appUpdate = AppUpdate(container =
        Some(
          Container(
            EngineType.Docker,
            docker = Some(DockerContainer(image = "something")),
            portMappings = Option(
              Seq(
                ContainerPortMapping(containerPort = 4000)
              )
            )
          )
        )
      )
      val roundTrip = Raml.fromRaml((appUpdate, appDef))
      roundTrip.container should be('nonEmpty)
      roundTrip.container.foreach { container =>
        container.portMappings should be('nonEmpty)
        container.portMappings.flatMap(_.headOption.map(_.containerPort)) should contain(4000)
      }
    }

    "app update changes kill selection" in {
      val appDef = AppDefinition(id = runSpecId, role = "*", killSelection = KillSelection.YoungestFirst)
      val update = AppUpdate(killSelection = Some(raml.KillSelection.OldestFirst))
      val result = Raml.fromRaml(update -> appDef)
      result.killSelection should be(raml.KillSelection.OldestFirst)
    }

    "not allow appUpdate with a version and other changes besides id" in {
      val vfe = intercept[ValidationFailedException](
        validateOrThrow(AppUpdate(id = Some("/test"), cmd = Some("sleep 2"), version = Some(Timestamp(2).toOffsetDateTime)))
      )
      assert(vfe.failure.violations.toString.contains("The 'version' field may only be combined with the 'id' field."))
    }
  }
}
