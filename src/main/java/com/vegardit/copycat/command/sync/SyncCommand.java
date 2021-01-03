/*
 * Copyright 2020 by Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import java.io.File;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.mutable.MutableLong;

import com.vegardit.copycat.command.AbstractCommand;
import com.vegardit.copycat.util.FileUtils;
import com.vegardit.copycat.util.JdkLoggingUtils;

import net.sf.jstuff.core.SystemUtils;
import net.sf.jstuff.core.collection.CollectionUtils;
import net.sf.jstuff.core.concurrent.Threads;
import net.sf.jstuff.core.io.MoreFiles;
import net.sf.jstuff.core.io.Size;
import net.sf.jstuff.core.logging.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
@CommandLine.Command(name = "sync", //
   description = "Performs one-way recursive directory synchronization copying new files/directories." //
)
public class SyncCommand extends AbstractCommand {

   enum State {
      NORMAL,
      ABORT_BY_EXCEPTION,
      ABORT_BY_SIGNAL
   }

   enum LogEvent {
      CREATE,
      REPLACE,
      DELETE,
      SCAN
   }

   private static final Logger LOG = Logger.create();

   private static final CopyOption[] DIR_COPY_OPTIONS = {StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS};
   private static final CopyOption[] FILE_COPY_OPTIONS = {StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING};
   private static final LinkOption[] NOFOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

   @Spec
   private CommandSpec spec;

   @Option(names = "--copy-acl", defaultValue = "false", description = "Copy file permissions (ACL) for newly copied files.")
   private boolean copyAcl;

   @Option(names = "--dry-run", defaultValue = "false", description = "Don't perform actual synchronization.")
   private boolean dryRun;

   @Option(names = "--delete", defaultValue = "false", description = "Delete extraneous files/directories from target.")
   private boolean delete;

   @Option(names = "--delete-excluded", defaultValue = "false", description = "Delete excluded files/directories from target.")
   private boolean deleteExcluded;

   @Option(names = "--exclude-hidden-files", defaultValue = "false", description = "Don't synchronize hidden files.")
   private boolean excludeHiddenFiles;

   @Option(names = "--exclude-system-files", defaultValue = "false", description = "Don't synchronize system files.")
   private boolean excludeSystemFiles;

   @Option(names = "--exclude-hidden-system-files", defaultValue = "false", description = "Don't synchronize hidden system files.")
   private boolean excludeHiddenSystemFiles;

   @Option(names = "--exclude-older-files", defaultValue = "false", description = "Don't override newer files in target with older files in source.")
   private boolean excludeOlderFiles;

   @Option(names = "--exclude", description = "Glob pattern for files/directories to be excluded from sync.")
   private String[] excludes;
   private PathMatcher[] excludesSource;
   private PathMatcher[] excludesTarget;

   @Option(names = "--ignore-errors", defaultValue = "false", description = "Continue sync when errors occurr.")
   private boolean ignoreErrors;

   @Option(names = "--ignore-symlink-errors", defaultValue = "false", description = "Continue if creation of symlinks on target fails.")
   private boolean ignoreSymlinkErrors;

   private final Set<LogEvent> log = CollectionUtils.newHashSet(LogEvent.values());

   @Option(names = "--no-log", description = "Don't log the given filesystem operation. Valid values: ${COMPLETION-CANDIDATES}")
   private void setNoLog(final LogEvent[] values) {
      for (final var val : values) {
         log.remove(val);
      }
   }

   @Option(names = "--threads", defaultValue = "2", description = "Number of concurrent threads.")
   private int threads;

   private final AtomicInteger threadsWaiting = new AtomicInteger();
   private final AtomicBoolean threadsDone = new AtomicBoolean();

   private final SyncStats stats = new SyncStats();

   private Path sourceRoot;
   private Path targetRoot;
   private Queue<Path> sourceDirsToScan;

   private volatile State state = State.NORMAL;

   private void delDir(final Path dir) throws IOException {
      final var start = System.currentTimeMillis();
      final var filesDeleted = new MutableLong();
      final var filesDeletedSize = new MutableLong();

      Files.walkFileTree(dir, new SimpleFileVisitor<>() {
         @Override
         public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
            if (!dryRun) {
               Files.delete(dir);
            }
            filesDeleted.increment();
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
            if (!dryRun) {
               Files.delete(file);
            }
            filesDeleted.increment();
            filesDeletedSize.add(attrs.size());
            return FileVisitResult.CONTINUE;
         }
      });
      stats.onDirDeleted(System.currentTimeMillis() - start, filesDeleted.longValue(), filesDeletedSize.longValue());
   }

   private void delFile(final Path file, final boolean count) throws IOException {
      final var fileSize = Files.size(file); // CHECKSTYLE:IGNORE MoveVariableInsideIfCheck
      final var start = System.currentTimeMillis(); // CHECKSTYLE:IGNORE MoveVariableInsideIfCheck
      if (!dryRun) {
         Files.delete(file);
      }
      if (count) {
         stats.onFileDeleted(System.currentTimeMillis() - start, fileSize);
      }
   }

   @SuppressWarnings("resource")
   private void configureExcludePathMatchers() {
      if (excludes != null && excludes.length > 0) {
         final var sourceExcludes = new ArrayList<PathMatcher>(excludes.length);
         final var targetExcludes = new ArrayList<PathMatcher>(excludes.length);
         for (final var exclude : excludes) {
            sourceExcludes.add(sourceRoot.getFileSystem().getPathMatcher("glob:" + exclude));
            targetExcludes.add(targetRoot.getFileSystem().getPathMatcher("glob:" + exclude));
         }
         excludesSource = sourceExcludes.toArray(new PathMatcher[excludes.length]);
         excludesTarget = targetExcludes.toArray(new PathMatcher[excludes.length]);
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

   @Override
   protected void execute() throws Exception {
      stats.start();

      if (threads < 0 || threads == 1) {
         threads = 1;
         sourceDirsToScan = new ArrayDeque<>();
      } else {
         sourceDirsToScan = new ConcurrentLinkedDeque<>();
      }

      JdkLoggingUtils.withRootLogLevel(Level.INFO, () -> {
         LOG.info("Source: %s", sourceRoot.toAbsolutePath());
         LOG.info("Target: %s", targetRoot.toAbsolutePath());
      });

      if (copyAcl && SystemUtils.IS_OS_WINDOWS && !SystemUtils.isRunningAsAdmin()) {
         LOG.warn("Option --copy-acl was specified but process is not running with elevated administrative permissions."
            + "ACL will be copied but excluding ownership information.");
         Threads.sleep(2_000);
      }
      LOG.info("Working hard using %s thread(s)%s...", threads, dryRun ? " (DRY RUN)" : "");

      if (Files.exists(targetRoot, NOFOLLOW_LINKS)) {
         if (Files.isSameFile(sourceRoot, targetRoot))
            throw new ParameterException(spec.commandLine(), "Source and target path point to the same filesystem entry [" + sourceRoot.toRealPath() + "]!");
      } else {
         if (log.contains(LogEvent.CREATE)) {
            LOG.info("NEW [@|magenta %s%s|@]...", targetRoot, File.separator);
         }
         Files.copy(sourceRoot, targetRoot, DIR_COPY_OPTIONS);
      }

      configureExcludePathMatchers();

      sourceDirsToScan.add(sourceRoot);

      /*
       * start syncing
       */
      try {
         if (threads == 1) {
            sync();
         } else {
            final var threadPool = Executors.newFixedThreadPool(threads, //
               new BasicThreadFactory.Builder().namingPattern("sync-%d").build() //
            );
            // CHECKSTYLE:IGNORE .* FOR NEXT LINE
            final var ex = new Exception[] {null};
            for (var i = 0; i < threads; i++) {
               threadPool.submit(() -> {
                  try {
                     sync();
                  } catch (final Exception e) {
                     ex[0] = e;
                  }
               });
            }
            threadPool.shutdown();
            threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            if (ex[0] != null)
               throw ex[0];
         }

         if (state == State.ABORT_BY_SIGNAL) {
            Thread.sleep(Long.MAX_VALUE);
         }
      } finally {
         JdkLoggingUtils.withRootLogLevel(Level.INFO, stats::logStats);
      }
   }

   private Path getNextSourceEntry() {
      var entry = sourceDirsToScan.poll();
      if (entry != null)
         return entry;

      if (threads > 1) {
         threadsWaiting.incrementAndGet();
         try {
            while (!threadsDone.get()) {
               entry = sourceDirsToScan.poll();
               if (entry != null)
                  return entry;
               Threads.sleep(100);
               if (threadsWaiting.get() == threads) {
                  threadsDone.set(true);
                  break;
               }
            }
         } finally {
            threadsWaiting.decrementAndGet();
         }
      }
      return null;
   }

   @Parameters(index = "0", arity = "1", paramLabel = "SOURCE", description = "Directory to copy from files.")
   private void setSourceRoot(final String source) {
      try {
         sourceRoot = Path.of(source).toAbsolutePath();
      } catch (final InvalidPathException ex) {
         throw new ParameterException(spec.commandLine(), "Source path: " + ex.getMessage());
      }

      if (!Files.exists(sourceRoot))
         throw new ParameterException(spec.commandLine(), "Source path [" + source + "] does not exist!");
      if (!Files.isReadable(sourceRoot))
         throw new ParameterException(spec.commandLine(), "Source path [" + source + "] is not readable by user [" + SystemUtils.USER_NAME + "]!");
      if (!Files.isDirectory(sourceRoot))
         throw new ParameterException(spec.commandLine(), "Source path [" + source + "] is not a directory!");
   }

   @SuppressWarnings("resource")
   @Parameters(index = "1", arity = "1", paramLabel = "TARGET", description = "Directory to copy files to.")
   private void setTargetRoot(final String target) {
      try {
         targetRoot = Path.of(target).toAbsolutePath();
      } catch (final InvalidPathException ex) {
         throw new ParameterException(spec.commandLine(), "Target path: " + ex.getMessage());
      }

      if (targetRoot.getFileSystem().isReadOnly())
         throw new ParameterException(spec.commandLine(), "Target path [" + target + "] is on a read-only filesystem!");

      if (Files.exists(targetRoot)) {
         if (!Files.isReadable(targetRoot))
            throw new ParameterException(spec.commandLine(), "Target path [" + target + "] is not readable by user [" + SystemUtils.USER_NAME + "]!");
         if (!Files.isDirectory(targetRoot))
            throw new ParameterException(spec.commandLine(), "Target path [" + target + "] is not a directory!");
         if (!FileUtils.isWritable(targetRoot)) // Files.isWritable(targetRoot) always returns false for some reason
            throw new ParameterException(spec.commandLine(), "Target path [" + target + "] is not writable by user [" + SystemUtils.USER_NAME + "]!");
      } else {
         if (!Files.exists(targetRoot.getParent()))
            throw new ParameterException(spec.commandLine(), "Target path parent directory [" + targetRoot.getParent() + "] does not exist!");
         if (!Files.isDirectory(targetRoot.getParent()))
            throw new ParameterException(spec.commandLine(), "Target path parent [" + targetRoot.getParent() + "] is not a directory!");
      }
   }

   private void sync() throws Exception {

      final var sourceEntries = new HashMap<Path, Path>(); // Map<SourcePathRelativeToRoot, SourcePathAbsolute>
      final var targetEntries = new HashMap<Path, Path>(); // Map<TargetPathRelativeToRoot, TargetPathAbsolute>

      while (state == State.NORMAL) {
         final var source = getNextSourceEntry();
         if (source == null) {
            break;
         }

         try {
            final Path target;
            final Path sourceRelative;
            if (source.equals(sourceRoot)) { // is root ?
               target = targetRoot;
               sourceRelative = Paths.get(".");
            } else {
               sourceRelative = source.subpath(sourceRoot.getNameCount(), source.getNameCount());
               target = targetRoot.resolve(sourceRelative);
            }

            if (log.contains(LogEvent.SCAN)) {
               LOG.info("Scanning [@|magenta %s%s|@]...", sourceRelative, File.separator);
            }

            /*
             * read direct children of source dir
             */
            sourceEntries.clear();
            try (var sourceDS = Files.newDirectoryStream(source)) {
               sourceDS.forEach(sourceEntry -> sourceEntries.put(sourceEntry.subpath(sourceRoot.getNameCount(), sourceEntry.getNameCount()), sourceEntry));
            }

            /*
             * read direct children of target dir
             */
            targetEntries.clear();
            if (!(dryRun && !Files.exists(target))) { // in dry run mode the target directory may not exist
               try (var targetDS = Files.newDirectoryStream(target)) {
                  targetDS.forEach(targetEntry -> targetEntries.put(targetEntry.subpath(targetRoot.getNameCount(), targetEntry.getNameCount()), targetEntry));
               }
            }

            /*
             * remove extraneous entries in target dir
             */
            if (delete) {
               for (final var targetEntry : targetEntries.entrySet()) {
                  if (state != State.NORMAL) {
                     break;
                  }
                  final var targetRelative = targetEntry.getKey();
                  if (sourceEntries.containsKey(targetRelative)) {
                     continue;
                  }
                  final var targetAbsolute = targetEntry.getValue();

                  if (!deleteExcluded) {
                     if (excludeHiddenSystemFiles && Files.isHidden(targetAbsolute) && FileUtils.isDosSystemFile(targetAbsolute)) {
                        continue;
                     }
                     if (excludeSystemFiles && FileUtils.isDosSystemFile(targetAbsolute)) {
                        continue;
                     }
                     if (excludeHiddenFiles && Files.isHidden(targetAbsolute)) {
                        continue;
                     }
                     if (excludesTarget != null) {
                        var isExcluded = false;
                        for (final var exclude : excludesTarget) {
                           if (exclude.matches(targetRelative)) {
                              isExcluded = true;
                              break;
                           }
                        }
                        if (isExcluded) {
                           continue;
                        }
                     }
                  }

                  if (log.contains(LogEvent.DELETE)) {
                     LOG.info("DELETE [@|magenta %s|@]...", targetRelative);
                  }
                  if (Files.isDirectory(targetAbsolute, NOFOLLOW_LINKS)) {
                     delDir(targetAbsolute);
                  } else {
                     delFile(targetAbsolute, true);
                  }
               }
            }

            /*
             * iterate over direct children of source dir
             */
            for (final var sourceEntry : sourceEntries.entrySet()) {
               if (state != State.NORMAL) {
                  break;
               }
               final var relativePath = sourceEntry.getKey();
               final var sourceEntryAbsolute = sourceEntry.getValue();
               final var targetEntryAbsolute = targetEntries.get(relativePath);

               if (excludeHiddenSystemFiles && Files.isHidden(sourceEntryAbsolute) && FileUtils.isDosSystemFile(sourceEntryAbsolute)) {
                  continue;
               }
               if (excludeSystemFiles && FileUtils.isDosSystemFile(sourceEntryAbsolute)) {
                  continue;
               }
               if (excludeHiddenFiles && Files.isHidden(sourceEntryAbsolute)) {
                  continue;
               }
               if (excludesSource != null) {
                  var isExcluded = false;
                  for (final var exclude : excludesSource) {
                     if (exclude.matches(sourceRelative)) {
                        isExcluded = true;
                        break;
                     }
                  }
                  if (isExcluded) {
                     continue;
                  }
               }

               if (Files.isRegularFile(sourceEntryAbsolute)) {
                  syncFile(sourceEntryAbsolute, targetEntryAbsolute, relativePath);
                  stats.onFileScanned();
               } else {
                  syncDirShallow(sourceEntryAbsolute, targetEntryAbsolute, relativePath);
                  if (Files.isSymbolicLink(sourceEntryAbsolute)) {
                     stats.onFileScanned();
                  } else {
                     sourceDirsToScan.add(sourceEntryAbsolute);
                  }
               }
            }
         } catch (final Exception ex) {
            stats.onError(ex);
            if (ignoreErrors) {
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
   private void syncDirShallow(final Path sourcePath, final Path targetPath, final Path relativePath) throws IOException {
      final var sourceAttrs = Files.readAttributes(sourcePath, BasicFileAttributes.class, NOFOLLOW_LINKS);

      final Path resolvedTargetPath;
      if (targetPath == null) {
         resolvedTargetPath = targetRoot.resolve(relativePath);
      } else {
         if (Files.isRegularFile(targetPath)) {
            LOG.debug("Deleting target file [@|magenta %s|@] because source is directory...", relativePath);
            delFile(targetPath, true);
         } else {
            final var targetEntryIsSymlink = Files.isSymbolicLink(targetPath);

            if (sourceAttrs.isSymbolicLink() == targetEntryIsSymlink)
               // both are either symlink or directory, thus nothing to do
               return;

            if (targetEntryIsSymlink) {
               LOG.debug("Deleting target [@|magenta %s|@] because target is symlink and source is not...", targetPath);
               delFile(targetPath, true);
            } else {
               LOG.debug("Deleting target [@|magenta %s|@] because source is symlink and target is not...", targetPath);
               delDir(targetPath);
            }
         }
         resolvedTargetPath = targetPath;
      }

      final var start = System.currentTimeMillis();
      if (sourceAttrs.isSymbolicLink()) {
         if (log.contains(LogEvent.CREATE)) {
            LOG.info("NEW [@|magenta %s -> %s%s|@]...", relativePath, Files.readSymbolicLink(sourcePath), File.separator);
         }
         try {
            if (!dryRun) {
               Files.copy(sourcePath, resolvedTargetPath, FILE_COPY_OPTIONS);
            }
            stats.onFileCopied(System.currentTimeMillis() - start, sourceAttrs.size());
         } catch (final FileSystemException ex) {
            if (ignoreSymlinkErrors) {
               LOG.error("Symlink creation failed:" + ex.getMessage(), ex);
            } else
               throw ex;
         }
      } else {
         if (log.contains(LogEvent.CREATE)) {
            LOG.info("NEW [@|magenta %s%s|@]...", relativePath, File.separator);
         }
         if (!dryRun) {
            Files.copy(sourcePath, resolvedTargetPath, DIR_COPY_OPTIONS);
         }
         stats.onFileCopied(System.currentTimeMillis() - start, sourceAttrs.size());
      }
   }

   /**
    * @param targetPath null, if target path does not exist yet
    */
   private void syncFile(final Path sourcePath, Path targetPath, final Path relativePath) throws IOException {
      final String copyCause;

      final var sourceAttrs = MoreFiles.readAttributes(sourcePath);

      if (targetPath == null) {
         // target file does not exist
         targetPath = targetRoot.resolve(relativePath);
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
                  if (excludeOlderFiles) {
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
               delFile(targetPath, true);
               copyCause = "REPLACE";
            }

            /*
             * target path points to directory
             */
         } else {
            LOG.debug("Deleting target directory [@|magenta %s|@] because source is file...", targetPath);
            if (Files.isSymbolicLink(targetPath)) {
               delFile(targetPath, true);
            } else {
               delDir(targetPath);
            }
            copyCause = "REPLACE";
         }
      }

      if (copyCause != null) {
         if ("NEW".equals(copyCause) && log.contains(LogEvent.CREATE) || log.contains(LogEvent.REPLACE)) {
            LOG.info("%s [@|magenta %s|@] %s...", copyCause, relativePath, Size.ofBytes(sourceAttrs.size()));
         }
         final var start = System.currentTimeMillis();
         if (!dryRun) {
            FileUtils.copyFile(sourcePath, sourceAttrs, targetPath, copyAcl, (bytesWritten, totalBytesWritten) -> { /**/ });
         }
         stats.onFileCopied(System.currentTimeMillis() - start, sourceAttrs.size());
      }
   }
}
