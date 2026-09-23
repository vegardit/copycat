/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.asNonNull;
import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that sync uses logical target contents consistently across filtered traversal, preparation, and dry-run accounting.
 *
 * @author Vegard IT GmbH
 */
class SyncCommandTargetStateTest {

   static Stream<Arguments> conflicts() {
      return Stream.of(1, 4).flatMap(threads -> Stream.of(false, true).flatMap(deep -> Stream.of(false, true).flatMap(symlink -> Stream.of(
         false, true).map(dryRun -> Arguments.of(threads, deep, symlink, dryRun)))));
   }

   @ParameterizedTest
   @MethodSource("conflicts")
   void replacesConflictsWithoutInspectingTheirDescendants(final int threads, final boolean deep, final boolean symlink,
         final boolean dryRun, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      final Path outside = Files.createDirectory(tempDir.resolve("outside"));
      final Path relative = Path.of(deep ? "a/b/c/keep.txt" : "a/keep.txt");
      final Path sourceFile = writeFile(source.resolve(relative), "source");
      final Path outsideFile = writeFile(outside.resolve(Path.of("a").relativize(relative)), "source");
      // Matching metadata would hide an accidental comparison against the symlink's referent.
      Files.setLastModifiedTime(outsideFile, Files.getLastModifiedTime(sourceFile));
      final Path sentinel = Files.writeString(asNonNull(outsideFile.getParent()).resolve("extra.txt"), "outside");
      createConflict(target.resolve("a"), outside, symlink);
      final var task = task(source, target, threads, dryRun, List.of("ex:**/*.tmp"));
      task.delete = true;
      final var command = new SyncCommand();

      command.doExecute(List.of(task));

      assertThat(Files.readString(sentinel)).isEqualTo("outside");
      assertThat(Files.readString(outsideFile)).isEqualTo("source");
      assertThat(counts(command, task)).isEqualTo(new Counts(deep ? 3 : 1, 1, 0, 1));
      if (dryRun) {
         assertConflict(target.resolve("a"), outside, symlink);
      } else {
         assertThat(Files.isDirectory(target.resolve("a"), LinkOption.NOFOLLOW_LINKS)).isTrue();
         assertThat(Files.readString(target.resolve(relative))).isEqualTo("source");
         assertThat(Files.exists(target.resolve(relative).resolveSibling("extra.txt"))).isFalse();
      }
   }

   @ParameterizedTest
   @MethodSource("conflicts")
   void excludedOnlySubtreesDoNotMaterializeConflicts(final int threads, final boolean deep, final boolean symlink, final boolean dryRun,
         @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      final Path outside = Files.createDirectory(tempDir.resolve("outside"));
      writeFile(source.resolve(deep ? "a/b/c/skip.txt" : "a/skip.txt"), "excluded");
      Files.writeString(outside.resolve("extra.txt"), "outside");
      createConflict(target.resolve("a"), outside, symlink);
      final var task = task(source, target, threads, dryRun, List.of("in:**/keep.txt", "ex:**"));
      task.delete = true;
      final var command = new SyncCommand();

      command.doExecute(List.of(task));

      assertConflict(target.resolve("a"), outside, symlink);
      assertThat(Files.readString(outside.resolve("extra.txt"))).isEqualTo("outside");
      assertThat(counts(command, task)).isEqualTo(new Counts(0, 0, 0, 0));
   }

   @ParameterizedTest
   @MethodSource("conflicts")
   void explicitEmptyDirectoriesReplaceConflicts(final int threads, final boolean deep, final boolean symlink, final boolean dryRun,
         @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      final Path outside = Files.createDirectory(tempDir.resolve("outside"));
      final String relative = deep ? "a/b/c" : "a";
      Files.createDirectories(source.resolve(relative));
      Files.writeString(outside.resolve("extra.txt"), "outside");
      createConflict(target.resolve("a"), outside, symlink);
      final var task = task(source, target, threads, dryRun, List.of("in:" + relative, "ex:**"));
      final var command = new SyncCommand();

      command.doExecute(List.of(task));

      assertThat(Files.readString(outside.resolve("extra.txt"))).isEqualTo("outside");
      assertThat(counts(command, task)).isEqualTo(new Counts(deep ? 3 : 1, 0, 0, 1));
      if (dryRun) {
         assertConflict(target.resolve("a"), outside, symlink);
      } else {
         assertThat(Files.isDirectory(target.resolve(relative), LinkOption.NOFOLLOW_LINKS)).isTrue();
      }
   }

   @ParameterizedTest
   @ValueSource(ints = {1, 4})
   void simulatedDeletionMatchesRealRecreationAndCopyCounts(final int threads, @TempDir final Path tempDir) throws Exception {
      final Counts planned = deletedSubtreeRun(tempDir.resolve("dry"), threads, true);
      final Counts actual = deletedSubtreeRun(tempDir.resolve("real"), threads, false);

      assertThat(planned).isEqualTo(new Counts(3, 1, 3, 1));
      assertThat(actual).isEqualTo(planned);
   }

   @ParameterizedTest
   @CsvSource({"false, false", "false, true", "true, false", "true, true"})
   void missingRootCreationIsCountedOnce(final boolean dryRun, final boolean filtered, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = tempDir.resolve("dst");
      // An empty filtered source still needs its root; lazy preparation by included children cannot provide it.
      final var task = task(source, target, 1, dryRun, filtered ? List.of("ex:**") : List.of());
      final var command = new SyncCommand();

      command.doExecute(List.of(task));

      assertThat(counts(command, task)).isEqualTo(new Counts(1, 0, 0, 0));
      assertThat(Files.exists(target)).isEqualTo(!dryRun);
   }

   @ParameterizedTest
   @CsvSource({"false, false, 1", "false, true, 1", "true, false, 1", "true, true, 1", //
      "false, false, 4", "false, true, 4", "true, false, 4", "true, true, 4"})
   void rootPreparationFailureHonorsIgnoreErrors(final boolean ignoreErrors, final boolean filtered, final int threads,
         @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      final Path healthySource = Files.createDirectory(tempDir.resolve("healthy-src"));
      Files.writeString(healthySource.resolve("keep.txt"), "source");
      final Path healthyTarget = tempDir.resolve("healthy-dst");
      final List<String> filters = filtered ? List.of("ex:**/*.tmp") : List.of();
      final var first = task(source, target, threads, false, filters);
      first.ignoreErrors = ignoreErrors;
      final var second = task(healthySource, healthyTarget, threads, false, filters);
      final var command = new SyncCommand();

      // Both tasks have passed preflight. Disappearance now must follow the runtime error policy, not validation policy.
      Files.delete(source);
      if (ignoreErrors) {
         command.doExecute(List.of(first, second));
         assertThat(Files.readString(healthyTarget.resolve("keep.txt"))).isEqualTo("source");
      } else {
         assertThatThrownBy(() -> command.doExecute(List.of(first, second))).isInstanceOf(NoSuchFileException.class);
         assertThat(Files.exists(healthyTarget)).isFalse();
      }

      // Inspect existing statistics without adding a production accessor solely for this regression.
      assertThat(asNonNull(command.syncContext(first).stats())).extracting("errors").asInstanceOf(InstanceOfAssertFactories.COLLECTION)
         .hasSize(1);
   }

   @ParameterizedTest
   @ValueSource(ints = {1, 4})
   void safeExistingDirectoriesRetainComparisonAndDeletion(final int threads, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      final Path sourceFile = writeFile(source.resolve("a/keep.txt"), "same");
      final Path targetFile = writeFile(target.resolve("a/keep.txt"), "same");
      Files.setLastModifiedTime(targetFile, Files.getLastModifiedTime(sourceFile));
      Files.writeString(target.resolve("a/extra.txt"), "extra");
      final var task = task(source, target, threads, false, List.of("ex:**/*.tmp"));
      task.delete = true;
      final var command = new SyncCommand();

      command.doExecute(List.of(task));

      assertThat(counts(command, task)).isEqualTo(new Counts(0, 0, 0, 1));
      assertThat(Files.readString(targetFile)).isEqualTo("same");
      assertThat(Files.exists(target.resolve("a/extra.txt"))).isFalse();
   }

   @ParameterizedTest
   @ValueSource(strings = {"", "a/b/c"})
   void parentPreparationReadsDoNotGrowWithSiblingCount(final String relativeDir, @TempDir final Path tempDir) throws Exception {
      final long singleFileReads = ancestorReadsForUnchangedFiles(tempDir.resolve("single"), relativeDir, 1);
      final long manyFileReads = ancestorReadsForUnchangedFiles(tempDir.resolve("many"), relativeDir, 32);

      // Compare equivalent directory trees, not elapsed time or an implementation-specific absolute read count.
      assertThat(manyFileReads).isEqualTo(singleFileReads);
   }

   private static long ancestorReadsForUnchangedFiles(final Path base, final String relativeDir, final int fileCount) throws Exception {
      final Path source = Files.createDirectories(base.resolve("src"));
      final Path target = Files.createDirectories(base.resolve("dst"));
      final Path sourceDir = Files.createDirectories(source.resolve(relativeDir));
      final Path targetDir = Files.createDirectories(target.resolve(relativeDir));
      for (int index = 0; index < fileCount; index++) {
         final Path sourceFile = Files.writeString(sourceDir.resolve("file" + index + ".txt"), "same");
         final Path targetFile = Files.writeString(targetDir.resolve(sourceFile.getFileName()), "same");
         Files.setLastModifiedTime(targetFile, Files.getLastModifiedTime(sourceFile));
      }
      // One worker isolates sibling reuse from scheduling-dependent preparation races, covered separately.
      final var task = task(source, target, 1, false, List.of("ex:**/*.tmp"));
      final var reads = new LongAdder();
      final var command = new SyncCommand() {
         @Override
         @Nullable
         BasicFileAttributes readTargetDirAttributes(final Path path) throws IOException {
            reads.increment();
            return super.readTargetDirAttributes(path);
         }
      };

      command.doExecute(List.of(task));

      assertThat(counts(command, task)).isEqualTo(new Counts(0, 0, 0, 0));
      assertThat(count(asNonNull(command.syncContext(task).stats()), "filesScanned")).isEqualTo(fileCount);
      return reads.sum();
   }

   @Test
   void simulatedTargetStateIsResetBetweenTasks(@TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      final Path sourceFile = writeFile(source.resolve("a/keep.txt"), "same");
      final Path targetFile = writeFile(target.resolve("a/keep.txt"), "same");
      Files.setLastModifiedTime(targetFile, Files.getLastModifiedTime(sourceFile));
      final var first = task(source, target, 4, true, List.of("in:a/keep.txt", "ex:**"));
      first.delete = true;
      first.deleteExcluded = true;
      final var second = task(source, target, 4, true, asNonNull(first.fileFilters));
      final var command = new SyncCommand();

      command.doExecute(List.of(first, second));

      // The second task sees the untouched filesystem, not the first task's simulated removal.
      assertThat(counts(command, first)).isEqualTo(new Counts(1, 1, 1, 1));
      assertThat(Files.readString(targetFile)).isEqualTo("same");
   }

   @Test
   void ignoredChildInspectionFailureDoesNotPreventSiblingJobs(@TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      for (final String name : List.of("a", "b")) {
         writeFile(source.resolve(name).resolve("keep.txt"), name);
         Files.createDirectory(target.resolve(name));
      }
      final var task = task(source, target, 1, false, List.of("ex:**/*.tmp"));
      task.ignoreErrors = true;
      final var failFirstChild = new AtomicBoolean(true);
      final var command = new SyncCommand() {
         @Override
         @Nullable
         BasicFileAttributes readTargetDirAttributes(final Path path) throws IOException {
            // Fail whichever child is inspected first, without relying on directory iteration order.
            // Match the resolved root because temporary-directory paths may contain aliases.
            if (task.targetRootAbsolute.equals(path.getParent()) && failFirstChild.compareAndSet(true, false))
               throw new AccessDeniedException(path.toString());
            return super.readTargetDirAttributes(path);
         }
      };

      command.doExecute(List.of(task));

      assertThat(failFirstChild.get()).isFalse();
      assertThat(counts(command, task)).isEqualTo(new Counts(0, 1, 0, 0));
      assertThat(Files.exists(target.resolve("a/keep.txt")) || Files.exists(target.resolve("b/keep.txt"))).isTrue();
   }

   @Test
   void preparedDirectoryReplacedBySymlinkMustBePreparedAgain(@TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      final Path outside = Files.createDirectory(tempDir.resolve("outside"));
      final Path sourceDir = Files.createDirectory(source.resolve("a"));
      Files.writeString(outside.resolve("extra.txt"), "outside");
      final var task = task(source, target, 1, false, List.of("in:a", "ex:**"));
      final var command = new SyncCommand();
      final var ctx = command.syncContext(task);
      command.ensureTargetDirPrepared(task, ctx, sourceDir, Path.of("a"));
      Files.delete(target.resolve("a"));
      createSymlink(target.resolve("a"), outside);

      command.ensureTargetDirPrepared(task, ctx, sourceDir, Path.of("a"));

      assertThat(Files.isDirectory(target.resolve("a"), LinkOption.NOFOLLOW_LINKS)).isTrue();
      assertThat(Files.readString(outside.resolve("extra.txt"))).isEqualTo("outside");
   }

   private static Counts deletedSubtreeRun(final Path base, final int threads, final boolean dryRun) throws Exception {
      final Path source = Files.createDirectories(base.resolve("src"));
      final Path target = Files.createDirectories(base.resolve("dst"));
      final Path sourceFile = writeFile(source.resolve("a/b/c/keep.txt"), "same");
      final Path targetFile = writeFile(target.resolve("a/b/c/keep.txt"), "same");
      final FileTime timestamp = Files.getLastModifiedTime(sourceFile);
      Files.setLastModifiedTime(targetFile, timestamp);
      final var task = task(source, target, threads, dryRun, List.of("in:a/b/c/keep.txt", "ex:**"));
      task.delete = true;
      task.deleteExcluded = true;
      final var command = new SyncCommand();

      command.doExecute(List.of(task));

      assertThat(Files.readString(targetFile)).isEqualTo("same");
      assertThat(Files.getLastModifiedTime(targetFile)).isEqualTo(timestamp);
      return counts(command, task);
   }

   private static SyncCommandConfig task(final Path source, final Path target, final int threads, final boolean dryRun,
         final List<String> filters) {
      final var task = new SyncCommandConfig();
      task.source = source;
      task.target = target;
      task.threads = threads;
      task.dryRun = dryRun;
      task.fileFilters = filters;
      task.applyDefaults();
      task.compute();
      return task;
   }

   private static Path writeFile(final Path file, final String contents) throws IOException {
      Files.createDirectories(asNonNull(file.getParent()));
      return Files.writeString(file, contents);
   }

   private static void createConflict(final Path target, final Path outside, final boolean symlink) throws IOException {
      if (symlink) {
         createSymlink(target, outside);
      } else {
         Files.writeString(target, "conflict");
      }
   }

   private static void assertConflict(final Path target, final Path outside, final boolean symlink) throws IOException {
      if (symlink) {
         assertThat(Files.readSymbolicLink(target)).isEqualTo(outside);
      } else {
         assertThat(Files.readString(target)).isEqualTo("conflict");
      }
   }

   private static void createSymlink(final Path link, final Path target) {
      try {
         Files.createSymbolicLink(link, target);
      } catch (final UnsupportedOperationException | IOException ex) {
         Assumptions.assumeTrue(false, "Symlinks are not available: " + ex.getMessage());
      }
   }

   private record Counts(long dirsCreated, long filesCopied, long dirsDeleted, long filesDeleted) {
   }

   private static Counts counts(final SyncCommand command, final SyncCommandConfig task) throws ReflectiveOperationException {
      final var stats = asNonNull(command.syncContext(task).stats());
      return new Counts(count(stats, "dirsCreated"), count(stats, "filesCopied"), count(stats, "dirsDeleted"), count(stats,
         "filesDeleted"));
   }

   private static long count(final SyncStats stats, final String name) throws ReflectiveOperationException {
      // Read the existing counters without adding production API solely for test assertions.
      final var field = SyncStats.class.getDeclaredField(name);
      field.setAccessible(true);
      return ((LongAdder) asNonNull(field.get(stats))).sum();
   }
}
