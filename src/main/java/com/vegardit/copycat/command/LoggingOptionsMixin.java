/*
 * Copyright 2020 by Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command;

import java.nio.file.Path;

import picocli.CommandLine.Option;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public class LoggingOptionsMixin {

   @Option(names = "--log-file", description = "Write console output also to the given log file..")
   public Path logFile;

   @Option(names = "--log-errors-to-stdout", description = "Log errors to stdout instead of stderr.")
   public boolean logErrorsToStdOut;
}
