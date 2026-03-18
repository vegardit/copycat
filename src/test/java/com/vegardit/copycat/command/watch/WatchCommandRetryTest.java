/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.watch;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.lateNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.hashing.FileHash;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class WatchCommandRetryTest {

   @TempDir
   Path tempDir = lateNonNull();

   @Test
   void retriesTransientFileSyncFailure() throws Exception {
      final Path sourceRoot = Files.createDirectory(tempDir.resolve("src"));
      final Path targetRoot = Files.createDirectory(tempDir.resolve("dst"));
      final Path sourceFile = Files.writeString(sourceRoot.resolve("example.txt"), "updated");
      final Path targetFile = Files.writeString(targetRoot.resolve("example.txt"), "stale");

      final var task = new WatchCommandConfig();
      task.source = sourceRoot;
      task.target = targetRoot;
      task.applyDefaults();
      task.compute();

      final class RetryingWatchCommand extends WatchCommand {
         final AtomicInteger syncAttempts = new AtomicInteger();
         final CountDownLatch retryCompleted = new CountDownLatch(1);

         @Override
         long eventRetryDelayMillis(final int attempt) {
            return 0;
         }

         @Override
         int maxEventRetryAttempts() {
            return 2;
         }

         @Override
         void syncFile(final WatchCommandConfig cfg, final Path sourcePath, final Path targetPath, final boolean contentChanged)
               throws IOException {
            if (syncAttempts.getAndIncrement() == 0)
               throw new IOException("Simulated transient sync failure");

            super.syncFile(cfg, sourcePath, targetPath, contentChanged);
            retryCompleted.countDown();
         }
      }

      final var cmd = new RetryingWatchCommand();
      try {
         final var event = new DirectoryChangeEvent(DirectoryChangeEvent.EventType.MODIFY, false, sourceFile, FileHash.fromLong(1L), 1,
            sourceRoot);

         cmd.onFileChanged(task, event);

         assertThat(cmd.retryCompleted.await(2, TimeUnit.SECONDS)).isTrue();
         assertThat(cmd.syncAttempts.get()).isEqualTo(2);
         assertThat(Files.readString(targetFile)).isEqualTo("updated");
      } finally {
         cmd.shutdownRetryExecutor();
      }
   }
}
