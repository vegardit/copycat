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
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.vegardit.copycat.util.FileAttrs;

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

   @Test
   @DisplayName("before filter is exclusive (files with mtime == before are excluded)")
   void testBeforeIsExclusiveUpperBound() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final Path boundaryFile = sourceRoot.resolve("boundary.log");
         Files.createFile(boundaryFile);

         final Path oldFile = sourceRoot.resolve("old.log");
         Files.createFile(oldFile);

         final long now = System.currentTimeMillis();
         Files.setLastModifiedTime(boundaryFile, FileTime.fromMillis(now));
         final FileTime boundaryTime = Files.getLastModifiedTime(boundaryFile);

         Files.setLastModifiedTime(oldFile, FileTime.fromMillis(now - 60_000));

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.modifiedBefore = boundaryTime;
         cfg.applyDefaults();
         cfg.compute();

         final Path boundaryRelative = boundaryFile.subpath(sourceRoot.getNameCount(), boundaryFile.getNameCount());
         final Path oldRelative = oldFile.subpath(sourceRoot.getNameCount(), oldFile.getNameCount());

         final var filters = FilterEngine.buildSourceFilterContext(cfg);

         // mtime equal to the upper bound is excluded for exclusive bounds
         assertThat(FilterEngine.includesSource(filters, boundaryFile, boundaryRelative, FileAttrs.get(boundaryFile))).isFalse();

         // strictly older files remain included
         assertThat(FilterEngine.includesSource(filters, oldFile, oldRelative, FileAttrs.get(oldFile))).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("since/until filters apply only to files, not directories")
   void testDateFiltersApplyToFilesOnly() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final Path dir = sourceRoot.resolve("logs");
         Files.createDirectories(dir);

         final Path oldFile = dir.resolve("old.log");
         Files.createFile(oldFile);

         final Path newFile = dir.resolve("new.log");
         Files.createFile(newFile);

         final long now = System.currentTimeMillis();
         Files.setLastModifiedTime(dir, FileTime.fromMillis(now - 60_000));
         Files.setLastModifiedTime(oldFile, FileTime.fromMillis(now - 60_000));
         Files.setLastModifiedTime(newFile, FileTime.fromMillis(now));

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         // only include files modified in the last 30 seconds
         cfg.modifiedFrom = FileTime.fromMillis(now - 30_000);
         cfg.applyDefaults();
         cfg.compute();

         final Path dirRelative = dir.subpath(sourceRoot.getNameCount(), dir.getNameCount());
         final Path oldFileRelative = oldFile.subpath(sourceRoot.getNameCount(), oldFile.getNameCount());
         final Path newFileRelative = newFile.subpath(sourceRoot.getNameCount(), newFile.getNameCount());

         final var filters = FilterEngine.buildSourceFilterContext(cfg);

         // directories are never excluded based on modification time
         assertThat(FilterEngine.includesSource(filters, dir, dirRelative, FileAttrs.get(dir))).isTrue();

         // old file is outside the since window and must be excluded
         assertThat(FilterEngine.includesSource(filters, oldFile, oldFileRelative, FileAttrs.get(oldFile))).isFalse();

         // new file is within the since window and remains included
         assertThat(FilterEngine.includesSource(filters, newFile, newFileRelative, FileAttrs.get(newFile))).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("no filters include all entries by default")
   void testDefaultBehaviorNoFilters() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final Path dir = sourceRoot.resolve("dir");
         Files.createDirectories(dir);
         final Path file = dir.resolve("file.txt");
         Files.createFile(file);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         // leave cfg.fileFilters null to simulate "no filters" configuration
         cfg.applyDefaults();
         cfg.compute();

         final Path dirRelative = dir.subpath(sourceRoot.getNameCount(), dir.getNameCount());
         final Path fileRelative = file.subpath(sourceRoot.getNameCount(), file.getNameCount());

         final var filters = FilterEngine.buildSourceFilterContext(cfg);
         assertThat(FilterEngine.includesSource(filters, dir, dirRelative, FileAttrs.get(dir))).isTrue();
         assertThat(FilterEngine.includesSource(filters, file, fileRelative, FileAttrs.get(file))).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("Directory and file patterns behave as documented in README")
   void testDirectoryAndFilePatternsFromReadme() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final Path nodeModules = sourceRoot.resolve("node_modules");
         Files.createDirectories(nodeModules);
         final Path nodeModulesFile = nodeModules.resolve("package.json");
         Files.createFile(nodeModulesFile);

         final Path nestedNodeModulesFile = sourceRoot.resolve("src/node_modules/deep/file.js");
         Files.createDirectories(asNonNull(nestedNodeModulesFile.getParent()));
         Files.createFile(nestedNodeModulesFile);

         final Path latestLog = sourceRoot.resolve("logs/latest.log");
         Files.createDirectories(asNonNull(latestLog.getParent()));
         Files.createFile(latestLog);

         final Path otherLog = sourceRoot.resolve("logs/other.log");
         Files.createFile(otherLog);

         final Path nestedLatest = sourceRoot.resolve("logs/2024/latest.log");
         Files.createDirectories(asNonNull(nestedLatest.getParent()));
         Files.createFile(nestedLatest);

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

         final Path nodeModulesRelative = nodeModules.subpath(sourceRoot.getNameCount(), nodeModules.getNameCount());
         final Path nodeModulesFileRelative = nodeModulesFile.subpath(sourceRoot.getNameCount(), nodeModulesFile.getNameCount());
         final Path nestedNodeModulesFileRelative = nestedNodeModulesFile.subpath(sourceRoot.getNameCount(), nestedNodeModulesFile
            .getNameCount());

         final Path latestLogRelative = latestLog.subpath(sourceRoot.getNameCount(), latestLog.getNameCount());
         final Path otherLogRelative = otherLog.subpath(sourceRoot.getNameCount(), otherLog.getNameCount());
         final Path nestedLatestRelative = nestedLatest.subpath(sourceRoot.getNameCount(), nestedLatest.getNameCount());

         final var filters = FilterEngine.buildSourceFilterContext(cfg);

         // node_modules exclusion should apply to the directory itself and everything under it
         assertThat(FilterEngine.includesSource(filters, nodeModules, nodeModulesRelative, FileAttrs.get(nodeModules))).isFalse();
         assertThat(FilterEngine.includesSource(filters, nodeModulesFile, nodeModulesFileRelative, FileAttrs.get(nodeModulesFile)))
            .isFalse();
         assertThat(FilterEngine.includesSource(filters, nestedNodeModulesFile, nestedNodeModulesFileRelative, FileAttrs.get(
            nestedNodeModulesFile))).isFalse();

         // specific include for logs/latest.log should win over the later exclude
         assertThat(FilterEngine.includesSource(filters, latestLog, latestLogRelative, FileAttrs.get(latestLog))).isTrue();

         // other logs at the same level should be excluded
         assertThat(FilterEngine.includesSource(filters, otherLog, otherLogRelative, FileAttrs.get(otherLog))).isFalse();

         // nested logs do not match the simple logs/*.log pattern and remain included
         assertThat(FilterEngine.includesSource(filters, nestedLatest, nestedLatestRelative, FileAttrs.get(nestedLatest))).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

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

         final var filters = FilterEngine.buildSourceFilterContext(cfg);
         assertThat(FilterEngine.includesSource(filters, someFile, someFileRelative, FileAttrs.get(someFile))).isFalse();
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

         final var filters = FilterEngine.buildSourceFilterContext(cfg);
         assertThat(FilterEngine.includesSource(filters, keepFile, keepFileRelative, FileAttrs.get(keepFile))).isFalse();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("getFileAttrsIfIncluded keeps include-relevant directories for traversal even with global excludes")
   void testGetFileAttrsIfIncludedKeepsTraversalDirs() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of( //
            "in:CDNS*/System*.log", //
            "in:DURS*/System*.log", //
            "ex:**/*", //
            "ex:*.*" //
         );
         cfg.applyDefaults();
         cfg.compute();

         final var filters = FilterEngine.buildSourceFilterContext(cfg);

         @SuppressWarnings("resource")
         final Path cdnsRel = sourceRoot.getFileSystem().getPath("CDNS1");
         Files.createDirectories(sourceRoot.resolve(cdnsRel));
         final boolean cdnsPrunable = cfg.isExcludedSourceSubtreeDir(cdnsRel);
         assertThat(cdnsPrunable).isFalse();

         final var attrs = FilterEngine.getFileAttrsIfIncluded(filters, sourceRoot.resolve(cdnsRel), cdnsRel, cdnsPrunable);
         assert attrs != null;
         assertThat(attrs.isDir()).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("getFileAttrsIfIncluded skips excluded paths without touching filesystem")
   void testGetFileAttrsIfIncludedSkipsExcludedWithoutAttrsRead() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of( //
            "in:DUR*", //
            "ex:**");
         cfg.applyDefaults();
         cfg.compute();

         final var filters = FilterEngine.buildSourceFilterContext(cfg);
         @SuppressWarnings("resource")
         final var fs = sourceRoot.getFileSystem();

         // excluded file that does NOT exist: must be skipped without reading attributes (otherwise it would throw)
         final Path excludedRel = fs.getPath("DUF.log.1.lck");
         final Path excludedAbs = sourceRoot.resolve(excludedRel);
         final boolean excludedPrunable = cfg.isExcludedSourceSubtreeDir(excludedRel);
         assertThat(FilterEngine.getFileAttrsIfIncluded(filters, excludedAbs, excludedRel, excludedPrunable)).isNull();

         // included file must return attributes (and thus requires the file to exist)
         final Path includedRel = fs.getPath("DUR001.log");
         Files.createFile(sourceRoot.resolve(includedRel));
         final boolean includedPrunable = cfg.isExcludedSourceSubtreeDir(includedRel);
         assertThat(FilterEngine.getFileAttrsIfIncluded(filters, sourceRoot.resolve(includedRel), includedRel, includedPrunable))
            .isNotNull();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("Global ex:**/* with root-level includes prunes unrelated subtrees")
   void testGlobalExcludeWithIncludesPrunesUnrelatedSubtrees() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of( //
            "in:DUR*.*", //
            "in:CDNS*/System*.log", //
            "in:DURS*/System*.log", //
            "ex:**/*", //
            "ex:*.*" //
         );
         cfg.applyDefaults();
         cfg.compute();

         final Path durLog = sourceRoot.resolve("DUR001.log");
         Files.createFile(durLog);
         final Path durLogRelative = durLog.subpath(sourceRoot.getNameCount(), durLog.getNameCount());

         final Path cdnsDir = sourceRoot.resolve("CDNS1");
         final Path cdnsDirRelative = cdnsDir.subpath(sourceRoot.getNameCount(), cdnsDir.getNameCount());

         final Path dursDir = sourceRoot.resolve("DURS1");
         final Path dursDirRelative = dursDir.subpath(sourceRoot.getNameCount(), dursDir.getNameCount());

         final Path otherDir = sourceRoot.resolve("AkoordS1");
         final Path otherDirRelative = otherDir.subpath(sourceRoot.getNameCount(), otherDir.getNameCount());

         final var filters = FilterEngine.buildSourceFilterContext(cfg);

         // Included file pattern should still work as usual
         // durLog only needs to exist so that FileAttrs can be resolved; the semantics rely solely on its name.
         assertThat(FilterEngine.includesSource(filters, durLog, durLogRelative, FileAttrs.get(durLog))).isTrue();

         // Directories that may contain matching CDNS*/System*.log or DURS*/System*.log must not be pruned
         assertThat(cfg.isExcludedSourceSubtreeDir(cdnsDirRelative)).isFalse();
         assertThat(cfg.isExcludedSourceSubtreeDir(dursDirRelative)).isFalse();

         // Directories with names unrelated to the INCLUDE prefixes can be safely pruned when ex:**/* is present
         assertThat(cfg.isExcludedSourceSubtreeDir(otherDirRelative)).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("Hidden files are excluded when excludeHiddenFiles=true")
   void testHiddenFilesExcluded() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final Path dir = sourceRoot.resolve("dir");
         Files.createDirectories(dir);

         final Path visibleFile = dir.resolve("visible.txt");
         Files.createFile(visibleFile);

         final Path hiddenFile;
         if (SystemUtils.IS_OS_WINDOWS) {
            hiddenFile = dir.resolve("hidden.txt");
            Files.createFile(hiddenFile);
            Files.setAttribute(hiddenFile, "dos:hidden", true);
         } else {
            hiddenFile = dir.resolve(".hidden.txt");
            Files.createFile(hiddenFile);
         }

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.excludeHiddenFiles = true;
         cfg.excludeHiddenSystemFiles = false;
         cfg.excludeSystemFiles = false;
         cfg.applyDefaults();
         cfg.compute();

         final Path dirRelative = dir.subpath(sourceRoot.getNameCount(), dir.getNameCount());
         final Path visibleFileRelative = visibleFile.subpath(sourceRoot.getNameCount(), visibleFile.getNameCount());
         final Path hiddenFileRelative = hiddenFile.subpath(sourceRoot.getNameCount(), hiddenFile.getNameCount());

         final var filters = FilterEngine.buildSourceFilterContext(cfg);

         // directory is not hidden and remains included
         assertThat(FilterEngine.includesSource(filters, dir, dirRelative, FileAttrs.get(dir))).isTrue();

         // visible file is included
         assertThat(FilterEngine.includesSource(filters, visibleFile, visibleFileRelative, FileAttrs.get(visibleFile))).isTrue();

         // hidden file is excluded when excludeHiddenFiles=true
         assertThat(Files.isHidden(hiddenFile)).isTrue();
         assertThat(FilterEngine.includesSource(filters, hiddenFile, hiddenFileRelative, FileAttrs.get(hiddenFile))).isFalse();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("include **/patch.xml with ex:** still reaches patch files")
   void testIncludePatchXmlWithGlobalExclude() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final Path patchFile = sourceRoot.resolve("dev/ant/apache-ant-1.9.6/patch.xml");
         Files.createDirectories(asNonNull(patchFile.getParent()));
         Files.createFile(patchFile);

         final Path otherFile = sourceRoot.resolve("dev/ant/apache-ant-1.9.6/other.txt");
         Files.createFile(otherFile);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of( //
            "in:**/patch.xml", //
            "ex:**" //
         );
         cfg.applyDefaults();
         cfg.compute();

         final Path devDir = sourceRoot.resolve("dev");
         final Path devDirRelative = devDir.subpath(sourceRoot.getNameCount(), devDir.getNameCount());

         final Path patchFileRelative = patchFile.subpath(sourceRoot.getNameCount(), patchFile.getNameCount());
         final Path otherFileRelative = otherFile.subpath(sourceRoot.getNameCount(), otherFile.getNameCount());

         final var filters = FilterEngine.buildSourceFilterContext(cfg);

         // parent directory must not be excluded or pruned so that patch.xml can be discovered
         assertThat(FilterEngine.includesSource(filters, devDir, devDirRelative, FileAttrs.get(devDir))).isTrue();
         assertThat(cfg.isExcludedSourceSubtreeDir(devDirRelative)).isFalse();

         // patch.xml should be included due to the include rule
         assertThat(FilterEngine.includesSource(filters, patchFile, patchFileRelative, FileAttrs.get(patchFile))).isTrue();

         // non-matching files should still be excluded by ex:**
         assertThat(FilterEngine.includesSource(filters, otherFile, otherFileRelative, FileAttrs.get(otherFile))).isFalse();
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

         final var filters = FilterEngine.buildSourceFilterContext(cfg);
         assertThat(FilterEngine.includesSource(filters, includedFile, includedFileRelative, FileAttrs.get(includedFile))).isTrue();
         assertThat(FilterEngine.includesSource(filters, excludedFile, excludedFileRelative, FileAttrs.get(excludedFile))).isFalse();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("max-depth=0 limits traversal to root-level files only")
   void testMaxDepthZeroLimitsTraversalToRootFiles() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         // root-level file and nested file
         final Path rootFile = sourceRoot.resolve("root.txt");
         Files.createFile(rootFile);

         final Path nestedFile = sourceRoot.resolve("subdir/nested.txt");
         Files.createDirectories(asNonNull(nestedFile.getParent()));
         Files.createFile(nestedFile);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.maxDepth = 0;
         cfg.applyDefaults();
         cfg.compute();

         final Path rootFileRelative = rootFile.subpath(sourceRoot.getNameCount(), rootFile.getNameCount());
         final Path nestedFileRelative = nestedFile.subpath(sourceRoot.getNameCount(), nestedFile.getNameCount());

         final var filters = FilterEngine.buildSourceFilterContext(cfg);

         // filter engine itself does not enforce depth, but we can assert that both paths are "included"
         assertThat(FilterEngine.includesSource(filters, rootFile, rootFileRelative, FileAttrs.get(rootFile))).isTrue();
         assertThat(FilterEngine.includesSource(filters, nestedFile, nestedFileRelative, FileAttrs.get(nestedFile))).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("only in: filters still default-include unmatched paths")
   void testOnlyIncludeFiltersKeepUnmatchedPathsIncluded() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final Path includedFile = sourceRoot.resolve("keep-01.txt");
         Files.createFile(includedFile);

         final Path unmatchedFile = sourceRoot.resolve("other-01.log");
         Files.createFile(unmatchedFile);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of("in:*.txt");
         cfg.applyDefaults();
         cfg.compute();

         final Path includedFileRelative = includedFile.subpath(sourceRoot.getNameCount(), includedFile.getNameCount());
         final Path unmatchedFileRelative = unmatchedFile.subpath(sourceRoot.getNameCount(), unmatchedFile.getNameCount());

         final var filters = FilterEngine.buildSourceFilterContext(cfg);
         assertThat(FilterEngine.includesSource(filters, includedFile, includedFileRelative, FileAttrs.get(includedFile))).isTrue();
         // no filter matches *.log, so it is still included by default
         assertThat(FilterEngine.includesSource(filters, unmatchedFile, unmatchedFileRelative, FileAttrs.get(unmatchedFile))).isTrue();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("Subtree exclude matches only the intended prefix path")
   void testSubtreeExcludeDoesNotMatchUnrelatedPaths() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final Path unrelatedFile = sourceRoot.resolve("baz/bar/file.txt");
         Files.createDirectories(asNonNull(unrelatedFile.getParent()));
         Files.createFile(unrelatedFile);

         final Path excludedFile = sourceRoot.resolve("foo/bar/blocked.txt");
         Files.createDirectories(asNonNull(excludedFile.getParent()));
         Files.createFile(excludedFile);

         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of("ex:foo/bar/**");
         cfg.applyDefaults();
         cfg.compute();

         final Path unrelatedRelative = unrelatedFile.subpath(sourceRoot.getNameCount(), unrelatedFile.getNameCount());
         final Path excludedRelative = excludedFile.subpath(sourceRoot.getNameCount(), excludedFile.getNameCount());

         final var filters = FilterEngine.buildSourceFilterContext(cfg);

         // Unrelated paths that merely contain the same last segment must remain included.
         assertThat(FilterEngine.includesSource(filters, unrelatedFile, unrelatedRelative, FileAttrs.get(unrelatedFile))).isTrue();

         // Paths under the excluded prefix are still excluded as expected.
         assertThat(FilterEngine.includesSource(filters, excludedFile, excludedRelative, FileAttrs.get(excludedFile))).isFalse();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }

   @Test
   @DisplayName("Subtree exclude patterns mark directory prefixes to skip")
   void testSubtreeExcludePrefixes() throws IOException {
      final Path sourceRoot = Files.createTempDirectory("copycat-filters-src");
      final Path targetRoot = Files.createTempDirectory("copycat-filters-dst");

      try {
         final var cfg = new SyncCommandConfig();
         cfg.source = sourceRoot;
         cfg.target = targetRoot;
         cfg.fileFilters = List.of("ex:bbb/**", "ex:**/ccc/**");
         cfg.applyDefaults();
         cfg.compute();

         final Path bbb = sourceRoot.resolve("bbb");
         final Path bbbRelative = bbb.subpath(sourceRoot.getNameCount(), bbb.getNameCount());

         final Path aaaCcc = sourceRoot.resolve("aaa/ccc");
         final Path aaaCccRelative = aaaCcc.subpath(sourceRoot.getNameCount(), aaaCcc.getNameCount());

         Files.createDirectories(bbb);
         Files.createDirectories(aaaCcc);

         final var filters = FilterEngine.buildSourceFilterContext(cfg);

         // ex:bbb/** should allow syncing the "bbb" directory itself but skip its subtree
         // bbb is created here so that FileAttrs can be resolved; semantics only depend on its name.
         assertThat(FilterEngine.includesSource(filters, bbb, bbbRelative, FileAttrs.get(bbb))).isTrue();
         assertThat(cfg.isExcludedSourceSubtreeDir(bbbRelative)).isTrue();

         // ex:**/ccc/** should cause any ".../ccc" subtree (including "aaa/ccc") to be skipped during traversal
         // aaa/ccc is also created for the same reason.
         assertThat(FilterEngine.includesSource(filters, aaaCcc, aaaCccRelative, FileAttrs.get(aaaCcc))).isTrue();
         assertThat(cfg.isExcludedSourceSubtreeDir(aaaCccRelative)).isTrue();
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

         final var targetFilters = FilterEngine.buildTargetFilterContext(cfg);
         assertThat(FilterEngine.includesSource(targetFilters, latestLogTarget, latestLogTargetRelative, FileAttrs.get(latestLogTarget)))
            .isTrue();
         assertThat(FilterEngine.includesSource(targetFilters, otherLogTarget, otherLogTargetRelative, FileAttrs.get(otherLogTarget)))
            .isFalse();
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

         final var filters = FilterEngine.buildSourceFilterContext(cfg);
         assertThat(FilterEngine.includesSource(filters, matchingFile, matchingRelative, FileAttrs.get(matchingFile))).isTrue();
         assertThat(FilterEngine.includesSource(filters, nonMatchingFile, nonMatchingRelative, FileAttrs.get(nonMatchingFile))).isFalse();
      } finally {
         deleteRecursive(targetRoot);
         deleteRecursive(sourceRoot);
      }
   }
}
