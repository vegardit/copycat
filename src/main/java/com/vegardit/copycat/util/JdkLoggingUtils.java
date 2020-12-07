/*
 * Copyright 2020 by Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.logging.ErrorManager;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.fusesource.jansi.AnsiRenderer;

import net.sf.jstuff.core.exception.Exceptions;
import net.sf.jstuff.core.logging.LoggerConfig;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class JdkLoggingUtils {

   public static final class DualStreamHandler extends PrintStreamHandler {

      private final Handler stderrHandler;

      private DualStreamHandler(final PrintStream stdout, final PrintStream stderr) {
         super(stdout, ANSI_FORMATTER);
         stderrHandler = new PrintStreamHandler(stderr, ANSI_FORMATTER);
      }

      @Override
      public synchronized void flush() {
         super.flush();
         stderrHandler.flush();
      }

      @Override
      public synchronized void publish(final LogRecord record) {
         if (record.getLevel().intValue() > 800 /*Level.INFO.intValue() */) {
            super.flush();
            stderrHandler.publish(record);
            stderrHandler.flush();
         } else {
            super.publish(record);
         }
      }
   }

   /**
    * Not using {@link java.util.logging.StreamHandler} which for some reason prints nothing
    * when compiled to native with GraalVM.
    */
   public static class PrintStreamHandler extends Handler {
      private boolean doneHeader;
      private final PrintStream out;

      public PrintStreamHandler(final PrintStream out, final Formatter formatter) {
         setLevel(Level.INFO);
         setFormatter(formatter);
         this.out = out;
      }

      @Override
      public void close() throws SecurityException {
         try {
            if (!doneHeader) {
               out.print(getFormatter().getHead(this));
               doneHeader = true;
            }
            out.print(getFormatter().getTail(this));
            flush();
            out.close();
         } catch (final Exception ex) {
            reportError(null, ex, ErrorManager.CLOSE_FAILURE);
         }
      }

      @Override
      public void flush() {
         try {
            out.flush();
         } catch (final Exception ex) {
            reportError(null, ex, ErrorManager.FLUSH_FAILURE);
         }
      }

      @Override
      public void publish(final LogRecord record) {
         try {
            final var msg = getFormatter().format(record);

            try {
               if (!doneHeader) {
                  out.print(getFormatter().getHead(this));
                  doneHeader = true;
               }
               out.print(msg);
            } catch (final Exception ex) {
               reportError(null, ex, ErrorManager.WRITE_FAILURE);
            }

         } catch (final Exception ex) {
            reportError(null, ex, ErrorManager.FORMAT_FAILURE);
         }
      }
   }

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
            case 800: // Level.INFO
               return ansiRender("%1$tT @|green [%2$s]|@ %3$s%n", //
                  recordTime, threadName, msg);

            case 900: // Level.WARNING)
               return ansiRender("@|yellow %1$tT [%2$s] WARN: %3$s%n|@", //
                  recordTime, threadName, msg);

            case 1000: // Level.SEVERE
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

   private static final Logger ROOT_LOGGER = Logger.getLogger("");
   private static Handler consoleHandler;

   public static synchronized FileHandler addFileHandler(final Path path) throws IOException {
      final var handler = new FileHandler(path.toString(), 0, 1, false);
      handler.setFormatter(PLAIN_FORMATTER);
      ROOT_LOGGER.addHandler(handler);
      return handler;
   }

   /**
    * Configures the JDK Logger for pretty console printing.
    */
   public static synchronized void configureConsoleHandler(final boolean useStdErr) {

      if (useStdErr && consoleHandler instanceof DualStreamHandler)
         // nothing to do
         return;

      LoggerConfig.setCompactExceptionLogging(false);
      ROOT_LOGGER.setUseParentHandlers(false);
      for (final Handler handler : ROOT_LOGGER.getHandlers()) {
         if (handler instanceof FileHandler) {
            continue;
         }
         ROOT_LOGGER.removeHandler(handler);
      }
      if (useStdErr) {
         consoleHandler = new DualStreamHandler(System.out, System.err);
      } else {
         consoleHandler = new PrintStreamHandler(System.out, ANSI_FORMATTER);
      }
      ROOT_LOGGER.addHandler(consoleHandler);
   }

   public static synchronized Level getRootLogLevel() {
      return ROOT_LOGGER.getLevel();
   }

   public static synchronized Level setRootLogLevel(final Level enabledLevel) {
      final var oldLevel = ROOT_LOGGER.getLevel();
      ROOT_LOGGER.setLevel(enabledLevel);
      return oldLevel;
   }

   /**
    * Executes the given code block with the root logger set to at least required granularity.
    */
   public static synchronized void withRootLogLevel(final Level requiredLevel, final Runnable code) {
      final var currentLevel = ROOT_LOGGER.getLevel();

      if (currentLevel.intValue() > requiredLevel.intValue()) {
         ROOT_LOGGER.setLevel(requiredLevel);
         try {
            code.run();
         } finally {
            ROOT_LOGGER.setLevel(currentLevel);
         }
      } else {
         code.run();
      }
   }

   private JdkLoggingUtils() {
   }
}
