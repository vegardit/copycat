#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
# SPDX-License-Identifier: Apache-2.0
#
# Author: Sebastian Thomschke, Vegard IT GmbH

set -eu

if ! hash native-image 2>/dev/null; then
   /usr/bin/env bash "$(dirname ${BASH_SOURCE[0]})/run-in-docker.sh" bash tools/build-native-image.sh
   exit $?
fi

cd "$(dirname ${BASH_SOURCE[0]})/../target/"

input_jar=copycat-*-fat-minimized.jar
output_binary=copycat-snapshot-linux-amd64

# https://www.graalvm.org/22.3/reference-manual/native-image/metadata/AutomaticMetadataCollection/#tracing-agent
rm -rf native-image-metadata
function traceCall() {
  echo "Tracing copycat ${@}..."
  java -agentlib:native-image-agent=config-merge-dir=native-image-metadata/META-INF/native-image/ -jar $input_jar ${@} >/dev/null
}

traceCall --help
traceCall --help
traceCall --version
traceCall sync --help
traceCall watch --help

rm -rf workdir
install -D /dev/null workdir/source/dir1/file1
traceCall sync workdir/source workdir/target

native-image \
  -H:NativeLinkerOption=-no-pie `# do not to generate Position Independent Executables (PIE)` \
  -H:ReflectionConfigurationFiles=picocli-reflections.json \
  -H:Log=registerResource:3 \
  -H:+ReportExceptionStackTraces \
  -H:+RemoveUnusedSymbols \
  -H:+StaticExecutableWithDynamicLibC `# https://www.graalvm.org/reference-manual/native-image/StaticImages/#build-a-mostly-static-native-image` \
  -H:DashboardDump=copycat -H:+DashboardAll `# https://www.graalvm.org/22.3/reference-manual/native-image/guides/use-graalvm-dashboard/` \
  --allow-incomplete-classpath \
  --no-fallback \
  --no-server \
  --verbose \
  --report-unsupported-elements-at-runtime `# avoid: Unsupported type sun.awt.X11.XBaseWindow is reachable` \
  -Dfile.encoding=UTF-8 \
  --class-path native-image-metadata \
  --class-path $input_jar \
  com.vegardit.copycat.CopyCatMain \
  $output_binary


ls -l $output_binary
strip --strip-unneeded $output_binary
chmod 775 $output_binary
ls -l $output_binary


./$output_binary --help
./$output_binary sync --help
./$output_binary watch --help
./$output_binary --version
