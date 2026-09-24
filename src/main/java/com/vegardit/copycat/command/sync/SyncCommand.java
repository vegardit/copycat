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
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
 * Performs one-way directory synchronization using prepared roots while copying source symlinks as leaf entries.
 * Keeps original target contents separate from directory preparation, including simulated changes in dry-run mode.
 *
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

   /**
    * Original contents available to traversal, independent of whether preparation has since created a directory.
    * A conflict retains the entry to replace, but none of its descendants may be inspected.
    * Empty includes missing/deleted entries and descendants whose original ancestors are unavailable.
    */
   private enum TargetDirState {
      ORIGINAL_DIRECTORY,
      CONFLICT,
      EMPTY
   }

   static final class DirJob {
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
   static final class DirJobQueue {
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
   private final ConcurrentHashMap<Path, TargetDirState> targetDirStates = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Path, CompletableFuture<@Nullable Void>> preparedTargetDirs = new ConcurrentHashMap<>();

   private final SyncStats stats = new SyncStats();
   private volatile State state = State.NORMAL;

   public SyncCommand() {
      super(SyncCommandConfig::new);
   }

   SyncHelpers.Context syncContext(final SyncCommandConfig task) {
      return new SyncHelpers.Context( // CHECKSTYLE:IGNORE .*
         loggableEvents.contains(LogEvent.CREATE), // logCreate
         loggableEvents.contains(LogEvent.MODIFY), // logModify
         loggableEvents.contains(LogEvent.DELETE), // logDelete
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
      boolean hasDryRunTasks = false;
      boolean hasRealTasks = false;
      for (final var task : tasks) {
         if (isTrue(task.dryRun)) {
            hasDryRunTasks = true;
         } else {
            hasRealTasks = true;
         }
      }
      if (hasDryRunTasks && hasRealTasks)
         throw new picocli.CommandLine.ParameterException(commandSpec.commandLine(),
            "Mixing dry-run and non-dry-run sync tasks is not supported. Set dry-run globally via --dry-run or YAML defaults.");

      DesktopNotifications.setTrayIconToolTip("copycat is syncing...");

      stats.setDryRun(hasDryRunTasks);
      stats.start();

      try {
         for (final SyncCommandConfig task : tasks) {
            final var sourceFilterCtx = task.toSourceFilterContext();
            final var targetFilterCtx = task.toTargetFilterContext();
            // Tasks may reuse the same paths, but each must observe its own initial filesystem state.
            preparedTargetDirs.clear();
            targetDirStates.clear();
            final var ctx = syncContext(task);

            JdkLoggingUtils.withRootLogLevel(Level.INFO, //
               () -> LOG.info("Executing sync task with effective config:\n%s", YamlUtils.toYamlString(task)));

            final long stallTimeoutMillis = Math.max(asNonNull(task.stallTimeout).toMillis(), 0);
            progressTracker.reset();
            progressTracker.configureForStallTimeoutMillis(stallTimeoutMillis);
            final long awaitPollMillis = stallTimeoutMillis > 0 ? Math.min(AWAIT_POLL_MILLIS, stallTimeoutMillis) : AWAIT_POLL_MILLIS;

            final int taskThreads = Math.max(asNonNull(task.threads), 1);
            final var dirJobs = new DirJobQueue(taskThreads);
            LOG.info("Working hard using %s thread(s)%s...", taskThreads, isTrue(task.dryRun) ? " (DRY RUN)" : "");

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
                  if (state == State.ABORT_BY_SIGNAL) {
                     break;
                  }
                  if (state == State.ABORT_BY_EXCEPTION) {
                     // The abort flag can be visible before the failed worker's future reaches the completion queue.
                     // Keep waiting for its cause; a stall timeout here would mask the original failure.
                     continue;
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

            if (state == State.ABORT_BY_SIGNAL) {
               DesktopNotifications.showSticky(MessageType.WARNING, "Syncing aborted.", //
                  "FROM: " + task.sourceRootAbsolute + "\nTO: " + task.targetRootAbsolute);
               throw new InterruptedException("Sync aborted by signal.");
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
      cfgCLI.stallTimeout = SyncCommandConfig.parseDuration(value, "--stall-timeout");
   }

   @Option(names = "--timestamp-tolerance", paramLabel = "<duration>", description = {
      "Treat source and target files as unchanged when their last-modified timestamps differ by no more than this duration.",
      "Examples: 1s, 2s, PT0.5S. Use 0 to require exact timestamp matches. Default: 0"})
   private void setTimestampTolerance(final String value) {
      cfgCLI.timestampTolerance = SyncCommandConfig.parseTimestampTolerance(value, "--timestamp-tolerance");
   }

   void syncWorker(final SyncCommandConfig task, final FilterEngine.FilterContext sourceFilterCtx,
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
            // Waiting can end because of interruption as well as an empty queue; only the latter is successful completion.
            if (state == State.NORMAL) {
               checkInterrupted();
            }
            LOG.debug("Worker done.");
            break;
         }

         try {
            if (state != State.NORMAL) {
               break;
            }
            checkInterrupted();
            progressTracker.markProgress();
            final Path source = job.sourceDir;
            final Path sourceRelative = job.relativeDir;
            final Path target = source.equals(task.sourceRootAbsolute) //
                  ? task.targetRootAbsolute
                  : task.targetRootAbsolute.resolve(sourceRelative);

            // Capture the original view before preparation; new directories must not become comparison inputs.
            targetDirState(task, target);
            final var taskFileFilters = task.fileFilters;
            // The root is always prepared, even when filters include no children; other filtered directories stay lazy.
            // Keep runtime preparation in this error handler so ignoreErrors can count failures and continue with later tasks.
            if (taskFileFilters == null || taskFileFilters.isEmpty() || source.equals(task.sourceRootAbsolute)) {
               ensureTargetDirPrepared(task, ctx, source, sourceRelative);
            }

            if (loggableEvents.contains(LogEvent.SCAN)) {
               LOG.info("Scanning [@|magenta %s%s|@]...", sourceRelative, File.separator);
            }

            /*
             * read direct children of source dir
             */
            // Keep both listings outside entry recovery. A partial source map could make real source entries look extraneous.
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
             * Physical leftovers beneath a conflict or simulated deletion are not usable target contents.
             * The inherited state also prevents following a symlink in an intermediate path component.
             */
            targetChildren.clear();
            if (targetDirState(task, target) == TargetDirState.ORIGINAL_DIRECTORY) {
               try (var ds = Files.newDirectoryStream(target)) {
                  ds.forEach(child -> {
                     progressTracker.markProgress();
                     targetChildren.put(child.subpath(targetRootNameCount, child.getNameCount()), child);
                  });
               } catch (final NoSuchFileException ex) {
                  // A directory removed externally no longer provides original contents for descendants either.
                  targetDirStates.put(target, TargetDirState.EMPTY);
               }
            }

            /*
             * remove extraneous entries in target dir
             */
            // Keep recovery state separate from the complete source listing used for deletion decisions.
            final var failedTargetEntries = new HashSet<Path>();
            if (isTrue(task.delete)) {
               for (final var it = targetChildren.entrySet().iterator(); it.hasNext();) {
                  if (state != State.NORMAL) {
                     break;
                  }
                  checkInterrupted();
                  progressTracker.markProgress();
                  final var targetChildEntry = it.next();
                  final var targetChildRelative = targetChildEntry.getKey();
                  final var targetChildAbsolute = targetChildEntry.getValue();

                  try {
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
                        final boolean isIncludedTarget = FilterEngine.includesSource(targetFilterCtx, targetChildAbsolute,
                           targetChildRelative, targetAttrs);
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

                     boolean removed = true;
                     if (targetAttrs.isDir()) {
                        if (targetFilterHasEffects && !isTrue(task.deleteExcluded)) {
                           removed = SyncTargetDeletion.deleteDirectory(ctx, task.targetRootAbsolute, targetChildAbsolute, targetFilterCtx);
                        } else {
                           // Unconditional deletion remains appropriate when exclusions cannot protect descendants.
                           SyncHelpers.deleteDir(ctx, targetChildAbsolute);
                           // Shared detail logs are subtree-relative; identify the root only after the whole deletion succeeds.
                           if (loggableEvents.contains(LogEvent.DELETE)) {
                              LOG.info("DELETE [@|magenta %s|@]...", targetChildRelative);
                           }
                        }
                     } else {
                        SyncHelpers.deleteFile(ctx, targetChildAbsolute, targetAttrs, true);
                        // Report a leaf only after deletion succeeds or is planned.
                        if (loggableEvents.contains(LogEvent.DELETE)) {
                           LOG.info("DELETE [@|magenta %s|@]...", targetChildRelative);
                        }
                     }

                     // Partial pruning leaves original contents available; only complete removal may imply an EMPTY target subtree.
                     if (removed) {
                        it.remove();
                     }
                  } catch (final IOException | RuntimeException ex) {
                     // Rethrown failures belong to the outer handler; counting here as well would report them twice.
                     if (state != State.NORMAL || not(task.ignoreErrors) || Thread.currentThread().isInterrupted())
                        throw ex;
                     stats.onError(ex);
                     logError(ex);
                     failedTargetEntries.add(targetChildRelative);
                  }
               }
            }

            /*
             * iterate over direct children of source dir
             */
            // Siblings share this chain, and ancestor jobs finish deletion before publishing their children.
            // Reuse preparation only within this job; concurrent external replacements are not isolated by either sync mode.
            boolean parentDirsPrepared = false;
            for (final var sourceEntry : sourceChildren.entrySet()) {
               if (state != State.NORMAL) {
                  break;
               }
               checkInterrupted();
               progressTracker.markProgress();
               final var sourceChildRelative = sourceEntry.getKey();
               // A failed target operation may have removed some descendants. Reusing physical leftovers in dry-run
               // would lose those simulated deletions, so skip dependent source work (including queueing its directory).
               if (failedTargetEntries.contains(sourceChildRelative)) {
                  continue;
               }
               final var sourceChildAbsolute = sourceEntry.getValue();
               final var targetChildAbsolute = targetChildren.get(sourceChildRelative);

               try {
                  final boolean skipSubtreeScan = task.isExcludedSourceSubtreeDir(sourceChildRelative);

                  final var sourceAttrs = FilterEngine.getFileAttrsIfIncluded(sourceFilterCtx, sourceChildAbsolute, sourceChildRelative,
                     skipSubtreeScan);
                  if (sourceAttrs == null) {
                     continue;
                  }

                  // Traversal alone must not create a target directory; only included files and symlink leaves need it here.
                  if (!parentDirsPrepared && (sourceAttrs.isFile() || sourceAttrs.isSymlink())) {
                     prepareParentDirs(task, ctx, sourceChildRelative);
                     // Reuse is valid only after every ancestor's preparation has completed successfully, including other workers' work.
                     parentDirsPrepared = true;
                  }

                  switch (sourceAttrs.type()) {
                     case FILE, FILE_SYMLINK -> {
                        syncFile(task, ctx, sourceChildAbsolute, targetChildAbsolute, sourceChildRelative);
                        stats.onFileScanned();
                     }
                     case BROKEN_SYMLINK, OTHER_SYMLINK -> {
                        syncSymlinkLeaf(task, ctx, sourceChildAbsolute, targetChildAbsolute, sourceChildRelative, sourceAttrs);
                        stats.onFileScanned();
                     }
                     case DIRECTORY_SYMLINK -> {
                        // Directory symlinks are leaves too; their referents must not become traversal jobs.
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
                           // Deletion finishes before jobs are published. A removed entry stays absent in dry-run,
                           // even if its physical contents remain or another descendant later prepares the parent.
                           if (targetChildAbsolute == null) {
                              targetDirStates.put(task.targetRootAbsolute.resolve(sourceChildRelative), TargetDirState.EMPTY);
                           }
                           // Inspect existing entries in their own jobs so an ignored failure does not prevent sibling jobs.
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
               } catch (final IOException | RuntimeException ex) {
                  // Cancellation is not an ignored entry failure; the outer handler owns its accounting and completion.
                  if (state != State.NORMAL || not(task.ignoreErrors) || Thread.currentThread().isInterrupted())
                     throw ex;
                  stats.onError(ex);
                  logError(ex);
               }
            }
         } catch (final IOException | RuntimeException ex) {
            stats.onError(ex);
            // An existing signal or worker failure already owns completion; do not replace its abort reason.
            if (state == State.NORMAL && (not(task.ignoreErrors) || Thread.currentThread().isInterrupted())) {
               state = State.ABORT_BY_EXCEPTION;
               throw ex;
            }
            logError(ex);
         } finally {
            try {
               // Finally also runs after interruption; explicitly included directories must not resume filesystem work then.
               if (state == State.NORMAL && !Thread.currentThread().isInterrupted() && !job.sourceDir.equals(task.sourceRootAbsolute)) {
                  final var fileFilters = task.fileFilters;
                  if (fileFilters != null && !fileFilters.isEmpty() //
                        && FilterEngine.isDirExplicitlyIncluded(sourceFilterCtx, job.relativeDir)) {
                     final var dirAttrs = FileAttrs.get(job.sourceDir);
                     if (FilterEngine.includesSource(sourceFilterCtx, job.sourceDir, job.relativeDir, dirAttrs)) {
                        prepareParentDirs(task, ctx, job.relativeDir);
                        ensureTargetDirPrepared(task, ctx, job.sourceDir, job.relativeDir);
                     }
                  }
               }
            } catch (final IOException | RuntimeException ex) {
               stats.onError(ex);
               if (state == State.NORMAL && (not(task.ignoreErrors) || Thread.currentThread().isInterrupted())) {
                  state = State.ABORT_BY_EXCEPTION;
                  throw ex;
               }
               logError(ex);
            } finally {
               stats.onDirScanned();
            }
         }
      }
   }

   private static void checkInterrupted() throws InterruptedIOException {
      // Preserve the flag for the caller; ignoreErrors must never turn interrupted work into successful completion.
      if (Thread.currentThread().isInterrupted())
         throw new InterruptedIOException("Sync worker interrupted.");
   }

   private void logError(final Exception ex) {
      if (getVerbosity() > 0) {
         LOG.error(ex);
      } else {
         LOG.error(ex.getClass().getSimpleName() + ": " + ex.getMessage());
      }
   }

   private void prepareParentDirs(final SyncCommandConfig task, final SyncHelpers.Context ctx, final Path entryRelative)
         throws IOException {
      final var fileFilters = task.fileFilters;
      if (fileFilters == null || fileFilters.isEmpty())
         // Unfiltered jobs prepare their target directory before copying any children.
         return;

      // Always visit ancestors in order: a cached leaf cannot certify that its parents are still real directories.
      final var parents = new ArrayDeque<Path>();
      Path current = entryRelative.getParent();
      while (current != null && current.getNameCount() > 0) {
         parents.push(current);
         current = current.getParent();
      }
      parents.push(Paths.get("."));

      while (!parents.isEmpty()) {
         final var dirRelative = parents.pop();
         final var sourceDir = task.sourceRootAbsolute.resolve(dirRelative);
         ensureTargetDirPrepared(task, ctx, sourceDir, dirRelative);
      }
   }

   /**
    * Prepares one real source directory after its ancestors; source symlink leaves use syncDirShallow directly.
    */
   void ensureTargetDirPrepared(final SyncCommandConfig task, final SyncHelpers.Context ctx, final Path sourceDir, final Path dirRelative)
         throws IOException {
      // The root job uses "."; normalize so parent preparation and traversal share one cache entry.
      final Path targetDir = task.targetRootAbsolute.resolve(dirRelative).normalize();
      while (true) {
         final var existingFuture = preparedTargetDirs.get(targetDir);
         if (existingFuture != null) {
            awaitTargetDirPrepared(existingFuture, dirRelative);
            // Dry-run completion certifies preparation even when the physical entry is still missing or incompatible.
            if (isTrue(task.dryRun))
               return;
            final var targetAttrs = readTargetDirAttributes(targetDir);
            // A newly visible directory may belong to a replacement whose preparation has not completed yet.
            if (targetAttrs != null && targetAttrs.isDirectory() && !targetAttrs.isSymbolicLink() && preparedTargetDirs.get(
               targetDir) == existingFuture)
               return;
         }

         final var myFuture = new CompletableFuture<@Nullable Void>();
         // Renew only the completed future we inspected. A stale observer must not evict another worker's new owner.
         if (existingFuture == null ? preparedTargetDirs.putIfAbsent(targetDir, myFuture) != null
               : !preparedTargetDirs.replace(targetDir, existingFuture, myFuture)) {
            continue;
         }
         try {
            // Attribute failures must complete the published future too, otherwise waiters can remain blocked.
            // Real runs also need this snapshot before mutation; moving the call into the dry-run branch loses it.
            final var originalState = targetDirState(task, targetDir); // CHECKSTYLE:IGNORE MoveVariableInsideIfCheck
            final Path existingTarget;
            if (isTrue(task.dryRun)) {
               // Conflicts still need unlinking, but simulated removals and unsafe descendants must stay absent.
               existingTarget = originalState == TargetDirState.EMPTY ? null : targetDir;
            } else {
               final var targetAttrs = readTargetDirAttributes(targetDir);
               existingTarget = targetAttrs == null ? null : targetDir;
               if (targetAttrs == null || !targetAttrs.isDirectory() || targetAttrs.isSymbolicLink()) {
                  targetDirStates.put(targetDir, targetAttrs == null ? TargetDirState.EMPTY : TargetDirState.CONFLICT);
               }
            }
            syncDirShallow(task, ctx, sourceDir, existingTarget, dirRelative);
            // Preparation completion never promotes replacement contents into the original comparison view.
            myFuture.complete(null);
            return;
         } catch (final IOException | RuntimeException ex) {
            myFuture.completeExceptionally(ex);
            preparedTargetDirs.remove(targetDir, myFuture);
            throw ex;
         }
      }
   }

   void awaitTargetDirPrepared(final CompletableFuture<@Nullable Void> future, final Path dirRelative) throws IOException {
      try {
         // Keep the captured future so existing waiters observe its failure even after a retry replaces the cache entry.
         future.get();
      } catch (final InterruptedException ex) {
         Thread.currentThread().interrupt();
         throw new IOException("Interrupted while preparing target directory [" + dirRelative + "].", ex);
      } catch (final java.util.concurrent.ExecutionException ex) {
         final var cause = ex.getCause();
         if (cause instanceof final IOException io)
            throw io;
         if (cause instanceof final RuntimeException re)
            throw re;
         if (cause instanceof final Error err)
            throw err;
         throw new RuntimeException(cause);
      }
   }

   private TargetDirState targetDirState(final SyncCommandConfig task, final Path targetDir) throws IOException {
      // A real final component is insufficient: an earlier conflict/removal makes every original descendant unavailable.
      // Check ancestors before the cache so previously recorded children also lose their original contents.
      if (!targetDir.equals(task.targetRootAbsolute) && targetDirState(task, asNonNull(targetDir
         .getParent())) != TargetDirState.ORIGINAL_DIRECTORY) {
         targetDirStates.put(targetDir, TargetDirState.EMPTY);
         return TargetDirState.EMPTY;
      }
      final var knownState = targetDirStates.get(targetDir);
      if (knownState != null)
         return knownState;

      final var attrs = readTargetDirAttributes(targetDir);
      final var originalState = attrs == null ? TargetDirState.EMPTY
            : attrs.isDirectory() && !attrs.isSymbolicLink() ? TargetDirState.ORIGINAL_DIRECTORY : TargetDirState.CONFLICT;
      final var existingState = targetDirStates.putIfAbsent(targetDir, originalState);
      return existingState == null ? originalState : existingState;
   }

   /**
    * Inspects only the entry itself; callers establish usable ancestors before requesting descendant attributes.
    */
   @Nullable
   BasicFileAttributes readTargetDirAttributes(final Path targetDir) throws IOException {
      try {
         return Files.readAttributes(targetDir, BasicFileAttributes.class, NOFOLLOW_LINKS);
      } catch (final NoSuchFileException ex) {
         // Inaccessibility and other I/O errors must not be mistaken for an empty target subtree.
         return null;
      }
   }

   /**
    * @param targetPath null if no usable target entry remains, including after simulated deletion
    */
   void syncDirShallow(final SyncCommandConfig task, final SyncHelpers.Context ctx, final Path sourcePath, final @Nullable Path targetPath,
         final Path relativePath) throws IOException {
      // Creating a missing root requires the root path itself; a trailing "/." would require it to exist already.
      final Path resolvedTargetPath = targetPath == null ? task.targetRootAbsolute.resolve(relativePath).normalize() : targetPath;
      SyncHelpers.ensureDir(ctx, sourcePath, targetPath, resolvedTargetPath, relativePath);
   }

   /**
    * Package-private so tests can inject file-operation failures without platform-dependent permissions.
    *
    * @param targetPath null if no usable target entry remains, including after simulated deletion
    */
   void syncFile(final SyncCommandConfig task, final SyncHelpers.Context ctx, final Path sourcePath, @Nullable Path targetPath,
         final Path relativePath) throws IOException {
      final @Nullable SyncFileCopyCause copyCause;

      if (Files.isSymbolicLink(sourcePath)) {
         syncSymlinkLeaf(task, ctx, sourcePath, targetPath, relativePath, FileAttrs.get(sourcePath));
         return;
      }

      final var sourceAttrs = MoreFiles.readAttributes(sourcePath);

      if (targetPath == null) {
         // target file does not exist
         targetPath = task.targetRootAbsolute.resolve(relativePath);
         copyCause = SyncFileCopyCause.NEW;
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
                  copyCause = SyncFileCopyCause.REPLACE;
               } else {
                  final var compareResult = SyncFileComparator.compareRegularFiles(sourceAttrs, targetAttrs, asNonNull(
                     task.timestampTolerance), isTrue(task.excludeOlderFiles));
                  if (compareResult.timestampToleranceApplied()) {
                     // Precision-limited targets may hit this for most unchanged files, so keep it below debug.
                     LOG.trace("Treating source file [@|magenta %s|@] as timestamp-equal to target; delta [%s] is within tolerance [%s].",
                        relativePath, compareResult.toleratedTimestampDelta(), task.timestampTolerance);
                  }
                  if (compareResult.sourceOlderSkipped()) {
                     LOG.debug("Ignoring source file [@|magenta %s|@] because it is older than target file...", relativePath);
                     copyCause = null;
                  } else if (compareResult.shouldCopy()) {
                     copyCause = compareResult.copyCause();
                  } else {
                     LOG.trace("Source file [@|magenta %s|@] is in sync...", relativePath);
                     copyCause = null;
                  }
               }
            }
            case OTHER -> {
               LOG.info("Deleting target entry [@|magenta %s|@] because source is file...", targetPath);
               SyncHelpers.deleteFile(ctx, targetPath, targetAttrs, true);
               copyCause = SyncFileCopyCause.REPLACE;
            }
            case DIRECTORY, DIRECTORY_SYMLINK -> {
               LOG.info("Deleting target directory [@|magenta %s|@] because source is file...", targetPath);
               if (targetAttrs.isSymlink()) {
                  SyncHelpers.deleteFile(ctx, targetPath, targetAttrs, true);
               } else {
                  SyncHelpers.deleteDir(ctx, targetPath);
               }
               copyCause = SyncFileCopyCause.REPLACE;
            }
            default -> throw new IllegalStateException("Unknown type [" + targetAttrs.type() + "] of target [" + targetPath + "].");
         }
      }

      if (copyCause != null) {
         if (copyCause == SyncFileCopyCause.NEW ? loggableEvents.contains(LogEvent.CREATE) : loggableEvents.contains(LogEvent.MODIFY)) {
            LOG.info("%s [@|magenta %s|@] %s...", copyCause.name(), relativePath, Size.ofBytes(sourceAttrs.size()));
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
