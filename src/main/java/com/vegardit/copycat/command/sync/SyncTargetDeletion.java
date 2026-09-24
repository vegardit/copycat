/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;

import com.vegardit.copycat.util.FileAttrs;

import net.sf.jstuff.core.logging.Logger;

/**
 * Prunes extraneous sync target trees while preserving excluded entries and the ancestors required to contain them.
 * Unconditional deletion for watch and type replacement remains in {@link SyncHelpers}.
 *
 * @author OpenAI Codex
 */
final class SyncTargetDeletion extends SimpleFileVisitor<Path> {
   /**
    * Records logical retention, since dry-run leaves even eligible children physically present.
    */
   private static final class DirectoryState {
      private boolean retained;
   }

   private static final Logger LOG = Logger.create();

   /**
    * @return whether the selected root was actually removed, or would be removed in dry-run
    */
   static boolean deleteDirectory(final SyncHelpers.Context ctx, final Path targetRoot, final Path directory,
         final FilterEngine.FilterContext filters) throws IOException {
      final var visitor = new SyncTargetDeletion(ctx, targetRoot, filters);
      // The default walk never follows symlinks: even directory links must be filtered and deleted as leaves.
      Files.walkFileTree(directory, visitor);
      return visitor.rootRemoved;
   }

   private final SyncHelpers.Context ctx;
   private final Path targetRoot;
   private final FilterEngine.FilterContext filters;
   private final ArrayDeque<DirectoryState> directories = new ArrayDeque<>();
   private boolean rootRemoved;

   private SyncTargetDeletion(final SyncHelpers.Context ctx, final Path targetRoot, final FilterEngine.FilterContext filters) {
      this.ctx = ctx;
      this.targetRoot = targetRoot;
      this.filters = filters;
   }

   private boolean isIncluded(final Path path, final FileAttrs attrs) throws IOException {
      // Filters are rooted at the task target, not the extraneous subtree currently being pruned.
      return FilterEngine.includesSource(filters, path, targetRoot.relativize(path), attrs);
   }

   private void markProgress() {
      // Excluded entries still advance traversal and must keep the command's stall tracking alive.
      final var progress = ctx.progress();
      if (progress != null) {
         progress.markProgress();
      }
   }

   private void retainParent() {
      // Propagate protection one level at a time so every required ancestor survives without retaining eligible siblings.
      final var parent = directories.peek();
      if (parent != null) {
         parent.retained = true;
      }
   }

   @Override
   public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
      markProgress();
      if (!isIncluded(dir, FileAttrs.get(dir, attrs))) {
         // A protected directory owns its whole subtree; a child include rule cannot reopen it.
         retainParent();
         return FileVisitResult.SKIP_SUBTREE;
      }
      directories.push(new DirectoryState());
      return FileVisitResult.CONTINUE;
   }

   @Override
   public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
      markProgress();
      final var fileAttrs = FileAttrs.get(file, attrs);
      if (!isIncluded(file, fileAttrs)) {
         retainParent();
         return FileVisitResult.CONTINUE;
      }
      // Publish each completed operation immediately, so a later scan failure cannot discard its deletion count.
      SyncHelpers.deleteFile(ctx, file, fileAttrs, true);
      if (ctx.logDelete()) {
         LOG.info("DELETE [@|magenta %s|@]...", targetRoot.relativize(file));
      }
      if (directories.isEmpty()) {
         // The selected root may have become a leaf between the caller's attribute lookup and this walk.
         rootRemoved = true;
      }
      return FileVisitResult.CONTINUE;
   }

   @Override
   public FileVisitResult postVisitDirectory(final Path dir, final @Nullable IOException exc) throws IOException {
      // Unread contents invalidate removal, including dry-run; never mask the scan failure with deletion or retention.
      if (exc != null)
         throw exc;
      markProgress();
      if (directories.pop().retained) {
         retainParent();
         return FileVisitResult.CONTINUE;
      }

      final long startNanos = System.nanoTime(); // CHECKSTYLE:IGNORE MoveVariableInsideIfCheck
      if (!ctx.dryRun()) {
         // Concurrently added contents must surface as a deletion failure, not as successful pruning.
         Files.delete(dir);
      }
      final var stats = ctx.stats();
      if (stats != null) {
         // Account for this directory now; later failures must preserve completed child operations.
         stats.onDirDeleted(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), 1, 0, 0);
      }
      if (ctx.logDelete()) {
         LOG.info("DELETE [@|magenta %s%s|@]...", targetRoot.relativize(dir), File.separator);
      }
      if (directories.isEmpty()) {
         rootRemoved = true;
      }
      return FileVisitResult.CONTINUE;
   }
}
