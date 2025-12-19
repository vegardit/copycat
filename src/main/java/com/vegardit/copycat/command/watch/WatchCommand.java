/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.watch;

import static com.vegardit.copycat.util.Booleans.*;
import static net.sf.jstuff.core.validation.NullAnalysisHelper.asNonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.eclipse.jdt.annotation.Nullable;

import com.vegardit.copycat.command.sync.AbstractSyncCommand;
import com.vegardit.copycat.command.sync.FilterEngine;
import com.vegardit.copycat.command.sync.FilterEngine.FilterContext;
import com.vegardit.copycat.command.sync.SyncHelpers;
import com.vegardit.copycat.util.DesktopNotifications;
import com.vegardit.copycat.util.FileAttrs;
import com.vegardit.copycat.util.FileUtils;
import com.vegardit.copycat.util.JdkLoggingUtils;
import com.vegardit.copycat.util.YamlUtils;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.hashing.FileHash;
import io.methvin.watcher.hashing.FileHasher;
import io.methvin.watcher.visitor.FileTreeVisitor;
import net.sf.jstuff.core.collection.Sets;
import net.sf.jstuff.core.io.MoreFiles;
import net.sf.jstuff.core.io.Size;
import net.sf.jstuff.core.logging.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
@CommandLine.Command(name = "watch", //
   description = "Continuously watches a directory recursively for changes and synchronizes them to another directory." //
)
public class WatchCommand extends AbstractSyncCommand<WatchCommandConfig> {

   private static final long FORCE_SHUTDOWN_TIMEOUT_MILLIS = 30_000;

   /**
    * custom visitor that WatchCommandConfig excludes into account
    */
   private final class CustomFileTreeVisitor implements FileTreeVisitor {

      private final WatchCommandConfig task;

      CustomFileTreeVisitor(final WatchCommandConfig task) {
         this.task = task;
      }

      @Override
      public void recursiveVisitFiles(final Path sourceRootAbsolute, final Callback onDirectory, final Callback onFile) throws IOException {
         Files.walkFileTree(sourceRootAbsolute, new FileVisitor<Path>() {

            private boolean isIgnoreSourcePath(final Path sourceAbsolute) throws IOException {
               if (sourceRootAbsolute.equals(sourceAbsolute))
                  return false;
               final var filterCtx = filterContexts.get(task);
               if (filterCtx != null) {
                  final var sourceRelative = sourceAbsolute.subpath(sourceRootAbsolute.getNameCount(), sourceAbsolute.getNameCount());
                  final var attrs = FileAttrs.get(sourceAbsolute);
                  if (!FilterEngine.includesSource(filterCtx, sourceAbsolute, sourceRelative, attrs)) {
                     LOG.debug("Ignoring %s", sourceAbsolute);
                     return true;
                  }
               }
               return false;
            }

            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
               if (isIgnoreSourcePath(dir))
                  return FileVisitResult.SKIP_SUBTREE;

               // respect optional max-depth and filter-based subtree excludes: only register/watch allowed directories
               if (!sourceRootAbsolute.equals(dir)) {
                  final Integer maxDepth = task.maxDepth;
                  final Path dirRelative = dir.subpath(sourceRootAbsolute.getNameCount(), dir.getNameCount());
                  if (maxDepth != null && dirRelative.getNameCount() > maxDepth)
                     return FileVisitResult.SKIP_SUBTREE;

                  if (task.isExcludedSourceSubtreeDir(dirRelative)) {
                     LOG.debug("Skipping subtree below %s due to filters", dirRelative);
                     return FileVisitResult.SKIP_SUBTREE;
                  }
               }

               onDirectory.call(dir);
               return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
               if (isIgnoreSourcePath(file))
                  return FileVisitResult.SKIP_SUBTREE;

               onFile.call(file);
               return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(final Path file, final @Nullable IOException ex) throws IOException {
               if (ex != null) {
                  LOG.error(ex);
               }
               return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final @Nullable IOException ex) throws IOException {
               if (ex != null) {
                  LOG.error(ex);
               }
               return FileVisitResult.CONTINUE;
            }
         });
      }
   }

   enum LogEvent {
      CREATE,
      MODIFY,
      DELETE
   }

   private static final Logger LOG = Logger.create();

   private final Set<LogEvent> loggableEvents = Sets.newHashSet(LogEvent.values());
   private final ConcurrentMap<WatchCommandConfig, FilterContext> filterContexts = new ConcurrentHashMap<>();
   private final ConcurrentMap<WatchCommandConfig, FilterContext> targetFilterContexts = new ConcurrentHashMap<>();
   private final ConcurrentMap<WatchCommandConfig, Set<Path>> preparedTargetDirsByTask = new ConcurrentHashMap<>();
   private final ConcurrentMap<WatchCommandConfig, Set<Path>> preparedParentDirsRelativeByTask = new ConcurrentHashMap<>();
   private final ConcurrentMap<WatchCommandConfig, ConcurrentMap<Path, FileHash>> sourceFileHashesByTask = new ConcurrentHashMap<>();

   private Set<Path> preparedTargetDirs(final WatchCommandConfig task) {
      return preparedTargetDirsByTask.computeIfAbsent(task, t -> ConcurrentHashMap.newKeySet());
   }

   private Set<Path> preparedParentDirsRelative(final WatchCommandConfig task) {
      return preparedParentDirsRelativeByTask.computeIfAbsent(task, t -> ConcurrentHashMap.newKeySet());
   }

   private ConcurrentMap<Path, FileHash> sourceFileHashes(final WatchCommandConfig task) {
      return sourceFileHashesByTask.computeIfAbsent(task, t -> new ConcurrentHashMap<>());
   }

   private void invalidatePreparedDirs(final WatchCommandConfig task, final Path relativeDir) {
      if (relativeDir.getNameCount() == 0) {
         preparedTargetDirs(task).clear();
         preparedParentDirsRelative(task).clear();
         return;
      }

      final Path targetDir = task.targetRootAbsolute.resolve(relativeDir);
      preparedTargetDirs(task).removeIf(path -> path.startsWith(targetDir));
      preparedParentDirsRelative(task).removeIf(path -> path.startsWith(relativeDir));
   }

   public WatchCommand() {
      super(WatchCommandConfig::new);
   }

   private SyncHelpers.Context syncContext(final WatchCommandConfig task, final boolean logCreate, final boolean logModify) {
      return new SyncHelpers.Context( // CHECKSTYLE:IGNORE .*
         logCreate, // logCreate
         logModify, // logModify
         false, // logDelete
         false, // dryRun
         true, // ignoreSymlinkErrors
         isTrue(task.copyACL), // copyACL
         isTrue(task.allowReadingOpenFiles), // allowReadingOpenFiles
         null, // stats
         null // progress
      );
   }

   @Override
   protected void doExecute(final List<WatchCommandConfig> tasks) throws Exception {

      if (tasks.isEmpty())
         throw new CommandLine.ParameterException(commandSpec.commandLine(), "No watch tasks configured.");

      DesktopNotifications.setTrayIconToolTip("copycat is watching...");

      final var threadPool = Executors.newFixedThreadPool(tasks.size(), //
         BasicThreadFactory.builder().namingPattern("watch-%d").build() //
      );

      final var completion = new ExecutorCompletionService<@Nullable Void>(threadPool);
      final var watchers = new ArrayList<DirectoryWatcher>(tasks.size());

      @Nullable
      Throwable failure = null;
      try {
         for (final var task : tasks) {
            final var filterCtx = task.toSourceFilterContext();
            filterContexts.put(task, filterCtx);
            final var targetFilterCtx = task.toTargetFilterContext();
            targetFilterContexts.put(task, targetFilterCtx);
            preparedTargetDirsByTask.put(task, ConcurrentHashMap.newKeySet());
            preparedParentDirsRelativeByTask.put(task, ConcurrentHashMap.newKeySet());
            sourceFileHashesByTask.put(task, new ConcurrentHashMap<>());

            JdkLoggingUtils.withRootLogLevel(Level.INFO, () -> {
               LOG.info("Effective Config:\n%s", YamlUtils.toYamlString(task));
            });

            if (!Files.exists(task.targetRootAbsolute, NOFOLLOW_LINKS)) {
               if (loggableEvents.contains(LogEvent.CREATE)) {
                  LOG.info("NEW [@|magenta %s%s|@]...", task.targetRootAbsolute, File.separator);
               }
               FileUtils.copyDirShallow(task.sourceRootAbsolute, task.targetRootAbsolute, isTrue(task.copyACL));
            }

            LOG.info("Preparing watching of [%s]...", task.sourceRootAbsolute);
            final var watcher = DirectoryWatcher.builder() //
               .path(task.sourceRootAbsolute) //
               .fileHashing(false) // disable file hashing which takes too much time on large trees during initialization
               .fileTreeVisitor(new CustomFileTreeVisitor(task)) //
               .listener(event -> onFileChanged(task, event)) //
               .build();
            watchers.add(watcher);

            completion.submit(() -> {
               watcher.watch();
               if (!Files.exists(task.sourceRootAbsolute))
                  throw new IllegalStateException("Directory: [" + task.sourceRootAbsolute + "] does not exist anymore!");
               return null;
            });
         }

         LOG.info("Now watching...");

         threadPool.shutdown();

         // Block until any watcher thread ends (normally or exceptionally). Exceptions from watcher threads
         // are otherwise captured by the Future and would never be surfaced.
         final Future<@Nullable Void> firstCompleted = completion.take();
         firstCompleted.get();
         throw new IllegalStateException("Watcher stopped unexpectedly.");
      } catch (final Throwable t) { // CHECKSTYLE:IGNORE .*
         final Throwable primary;
         if (t instanceof final ExecutionException ex) {
            final Throwable cause = ex.getCause();
            primary = cause == null ? ex : cause;
         } else {
            primary = t;
         }
         failure = primary;
         if (primary instanceof InterruptedException) {
            Thread.currentThread().interrupt();
         }
         if (primary instanceof final Exception ex)
            throw ex;
         if (primary instanceof final Error ex)
            throw ex;
         throw new RuntimeException(primary);
      } finally {
         for (final var watcher : watchers) {
            try {
               watcher.close();
            } catch (final IOException ex) {
               if (failure != null) {
                  failure.addSuppressed(ex);
               } else {
                  LOG.warn("Failed to close directory watcher: %s", ex.toString());
               }
            }
         }
         threadPool.shutdownNow();
         try {
            if (!threadPool.awaitTermination(FORCE_SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
               LOG.warn("Watcher thread pool did not terminate within %sms.", FORCE_SHUTDOWN_TIMEOUT_MILLIS);
            }
         } catch (final InterruptedException ex) {
            if (failure != null) {
               failure.addSuppressed(ex);
            }
            Thread.currentThread().interrupt();
         }
      }
   }

   private void onFileChanged(final WatchCommandConfig task, final DirectoryChangeEvent event) {
      try {
         final var filterCtx = filterContexts.get(task);

         final Path sourceAbsolute = asNonNull(event.path());
         final Path sourceRelative;
         try {
            sourceRelative = task.sourceRootAbsolute.relativize(sourceAbsolute);
         } catch (final IllegalArgumentException ex) {
            LOG.warn("Ignoring event outside watched root [%s]: %s", task.sourceRootAbsolute, sourceAbsolute);
            return;
         }

         final var eventType = event.eventType();
         final @Nullable FileAttrs sourceAttrs;
         final boolean isDirEvent;
         if (eventType == DirectoryChangeEvent.EventType.CREATE || eventType == DirectoryChangeEvent.EventType.MODIFY) {
            sourceAttrs = FileAttrs.get(sourceAbsolute);
            isDirEvent = switch (sourceAttrs.type()) {
               case DIRECTORY, DIRECTORY_SYMLINK -> true;
               default -> false;
            };
         } else {
            sourceAttrs = null;
            isDirEvent = false;
         }

         // enforce optional max-depth on incoming events as a safety net
         final Integer maxDepth = task.maxDepth;
         if (maxDepth != null) {
            final int depth = sourceRelative.getNameCount();
            final int allowedDepth = isDirEvent ? maxDepth : maxDepth + 1;
            if (depth > allowedDepth) {
               LOG.trace("Ignoring event outside max-depth [%s]: %s", maxDepth, sourceRelative);
               return;
            }
         }

         final var targetAbsolute = task.targetRootAbsolute.resolve(sourceRelative);

         switch (eventType) {
            case CREATE:
               assert sourceAttrs != null;
               if (isDirEvent) {
                  if (filterCtx != null && !FilterEngine.includesSource(filterCtx, sourceAbsolute, sourceRelative, sourceAttrs)) {
                     LOG.debug("Ignoring CREATE of %s", sourceAbsolute);
                     return;
                  }
                  if (loggableEvents.contains(LogEvent.CREATE)) {
                     LOG.info("CREATE [@|magenta %s%s|@]...", sourceRelative, File.separator);
                  }
                  if (sourceAttrs.type() == FileAttrs.Type.DIRECTORY_SYMLINK) {
                     prepareParentDirsForIncludedFile(task, sourceRelative);
                     final Path existingTarget = Files.exists(targetAbsolute, NOFOLLOW_LINKS) ? targetAbsolute : null;
                     SyncHelpers.ensureDir(syncContext(task, false, false), sourceAbsolute, existingTarget, targetAbsolute, sourceRelative);
                  } else if (task.fileFilters == null || asNonNull(task.fileFilters).isEmpty() //
                        || filterCtx != null && FilterEngine.isDirExplicitlyIncluded(filterCtx, sourceRelative)) {
                     ensureTargetDirPrepared(task, sourceAbsolute, sourceRelative);
                  }
               } else {
                  if (filterCtx != null && !FilterEngine.includesSource(filterCtx, sourceAbsolute, sourceRelative, sourceAttrs)) {
                     LOG.debug("Ignoring CREATE of %s", sourceAbsolute);
                     return;
                  }
                  if (isSymlinkLeaf(sourceAttrs.type())) {
                     if (loggableEvents.contains(LogEvent.CREATE)) {
                        logSymlinkEvent("CREATE", sourceAbsolute, sourceRelative);
                     }
                     prepareParentDirsForIncludedFile(task, sourceRelative);
                     syncSymlinkLeaf(task, sourceAbsolute, targetAbsolute, sourceRelative);
                  } else {
                     if (loggableEvents.contains(LogEvent.CREATE)) {
                        LOG.info("CREATE [@|magenta %s|@] %s...", sourceRelative, Size.ofBytes(Files.size(sourceAbsolute)));
                     }

                     prepareParentDirsForIncludedFile(task, sourceRelative);
                     syncFile(task, sourceAbsolute, targetAbsolute, true);
                  }
               }
               break;

            case MODIFY:
               assert sourceAttrs != null;
               if (isDirEvent) {
                  if (filterCtx != null && !FilterEngine.includesSource(filterCtx, sourceAbsolute, sourceRelative, sourceAttrs)) {
                     LOG.debug("Ignoring MODIFY of %s", sourceAbsolute);
                     return;
                  }
                  if (loggableEvents.contains(LogEvent.MODIFY)) {
                     LOG.info("MODIFY [@|magenta %s%s|@]...", sourceRelative, File.separator);
                  }
                  if (sourceAttrs.type() == FileAttrs.Type.DIRECTORY_SYMLINK) {
                     prepareParentDirsForIncludedFile(task, sourceRelative);
                     final Path existingTarget = Files.exists(targetAbsolute, NOFOLLOW_LINKS) ? targetAbsolute : null;
                     SyncHelpers.ensureDir(syncContext(task, false, false), sourceAbsolute, existingTarget, targetAbsolute, sourceRelative);
                  } else if (task.fileFilters == null || asNonNull(task.fileFilters).isEmpty() //
                        || filterCtx != null && FilterEngine.isDirExplicitlyIncluded(filterCtx, sourceRelative)) {
                     ensureTargetDirPrepared(task, sourceAbsolute, sourceRelative);
                  }
               } else {
                  if (filterCtx != null && !FilterEngine.includesSource(filterCtx, sourceAbsolute, sourceRelative, sourceAttrs)) {
                     LOG.debug("Ignoring MODIFY of %s", sourceAbsolute);
                     return;
                  }
                  if (isSymlinkLeaf(sourceAttrs.type())) {
                     if (loggableEvents.contains(LogEvent.MODIFY)) {
                        logSymlinkEvent("MODIFY", sourceAbsolute, sourceRelative);
                     }
                     prepareParentDirsForIncludedFile(task, sourceRelative);
                     syncSymlinkLeaf(task, sourceAbsolute, targetAbsolute, sourceRelative);
                  } else {
                     if (loggableEvents.contains(LogEvent.MODIFY)) {
                        LOG.info("MODIFY [@|magenta %s|@] %s...", sourceRelative, Size.ofBytes(Files.size(sourceAbsolute)));
                     }

                     // compare file hashes to avoid unnecessary file copying
                     final var sourceFileHashNew = FileHasher.DEFAULT_FILE_HASHER.hash(sourceAbsolute);
                     final var sourceFileHashOld = sourceFileHashes(task).put(sourceAbsolute, sourceFileHashNew);
                     final boolean contentChanged = !sourceFileHashNew.equals(sourceFileHashOld);
                     if (contentChanged) {
                        prepareParentDirsForIncludedFile(task, sourceRelative);
                     }
                     syncFile(task, sourceAbsolute, targetAbsolute, contentChanged);
                  }
               }
               break;

            case DELETE:
               if (Files.exists(targetAbsolute, NOFOLLOW_LINKS)) {
                  final var targetAttrs = FileAttrs.get(targetAbsolute);
                  if (not(task.deleteExcluded)) {
                     final var targetFilterCtx = targetFilterContexts.get(task);
                     if (targetFilterCtx != null) {
                        final boolean isIncludedTarget = FilterEngine.includesSource(targetFilterCtx, targetAbsolute, sourceRelative,
                           targetAttrs);
                        if (!isIncludedTarget) {
                           break;
                        }
                     }
                  }
                  final boolean targetDirLike = targetAttrs.isDir() || targetAttrs.isDirSymlink();
                  final var deleteCtx = syncContext(task, false, false);
                  if (targetAttrs.type() == FileAttrs.Type.DIRECTORY) {
                     if (loggableEvents.contains(LogEvent.DELETE)) {
                        LOG.info("DELETE [@|magenta %s%s|@]...", sourceRelative, File.separator);
                     }
                     SyncHelpers.deleteDir(deleteCtx, targetAbsolute);
                  } else {
                     if (loggableEvents.contains(LogEvent.DELETE)) {
                        LOG.info("DELETE [@|magenta %s|@]...", sourceRelative);
                     }
                     sourceFileHashes(task).remove(sourceAbsolute);
                     SyncHelpers.deleteFile(deleteCtx, targetAbsolute, targetAttrs, true);
                  }
                  if (targetDirLike) {
                     invalidatePreparedDirs(task, sourceRelative);
                  }
               }
               break;

            case OVERFLOW:
               LOG.warn("Filesystem event overflow encountered!");
         }
      } catch (final Exception ex) {
         LOG.error(ex);
      }
   }

   @Option(names = "--no-log", paramLabel = "<op>", split = ",", //
      description = "Don't log the given filesystem operation. Valid values: ${COMPLETION-CANDIDATES}")
   private void setNoLog(final LogEvent[] values) {
      for (final var val : values) {
         loggableEvents.remove(val);
      }
   }

   private void prepareParentDirsForIncludedFile(final WatchCommandConfig task, final Path fileRelative) throws IOException {
      final Path parentRelative = fileRelative.getParent();
      if (parentRelative == null)
         // file directly under root
         return;

      // If the immediate parent is already known to be prepared (and thus its ancestors as well),
      // we can skip rebuilding and walking the full parent chain.
      final var preparedParents = preparedParentDirsRelative(task);
      if (preparedParents.contains(parentRelative)) {
         final Path targetParent = task.targetRootAbsolute.resolve(parentRelative);
         final var targetAttrs = FileAttrs.find(targetParent);
         if (targetAttrs != null && (targetAttrs.isDir() || targetAttrs.isDirSymlink()))
            return;
         preparedParents.remove(parentRelative);
      }

      final var parents = new java.util.ArrayDeque<Path>();
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

   private void ensureTargetDirPrepared(final WatchCommandConfig task, final Path sourceDir, final Path dirRelative) throws IOException {
      final Path targetDir = task.targetRootAbsolute.resolve(dirRelative);
      final var preparedDirs = preparedTargetDirs(task);
      if (preparedDirs.contains(targetDir)) {
         final var targetAttrs = FileAttrs.find(targetDir);
         if (targetAttrs != null && (targetAttrs.isDir() || targetAttrs.isDirSymlink()))
            return;
         preparedDirs.remove(targetDir);
      }
      preparedDirs.add(targetDir);

      preparedParentDirsRelative(task).add(dirRelative);

      final Path existingTarget = Files.exists(targetDir, NOFOLLOW_LINKS) ? targetDir : null;
      SyncHelpers.ensureDir(syncContext(task, loggableEvents.contains(LogEvent.CREATE), false), sourceDir, existingTarget, targetDir,
         dirRelative);
   }

   private void syncFile(final WatchCommandConfig cfg, final Path sourcePath, final Path targetPath, final boolean contentChanged)
         throws IOException {
      final var sourceAttrs = MoreFiles.readAttributes(sourcePath);
      final var syncCtx = syncContext(cfg, false, false);

      final boolean targetExists;
      if (Files.exists(targetPath, NOFOLLOW_LINKS)) {
         final var targetAttrs = FileAttrs.get(targetPath);
         switch (targetAttrs.type()) {
            case FILE, FILE_SYMLINK, BROKEN_SYMLINK, OTHER_SYMLINK -> {
               if (sourceAttrs.isSymbolicLink() != targetAttrs.isSymlink()) { // one is symlink
                  if (sourceAttrs.isSymbolicLink()) {
                     LOG.debug("Deleting target [@|magenta %s|@] because source is symlink and target is not...", targetPath);
                  } else {
                     LOG.debug("Deleting target [@|magenta %s|@] because target is symlink and source is not...", targetPath);
                  }
                  SyncHelpers.deleteFile(syncCtx, targetPath, targetAttrs, true);
                  targetExists = false;
               } else {
                  targetExists = true;
               }
            }
            case OTHER -> {
               LOG.debug("Deleting target [@|magenta %s|@] because source is file...", targetPath);
               SyncHelpers.deleteFile(syncCtx, targetPath, targetAttrs, true);
               targetExists = false;
            }
            case DIRECTORY, DIRECTORY_SYMLINK -> {
               LOG.debug("Deleting target directory [@|magenta %s|@] because source is file...", targetPath);
               if (targetAttrs.isSymlink()) {
                  SyncHelpers.deleteFile(syncCtx, targetPath, targetAttrs, true);
                  targetExists = false;
               } else {
                  SyncHelpers.deleteDir(syncCtx, targetPath);
                  targetExists = false;
               }
            }
            default -> throw new IllegalStateException("Unknown type [" + targetAttrs.type() + "] of target [" + targetPath + "].");
         }
      } else {
         targetExists = false;
      }

      if (contentChanged || !targetExists) {
         SyncHelpers.copyFile(syncCtx, sourcePath, sourceAttrs, targetPath);
      } else {
         FileUtils.copyAttributes(sourcePath, sourceAttrs, targetPath, isTrue(cfg.copyACL), isTrue(cfg.allowReadingOpenFiles));
      }
   }

   private static boolean isSymlinkLeaf(final FileAttrs.Type type) {
      return switch (type) {
         case FILE_SYMLINK, BROKEN_SYMLINK, OTHER_SYMLINK -> true;
         default -> false;
      };
   }

   private void syncSymlinkLeaf(final WatchCommandConfig task, final Path sourcePath, final Path targetPath, final Path relativePath)
         throws IOException {
      final Path existingTarget = Files.exists(targetPath, NOFOLLOW_LINKS) ? targetPath : null;
      SyncHelpers.syncSymlinkLeaf(syncContext(task, false, false), sourcePath, existingTarget, targetPath, relativePath, FileAttrs.get(
         sourcePath));
   }

   private void logSymlinkEvent(final String action, final Path sourceAbsolute, final Path sourceRelative) {
      try {
         LOG.info("%s [@|magenta %s -> %s|@]...", action, sourceRelative, Files.readSymbolicLink(sourceAbsolute));
      } catch (final IOException ex) {
         LOG.info("%s [@|magenta %s|@]...", action, sourceRelative);
      }
   }
}
