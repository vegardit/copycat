/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.watch;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.lateNonNull;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.methvin.watcher.DirectoryChangeEvent;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class WatchCommandOverflowTest {

   @TempDir
   Path tempDir = lateNonNull();

   @Test
   void overflowMarksWatchAsFailedEvenWithoutEventPath() throws Exception {
      final Path sourceRoot = Files.createDirectory(tempDir.resolve("src"));
      final Path targetRoot = Files.createDirectory(tempDir.resolve("dst"));

      final var task = new WatchCommandConfig();
      task.source = sourceRoot;
      task.target = targetRoot;
      task.applyDefaults();
      task.compute();

      final var cmd = new WatchCommand();
      try {
         final var event = new DirectoryChangeEvent(DirectoryChangeEvent.EventType.OVERFLOW, false, null, null, 0, sourceRoot);

         cmd.onFileChanged(task, event);

         assertThatThrownBy(cmd::throwIfOverflowFailure) //
            .isInstanceOf(IllegalStateException.class) //
            .hasMessageContaining("overflow") //
            .hasMessageContaining("full resync is required") //
            .hasMessageContaining(sourceRoot.toString());
      } finally {
         cmd.shutdownRetryExecutor();
      }
   }
}
