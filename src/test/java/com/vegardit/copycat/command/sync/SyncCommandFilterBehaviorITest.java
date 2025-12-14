/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.asNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.sf.jstuff.core.SystemUtils;

/**
 * Integration-style tests for {@link SyncCommand} that exercise the
 * end-to-end filter semantics described in {@code filter-behavior.md}.
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class SyncCommandFilterBehaviorITest {

   @Test
   @DisplayName("in:**/patch.xml with ex:** only materializes directories along the patch.xml path")
   void testPatchXmlWhitelistWithGlobalExclude() throws Exception {
      final Path sourceRoot = Files.createTempDirectory("copycat-sync-src");
      final Path targetRoot = Files.createTempDirectory("copycat-sync-dst");

      try {
         // included file
         final Path patchFile = sourceRoot.resolve("dev/ant/apache-ant-1.9.6/patch.xml");
         Files.createDirectories(asNonNull(patchFile.getParent()));
         Files.createFile(patchFile);

         // non-included sibling file in the same directory
         final Path otherFile = sourceRoot.resolve("dev/ant/apache-ant-1.9.6/other.txt");
         Files.createFile(otherFile);

         // unrelated top-level subtrees that must not be materialized
         Files.createDirectories(sourceRoot.resolve("network/jdownloader"));
         Files.createDirectories(sourceRoot.resolve("multimedia/avidemux"));
         Files.createDirectories(sourceRoot.resolve("tools/cli-tool"));

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of( //
            "in:**/patch.xml", //
            "ex:**");
         cfg.applyDefaults();
         cfg.compute();

         final var cmd = new SyncCommand();
         cmd.doExecute(List.of(cfg));

         // patch.xml content must be synced
         final Path targetPatch = targetRoot.resolve("dev/ant/apache-ant-1.9.6/patch.xml");
         assertThat(Files.exists(targetPatch)).as("patch.xml should be copied").isTrue();

         // sibling file must not be copied
         final Path targetOther = targetRoot.resolve("dev/ant/apache-ant-1.9.6/other.txt");
         assertThat(Files.exists(targetOther)).as("other.txt should not be copied").isFalse();

         // only the directory chain for the included patch.xml should exist
         assertThat(Files.exists(targetRoot.resolve("dev"))).isTrue();
         assertThat(Files.exists(targetRoot.resolve("dev/ant"))).isTrue();
         assertThat(Files.exists(targetRoot.resolve("dev/ant/apache-ant-1.9.6"))).isTrue();

         // unrelated top-level subtrees must not be materialized
         assertThat(Files.exists(targetRoot.resolve("network"))).isFalse();
         assertThat(Files.exists(targetRoot.resolve("multimedia"))).isFalse();
         assertThat(Files.exists(targetRoot.resolve("tools"))).isFalse();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("in:somedir (with ex:**) creates empty somedir, but in:somedir/** does not")
   void testEmptyDirectoryInclusionPatterns() throws Exception {
      final Path sourceRoot = Files.createTempDirectory("copycat-sync-src");
      final Path targetRootA = Files.createTempDirectory("copycat-sync-dst-a");
      final Path targetRootB = Files.createTempDirectory("copycat-sync-dst-b");

      try {
         // shared source layout: one explicitly named directory and one unrelated directory
         final Path someDir = sourceRoot.resolve("somedir");
         Files.createDirectories(someDir);
         final Path otherDir = sourceRoot.resolve("otherdir");
         Files.createDirectories(otherDir);

         /*
          * Variant A: in:somedir, ex:**  => empty somedir must still be created on target,
          * while unrelated otherdir must not be materialized.
          */
         final var cfgA = new SyncCommandConfig();
         cfgA.source = sourceRoot;
         cfgA.target = targetRootA;
         cfgA.fileFilters = List.of( //
            "in:somedir", //
            "ex:**");
         cfgA.applyDefaults();
         cfgA.compute();

         final var cmdA = new SyncCommand();
         cmdA.doExecute(List.of(cfgA));

         final Path targetSomeDirA = targetRootA.resolve("somedir");
         final Path targetOtherDirA = targetRootA.resolve("otherdir");

         assertThat(Files.exists(targetSomeDirA)).as("somedir should be created for in:somedir").isTrue();
         assertThat(Files.isDirectory(targetSomeDirA)).isTrue();
         assertThat(Files.exists(targetOtherDirA)).as("otherdir should not be materialized").isFalse();

         /*
          * Variant B: in:somedir/**, ex:**  => empty somedir must NOT be created, because the
          * pattern only includes descendants, not the directory itself.
          */
         final var cfgB = new SyncCommandConfig();
         cfgB.source = sourceRoot;
         cfgB.target = targetRootB;
         cfgB.fileFilters = List.of( //
            "in:somedir/**", //
            "ex:**");
         cfgB.applyDefaults();
         cfgB.compute();

         final var cmdB = new SyncCommand();
         cmdB.doExecute(List.of(cfgB));

         final Path targetSomeDirB = targetRootB.resolve("somedir");
         final Path targetOtherDirB = targetRootB.resolve("otherdir");

         // no descendants exist under somedir, so nothing should be created
         assertThat(Files.exists(targetSomeDirB)).as("somedir should not be created for in:somedir/** when empty").isFalse();
         assertThat(Files.exists(targetOtherDirB)).as("otherdir should not be materialized").isFalse();
      } finally {
         deleteRecursive(targetRootB);
         deleteRecursive(targetRootA);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("delete and delete-excluded respect target filter semantics")
   void testDeleteWithTargetFilters() throws Exception {
      final Path sourceRoot = Files.createTempDirectory("copycat-sync-src");
      final Path targetRoot = Files.createTempDirectory("copycat-sync-dst-del");

      try {
         /*
          * Source has only logs/latest.log. Target starts with both logs/latest.log and logs/other.log.
          * Filters:
          *   - ex:** /node_modules
          *   - in:logs/latest.log
          *   - ex:logs/*.log
          *
          * We expect:
          *   - logs/latest.log kept and updated from source.
          *   - logs/other.log treated as extraneous and removed when --delete is enabled.
          */
         final Path sourceLogsDir = sourceRoot.resolve("logs");
         Files.createDirectories(sourceLogsDir);
         final Path sourceLatest = sourceLogsDir.resolve("latest.log");
         Files.writeString(sourceLatest, "from-source");

         final Path targetLogsDir = targetRoot.resolve("logs");
         Files.createDirectories(targetLogsDir);
         final Path targetLatest = targetLogsDir.resolve("latest.log");
         Files.writeString(targetLatest, "old-target");
         final Path targetOther = targetLogsDir.resolve("other.log");
         Files.writeString(targetOther, "should-be-deleted");

         final var baseFilters = List.of( //
            "ex:**/node_modules", //
            "in:logs/latest.log", //
            "ex:logs/*.log");

         /*
          * First run: delete=true, delete-excluded=false
          *
          * - latest.log: included by target filters -> should be updated from source.
          * - other.log: excluded by target filters -> treated as protected and must remain.
          */
         final var cfg1 = new SyncCommandConfig();
         cfg1.source = sourceRoot;
         cfg1.target = targetRoot;
         cfg1.fileFilters = baseFilters;
         cfg1.delete = true;
         cfg1.deleteExcluded = false;
         cfg1.applyDefaults();
         cfg1.compute();

         final var cmd = new SyncCommand();
         cmd.doExecute(List.of(cfg1));

         assertThat(Files.exists(targetLatest)).isTrue();
         assertThat(Files.readString(targetLatest)).isEqualTo("from-source");
         assertThat(Files.exists(targetOther)).as("other.log must be kept when delete-excluded=false").isTrue();

         /*
          * Second run: delete=true, delete-excluded=true
          *
          * Now excluded target entries that are extraneous should be removed.
          */
         final var cfg2 = new SyncCommandConfig();
         cfg2.source = sourceRoot;
         cfg2.target = targetRoot;
         cfg2.fileFilters = baseFilters;
         cfg2.delete = true;
         cfg2.deleteExcluded = true;
         cfg2.applyDefaults();
         cfg2.compute();

         cmd.doExecute(List.of(cfg2));

         assertThat(Files.exists(targetLatest)).isTrue();
         assertThat(Files.readString(targetLatest)).isEqualTo("from-source");
         assertThat(Files.exists(targetOther)).as("other.log must be deleted when delete-excluded=true").isFalse();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("target deletes respect global ex:** for directories with whitelist-style filters")
   void testTargetDeleteHonorsGlobalExcludeForDirectories() throws Exception {
      final Path sourceRoot = Files.createTempDirectory("copycat-sync-src");
      final Path targetRoot = Files.createTempDirectory("copycat-sync-dst-target-global-exclude");

      try {
         // no source entries; target contains an extraneous directory that should be treated as excluded
         final Path protectedDir = targetRoot.resolve("backup");
         Files.createDirectories(protectedDir);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = java.util.List.of( //
            "in:**/patch.xml", //
            "ex:**");
         cfg.delete = true;
         cfg.deleteExcluded = false;
         cfg.applyDefaults();
         cfg.compute();

         final var cmd = new SyncCommand();
         cmd.doExecute(java.util.List.of(cfg));

         // With whitelist-style filters and a global ex:**, an extraneous directory not matching any include
         // should be considered excluded and only removable when delete-excluded=true. Here we expect it to remain.
         assertThat(Files.exists(protectedDir)) //
            .as("backup directory should be protected by ex:** when delete-excluded=false") //
            .isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("global ex:**/* with includes prunes unrelated subtrees end-to-end")
   void testGlobalExcludePrunesUnrelatedSubtreesEndToEnd() throws Exception {
      final Path sourceRoot = Files.createTempDirectory("copycat-sync-src");
      final Path targetRoot = Files.createTempDirectory("copycat-sync-dst-prune");

      try {
         /*
          * Tree mirroring FileFilterLogicTest.testGlobalExcludeWithIncludesPrunesUnrelatedSubtrees:
          *   DUR001.log                     -> included
          *   CDNS1/System01.log             -> included
          *   DURS1/System02.log             -> included
          *   AkoordS1/System99.log          -> excluded, subtree pruned
          */
         final Path durLog = sourceRoot.resolve("DUR001.log");
         Files.writeString(durLog, "dur");

         final Path cdnsDir = sourceRoot.resolve("CDNS1");
         Files.createDirectories(cdnsDir);
         final Path cdnsLog = cdnsDir.resolve("System01.log");
         Files.writeString(cdnsLog, "cdns");

         final Path dursDir = sourceRoot.resolve("DURS1");
         Files.createDirectories(dursDir);
         final Path dursLog = dursDir.resolve("System02.log");
         Files.writeString(dursLog, "durs");

         final Path otherDir = sourceRoot.resolve("AkoordS1");
         Files.createDirectories(otherDir);
         final Path otherLog = otherDir.resolve("System99.log");
         Files.writeString(otherLog, "other");

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of( //
            "in:DUR*.*", //
            "in:CDNS*/System*.log", //
            "in:DURS*/System*.log", //
            "ex:**/*", //
            "ex:*.*");
         cfg.applyDefaults();
         cfg.compute();

         final var cmd = new SyncCommand();
         cmd.doExecute(List.of(cfg));

         // included files must exist on target
         assertThat(Files.exists(targetRoot.resolve("DUR001.log"))).isTrue();
         assertThat(Files.exists(targetRoot.resolve("CDNS1/System01.log"))).isTrue();
         assertThat(Files.exists(targetRoot.resolve("DURS1/System02.log"))).isTrue();

         // unrelated subtree must not be materialized at all
         assertThat(Files.exists(targetRoot.resolve("AkoordS1"))).isFalse();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("Hidden directories are not traversed or synced when excludeHiddenFiles=true")
   void testHiddenDirectoriesExcludedFromTraversalAndSync() throws Exception {
      final Path sourceRoot = Files.createTempDirectory("copycat-sync-src");
      final Path targetRoot = Files.createTempDirectory("copycat-sync-dst-hidden-dirs");

      try {
         final Path hiddenDir;
         if (SystemUtils.IS_OS_WINDOWS) {
            hiddenDir = sourceRoot.resolve("hidden-dir");
            Files.createDirectories(hiddenDir);
            Files.setAttribute(hiddenDir, "dos:hidden", true);
         } else {
            hiddenDir = sourceRoot.resolve(".hidden-dir");
            Files.createDirectories(hiddenDir);
         }

         final Path fileInHiddenDir = hiddenDir.resolve("visible.txt");
         Files.writeString(fileInHiddenDir, "should-not-be-synced");

         final Path visibleDir = sourceRoot.resolve("visible");
         Files.createDirectories(visibleDir);
         final Path visibleFile = visibleDir.resolve("visible.txt");
         Files.writeString(visibleFile, "should-be-synced");

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.excludeHiddenFiles = true;
         cfg.excludeHiddenSystemFiles = false;
         cfg.excludeSystemFiles = false;
         cfg.applyDefaults();
         cfg.compute();

         final var cmd = new SyncCommand();
         cmd.doExecute(List.of(cfg));

         final Path targetVisibleFile = targetRoot.resolve("visible/visible.txt");
         assertThat(Files.exists(targetVisibleFile)) //
            .as("visible file outside hidden directories should be synced") //
            .isTrue();

         final Path targetHiddenDir = SystemUtils.IS_OS_WINDOWS ? targetRoot.resolve("hidden-dir") : targetRoot.resolve(".hidden-dir");
         assertThat(Files.exists(targetHiddenDir)) //
            .as("hidden directory should not be materialized when excludeHiddenFiles=true") //
            .isFalse();

         final Path targetFileInHiddenDir = targetHiddenDir.resolve("visible.txt");
         assertThat(Files.exists(targetFileInHiddenDir)) //
            .as("files inside hidden directories must not be synced when excludeHiddenFiles=true") //
            .isFalse();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("max-depth=0 syncs only root-level files")
   void testMaxDepthZeroSyncsOnlyRootLevelFiles() throws Exception {
      final Path sourceRoot = Files.createTempDirectory("copycat-sync-src");
      final Path targetRoot = Files.createTempDirectory("copycat-sync-dst-depth");

      try {
         final Path rootFile = sourceRoot.resolve("root.txt");
         Files.writeString(rootFile, "root");

         final Path nestedFile = sourceRoot.resolve("sub/nested.txt");
         Files.createDirectories(nestedFile.getParent());
         Files.writeString(nestedFile, "nested");

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.maxDepth = 0;
         cfg.applyDefaults();
         cfg.compute();

         final var cmd = new SyncCommand();
         cmd.doExecute(List.of(cfg));

         // root-level file must be copied
         assertThat(Files.exists(targetRoot.resolve("root.txt"))).isTrue();

         // nested directory and file must not be created
         assertThat(Files.exists(targetRoot.resolve("sub"))).isFalse();
         assertThat(Files.exists(targetRoot.resolve("sub/nested.txt"))).isFalse();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("explicitly included empty nested directory creates missing parent directories")
   void testExplicitEmptyNestedDirectoryCreatesParents() throws Exception {
      final Path sourceRoot = Files.createTempDirectory("copycat-sync-src");
      final Path targetRoot = Files.createTempDirectory("copycat-sync-dst-empty-nested");

      try {
         final Path nestedDir = sourceRoot.resolve("foo/bar");
         Files.createDirectories(nestedDir);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of( //
            "in:foo/bar", //
            "ex:**");
         cfg.applyDefaults();
         cfg.compute();

         final var cmd = new SyncCommand();
         assertThatCode(() -> cmd.doExecute(List.of(cfg))) //
            .as("sync should not fail when explicitly including empty nested dir") //
            .doesNotThrowAnyException();

         assertThat(Files.exists(targetRoot.resolve("foo"))).isTrue();
         assertThat(Files.exists(targetRoot.resolve("foo/bar"))).isTrue();
         assertThat(Files.isDirectory(targetRoot.resolve("foo/bar"))).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("descendant-only include does not materialize empty nested directory")
   void testDescendantOnlyIncludeDoesNotCreateEmptyNestedDir() throws Exception {
      final Path sourceRoot = Files.createTempDirectory("copycat-sync-src");
      final Path targetRoot = Files.createTempDirectory("copycat-sync-dst-empty-desc");

      try {
         final Path nestedDir = sourceRoot.resolve("foo/bar");
         Files.createDirectories(nestedDir);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of( //
            "in:foo/bar/**", //
            "ex:**");
         cfg.applyDefaults();
         cfg.compute();

         final var cmd = new SyncCommand();
         assertThatCode(() -> cmd.doExecute(List.of(cfg))) //
            .as("sync should not fail for descendant-only include with empty dir") //
            .doesNotThrowAnyException();

         assertThat(Files.exists(targetRoot.resolve("foo/bar"))).as(
            "foo/bar should not be created when only descendants are included and none exist").isFalse();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("explicitly included nested directory with trailing slash is treated as explicit include")
   void testExplicitEmptyNestedDirectoryTrailingSlash() throws Exception {
      final Path sourceRoot = Files.createTempDirectory("copycat-sync-src");
      final Path targetRoot = Files.createTempDirectory("copycat-sync-dst-empty-trailing");

      try {
         final Path nestedDir = sourceRoot.resolve("foo/bar");
         Files.createDirectories(nestedDir);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of( //
            "in:foo/bar/", //
            "ex:**");
         cfg.applyDefaults();
         cfg.compute();

         final var cmd = new SyncCommand();
         assertThatCode(() -> cmd.doExecute(List.of(cfg))) //
            .as("sync should not fail when explicitly including empty nested dir with trailing slash") //
            .doesNotThrowAnyException();

         assertThat(Files.exists(targetRoot.resolve("foo"))).isTrue();
         assertThat(Files.exists(targetRoot.resolve("foo/bar"))).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("filtered directory symlink ensures parent directories exist before copy")
   void testFilteredDirectorySymlinkCreatesParents() throws Exception {
      final Path sourceRoot = Files.createTempDirectory("copycat-sync-src");
      final Path targetRoot = Files.createTempDirectory("copycat-sync-dst-symlink");

      try {
         // create real target directory that the symlink will point to
         final Path realTargetDir = sourceRoot.resolve("real-target");
         Files.createDirectories(realTargetDir);

         // create nested directory structure and a directory symlink inside it
         final Path linkParent = sourceRoot.resolve("foo/bar");
         Files.createDirectories(linkParent);
         final Path dirSymlink = linkParent.resolve("link");

         try { // CHECKSTYLE:IGNORE .*
            Files.createSymbolicLink(dirSymlink, realTargetDir);
         } catch (final UnsupportedOperationException | IOException ex) {
            // environment does not support or allow creating symlinks; skip this test
            Assumptions.assumeTrue(false, "Symlinks not supported in this environment: " + ex.getMessage());
         }

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of( //
            "in:foo/bar/link", //
            "ex:**");
         cfg.applyDefaults();
         cfg.compute();

         final var cmd = new SyncCommand();
         cmd.doExecute(List.of(cfg));

         final Path targetDirSymlink = targetRoot.resolve("foo/bar/link");
         assertThat(Files.exists(targetDirSymlink)).as("directory symlink should be copied").isTrue();
         assertThat(Files.isSymbolicLink(targetDirSymlink)).isTrue();

         // parent directories must exist as part of the symlink creation
         assertThat(Files.exists(targetRoot.resolve("foo"))).isTrue();
         assertThat(Files.exists(targetRoot.resolve("foo/bar"))).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   private static void deleteRecursive(final Path root) throws IOException {
      if (!Files.exists(root))
         return;
      try (var files = Files.walk(root)) {
         files.sorted(Comparator.reverseOrder()).forEach(path -> {
            try {
               Files.delete(path);
            } catch (final IOException ex) {
               // ignore cleanup failures in tests
            }
         });
      }
   }
}
