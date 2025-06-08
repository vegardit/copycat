#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
# SPDX-FileContributor: Sebastian Thomschke (Vegard IT GmbH)
# SPDX-License-Identifier: Apache-2.0
# SPDX-ArtifactOfProjectHomePage: https://github.com/vegardit/copycat

#####################
# Script init
#####################
set -eu

# execute script with bash if loaded with other shell interpreter
if [ -z "${BASH_VERSINFO:-}" ]; then /usr/bin/env bash "$0" "$@"; exit; fi

set -o pipefail # causes a pipeline to return the exit status of the last command in the pipe that returned a non-zero return value
set -o nounset # treat undefined variables as errors

# configure stack trace reporting
trap 'rc=$?; echo >&2 "$(date +%H:%M:%S) Error - exited with status $rc in [$BASH_SOURCE] at line $LINENO:"; cat -n $BASH_SOURCE | tail -n+$((LINENO - 3)) | head -n7' ERR

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"


#####################
# Main
#####################
cd "$SCRIPT_DIR/.."

if [[ -f .ci/release-trigger.sh ]]; then
   echo "Sourcing [.ci/release-trigger.sh]..."
   source .ci/release-trigger.sh
fi


echo
echo "###################################################"
echo "# Determining GIT branch......                    #"
echo "###################################################"
GIT_BRANCH=$(git branch --show-current)
echo "  -> GIT Branch: $GIT_BRANCH"


echo
echo "###################################################"
echo "# Configuring MAVEN_OPTS...                       #"
echo "###################################################"
MAVEN_OPTS="${MAVEN_OPTS:-}"
MAVEN_OPTS+=" -Djava.security.egd=file:/dev/./urandom" # https://stackoverflow.com/questions/58991966/what-java-security-egd-option-is-for/59097932#59097932
MAVEN_OPTS+=" -Dorg.slf4j.simpleLogger.showDateTime=true -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss,SSS" # https://stackoverflow.com/questions/5120470/how-to-time-the-different-stages-of-maven-execution/49494561#49494561
MAVEN_OPTS+=" -Xmx1024m -Djava.awt.headless=true -Djava.net.preferIPv4Stack=true -Dhttps.protocols=TLSv1.3,TLSv1.2"
echo "  -> MAVEN_OPTS: $MAVEN_OPTS"
export MAVEN_OPTS

MAVEN_CLI_OPTS="-e -U --batch-mode --show-version -s .ci/maven-settings.xml -t .ci/maven-toolchains.xml"
if [[ -n ${GITEA_ACTIONS:-} || (-n ${CI:-} && -z ${ACT:-}) ]]; then # if running on a remote CI but not on local nektos/act runner
   MAVEN_CLI_OPTS+=" --no-transfer-progress"
fi
if [[ -n ${ACT:-} ]]; then
   MAVEN_CLI_OPTS+=" -Dformatter.validate.lineending=KEEP"
fi
echo "  -> MAVEN_CLI_OPTS: $MAVEN_CLI_OPTS"


echo
echo "###################################################"
echo "# Determining current Maven project version...    #"
echo "###################################################"
# https://stackoverflow.com/questions/3545292/how-to-get-maven-project-version-to-the-bash-command-line
projectVersion=$(python -c "import xml.etree.ElementTree as ET; \
  print(ET.parse(open('pom.xml')).getroot().find(  \
  '{http://maven.apache.org/POM/4.0.0}version').text)")
echo "  -> Current Version: $projectVersion"

#
# ensure mnvw is executable
#
chmod u+x ./mvnw


#
# decide whether to perform a release build or build+deploy a snapshot version
#
if [[ ${projectVersion:-foo} == ${POM_CURRENT_VERSION:-bar} && ${MAY_CREATE_RELEASE:-false} == "true" ]]; then
   # https://stackoverflow.com/questions/8653126/how-to-increment-version-number-in-a-shell-script/21493080#21493080
   nextDevelopmentVersion="$(echo ${POM_RELEASE_VERSION} | perl -pe 's/^((\d+\.)*)(\d+)(.*)$/$1.($3+1).$4/e')-SNAPSHOT"

   SKIP_TESTS=${SKIP_TESTS:-false}

   echo
   echo "###################################################"
   echo "# Creating Maven Release...                       #"
   echo "###################################################"
   echo "  ->          Release Version: ${POM_RELEASE_VERSION}"
   echo "  -> Next Development Version: ${nextDevelopmentVersion}"
   echo "  ->           Skipping Tests: ${SKIP_TESTS}"
   echo "  ->               Is Dry-Run: ${DRY_RUN}"

   # workaround for "No toolchain found with specification [version:11, vendor:default]" during release builds
   cp -f .ci/maven-settings.xml $HOME/.m2/settings.xml
   cp -f .ci/maven-toolchains.xml $HOME/.m2/toolchains.xml

   export DEPLOY_RELEASES_TO_MAVEN_CENTRAL=false

   ./mvnw $MAVEN_CLI_OPTS "$@" \
      -DskipTests=${SKIP_TESTS} \
      -DskipITs=${SKIP_TESTS} \
      -DdryRun=${DRY_RUN} \
      -Dresume=false \
      "-Darguments=-Dmaven.deploy.skip=true -DskipTests=${SKIP_TESTS} -DskipITs=${SKIP_TESTS}" \
      -DreleaseVersion=${POM_RELEASE_VERSION} \
      -DdevelopmentVersion=${nextDevelopmentVersion} \
      help:active-profiles clean release:clean release:prepare release:perform \
      | grep -v -e "\[INFO\] Download.* from repository-restored-from-cache" `# suppress download messages from repo restored from cache ` \
      | grep -v -e "\[INFO\]  .* \[0.0[0-9][0-9]s\]" # the grep command suppresses all lines from maven-buildtime-extension that report plugins with execution time <=99ms
else
   echo
   echo "###################################################"
   echo "# Building Maven Project...                       #"
   echo "###################################################"
   ./mvnw $MAVEN_CLI_OPTS "$@" \
      help:active-profiles clean verify \
      | grep -v -e "\[INFO\] Download.* from repository-restored-from-cache" `# suppress download messages from repo restored from cache ` \
      | grep -v -e "\[INFO\]  .* \[0.0[0-9][0-9]s\]" # the grep command suppresses all lines from maven-buildtime-extension that report plugins with execution time <=99ms
fi
