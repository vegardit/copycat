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
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.eclipse.jdt.annotation.Nullable;

import com.vegardit.copycat.util.DesktopNotifications;
import com.vegardit.copycat.util.FileAttrs;
import com.vegardit.copycat.util.FileUtils;
import com.vegardit.copycat.util.JdkLoggingUtils;
import com.vegardit.copycat.util.ProgressTracker;
import com.vegardit.copycat.util.YamlUtils;

import net.sf.jstuff.core.collection.Sets;
import net.sf.jstuff.core.concurrent.Threads;
import net.sf.jstuff.core.io.MoreFiles;
import net.sf.jstuff.core.io.Size;
import net.sf.jstuff.core.logging.Logger;
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

   private static final long AWAIT_POLL_MILLIS = 5_000;
   private static final long FORCE_SHUTDOWN_TIMEOUT_MILLIS = 30_000;
   private static final long WORKER_IDLE_SLEEP_MILLIS = 25;

   /**
    * Multi-producer/multi-consumer directory job queue with a simple "all workers idle => done" termination
    * condition.
    * <p>
    * Important invariant: only active workers can enqueue new directory jobs. Therefore, if all workers are
    * simultaneously waiting for work and the queue is empty, no new work will appear and workers can exit.
    */
   private static final class DirJobQueue {
      private final int workerCount;
      private final Queue<DirJob> jobs;

      private int workersWaiting;
      private boolean workersDone;

      DirJobQueue(final int workerCount) {
         this(workerCount, new ArrayDeque<>());
      }

      DirJobQueue(final int workerCount, final Queue<DirJob> jobs) {
         this.workerCount = workerCount;
         this.jobs = jobs;
      }

      synchronized void add(final DirJob job) {
         if (workersDone)
            return;
         jobs.add(job);
         notifyAll();
      }

      synchronized @Nullable DirJob pollOrWait(final BooleanSupplier keepRunning) {
         final var immediate = jobs.poll();
         if (immediate != null || workerCount <= 1)
            return immediate;

         workersWaiting++;
         try {
            while (!workersDone && keepRunning.getAsBoolean()) {
               final var next = jobs.poll();
               if (next != null)
                  return next;

               // If all workers are waiting for a new job, the queue is drained and no further work will be enqueued,
               // because only active workers can discover/enqueue new directory jobs.
               if (workersWaiting == workerCount) {
                  workersDone = true;
                  notifyAll();
                  return null;
               }

               try {
                  wait(WORKER_IDLE_SLEEP_MILLIS);
               } catch (final InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  workersDone = true;
                  notifyAll();
                  return null;
               }
            }
            return null;
         } finally {
            workersWaiting--;
         }
      }
   }

   private final ProgressTracker progressTracker = new ProgressTracker();

   private final Set<LogEvent> loggableEvents = Sets.newHashSet(LogEvent.values());
   private final Set<Path> preparedTargetDirs = ConcurrentHashMap.newKeySet();
   private final Set<Path> preparedParentDirsRelative = ConcurrentHashMap.newKeySet();

   private final SyncStats stats = new SyncStats();
   private volatile State state = State.NORMAL;

   public SyncCommand() {
      super(SyncCommandConfig::new);
   }

   private SyncHelpers.Context syncContext(final SyncCommandConfig task) {
      return new SyncHelpers.Context( // CHECKSTYLE:IGNORE .*
         loggableEvents.contains(LogEvent.CREATE), // logCreate
         loggableEvents.contains(LogEvent.MODIFY), // logModify
         true, // logDelete
         isTrue(task.dryRun), // dryRun
         isTrue(task.ignoreSymlinkErrors), // ignoreSymlinkErrors
         isTrue(task.copyACL), // copyACL
         isTrue(task.allowReadingOpenFiles), // allowReadingOpenFiles
         stats, // stats
         progressTracker // progress
      );
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
            final var ctx = syncContext(task);

            JdkLoggingUtils.withRootLogLevel(Level.INFO, () -> {
               LOG.info("Executing sync task with effective config:\n%s", YamlUtils.toYamlString(task));
            });

            final long stallTimeoutMillis = Math.max(asNonNull(task.stallTimeout).toMillis(), 0);
            progressTracker.reset();
            progressTracker.configureForStallTimeoutMillis(stallTimeoutMillis);
            final long awaitPollMillis = stallTimeoutMillis > 0 ? Math.min(AWAIT_POLL_MILLIS, stallTimeoutMillis) : AWAIT_POLL_MILLIS;

            final int taskThreads = Math.max(asNonNull(task.threads), 1);
            final var dirJobs = new DirJobQueue(taskThreads);
            LOG.info("Working hard using %s thread(s)%s...", taskThreads, isTrue(task.dryRun) ? " (DRY RUN)" : "");

            if (!Files.exists(task.targetRootAbsolute, NOFOLLOW_LINKS)) {
               if (loggableEvents.contains(LogEvent.CREATE)) {
                  LOG.info("NEW [@|magenta %s%s|@]...", task.targetRootAbsolute, File.separator);
               }
               if (not(task.dryRun)) {
                  FileUtils.copyDirShallow(task.sourceRootAbsolute, task.targetRootAbsolute, isTrue(task.copyACL));
               }
            }

            dirJobs.add(new DirJob(task.sourceRootAbsolute, Paths.get(".")));

            /*
             * start syncing
             */
            DesktopNotifications.showTransient(MessageType.INFO, "Syncing started...", //
               "FROM: " + task.sourceRootAbsolute + "\nTO: " + task.targetRootAbsolute);
            final var threadPool = Executors.newFixedThreadPool(taskThreads, //
               BasicThreadFactory.builder().namingPattern("sync-%d").build() //
            );
            final var completion = new ExecutorCompletionService<@Nullable Void>(threadPool);
            for (var i = 0; i < taskThreads; i++) {
               completion.submit(() -> {
                  try {
                     syncWorker(task, sourceFilterCtx, targetFilterCtx, dirJobs, ctx);
                     return null;
                  } catch (final Exception ex) {
                     state = State.ABORT_BY_EXCEPTION;
                     throw ex;
                  }
               });
            }
            threadPool.shutdown();
            @Nullable
            Exception stallError = null;
            @Nullable
            Throwable workerError = null;
            int remainingThreads = taskThreads;
            while (remainingThreads > 0) {
               final @Nullable Future<@Nullable Void> completed = completion.poll(awaitPollMillis, TimeUnit.MILLISECONDS);
               if (completed == null) {
                  if (state != State.NORMAL) {
                     break;
                  }
                  try {
                     progressTracker.checkStalled(stallTimeoutMillis, "Sync");
                  } catch (final IOException ex) {
                     stallError = ex;
                     state = State.ABORT_BY_EXCEPTION;
                     break;
                  }
                  continue;
               }
               remainingThreads--;
               try {
                  completed.get();
               } catch (final java.util.concurrent.ExecutionException ex) {
                  state = State.ABORT_BY_EXCEPTION;
                  workerError = ex.getCause();
                  break;
               }
            }
            if (state != State.NORMAL) {
               threadPool.shutdownNow();
               threadPool.awaitTermination(FORCE_SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            }
            if (stallError != null) {
               DesktopNotifications.showSticky(MessageType.ERROR, "Syncing failed.", //
                  "ERROR: " + stallError.getMessage() + "\nFROM: " + task.sourceRootAbsolute + "\nTO: " + task.targetRootAbsolute);
               throw stallError;
            }
            if (workerError != null) {
               DesktopNotifications.showSticky(MessageType.ERROR, "Syncing failed.", //
                  "ERROR: " + workerError.getMessage() + "\nFROM: " + task.sourceRootAbsolute + "\nTO: " + task.targetRootAbsolute);
               if (workerError instanceof final Exception ex)
                  throw ex;
               if (workerError instanceof final Error ex)
                  throw ex;
               throw new RuntimeException(workerError);
            }

            DesktopNotifications.showSticky(MessageType.INFO, "Syncing done.", //
               "FROM: " + task.sourceRootAbsolute + "\nTO: " + task.targetRootAbsolute);
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

   @Option(names = "--stall-timeout", paramLabel = "<duration>", description = {"Abort sync if no progress is observed for this long.",
      "Examples: PT10M, 10m, 2h 30m. Use 0 to disable.", "Bare numbers are minutes. Default: 10m"})
   private void setStallTimeout(final String value) {
      final String v = value.strip();
      if (v.matches("\\d+")) {
         final long minutes = Long.parseLong(v);
         cfgCLI.stallTimeout = minutes == 0 ? java.time.Duration.ZERO : java.time.Duration.ofMinutes(minutes);
         return;
      }
      cfgCLI.stallTimeout = SyncCommandConfig.parseDuration(v, "--stall-timeout");
   }

   private void syncWorker(final SyncCommandConfig task, final FilterEngine.FilterContext sourceFilterCtx,
         final FilterEngine.FilterContext targetFilterCtx, final DirJobQueue dirJobs, final SyncHelpers.Context ctx) throws IOException {
      final var sourceChildren = new HashMap<Path, Path>(256); // Map<SourcePathRelativeToRoot, SourcePathAbsolute>
      final var targetChildren = new HashMap<Path, Path>(256); // Map<TargetPathRelativeToRoot, TargetPathAbsolute>
      final int sourceRootNameCount = task.sourceRootAbsolute.getNameCount();
      final int targetRootNameCount = task.targetRootAbsolute.getNameCount();
      final boolean targetFilterHasEffects = targetFilterCtx.modifiedFrom != null //
            || targetFilterCtx.modifiedTo != null //
            || targetFilterCtx.excludeHiddenFiles //
            || targetFilterCtx.excludeHiddenSystemFiles //
            || targetFilterCtx.excludeOtherLinks //
            || targetFilterCtx.excludeSystemFiles //
            || !targetFilterCtx.sourceRules.isEmpty();

      while (state == State.NORMAL) {
         final var job = dirJobs.pollOrWait(() -> state == State.NORMAL);
         if (job == null) {
            LOG.debug("Worker done.");
            break;
         }

         try {
            progressTracker.markProgress();
            final Path source = job.sourceDir;
            final Path sourceRelative = job.relativeDir;
            final Path target = source.equals(task.sourceRootAbsolute) //
                  ? task.targetRootAbsolute
                  : task.targetRootAbsolute.resolve(sourceRelative);

            // In unfiltered syncs directories are mirrored eagerly. With multiple worker threads a child directory
            // may be scanned before its parent thread has created the corresponding target directory. Ensure the
            // target directory exists before attempting to copy files into it.
            final var taskFileFilters = task.fileFilters;
            if (taskFileFilters == null || taskFileFilters.isEmpty()) {
               final Path existingTarget = Files.exists(target, NOFOLLOW_LINKS) ? target : null;
               syncDirShallow(task, ctx, source, existingTarget, sourceRelative);
            }

            if (loggableEvents.contains(LogEvent.SCAN)) {
               LOG.info("Scanning [@|magenta %s%s|@]...", sourceRelative, File.separator);
            }

            /*
             * read direct children of source dir
             */
            sourceChildren.clear();
            try (var ds = Files.newDirectoryStream(source)) {
               ds.forEach(child -> {
                  progressTracker.markProgress();
                  sourceChildren.put(child.subpath(sourceRootNameCount, child.getNameCount()), child);
               });
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
                  ds.forEach(child -> {
                     progressTracker.markProgress();
                     targetChildren.put(child.subpath(targetRootNameCount, child.getNameCount()), child);
                  });
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
                  progressTracker.markProgress();
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
                     SyncHelpers.deleteDir(ctx, targetChildAbsolute);
                  } else {
                     SyncHelpers.deleteFile(ctx, targetChildAbsolute, targetAttrs, true);
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
               progressTracker.markProgress();
               final var sourceChildRelative = sourceEntry.getKey();
               final var sourceChildAbsolute = sourceEntry.getValue();
               final var targetChildAbsolute = targetChildren.get(sourceChildRelative);

               final boolean skipSubtreeScan = task.isExcludedSourceSubtreeDir(sourceChildRelative);

               final var sourceAttrs = FilterEngine.getFileAttrsIfIncluded(sourceFilterCtx, sourceChildAbsolute, sourceChildRelative,
                  skipSubtreeScan);
               if (sourceAttrs == null) {
                  continue;
               }

               switch (sourceAttrs.type()) {
                  case FILE, FILE_SYMLINK -> {
                     prepareParentDirsForIncludedFile(task, ctx, sourceChildRelative);
                     syncFile(task, ctx, sourceChildAbsolute, targetChildAbsolute, sourceChildRelative);
                     stats.onFileScanned();
                  }
                  case BROKEN_SYMLINK, OTHER_SYMLINK -> {
                     prepareParentDirsForIncludedFile(task, ctx, sourceChildRelative);
                     syncSymlinkLeaf(task, ctx, sourceChildAbsolute, targetChildAbsolute, sourceChildRelative, sourceAttrs);
                     stats.onFileScanned();
                  }
                  case DIRECTORY_SYMLINK -> {
                     // handle directory symlink entries immediately (do not descend into them)
                     // and ensure their parent directory chain exists on the target, just like for files.
                     prepareParentDirsForIncludedFile(task, ctx, sourceChildRelative);
                     syncDirShallow(task, ctx, sourceChildAbsolute, targetChildAbsolute, sourceChildRelative);
                     stats.onFileScanned();
                  }
                  case DIRECTORY -> {
                     final Integer maxDepth = task.maxDepth;
                     final int childDepth = sourceChildRelative.getNameCount();

                     // respect optional max-depth: skip directories beyond maxDepth entirely
                     if (maxDepth != null && childDepth > maxDepth) {
                        if (LOG.isTraceEnabled()) {
                           LOG.trace("Ignoring directory outside max-depth [%s]: %s", maxDepth, sourceChildRelative);
                        }
                        break;
                     }

                     // respect optional max-depth: only descend if child depth <= maxDepth
                     if (!skipSubtreeScan && (maxDepth == null || childDepth <= maxDepth)) {
                        dirJobs.add(new DirJob(sourceChildAbsolute, sourceChildRelative));
                     }
                  }
                  case OTHER -> {
                     if (getVerbosity() > 0) {
                        LOG.warn("Skipping unsupported filesystem entry [@|magenta %s|@].", sourceChildRelative);
                     }
                     stats.onFileScanned();
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
               // explicitly included empty directories
               if (!job.sourceDir.equals(task.sourceRootAbsolute)) {
                  final var fileFilters = task.fileFilters;
                  if (fileFilters != null && !fileFilters.isEmpty() //
                        && FilterEngine.isDirExplicitlyIncluded(sourceFilterCtx, job.relativeDir)) {
                     final var dirAttrs = FileAttrs.get(job.sourceDir);
                     if (FilterEngine.includesSource(sourceFilterCtx, job.sourceDir, job.relativeDir, dirAttrs)) {
                        prepareParentDirsForExplicitlyIncludedDir(task, ctx, job.relativeDir);
                        ensureTargetDirPrepared(task, ctx, job.sourceDir, job.relativeDir);
                     }
                  }
               }
            } finally {
               stats.onDirScanned();
            }
         }
      }
   }

   private void prepareParentDirsForIncludedFile(final SyncCommandConfig task, final SyncHelpers.Context ctx, final Path fileRelative)
         throws IOException {
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
         ensureTargetDirPrepared(task, ctx, sourceDir, dirRelative);
      }
   }

   private void prepareParentDirsForExplicitlyIncludedDir(final SyncCommandConfig task, final SyncHelpers.Context ctx,
         final Path dirRelative) throws IOException {
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
         ensureTargetDirPrepared(task, ctx, sourceDir, rel);
      }
   }

   private void ensureTargetDirPrepared(final SyncCommandConfig task, final SyncHelpers.Context ctx, final Path sourceDir,
         final Path dirRelative) throws IOException {
      final Path targetDir = task.targetRootAbsolute.resolve(dirRelative);
      if (!preparedTargetDirs.add(targetDir))
         return;

      preparedParentDirsRelative.add(dirRelative);

      final Path existingTarget = Files.exists(targetDir, NOFOLLOW_LINKS) ? targetDir : null;
      try {
         syncDirShallow(task, ctx, sourceDir, existingTarget, dirRelative);
      } catch (final IOException | RuntimeException ex) {
         preparedTargetDirs.remove(targetDir);
         preparedParentDirsRelative.remove(dirRelative);
         throw ex;
      }
   }

   /**
    * @param targetPath null, if target path does not exist yet
    */
   private void syncDirShallow(final SyncCommandConfig task, final SyncHelpers.Context ctx, final Path sourcePath,
         final @Nullable Path targetPath, final Path relativePath) throws IOException {
      final Path resolvedTargetPath = targetPath == null ? task.targetRootAbsolute.resolve(relativePath) : targetPath;
      SyncHelpers.ensureDir(ctx, sourcePath, targetPath, resolvedTargetPath, relativePath);
   }

   /**
    * @param targetPath null, if target path does not exist yet
    */
   private void syncFile(final SyncCommandConfig task, final SyncHelpers.Context ctx, final Path sourcePath, @Nullable Path targetPath,
         final Path relativePath) throws IOException {
      final String copyCause;

      if (Files.isSymbolicLink(sourcePath)) {
         syncSymlinkLeaf(task, ctx, sourcePath, targetPath, relativePath, FileAttrs.get(sourcePath));
         return;
      }

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
         switch (targetAttrs.type()) {
            case FILE, FILE_SYMLINK, BROKEN_SYMLINK, OTHER_SYMLINK -> {
               if (targetAttrs.isSymlink()) {
                  LOG.debug("Deleting target [@|magenta %s|@] because target is symlink and source is not...", targetPath);
                  SyncHelpers.deleteFile(ctx, targetPath, targetAttrs, true);
                  copyCause = "REPLACE";
               } else {
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
               }
            }
            case OTHER -> {
               LOG.info("Deleting target entry [@|magenta %s|@] because source is file...", targetPath);
               SyncHelpers.deleteFile(ctx, targetPath, targetAttrs, true);
               copyCause = "REPLACE";
            }
            case DIRECTORY, DIRECTORY_SYMLINK -> {
               LOG.info("Deleting target directory [@|magenta %s|@] because source is file...", targetPath);
               if (targetAttrs.isSymlink()) {
                  SyncHelpers.deleteFile(ctx, targetPath, targetAttrs, true);
               } else {
                  SyncHelpers.deleteDir(ctx, targetPath);
               }
               copyCause = "REPLACE";
            }
            default -> throw new IllegalStateException("Unknown type [" + targetAttrs.type() + "] of target [" + targetPath + "].");
         }
      }

      if (copyCause != null) {
         if ("NEW".equals(copyCause) ? loggableEvents.contains(LogEvent.CREATE) : loggableEvents.contains(LogEvent.MODIFY)) {
            LOG.info("%s [@|magenta %s|@] %s...", copyCause, relativePath, Size.ofBytes(sourceAttrs.size()));
         }
         SyncHelpers.copyFile(ctx, sourcePath, sourceAttrs, targetPath);
      }
   }

   private void syncSymlinkLeaf(final SyncCommandConfig task, final SyncHelpers.Context ctx, final Path sourcePath,
         final @Nullable Path targetPath, final Path relativePath, final FileAttrs sourceAttrs) throws IOException {
      final Path resolvedTargetPath = targetPath == null ? task.targetRootAbsolute.resolve(relativePath) : targetPath;
      SyncHelpers.syncSymlinkLeaf(ctx, sourcePath, targetPath, resolvedTargetPath, relativePath, sourceAttrs);
   }
}
