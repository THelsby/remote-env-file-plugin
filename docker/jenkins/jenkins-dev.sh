#!/bin/bash
set -euo pipefail

PLUGIN_NAME="remote-env-file"
REF_PLUGIN="/usr/share/jenkins/ref/plugins/${PLUGIN_NAME}.jpi"
HOME_PLUGIN="${JENKINS_HOME}/plugins/${PLUGIN_NAME}.jpi"
EXPLODED_DIR="${JENKINS_HOME}/plugins/${PLUGIN_NAME}"
REF_INIT_DIR="/usr/share/jenkins/ref/init.groovy.d"
HOME_INIT_DIR="${JENKINS_HOME}/init.groovy.d"

mkdir -p "${JENKINS_HOME}/plugins"
mkdir -p "${HOME_INIT_DIR}"

if [[ -f "${REF_PLUGIN}" ]]; then
  cp "${REF_PLUGIN}" "${HOME_PLUGIN}"
  rm -rf "${EXPLODED_DIR}"
fi

if [[ -d "${REF_INIT_DIR}" ]]; then
  cp -R "${REF_INIT_DIR}/." "${HOME_INIT_DIR}/"
fi

exec /usr/local/bin/jenkins.sh
