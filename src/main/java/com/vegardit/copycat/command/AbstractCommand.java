/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.*;

import java.util.concurrent.Callable;
import java.util.logging.Level;

import com.vegardit.copycat.command.AbstractCommand.VersionProvider;

import net.sf.jstuff.core.logging.Logger;
import net.sf.jstuff.core.logging.jul.Levels;
import net.sf.jstuff.core.reflection.Types;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
@Command( //
   headerHeading = """
      @|magenta\s\
                                               __    /\\_/\\%n\
            _________  ____  __  ___________ _/ /_  ( o.o )%n\
       -===/ ___/ __ \\/ __ \\/ / / / ___/ __ `/ __/   > ^ <%n\
        -=/ /__/ /_/ / /_/ / /_/ / /__/ /_/ / /_     / * \\%n\
      -===\\___/\\____/ .___/\\__, /\\___/\\__,_/\\__/   (..)~(..)%n\
                   /_/    /____/|@ %n\
                         @|faint https://github.com/vegardit/copycat|@ %n\
      %n""", //
   mixinStandardHelpOptions = true, //
   descriptionHeading = "%n", //
   commandListHeading = "%nCommands%n", //
   parameterListHeading = "%nPositional parameters:%n", //
   optionListHeading = "%nOptions:%n", //
   requiredOptionMarker = '*', //
   usageHelpAutoWidth = true, //
   separator = " ", //
   showDefaultValues = true, //
   sortOptions = true, //
   versionProvider = VersionProvider.class //
)
public abstract class AbstractCommand implements Callable<Void> {
   public static final class VersionProvider implements IVersionProvider {
      @Override
      public String[] getVersion() throws Exception {
         final var version = Types.getVersion(AbstractCommand.class);
         return new String[] {version == null ? "unknown" : version};
      }
   }

   private static final Logger LOG = Logger.create();

   @Spec
   protected CommandSpec commandSpec = lateNonNull();

   /**
    * logging options are not further evaluated, since it is already done in main entry point
    */
   @Mixin
   private LoggingOptionsMixin loggingOptions = lateNonNull();

   private int verbosity = 0;

   public boolean isQuiet() {
      return verbosity == -1;
   }

   public int getVerbosity() {
      return verbosity;
   }

   @Override
   public final Void call() throws Exception {
      /*
       * install signal handlers
       */
      // Runtime.getRuntime().addShutdownHook() is not working reliable
      try {
         sun.misc.Signal.handle(new sun.misc.Signal("INT"), signal -> {
            LOG.warn("Canceling operation due to SIGINT(2) signal (CTRL+C) received...");
            onSigInt();
            System.exit(128 + 2);
         });
      } catch (final IllegalArgumentException ex) {
         // ignore java.lang.IllegalArgumentException: Unknown signal: INT
      }
      try {
         sun.misc.Signal.handle(new sun.misc.Signal("TERM"), signal -> {
            LOG.warn("Canceling operation due to SIGTERM(15) signal received...");
            onSigTerm();
            System.exit(128 + 15);
         });
      } catch (final IllegalArgumentException ex) {
         // ignore java.lang.IllegalArgumentException: Unknown signal: TERM
      }

      execute();

      LOG.info("");
      LOG.info("THE OPERATION COMPLETED SUCCESSFULLY.");
      return asNonNullUnsafe((Void) null);
   }

   protected abstract void execute() throws Exception;

   protected void onSigInt() {
   }

   protected void onSigTerm() {
   }

   @Option(names = {"-q", "--quiet"}, description = "Quiet mode.")
   private void setQuiet(final boolean flag) {
      if (flag) {
         Levels.setRootLevel(Level.SEVERE);
         verbosity = -1;
      }
   }

   @Option(names = {"-v", "--verbose"}, description = {"Specify multiple -v options to increase verbosity.",
      "For example `-v -v -v` or `-vvv`."})
   private void setVerbosity(final boolean[] flags) {
      Levels.setRootLevel(switch (flags.length) {
         case 0 -> Level.INFO;
         case 1 -> Level.FINE;
         case 2 -> Level.FINER;
         default -> Level.FINEST;
      });
      verbosity = flags.length;
   }
}
