/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.watch;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.vegardit.copycat.util.FileUtils;

import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

/**
 * Keeps watch's root preparation policy stable when the shared sync/watch configuration lifecycle changes.
 *
 * @author Vegard IT GmbH
 */
class WatchCommandRootValidationTest {

   private static final class PreparedWatchCommand extends WatchCommand {
      private List<WatchCommandConfig> tasks = List.of();

      @Override
      protected void doExecute(final List<WatchCommandConfig> tasks) {
         // Inspect the real CLI preparation result without starting long-lived watchers or writing to the target.
         this.tasks = tasks;
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void rootAliasesKeepTheirConfiguredPaths(final boolean targetExists, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path sourceAlias = tempDir.resolve("source-alias");
      createSymlink(sourceAlias, source);
      final Path targetParent = Files.createDirectory(tempDir.resolve("destination"));
      final Path targetAlias = tempDir.resolve("target-alias");
      createSymlink(targetAlias, targetParent);
      final Path target = targetExists ? targetAlias : targetAlias.resolve("backup");

      final var command = new PreparedWatchCommand();
      new CommandLine(command).parseArgs(sourceAlias.toString(), target.toString());
      command.call();

      assertThat(command.tasks).hasSize(1);
      final var task = command.tasks.get(0);
      assertThat(task.sourceRootAbsolute).isEqualTo(FileUtils.toAbsolute(sourceAlias));
      assertThat(task.targetRootAbsolute).isEqualTo(FileUtils.toAbsolute(target));
      assertThat(Files.isSymbolicLink(sourceAlias)).isTrue();
      assertThat(Files.isSymbolicLink(targetAlias)).isTrue();
      assertThat(Files.exists(target)).isEqualTo(targetExists);
   }

   @ParameterizedTest
   @CsvSource({"false, false", "false, true", "true, true"})
   void nestedRootsKeepTheirExistingPreparationPolicy(final boolean sourceInsideTarget, final boolean targetExists,
         @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = sourceInsideTarget ? tempDir : source.resolve("backup");
      if (!sourceInsideTarget && targetExists) {
         Files.createDirectory(target);
      }

      final var command = new PreparedWatchCommand();
      new CommandLine(command).parseArgs(source.toString(), target.toString());
      // Nesting rejection belongs to sync; this characterizes preparation, not the safety of watching nested roots.
      command.call();

      assertThat(command.tasks).hasSize(1);
      assertThat(command.tasks.get(0).sourceRootAbsolute).isEqualTo(FileUtils.toAbsolute(source));
      assertThat(command.tasks.get(0).targetRootAbsolute).isEqualTo(FileUtils.toAbsolute(target));
      assertThat(Files.exists(target)).isEqualTo(targetExists);
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void sameFilesystemEntryRemainsACommandLineError(final boolean symlink, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = symlink ? tempDir.resolve("alias") : source.resolve(".");
      if (symlink) {
         createSymlink(target, source);
      }

      final var command = new PreparedWatchCommand();
      new CommandLine(command).parseArgs(source.toString(), target.toString());

      assertThatThrownBy(command::call).isInstanceOf(ParameterException.class).hasMessageContaining("same filesystem entry");
      assertThat(command.tasks).isEmpty();
   }

   private static void createSymlink(final Path link, final Path target) {
      try {
         Files.createSymbolicLink(link, target);
      } catch (final UnsupportedOperationException | IOException ex) {
         // Only fixture setup may skip for platform permissions; preparation failures must still fail the test.
         Assumptions.assumeTrue(false, "Symlinks not supported in this environment: " + ex.getMessage());
      }
   }
}
