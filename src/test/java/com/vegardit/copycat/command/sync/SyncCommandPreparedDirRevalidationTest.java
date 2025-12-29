/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reproducer for missing filesystem revalidation in SyncCommand's prepared-directory fast path.
 *
 * Expected behavior: if a directory is marked as prepared, but the target directory is removed externally,
 * SyncCommand should recreate it when asked to prepare it again.
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class SyncCommandPreparedDirRevalidationTest {

   @Test
   void preparedDirCacheIsRevalidatedIfTargetDirDisappears(@TempDir final Path tempDir) throws Exception {
      final Path sourceRoot = Files.createDirectory(tempDir.resolve("src"));
      final Path targetRoot = Files.createDirectory(tempDir.resolve("dst"));

      final Path fooRel = Path.of("foo");
      final Path fooArel = Path.of("foo", "a");

      Files.createDirectories(sourceRoot.resolve(fooArel));

      final var task = new SyncCommandConfig();
      task.source = sourceRoot;
      task.target = targetRoot;
      task.dryRun = false;
      task.fileFilters = List.of("in:foo/a/**", "ex:**");
      task.applyDefaults();
      task.compute();

      final var cmd = new SyncCommand();
      final var ctx = cmd.syncContext(task);

      // prepare foo once (creates target/foo and marks it as prepared)
      cmd.ensureTargetDirPrepared(task, ctx, sourceRoot.resolve(fooRel), fooRel);
      assertThat(Files.isDirectory(targetRoot.resolve(fooRel))).isTrue();

      // simulate external modification: the prepared target directory disappears
      deleteRecursive(targetRoot.resolve(fooRel));
      assertThat(Files.exists(targetRoot.resolve(fooRel))).isFalse();

      // desired behavior: preparing foo again should recreate it so that preparing foo/a can succeed
      assertThatCode(() -> {
         cmd.ensureTargetDirPrepared(task, ctx, sourceRoot.resolve(fooRel), fooRel);
         cmd.ensureTargetDirPrepared(task, ctx, sourceRoot.resolve(fooArel), fooArel);
      }).doesNotThrowAnyException();

      assertThat(Files.isDirectory(targetRoot.resolve(fooRel))).isTrue();
      assertThat(Files.isDirectory(targetRoot.resolve(fooArel))).isTrue();
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
