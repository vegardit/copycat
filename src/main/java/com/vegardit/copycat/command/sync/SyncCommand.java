/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import java.awt.TrayIcon.MessageType;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.mutable.MutableLong;

import com.vegardit.copycat.util.DesktopNotifications;
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

   private static final Logger LOG = Logger.create();

   private final Set<LogEvent> loggableEvents = Sets.newHashSet(LogEvent.values());
   private final AtomicInteger threadsWaiting = new AtomicInteger();
   private final AtomicBoolean threadsDone = new AtomicBoolean();

   private final SyncStats stats = new SyncStats();
   private Queue<Path> sourceDirsToScan;
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
         public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
            if (!task.dryRun) {
               Files.delete(dir);
            }
            filesDeleted.increment();
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
            if (!task.dryRun) {
               Files.delete(file);
            }
            filesDeleted.increment();
            filesDeletedSize.add(attrs.size());
            return FileVisitResult.CONTINUE;
         }
      });
      stats.onDirDeleted(System.currentTimeMillis() - start, filesDeleted.longValue(), filesDeletedSize.longValue());
   }

   private void delFile(final SyncCommandConfig task, final Path file, final boolean count) throws IOException {
      final var fileSize = Files.size(file); // CHECKSTYLE:IGNORE MoveVariableInsideIfCheck
      final var start = System.currentTimeMillis(); // CHECKSTYLE:IGNORE MoveVariableInsideIfCheck
      if (!task.dryRun) {
         Files.delete(file);
      }
      if (count) {
         stats.onFileDeleted(System.currentTimeMillis() - start, fileSize);
      }
   }

   @Override
   protected void doExecute(final List<SyncCommandConfig> tasks) throws Exception {
      DesktopNotifications.setTrayIconToolTip("copycat is syncing...");

      stats.start();

      try {
         for (final var task : tasks) {
            JdkLoggingUtils.withRootLogLevel(Level.INFO, () -> {
               LOG.info("Effective Config:\n%s", YamlUtils.toYamlString(task));
            });

            if (task.threads < 0 || task.threads == 1) {
               task.threads = 1;
               sourceDirsToScan = new ArrayDeque<>();
            } else {
               sourceDirsToScan = new ConcurrentLinkedDeque<>();
            }
            LOG.info("Working hard using %s thread(s)%s...", task.threads, task.dryRun ? " (DRY RUN)" : "");

            if (!Files.exists(task.targetRootAbsolute, NOFOLLOW_LINKS)) {
               if (loggableEvents.contains(LogEvent.CREATE)) {
                  LOG.info("NEW [@|magenta %s%s|@]...", task.targetRootAbsolute, File.separator);
               }
               FileUtils.copyDirShallow(task.sourceRootAbsolute, task.targetRootAbsolute, task.copyACL);
            }

            sourceDirsToScan.add(task.sourceRootAbsolute);

            /*
             * start syncing
             */
            DesktopNotifications.showTransient(MessageType.INFO, "Syncing started...", //
               "FROM: " + task.sourceRootAbsolute + "\nTO: " + task.targetRootAbsolute);
            if (task.threads == 1) {
               sync(task);
            } else {
               final var threadPool = Executors.newFixedThreadPool(task.threads, //
                  new BasicThreadFactory.Builder().namingPattern("sync-%d").build() //
               );
               // CHECKSTYLE:IGNORE .* FOR NEXT LINE
               final var ex = MutableRef.of((Exception) null);
               for (var i = 0; i < task.threads; i++) {
                  threadPool.submit(() -> {
                     try {
                        sync(task);
                     } catch (final Exception e) {
                        ex.set(e);
                     }
                  });
               }
               threadPool.shutdown();
               threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
               if (ex.isNotNull()) {
                  DesktopNotifications.showSticky(MessageType.ERROR, "Syncing failed.", //
                     "ERROR: " + ex.get().getMessage() + "\nFROM: " + task.sourceRootAbsolute + "\nTO: " + task.targetRootAbsolute);
                  throw ex.get();
               }

               DesktopNotifications.showSticky(MessageType.INFO, "Syncing done.", //
                  "FROM: " + task.sourceRootAbsolute + "\nTO: " + task.targetRootAbsolute);
            }
         }
      } finally {
         JdkLoggingUtils.withRootLogLevel(Level.INFO, stats::logStats);
      }
   }

   private Path getNextSourceEntry(final SyncCommandConfig task) {
      var entry = sourceDirsToScan.poll();
      if (entry != null)
         return entry;

      if (task.threads > 1) {
         threadsWaiting.incrementAndGet();
         try {
            while (!threadsDone.get()) {
               entry = sourceDirsToScan.poll();
               if (entry != null)
                  return entry;

               Threads.sleep(100);

               // if all threads are waiting for a new entry we scanned all files
               if (threadsWaiting.get() == task.threads) {
                  threadsDone.set(true);
                  return null;
               }
            }
         } finally {
            threadsWaiting.decrementAndGet();
         }
      }
      return null;
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

   @Option(names = "--dry-run", defaultValue = "false", description = "Don't perform actual synchronization.")
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

   private void sync(final SyncCommandConfig task) throws IOException {
      final var sourceChildren = new HashMap<Path, Path>(); // Map<SourcePathRelativeToRoot, SourcePathAbsolute>
      final var targetChildren = new ConcurrentHashMap<Path, Path>(); // Map<TargetPathRelativeToRoot, TargetPathAbsolute>

      while (state == State.NORMAL) {
         final var source = getNextSourceEntry(task);
         if (source == null) {
            break;
         }

         try {
            final Path target;
            final Path sourceRelative;
            if (source.equals(task.sourceRootAbsolute)) { // is root ?
               target = task.targetRootAbsolute;
               sourceRelative = Paths.get(".");
            } else {
               sourceRelative = source.subpath(task.sourceRootAbsolute.getNameCount(), source.getNameCount());
               target = task.targetRootAbsolute.resolve(sourceRelative);
            }

            if (loggableEvents.contains(LogEvent.SCAN)) {
               LOG.info("Scanning [@|magenta %s%s|@]...", sourceRelative, File.separator);
            }

            /*
             * read direct children of source dir
             */
            sourceChildren.clear();
            try (var ds = Files.newDirectoryStream(source)) {
               ds.forEach(child -> sourceChildren //
                  .put(child.subpath(task.sourceRootAbsolute.getNameCount(), child.getNameCount()), child));
            }

            /*
             * read direct children of target dir
             */
            targetChildren.clear();
            if (!(task.dryRun && !Files.exists(target))) { // in dry run mode the target directory may not exist
               try (var ds = Files.newDirectoryStream(target)) {
                  ds.forEach(child -> targetChildren //
                     .put(child.subpath(task.targetRootAbsolute.getNameCount(), child.getNameCount()), child));
               }
            }

            /*
             * remove extraneous entries in target dir
             */
            if (task.delete) {
               for (final var targetChildEntry : targetChildren.entrySet()) {
                  if (state != State.NORMAL) {
                     break;
                  }

                  final var targetChildRelative = targetChildEntry.getKey();
                  final var targetChildAbsolute = targetChildEntry.getValue();

                  final boolean existsInSource = sourceChildren.containsKey(targetChildRelative);
                  final boolean isExcludedFromSync = task.isExcludedTargetPath(targetChildAbsolute, targetChildRelative);

                  final boolean needRemoval;
                  if (existsInSource) {
                     needRemoval = isExcludedFromSync && task.deleteExcluded;
                  } else {
                     needRemoval = !isExcludedFromSync || isExcludedFromSync && task.deleteExcluded;
                  }

                  if (!needRemoval) {
                     continue;
                  }

                  if (loggableEvents.contains(LogEvent.DELETE)) {
                     LOG.info("DELETE [@|magenta %s|@]...", targetChildRelative);
                  }
                  if (Files.isDirectory(targetChildAbsolute, NOFOLLOW_LINKS)) {
                     delDir(task, targetChildAbsolute);
                  } else {
                     delFile(task, targetChildAbsolute, true);
                  }

                  // this requires targetChildEntries to be a ConcurrentHashMap
                  targetChildren.remove(targetChildRelative);
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

               if (task.isExcludedSourcePath(sourceChildAbsolute, sourceChildRelative)) {
                  continue;
               }

               if (Files.isRegularFile(sourceChildAbsolute)) {
                  syncFile(task, sourceChildAbsolute, targetChildAbsolute, sourceChildRelative);
                  stats.onFileScanned();
               } else {
                  syncDirShallow(task, sourceChildAbsolute, targetChildAbsolute, sourceChildRelative);
                  if (Files.isSymbolicLink(sourceChildAbsolute)) {
                     stats.onFileScanned();
                  } else {
                     sourceDirsToScan.add(sourceChildAbsolute);
                  }
               }
            }
         } catch (final IOException | RuntimeException ex) {
            stats.onError(ex);
            if (task.ignoreErrors) {
               if (getVerbosity() > 0) {
                  LOG.error(ex);
               } else {
                  LOG.error(ex.getClass().getSimpleName() + ": " + ex.getMessage());
               }
            } else {
               state = State.ABORT_BY_EXCEPTION;
               throw ex;
            }
         } finally {
            stats.onDirScanned();
         }
      }
   }

   /**
    * @param targetPath null, if target path does not exist yet
    */
   private void syncDirShallow(final SyncCommandConfig task, final Path sourcePath, final Path targetPath, final Path relativePath)
      throws IOException {
      final var sourceAttrs = Files.readAttributes(sourcePath, BasicFileAttributes.class, NOFOLLOW_LINKS);

      final Path resolvedTargetPath;
      if (targetPath == null) {
         resolvedTargetPath = task.targetRootAbsolute.resolve(relativePath);
      } else {
         if (Files.isRegularFile(targetPath)) {
            LOG.debug("Deleting target file [@|magenta %s|@] because source is directory...", relativePath);
            delFile(task, targetPath, true);
         } else {
            final var targetEntryIsSymlink = Files.isSymbolicLink(targetPath);

            if (sourceAttrs.isSymbolicLink() == targetEntryIsSymlink)
               // both are either symlink or directory, thus nothing to do
               return;

            if (targetEntryIsSymlink) {
               LOG.debug("Deleting target [@|magenta %s|@] because target is symlink and source is not...", targetPath);
               delFile(task, targetPath, true);
            } else {
               LOG.debug("Deleting target [@|magenta %s|@] because source is symlink and target is not...", targetPath);
               delDir(task, targetPath);
            }
         }
         resolvedTargetPath = targetPath;
      }

      final var start = System.currentTimeMillis();
      if (sourceAttrs.isSymbolicLink()) {
         if (loggableEvents.contains(LogEvent.CREATE)) {
            LOG.info("NEW [@|magenta %s -> %s%s|@]...", relativePath, Files.readSymbolicLink(sourcePath), File.separator);
         }
         try {
            if (!task.dryRun) {
               Files.copy(sourcePath, resolvedTargetPath, SYMLINK_COPY_OPTIONS);
            }
            stats.onFileCopied(System.currentTimeMillis() - start, sourceAttrs.size());
         } catch (final FileSystemException ex) {
            if (task.ignoreSymlinkErrors) {
               LOG.error("Symlink creation failed:" + ex.getMessage(), ex);
            } else
               throw ex;
         }
      } else {
         if (loggableEvents.contains(LogEvent.CREATE)) {
            LOG.info("NEW [@|magenta %s%s|@]...", relativePath, File.separator);
         }
         if (!task.dryRun) {
            FileUtils.copyDirShallow(sourcePath, sourceAttrs, resolvedTargetPath, task.copyACL);
         }
         stats.onFileCopied(System.currentTimeMillis() - start, sourceAttrs.size());
      }
   }

   /**
    * @param targetPath null, if target path does not exist yet
    */
   private void syncFile(final SyncCommandConfig task, final Path sourcePath, Path targetPath, final Path relativePath) throws IOException {
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
         if (Files.isRegularFile(targetPath)) {
            final var targetAttrs = Files.readAttributes(targetPath, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (sourceAttrs.isSymbolicLink() && targetAttrs.isSymbolicLink()) { // both are symlinks
               copyCause = null;

            } else if (sourceAttrs.isSymbolicLink() == targetAttrs.isSymbolicLink()) { // non are symlinks
               final var timeCompare = sourceAttrs.lastModifiedTime().compareTo(targetAttrs.lastModifiedTime());
               if (timeCompare > 0) {
                  copyCause = "NEWER";
               } else if (timeCompare < 0) {
                  if (task.excludeOlderFiles) {
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
                     copyCause = null;
                  }
               }

            } else { // one is symlink
               if (sourceAttrs.isSymbolicLink()) {
                  LOG.debug("Deleting target [@|magenta %s|@] because source is symlink and target is not...", targetPath);
               } else {
                  LOG.debug("Deleting target [@|magenta %s|@] because target is symlink and source is not...", targetPath);
               }
               delFile(task, targetPath, true);
               copyCause = "REPLACE";
            }

            /*
             * target path points to directory
             */
         } else {
            LOG.debug("Deleting target directory [@|magenta %s|@] because source is file...", targetPath);
            if (Files.isSymbolicLink(targetPath)) {
               delFile(task, targetPath, true);
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
         if (!task.dryRun) {
            FileUtils.copyFile(sourcePath, sourceAttrs, targetPath, task.copyACL, (bytesWritten, totalBytesWritten) -> { /**/ });
         }
         stats.onFileCopied(System.currentTimeMillis() - start, sourceAttrs.size());
      }
   }
}
