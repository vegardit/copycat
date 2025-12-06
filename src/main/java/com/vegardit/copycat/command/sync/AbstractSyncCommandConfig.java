/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static com.vegardit.copycat.util.Booleans.isTrue;
import static com.vegardit.copycat.util.MapUtils.*;
import static net.sf.jstuff.core.validation.NullAnalysisHelper.lateNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.Nullable;

import com.vegardit.copycat.util.FileUtils;
import com.vegardit.copycat.util.YamlUtils.ToYamlString;

import net.sf.jstuff.core.Strings;
import net.sf.jstuff.core.SystemUtils;
import net.sf.jstuff.core.collection.tuple.Tuple2;
import net.sf.jstuff.core.logging.Logger;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public abstract class AbstractSyncCommandConfig<THIS extends AbstractSyncCommandConfig<THIS>> {

   private static final Logger LOG = Logger.create();

   protected enum FileFilterAction {
      EXCLUDE,
      INCLUDE
   }

   public @Nullable @ToYamlString(ignore = true) Path source;
   public @ToYamlString(name = "source") Path sourceRootAbsolute = lateNonNull(); // computed value

   public @Nullable @ToYamlString(ignore = true) Path target;
   public @ToYamlString(name = "target") Path targetRootAbsolute = lateNonNull(); // computed value

   public @Nullable Boolean copyACL;
   public @Nullable Boolean deleteExcluded;
   public @Nullable @ToYamlString(name = "filters") List<String> fileFilters;
   public @Nullable Integer maxDepth;

   protected @ToYamlString(ignore = true) Tuple2<FileFilterAction, PathMatcher> @Nullable [] fileFiltersSource; // computed value
   protected @ToYamlString(ignore = true) Tuple2<FileFilterAction, PathMatcher> @Nullable [] fileFiltersTarget; // computed value

   /**
    * Additional matchers used purely to decide whether a directory subtree can be skipped during traversal without changing filter
    * semantics for individual files.
    */
   protected @ToYamlString(ignore = true) PathMatcher @Nullable [] subtreeExcludeSource; // computed value
   protected @ToYamlString(ignore = true) PathMatcher @Nullable [] subtreeExcludeTarget; // computed value

   public @Nullable Boolean excludeHiddenFiles;
   public @Nullable Boolean excludeHiddenSystemFiles;
   public @Nullable Boolean excludeSystemFiles;

   public @Nullable @ToYamlString(name = "since") FileTime modifiedFrom;
   public @Nullable @ToYamlString(name = "until") FileTime modifiedTo;

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
      defaults.fileFilters = Collections.emptyList();
      defaults.excludeHiddenFiles = false;
      defaults.excludeHiddenSystemFiles = false;
      defaults.excludeSystemFiles = false;
      defaults.maxDepth = null;
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
      final var other_fileFilters = other.fileFilters;
      if (override && other_fileFilters != null || fileFilters == null) {
         fileFilters = other_fileFilters;
      }
      if (override && other.fileFiltersSource != null || fileFiltersSource == null) {
         fileFiltersSource = other.fileFiltersSource;
      }
      if (override && other.fileFiltersTarget != null || fileFiltersTarget == null) {
         fileFiltersTarget = other.fileFiltersTarget;
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
      if (override && other.maxDepth != null || maxDepth == null) {
         maxDepth = other.maxDepth;
      }
      if (override && other.modifiedFrom != null || modifiedFrom == null) {
         modifiedFrom = other.modifiedFrom;
      }
      if (override && other.modifiedTo != null || modifiedTo == null) {
         modifiedTo = other.modifiedTo;
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
      defaults.excludeHiddenFiles = getBoolean(cfg, "exclude-hidden-files", true);
      defaults.excludeHiddenSystemFiles = getBoolean(cfg, "exclude-hidden-system-files", true);
      defaults.excludeSystemFiles = getBoolean(cfg, "exclude-system-files", true);
      defaults.maxDepth = getInteger(cfg, "max-depth", true);
      defaults.modifiedFrom = getFileTime(cfg, "since", true);
      defaults.modifiedTo = getFileTime(cfg, "until", true);
      defaults.source = getPath(cfg, "source", true);
      defaults.target = getPath(cfg, "target", true);

      var filters = getStringList(cfg, "filters", true);
      final var filter = getStringList(cfg, "filter", true); // mutual exclusive alias for "filters"
      final var excludes = getStringList(cfg, "exclude", true); // deprecated excludes option

      if (filter != null) {
         if (filters != null)
            throw new IllegalArgumentException("Duplicate option 'filters:' and 'filter:' found in config.");
         LOG.warn("Option 'filter:' found in config should be named 'filters:'.");
         filters = filter;
      }
      if (excludes != null) {
         if (filters != null)
            throw new IllegalArgumentException(
               "Option 'filters:' and deprecated option 'exclude:' found in config cannot be specified at the same time.");
         LOG.warn("Deprecated 'exclude:' option found in config. Please migrate to new 'filters:' option.");
         filters = excludes.stream().map(exclude -> "ex:" + exclude).filter(Objects::nonNull).toList();
      }

      defaults.fileFilters = filters;
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
         final var parent = targetRootAbsolute.getParent();
         if (parent == null || !Files.exists(parent))
            throw new IllegalArgumentException("Parent directory of target path [" + parent + "] does not exist!");
         if (!Files.isDirectory(parent))
            throw new IllegalArgumentException("Parent of target path [" + parent + "] is not a directory!");
      }

      computePathMatchers();
   }

   @SuppressWarnings("resource")
   private void computePathMatchers() {
      final var filters = fileFilters;
      if (filters != null && !filters.isEmpty()) {
         final var sourceFilters = new ArrayList<Tuple2<FileFilterAction, PathMatcher>>(filters.size() * 2);
         final var targetFilters = new ArrayList<Tuple2<FileFilterAction, PathMatcher>>(filters.size() * 2);
         final var sourceSubtreeFilters = new ArrayList<PathMatcher>();
         final var targetSubtreeFilters = new ArrayList<PathMatcher>();
         final var sourceFS = sourceRootAbsolute.getFileSystem();
         final var targetFS = targetRootAbsolute.getFileSystem();
         for (final String filterSpec : filters) {
            final FileFilterAction action;
            if (Strings.startsWithIgnoreCase(filterSpec, "in:")) {
               action = FileFilterAction.INCLUDE;
            } else if (Strings.startsWithIgnoreCase(filterSpec, "ex:")) {
               action = FileFilterAction.EXCLUDE;
            } else
               throw new IllegalArgumentException("Illegal filter definition \"" + filterSpec
                     + "\". Must start with action prefix \"in:\" or \"ex:\".");

            var rawPattern = Strings.substringAfter(filterSpec, ":");

            // globbing does not work with backslash as path separator, so replacing it with slash on Windows
            if (SystemUtils.IS_OS_WINDOWS) {
               rawPattern = Strings.replace(rawPattern, "\\", "/");
            }
            rawPattern = Strings.removeEnd(rawPattern, "/");

            final var effectivePatterns = new ArrayList<String>(2);
            effectivePatterns.add(rawPattern);

            // For patterns starting with "**/" (e.g. "**/node_modules"), also match the top-level variant
            // so that a root "node_modules" directory is treated the same as nested ones.
            if (rawPattern.startsWith("**/") && rawPattern.length() > 3) {
               effectivePatterns.add(rawPattern.substring(3));
            }

            /*
             * For exclude patterns ending with "/**" (e.g. "bbb/**"), we can skip traversing the corresponding directory subtree.
             * We keep normal path matchers for file-level semantics and add separate prefix matchers used only for traversal decisions.
             */
            if (action == FileFilterAction.EXCLUDE && rawPattern.endsWith("/**")) {
               final var prefixPattern = Strings.removeEnd(rawPattern, "/**");
               if (!prefixPattern.isEmpty()) {
                  if (prefixPattern.startsWith("**/") && prefixPattern.length() > 3) {
                     final var withoutStarStar = prefixPattern.substring(3);
                     sourceSubtreeFilters.add(sourceFS.getPathMatcher("glob:" + prefixPattern));
                     targetSubtreeFilters.add(targetFS.getPathMatcher("glob:" + prefixPattern));
                     sourceSubtreeFilters.add(sourceFS.getPathMatcher("glob:" + withoutStarStar));
                     targetSubtreeFilters.add(targetFS.getPathMatcher("glob:" + withoutStarStar));
                  } else {
                     sourceSubtreeFilters.add(sourceFS.getPathMatcher("glob:" + prefixPattern));
                     targetSubtreeFilters.add(targetFS.getPathMatcher("glob:" + prefixPattern));
                  }
               }
            }

            for (final var p : effectivePatterns) {
               final var globPattern = "glob:" + p;

               sourceFilters.add(Tuple2.create(action, sourceFS.getPathMatcher(globPattern)));
               targetFilters.add(Tuple2.create(action, targetFS.getPathMatcher(globPattern)));

               if (!globPattern.endsWith("/**")) {
                  final var recursiveGlobPattern = globPattern + "/**";
                  sourceFilters.add(Tuple2.create(action, sourceFS.getPathMatcher(recursiveGlobPattern)));
                  targetFilters.add(Tuple2.create(action, targetFS.getPathMatcher(recursiveGlobPattern)));
               }
            }
         }
         fileFiltersSource = sourceFilters.toArray(new Tuple2[sourceFilters.size()]);
         fileFiltersTarget = targetFilters.toArray(new Tuple2[targetFilters.size()]);
         if (sourceSubtreeFilters.isEmpty()) {
            subtreeExcludeSource = null;
            subtreeExcludeTarget = null;
         } else {
            subtreeExcludeSource = sourceSubtreeFilters.toArray(new PathMatcher[sourceSubtreeFilters.size()]);
            subtreeExcludeTarget = targetSubtreeFilters.toArray(new PathMatcher[targetSubtreeFilters.size()]);
         }
      }
   }

   public boolean isExcludedSourcePath(final Path sourceAbsolute, final Path sourceRelative) throws IOException {
      return isExcludedPath(sourceAbsolute, sourceRelative, fileFiltersSource);
   }

   public boolean isExcludedTargetPath(final Path targetAbsolute, final Path targetRelative) throws IOException {
      return isExcludedPath(targetAbsolute, targetRelative, fileFiltersTarget);
   }

   /**
    * Returns true if the given source-relative directory should have its subtree
    * skipped based on EXCLUDE patterns ending with "/**".
    */
   public boolean isExcludedSourceSubtreeDir(final Path sourceRelative) {
      return matchesSubtreeExclude(sourceRelative, subtreeExcludeSource);
   }

   /**
    * Returns true if the given target-relative directory should have its subtree
    * skipped based on EXCLUDE patterns ending with "/**".
    */
   public boolean isExcludedTargetSubtreeDir(final Path targetRelative) {
      return matchesSubtreeExclude(targetRelative, subtreeExcludeTarget);
   }

   private static boolean matchesSubtreeExclude(final Path relativePath, final PathMatcher @Nullable [] matchers) {
      if (matchers == null)
         return false;
      for (final var matcher : matchers) {
         if (matcher.matches(relativePath))
            return true;
      }
      return false;
   }

   private boolean isExcludedPath(final Path absolutePath, final Path relativePath,
         final Tuple2<FileFilterAction, PathMatcher> @Nullable [] fileFilters) throws IOException {
      if (isTrue(excludeHiddenSystemFiles) && Files.isHidden(absolutePath) && FileUtils.isDosSystemFile(absolutePath) //
            || isTrue(excludeSystemFiles) && FileUtils.isDosSystemFile(absolutePath) //
            || isTrue(excludeHiddenFiles) && Files.isHidden(absolutePath))
         return true;

      // Check modification time filters
      if ((modifiedFrom != null || modifiedTo != null) && Files.isRegularFile(absolutePath)) {
         final var lastModified = Files.getLastModifiedTime(absolutePath);

         // Exclude files modified before the "since" date or after the "until" date
         if (modifiedFrom != null && lastModified.compareTo(modifiedFrom) < 0 || modifiedTo != null && lastModified.compareTo(
            modifiedTo) > 0)
            return true;
      }

      if (fileFilters != null) {
         for (final var filter : fileFilters) {
            switch (filter.get1()) {
               case EXCLUDE:
                  if (filter.get2().matches(relativePath))
                     return true;
                  break;
               case INCLUDE:
                  if (filter.get2().matches(relativePath))
                     return false;
                  break;
            }
         }
      }
      return false;
   }
}
