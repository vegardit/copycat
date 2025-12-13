/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permission;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.eclipse.jdt.annotation.NonNull;
import org.junit.jupiter.api.Test;

/**
 * {@link CopyCatMain} integration test
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */

@SuppressWarnings("removal")
class CopyCatMainITest {

   private record MainResult(int exitCode, String logOutput) {
   }

   @SuppressWarnings("resource")
   private static String generateMainHelp() {
      final var cmd = new picocli.CommandLine(new CopyCatMain());
      final var stdOut = new ByteArrayOutputStream();
      cmd.setOut(new PrintWriter(stdOut, true, StandardCharsets.UTF_8));
      cmd.usage(cmd.getOut(), picocli.CommandLine.Help.Ansi.OFF);
      return stdOut.toString(StandardCharsets.UTF_8);
   }

   private static MainResult runMain(final String... args) throws IOException {
      final var originalOut = System.out;
      final var originalErr = System.err;
      final var originalSecurityManager = System.getSecurityManager();

      final var rootLogger = Logger.getLogger("");
      final @NonNull Handler[] rootHandlersBefore = rootLogger.getHandlers();
      final boolean useParentHandlersBefore = rootLogger.getUseParentHandlers();
      final Level rootLevelBefore = rootLogger.getLevel();

      final Path logFile = Files.createTempFile("copycat-main", ".log");
      final var logHandler = new java.util.logging.FileHandler(logFile.toString(), 0, 1, false);
      logHandler.setEncoding(StandardCharsets.UTF_8.name());
      logHandler.setLevel(Level.ALL);
      logHandler.setFormatter(new Formatter() {
         @Override
         public String format(final LogRecord record) {
            return record.getLevel().getName() + ": " + record.getMessage() + System.lineSeparator();
         }
      });
      rootLogger.addHandler(logHandler);
      rootLogger.setLevel(Level.ALL);

      int exitCode = -1;

      try {
         class ExitException extends SecurityException {
            private static final long serialVersionUID = 1L;

            final int status;

            ExitException(final int status) {
               this.status = status;
            }
         }

         class NoExitSecurityManager extends SecurityManager {

            @Override
            public void checkExit(final int status) {
               throw new ExitException(status);
            }

            @Override
            public void checkPermission(final Permission perm) {
               // allow everything
            }

            @Override
            public void checkPermission(final Permission perm, final Object context) {
               // allow everything
            }
         }

         System.setSecurityManager(new NoExitSecurityManager());

         try {
            CopyCatMain.main(args);
         } catch (final ExitException ex) {
            exitCode = ex.status;
         } catch (final Exception ex) {
            throw new RuntimeException(ex);
         }
      } finally {
         rootLogger.removeHandler(logHandler);
         logHandler.close();

         try {
            System.setSecurityManager(originalSecurityManager);
         } catch (final RuntimeException ignored) { /* best effort */ }
         System.setOut(originalOut);
         System.setErr(originalErr);

         for (final Handler handler : rootLogger.getHandlers()) {
            rootLogger.removeHandler(handler);
         }
         for (final Handler handler : rootHandlersBefore) {
            rootLogger.addHandler(handler);
         }
         rootLogger.setUseParentHandlers(useParentHandlersBefore);
         rootLogger.setLevel(rootLevelBefore);
      }

      final String logOutput = Files.readString(logFile, StandardCharsets.UTF_8);
      Files.deleteIfExists(logFile);

      return new MainResult(exitCode, logOutput);
   }

   @Test
   void helpListsCommands() {
      final String help = generateMainHelp();
      assertThat(help) //
         .contains("Usage: copycat") //
         .contains("Commands") //
         .contains("sync") //
         .contains("watch");
   }

   @Test
   void missingSubcommandExitsWithCode1() throws Exception {
      final var result = runMain();
      assertThat(result.exitCode).isEqualTo(1);
      assertThat(result.logOutput).contains("Missing required subcommand.");
   }

   @Test
   void unknownOptionShowsHintAndExitsWithCode1() throws Exception {
      final var result = runMain("--definitely-not-a-valid-option");
      assertThat(result.exitCode).isEqualTo(1);
      assertThat(result.logOutput) //
         .contains("Unknown option") //
         .contains("Execute 'copycat --help' for usage help.");
   }
}
