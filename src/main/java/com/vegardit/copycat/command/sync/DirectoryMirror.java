/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static com.vegardit.copycat.util.Booleans.isTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jdt.annotation.Nullable;

import com.vegardit.copycat.util.FileAttrs;
import com.vegardit.copycat.util.FileUtils;

import net.sf.jstuff.core.io.MoreFiles;
import net.sf.jstuff.core.logging.Logger;

/**
 * Shared helper for shallow directory mirroring between source and target.
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class DirectoryMirror {

   @FunctionalInterface
   public interface FileDeleter {
      void deleteFile(Path file, FileAttrs fileAttrs, boolean countStats) throws IOException;
   }

   @FunctionalInterface
   public interface DirDeleter {
      void deleteDir(Path dir) throws IOException;
   }

   private DirectoryMirror() {
      // no instances
   }

   /**
    * Ensures that the target path represents the same kind of entry (directory or directory symlink) as the source
    * and, when needed, creates or replaces it by copying shallow directory metadata from the source.
    * <p>
    * This method centralizes the reconciliation logic used by both {@code sync} and {@code watch} commands:
    * <ul>
    * <li>Existing regular files at the target path are deleted via {@code fileDeleter}.</li>
    * <li>Existing directories/symlinks are kept when their kind matches the source; otherwise they are deleted via
    * {@code fileDeleter} or {@code dirDeleter}.</li>
    * <li>When creation or replacement is required, the source directory or symlink is shallow-copied to the target
    * path, respecting {@code dryRun}, {@code copyACL}, and {@code ignoreSymlinkErrors} flags.</li>
    * <li>Logging of {@code NEW [...]} messages and optional statistics updates are performed inside this helper,
    * controlled by the {@code logCreate} flag and {@link SyncStats} instance passed in.</li>
    * </ul>
    *
    * @param cfg
    *           config providing at least {@code copyACL}
    * @param dryRun
    *           when {@code true}, no filesystem modifications are performed
    * @param sourcePath
    *           existing source directory or directory symlink
    * @param existingTargetPath
    *           {@code null} if the target path does not exist yet; otherwise the existing target entry
    * @param resolvedTargetPath
    *           target path to create/update (never {@code null})
    * @param relativePath
    *           path relative to the sync root used for logging
    * @param logCreate
    *           whether a {@code NEW [...]} log line should be emitted when the directory/symlink is created or replaced
    * @param log
    *           logger used for debug and info messages
    * @param stats
    *           optional {@link SyncStats} instance for counting copied directories; may be {@code null}
    * @param fileDeleter
    *           callback for deleting regular files or symlinks
    * @param dirDeleter
    *           callback for deleting directory trees
    * @param ignoreSymlinkErrors
    *           when {@code true}, symlink copy errors are logged and ignored; otherwise they are rethrown
    */
   public static void ensureDir(final AbstractSyncCommandConfig<?> cfg, final boolean dryRun, final Path sourcePath, // CHECKSTYLE:IGNORE .*
         final @Nullable Path existingTargetPath, final Path resolvedTargetPath, final Path relativePath, final boolean logCreate,
         final Logger log, final @Nullable SyncStats stats, final FileDeleter fileDeleter, final DirDeleter dirDeleter,
         final boolean ignoreSymlinkErrors) throws IOException {

      final var sourceAttrs = MoreFiles.readAttributes(sourcePath);

      if (existingTargetPath != null) {
         final var targetAttrs = FileAttrs.get(existingTargetPath);
         if (!sourceAttrs.isSymbolicLink()) {
            // source is a real directory
            if (targetAttrs.isDir())
               return;

            log.debug("Deleting target [@|magenta %s|@] because source is directory...", relativePath);
            fileDeleter.deleteFile(existingTargetPath, targetAttrs, true);
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
               log.debug("Replacing target symlink [@|magenta %s|@] because symlink target changed...", relativePath);
            } else if (targetAttrs.isDir()) {
               log.debug("Deleting target [@|magenta %s|@] because source is symlink and target is not...", existingTargetPath);
               dirDeleter.deleteDir(existingTargetPath);
            } else {
               log.debug("Deleting target [@|magenta %s|@] because source is symlink and target is not...", existingTargetPath);
               fileDeleter.deleteFile(existingTargetPath, targetAttrs, true);
            }
         }
      }

      final long start = System.currentTimeMillis();
      if (sourceAttrs.isSymbolicLink()) {
         if (logCreate) {
            try {
               log.info("NEW [@|magenta %s -> %s%s|@]...", relativePath, Files.readSymbolicLink(sourcePath), File.separator);
            } catch (final IOException ex) {
               if (!ignoreSymlinkErrors)
                  throw ex;
               log.info("NEW [@|magenta %s%s|@]...", relativePath, File.separator);
            }
         }
         try {
            if (!dryRun) {
               Files.copy(sourcePath, resolvedTargetPath, AbstractSyncCommand.SYMLINK_COPY_OPTIONS);
            }
            if (stats != null) {
               stats.onFileCopied(System.currentTimeMillis() - start, sourceAttrs.size());
            }
         } catch (final FileSystemException ex) {
            if (!ignoreSymlinkErrors)
               throw ex;
            log.error("Symlink creation failed:" + ex.getMessage(), ex);
         }
      } else {
         if (logCreate) {
            log.info("NEW [@|magenta %s%s|@]...", relativePath, File.separator);
         }
         if (!dryRun) {
            FileUtils.copyDirShallow(sourcePath, sourceAttrs, resolvedTargetPath, isTrue(cfg.copyACL));
         }
         if (stats != null) {
            stats.onFileCopied(System.currentTimeMillis() - start, sourceAttrs.size());
         }
      }
   }
}
