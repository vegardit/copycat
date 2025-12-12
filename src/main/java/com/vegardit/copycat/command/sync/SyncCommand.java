/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static com.vegardit.copycat.util.Booleans.*;
import static net.sf.jstuff.core.validation.NullAnalysisHelper.asNonNull;

import java.awt.TrayIcon.MessageType;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.mutable.MutableLong;
import org.eclipse.jdt.annotation.Nullable;

import com.vegardit.copycat.util.DesktopNotifications;
import com.vegardit.copycat.util.FileAttrs;
import com.vegardit.copycat.util.FileUtils;
import com.vegardit.copycat.util.JdkLoggingUtils;
import com.vegardit.copycat.util.YamlUtils;

import net.sf.jstuff.core.collection.Sets;
import net.sf.jstuff.core.concurrent.Threads;
import net.sf.jstuff.core.io.MoreFiles;
import net.sf.jstuff.core.io.Size;
import net.sf.jstuff.core.logging.Logger;
import net.sf.jstuff.core.ref.MutableRef;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
@CommandLine.Command(name = "sync", //
   description = "Performs one-way recursive directory synchronization copying new files/directories." //
)
public class SyncCommand extends AbstractSyncCommand<SyncCommandConfig> {

   enum LogEvent {
      CREATE,
      MODIFY,
      DELETE,
      SCAN
   }

   enum State {
      NORMAL,
      ABORT_BY_EXCEPTION,
      ABORT_BY_SIGNAL
   }

   private static final class DirJob {
      final Path sourceDir;
      final Path relativeDir;

      DirJob(final Path sourceDir, final Path relativeDir) {
         this.sourceDir = sourceDir;
         this.relativeDir = relativeDir;
      }
   }

   private static final Logger LOG = Logger.create();

   private final Set<LogEvent> loggableEvents = Sets.newHashSet(LogEvent.values());
   private final Set<Path> preparedTargetDirs = ConcurrentHashMap.newKeySet();
   private final Set<Path> preparedParentDirsRelative = ConcurrentHashMap.newKeySet();

   private final SyncStats stats = new SyncStats();
   private volatile State state = State.NORMAL;

   public SyncCommand() {
      super(SyncCommandConfig::new);
   }

   private void delDir(final SyncCommandConfig task, final Path dir) throws IOException {
      final var start = System.currentTimeMillis();
      final var filesDeleted = new MutableLong();
      final var filesDeletedSize = new MutableLong();

      Files.walkFileTree(dir, new SimpleFileVisitor<>() {
         @Override
         public FileVisitResult postVisitDirectory(final Path subdir, final @Nullable IOException exc) throws IOException {
            LOG.info("Deleting [@|magenta %s%s|@]...", dir.relativize(subdir), File.separator);
            if (not(task.dryRun)) {
               Files.delete(subdir);
            }
            filesDeleted.increment();
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
            LOG.info("Deleting [@|magenta %s|@]...", dir.relativize(file));
            if (not(task.dryRun)) {
               Files.delete(file);
            }
            filesDeleted.increment();
            filesDeletedSize.add(attrs.size());
            return FileVisitResult.CONTINUE;
         }
      });
      stats.onDirDeleted(System.currentTimeMillis() - start, filesDeleted.longValue(), filesDeletedSize.longValue());
   }

   private void delFile(final SyncCommandConfig task, final Path file, final FileAttrs fileAttrs, final boolean count) throws IOException {
      final var start = System.currentTimeMillis(); // CHECKSTYLE:IGNORE MoveVariableInsideIfCheck
      if (not(task.dryRun)) {
         Files.delete(file);
      }
      if (count) {
         stats.onFileDeleted(System.currentTimeMillis() - start, fileAttrs.size());
      }
   }

   @Override
   protected void doExecute(final List<SyncCommandConfig> tasks) throws Exception {
      DesktopNotifications.setTrayIconToolTip("copycat is syncing...");

      stats.start();

      try {
         for (final SyncCommandConfig task : tasks) {
            final var sourceFilterCtx = task.toSourceFilterContext();
            final var targetFilterCtx = task.toTargetFilterContext();
            preparedTargetDirs.clear();
            preparedParentDirsRelative.clear();

            JdkLoggingUtils.withRootLogLevel(Level.INFO, () -> {
               LOG.info("Executing sync task with effective config:\n%s", YamlUtils.toYamlString(task));
            });

            int task_threads = asNonNull(task.threads);
            final Queue<DirJob> dirJobs;
            if (task_threads < 0 || task_threads == 1) {
               task_threads = task.threads = 1;
               dirJobs = new ArrayDeque<>();
            } else {
               dirJobs = new ConcurrentLinkedDeque<>();
            }
            LOG.info("Working hard using %s thread(s)%s...", task_threads, isTrue(task.dryRun) ? " (DRY RUN)" : "");

            if (!Files.exists(task.targetRootAbsolute, NOFOLLOW_LINKS)) {
               if (loggableEvents.contains(LogEvent.CREATE)) {
                  LOG.info("NEW [@|magenta %s%s|@]...", task.targetRootAbsolute, File.separator);
               }
               FileUtils.copyDirShallow(task.sourceRootAbsolute, task.targetRootAbsolute, isTrue(task.copyACL));
            }

            dirJobs.add(new DirJob(task.sourceRootAbsolute, Paths.get(".")));

            /*
             * start syncing
             */
            DesktopNotifications.showTransient(MessageType.INFO, "Syncing started...", //
               "FROM: " + task.sourceRootAbsolute + "\nTO: " + task.targetRootAbsolute);
            if (task_threads == 1) {
               syncWorker(task, sourceFilterCtx, targetFilterCtx, dirJobs);
            } else {
               final var threadPool = Executors.newFixedThreadPool(task_threads, //
                  BasicThreadFactory.builder().namingPattern("sync-%d").build() //
               );
               // CHECKSTYLE:IGNORE .* FOR NEXT LINE
               final var exRef = MutableRef.create();
               for (var i = 0; i < task_threads; i++) {
                  threadPool.submit(() -> {
                     try {
                        syncWorker(task, sourceFilterCtx, targetFilterCtx, dirJobs);
                     } catch (final Exception e) {
                        exRef.set(e);
                     }
                  });
               }
               threadPool.shutdown();
               threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
               if (exRef.get() instanceof final Exception ex) {
                  DesktopNotifications.showSticky(MessageType.ERROR, "Syncing failed.", //
                     "ERROR: " + ex.getMessage() + "\nFROM: " + task.sourceRootAbsolute + "\nTO: " + task.targetRootAbsolute);
                  throw ex;
               }

               DesktopNotifications.showSticky(MessageType.INFO, "Syncing done.", //
                  "FROM: " + task.sourceRootAbsolute + "\nTO: " + task.targetRootAbsolute);
            }
         }
      } finally {
         JdkLoggingUtils.withRootLogLevel(Level.INFO, stats::logStats);
      }
   }

   @Override
   protected void onSigInt() {
      state = State.ABORT_BY_SIGNAL;
      Threads.sleep(500);
      JdkLoggingUtils.withRootLogLevel(Level.INFO, stats::logStats);
   }

   @Override
   protected void onSigTerm() {
      onSigInt();
   }

   @Option(names = "--delete", description = "Delete extraneous files/directories from target.")
   private void setDelete(final boolean value) {
      cfgCLI.delete = value;
   }

   @Option(names = "--dry-run", description = "Don't perform actual synchronization.")
   private void setDryRun(final boolean dryRun) {
      cfgCLI.dryRun = dryRun;
   }

   @Option(names = "--exclude-older-files", description = "Don't override newer files in target with older files in source.")
   private void setExcludeOlderFiles(final boolean value) {
      cfgCLI.excludeOlderFiles = value;
   }

   @Option(names = "--ignore-errors", description = "Continue sync when errors occur.")
   private void setIgnoreErrors(final boolean value) {
      cfgCLI.ignoreErrors = value;
   }

   @Option(names = "--ignore-symlink-errors", description = "Continue if creation of symlinks on target fails.")
   private void setIgnoreSymlinkErrors(final boolean value) {
      cfgCLI.ignoreSymlinkErrors = value;
   }

   @Option(names = "--no-log", paramLabel = "<op>", split = ",", description = "Don't log the given sync operation. Valid values: ${COMPLETION-CANDIDATES}")
   private void setNoLog(final LogEvent[] values) {
      for (final var val : values) {
         loggableEvents.remove(val);
      }
   }

   @Option(names = "--threads", paramLabel = "<count>", description = "Number of concurrent threads. Default: 2")
   private void setThreads(final int i) {
      cfgCLI.threads = i;
   }

   private void syncWorker(final SyncCommandConfig task, final FilterEngine.FilterContext sourceFilterCtx,
         final FilterEngine.FilterContext targetFilterCtx, final Queue<DirJob> dirJobs) throws IOException {
      final var sourceChildren = new HashMap<Path, Path>(256); // Map<SourcePathRelativeToRoot, SourcePathAbsolute>
      final var targetChildren = new HashMap<Path, Path>(256); // Map<TargetPathRelativeToRoot, TargetPathAbsolute>
      final int sourceRootNameCount = task.sourceRootAbsolute.getNameCount();
      final int targetRootNameCount = task.targetRootAbsolute.getNameCount();
      final boolean targetFilterHasEffects = targetFilterCtx.modifiedFrom != null //
            || targetFilterCtx.modifiedTo != null //
            || targetFilterCtx.excludeHiddenFiles //
            || targetFilterCtx.excludeHiddenSystemFiles //
            || targetFilterCtx.excludeSystemFiles //
            || !targetFilterCtx.sourceRules.isEmpty();

      while (state == State.NORMAL) {
         final var job = dirJobs.poll();
         if (job == null) {
            LOG.debug("Worker done.");
            break;
         }

         try {
            final Path source = job.sourceDir;
            final Path sourceRelative = job.relativeDir;
            final Path target = source.equals(task.sourceRootAbsolute) //
                  ? task.targetRootAbsolute
                  : task.targetRootAbsolute.resolve(sourceRelative);

            if (loggableEvents.contains(LogEvent.SCAN)) {
               LOG.info("Scanning [@|magenta %s%s|@]...", sourceRelative, File.separator);
            }

            /*
             * read direct children of source dir
             */
            sourceChildren.clear();
            try (var ds = Files.newDirectoryStream(source)) {
               ds.forEach(child -> sourceChildren //
                  .put(child.subpath(sourceRootNameCount, child.getNameCount()), child));
            }

            /*
             * read direct children of target dir
             *
             * With lazy directory creation some source directories may not yet have a corresponding
             * directory on the target side. In that case attempting to list the target would raise
             * NoSuchFileException; we catch that below and simply treat the target as empty.
             */
            targetChildren.clear();
            if (not(task.dryRun) || Files.isDirectory(target, NOFOLLOW_LINKS)) {
               try (var ds = Files.newDirectoryStream(target)) {
                  ds.forEach(child -> targetChildren //
                     .put(child.subpath(targetRootNameCount, child.getNameCount()), child));
               } catch (final NoSuchFileException ex) {
                  // target directory does not exist; treat as empty
               }
            }

            /*
             * remove extraneous entries in target dir
             */
            if (isTrue(task.delete)) {
               for (final var it = targetChildren.entrySet().iterator(); it.hasNext();) {
                  if (state != State.NORMAL) {
                     break;
                  }
                  final var targetChildEntry = it.next();
                  final var targetChildRelative = targetChildEntry.getKey();
                  final var targetChildAbsolute = targetChildEntry.getValue();

                  final boolean existsInSource = sourceChildren.containsKey(targetChildRelative);

                  final boolean needRemoval;
                  final FileAttrs targetAttrs;
                  if (!targetFilterHasEffects) {
                     // Fast path: no target-side filters/flags or date constraints; default-include semantics.
                     // In this case only entries that do not exist in the source are removed.
                     if (existsInSource) {
                        continue;
                     }
                     needRemoval = true;
                     targetAttrs = FileAttrs.get(targetChildAbsolute);
                  } else {
                     targetAttrs = FileAttrs.get(targetChildAbsolute);
                     final boolean isIncludedTarget = FilterEngine.includesSource(targetFilterCtx, targetChildAbsolute, targetChildRelative,
                        targetAttrs);
                     final boolean isExcludedFromSync = !isIncludedTarget;

                     if (existsInSource) {
                        needRemoval = isExcludedFromSync && isTrue(task.deleteExcluded);
                     } else {
                        needRemoval = !isExcludedFromSync || isExcludedFromSync && isTrue(task.deleteExcluded);
                     }

                     if (!needRemoval) {
                        continue;
                     }
                  }

                  if (loggableEvents.contains(LogEvent.DELETE)) {
                     LOG.info("DELETE [@|magenta %s|@]...", targetChildRelative);
                  }
                  if (targetAttrs.isDir()) {
                     delDir(task, targetChildAbsolute);
                  } else {
                     delFile(task, targetChildAbsolute, targetAttrs, true);
                  }

                  it.remove();
               }
            }

            /*
             * iterate over direct children of source dir
             */
            for (final var sourceEntry : sourceChildren.entrySet()) {
               if (state != State.NORMAL) {
                  break;
               }
               final var sourceChildRelative = sourceEntry.getKey();
               final var sourceChildAbsolute = sourceEntry.getValue();
               final var targetChildAbsolute = targetChildren.get(sourceChildRelative);

               final var sourceAttrs = FileAttrs.get(sourceChildAbsolute);
               final boolean isFile = sourceAttrs.isFile() || sourceAttrs.isFileSymlink();

               /*
                * Apply FilterEngine to all entry types (files, directories, symlinks) so that hidden/system
                * flags and glob filters consistently affect traversal as well as copying. If a directory is
                * excluded here, we neither traverse into it nor materialize it on the target.
                */
               if (!FilterEngine.includesSource(sourceFilterCtx, sourceChildAbsolute, sourceChildRelative, sourceAttrs)) {
                  continue;
               }

               if (isFile) {
                  prepareParentDirsForIncludedFile(task, sourceChildRelative);
                  syncFile(task, sourceChildAbsolute, targetChildAbsolute, sourceChildRelative);
                  stats.onFileScanned();
               } else if (sourceAttrs.isDirSymlink()) {
                  // handle directory symlink entries immediately (do not descend into them)
                  // and ensure their parent directory chain exists on the target, just like for files.
                  prepareParentDirsForIncludedFile(task, sourceChildRelative);
                  syncDirShallow(task, sourceChildAbsolute, targetChildAbsolute, sourceChildRelative);
                  stats.onFileScanned();
               } else {
                  final Integer maxDepth = task.maxDepth;
                  final int childDepth = sourceChildRelative.getNameCount();

                  // respect optional max-depth: skip directories beyond maxDepth entirely
                  if (maxDepth != null && childDepth > maxDepth && !sourceAttrs.isSymlink()) {
                     if (LOG.isTraceEnabled()) {
                        LOG.trace("Ignoring directory outside max-depth [%s]: %s", maxDepth, sourceChildRelative);
                     }
                     continue;
                  }

                  final boolean pruneSubtree = task.isExcludedSourceSubtreeDir(sourceChildRelative);
                  // respect optional max-depth: only descend if child depth <= maxDepth
                  if (!pruneSubtree && (maxDepth == null || childDepth <= maxDepth)) {
                     dirJobs.add(new DirJob(sourceChildAbsolute, sourceChildRelative));
                  }

                  // For unfiltered syncs, eagerly mirror directory metadata as before.
                  final var fileFilters = task.fileFilters;
                  if (fileFilters == null || fileFilters.isEmpty()) {
                     syncDirShallow(task, sourceChildAbsolute, targetChildAbsolute, sourceChildRelative);
                  }
               }
            }
         } catch (final IOException | RuntimeException ex) {
            stats.onError(ex);
            if (not(task.ignoreErrors)) {
               state = State.ABORT_BY_EXCEPTION;
               throw ex;
            }
            if (getVerbosity() > 0) {
               LOG.error(ex);
            } else {
               LOG.error(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
         } finally {
            try {
               // explicitly included empty directories (Option A from filter-behavior.md)
               if (!job.sourceDir.equals(task.sourceRootAbsolute)) {
                  final Path dirRelative = job.relativeDir;
                  final var fileFilters = task.fileFilters;
                  if (fileFilters != null && !fileFilters.isEmpty() //
                        && FilterEngine.isDirExplicitlyIncluded(sourceFilterCtx, dirRelative)) {
                     final var dirAttrs = FileAttrs.get(job.sourceDir);
                     if (FilterEngine.includesSource(sourceFilterCtx, job.sourceDir, dirRelative, dirAttrs)) {
                        prepareParentDirsForExplicitlyIncludedDir(task, dirRelative);
                        ensureTargetDirPrepared(task, job.sourceDir, dirRelative);
                     }
                  }
               }
            } finally {
               stats.onDirScanned();
            }
         }
      }
   }

   private void prepareParentDirsForIncludedFile(final SyncCommandConfig task, final Path fileRelative) throws IOException {
      final var fileFilters = task.fileFilters;
      if (fileFilters == null || fileFilters.isEmpty())
         // In unfiltered syncs, directories are mirrored eagerly via syncDirShallow.
         return;

      final Path parentRelative = fileRelative.getParent();
      // If the immediate parent is already known to be prepared (and thus its ancestors as well),
      // we can skip rebuilding and walking the full parent chain.
      if (parentRelative == null || preparedParentDirsRelative.contains(parentRelative))
         return;

      // build parent chain from root -> immediate parent
      final var parents = new ArrayDeque<Path>();
      Path current = parentRelative;
      while (current != null && current.getNameCount() > 0) {
         parents.push(current);
         current = current.getParent();
      }

      while (!parents.isEmpty()) {
         final var dirRelative = parents.pop();
         final var sourceDir = task.sourceRootAbsolute.resolve(dirRelative);
         ensureTargetDirPrepared(task, sourceDir, dirRelative);
      }
   }

   private void prepareParentDirsForExplicitlyIncludedDir(final SyncCommandConfig task, final Path dirRelative) throws IOException {
      final var fileFilters = task.fileFilters;
      if (fileFilters == null || fileFilters.isEmpty())
         // In unfiltered syncs, directories are mirrored eagerly via syncDirShallow.
         return;

      final Path parentRelative = dirRelative.getParent();
      if (parentRelative == null || preparedParentDirsRelative.contains(parentRelative))
         return;

      // build parent chain from root -> immediate parent
      final var parents = new ArrayDeque<Path>();
      Path current = parentRelative;
      while (current != null && current.getNameCount() > 0) {
         parents.push(current);
         current = current.getParent();
      }

      while (!parents.isEmpty()) {
         final var rel = parents.pop();
         final var sourceDir = task.sourceRootAbsolute.resolve(rel);
         ensureTargetDirPrepared(task, sourceDir, rel);
      }
   }

   private void ensureTargetDirPrepared(final SyncCommandConfig task, final Path sourceDir, final Path dirRelative) throws IOException {
      final Path targetDir = task.targetRootAbsolute.resolve(dirRelative);
      if (!preparedTargetDirs.add(targetDir))
         return;

      preparedParentDirsRelative.add(dirRelative);

      final Path existingTarget = Files.exists(targetDir, NOFOLLOW_LINKS) ? targetDir : null;
      syncDirShallow(task, sourceDir, existingTarget, dirRelative);
   }

   /**
    * @param targetPath null, if target path does not exist yet
    */
   private void syncDirShallow(final SyncCommandConfig task, final Path sourcePath, final @Nullable Path targetPath,
         final Path relativePath) throws IOException {
      final Path resolvedTargetPath = targetPath == null ? task.targetRootAbsolute.resolve(relativePath) : targetPath;

      DirectoryMirror.ensureDir(task, //
         isTrue(task.dryRun), //
         sourcePath, //
         targetPath, //
         resolvedTargetPath, //
         relativePath, //
         loggableEvents.contains(LogEvent.CREATE), //
         LOG, //
         stats, //
         (file, fileAttrs, countStats) -> delFile(task, file, fileAttrs, countStats), //
         dir -> delDir(task, dir), //
         Boolean.TRUE.equals(task.ignoreSymlinkErrors));
   }

   /**
    * @param targetPath null, if target path does not exist yet
    */
   private void syncFile(final SyncCommandConfig task, final Path sourcePath, @Nullable Path targetPath, final Path relativePath)
         throws IOException {
      final String copyCause;

      final var sourceAttrs = MoreFiles.readAttributes(sourcePath);

      if (targetPath == null) {
         // target file does not exist
         targetPath = task.targetRootAbsolute.resolve(relativePath);
         copyCause = "NEW";

      } else {

         /*
          * target path points to file
          */
         final var targetAttrs = FileAttrs.get(targetPath);
         if (targetAttrs.isFileOrFileSymlink()) {
            if (sourceAttrs.isSymbolicLink() && targetAttrs.isSymlink()) { // both are symlinks
               copyCause = null;

            } else if (sourceAttrs.isSymbolicLink() == targetAttrs.isSymlink()) { // neither is a symlink
               final var timeCompare = sourceAttrs.lastModifiedTime().compareTo(targetAttrs.lastModifiedTime());
               if (timeCompare > 0) {
                  copyCause = "NEWER";
               } else if (timeCompare < 0) {
                  if (isTrue(task.excludeOlderFiles)) {
                     LOG.debug("Ignoring source file [@|magenta %s|@] because it is older than target file...", relativePath);
                     copyCause = null;
                  } else {
                     copyCause = "OLDER";
                  }
               } else {
                  final var sizeCompare = Long.compare(sourceAttrs.size(), targetAttrs.size());
                  if (sizeCompare > 0) {
                     copyCause = "LARGER";
                  } else if (sizeCompare < 0) {
                     copyCause = "SMALLER";
                  } else {
                     LOG.trace("Source file [@|magenta %s|@] is in sync...", relativePath);
                     copyCause = null;
                  }
               }

            } else { // one is symlink
               if (sourceAttrs.isSymbolicLink()) {
                  LOG.debug("Deleting target [@|magenta %s|@] because source is symlink and target is not...", targetPath);
               } else {
                  LOG.debug("Deleting target [@|magenta %s|@] because target is symlink and source is not...", targetPath);
               }
               delFile(task, targetPath, targetAttrs, true);
               copyCause = "REPLACE";
            }

            /*
             * target path points to directory
             */
         } else {
            LOG.info("Deleting target directory [@|magenta %s|@] because source is file...", targetPath);
            if (targetAttrs.isSymlink()) {
               delFile(task, targetPath, targetAttrs, true);
            } else {
               delDir(task, targetPath);
            }
            copyCause = "REPLACE";
         }
      }

      if (copyCause != null) {
         if ("NEW".equals(copyCause) && loggableEvents.contains(LogEvent.CREATE) || loggableEvents.contains(LogEvent.MODIFY)) {
            LOG.info("%s [@|magenta %s|@] %s...", copyCause, relativePath, Size.ofBytes(sourceAttrs.size()));
         }
         final var start = System.currentTimeMillis();
         if (not(task.dryRun)) {
            FileUtils.copyFile(sourcePath, sourceAttrs, targetPath, isTrue(task.copyACL), (bytesWritten, totalBytesWritten) -> { /**/ });
         }
         stats.onFileCopied(System.currentTimeMillis() - start, sourceAttrs.size());
      }
   }
}
