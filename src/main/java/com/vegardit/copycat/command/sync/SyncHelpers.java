/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;

import com.vegardit.copycat.util.FileAttrs;
import com.vegardit.copycat.util.FileUtils;
import com.vegardit.copycat.util.ProgressTracker;

import net.sf.jstuff.core.io.MoreFiles;
import net.sf.jstuff.core.logging.Logger;

/**
 * Shared sync primitives for file/directory copy logic.
 *
 * <p>
 * This engine focuses on leaf-level sync operations (files, symlinks, directories).
 * Traversal, filtering, and scheduling remain in the command implementations.
 * </p>
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class SyncHelpers {

   public record Context( // CHECKSTYLE:IGNORE .*
         boolean logCreate, boolean logModify, boolean logDelete, boolean dryRun, boolean ignoreSymlinkErrors, boolean copyACL,
         boolean allowReadingOpenFiles, @Nullable SyncStats stats, @Nullable ProgressTracker progress) {
   }

   private static final Logger LOG = Logger.create();

   public static void copyFile(final Context ctx, final Path sourcePath, final BasicFileAttributes sourceAttrs, final Path targetPath)
         throws IOException {
      final long startNanos = System.nanoTime(); // CHECKSTYLE:IGNORE MoveVariableInsideIfCheck
      if (!ctx.dryRun) {
         FileUtils.copyFile(sourcePath, sourceAttrs, targetPath, ctx.copyACL, ctx.allowReadingOpenFiles, (bytesWritten,
               totalBytesWritten) -> {
            if (ctx.progress != null) {
               ctx.progress.markProgress();
            }
         });
      }
      if (ctx.stats != null) {
         ctx.stats.onFileCopied(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), sourceAttrs.size());
      }
   }

   public static void deleteFile(final Context ctx, final Path file, final FileAttrs fileAttrs, final boolean countStats)
         throws IOException {
      final long startNanos = System.nanoTime(); // CHECKSTYLE:IGNORE MoveVariableInsideIfCheck
      if (ctx.progress != null) {
         ctx.progress.markProgress();
      }
      if (!ctx.dryRun) {
         Files.delete(file);
      }
      if (countStats && ctx.stats != null) {
         ctx.stats.onFileDeleted(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), fileAttrs.size());
      }
   }

   public static void deleteDir(final Context ctx, final Path dir) throws IOException {
      final long startNanos = System.nanoTime(); // CHECKSTYLE:IGNORE MoveVariableInsideIfCheck
      final long[] deletedFiles = {0};
      final long[] deletedDirs = {0};
      final long[] deletedFileSize = {0};

      Files.walkFileTree(dir, new SimpleFileVisitor<>() {
         @Override
         public FileVisitResult postVisitDirectory(final Path subdir, final @Nullable IOException exc) throws IOException {
            if (ctx.progress != null) {
               ctx.progress.markProgress();
            }
            if (ctx.logDelete) {
               LOG.info("Deleting [@|magenta %s%s|@]...", dir.relativize(subdir), File.separator);
            }
            if (!ctx.dryRun) {
               Files.delete(subdir);
            }
            deletedDirs[0]++;
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
            if (ctx.progress != null) {
               ctx.progress.markProgress();
            }
            if (ctx.logDelete) {
               LOG.info("Deleting [@|magenta %s|@]...", dir.relativize(file));
            }
            if (!ctx.dryRun) {
               Files.delete(file);
            }
            deletedFiles[0]++;
            deletedFileSize[0] += attrs.size();
            return FileVisitResult.CONTINUE;
         }
      });

      if (ctx.stats != null) {
         ctx.stats.onDirDeleted(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), deletedDirs[0], deletedFiles[0],
            deletedFileSize[0]);
      }
   }

   public static void ensureDir(final Context ctx, final Path sourcePath, final @Nullable Path existingTargetPath,
         final Path resolvedTargetPath, final Path relativePath) throws IOException {

      final var sourceAttrs = MoreFiles.readAttributes(sourcePath);

      if (existingTargetPath != null) {
         final var targetAttrs = FileAttrs.get(existingTargetPath);
         if (!sourceAttrs.isSymbolicLink()) {
            // source is a real directory
            if (targetAttrs.isDir())
               return;

            LOG.debug("Deleting target [@|magenta %s|@] because source is directory...", relativePath);
            deleteFile(ctx, existingTargetPath, targetAttrs, true);
         } else {
            // source is a directory symlink
            if (targetAttrs.isSymlink()) {
               try {
                  final var sourceLink = Files.readSymbolicLink(sourcePath);
                  final var targetLink = Files.readSymbolicLink(existingTargetPath);
                  if (sourceLink.equals(targetLink))
                     return;
               } catch (final IOException ex) {
                  // treat as mismatch and recreate
               }
               LOG.debug("Replacing target symlink [@|magenta %s|@] because symlink target changed...", relativePath);
            } else if (targetAttrs.isDir()) {
               LOG.debug("Deleting target [@|magenta %s|@] because source is symlink and target is not...", existingTargetPath);
               deleteDir(ctx, existingTargetPath);
            } else {
               LOG.debug("Deleting target [@|magenta %s|@] because source is symlink and target is not...", existingTargetPath);
               deleteFile(ctx, existingTargetPath, targetAttrs, true);
            }
         }
      }

      final long start = System.currentTimeMillis();
      if (sourceAttrs.isSymbolicLink()) {
         if (ctx.logCreate) {
            try {
               LOG.info("NEW [@|magenta %s -> %s%s|@]...", relativePath, Files.readSymbolicLink(sourcePath), File.separator);
            } catch (final IOException ex) {
               if (!ctx.ignoreSymlinkErrors)
                  throw ex;
               LOG.info("NEW [@|magenta %s%s|@]...", relativePath, File.separator);
            }
         }
         try {
            if (!ctx.dryRun) {
               Files.copy(sourcePath, resolvedTargetPath, AbstractSyncCommand.SYMLINK_COPY_OPTIONS);
            }
            if (ctx.stats != null) {
               ctx.stats.onFileCopied(System.currentTimeMillis() - start, sourceAttrs.size());
            }
         } catch (final FileSystemException ex) {
            if (!ctx.ignoreSymlinkErrors)
               throw ex;
            LOG.error("Symlink creation failed:" + ex.getMessage(), ex);
         }
      } else {
         if (ctx.logCreate) {
            LOG.info("NEW [@|magenta %s%s|@]...", relativePath, File.separator);
         }
         if (!ctx.dryRun) {
            FileUtils.copyDirShallow(sourcePath, sourceAttrs, resolvedTargetPath, ctx.copyACL);
         }
         if (ctx.stats != null) {
            ctx.stats.onFileCopied(System.currentTimeMillis() - start, sourceAttrs.size());
         }
      }
   }

   public static void syncSymlinkLeaf(final Context ctx, final Path sourcePath, final @Nullable Path targetPath,
         final Path resolvedTargetPath, final Path relativePath, final FileAttrs sourceAttrs) throws IOException {

      final String copyCause;
      if (targetPath == null) {
         copyCause = "NEW";
      } else {
         final var targetAttrs = FileAttrs.get(targetPath);
         if (targetAttrs.isSymlink()) {
            try {
               final var sourceLink = Files.readSymbolicLink(sourcePath);
               final var targetLink = Files.readSymbolicLink(targetPath);
               if (sourceLink.equals(targetLink))
                  return;
            } catch (final IOException ex) {
               // treat as changed and recreate
            }
            LOG.debug("Replacing target symlink [@|magenta %s|@] because symlink target changed...", targetPath);
            copyCause = "UPDATE";
         } else {
            if (targetAttrs.isDir()) {
               LOG.debug("Deleting target directory [@|magenta %s|@] because source is symlink and target is not...", targetPath);
               deleteDir(ctx, targetPath);
            } else {
               LOG.debug("Deleting target [@|magenta %s|@] because source is symlink and target is not...", targetPath);
               deleteFile(ctx, targetPath, targetAttrs, true);
            }
            copyCause = "REPLACE";
         }
      }

      if ("NEW".equals(copyCause) ? ctx.logCreate : ctx.logModify) {
         try {
            LOG.info("%s [@|magenta %s -> %s|@]...", copyCause, relativePath, Files.readSymbolicLink(sourcePath));
         } catch (final IOException ex) {
            LOG.info("%s [@|magenta %s|@]...", copyCause, relativePath);
         }
      }

      final long startNanos = System.nanoTime(); // CHECKSTYLE:IGNORE MoveVariableInsideIfCheck
      if (!ctx.dryRun) {
         try {
            final Path targetActual = targetPath == null ? resolvedTargetPath : targetPath;
            Files.copy(sourcePath, targetActual, AbstractSyncCommand.SYMLINK_COPY_OPTIONS);
         } catch (final FileSystemException ex) {
            if (!ctx.ignoreSymlinkErrors)
               throw ex;
            LOG.error("Symlink creation failed:" + ex.getMessage(), ex);
            return;
         }
      }
      if (ctx.stats != null) {
         ctx.stats.onFileCopied(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), sourceAttrs.size());
      }
   }

   private SyncHelpers() {
   }
}
