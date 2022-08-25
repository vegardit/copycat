@echo off
REM SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
REM SPDX-License-Identifier: Apache-2.0
REM Author: Sebastian Thomschke, Vegard IT GmbH

for /f tokens^=2-5^ delims^=.-_^" %%j in ('java -fullversion 2^>^&1') do set "JAVA_MAJOR_VERSION=%%j"

if %JAVA_MAJOR_VERSION% LSS 11 (
  echo ERROR: Java 11 or higher must be on PATH.
  exit /b
)


set ROOT=%~dp0

if exist "%ROOT%_LOCAL\env.cmd" (
  call _LOCAL\env.cmd
)

if not exist "%ROOT%target\copycat-*-SNAPSHOT-fat-minimized.jar" (
  mvn -Pfast-build compile
)

for /F %%I in ('dir "%ROOT%target\copycat-*-SNAPSHOT-fat-minimized.jar" /b /O-N') do (
  set "JAR=%ROOT%target\%%I"
  goto :found_jar
)
:found_jar

java -jar "%JAR%" %*
