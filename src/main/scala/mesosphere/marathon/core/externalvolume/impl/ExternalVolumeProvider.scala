package mesosphere.marathon
package core.externalvolume.impl

import com.wix.accord.Validator
import mesosphere.marathon.state.{AppDefinition, ExternalVolume, RootGroup, VolumeMount}
import org.apache.mesos.Protos

/**
  * Validations for external volumes on different levels.
  */
private[externalvolume] trait ExternalVolumeValidations {
  def rootGroup: Validator[RootGroup]
  def app: Validator[AppDefinition]
  def volume(volumeMount: VolumeMount): Validator[ExternalVolume]
  def ramlVolume(container: raml.Container): Validator[raml.AppExternalVolume]
  def ramlApp: Validator[raml.App]
}

/**
  * ExternalVolumeProvider is an interface implemented by external storage volume providers
  */
private[externalvolume] trait ExternalVolumeProvider {
  def name: String

  def validations: ExternalVolumeValidations

  /** build converts the given volume and mount to a Mesos volume **/
  def build(v: ExternalVolume, mount: VolumeMount): Protos.Volume
}
