/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises preparation ownership, failure publication, and dry-run completion with deterministic worker interleavings.
 *
 * @author Vegard IT GmbH
 */
class SyncCommandPreparationStateTest {

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void staleRevalidationWaitsForTheNewPreparationOwner(final boolean directoryVisible, @TempDir final Path tempDir) throws Exception {
      final var task = task(tempDir, false);
      final Path relative = Path.of("a");
      final Path sourceDir = Files.createDirectory(task.sourceRootAbsolute.resolve(relative));
      final Path targetDir = task.targetRootAbsolute.resolve(relative);
      final var armed = new AtomicBoolean();
      final var missingReads = new AtomicInteger();
      final var recreations = new AtomicInteger();
      final var staleRead = new CountDownLatch(1);
      final var replacementEntered = new CountDownLatch(1);
      final var waiterEntered = new CountDownLatch(1);
      final var releaseReplacement = new CountDownLatch(1);
      final var command = new SyncCommand() {
         @Override
         @Nullable
         BasicFileAttributes readTargetDirAttributes(final Path path) throws IOException {
            final var attrs = super.readTargetDirAttributes(path);
            if (attrs == null && armed.get() && path.equals(targetDir) && missingReads.incrementAndGet() == 1) {
               // Hold an obsolete observation until another worker has published the replacement future.
               staleRead.countDown();
               await(replacementEntered);
               if (directoryVisible)
                  return super.readTargetDirAttributes(path);
            }
            return attrs;
         }

         @Override
         void syncDirShallow(final SyncCommandConfig config, final SyncHelpers.Context ctx, final Path source, final @Nullable Path target,
               final Path dirRelative) throws IOException {
            if (!armed.get() || !dirRelative.equals(relative)) {
               super.syncDirShallow(config, ctx, source, target, dirRelative);
               return;
            }
            recreations.incrementAndGet();
            // Existence can precede completion; both that window and an obsolete missing-entry read must wait.
            if (directoryVisible) {
               super.syncDirShallow(config, ctx, source, target, dirRelative);
            }
            replacementEntered.countDown();
            await(releaseReplacement);
            if (!directoryVisible) {
               super.syncDirShallow(config, ctx, source, target, dirRelative);
            }
         }

         @Override
         void awaitTargetDirPrepared(final CompletableFuture<@Nullable Void> future, final Path dirRelative) throws IOException {
            if (armed.get() && dirRelative.equals(relative) && !future.isDone()) {
               waiterEntered.countDown();
            }
            super.awaitTargetDirPrepared(future, dirRelative);
         }
      };
      final var ctx = command.syncContext(task);
      command.ensureTargetDirPrepared(task, ctx, sourceDir, relative);
      Files.delete(targetDir);
      armed.set(true);

      final var workers = Executors.newFixedThreadPool(2);
      try {
         final var staleWorker = workers.submit(() -> {
            command.ensureTargetDirPrepared(task, ctx, sourceDir, relative);
            return null;
         });
         await(staleRead);
         final var replacingWorker = workers.submit(() -> {
            command.ensureTargetDirPrepared(task, ctx, sourceDir, relative);
            return null;
         });

         await(waiterEntered);
         assertThat(recreations.get()).isEqualTo(1);
         assertThat(Files.exists(targetDir)).isEqualTo(directoryVisible);
         releaseReplacement.countDown();
         staleWorker.get(10, TimeUnit.SECONDS);
         replacingWorker.get(10, TimeUnit.SECONDS);
         assertThat(recreations.get()).isEqualTo(1);
         assertThat(Files.isDirectory(targetDir)).isTrue();
      } finally {
         releaseReplacement.countDown();
         workers.shutdownNow();
         assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
   }

   @Test
   void inspectionFailureReachesWaitersAndAllowsRetry(@TempDir final Path tempDir) throws Exception {
      final var task = task(tempDir, false);
      final Path relative = Path.of("a");
      final Path sourceDir = Files.createDirectory(task.sourceRootAbsolute.resolve(relative));
      final Path targetDir = task.targetRootAbsolute.resolve(relative);
      final var inspections = new AtomicInteger();
      final var inspectionEntered = new CountDownLatch(1);
      final var waiterEntered = new CountDownLatch(1);
      final var failInspection = new CountDownLatch(1);
      final var command = new SyncCommand() {
         @Override
         @Nullable
         BasicFileAttributes readTargetDirAttributes(final Path path) throws IOException {
            if (path.equals(targetDir) && inspections.incrementAndGet() == 1) {
               inspectionEntered.countDown();
               await(failInspection);
               throw new AccessDeniedException(path.toString());
            }
            return super.readTargetDirAttributes(path);
         }

         @Override
         void awaitTargetDirPrepared(final CompletableFuture<@Nullable Void> future, final Path dirRelative) throws IOException {
            if (dirRelative.equals(relative) && !future.isDone()) {
               // The worker has captured the original future, even if failure removes it before get() starts.
               waiterEntered.countDown();
            }
            super.awaitTargetDirPrepared(future, dirRelative);
         }
      };
      final var ctx = command.syncContext(task);
      final var workers = Executors.newFixedThreadPool(2);
      try {
         final var owner = workers.submit(() -> {
            command.ensureTargetDirPrepared(task, ctx, sourceDir, relative);
            return null;
         });
         await(inspectionEntered);
         final var waiter = workers.submit(() -> {
            command.ensureTargetDirPrepared(task, ctx, sourceDir, relative);
            return null;
         });
         await(waiterEntered);
         failInspection.countDown();

         assertThatThrownBy(() -> owner.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(AccessDeniedException.class);
         assertThatThrownBy(() -> waiter.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(AccessDeniedException.class);
         assertThat(Files.exists(targetDir)).isFalse();

         command.ensureTargetDirPrepared(task, ctx, sourceDir, relative);
         assertThat(Files.isDirectory(targetDir)).isTrue();
      } finally {
         failInspection.countDown();
         workers.shutdownNow();
         assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
   }

   @Test
   void dryRunWaitersReuseRecordedPreparation(@TempDir final Path tempDir) throws Exception {
      final var task = task(tempDir, true);
      final Path relative = Path.of("a");
      final Path sourceDir = Files.createDirectory(task.sourceRootAbsolute.resolve(relative));
      final var preparations = new AtomicInteger();
      final var ownerEntered = new CountDownLatch(1);
      final var waiterEntered = new CountDownLatch(1);
      final var releaseOwner = new CountDownLatch(1);
      final var command = new SyncCommand() {
         @Override
         void syncDirShallow(final SyncCommandConfig config, final SyncHelpers.Context ctx, final Path source, final @Nullable Path target,
               final Path dirRelative) throws IOException {
            preparations.incrementAndGet();
            ownerEntered.countDown();
            await(releaseOwner);
            super.syncDirShallow(config, ctx, source, target, dirRelative);
         }

         @Override
         void awaitTargetDirPrepared(final CompletableFuture<@Nullable Void> future, final Path dirRelative) throws IOException {
            if (!future.isDone()) {
               waiterEntered.countDown();
            }
            super.awaitTargetDirPrepared(future, dirRelative);
         }
      };
      final var ctx = command.syncContext(task);
      final var workers = Executors.newFixedThreadPool(2);
      try {
         final var owner = workers.submit(() -> {
            command.ensureTargetDirPrepared(task, ctx, sourceDir, relative);
            return null;
         });
         await(ownerEntered);
         final var waiter = workers.submit(() -> {
            command.ensureTargetDirPrepared(task, ctx, sourceDir, relative);
            return null;
         });
         await(waiterEntered);
         releaseOwner.countDown();
         owner.get(10, TimeUnit.SECONDS);
         waiter.get(10, TimeUnit.SECONDS);

         // Physical absence after dry-run completion must not trigger another preparation.
         command.ensureTargetDirPrepared(task, ctx, sourceDir, relative);
         assertThat(preparations.get()).isEqualTo(1);
         assertThat(Files.exists(task.targetRootAbsolute.resolve(relative))).isFalse();
      } finally {
         releaseOwner.countDown();
         workers.shutdownNow();
         assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
   }

   private static SyncCommandConfig task(final Path tempDir, final boolean dryRun) throws IOException {
      final var task = new SyncCommandConfig();
      task.source = Files.createDirectory(tempDir.resolve("src"));
      task.target = Files.createDirectory(tempDir.resolve("dst"));
      task.dryRun = dryRun;
      task.applyDefaults();
      task.compute();
      return task;
   }

   private static void await(final CountDownLatch latch) throws IOException {
      try {
         if (!latch.await(10, TimeUnit.SECONDS))
            throw new AssertionError("Timed out waiting for the worker interleaving.");
      } catch (final InterruptedException ex) {
         Thread.currentThread().interrupt();
         throw new IOException("Interrupted while coordinating workers.", ex);
      }
   }
}
