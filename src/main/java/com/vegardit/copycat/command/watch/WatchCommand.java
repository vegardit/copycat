/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.watch;

import static com.vegardit.copycat.util.Booleans.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.eclipse.jdt.annotation.Nullable;

import com.vegardit.copycat.command.sync.AbstractSyncCommand;
import com.vegardit.copycat.util.DesktopNotifications;
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

   /**
    * custom visitor that WatchCommandConfig excludes into account
    */
   private static final class CustomFileTreeVisitor implements FileTreeVisitor {

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
               final var sourceRelative = sourceAbsolute.subpath(sourceRootAbsolute.getNameCount(), sourceAbsolute.getNameCount());
               if (task.isExcludedSourcePath(sourceAbsolute, sourceRelative)) {
                  LOG.debug("Ignoring %s", sourceAbsolute);
                  return true;
               }
               return false;
            }

            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
               if (isIgnoreSourcePath(dir))
                  return FileVisitResult.SKIP_SUBTREE;

               // respect optional max-depth: only register/watch directories up to maxDepth
               if (!sourceRootAbsolute.equals(dir)) {
                  final Integer maxDepth = task.maxDepth;
                  if (maxDepth != null) {
                     final Path dirRelative = dir.subpath(sourceRootAbsolute.getNameCount(), dir.getNameCount());
                     if (dirRelative.getNameCount() > maxDepth.intValue())
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

   public WatchCommand() {
      super(WatchCommandConfig::new);
   }

   private void delDir(final Path dir) throws IOException {
      Files.walkFileTree(dir, new SimpleFileVisitor<>() {
         @Override
         public FileVisitResult postVisitDirectory(final Path dir, final @Nullable IOException exc) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
         }
      });
   }

   private void delFile(final Path file) throws IOException {
      Files.delete(file);
   }

   @Override
   protected void doExecute(final List<WatchCommandConfig> tasks) throws Exception {

      DesktopNotifications.setTrayIconToolTip("copycat is watching...");

      final var threadPool = Executors.newFixedThreadPool(tasks.size(), //
         BasicThreadFactory.builder().namingPattern("sync-%d").build() //
      );

      for (final var task : tasks) {
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

         threadPool.submit(() -> {
            watcher.watch();
            if (!Files.exists(task.sourceRootAbsolute))
               throw new IllegalStateException("Directory: [" + task.sourceRootAbsolute + "] does not exist anymore!");
         });
      }

      LOG.info("Now watching...");

      threadPool.shutdown();
      threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
   }

   private ConcurrentMap<Path, FileHash> sourceFileHashes = new ConcurrentHashMap<>();

   private void onFileChanged(final WatchCommandConfig task, final DirectoryChangeEvent event) {
      try {
         final var sourceAbsolute = event.path();
         final var sourceRelative = sourceAbsolute.subpath(task.sourceRootAbsolute.getNameCount(), sourceAbsolute.getNameCount());

         // enforce optional max-depth on incoming events as a safety net
         final Integer maxDepth = task.maxDepth;
         if (maxDepth != null) {
            final int maxEventDepth = maxDepth.intValue() + 1; // allow files in dirs at maxDepth and dirs at (maxDepth+1)
            if (sourceRelative.getNameCount() > maxEventDepth) {
               LOG.trace("Ignoring event outside max-depth [%s]: %s", maxDepth, sourceRelative);
               return;
            }
         }

         if (task.isExcludedSourcePath(sourceAbsolute, sourceRelative)) {
            LOG.debug("Ignoring %s of s%s", event.eventType(), sourceAbsolute);
            return;
         }

         final var targetAbsolute = task.targetRootAbsolute.resolve(sourceRelative);

         switch (event.eventType()) {
            case CREATE:
               if (Files.isDirectory(sourceAbsolute, NOFOLLOW_LINKS)) {
                  if (loggableEvents.contains(LogEvent.CREATE)) {
                     LOG.info("CREATE [@|magenta %s\\|@]...", sourceRelative);
                  }
                  syncDirShallow(task, sourceAbsolute, targetAbsolute);
               } else {
                  if (loggableEvents.contains(LogEvent.CREATE)) {
                     LOG.info("CREATE [@|magenta %s|@] %s...", sourceRelative, Size.ofBytes(Files.size(sourceAbsolute)));
                  }

                  syncFile(task, sourceAbsolute, targetAbsolute, true);
               }
               break;

            case MODIFY:
               if (Files.isDirectory(sourceAbsolute, NOFOLLOW_LINKS)) {
                  if (loggableEvents.contains(LogEvent.MODIFY)) {
                     LOG.info("MODIFY [@|magenta %s\\|@]...", sourceRelative);
                  }
                  syncDirShallow(task, sourceAbsolute, targetAbsolute);
               } else {
                  if (loggableEvents.contains(LogEvent.MODIFY)) {
                     LOG.info("MODIFY5 [@|magenta %s|@] %s...", sourceRelative, Size.ofBytes(Files.size(sourceAbsolute)));
                  }

                  // compare file hashes to avoid unnecessary file copying
                  final var sourceFileHashNew = FileHasher.DEFAULT_FILE_HASHER.hash(sourceAbsolute);
                  final var sourceFileHashOld = sourceFileHashes.put(sourceAbsolute, sourceFileHashNew);
                  syncFile(task, sourceAbsolute, targetAbsolute, !sourceFileHashNew.equals(sourceFileHashOld));
               }
               break;

            case DELETE:
               if (not(task.deleteExcluded) && task.isExcludedTargetPath(targetAbsolute, sourceRelative)) {
                  break;
               }
               if (Files.exists(targetAbsolute, NOFOLLOW_LINKS)) {
                  if (Files.isDirectory(targetAbsolute, NOFOLLOW_LINKS)) {
                     if (loggableEvents.contains(LogEvent.DELETE)) {
                        LOG.info("DELETE [@|magenta %s\\|@]...", sourceRelative);
                     }
                     delDir(targetAbsolute);
                  } else {
                     if (loggableEvents.contains(LogEvent.DELETE)) {
                        LOG.info("DELETE [@|magenta %s|@]...", sourceRelative);
                     }
                     sourceFileHashes.remove(sourceAbsolute);
                     delFile(targetAbsolute);
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

   private void syncDirShallow(final WatchCommandConfig cfg, final Path sourcePath, final Path targetPath) throws IOException {
      final var sourceAttrs = Files.readAttributes(sourcePath, BasicFileAttributes.class, NOFOLLOW_LINKS);

      if (Files.exists(targetPath, NOFOLLOW_LINKS)) {
         if (Files.isRegularFile(targetPath)) {
            LOG.debug("Deleting target file [@|magenta %s|@] because source is directory...", targetPath);
            delFile(targetPath);
         } else {
            final var targetEntryIsSymlink = Files.isSymbolicLink(targetPath);

            if (sourceAttrs.isSymbolicLink() == targetEntryIsSymlink)
               // both are either symlink or directory, thus nothing to do
               return;

            if (targetEntryIsSymlink) {
               LOG.debug("Deleting target [@|magenta %s|@] because target is symlink and source is not...", targetPath);
               delFile(targetPath);
            } else {
               LOG.debug("Deleting target [@|magenta %s|@] because source is symlink and target is not...", targetPath);
               delDir(targetPath);
            }
         }
      }

      if (sourceAttrs.isSymbolicLink()) {
         try {
            Files.copy(sourcePath, targetPath, SYMLINK_COPY_OPTIONS);
         } catch (final FileSystemException ex) {
            LOG.error("Symlink creation failed:" + ex.getMessage(), ex);
         }
      } else {
         FileUtils.copyDirShallow(sourcePath, sourceAttrs, targetPath, isTrue(cfg.copyACL));
      }
   }

   private void syncFile(final WatchCommandConfig cfg, final Path sourcePath, final Path targetPath, final boolean contentChanged)
         throws IOException {
      final var sourceAttrs = MoreFiles.readAttributes(sourcePath);

      final boolean targetExists;
      if (Files.exists(targetPath, NOFOLLOW_LINKS)) {
         /*
          * target path points to file
          */
         if (Files.isRegularFile(targetPath)) {
            final var targetAttrs = Files.readAttributes(targetPath, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (sourceAttrs.isSymbolicLink() != targetAttrs.isSymbolicLink()) { // one is symlink
               if (sourceAttrs.isSymbolicLink()) {
                  LOG.debug("Deleting target [@|magenta %s|@] because source is symlink and target is not...", targetPath);
               } else {
                  LOG.debug("Deleting target [@|magenta %s|@] because target is symlink and source is not...", targetPath);
               }
               delFile(targetPath);
               targetExists = false;
            } else {
               targetExists = true;
            }

            /*
             * target path points to directory
             */
         } else {
            LOG.debug("Deleting target directory [@|magenta %s|@] because source is file...", targetPath);
            if (Files.isSymbolicLink(targetPath)) {
               delFile(targetPath);
               targetExists = false;
            } else {
               delDir(targetPath);
               targetExists = false;
            }
         }
      } else {
         targetExists = false;
      }

      if (contentChanged || !targetExists) {
         FileUtils.copyFile(sourcePath, sourceAttrs, targetPath, isTrue(cfg.copyACL), (bytesWritten, totalBytesWritten) -> { /**/ });
      } else {
         FileUtils.copyAttributes(sourcePath, sourceAttrs, targetPath, isTrue(cfg.copyACL));
      }
   }
}
