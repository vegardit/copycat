/*
 * Copyright 2020 by Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.io.IOException;
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
       * at least after 5 seconds the log appenders buffer is flushed
       */
      new Timer(true).schedule(new TimerTask() {
         @Override
         public void run() {
            synchronized (JdkLoggingUtils.class) {
               if (consoleHandler != null) {
                  consoleHandler.flush();
               }
            }
         }
      }, TimeUnit.SECONDS.toMillis(5), TimeUnit.SECONDS.toMillis(5));
   }

   private static final Formatter ANSI_FORMATTER = new Formatter() {

      private String ansiRender(final String text) {
         return AnsiRenderer.render(text);
      }

      private String ansiRender(final String text, final Object... args) {
         return String.format(AnsiRenderer.render(text), args);
      }

      @Override
      public synchronized String format(final LogRecord record) {
         final var threadName = Thread.currentThread().getName();
         final var recordTime = new Date(record.getMillis());
         final var msg = ansiRender(record.getMessage());

         switch (record.getLevel().intValue()) {
            case Levels.INFO_INT:
               return ansiRender("%1$tT @|green [%2$s]|@ %3$s%n", //
                  recordTime, threadName, msg);

            case Levels.WARNING_INT:
               return ansiRender("@|yellow %1$tT [%2$s] WARN: %3$s%n|@", //
                  recordTime, threadName, msg);

            case Levels.SEVERE_INT:
               return record.getThrown() == null //
                  ? ansiRender("@|red %1$tT [%2$s] ERROR: %3$s%n|@", //
                     recordTime, threadName, record.getMessage()) //
                  : ansiRender("@|red %1$tT [%2$s] ERROR: %3$s %4$s|@", //
                     recordTime, threadName, msg, Exceptions.getStackTrace(record.getThrown()));

            default:
               return String.format("%1$tT [%2$s] %3$-6s: %4$s %n", //
                  recordTime, threadName, record.getLevel().getLocalizedName(), msg);
         }
      }
   };

   private static final Formatter PLAIN_FORMATTER = new Formatter() {

      @Override
      public synchronized String format(final LogRecord record) {
         final var threadName = Thread.currentThread().getName();
         final var recordTime = new Date(record.getMillis());
         final var msg = record.getMessage().replaceAll("(@\\|[a-z]+\\s)|(\\|@)", ""); // remove ansi keywords

         return String.format("%1$tT [%2$s] %3$-6s: %4$s %n", //
            recordTime, threadName, record.getLevel().getLocalizedName(), msg);
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
   public static void configureConsoleHandler(final boolean useStdErr) {
      synchronized (Loggers.ROOT_LOGGER) {
         if (useStdErr && consoleHandler instanceof DualPrintStreamHandler)
            // nothing to do
            return;

         LoggerConfig.setCompactExceptionLogging(false);
         Loggers.ROOT_LOGGER.setUseParentHandlers(false);
         for (final Handler handler : Loggers.ROOT_LOGGER.getHandlers()) {
            if (handler instanceof FileHandler) {
               continue;
            }
            Loggers.ROOT_LOGGER.removeHandler(handler);
         }
         if (useStdErr) {
            consoleHandler = new DualPrintStreamHandler(System.out, System.err, ANSI_FORMATTER);
         } else {
            consoleHandler = new PrintStreamHandler(System.out, ANSI_FORMATTER);
         }
         Loggers.ROOT_LOGGER.addHandler(consoleHandler);
      }
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
