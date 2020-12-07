@echo off
REM Copyright (c) 2020 Vegard IT GmbH (https://vegardit.com) and contributors.
REM SPDX-License-Identifier: Apache-2.0
REM Author: Sebastian Thomschke, Vegard IT GmbH

for /f tokens^=2-5^ delims^=.-_^" %%j in ('java -fullversion 2^>^&1') do set "JAVA_MAJOR_VERSION=%%j"

if %JAVA_MAJOR_VERSION% LSS 11 (
  echo ERROR: Java 11 or higher must be on PATH.
  exit /b
)

if exist "_LOCAL\env.cmd" (
  call _LOCAL\env.cmd
)

if not exist target\classes (
  mvn -Pfast-build compile
)

mvn exec:java ^
  -Dexec.mainClass="com.vegardit.copycat.CopyCatMain" ^
  -Dexec.classpathScope=runtime ^
  -Dexec.args="%*"
