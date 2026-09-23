/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.vegardit.copycat.util.YamlUtils;

import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

/**
 * Verifies that sync validates every task before writing and resolves selected root aliases without following child symlinks.
 *
 * @author Vegard IT GmbH
 */
class SyncCommandRootValidationTest {

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void sourceInsideTargetIsRejectedBeforeDeletion(final boolean dryRun, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path sourceFile = Files.writeString(source.resolve("keep.txt"), "source");
      final Path targetFile = Files.writeString(tempDir.resolve("extra.txt"), "target");
      final var task = task(source, tempDir, dryRun);
      task.delete = true;
      task.ignoreErrors = true;

      assertRejected(task);

      assertThat(Files.readString(sourceFile)).isEqualTo("source");
      assertThat(Files.readString(targetFile)).isEqualTo("target");
   }

   @ParameterizedTest
   @CsvSource({"false, false", "true, false", "false, true", "true, true"})
   void targetInsideSourceIsRejectedEvenWithTraversalLimits(final boolean targetExists, final boolean dryRun, @TempDir final Path tempDir)
         throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      Files.writeString(source.resolve("keep.txt"), "source");
      final Path target = source.resolve("backup");
      if (targetExists) {
         Files.createDirectory(target);
      }
      final var task = task(source, target, dryRun);
      // Filters and traversal limits must not make overlapping roots appear safe.
      task.maxDepth = 0;
      task.fileFilters = List.of("ex:backup/**");

      assertRejected(task);

      assertThat(Files.exists(target)).isEqualTo(targetExists);
      assertThat(Files.exists(target.resolve("keep.txt"))).isFalse();
      assertThat(Files.exists(target.resolve("backup"))).isFalse();
   }

   @Test
   void sameDirectoryIsRejectedThroughDifferentSpellings(@TempDir final Path tempDir) {
      final var task = task(tempDir, tempDir.resolve("."), false);

      assertThatThrownBy(task::compute) //
         .isInstanceOf(IllegalArgumentException.class) //
         .hasMessageContaining("same filesystem entry");
   }

   @Test
   void sameDirectoryIsRejectedThroughRootSymlink(@TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path alias = tempDir.resolve("alias");
      createSymlink(alias, source);

      assertRejected(task(source, alias, false));

      assertThat(Files.isSymbolicLink(alias)).isTrue();
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void targetInsideSourceIsRejectedThroughParentSymlink(final boolean targetExists, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path alias = tempDir.resolve("alias");
      createSymlink(alias, source);
      final Path target = alias.resolve("backup");
      if (targetExists) {
         Files.createDirectory(target);
      }
      final var task = task(source, target, false);
      task.maxDepth = 0;

      assertRejected(task);

      assertThat(Files.exists(target)).isEqualTo(targetExists);
      assertThat(Files.exists(target.resolve("backup"))).isFalse();
      assertThat(Files.isSymbolicLink(alias)).isTrue();
   }

   @Test
   void sourceInsideTargetIsRejectedThroughRootSymlink(@TempDir final Path tempDir) throws Exception {
      final Path target = Files.createDirectory(tempDir.resolve("target"));
      final Path source = Files.createDirectory(target.resolve("src"));
      final Path sourceFile = Files.writeString(source.resolve("keep.txt"), "source");
      final Path alias = tempDir.resolve("source-alias");
      createSymlink(alias, source);
      final var task = task(alias, target, false);
      task.delete = true;

      assertRejected(task);

      assertThat(Files.readString(sourceFile)).isEqualTo("source");
      assertThat(Files.isSymbolicLink(alias)).isTrue();
   }

   @Test
   void invalidLaterTaskPreventsEarlierTaskFromWriting(@TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      Files.writeString(source.resolve("keep.txt"), "source");
      final Path target = tempDir.resolve("backup");
      final Path config = tempDir.resolve("tasks.yaml");
      Files.writeString(config, YamlUtils.toYamlString(Map.of("sync", List.of( //
         Map.of("source", source.toString(), "target", target.toString()), //
         Map.of("source", source.toString(), "target", tempDir.toString(), "delete", true)))));
      final var command = new SyncCommand();
      new CommandLine(command).parseArgs("--config", config.toString());

      // Exercise the real preparation boundary: all YAML tasks must be validated before execution can start writing.
      assertThatThrownBy(command::call).isInstanceOf(ParameterException.class).hasMessageContaining("must not overlap");

      assertThat(Files.exists(target)).isFalse();
      assertThat(Files.readString(source.resolve("keep.txt"))).isEqualTo("source");
   }

   @ParameterizedTest
   @CsvSource({"false, false", "true, false", "false, true", "true, true"})
   void sourceRootSymlinkCopiesIndependentTree(final boolean relativeLink, final boolean targetExists, @TempDir final Path tempDir)
         throws Exception {
      final Path sourceParent = Files.createDirectory(tempDir.resolve("source-area"));
      final Path source = Files.createDirectory(sourceParent.resolve("data"));
      final Path sourceFile = Files.writeString(source.resolve("keep.txt"), "source");
      final Path childDir = Files.createDirectory(source.resolve("child"));
      Files.writeString(childDir.resolve("nested.txt"), "nested");
      createSymlink(source.resolve("child-link"), Path.of("child"));
      final Path alias = sourceParent.resolve("current");
      createSymlink(alias, relativeLink ? Path.of("data") : source);
      // A different parent exposes the bug where a relative root link is copied verbatim to the destination.
      final Path targetParent = Files.createDirectory(tempDir.resolve("destination-area"));
      final Path target = targetParent.resolve("backup");
      if (targetExists) {
         Files.createDirectory(target);
         Files.writeString(target.resolve("extra.txt"), "target");
      }
      final var task = task(alias, target, false);

      sync(task);

      assertThat(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)).isTrue();
      assertThat(Files.isSymbolicLink(target)).isFalse();
      assertThat(Files.readString(target.resolve("keep.txt"))).isEqualTo("source");
      assertThat(Files.readString(target.resolve("child/nested.txt"))).isEqualTo("nested");
      assertThat(Files.readSymbolicLink(target.resolve("child-link"))).isEqualTo(Path.of("child"));
      assertThat(task.source).isEqualTo(alias);
      assertThat(task.sourceRootAbsolute).isEqualTo(source.toRealPath());
      assertThat(Files.isSameFile(sourceFile, target.resolve("keep.txt"))).isFalse();
      Files.writeString(target.resolve("keep.txt"), "changed target");
      assertThat(Files.readString(sourceFile)).isEqualTo("source");
      if (targetExists) {
         assertThat(Files.readString(target.resolve("extra.txt"))).isEqualTo("target");
      }
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void explicitTargetRootSymlinkIsPreserved(final boolean filtered, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path child = Files.createDirectory(source.resolve("child"));
      Files.writeString(child.resolve("keep.txt"), "source");
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      Files.writeString(target.resolve("extra.txt"), "target");
      final Path alias = tempDir.resolve("target-alias");
      createSymlink(alias, target);
      final var task = task(source, alias, false);
      if (filtered) {
         task.fileFilters = List.of("ex:**/*.tmp");
      }

      sync(task);

      assertThat(Files.isSymbolicLink(alias)).isTrue();
      assertThat(Files.readSymbolicLink(alias)).isEqualTo(target);
      assertThat(Files.readString(target.resolve("child/keep.txt"))).isEqualTo("source");
      assertThat(Files.readString(target.resolve("extra.txt"))).isEqualTo("target");
      assertThat(task.target).isEqualTo(alias);
      assertThat(task.targetRootAbsolute).isEqualTo(target.toRealPath());
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void invalidTargetRootSymlinkIsRejectedWithoutReplacingIt(final boolean loop, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path sourceFile = Files.writeString(source.resolve("keep.txt"), "source");
      final Path target = tempDir.resolve("target");
      final Path linkValue = Path.of(loop ? "target" : "missing");
      createSymlink(target, linkValue);
      final var task = task(source, target, false);

      assertThatThrownBy(task::compute) //
         .isInstanceOf(IllegalArgumentException.class) //
         .hasCauseInstanceOf(IOException.class);

      assertThat(Files.readSymbolicLink(target)).isEqualTo(linkValue);
      assertThat(Files.readString(sourceFile)).isEqualTo("source");
      assertThat(Files.exists(tempDir.resolve("missing"))).isFalse();
   }

   @ParameterizedTest
   @CsvSource({"false, false", "true, false", "false, true", "true, true"})
   void separateRootsWithCommonNamePrefixRemainValid(final boolean targetExists, final boolean dryRun, @TempDir final Path tempDir)
         throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      Files.writeString(source.resolve("keep.txt"), "source");
      final Path target = tempDir.resolve("src-backup");
      if (targetExists) {
         Files.createDirectory(target);
      }

      sync(task(source, target, dryRun));

      assertThat(Files.exists(target)).isEqualTo(targetExists || !dryRun);
      assertThat(Files.exists(target.resolve("keep.txt"))).isEqualTo(!dryRun);
      assertThat(Files.readString(source.resolve("keep.txt"))).isEqualTo("source");
   }

   @Test
   void missingTargetUsesResolvedParent(@TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      Files.writeString(source.resolve("keep.txt"), "source");
      final Path targetParent = Files.createDirectory(tempDir.resolve("destination-area"));
      final Path alias = tempDir.resolve("parent-alias");
      createSymlink(alias, targetParent);
      final var task = task(source, alias.resolve("backup"), false);

      sync(task);

      assertThat(task.targetRootAbsolute).isEqualTo(targetParent.toRealPath().resolve("backup"));
      assertThat(Files.readString(targetParent.resolve("backup/keep.txt"))).isEqualTo("source");
      assertThat(Files.isSymbolicLink(alias)).isTrue();
   }

   private static void assertRejected(final SyncCommandConfig task) {
      // Root errors now belong to configuration preparation; CLI error translation is covered through command.call().
      assertThatThrownBy(task::compute).isInstanceOf(IllegalArgumentException.class);
   }

   private static void sync(final SyncCommandConfig task) throws Exception {
      task.compute();
      new SyncCommand().doExecute(List.of(task));
   }

   private static SyncCommandConfig task(final Path source, final Path target, final boolean dryRun) {
      final var task = new SyncCommandConfig();
      task.source = source;
      task.target = target;
      task.dryRun = dryRun;
      task.threads = 1;
      task.applyDefaults();
      return task;
   }

   private static void createSymlink(final Path link, final Path target) {
      try {
         Files.createSymbolicLink(link, target);
      } catch (final UnsupportedOperationException | IOException ex) {
         // Only fixture setup may skip for platform permissions; failures in sync itself must still fail the test.
         Assumptions.assumeTrue(false, "Symlinks not supported in this environment: " + ex.getMessage());
      }
   }
}
