/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static com.vegardit.copycat.util.Booleans.isTrue;
import static com.vegardit.copycat.util.MapUtils.*;
import static net.sf.jstuff.core.validation.NullAnalysisHelper.lazyNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;

import com.vegardit.copycat.util.FileUtils;
import com.vegardit.copycat.util.YamlUtils.ToYamlString;

import net.sf.jstuff.core.Strings;
import net.sf.jstuff.core.SystemUtils;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public abstract class AbstractSyncCommandConfig<THIS extends AbstractSyncCommandConfig<THIS>> {

   public @Nullable @ToYamlString(ignore = true) Path source;
   public @ToYamlString(name = "source") Path sourceRootAbsolute = lazyNonNull(); // computed value

   public @Nullable @ToYamlString(ignore = true) Path target;
   public @ToYamlString(name = "target") Path targetRootAbsolute = lazyNonNull(); // computed value

   public @Nullable Boolean copyACL;
   public @Nullable Boolean deleteExcluded;
   public @Nullable List<String> excludes;

   public @ToYamlString(ignore = true) PathMatcher @Nullable [] excludesSource; // computed value
   public @ToYamlString(ignore = true) PathMatcher @Nullable [] excludesTarget; // computed value

   public @Nullable Boolean excludeHiddenFiles;
   public @Nullable Boolean excludeHiddenSystemFiles;
   public @Nullable Boolean excludeSystemFiles;

   @SuppressWarnings({"unchecked", "rawtypes"})
   protected THIS newInstance() {
      return (THIS) new AbstractSyncCommandConfig() {};
   }

   /**
    * Applies default values to null settings
    */
   public void applyDefaults() {
      final THIS defaults = newInstance();
      defaults.copyACL = false;
      defaults.deleteExcluded = false;
      defaults.excludes = Collections.emptyList();
      defaults.excludeHiddenFiles = false;
      defaults.excludeHiddenSystemFiles = false;
      defaults.excludeSystemFiles = false;
      applyFrom(defaults, false);
   }

   /**
    * Applies all non-null settings from the given config object to this config object
    */
   public void applyFrom(final @Nullable THIS other, final boolean override) {
      if (other == null)
         return;

      if (override && other.copyACL != null || copyACL == null) {
         copyACL = other.copyACL;
      }
      if (override && other.deleteExcluded != null || deleteExcluded == null) {
         deleteExcluded = other.deleteExcluded;
      }
      final var excludes = this.excludes;
      final var other_excludes = other.excludes;
      if (override && other_excludes != null || excludes == null) {
         this.excludes = other_excludes;
      } else if (other_excludes != null) {
         this.excludes = new ArrayList<>(excludes);
         this.excludes.addAll(other_excludes);
      }

      if (override && other.excludesSource != null || excludesSource == null) {
         excludesSource = other.excludesSource;
      }
      if (override && other.excludesTarget != null || excludesTarget == null) {
         excludesTarget = other.excludesTarget;
      }
      if (override && other.excludeHiddenFiles != null || excludeHiddenFiles == null) {
         excludeHiddenFiles = other.excludeHiddenFiles;
      }
      if (override && other.excludeHiddenSystemFiles != null || excludeHiddenSystemFiles == null) {
         excludeHiddenSystemFiles = other.excludeHiddenSystemFiles;
      }
      if (override && other.excludeSystemFiles != null || excludeSystemFiles == null) {
         excludeSystemFiles = other.excludeSystemFiles;
      }
      if (override && other.source != null || source == null) {
         source = other.source;
      }
      if (override && other.target != null || target == null) {
         target = other.target;
      }
   }

   /**
    * @return a map with any unused config parameters
    */
   public Map<String, Object> applyFrom(final @Nullable Map<String, Object> config, final boolean override) {
      if (config == null || config.isEmpty())
         return Collections.emptyMap();
      final var cfg = new HashMap<>(config);
      final THIS defaults = newInstance();
      defaults.copyACL = getBoolean(cfg, "copy-acl", true);
      defaults.deleteExcluded = getBoolean(cfg, "delete-excluded", true);
      defaults.excludes = getStringList(cfg, "exclude", true);
      defaults.excludeHiddenFiles = getBoolean(cfg, "exclude-hidden-files", true);
      defaults.excludeHiddenSystemFiles = getBoolean(cfg, "exclude-hidden-system-files", true);
      defaults.excludeSystemFiles = getBoolean(cfg, "exclude-system-files", true);
      defaults.source = getPath(cfg, "source", true);
      defaults.target = getPath(cfg, "target", true);
      applyFrom(defaults, override);
      return cfg;
   }

   @SuppressWarnings("resource")
   public void compute() {
      final var source = this.source;
      if (source == null)
         throw new IllegalArgumentException("Source is not specified!");
      sourceRootAbsolute = FileUtils.toAbsolute(source);
      if (!Files.exists(sourceRootAbsolute))
         throw new IllegalArgumentException("Source path [" + source + "] does not exist!");
      if (!Files.isReadable(sourceRootAbsolute))
         throw new IllegalArgumentException("Source path [" + source + "] is not readable by user [" + SystemUtils.USER_NAME + "]!");
      if (!Files.isDirectory(sourceRootAbsolute))
         throw new IllegalArgumentException("Source path [" + source + "] is not a directory!");

      final var target = this.target;
      if (target == null)
         throw new IllegalArgumentException("Target is not specified!");
      final var targetRootAbsolute = this.targetRootAbsolute = FileUtils.toAbsolute(target);
      if (targetRootAbsolute.getFileSystem().isReadOnly())
         throw new IllegalArgumentException("Target path [" + target + "] is on a read-only filesystem!");
      if (Files.exists(targetRootAbsolute)) {
         if (!Files.isReadable(targetRootAbsolute))
            throw new IllegalArgumentException("Target path [" + target + "] is not readable by user [" + SystemUtils.USER_NAME + "]!");
         if (!Files.isDirectory(targetRootAbsolute))
            throw new IllegalArgumentException("Target path [" + target + "] is not a directory!");
         if (!FileUtils.isWritable(targetRootAbsolute)) // Files.isWritable(targetRoot) always returns false for some reason
            throw new IllegalArgumentException("Target path [" + target + "] is not writable by user [" + SystemUtils.USER_NAME + "]!");
      } else {
         if (!Files.exists(targetRootAbsolute.getParent()))
            throw new IllegalArgumentException("Parent directory of target path [" + targetRootAbsolute.getParent() + "] does not exist!");
         if (!Files.isDirectory(targetRootAbsolute.getParent()))
            throw new IllegalArgumentException("Parent of target path [" + targetRootAbsolute.getParent() + "] is not a directory!");
      }

      computeExcludePathMatchers();
   }

   private void computeExcludePathMatchers() {
      final var excludes = this.excludes;
      if (excludes != null && !excludes.isEmpty()) {
         final var sourceExcludes = new ArrayList<>(excludes.size() * 2);
         final var targetExcludes = new ArrayList<>(excludes.size() * 2);
         final @SuppressWarnings("resource") var sourceFS = sourceRootAbsolute.getFileSystem();
         final @SuppressWarnings("resource") var targetFS = targetRootAbsolute.getFileSystem();
         for (String exclude : excludes) {
            // globbing does not work with backslash as path separator, so replacing it with slash on windows
            if (SystemUtils.IS_OS_WINDOWS) {
               exclude = Strings.replace(exclude, "\\", "/");
            }
            exclude = Strings.removeEnd(exclude, "/");
            exclude = "glob:" + exclude;
            sourceExcludes.add(sourceFS.getPathMatcher(exclude));
            sourceExcludes.add(sourceFS.getPathMatcher(exclude + "/**"));
            targetExcludes.add(targetFS.getPathMatcher(exclude));
            targetExcludes.add(targetFS.getPathMatcher(exclude + "/**"));
         }
         excludesSource = sourceExcludes.toArray(PathMatcher[]::new);
         excludesTarget = targetExcludes.toArray(PathMatcher[]::new);
      }
   }

   public boolean isExcludedSourcePath(final Path sourceAbsolute, final Path sourceRelative) throws IOException {
      if (isTrue(excludeHiddenSystemFiles) && Files.isHidden(sourceAbsolute) && FileUtils.isDosSystemFile(sourceAbsolute))
         return true;
      if (isTrue(excludeSystemFiles) && FileUtils.isDosSystemFile(sourceAbsolute) //
         || isTrue(excludeHiddenFiles) && Files.isHidden(sourceAbsolute))
         return true;
      if (excludesSource != null) {
         for (final var exclude : excludesSource) {
            if (exclude.matches(sourceRelative))
               return true;
         }
      }
      return false;
   }

   public boolean isExcludedTargetPath(final Path targetAbsolute, final Path targetRelative) throws IOException {
      if (isTrue(excludeHiddenSystemFiles) && Files.isHidden(targetAbsolute) && FileUtils.isDosSystemFile(targetAbsolute))
         return true;
      if (isTrue(excludeSystemFiles) && FileUtils.isDosSystemFile(targetAbsolute) //
         || isTrue(excludeHiddenFiles) && Files.isHidden(targetAbsolute))
         return true;
      if (excludesTarget != null) {
         for (final var exclude : excludesTarget) {
            if (exclude.matches(targetRelative))
               return true;
         }
      }
      return false;
   }
}
