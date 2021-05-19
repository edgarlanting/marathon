package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.api.serialization.VolumeSerializer
import mesosphere.marathon.state.{DiskType, ExternalVolume, Volume, VolumeWithMount}
import org.scalatest.Inside
import org.scalatest.prop.TableDrivenPropertyChecks

class VolumeConversionTest extends UnitTest with TableDrivenPropertyChecks with Inside {

  def convertToProtobufThenToRAML(volumeWithMount: => state.VolumeWithMount[Volume], raml: => AppVolume): Unit = {
    "convert to protobuf, then to RAML" in {
      val proto = VolumeSerializer.toProto(volumeWithMount)
      val proto2Raml = proto.toRaml
      proto2Raml should be(raml)
    }
  }

  "core HostVolume conversion" when {
    val hostVolume = state.HostVolume(None, "/host")
    val mount = state.VolumeMount(None, "/container")
    val volume = state.VolumeWithMount(hostVolume, mount)
    "converting to RAML" should {
      val raml = volume.toRaml[AppVolume]
      behave like convertToProtobufThenToRAML(volume, raml)
      "convert all fields to RAML" in {
        raml shouldBe a[AppHostVolume]
        val ramlDocker = raml.asInstanceOf[AppHostVolume]
        ramlDocker.containerPath should be(mount.mountPath)
        ramlDocker.hostPath should be(hostVolume.hostPath)
        ramlDocker.mode should be(ReadMode.Rw)
      }
    }
  }

  "RAML docker volume conversion" when {
    val volume = AppHostVolume(containerPath = "/container", hostPath = "/host", mode = ReadMode.Rw)
    "converting to core HostVolume" should {
      val (hostVolume, mount) = Some(volume.fromRaml).collect {
        case state.VolumeWithMount(v: state.HostVolume, m) => (v, m)
      }.getOrElse(fail("expected docker volume"))

      "convert all fields from RAML to core" in {
        mount.mountPath should be(volume.containerPath)
        hostVolume.hostPath should be(volume.hostPath)
        mount.readOnly should be(false)
      }
    }
  }

  "core ExternalVolume conversion" when {
    val external = state.DVDIExternalVolumeInfo(Some(123L), "external", "foo", Map("foo" -> "bla"), shared = true)
    val externalVolume = state.ExternalVolume(None, external)
    val mount = state.VolumeMount(None, "/container")
    val volume = state.VolumeWithMount(externalVolume, mount)
    "converting to RAML" should {
      val raml = volume.toRaml[AppVolume]
      behave like convertToProtobufThenToRAML(volume, raml)
      "convert all fields to RAML" in {
        raml shouldBe a[AppExternalVolume]
        val externalRaml = raml.asInstanceOf[AppExternalVolume]
        externalRaml.containerPath should be(mount.mountPath)
        externalRaml.mode should be(ReadMode.Rw)
        inside(externalRaml.external) {
          case vol: DVDIExternalVolumeInfo =>
            vol.name should be(Some(external.name))
            vol.options should be(external.options)
            vol.provider should be(Some(external.provider))
            vol.size should be(external.size)
            vol.shared should be(true)
        }
      }
    }
  }

  "RAML CSI external volume conversion" should {
    val volumeInfo = CSIExternalVolumeInfo(
      name = "csi-volume",
      provider = "csi",
      options = CSIExternalVolumeInfoOptions(
        pluginName = "csi-plugin",
        capability = CSICapability(accessMode = "MULTI_NODE_READER_ONLY", accessType = "block"),
        nodeStageSecret = Map("key" -> "secret-stage-key"),
        nodePublishSecret = Map("key" -> "secret-publish-key"),
        volumeContext = Map("a" -> "context")
      )
    )

    val volume = AppExternalVolume(
      "/container",
      volumeInfo,
      ReadMode.Ro
    )

    "convert a CSI block volume properly" in {
      inside(volume.fromRaml) {
        case VolumeWithMount(ExternalVolume(_, external: state.CSIExternalVolumeInfo), _) =>
          external.name shouldBe (volumeInfo.name)
          external.provider shouldBe "csi"
          external.pluginName shouldBe volumeInfo.options.pluginName
          external.accessType shouldBe state.CSIExternalVolumeInfo.BlockAccessType
          external.accessMode shouldBe state.CSIExternalVolumeInfo.AccessMode.MULTI_NODE_READER_ONLY
          external.nodeStageSecret shouldBe volumeInfo.options.nodeStageSecret
          external.nodePublishSecret shouldBe volumeInfo.options.nodePublishSecret
          external.volumeContext shouldBe volumeInfo.options.volumeContext
      }
    }

    "preserve all values during round-trip conversion" in {
      val roundTripConverted = volume.fromRaml.toRaml
      roundTripConverted shouldBe volume
    }
  }

  "RAML generic external volume conversion" when {
    val volumeInfo = DVDIExternalVolumeInfo(Some(1L), Some("vol-name"), Some("provider"), Map("foo" -> "bla"), shared = true)
    val volume = AppExternalVolume(
      "/container",
      volumeInfo,
      ReadMode.Rw
    )
    "converting to core ExternalVolume" should {
      val (externalVolume, mount) = Some(volume.fromRaml).collect {
        case state.VolumeWithMount(v: state.ExternalVolume, m) => (v, m)
      }.getOrElse(fail("expected ExternalVolume"))
      "covert all fields from RAML to core" in {
        mount.mountPath should be(volume.containerPath)
        mount.readOnly should be(false)
        inside(externalVolume.external) {
          case vol: state.DVDIExternalVolumeInfo =>
            vol.name should be(volumeInfo.name.head)
            vol.provider should be(volumeInfo.provider.head)
            vol.size should be(volumeInfo.size)
            vol.options should be(volumeInfo.options)
            vol.shared should be(volumeInfo.shared)
        }
      }
    }
  }

  "core PersistentVolume conversion" when {
    val persistent = state.PersistentVolumeInfo(123L, Some(1234L), state.DiskType.Path, Some("ssd-fast"))
    val persistentVolume = state.PersistentVolume(None, persistent)
    val mount = state.VolumeMount(None, "/container")
    val volume = state.VolumeWithMount(persistentVolume, mount)
    "converting to RAML" should {
      val raml = volume.toRaml[AppVolume]
      behave like convertToProtobufThenToRAML(volume, raml)
      "convert all fields to RAML" in {
        raml shouldBe a[AppPersistentVolume]
        val persistentRaml = raml.asInstanceOf[AppPersistentVolume]
        persistentRaml.containerPath should be(mount.mountPath)
        persistentRaml.mode should be(ReadMode.Rw)
        persistentRaml.persistent.`type` should be(Some(PersistentVolumeType.Path))
        persistentRaml.persistent.size should be(persistent.size)
        persistentRaml.persistent.maxSize should be(persistent.maxSize)
        persistentRaml.persistent.profileName should be(persistent.profileName)
        persistentRaml.persistent.constraints should be(empty)
      }
    }
  }

  "RAML persistent volume conversion for apps" when {
    val volume = AppPersistentVolume(
      "/container",
      PersistentVolumeInfo(None, size = 123L, maxSize = Some(1234L), profileName = None, constraints = Set.empty),
      ReadMode.Rw
    )
    "converting from RAML" should {
      val (persistent, mount) = Some(volume.fromRaml).collect {
        case state.VolumeWithMount(v: state.PersistentVolume, m) => (v, m)
      }.getOrElse(fail("expected PersistentVolume"))
      "convert all fields to core" in {
        mount.mountPath should be(volume.containerPath)
        mount.readOnly should be(false)
        persistent.persistent.`type` should be(state.DiskType.Root)
        persistent.persistent.size should be(volume.persistent.size)
        persistent.persistent.maxSize should be(volume.persistent.maxSize)
        persistent.persistent.profileName should be(volume.persistent.profileName)
        persistent.persistent.constraints should be(Set.empty)
      }
    }
  }

  def persistentVolumeFrom(raml: AppPersistentVolume): state.PersistentVolume = {
    Some(raml.fromRaml).collect {
      case state.VolumeWithMount(v: state.PersistentVolume, _) => v
    }.getOrElse(fail("expected PersistentVolume"))
  }

  def persistentVolumeFrom(raml: PodPersistentVolume): state.PersistentVolume = {
    Some(raml.asInstanceOf[PodVolume].fromRaml).collect { case pv: state.PersistentVolume => pv }
      .getOrElse(fail("expected PersistentVolume"))
  }

  val diskProfileCombinations = Table(
    ("configured disk type", "configured profile", "expected disk type"),
    (None, None, DiskType.Root),
    (Some(PersistentVolumeType.Root), None, DiskType.Root),
    (Some(PersistentVolumeType.Path), None, DiskType.Path),
    (Some(PersistentVolumeType.Mount), None, DiskType.Mount),
    (None, Some("ssd"), DiskType.Mount),
    (Some(PersistentVolumeType.Root), Some("ssd"), DiskType.Root), // this won't work with DSS
    (Some(PersistentVolumeType.Path), Some("ssd"), DiskType.Path), // this won't work with DSS
    (Some(PersistentVolumeType.Mount), Some("ssd"), DiskType.Mount)
  )

  "test disk type profile combinations" when {
    forAll(diskProfileCombinations) {
      (configuredDiskType: Option[PersistentVolumeType], configuredProfile: Option[String], expectedDiskType: DiskType) =>
        s"configuring disk type <$configuredDiskType> with profile <$configuredProfile>" should {
          s"result in expected disk type <$expectedDiskType> for apps" in {
            val raml = AppPersistentVolume(
              "/container",
              PersistentVolumeInfo(
                `type` = configuredDiskType,
                profileName = configuredProfile,
                size = 123L,
                maxSize = Some(1234L),
                constraints = Set.empty
              ),
              ReadMode.Rw
            )
            val volume = persistentVolumeFrom(raml)
            volume.persistent.`type` shouldBe expectedDiskType
          }
          s"result in expected disk type <$expectedDiskType> for pods" in {
            val raml = PodPersistentVolume(
              "/container",
              PersistentVolumeInfo(
                `type` = configuredDiskType,
                profileName = configuredProfile,
                size = 123L,
                maxSize = Some(1234L),
                constraints = Set.empty
              )
            )
            val volume = persistentVolumeFrom(raml)
            volume.persistent.`type` shouldBe expectedDiskType
          }
        }
    }
  }

  "RAML persistent volume conversion for pods" when {

    "converting PersistentVolume from RAML" should {
      val ramlVolume = PodPersistentVolume(
        "/container",
        PersistentVolumeInfo(None, size = 123L, maxSize = Some(1234L), profileName = None, constraints = Set.empty)
      )
      val persistentVolume = Some(ramlVolume.asInstanceOf[PodVolume].fromRaml).collect { case pv @ state.PersistentVolume(_, _) => pv }
        .getOrElse(fail("expected PersistentVolume"))
      "convert all fields to core" in {
        persistentVolume.name should be(Some(ramlVolume.name))
        val info = persistentVolume.persistent
        info.`type` should be(state.DiskType.Root)
        info.size should be(ramlVolume.persistent.size)
        info.maxSize should be(ramlVolume.persistent.maxSize)
        info.profileName should be(ramlVolume.persistent.profileName)
        info.constraints should be(Set.empty)
      }
    }

    "converting PersistentVolume to RAML" should {
      val persistentVolume = state.PersistentVolume(
        Some("/container"),
        state.PersistentVolumeInfo(
          size = 123L,
          maxSize = Some(1234L),
          `type` = DiskType.Mount,
          profileName = Some("ssd-fast"),
          constraints = Set.empty
        )
      )
      val ramlVolume = Some(persistentVolume.asInstanceOf[Volume].toRaml[PodVolume]).collect { case pv @ PodPersistentVolume(_, _) => pv }
        .getOrElse(fail("expected PodPersistentVolume"))
      "convert all fields to core" in {
        persistentVolume.name should be(Some(ramlVolume.name))
        val info = persistentVolume.persistent
        info.`type` should be(state.DiskType.Mount)
        info.size should be(ramlVolume.persistent.size)
        info.maxSize should be(ramlVolume.persistent.maxSize)
        info.profileName should be(ramlVolume.persistent.profileName)
        info.constraints should be(Set.empty)
      }
    }

    "converting EphemeralVolume from RAML" should {
      val ramlVolume = PodEphemeralVolume("/container")
      val ephemeralVolume = Some(ramlVolume.asInstanceOf[PodVolume].fromRaml).collect { case ev @ state.EphemeralVolume(_) => ev }
        .getOrElse(fail("expected EphemeralVolume"))

      "convert all fields to core" in {
        ephemeralVolume.name should be(Some(ramlVolume.name))
      }
    }

    "converting EphemeralVolume to RAML" should {
      val ephemeralVolume = state.EphemeralVolume(Some("/container"))
      val ramlVolume = Some(ephemeralVolume.asInstanceOf[Volume].toRaml[PodVolume]).collect { case ev @ PodEphemeralVolume(_) => ev }
        .getOrElse(fail("expected PodEphemeralVolume"))

      "convert all fields to core" in {
        ephemeralVolume.name should be(Some(ramlVolume.name))
      }
    }

    "converting HostVolume from RAML" should {
      val ramlVolume = PodHostVolume("/container", "/path")
      val hostVolume = Some(ramlVolume.asInstanceOf[PodVolume].fromRaml).collect { case hv @ state.HostVolume(_, _) => hv }
        .getOrElse(fail("expected HostVolume"))

      "convert all fields to core" in {
        hostVolume.name should be(Some(ramlVolume.name))
        hostVolume.hostPath should be(ramlVolume.host)
      }
    }

    "converting HostVolume to RAML" should {
      val hostVolume = state.HostVolume(Some("/container"), "/path")
      val ramlVolume = Some(hostVolume.asInstanceOf[Volume].toRaml[PodVolume]).collect { case hv @ PodHostVolume(_, _) => hv }
        .getOrElse(fail("expected PodHostVolume"))

      "convert all fields to core" in {
        hostVolume.name should be(Some(ramlVolume.name))
        hostVolume.hostPath should be(ramlVolume.host)
      }
    }

    "converting SecretVolume from RAML" should {
      val ramlVolume = PodSecretVolume("/container", "secret")
      val secretVolume = Some(ramlVolume.asInstanceOf[PodVolume].fromRaml).collect { case hv @ state.SecretVolume(_, _) => hv }
        .getOrElse(fail("expected SecretVolume"))

      "convert all fields to core" in {
        secretVolume.name should be(Some(ramlVolume.name))
        secretVolume.secret should be(ramlVolume.secret)
      }
    }

    "converting SecretVolume to RAML" should {
      val secretVolume = state.SecretVolume(Some("/container"), "secret")
      val ramlVolume = Some(secretVolume.asInstanceOf[Volume].toRaml[PodVolume]).collect { case sv @ PodSecretVolume(_, _) => sv }
        .getOrElse(fail("expected PodSecretVolume"))

      "convert all fields to core" in {
        secretVolume.name should be(Some(ramlVolume.name))
        secretVolume.secret should be(ramlVolume.secret)
      }
    }
  }
}
