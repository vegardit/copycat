/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.*;

import java.io.IOException;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.LogRecord;

import org.eclipse.jdt.annotation.Nullable;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiRenderer;

import com.vegardit.copycat.command.AbstractCommand;
import com.vegardit.copycat.command.LoggingOptionsMixin;
import com.vegardit.copycat.command.sync.SyncCommand;
import com.vegardit.copycat.command.watch.WatchCommand;
import com.vegardit.copycat.util.JdkLoggingUtils;

import net.sf.jstuff.core.Strings;
import net.sf.jstuff.core.io.StringPrintWriter;
import net.sf.jstuff.core.logging.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.RunLast;
import picocli.CommandLine.Unmatched;
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

   public static class LoggingOptions extends LoggingOptionsMixin {
      @Unmatched
      List<String> ignored = lazyNonNull();
   }

   private static final Logger LOG = Logger.create();

   @Nullable
   private static FileHandler configureLogging(final String[] args) throws IOException {
      final var loggingOptions = new LoggingOptions();
      CommandLine.populateCommand(loggingOptions, args);
      JdkLoggingUtils.configureConsoleHandler(!loggingOptions.logErrorsToStdOut, new JdkLoggingUtils.AnsiFormatter() {

         final String replaceLastLine = new Ansi().cursorUpLine().eraseLine().toString();

         @Nullable
         String lastMessage = null;

         @Override
         protected String ansiRender(final String text, final Object... args) {
            if (lastMessage != null && lastMessage.startsWith("Scanning "))
               return replaceLastLine + super.ansiRender(text, args);
            return super.ansiRender(text, args);
         }

         @Override
         public synchronized String format(final LogRecord entry) {
            try {
               return super.format(entry);
            } finally {
               lastMessage = entry.getMessage();
            }
         }
      });

      final var logFile = loggingOptions.logFile;
      if (logFile == null)
         return null;
      return JdkLoggingUtils.addFileHandler(logFile.toAbsolutePath().toString());
   }

   public static void main(final String[] args) throws Exception {
      Thread.currentThread().setName("main");

      // this is a small hack, but we need to evaluate the logging options before
      // any other component starts throwing exceptions, see https://github.com/remkop/picocli/issues/1295
      final var fileHandler = configureLogging(args);

      // enable ANSI coloring
      AnsiConsole.systemInstall();

      final var handler = new CommandLine(new CopyCatMain());
      handler.setCaseInsensitiveEnumValuesAllowed(true);
      handler.setExecutionStrategy(new RunLast());
      handler.setHelpFactory((commandSpec, colorScheme) -> new Help(commandSpec, colorScheme) {

         @Nullable
         @Override
         public String headerHeading(final Object @Nullable... params) {
            return AnsiRenderer.render(super.headerHeading(params));
         }
      });

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
            try (var sw = new StringPrintWriter()) {
               UnmatchedArgumentException.printSuggestions(ex, sw);
               final var suggestions = sw.toString();
               if (Strings.isNotBlank(suggestions)) {
                  LOG.info(Strings.trim(suggestions));
               }
            }
            LOG.info("Execute 'copycat --help' for usage help.");
         }
         return 1;
      });
      handler.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
         ex = asNonNullUnsafe(ex);
         if (LOG.isDebugEnabled() || ex instanceof UnsupportedOperationException || ex instanceof NullPointerException) {
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
