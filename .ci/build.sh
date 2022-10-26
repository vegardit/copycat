#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
# SPDX-License-Identifier: Apache-2.0
#
# @author Sebastian Thomschke, Vegard IT GmbH

#####################
# Script init
#####################
set -eu

# execute script with bash if loaded with other shell interpreter
if [ -z "${BASH_VERSINFO:-}" ]; then /usr/bin/env bash "$0" "$@"; exit; fi

set -o pipefail

# configure stack trace reporting
trap 'rc=$?; echo >&2 "$(date +%H:%M:%S) Error - exited with status $rc in [$BASH_SOURCE] at line $LINENO:"; cat -n $BASH_SOURCE | tail -n+$((LINENO - 3)) | head -n7' ERR

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"


#####################
# Main
#####################

if [[ -f .ci/release-trigger.sh ]]; then
   echo "Sourcing [.ci/release-trigger.sh]..."
   source .ci/release-trigger.sh
fi

cd $(dirname $0)/..

echo
echo "###################################################"
echo "# Determining GIT branch......                    #"
echo "###################################################"
GIT_BRANCH=$(git branch --show-current)
echo "  -> GIT Branch: $GIT_BRANCH"; echo


if ! hash mvn 2>/dev/null; then
   echo
   echo "###################################################"
   echo "# Determinig latest Maven version...              #"
   echo "###################################################"
   #MAVEN_VERSION=$(curl -sSf https://repo1.maven.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml | grep -oP '(?<=latest>).*(?=</latest)')
   MAVEN_VERSION=$(curl -sSf https://dlcdn.apache.org/maven/maven-3/ | grep -oP '(?<=>)[0-9.]+(?=/</a)' | tail -1)
   echo "  -> Latest Maven Version: ${MAVEN_VERSION}"
   if [[ ! -e $HOME/.m2/bin/apache-maven-$MAVEN_VERSION ]]; then
      echo
      echo "###################################################"
      echo "# Installing Maven version $MAVEN_VERSION...               #"
      echo "###################################################"
      mkdir -p $HOME/.m2/bin/
      #maven_download_url="https://repo1.maven.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
      maven_download_url="https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
      echo "Downloading [$maven_download_url]..."
      curl -fsSL $maven_download_url | tar zxv -C $HOME/.m2/bin/
   fi
   export M2_HOME=$HOME/.m2/bin/apache-maven-$MAVEN_VERSION
   export PATH=$M2_HOME/bin:$PATH
fi


echo
echo "###################################################"
echo "# Configuring JDK Class Data Sharing...           #"
echo "###################################################"
java_version=$(java -version 2>&1)
echo "$java_version"
# https://docs.oracle.com/javase/8/docs/technotes/guides/vm/class-data-sharing.html
jdk_version_checksum=$(echo "$java_version" | md5sum | cut -f1 -d" ")
if [[ ! -f $HOME/.xshare/$jdk_version_checksum ]]; then
   echo "  -> Generating shared class data archive..."
   mkdir -p $HOME/.xshare
   java -Xshare:dump -XX:+UnlockDiagnosticVMOptions -XX:SharedArchiveFile=$HOME/.xshare/$jdk_version_checksum
else
   echo "  -> Reusing shared class data archive..."
fi
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Xshare:on -XX:+UnlockDiagnosticVMOptions -XX:SharedArchiveFile=$HOME/.xshare/$jdk_version_checksum"


echo
echo "###################################################"
echo "# Configuring MAVEN_OPTS...                       #"
echo "###################################################"
MAVEN_OPTS="${MAVEN_OPTS:-}"
MAVEN_OPTS="$MAVEN_OPTS -XX:+TieredCompilation -XX:TieredStopAtLevel=1" # https://zeroturnaround.com/rebellabs/your-maven-build-is-slow-speed-it-up/
MAVEN_OPTS="$MAVEN_OPTS -Djava.security.egd=file:/dev/./urandom" # https://stackoverflow.com/questions/58991966/what-java-security-egd-option-is-for/59097932#59097932
MAVEN_OPTS="$MAVEN_OPTS -Dorg.slf4j.simpleLogger.showDateTime=true -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss,SSS" # https://stackoverflow.com/questions/5120470/how-to-time-the-different-stages-of-maven-execution/49494561#49494561
export MAVEN_OPTS="$MAVEN_OPTS -Xmx1024m -Djava.awt.headless=true -Djava.net.preferIPv4Stack=true -Dhttps.protocols=TLSv1.2"
echo "  -> MAVEN_OPTS: $MAVEN_OPTS"

MAVEN_CLI_OPTS="-e -U --batch-mode --show-version --no-transfer-progress -s .ci/maven-settings.xml -t .ci/maven-toolchains.xml"


echo
echo "###################################################"
echo "# Determining current Maven project version...    #"
echo "###################################################"
# https://stackoverflow.com/questions/3545292/how-to-get-maven-project-version-to-the-bash-command-line
projectVersion="$(mvn -s .ci/maven-settings.xml help:evaluate -Dexpression=project.version -q -DforceStdout)"
echo "  -> Current Version: $projectVersion"

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

   mvn $MAVEN_CLI_OPTS "$@" \
      -DskipTests=${SKIP_TESTS} \
      -DskipITs=${SKIP_TESTS} \
      -DdryRun=${DRY_RUN} \
      -Dresume=false \
      "-Darguments=-Dmaven.deploy.skip=true -DskipTests=${SKIP_TESTS} -DskipITs=${SKIP_TESTS}" \
      -DreleaseVersion=${POM_RELEASE_VERSION} \
      -DdevelopmentVersion=${nextDevelopmentVersion} \
      help:active-profiles clean release:clean release:prepare release:perform \
      | grep -v -e "\[INFO\]  .* \[0.0[0-9][0-9]s\]" # the grep command suppresses all lines from maven-buildtime-extension that report plugins with execution time <=99ms
else
   echo
   echo "###################################################"
   echo "# Building Maven Project...                       #"
   echo "###################################################"
   mvn $MAVEN_CLI_OPTS "$@" \
      help:active-profiles clean verify \
      | grep -v -e "\[INFO\]  .* \[0.0[0-9][0-9]s\]" # the grep command suppresses all lines from maven-buildtime-extension that report plugins with execution time <=99ms
fi
