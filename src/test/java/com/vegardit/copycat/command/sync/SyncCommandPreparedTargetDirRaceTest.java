/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for a race in filtered multi-threaded sync runs:
 * if a parent target directory is marked as "prepared" but not yet created, another thread can attempt
 * to create a child directory and fail due to missing parents.
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class SyncCommandPreparedTargetDirRaceTest {

   interface ThrowingRunnable extends Runnable {
      @Override
      default void run() {
         try {
            runOrThrow();
         } catch (final Throwable t) { // CHECKSTYLE:IGNORE IllegalCatch
            throw new RuntimeException(t);
         }
      }

      void runOrThrow() throws Exception;
   }

   @Test
   void filteredMultiThreadedSyncWaitsForParentTargetDir(@TempDir final Path tempDir) throws Exception {
      final Path sourceRoot = Files.createDirectory(tempDir.resolve("src"));
      final Path targetRoot = Files.createDirectory(tempDir.resolve("dst"));

      Files.createDirectories(sourceRoot.resolve("foo/a"));
      Files.createDirectories(sourceRoot.resolve("foo/b"));

      final var task = new SyncCommandConfig();
      task.source = sourceRoot;
      task.target = targetRoot;
      task.dryRun = false;
      task.applyDefaults();
      task.compute();

      final var creatorEntered = new CountDownLatch(1);
      final var childEntered = new CountDownLatch(1);
      final var releaseCreator = new CountDownLatch(1);
      final var releaseChild = new CountDownLatch(1);

      final var cmd = new SyncCommand() {
         @Override
         void syncDirShallow(final SyncCommandConfig t, final SyncHelpers.Context ctx, final Path sourcePath,
               final @Nullable Path targetPath, final Path relativePath) throws java.io.IOException {
            if (isFoo(relativePath)) {
               creatorEntered.countDown();
               await(releaseCreator, 10, TimeUnit.SECONDS);
            } else if (isFooChild(relativePath)) {
               childEntered.countDown();
               await(releaseChild, 10, TimeUnit.SECONDS);
            }
            super.syncDirShallow(t, ctx, sourcePath, targetPath, relativePath);
         }
      };

      final ThreadFactory tf = r -> {
         final Thread t = new Thread(r);
         t.setName("sync-race-test");
         t.setDaemon(true);
         return t;
      };

      final ExecutorService exec = Executors.newFixedThreadPool(2, tf);
      try {
         final var ctx = cmd.syncContext(task);
         final Path fooRel = Paths.get("foo");
         final Path fooArel = Paths.get("foo/a");

         final Future<?> creator = exec.submit((ThrowingRunnable) () -> cmd.ensureTargetDirPrepared(task, ctx, sourceRoot.resolve(fooRel),
            fooRel));
         await(creatorEntered, 10, TimeUnit.SECONDS);

         final Future<?> child = exec.submit((ThrowingRunnable) () -> {
            cmd.ensureTargetDirPrepared(task, ctx, sourceRoot.resolve(fooRel), fooRel);
            cmd.ensureTargetDirPrepared(task, ctx, sourceRoot.resolve(fooArel), fooArel);
         });

         final boolean childEnteredEarly = childEntered.await(2, TimeUnit.SECONDS);
         assertThat(Files.exists(targetRoot.resolve(fooRel))).isFalse();

         try {
            assertThat(childEnteredEarly) //
               .as("Child thread must not attempt to prepare foo/a before foo exists.") //
               .isFalse();

            releaseCreator.countDown();
            releaseChild.countDown();
            assertThatCode(() -> child.get(20, TimeUnit.SECONDS)).doesNotThrowAnyException();

            assertThatCode(() -> creator.get(20, TimeUnit.SECONDS)).doesNotThrowAnyException();

            assertThat(Files.exists(targetRoot.resolve(fooRel))).isTrue();
            assertThat(Files.exists(targetRoot.resolve(fooArel))).isTrue();
         } finally {
            releaseChild.countDown();
            releaseCreator.countDown();
         }
      } finally {
         exec.shutdownNow();
      }
   }

   private static boolean isFoo(final Path dirRelative) {
      return dirRelative.getNameCount() == 1 && "foo".equals(dirRelative.getName(0).toString());
   }

   private static boolean isFooChild(final Path dirRelative) {
      return dirRelative.getNameCount() == 2 && "foo".equals(dirRelative.getName(0).toString());
   }

   private static void await(final CountDownLatch latch, final long timeout, final TimeUnit unit) {
      try {
         if (!latch.await(timeout, unit))
            throw new AssertionError("Timed out waiting for latch.");
      } catch (final InterruptedException ex) {
         Thread.currentThread().interrupt();
         throw new AssertionError("Interrupted while waiting for latch.", ex);
      }
   }
}
