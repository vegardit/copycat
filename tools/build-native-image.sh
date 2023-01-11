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

input_jar=copycat-*-fat.jar
output_binary=copycat-snapshot-linux-amd64


native-image \
  -H:NativeLinkerOption=-no-pie `#do not to generate Position Independent Executables (PIE)` \
  -H:ReflectionConfigurationFiles=picocli-reflections.json \
  -H:Log=registerResource:3 \
  -H:+ReportExceptionStackTraces \
  -H:+RemoveUnusedSymbols \
  --allow-incomplete-classpath \
  --no-fallback \
  --no-server \
  --verbose \
  -H:+StaticExecutableWithDynamicLibC `#https://www.graalvm.org/reference-manual/native-image/StaticImages/#build-a-mostly-static-native-image` \
  --initialize-at-build-time=org.slf4j \
  --initialize-at-build-time=net.sf.jstuff.core.collection.WeakIdentityHashMap \
  --initialize-at-build-time=net.sf.jstuff.core.logging \
  --initialize-at-build-time=net.sf.jstuff.core.reflection.StackTrace \
  --initialize-at-build-time=com.vegardit.copycat.command.sync.AbstractSyncCommand \
  --initialize-at-build-time=com.vegardit.copycat.command.watch.WatchCommand \
  --report-unsupported-elements-at-runtime `# avoid: Unsupported type sun.awt.X11.XBaseWindow is reachable` \
  -Dnet.sf.jstuff.core.logging.Logger.preferSLF4J=false \
  -Dfile.encoding=UTF-8 \
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
