#!/usr/bin/env bash
#
# Copyright 2020 by Vegard IT GmbH, Germany, https://vegardit.com
# SPDX-License-Identifier: Apache-2.0
#
# Author: Sebastian Thomschke, Vegard IT GmbH

if ! hash native-image 2>/dev/null; then
   /usr/bin/env bash "$(dirname ${BASH_SOURCE[0]})/run-in-docker.sh" bash tools/build-native-image.sh
   exit $?
fi

cd "$(dirname ${BASH_SOURCE[0]})/../target/"

input_jar=copycat-*-fat.jar
output_binary=copycat-snapshot-linux-amd64

native-image \
  -H:ReflectionConfigurationFiles=picocli-reflections.json \
  -H:+ReportExceptionStackTraces \
  -H:+RemoveUnusedSymbols \
  --allow-incomplete-classpath \
  --no-fallback \
  --no-server \
  --verbose \
  -H:+StaticExecutableWithDynamicLibC `#https://www.graalvm.org/reference-manual/native-image/StaticImages/#build-a-mostly-static-native-image` \
  --class-path $input_jar \
  com.vegardit.copycat.CopyCatMain \
  $output_binary

ls -l $output_binary
strip --strip-unneeded $output_binary
ls -l $output_binary

chmod u+x $output_binary

./$output_binary --help
