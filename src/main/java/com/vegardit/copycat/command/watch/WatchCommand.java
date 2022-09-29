/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.watch;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import com.vegardit.copycat.command.sync.AbstractSyncCommand;
import com.vegardit.copycat.util.FileUtils;

import io.methvin.watcher.DirectoryWatcher;
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
         public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
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
      final var threadPool = Executors.newFixedThreadPool(tasks.size(), //
         new BasicThreadFactory.Builder().namingPattern("sync-%d").build() //
      );

      for (final var task : tasks) {
         if (!Files.exists(task.targetRootAbsolute, NOFOLLOW_LINKS)) {
            if (loggableEvents.contains(LogEvent.CREATE)) {
               LOG.info("NEW [@|magenta %s%s|@]...", task.targetRootAbsolute, File.separator);
            }
            FileUtils.copyDirShallow(task.sourceRootAbsolute, task.targetRootAbsolute, task.copyACL);
         }

         final var watcher = DirectoryWatcher.builder() //
            .path(task.sourceRootAbsolute) //
            .listener(event -> {
               try {
                  final var sourceAbsolute = event.path();
                  final var sourceRelative = sourceAbsolute.subpath(task.sourceRootAbsolute.getNameCount(), sourceAbsolute.getNameCount());
                  final var targetAbsolute = task.targetRootAbsolute.resolve(sourceRelative);

                  if (task.isExcludedSourcePath(sourceAbsolute, sourceRelative)) {
                     LOG.debug("Ignoring %s of %s", event.eventType(), sourceRelative);
                     return;
                  }

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
                           syncFile(task, sourceAbsolute, targetAbsolute);
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
                              LOG.info("MODIFY [@|magenta %s|@] %s...", sourceRelative, Size.ofBytes(Files.size(sourceAbsolute)));
                           }
                           syncFile(task, sourceAbsolute, targetAbsolute);
                        }
                        break;

                     case DELETE:
                        if (!task.deleteExcluded && task.isExcludedTargetPath(targetAbsolute, sourceRelative)) {
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
            }) //
            .build();
         threadPool.submit(() -> {
            watcher.watch();
            if (!Files.exists(task.sourceRootAbsolute))
               throw new IllegalStateException("Directory: [" + task.sourceRootAbsolute + "] does not exist anymore!");
         });
      }
      threadPool.shutdown();
      threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
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
         FileUtils.copyDirShallow(sourcePath, sourceAttrs, targetPath, cfg.copyACL);
      }
   }

   private void syncFile(final WatchCommandConfig cfg, final Path sourcePath, final Path targetPath) throws IOException {
      final var sourceAttrs = MoreFiles.readAttributes(sourcePath);

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
            }

            /*
             * target path points to directory
             */
         } else {
            LOG.debug("Deleting target directory [@|magenta %s|@] because source is file...", targetPath);
            if (Files.isSymbolicLink(targetPath)) {
               delFile(targetPath);
            } else {
               delDir(targetPath);
            }
         }
      }
      FileUtils.copyFile(sourcePath, sourceAttrs, targetPath, cfg.copyACL, (bytesWritten, totalBytesWritten) -> { /**/ });
   }
}
