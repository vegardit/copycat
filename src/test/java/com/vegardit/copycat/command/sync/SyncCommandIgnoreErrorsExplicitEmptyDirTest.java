/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class SyncCommandIgnoreErrorsExplicitEmptyDirTest {

   @Test
   void ignoreErrorsAlsoAppliesToExplicitEmptyDirectoryHandling(@TempDir final Path tempDir) throws Exception {
      final Path sourceRoot = Files.createDirectory(tempDir.resolve("src"));
      final Path targetRoot = Files.createDirectory(tempDir.resolve("dst"));

      final var task = new SyncCommandConfig();
      task.source = sourceRoot;
      task.target = targetRoot;
      task.dryRun = true;
      task.ignoreErrors = true;
      task.fileFilters = List.of("in:missing", "ex:**");
      task.applyDefaults();
      task.compute();

      final var cmd = new SyncCommand();
      final var sourceFilterCtx = task.toSourceFilterContext();
      final var targetFilterCtx = task.toTargetFilterContext();
      final var dirJobs = new SyncCommand.DirJobQueue(1, new ArrayDeque<>(List.of( //
         new SyncCommand.DirJob(sourceRoot.resolve("missing"), Paths.get("missing"))//
      )));

      assertThatCode(() -> cmd.syncWorker(task, sourceFilterCtx, targetFilterCtx, dirJobs, cmd.syncContext(task)))
         .doesNotThrowAnyException();
   }
}
