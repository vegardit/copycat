/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that sync collects worker failures before reporting completion and still cancels pending work on signals or stalls.
 *
 * @author Vegard IT GmbH
 */
class SyncCommandCompletionTest {

   @ParameterizedTest
   @ValueSource(ints = {1, 4})
   void rootFailureIsCollectedAfterAnEmptyCompletionPoll(final int threads, @TempDir final Path tempDir) throws Exception {
      final var task = task(tempDir, threads);
      final var failure = new IOException("Root preparation failed.");
      final var failurePending = new CountDownLatch(1);
      final var releaseFailure = new CountDownLatch(1);
      final var command = new SyncCommand() {
         @Override
         void syncDirShallow(final SyncCommandConfig config, final SyncHelpers.Context ctx, final Path source, final @Nullable Path target,
               final Path relative) throws IOException {
            throw failure;
         }

         @Override
         void syncWorker(final SyncCommandConfig config, final FilterEngine.FilterContext sourceFilters,
               final FilterEngine.FilterContext targetFilters, final DirJobQueue jobs, final SyncHelpers.Context ctx) throws IOException {
            try {
               super.syncWorker(config, sourceFilters, targetFilters, jobs, ctx);
            } catch (final IOException ex) {
               // The real worker has set the abort flag. Hold its exception before the completion queue can receive it.
               failurePending.countDown();
               try {
                  if (!releaseFailure.await(10, TimeUnit.SECONDS))
                     throw new AssertionError("Timed out waiting to publish the worker failure.");
               } catch (final InterruptedException interrupted) {
                  // Early shutdown must release the test worker without replacing the original preparation failure.
                  Thread.currentThread().interrupt();
               }
               throw ex;
            }
         }
      };

      final var caller = Executors.newSingleThreadExecutor();
      try {
         final var execution = caller.submit(() -> {
            command.doExecute(List.of(task));
            return null;
         });
         assertThat(failurePending.await(10, TimeUnit.SECONDS)).isTrue();
         // The configured one-second poll must expire while the failure is held; a stall must not replace that failure either.
         assertThrows(TimeoutException.class, () -> execution.get(2, TimeUnit.SECONDS));
         releaseFailure.countDown();

         final var reported = assertThrows(ExecutionException.class, () -> execution.get(10, TimeUnit.SECONDS));
         assertThat(reported.getCause()).isSameAs(failure);
         assertThat(Files.exists(task.targetRootAbsolute)).isFalse();
      } finally {
         releaseFailure.countDown();
         caller.shutdownNow();
         assertThat(caller.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void cancellationStopsAPendingWorker(final boolean signalAbort, @TempDir final Path tempDir) throws Exception {
      final var task = task(tempDir, 1);
      final var workerStarted = new CountDownLatch(1);
      final var releaseWorker = new CountDownLatch(1);
      final var interrupted = new AtomicBoolean();
      final var command = new SyncCommand() {
         @Override
         void syncWorker(final SyncCommandConfig config, final FilterEngine.FilterContext sourceFilters,
               final FilterEngine.FilterContext targetFilters, final DirJobQueue jobs, final SyncHelpers.Context ctx) {
            // Pending work must be interrupted by the coordinator, not finish and make cancellation look successful.
            workerStarted.countDown();
            try {
               if (!releaseWorker.await(10, TimeUnit.SECONDS))
                  throw new AssertionError("Pending worker was not cancelled.");
            } catch (final InterruptedException ex) {
               // Return normally to isolate coordinator cancellation from exception-versus-signal precedence.
               interrupted.set(true);
               Thread.currentThread().interrupt();
            }
         }
      };

      final var caller = Executors.newSingleThreadExecutor();
      try {
         final var execution = caller.submit(() -> {
            command.doExecute(List.of(task));
            return null;
         });
         assertThat(workerStarted.await(10, TimeUnit.SECONDS)).isTrue();
         if (signalAbort) {
            command.onSigInt();
         }

         final var reported = assertThrows(ExecutionException.class, () -> execution.get(10, TimeUnit.SECONDS));
         if (signalAbort) {
            assertThat(reported.getCause()).isInstanceOf(InterruptedException.class).hasMessageContaining("aborted");
         } else {
            assertThat(reported.getCause()).isInstanceOf(IOException.class).hasMessageContaining("appears stuck");
         }
         assertThat(interrupted.get()).isTrue();
      } finally {
         releaseWorker.countDown();
         caller.shutdownNow();
         assertThat(caller.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
   }

   private static SyncCommandConfig task(final Path tempDir, final int threads) throws IOException {
      final var task = new SyncCommandConfig();
      task.source = Files.createDirectory(tempDir.resolve("src"));
      task.target = tempDir.resolve("dst");
      task.threads = threads;
      task.ignoreErrors = false;
      task.stallTimeout = Duration.ofSeconds(1);
      task.fileFilters = List.of("ex:**/*.tmp");
      task.applyDefaults();
      task.compute();
      return task;
   }
}
