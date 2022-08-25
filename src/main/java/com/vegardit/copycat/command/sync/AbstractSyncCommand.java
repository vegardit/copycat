/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.logging.Level;

import com.vegardit.copycat.command.AbstractCommand;
import com.vegardit.copycat.util.FileUtils;
import com.vegardit.copycat.util.JdkLoggingUtils;

import net.sf.jstuff.core.SystemUtils;
import net.sf.jstuff.core.logging.Logger;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public abstract class AbstractSyncCommand extends AbstractCommand {

   private static final Logger LOG = Logger.create();

   protected static final LinkOption[] NOFOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

   protected static final CopyOption[] SYMLINK_COPY_OPTIONS = { //
      LinkOption.NOFOLLOW_LINKS, //
      StandardCopyOption.COPY_ATTRIBUTES, //
      StandardCopyOption.REPLACE_EXISTING //
   };

   @Option(names = "--copy-acl", defaultValue = "false", description = "Copy file permissions (ACL) for newly copied files.")
   protected boolean copyAcl;

   @Option(names = "--delete-excluded", defaultValue = "false", description = "Delete excluded files/directories from target.")
   protected boolean deleteExcluded;

   @Option(names = "--exclude", description = "Glob pattern for files/directories to be excluded from sync.")
   private String[] excludes;
   private PathMatcher[] excludesSource;
   private PathMatcher[] excludesTarget;

   @Option(names = "--exclude-hidden-files", defaultValue = "false", description = "Don't synchronize hidden files.")
   private boolean excludeHiddenFiles;

   @Option(names = "--exclude-system-files", defaultValue = "false", description = "Don't synchronize system files.")
   private boolean excludeSystemFiles;

   @Option(names = "--exclude-hidden-system-files", defaultValue = "false", description = "Don't synchronize hidden system files.")
   private boolean excludeHiddenSystemFiles;

   protected Path sourceRoot;
   protected Path targetRoot;

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

   protected abstract void doExecute() throws Exception;

   @Override
   protected final void execute() throws Exception {
      JdkLoggingUtils.withRootLogLevel(Level.INFO, () -> {
         LOG.info("Source: %s", sourceRoot.toAbsolutePath());
         LOG.info("Target: %s", targetRoot.toAbsolutePath());
      });

      if (Files.exists(targetRoot, NOFOLLOW_LINKS) && Files.isSameFile(sourceRoot, targetRoot))
         throw new ParameterException(commandSpec.commandLine(), "Source and target path point to the same filesystem entry [" + sourceRoot.toRealPath()
            + "]!");

      configureExcludePathMatchers();

      if (copyAcl && SystemUtils.IS_OS_WINDOWS && !SystemUtils.isRunningAsAdmin()) {
         LOG.warn("Option --copy-acl was specified but process is not running with elevated administrative permissions."
            + " ACL will be copied but excluding ownership information.");
      }

      doExecute();
   }

   protected boolean isExcludedSourcePath(final Path sourceAbsolute, final Path sourceRelative) throws IOException {
      if (excludeHiddenSystemFiles && Files.isHidden(sourceAbsolute) && FileUtils.isDosSystemFile(sourceAbsolute))
         return true;
      if (excludeSystemFiles && FileUtils.isDosSystemFile(sourceAbsolute))
         return true;
      if (excludeHiddenFiles && Files.isHidden(sourceAbsolute))
         return true;
      if (excludesSource != null) {
         for (final var exclude : excludesSource) {
            if (exclude.matches(sourceRelative))
               return true;
         }
      }
      return false;
   }

   protected boolean isExcludedTargetPath(final Path targetAbsolute, final Path targetRelative) throws IOException {
      if (excludeHiddenSystemFiles && Files.isHidden(targetAbsolute) && FileUtils.isDosSystemFile(targetAbsolute))
         return true;
      if (excludeSystemFiles && FileUtils.isDosSystemFile(targetAbsolute))
         return true;
      if (excludeHiddenFiles && Files.isHidden(targetAbsolute))
         return true;
      if (excludesTarget != null) {
         for (final var exclude : excludesTarget) {
            if (exclude.matches(targetRelative))
               return true;
         }
      }
      return false;
   }

   @Parameters(index = "0", arity = "1", paramLabel = "SOURCE", description = "Directory to copy from files.")
   private void setSourceRoot(final String source) {
      try {
         sourceRoot = FileUtils.toAbsolute(Path.of(source));
      } catch (final InvalidPathException ex) {
         throw new ParameterException(commandSpec.commandLine(), "Source path: " + ex.getMessage());
      }

      if (!Files.exists(sourceRoot))
         throw new ParameterException(commandSpec.commandLine(), "Source path [" + source + "] does not exist!");
      if (!Files.isReadable(sourceRoot))
         throw new ParameterException(commandSpec.commandLine(), "Source path [" + source + "] is not readable by user [" + SystemUtils.USER_NAME + "]!");
      if (!Files.isDirectory(sourceRoot))
         throw new ParameterException(commandSpec.commandLine(), "Source path [" + source + "] is not a directory!");
   }

   @SuppressWarnings("resource")
   @Parameters(index = "1", arity = "1", paramLabel = "TARGET", description = "Directory to copy files to.")
   private void setTargetRoot(final String target) {
      try {
         targetRoot = FileUtils.toAbsolute(Path.of(target));
      } catch (final InvalidPathException ex) {
         throw new ParameterException(commandSpec.commandLine(), "Target path: " + ex.getMessage());
      }

      if (targetRoot.getFileSystem().isReadOnly())
         throw new ParameterException(commandSpec.commandLine(), "Target path [" + target + "] is on a read-only filesystem!");

      if (Files.exists(targetRoot)) {
         if (!Files.isReadable(targetRoot))
            throw new ParameterException(commandSpec.commandLine(), "Target path [" + target + "] is not readable by user [" + SystemUtils.USER_NAME + "]!");
         if (!Files.isDirectory(targetRoot))
            throw new ParameterException(commandSpec.commandLine(), "Target path [" + target + "] is not a directory!");
         if (!FileUtils.isWritable(targetRoot)) // Files.isWritable(targetRoot) always returns false for some reason
            throw new ParameterException(commandSpec.commandLine(), "Target path [" + target + "] is not writable by user [" + SystemUtils.USER_NAME + "]!");
      } else {
         if (!Files.exists(targetRoot.getParent()))
            throw new ParameterException(commandSpec.commandLine(), "Target path parent directory [" + targetRoot.getParent() + "] does not exist!");
         if (!Files.isDirectory(targetRoot.getParent()))
            throw new ParameterException(commandSpec.commandLine(), "Target path parent [" + targetRoot.getParent() + "] is not a directory!");
      }
   }

}
