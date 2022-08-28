/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.fusesource.jansi.AnsiRenderer;

import net.sf.jstuff.core.exception.Exceptions;
import net.sf.jstuff.core.logging.LoggerConfig;
import net.sf.jstuff.core.logging.jul.DualPrintStreamHandler;
import net.sf.jstuff.core.logging.jul.Levels;
import net.sf.jstuff.core.logging.jul.Loggers;
import net.sf.jstuff.core.logging.jul.PrintStreamHandler;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class JdkLoggingUtils {

   static {
      /*
       * this ensures that in case of long running operations, e.g. copying of large files
       * at least after 2 seconds the log appenders buffer is flushed
       */
      new Timer(true).schedule(new TimerTask() {
         @Override
         public void run() {
            synchronized (JdkLoggingUtils.class) {
               if (consoleHandler != null) {
                  consoleHandler.flush();
                  System.out.flush();
                  System.err.flush();
               }
            }
         }
      }, TimeUnit.SECONDS.toMillis(2), TimeUnit.SECONDS.toMillis(2));
   }

   public static class AnsiFormatter extends Formatter {

      protected String ansiRender(final String text) {
         return text == null ? "null" : AnsiRenderer.render(text);
      }

      protected String ansiRender(final String template, final Object... args) {
         return String.format(AnsiRenderer.render(template), args);
      }

      @Override
      public synchronized String format(final LogRecord entry) {
         final var msg = ansiRender(entry.getMessage());
         final var recordTime = new Date(entry.getMillis());
         final var threadName = Thread.currentThread().getName();

         switch (entry.getLevel().intValue()) {
            case Levels.INFO_INT:
               return ansiRender("%1$tT @|green [%2$s]|@ %3$s%n", //
                  recordTime, threadName, msg);

            case Levels.WARNING_INT:
               return ansiRender("@|yellow %1$tT [%2$s] WARN: %3$s%n|@", //
                  recordTime, threadName, msg);

            case Levels.SEVERE_INT:
               return entry.getThrown() == null //
                  ? ansiRender("@|red %1$tT [%2$s] ERROR: %3$s%n|@", //
                     recordTime, threadName, entry.getMessage()) //
                  : ansiRender("@|red %1$tT [%2$s] ERROR: %3$s %4$s|@", //
                     recordTime, threadName, msg, Exceptions.getStackTrace(entry.getThrown()));

            default:
               return String.format("%1$tT [%2$s] %3$-6s: %4$s %n", //
                  recordTime, threadName, entry.getLevel().getLocalizedName(), msg);
         }
      }
   }

   private static final Formatter PLAIN_FORMATTER = new Formatter() {

      @Override
      public synchronized String format(final LogRecord entry) {
         final var threadName = Thread.currentThread().getName();
         final var recordTime = new Date(entry.getMillis());
         final var msg = entry.getMessage().replaceAll("(@\\|[a-z]+\\s)|(\\|@)", ""); // remove ansi keywords

         return String.format("%1$tT [%2$s] %3$-6s: %4$s %n", //
            recordTime, threadName, entry.getLevel().getLocalizedName(), msg);
      }
   };

   private static Handler consoleHandler;

   public static FileHandler addFileHandler(final String fileNamePattern) throws IOException {
      synchronized (Loggers.ROOT_LOGGER) {
         final var handler = new FileHandler(fileNamePattern, 0, 1, false);
         handler.setFormatter(PLAIN_FORMATTER);
         Loggers.ROOT_LOGGER.addHandler(handler);
         return handler;
      }
   }

   /**
    * Configures the JDK Logger for pretty console printing.
    */
   public static void configureConsoleHandler(final boolean useStdErr, final Formatter consoleFormatter) {
      synchronized (Loggers.ROOT_LOGGER) {
         if (useStdErr && consoleHandler instanceof DualPrintStreamHandler)
            // nothing to do
            return;

         disableAutoFlush();

         LoggerConfig.setCompactExceptionLogging(false);
         Loggers.ROOT_LOGGER.setUseParentHandlers(false);
         for (final Handler handler : Loggers.ROOT_LOGGER.getHandlers()) {
            if (handler instanceof FileHandler) {
               continue;
            }
            Loggers.ROOT_LOGGER.removeHandler(handler);
         }
         if (useStdErr) {
            consoleHandler = new DualPrintStreamHandler(System.out, System.err, consoleFormatter);
         } else {
            consoleHandler = new PrintStreamHandler(System.out, consoleFormatter);
         }
         Loggers.ROOT_LOGGER.addHandler(consoleHandler);
      }
   }

   private static void disableAutoFlush() {
      System.out.flush();
      System.err.flush();
      @SuppressWarnings("resource")
      final var errStream = new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.err), 1024), false);
      @SuppressWarnings("resource")
      final var outStream = new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1024), false);
      System.setErr(errStream);
      System.setOut(outStream);
   }

   /**
    * Executes the given code block with the root logger set to at least required granularity.
    */
   public static void withRootLogLevel(final Level requiredLevel, final Runnable code) {
      synchronized (Loggers.ROOT_LOGGER) {
         final var currentLevel = Levels.getRootLevel();

         if (currentLevel.intValue() > requiredLevel.intValue()) {
            Levels.setRootLevel(requiredLevel);
            try {
               code.run();
            } finally {
               Levels.setRootLevel(currentLevel);
            }
         } else {
            code.run();
         }
      }
   }

   private JdkLoggingUtils() {
   }
}
