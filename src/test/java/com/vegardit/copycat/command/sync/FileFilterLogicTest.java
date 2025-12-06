/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.asNonNull;
import static org.assertj.core.api.Assertions.assertThat;

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
 * Focused tests for {@link AbstractSyncCommandConfig} filter semantics.
 *
 * These tests exercise the include/exclude ordering and the common
 * "include a few patterns, then ex:**" configuration used in issue 186.
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class FileFilterLogicTest {

   @Test
   @DisplayName("ex:** excludes all non-matching paths")
   void testExcludeAllWithCatchAllPattern() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final Path someFile = sourceRoot.resolve("anydir/anyfile.txt");
         Files.createDirectories(asNonNull(someFile.getParent()));
         Files.createFile(someFile);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of("ex:**");
         cfg.applyDefaults();
         cfg.compute();

         final Path someFileRelative = someFile.subpath(sourceRoot.getNameCount(), someFile.getNameCount());

         assertThat(cfg.isExcludedSourcePath(someFile, someFileRelative)).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("include-before-exclude keeps only matching paths")
   void testIncludeThenExcludeOrdering() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final Path includedFile = sourceRoot.resolve("keep-01.log");
         Files.createFile(includedFile);

         final Path excludedFile = sourceRoot.resolve("other-01.log");
         Files.createFile(excludedFile);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of( //
            "in:keep*.*", // include matching files
            "ex:**" // exclude everything else
         );
         cfg.applyDefaults();
         cfg.compute();

         final Path includedFileRelative = includedFile.subpath(sourceRoot.getNameCount(), includedFile.getNameCount());

         final Path excludedFileRelative = excludedFile.subpath(sourceRoot.getNameCount(), excludedFile.getNameCount());

         assertThat(cfg.isExcludedSourcePath(includedFile, includedFileRelative)).isFalse();
         assertThat(cfg.isExcludedSourcePath(excludedFile, excludedFileRelative)).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("exclude-before-include cannot be overridden by later include")
   void testExcludeThenIncludeOrdering() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final Path keepFile = sourceRoot.resolve("keep-01.log");
         Files.createFile(keepFile);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of("ex:**", // catch-all exclude first
            "in:keep*.*" // later include cannot override earlier exclude
         );
         cfg.applyDefaults();
         cfg.compute();

         final Path keepFileRelative = keepFile.subpath(sourceRoot.getNameCount(), keepFile.getNameCount());

         assertThat(cfg.isExcludedSourcePath(keepFile, keepFileRelative)).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("Windows backslash patterns are normalized")
   void testWindowsBackslashPatternsNormalized() throws IOException {
      Assumptions.assumeTrue(SystemUtils.IS_OS_WINDOWS);

      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final Path matchingFile = sourceRoot.resolve("dir/subdir/test.log");
         Files.createDirectories(asNonNull(matchingFile.getParent()));
         Files.createFile(matchingFile);

         final Path nonMatchingFile = sourceRoot.resolve("dir/subdir/test.txt");
         Files.createFile(nonMatchingFile);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of( //
            "in:dir\\subdir\\*.log", //
            "ex:**" //
         );
         cfg.applyDefaults();
         cfg.compute();

         final Path matchingRelative = matchingFile.subpath(sourceRoot.getNameCount(), matchingFile.getNameCount());
         final Path nonMatchingRelative = nonMatchingFile.subpath(sourceRoot.getNameCount(), nonMatchingFile.getNameCount());

         assertThat(cfg.isExcludedSourcePath(matchingFile, matchingRelative)).isFalse();
         assertThat(cfg.isExcludedSourcePath(nonMatchingFile, nonMatchingRelative)).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("Target filters mirror source filter semantics")
   void testTargetFiltersMirrorSourceFilters() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final Path latestLogTarget = targetRoot.resolve("logs/latest.log");
         Files.createDirectories(asNonNull(latestLogTarget.getParent()));
         Files.createFile(latestLogTarget);

         final Path otherLogTarget = targetRoot.resolve("logs/other.log");
         Files.createFile(otherLogTarget);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of( //
            "ex:**/node_modules", //
            "in:logs/latest.log", //
            "ex:logs/*.log" //
         );
         cfg.applyDefaults();
         cfg.compute();

         final Path latestLogTargetRelative = latestLogTarget.subpath(targetRoot.getNameCount(), latestLogTarget.getNameCount());
         final Path otherLogTargetRelative = otherLogTarget.subpath(targetRoot.getNameCount(), otherLogTarget.getNameCount());

         assertThat(cfg.isExcludedTargetPath(latestLogTarget, latestLogTargetRelative)).isFalse();
         assertThat(cfg.isExcludedTargetPath(otherLogTarget, otherLogTargetRelative)).isTrue();
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
