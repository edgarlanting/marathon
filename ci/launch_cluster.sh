#!/bin/bash
set -x -e -o pipefail

# Ensure dependencies are installed.
if ! command -v jq >/dev/null; then
    echo "jq was not found. Please install it."
    exit 1
fi

# Two parameters are expected: CHANNEL and VARIANT where CHANNEL is the respective PR and
# VARIANT could be one of three custer variants: open, strict or permissive.
if [ "$#" -ne 3 ]; then
    echo "Expected 3 parameters: launch_cluster.sh <channel> <variant> <deployment-name>"
    echo "e.g. SHAKEDOWN_SSH_KEY_FILE='test.pem' launch_cluster.sh 'testing/pull/1739' 'open' 'si-testing-open'"
    exit 1
fi

CHANNEL="$1"
VARIANT="$2"
DEPLOYMENT_NAME="$3"
CONFIG_PATH="$DEPLOYMENT_NAME.yaml"
INFO_PATH="$DEPLOYMENT_NAME.info.json"

if [ "$VARIANT" == "open" ]; then
  INSTALLER="https://downloads.dcos.io/dcos/${CHANNEL}/dcos_generate_config.sh"
else
  INSTALLER="https://downloads.mesosphere.com/dcos-enterprise/${CHANNEL}/dcos_generate_config.ee.sh"
fi

echo "Using: ${INSTALLER}"

# Create config.yaml for dcos-launch.
export INSTALLER_ESCAPED=$(echo $INSTALLER | sed -e 's/[]\/$*.^[]/\\&/g')
export DEPLOYMENT_NAME_ESCAPED=$(echo $DEPLOYMENT_NAME | sed -e 's/[]\/$*.^[]/\\&/g')
sed -e "s/%DEPLOYMENT_NAME%/$DEPLOYMENT_NAME_ESCAPED/g" \
-e "s/%INSTALLER%/$INSTALLER_ESCAPED/g" \
<<EOF > "$CONFIG_PATH"
---
launch_config_version: 1
deployment_name: %DEPLOYMENT_NAME%
installer_url: %INSTALLER%
provider: onprem
platform: aws
aws_region: us-west-2
os_name: cent-os-7-dcos-prereqs
key_helper: true
instance_type: m4.large
num_public_agents: 1
num_private_agents: 3
num_masters: 3
dcos_config:
    cluster_name: %DEPLOYMENT_NAME%
    resolvers:
        - 8.8.4.4
        - 8.8.8.8
    dns_search: mesos
    master_discovery: static
    exhibitor_storage_backend: static
    rexray_config_preset: aws
    mesos_seccomp_enabled: true
    mesos_seccomp_profile_name: default.json
EOF

# Append license and security mode for EE variants.
if [ "$VARIANT" != "open" ]; then
    echo "    license_key_contents: $DCOS_LICENSE" >> "$CONFIG_PATH"
    echo "    security: $VARIANT" >> "$CONFIG_PATH"
fi

# Create cluster.
if ! pipenv run dcos-launch -c "$CONFIG_PATH" -i "$INFO_PATH" create; then
  echo "Failed to launch a cluster via dcos-launch"
  exit 2
fi
if ! pipenv run dcos-launch -i "$INFO_PATH" wait; then
  exit 3
fi

# Extract SSH key
jq -r .ssh_private_key "$INFO_PATH" > "$SHAKEDOWN_SSH_KEY_FILE"

# Return dcos_url
CLUSTER_IP="$(pipenv run dcos-launch -i "$INFO_PATH" describe | jq -r ".masters[0].public_ip")"
echo "Launched cluster with IP $CLUSTER_IP"
echo "$CLUSTER_IP"
