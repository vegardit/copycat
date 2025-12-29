/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class SyncCommandSignalAbortTest {

   @Test
   void abortBySignalDoesNotReportDone(@TempDir final Path tempDir) throws Exception {
      final Path sourceRoot = Files.createDirectory(tempDir.resolve("src"));
      final Path targetRoot = Files.createDirectory(tempDir.resolve("dst"));
      Files.writeString(sourceRoot.resolve("file.txt"), "x");

      final var task = new SyncCommandConfig();
      task.source = sourceRoot;
      task.target = targetRoot;
      task.dryRun = true;
      task.threads = 1;
      task.applyDefaults();
      task.compute();

      final var cmd = new SyncCommand();

      // Avoid calling cmd.onSigInt() (it sleeps 500ms). We only need to simulate the state change.
      forceState(cmd, SyncCommand.State.ABORT_BY_SIGNAL);

      assertThatThrownBy(() -> cmd.doExecute(List.of(task))) //
         .isInstanceOf(InterruptedException.class) //
         .hasMessageContaining("aborted");
   }

   private static void forceState(final SyncCommand cmd, final SyncCommand.State state) {
      try {
         final var f = SyncCommand.class.getDeclaredField("state");
         f.setAccessible(true);
         f.set(cmd, state);
      } catch (final ReflectiveOperationException ex) {
         throw new RuntimeException(ex);
      }
   }
}
