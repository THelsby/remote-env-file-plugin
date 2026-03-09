#!/bin/bash
set -euo pipefail

PLUGIN_NAME="remote-env-file"
REF_PLUGIN="/usr/share/jenkins/ref/plugins/${PLUGIN_NAME}.jpi"
HOME_PLUGIN="${JENKINS_HOME}/plugins/${PLUGIN_NAME}.jpi"
EXPLODED_DIR="${JENKINS_HOME}/plugins/${PLUGIN_NAME}"

mkdir -p "${JENKINS_HOME}/plugins"

if [[ -f "${REF_PLUGIN}" ]]; then
  cp "${REF_PLUGIN}" "${HOME_PLUGIN}"
  rm -rf "${EXPLODED_DIR}"
fi

exec /usr/local/bin/jenkins.sh
