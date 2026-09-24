/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.asNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that sync deletion preserves excluded descendants and reports incomplete scans in real and dry-run modes.
 *
 * @author OpenAI Codex
 */
class SyncCommandDeletionTest {
   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void excludedDescendantsPreserveOnlyTheirAncestorChain(final boolean dryRun, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      writeFile(target.resolve("archive/a/b/keep.txt"), "keep");
      writeFile(target.resolve("archive/a/b/remove.txt"), "remove");
      writeFile(target.resolve("archive/prune/child.txt"), "remove");
      writeFile(target.resolve("archive/remove.txt"), "remove");
      writeFile(target.resolve("archive/protected/inside.txt"), "keep");
      final var task = task(source, target, dryRun, List.of(
         // An included child must not reopen a directory protected as a whole by target filtering.
         "in:archive/protected/inside.txt", "ex:archive/protected", "ex:archive/a/b/keep.txt"));

      final var command = run(task, new Counts(1, 3));

      assertThat(count(asNonNull(command.syncContext(task).stats()), "filesDeletedSize")).isEqualTo(18);
      assertThat(Files.readString(target.resolve("archive/a/b/keep.txt"))).isEqualTo("keep");
      assertThat(Files.readString(target.resolve("archive/protected/inside.txt"))).isEqualTo("keep");
      if (!dryRun) {
         assertThat(Files.exists(target.resolve("archive/prune"))).isFalse();
         assertThat(Files.exists(target.resolve("archive/remove.txt"))).isFalse();
         assertThat(Files.exists(target.resolve("archive/a/b/remove.txt"))).isFalse();
      }
   }

   @ParameterizedTest
   @CsvSource({"false, 0", "true, 0", "false, 1", "true, 1", "false, 2", "true, 2"})
   void whollyEligibleTreesAreRemoved(final boolean dryRun, final int filterMode, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      writeFile(target.resolve("archive/sub/keep.txt"), "keep");
      writeFile(target.resolve("archive/remove.txt"), "remove");
      Files.createDirectory(target.resolve("archive/empty"));
      final var task = task(source, target, dryRun, switch (filterMode) {
         case 0 -> List.of();
         case 1 -> List.of("ex:**/*.protected");
         default -> List.of("ex:archive/sub/keep.txt");
      });
      task.deleteExcluded = filterMode == 2;

      try (var logs = new DeletionLogs()) {
         run(task, new Counts(3, 2));
         // Subtree-relative detail logs alone do not identify which extraneous directory was removed.
         assertThat(logs.messages).containsAnyOf("DELETE [@|magenta archive|@]...", "DELETE [@|magenta archive" + File.separator
               + "|@]...");
      }

      assertThat(Files.exists(target.resolve("archive"))).isEqualTo(dryRun);
   }

   @ParameterizedTest
   @CsvSource({"false, false", "false, true", "true, false", "true, true"})
   void firstMatchingRuleControlsDescendantDeletion(final boolean dryRun, final boolean includeFirst, @TempDir final Path tempDir)
         throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      writeFile(target.resolve("archive/selected.txt"), "selected");
      writeFile(target.resolve("archive/protected.txt"), "keep");
      final var task = task(source, target, dryRun, includeFirst ? List.of("in:archive/selected.txt", "ex:archive/*.txt")
            : List.of("ex:archive/*.txt", "in:archive/selected.txt"));

      run(task, new Counts(0, includeFirst ? 1 : 0));

      assertThat(Files.exists(target.resolve("archive/selected.txt"))).isEqualTo(dryRun || !includeFirst);
      assertThat(Files.readString(target.resolve("archive/protected.txt"))).isEqualTo("keep");
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void dateExcludedFilesProtectTheirParents(final boolean dryRun, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      final Path oldFile = writeFile(target.resolve("archive/old.txt"), "keep");
      final Path recentFile = writeFile(target.resolve("archive/recent.txt"), "remove");
      Files.setLastModifiedTime(oldFile, FileTime.from(Instant.parse("2000-01-01T00:00:00Z")));
      Files.setLastModifiedTime(recentFile, FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));
      final var task = task(source, target, dryRun, List.of());
      task.modifiedFrom = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));

      run(task, new Counts(0, 1));

      assertThat(Files.readString(oldFile)).isEqualTo("keep");
      assertThat(Files.exists(recentFile)).isEqualTo(dryRun);
   }

   @ParameterizedTest
   @CsvSource({"false, hidden", "true, hidden", "false, system", "true, system", "false, hidden-system", "true, hidden-system"})
   @SuppressWarnings("resource") // The default filesystem belongs to the JVM, not this test.
   void metadataExcludedEntriesProtectTheirParents(final boolean dryRun, final String exclusion, @TempDir final Path tempDir)
         throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      final Path protectedFile = writeFile(target.resolve("archive/.keep.txt"), "keep");
      final Path removedFile = writeFile(target.resolve("archive/remove.txt"), "remove");
      final boolean dos = target.getFileSystem().supportedFileAttributeViews().contains("dos");
      if (!"hidden".equals(exclusion)) {
         assumeTrue(dos, "System attributes require a DOS attribute view.");
         Files.setAttribute(protectedFile, "dos:system", true);
      }
      if (dos && !"system".equals(exclusion)) {
         Files.setAttribute(protectedFile, "dos:hidden", true);
      }
      final var task = task(source, target, dryRun, List.of());
      task.excludeHiddenFiles = "hidden".equals(exclusion);
      task.excludeSystemFiles = "system".equals(exclusion);
      task.excludeHiddenSystemFiles = "hidden-system".equals(exclusion);

      run(task, new Counts(0, 1));

      assertThat(Files.readString(protectedFile)).isEqualTo("keep");
      assertThat(Files.exists(removedFile)).isEqualTo(dryRun);
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void symlinksRemainLeavesAndOtherLinksCanBeProtected(final boolean dryRun, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      final Path outside = Files.createDirectory(tempDir.resolve("outside"));
      final Path sentinel = Files.writeString(outside.resolve("sentinel.txt"), "outside");
      writeFile(target.resolve("archive/remove.txt"), "remove");
      createSymlink(target.resolve("archive/remove-link"), outside);
      createSymlink(target.resolve("archive/protected-link"), outside);
      createSymlink(target.resolve("archive/broken-link"), outside.resolve("missing"));
      final var task = task(source, target, dryRun, List.of("ex:archive/protected-link"));
      task.excludeOtherLinks = true;

      run(task, new Counts(0, 2));

      assertThat(Files.readString(sentinel)).isEqualTo("outside");
      assertThat(Files.isSymbolicLink(target.resolve("archive/protected-link"))).isTrue();
      assertThat(Files.isSymbolicLink(target.resolve("archive/broken-link"))).isTrue();
      assertThat(Files.exists(target.resolve("archive/remove-link"), LinkOption.NOFOLLOW_LINKS)).isEqualTo(dryRun);
   }

   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void sharedDeletionPropagatesTheOriginalIterationFailure(final boolean dryRun, @TempDir final Path tempDir) throws Exception {
      final Path directory = Files.createDirectory(tempDir.resolve("archive")).toRealPath();
      writeFile(directory.resolve("unread.txt"), "keep");
      final var failure = new IOException("injected directory iteration failure");
      final var stats = new SyncStats();
      final var ctx = new SyncHelpers.Context(false, false, true, dryRun, false, false, false, stats, null);
      final var before = snapshot(directory);

      try (var fileSystem = new FailingDirectoryFileSystem(directory, failure, 0);
           var logs = new DeletionLogs()) {
         assertThatThrownBy(() -> SyncHelpers.deleteDir(ctx, fileSystem.wrap(directory))).isSameAs(failure);
         assertThat(fileSystem.failureCount()).isEqualTo(1);
         assertThat(logs.messages).isEmpty();
      }

      assertThat(counts(stats)).isEqualTo(new Counts(0, 0));
      assertThat(snapshot(directory)).isEqualTo(before);
   }

   @ParameterizedTest
   @CsvSource({"false, false, false", "false, false, true", "false, true, false", "false, true, true", "true, false, false",
      "true, false, true", "true, true, false", "true, true, true"})
   void commandReportsIterationFailureWithoutMarkingTheSubtreeAbsent(final boolean dryRun, final boolean filtered,
         final boolean ignoreErrors, @TempDir final Path tempDir) throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      writeFile(target.resolve("archive/unread.txt"), "keep");
      final var task = task(source, target, dryRun, filtered ? List.of("ex:**/*.protected") : List.of());
      task.ignoreErrors = ignoreErrors;
      final var failure = new IOException("injected directory iteration failure");
      final var before = snapshot(target);
      final var command = new SyncCommand();

      try (var fileSystem = new FailingDirectoryFileSystem(task.targetRootAbsolute.resolve("archive"), failure, 0);
           var logs = new DeletionLogs()) {
         // Inject after normal preflight so the failure belongs to deletion, not root validation.
         task.sourceRootAbsolute = fileSystem.wrap(task.sourceRootAbsolute);
         task.targetRootAbsolute = fileSystem.wrap(task.targetRootAbsolute);
         if (ignoreErrors) {
            assertThatCode(() -> runWorker(command, task)).doesNotThrowAnyException();
         } else {
            assertThatThrownBy(() -> runWorker(command, task)).isSameAs(failure);
         }
         assertThat(fileSystem.failureCount()).isEqualTo(1);
         assertThat(logs.messages).isEmpty();
      }

      final var stats = asNonNull(command.syncContext(task).stats());
      assertThat(counts(stats)).isEqualTo(new Counts(0, 0));
      assertThat(stats).extracting("errors").asInstanceOf(InstanceOfAssertFactories.COLLECTION).containsExactly(
         "IOException: injected directory iteration failure");
      assertThat(command).extracting("state").isEqualTo(ignoreErrors ? SyncCommand.State.NORMAL : SyncCommand.State.ABORT_BY_EXCEPTION);
      // Inspect the existing state rather than introducing production accessors just for the regression.
      assertThat(command).extracting("targetDirStates").asInstanceOf(InstanceOfAssertFactories.MAP).allSatisfy((path, state) -> assertThat(
         state.toString()).isNotEqualTo("EMPTY"));
      assertThat(snapshot(target)).isEqualTo(before);
   }

   @ParameterizedTest
   @CsvSource({"false, false", "false, true", "true, false", "true, true"})
   void deletionCountsCompletedChildrenBeforeAScanFailure(final boolean dryRun, final boolean filtered, @TempDir final Path tempDir)
         throws Exception {
      final Path source = Files.createDirectory(tempDir.resolve("src"));
      final Path target = Files.createDirectory(tempDir.resolve("dst"));
      final Path child = writeFile(target.resolve("archive/child.txt"), "remove");
      // A nonmatching rule selects filtered deletion without protecting the child; both paths must report the same completed work.
      final var task = task(source, target, dryRun, filtered ? List.of("ex:**/*.protected") : List.of());
      final var command = new SyncCommand();
      final var failure = new IOException("injected directory iteration failure");
      // Capture before the attempted deletion; moving this into the assertion would hide dry-run mutations.
      final var before = snapshot(target); // CHECKSTYLE:IGNORE MoveVariableInsideIfCheck

      // A single child makes the point of failure deterministic without depending on directory iteration order.
      try (var fileSystem = new FailingDirectoryFileSystem(task.targetRootAbsolute.resolve("archive"), failure, 1);
           var logs = new DeletionLogs()) {
         task.sourceRootAbsolute = fileSystem.wrap(task.sourceRootAbsolute);
         task.targetRootAbsolute = fileSystem.wrap(task.targetRootAbsolute);
         assertThatThrownBy(() -> runWorker(command, task)).isSameAs(failure);
         assertThat(fileSystem.failureCount()).isEqualTo(1);
         assertThat(logs.messages).hasSize(1).allSatisfy(message -> assertThat(message).contains("child.txt"));
      }

      final var stats = asNonNull(command.syncContext(task).stats());
      assertThat(counts(stats)).isEqualTo(new Counts(0, 1));
      assertThat(count(stats, "filesDeletedSize")).isEqualTo(6);
      assertThat(Files.isDirectory(target.resolve("archive"))).isTrue();
      assertThat(Files.exists(child)).isEqualTo(dryRun);
      if (dryRun) {
         assertThat(snapshot(target)).isEqualTo(before);
      }
   }

   @SuppressWarnings("resource") // The caller owns and closes the injected filesystem.
   private static void runWorker(final SyncCommand command, final SyncCommandConfig task) throws IOException {
      final var jobs = new SyncCommand.DirJobQueue(1, new ArrayDeque<>(List.of(new SyncCommand.DirJob(task.sourceRootAbsolute,
         task.sourceRootAbsolute.getFileSystem().getPath(".")))));
      command.syncWorker(task, task.toSourceFilterContext(), task.toTargetFilterContext(), jobs, command.syncContext(task));
   }

   private static SyncCommand run(final SyncCommandConfig task, final Counts expected) throws Exception {
      // The pre-operation snapshot is required even though only dry-run assertions consume it.
      final var before = snapshot(task.targetRootAbsolute); // CHECKSTYLE:IGNORE MoveVariableInsideIfCheck
      final var command = new SyncCommand();
      command.doExecute(List.of(task));
      final var stats = asNonNull(command.syncContext(task).stats());
      assertThat(counts(stats)).isEqualTo(expected);
      assertThat(stats).extracting("errors").asInstanceOf(InstanceOfAssertFactories.COLLECTION).isEmpty();
      if (Boolean.TRUE.equals(task.dryRun)) {
         assertThat(snapshot(task.targetRootAbsolute)).isEqualTo(before);
      }
      return command;
   }

   private static SyncCommandConfig task(final Path source, final Path target, final boolean dryRun, final List<String> filters) {
      final var task = new SyncCommandConfig();
      task.source = source;
      task.target = target;
      task.threads = 1;
      task.delete = true;
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

   private static void createSymlink(final Path link, final Path target) {
      try {
         Files.createSymbolicLink(link, target);
      } catch (final UnsupportedOperationException | IOException ex) {
         assumeTrue(false, "Symlinks are not available: " + ex.getMessage());
      }
   }

   private static Map<String, String> snapshot(final Path root) throws IOException {
      final var entries = new TreeMap<String, String>();
      try (var paths = Files.walk(root)) {
         for (final Path path : paths.toList()) {
            final var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            final String contents = attrs.isSymbolicLink() ? "link:" + Files.readSymbolicLink(path)
                  : attrs.isDirectory() ? "directory" : "file:" + Files.readString(path);
            entries.put(root.relativize(path).toString(), contents + " modified:" + attrs.lastModifiedTime());
         }
      }
      return entries;
   }

   private record Counts(long directories, long files) {
   }

   private static Counts counts(final SyncStats stats) throws ReflectiveOperationException {
      return new Counts(count(stats, "dirsDeleted"), count(stats, "filesDeleted"));
   }

   private static long count(final SyncStats stats, final String name) throws ReflectiveOperationException {
      final var field = SyncStats.class.getDeclaredField(name);
      field.setAccessible(true);
      return ((LongAdder) asNonNull(field.get(stats))).sum();
   }

   /**
    * Captures deletion events at the logging boundary, including premature messages from the command itself.
    */
   private static final class DeletionLogs extends Handler implements AutoCloseable {
      final List<String> messages = new CopyOnWriteArrayList<>();

      DeletionLogs() {
         Logger.getLogger("").addHandler(this);
      }

      @Override
      public void publish(final @Nullable LogRecord record) {
         if (record == null)
            return;
         final String message = new SimpleFormatter().formatMessage(record);
         if (message.startsWith("DELETE [") || message.startsWith("Deleting [")) {
            messages.add(message);
         }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
         Logger.getLogger("").removeHandler(this);
      }
   }
}
