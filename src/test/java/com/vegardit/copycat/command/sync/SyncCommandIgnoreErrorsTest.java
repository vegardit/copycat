/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.asNonNull;
import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that ignored entry failures allow independent work while incomplete listings and cancellation stop unsafe work.
 *
 * @author Vegard IT GmbH
 */
class SyncCommandIgnoreErrorsTest {

   @ParameterizedTest
   @CsvSource({"false, false, false", "false, false, true", "false, true, false", "false, true, true", "true, false, false",
      "true, false, true", "true, true, false", "true, true, true"})
   void sourceFailureDoesNotSkipHealthyFilesOrDirectories(final boolean dryRun, final boolean ignoreErrors, final boolean runtimeFailure,
         @TempDir final Path tempDir) throws Exception {
      final var task = task(tempDir, dryRun);
      task.ignoreErrors = ignoreErrors;
      final var relativeFiles = List.of(Path.of("one.txt"), Path.of("two.txt"), Path.of("three.txt"), Path.of("child/nested.txt"));
      for (final var relative : relativeFiles) {
         writeFile(task.sourceRootAbsolute.resolve(relative));
      }
      final Exception failure = runtimeFailure ? new IllegalStateException("injected copy failure")
            : new IOException("injected copy failure");
      final var attempts = new ArrayList<Path>();
      final var command = new SyncCommand() {
         @Override
         void syncFile(final SyncCommandConfig config, final SyncHelpers.Context ctx, final Path source, final @Nullable Path target,
               final Path relative) throws IOException {
            attempts.add(relative);
            // Fail the first attempted file, not a named file whose position would depend on HashMap iteration order.
            if (attempts.size() == 1) {
               if (failure instanceof final IOException io)
                  throw io;
               throw (RuntimeException) failure;
            }
            super.syncFile(config, ctx, source, target, relative);
         }
      };

      try (var logs = new ErrorLogs()) {
         if (ignoreErrors) {
            runWorker(command, task);
            assertThat(attempts).containsExactlyInAnyOrderElementsOf(relativeFiles);
         } else {
            assertThatThrownBy(() -> runWorker(command, task)).isSameAs(failure);
            assertThat(attempts).hasSize(1);
         }
         // Fail-fast errors are reported by the caller; ignored errors must be logged exactly once by the worker.
         assertThat(logs.errors).hasSize(ignoreErrors ? 1 : 0);
      }

      final var stats = asNonNull(command.syncContext(task).stats());
      assertError(stats, failure);
      assertThat(count(stats, "filesCopied")).isEqualTo(ignoreErrors ? 3 : 0);
      assertThat(count(stats, "filesScanned")).isEqualTo(ignoreErrors ? 3 : 0);
      assertThat(count(stats, "dirsScanned")).isEqualTo(ignoreErrors ? 2 : 1);
      assertThat(command).extracting("state").isEqualTo(ignoreErrors ? SyncCommand.State.NORMAL : SyncCommand.State.ABORT_BY_EXCEPTION);
      for (final var relative : relativeFiles) {
         assertThat(Files.exists(task.targetRootAbsolute.resolve(relative))).isEqualTo(!dryRun && ignoreErrors && !relative.equals(attempts
            .get(0)));
      }
   }

   @ParameterizedTest
   @CsvSource({"false, false", "false, true", "true, false", "true, true"})
   void recursiveDeletionFailureStillAllowsIndependentDeletesAndCopies(final boolean dryRun, final boolean filtered,
         @TempDir final Path tempDir) throws Exception {
      final var task = task(tempDir, dryRun);
      task.fileFilters = filtered ? List.of("ex:**/*.protected") : List.of();
      task.compute();
      writeFile(task.sourceRootAbsolute.resolve("healthy.txt"));
      final Path child = writeFile(task.targetRootAbsolute.resolve("archive/child.txt"));
      final Path first = writeFile(task.targetRootAbsolute.resolve("one.txt"));
      final Path second = writeFile(task.targetRootAbsolute.resolve("two.txt"));
      final Path healthyTarget = task.targetRootAbsolute.resolve("healthy.txt");
      final var failure = new IOException("injected deletion scan failure");
      final var command = new SyncCommand();

      // A single child fixes the failure point without relying on the filesystem's enumeration order.
      try (var fileSystem = new FailingDirectoryFileSystem(task.targetRootAbsolute.resolve("archive"), failure, 1)) {
         task.sourceRootAbsolute = fileSystem.wrap(task.sourceRootAbsolute);
         task.targetRootAbsolute = fileSystem.wrap(task.targetRootAbsolute);
         runWorker(command, task);
         assertThat(fileSystem.failureCount()).isEqualTo(1);
      }

      final var stats = asNonNull(command.syncContext(task).stats());
      assertError(stats, failure);
      assertThat(count(stats, "filesDeleted")).isEqualTo(3);
      assertThat(count(stats, "dirsDeleted")).isZero();
      assertThat(count(stats, "filesCopied")).isEqualTo(1);
      for (final var deleted : List.of(child, first, second)) {
         assertThat(Files.exists(deleted)).isEqualTo(dryRun);
      }
      assertThat(Files.exists(healthyTarget)).isEqualTo(!dryRun);
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void failedTargetDeletionPreventsMatchingSourceWork(final boolean dryRun, @TempDir final Path tempDir) throws Exception {
      final var task = task(tempDir, dryRun);
      task.deleteExcluded = true;
      // Source traversal can reach the included child, but target-side exclusion selects its parent for deletion.
      task.fileFilters = List.of("in:archive/keep.txt", "in:healthy.txt", "ex:**");
      task.compute();
      final Path sourceFile = writeFile(task.sourceRootAbsolute.resolve("archive/keep.txt"));
      final Path targetFile = writeFile(task.targetRootAbsolute.resolve("archive/keep.txt"));
      // Equal metadata makes an accidental dry-run revisit treat the physically retained file as unchanged.
      Files.setLastModifiedTime(targetFile, Files.getLastModifiedTime(sourceFile));
      writeFile(task.sourceRootAbsolute.resolve("healthy.txt"));
      final var attempts = new ArrayList<String>();
      final var command = new SyncCommand() {
         @Override
         void syncFile(final SyncCommandConfig config, final SyncHelpers.Context ctx, final Path source, final @Nullable Path target,
               final Path relative) throws IOException {
            attempts.add(relative.toString());
            super.syncFile(config, ctx, source, target, relative);
         }
      };
      final var failure = new IOException("injected partial deletion failure");
      try (var fileSystem = new FailingDirectoryFileSystem(task.targetRootAbsolute.resolve("archive"), failure, 1)) {
         task.sourceRootAbsolute = fileSystem.wrap(task.sourceRootAbsolute);
         task.targetRootAbsolute = fileSystem.wrap(task.targetRootAbsolute);
         runWorker(command, task);
         assertThat(fileSystem.failureCount()).isEqualTo(1);
      }

      final var stats = asNonNull(command.syncContext(task).stats());
      assertError(stats, failure);
      assertThat(attempts).containsExactly("healthy.txt");
      // Count jobs as well as copies: revisiting the failed directory is wrong even if its next scan also fails.
      assertThat(count(stats, "dirsScanned")).isEqualTo(1);
      assertThat(count(stats, "filesDeleted")).isEqualTo(1);
      assertThat(count(stats, "filesCopied")).isEqualTo(1);
      assertThat(Files.exists(targetFile)).isEqualTo(dryRun);
   }

   @ParameterizedTest
   @CsvSource({"false, false", "false, true", "true, false", "true, true"})
   void incompleteListingStopsTheDirectoryJob(final boolean dryRun, final boolean sourceListing, @TempDir final Path tempDir)
         throws Exception {
      final var task = task(tempDir, dryRun);
      for (final String name : List.of("one.txt", "two.txt")) {
         writeFile(task.sourceRootAbsolute.resolve(name));
         writeFile(task.targetRootAbsolute.resolve(name));
      }
      final Path stale = writeFile(task.targetRootAbsolute.resolve("stale.txt"));
      final var command = new SyncCommand();
      final var failure = new IOException("injected incomplete listing");
      final Path failingDirectory = sourceListing ? task.sourceRootAbsolute : task.targetRootAbsolute;
      try (var fileSystem = new FailingDirectoryFileSystem(failingDirectory, failure, 1)) {
         task.sourceRootAbsolute = fileSystem.wrap(task.sourceRootAbsolute);
         task.targetRootAbsolute = fileSystem.wrap(task.targetRootAbsolute);
         runWorker(command, task);
         assertThat(fileSystem.failureCount()).isEqualTo(1);
      }

      final var stats = asNonNull(command.syncContext(task).stats());
      assertThat(stats).extracting("errors").asInstanceOf(InstanceOfAssertFactories.COLLECTION).hasSize(1);
      assertThat(count(stats, "filesDeleted")).isZero();
      assertThat(count(stats, "filesCopied")).isZero();
      assertThat(count(stats, "dirsScanned")).isEqualTo(1);
      assertThat(Files.exists(stale)).isTrue();
      for (final String name : List.of("one.txt", "two.txt")) {
         assertThat(Files.exists(task.targetRootAbsolute.resolve(name))).isTrue();
      }
   }

   @Test
   void failedParentPreparationIsRetriedForTheNextEntry(@TempDir final Path tempDir) throws Exception {
      final var task = task(tempDir, false);
      // Including the directory alone excludes its files; include descendants so both entries attempt parent preparation.
      task.fileFilters = List.of("in:included", "in:included/**", "ex:**");
      task.compute();
      writeFile(task.sourceRootAbsolute.resolve("included/one.txt"));
      writeFile(task.sourceRootAbsolute.resolve("included/two.txt"));
      final var preparations = new AtomicInteger();
      final var failure = new IOException("injected parent preparation failure");
      // Both entries need this parent; its failed preparation must not be cached as successful for the second entry.
      final var command = new SyncCommand() {
         @Override
         void syncDirShallow(final SyncCommandConfig config, final SyncHelpers.Context ctx, final Path source, final @Nullable Path target,
               final Path relative) throws IOException {
            if (relative.equals(Path.of("included")) && preparations.incrementAndGet() == 1)
               throw failure;
            super.syncDirShallow(config, ctx, source, target, relative);
         }
      };

      runWorker(command, task);

      final var stats = asNonNull(command.syncContext(task).stats());
      assertError(stats, failure);
      assertThat(preparations.get()).isEqualTo(2);
      assertThat(count(stats, "filesCopied")).isEqualTo(1);
      assertThat(count(stats, "filesScanned")).isEqualTo(1);
   }

   @ParameterizedTest
   @CsvSource({"false, false, false", "false, false, true", "false, true, false", "false, true, true", "true, false, false",
      "true, false, true", "true, true, false", "true, true, true"})
   void cancellationStopsEntriesAndExplicitDirectoryFinalization(final boolean signal, final boolean operationFails,
         final boolean ignoreErrors, @TempDir final Path tempDir) throws Exception {
      final var task = task(tempDir, true);
      task.ignoreErrors = ignoreErrors;
      // Keep the directory explicit for finalization and include its files so syncFile() can trigger cancellation.
      task.fileFilters = List.of("in:included", "in:included/**", "ex:**");
      task.compute();
      writeFile(task.sourceRootAbsolute.resolve("included/one.txt"));
      writeFile(task.sourceRootAbsolute.resolve("included/two.txt"));
      final var cancelled = new AtomicBoolean();
      final var attempts = new AtomicInteger();
      final var preparationsAfterCancellation = new AtomicInteger();
      final var failure = new IOException("injected cancelled operation");
      final var command = new SyncCommand() {
         @Override
         void syncFile(final SyncCommandConfig config, final SyncHelpers.Context ctx, final Path source, final @Nullable Path target,
               final Path relative) throws IOException {
            attempts.incrementAndGet();
            cancelled.set(true);
            if (signal) {
               // Set the signal state directly: invoking the signal handler would add an unrelated sleep.
               signalAbort(this);
            } else {
               Thread.currentThread().interrupt();
            }
            if (operationFails)
               throw failure;
         }

         @Override
         void ensureTargetDirPrepared(final SyncCommandConfig config, final SyncHelpers.Context ctx, final Path source, final Path relative)
               throws IOException {
            if (cancelled.get()) {
               preparationsAfterCancellation.incrementAndGet();
            }
            super.ensureTargetDirPrepared(config, ctx, source, relative);
         }
      };

      try {
         if (signal) {
            runWorker(command, task);
            assertThat(command).extracting("state").isEqualTo(SyncCommand.State.ABORT_BY_SIGNAL);
         } else if (operationFails) {
            assertThatThrownBy(() -> runWorker(command, task)).isSameAs(failure);
         } else {
            assertThatThrownBy(() -> runWorker(command, task)).isInstanceOf(InterruptedIOException.class);
         }
         assertThat(Thread.currentThread().isInterrupted()).isEqualTo(!signal);
         assertThat(attempts.get()).isEqualTo(1);
         assertThat(preparationsAfterCancellation.get()).isZero();
         final var stats = asNonNull(command.syncContext(task).stats());
         assertThat(stats).extracting("errors").asInstanceOf(InstanceOfAssertFactories.COLLECTION).hasSize(signal && !operationFails ? 0
               : 1);
         if (!signal) {
            assertThat(command).extracting("state").isEqualTo(SyncCommand.State.ABORT_BY_EXCEPTION);
         }
      } finally {
         // The worker runs on the test thread so interruption must not leak into other tests.
         Thread.interrupted();
      }
   }

   private static SyncCommandConfig task(final Path tempDir, final boolean dryRun) throws IOException {
      final var task = new SyncCommandConfig();
      task.source = Files.createDirectory(tempDir.resolve("src"));
      task.target = Files.createDirectory(tempDir.resolve("dst"));
      task.dryRun = dryRun;
      task.delete = true;
      task.ignoreErrors = true;
      task.threads = 1;
      task.applyDefaults();
      task.compute();
      return task;
   }

   private static Path writeFile(final Path file) throws IOException {
      Files.createDirectories(asNonNull(file.getParent()));
      return Files.writeString(file, "contents");
   }

   @SuppressWarnings("resource") // The caller owns and closes any injected filesystem.
   private static void runWorker(final SyncCommand command, final SyncCommandConfig task) throws IOException {
      final var jobs = new SyncCommand.DirJobQueue(1, new ArrayDeque<>(List.of(new SyncCommand.DirJob(task.sourceRootAbsolute,
         task.sourceRootAbsolute.getFileSystem().getPath(".")))));
      command.syncWorker(task, task.toSourceFilterContext(), task.toTargetFilterContext(), jobs, command.syncContext(task));
   }

   private static void assertError(final SyncStats stats, final Exception failure) {
      assertThat(stats).extracting("errors").asInstanceOf(InstanceOfAssertFactories.COLLECTION).containsExactly(failure.getClass()
         .getSimpleName() + ": " + failure.getMessage());
   }

   private static long count(final SyncStats stats, final String name) throws ReflectiveOperationException {
      final var field = SyncStats.class.getDeclaredField(name);
      field.setAccessible(true);
      return ((LongAdder) asNonNull(field.get(stats))).sum();
   }

   private static void signalAbort(final SyncCommand command) {
      try {
         final var field = SyncCommand.class.getDeclaredField("state");
         field.setAccessible(true);
         field.set(command, SyncCommand.State.ABORT_BY_SIGNAL);
      } catch (final ReflectiveOperationException ex) {
         throw new AssertionError(ex);
      }
   }

   /**
    * Captures worker error reports separately from the statistics so duplicate logs cannot hide behind a correct count.
    */
   private static final class ErrorLogs extends Handler implements AutoCloseable {
      final List<LogRecord> errors = new ArrayList<>();

      ErrorLogs() {
         Logger.getLogger("").addHandler(this);
      }

      @Override
      public void publish(final @Nullable LogRecord record) {
         if (record != null && record.getLevel().intValue() >= Level.SEVERE.intValue()) {
            errors.add(record);
         }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
         Logger.getLogger("").removeHandler(this);
      }
   }
}
