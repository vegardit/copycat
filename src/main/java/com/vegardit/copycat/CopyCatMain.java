/*
 * Copyright 2020 by Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.util.logging.FileHandler;

import org.apache.commons.lang3.ArrayUtils;

import com.vegardit.copycat.command.AbstractCommand;
import com.vegardit.copycat.command.SyncCommand;
import com.vegardit.copycat.command.WatchCommand;
import com.vegardit.copycat.util.JdkLoggingUtils;

import net.sf.jstuff.core.Strings;
import net.sf.jstuff.core.logging.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.RunLast;
import picocli.CommandLine.UnmatchedArgumentException;
import picocli.jansi.graalvm.AnsiConsole;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
@Command(name = "copycat", //
   description = "The fast and sweet file synchronization tool.", //
   synopsisSubcommandLabel = "COMMAND", //
   subcommands = { //
      SyncCommand.class, //
      WatchCommand.class //
   } //
)
public class CopyCatMain extends AbstractCommand {

   private static final Logger LOG = Logger.create();

   /*
    * This block prevents the Maven Shade plugin to remove the specified classes
    * https://stackoverflow.com/questions/8698814/configure-maven-shade-minimizejar-to-include-class-files
    */
   static {
      @SuppressWarnings("unused")
      final Class<?>[] classes = new Class<?>[] { //
         com.thoughtworks.paranamer.Paranamer.class, //
      };
   }

   public static void main(final String[] args) throws Exception {
      Thread.currentThread().setName("main");

      // this is a hack, but we need to evaluate these options before
      // any other component starts throwing exceptions
      FileHandler fileHandler = null;
      {
         JdkLoggingUtils.configureConsoleHandler(!ArrayUtils.contains(args, "--log-errors-to-stdout"));
         final var logFilePos = ArrayUtils.indexOf(args, "--log-file");
         if (logFilePos > -1 && logFilePos + 1 < args.length) {
            final var logFilePath = args[logFilePos + 1];
            fileHandler = JdkLoggingUtils.addFileHandler(Paths.get(logFilePath));
         }
      }

      // enable ANSI coloring
      AnsiConsole.systemInstall();

      final var handler = new CommandLine(new CopyCatMain());
      handler.setCaseInsensitiveEnumValuesAllowed(true);
      handler.setExecutionStrategy(new RunLast());

      /*
       * custom exception handlers that use a logger instead of directly writing to stdout/stderr
       */
      handler.setParameterExceptionHandler((ex, args2) -> {
         if (args2.length == 0) {
            CommandLine.usage(handler, System.err);
            System.err.println();
            LOG.error(ex.getMessage());
         } else {
            LOG.error(ex.getMessage());
            final var sw = new StringWriter();
            UnmatchedArgumentException.printSuggestions(ex, new PrintWriter(sw));
            final var suggestions = sw.toString();
            if (Strings.isNotBlank(suggestions)) {
               LOG.info(Strings.trim(suggestions));
            }
            LOG.info("Execute 'copycat --help' for usage help.");
         }
         return 1;
      });
      handler.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
         if (LOG.isDebugEnabled()) {
            LOG.error(ex); // log with stacktrace
         } else {
            LOG.error(ex.getMessage());
         }
         return 1;
      });
      final var exitCode = handler.execute(args);
      if (fileHandler != null) {
         fileHandler.close();
      }
      System.exit(exitCode);
   }

   @Override
   protected void execute() throws Exception {
      throw new ParameterException(commandSpec.commandLine(), "Missing required subcommand.");
   }
}
