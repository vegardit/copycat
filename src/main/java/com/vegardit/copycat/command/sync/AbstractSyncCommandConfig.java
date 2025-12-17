/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static com.vegardit.copycat.util.MapUtils.*;
import static net.sf.jstuff.core.validation.NullAnalysisHelper.lateNonNull;

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

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.vegardit.copycat.util.DateTimeParser;
import com.vegardit.copycat.util.FileUtils;
import com.vegardit.copycat.util.YamlUtils.ToYamlString;

import net.sf.jstuff.core.Strings;
import net.sf.jstuff.core.SystemUtils;
import net.sf.jstuff.core.concurrent.Threads;
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

   /**
    * Lightweight representation of simple INCLUDE patterns (without \"**\" or character classes) that can be
    * used for cheap subtree skipping decisions during traversal.
    */
   private static final class IncludePatternHint {
      final @NonNull String[] segments;

      IncludePatternHint(final @NonNull String[] segments) {
         this.segments = segments;
      }
   }

   public @Nullable @ToYamlString(ignore = true) Path source;
   public @ToYamlString(name = "source") Path sourceRootAbsolute = lateNonNull(); // computed value

   public @Nullable @ToYamlString(ignore = true) Path target;
   public @ToYamlString(name = "target") Path targetRootAbsolute = lateNonNull(); // computed value

   /**
    * On Windows, open source files with shared read access (best-effort).
    * <p>
    * May copy an inconsistent snapshot for actively written files and may skip copying some metadata.
    */
   public @Nullable Boolean allowReadingOpenFiles;
   public @Nullable Boolean copyACL;
   public @Nullable Boolean deleteExcluded;
   public @Nullable @ToYamlString(name = "filters") List<String> fileFilters;
   public @Nullable Integer maxDepth;

   /**
    * Additional matcher used purely to decide whether a directory subtree can be skipped during traversal without changing filter
    * semantics for individual files.
    */
   private @ToYamlString(ignore = true) PathMatcher @Nullable [] subtreeExcludeSource; // computed value

   /**
    * Simple INCLUDE pattern hints derived from {@link #fileFilters} that are safe to use for conservative subtree
    * skipping in combination with a global EXCLUDE filter that matches all descendants (for example a pattern like \"**\").
    * Only patterns without \"**\" or character classes are considered.
    */
   private @ToYamlString(ignore = true) IncludePatternHint @Nullable [] includePatternHints; // computed value

   /**
    * True if there is an EXCLUDE filter with raw pattern \"**\" (after normalization) or an equivalent all-descendants
    * pattern, meaning that every non-root path is excluded unless explicitly re-included by an INCLUDE filter.
    */
   private boolean hasGlobalExcludeForSubtrees;

   public @Nullable Boolean excludeHiddenFiles;
   public @Nullable Boolean excludeHiddenSystemFiles;
   public @Nullable Boolean excludeSystemFiles;
   public @Nullable Boolean excludeOtherLinks;

   public @Nullable @ToYamlString(name = "since") FileTime modifiedFrom;
   public @Nullable @ToYamlString(name = "until") FileTime modifiedTo;
   public @Nullable @ToYamlString(name = "before") FileTime modifiedBefore;

   @SuppressWarnings({"unchecked", "rawtypes"})
   protected THIS newInstance() {
      return (THIS) new AbstractSyncCommandConfig() {};
   }

   /**
    * Applies default values to null settings
    */
   public void applyDefaults() {
      final THIS defaults = newInstance();
      defaults.allowReadingOpenFiles = false;
      defaults.copyACL = false;
      defaults.deleteExcluded = false;
      defaults.fileFilters = Collections.emptyList();
      defaults.excludeHiddenFiles = false;
      defaults.excludeHiddenSystemFiles = false;
      defaults.excludeSystemFiles = false;
      defaults.excludeOtherLinks = false;
      defaults.maxDepth = null;
      applyFrom(defaults, false);
   }

   /**
    * Applies all non-null settings from the given config object to this config object
    */
   public void applyFrom(final @Nullable THIS other, final boolean override) {
      if (other == null)
         return;

      if (override && other.allowReadingOpenFiles != null || allowReadingOpenFiles == null) {
         allowReadingOpenFiles = other.allowReadingOpenFiles;
      }
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
      if (override && other.excludeHiddenFiles != null || excludeHiddenFiles == null) {
         excludeHiddenFiles = other.excludeHiddenFiles;
      }
      if (override && other.excludeHiddenSystemFiles != null || excludeHiddenSystemFiles == null) {
         excludeHiddenSystemFiles = other.excludeHiddenSystemFiles;
      }
      if (override && other.excludeSystemFiles != null || excludeSystemFiles == null) {
         excludeSystemFiles = other.excludeSystemFiles;
      }
      if (override && other.excludeOtherLinks != null || excludeOtherLinks == null) {
         excludeOtherLinks = other.excludeOtherLinks;
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
      if (override && other.modifiedBefore != null || modifiedBefore == null) {
         modifiedBefore = other.modifiedBefore;
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
      defaults.allowReadingOpenFiles = getBoolean(cfg, "allow-reading-open-files", true);
      defaults.copyACL = getBoolean(cfg, "copy-acl", true);
      defaults.deleteExcluded = getBoolean(cfg, "delete-excluded", true);
      defaults.excludeHiddenFiles = getBoolean(cfg, "exclude-hidden-files", true);
      defaults.excludeHiddenSystemFiles = getBoolean(cfg, "exclude-hidden-system-files", true);
      defaults.excludeSystemFiles = getBoolean(cfg, "exclude-system-files", true);
      defaults.excludeOtherLinks = getBoolean(cfg, "exclude-other-links", true);
      defaults.maxDepth = getInteger(cfg, "max-depth", true);
      defaults.modifiedFrom = getFileTime(cfg, "since", true, DateTimeParser.DateOnlyInterpretation.START_OF_DAY);
      defaults.modifiedTo = getFileTime(cfg, "until", true, DateTimeParser.DateOnlyInterpretation.END_OF_DAY);
      defaults.modifiedBefore = getFileTime(cfg, "before", true, DateTimeParser.DateOnlyInterpretation.START_OF_DAY);
      if (defaults.modifiedTo != null && defaults.modifiedBefore != null)
         throw new IllegalArgumentException("Options 'until' and 'before' found in config cannot be specified at the same time.");
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

   /**
    * Builds a {@link FilterEngine.FilterContext} snapshot for this config that can be used with
    * {@link FilterEngine#includesSource(FilterEngine.FilterContext, Path, Path, com.vegardit.copycat.util.FileAttrs)}.
    */
   public FilterEngine.FilterContext toSourceFilterContext() {
      return FilterEngine.buildSourceFilterContext(this);
   }

   /**
    * Builds a {@link FilterEngine.FilterContext} snapshot for target-side evaluation of this config.
    */
   public FilterEngine.FilterContext toTargetFilterContext() {
      return FilterEngine.buildTargetFilterContext(this);
   }

   @SuppressWarnings("resource")
   private void computePathMatchers() {
      hasGlobalExcludeForSubtrees = false;
      includePatternHints = null;
      subtreeExcludeSource = null;

      final var filters = fileFilters;
      if (filters != null && !filters.isEmpty()) {
         boolean hasIncludeFilters = false;
         boolean hasExcludeFilters = false;

         final var sourceSubtreeFilters = new ArrayList<PathMatcher>();
         final var includeHints = new ArrayList<IncludePatternHint>();
         final var sourceFS = sourceRootAbsolute.getFileSystem();
         for (final String filterSpec : filters) {
            final FileFilterAction action;
            if (Strings.startsWithIgnoreCase(filterSpec, "in:")) {
               action = FileFilterAction.INCLUDE;
               hasIncludeFilters = true;
            } else if (Strings.startsWithIgnoreCase(filterSpec, "ex:")) {
               action = FileFilterAction.EXCLUDE;
               hasExcludeFilters = true;
            } else
               throw new IllegalArgumentException("Illegal filter definition \"" + filterSpec
                     + "\". Must start with action prefix \"in:\" or \"ex:\".");

            var rawPattern = Strings.substringAfter(filterSpec, ":");

            // globbing does not work with backslash as path separator, so replacing it with slash on Windows
            if (SystemUtils.IS_OS_WINDOWS) {
               rawPattern = Strings.replace(rawPattern, "\\", "/");
            }
            rawPattern = Strings.removeEnd(rawPattern, "/");

            if (action == FileFilterAction.EXCLUDE) {
               /*
                * Detect global subtree excludes like \"**\" (after normalization). In combination with INCLUDE hints
                * this allows us to skip traversing subtrees that cannot possibly contain included files.
                */
               if ("**".equals(rawPattern) || "**/*".equals(rawPattern)) {
                  hasGlobalExcludeForSubtrees = true;
               }
            } else if (action == FileFilterAction.INCLUDE) {
               /*
                * For INCLUDE patterns, derive a simple prefix representation that can be used to conservatively decide
                * whether a given directory subtree can still contain any included files.
                *
                * We intentionally restrict hints to patterns without "**" or character classes / groupings so that we
                * can implement cheap and safe prefix checks without attempting to fully reimplement glob semantics.
                */
               if (!rawPattern.contains("**") //
                     && rawPattern.indexOf('[') == -1 && rawPattern.indexOf(']') == -1 //
                     && rawPattern.indexOf('{') == -1 && rawPattern.indexOf('}') == -1) {
                  final var segments = rawPattern.split("/");
                  if (segments.length > 0) {
                     includeHints.add(new IncludePatternHint(segments));
                  }
               }
            }

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
                     sourceSubtreeFilters.add(sourceFS.getPathMatcher("glob:" + withoutStarStar));
                  } else {
                     sourceSubtreeFilters.add(sourceFS.getPathMatcher("glob:" + prefixPattern));
                  }
               }
            }
         }
         includePatternHints = includeHints.isEmpty() ? null : includeHints.toArray(new IncludePatternHint[includeHints.size()]);
         if (hasIncludeFilters && !hasExcludeFilters) {
            LOG.warn(
               "Only INCLUDE (in:) filters configured; unmatched paths are still synced. If you intended \"only these patterns\", add 'ex:**' last.");
            Threads.sleep(5_000);
         }
         if (sourceSubtreeFilters.isEmpty()) {
            subtreeExcludeSource = null;
         } else {
            subtreeExcludeSource = sourceSubtreeFilters.toArray(new PathMatcher[sourceSubtreeFilters.size()]);
         }
      }
   }

   /**
    * Returns true if the given source-relative directory should have its subtree skipped.
    *
    * This is used purely as a traversal optimization. It must never change which individual files are considered
    * included or excluded according to the main filter evaluation in {@link FilterEngine}.
    *
    * Current implementation combines:
    * <ul>
    * <li>legacy subtreeExcludeSource matchers for EXCLUDE patterns ending with \"/**\"</li>
    * <li>a conservative heuristic that, in the presence of a global EXCLUDE filter that matches all descendants and only simple
    * INCLUDE patterns, skips subtrees that cannot possibly contain included files</li>
    * </ul>
    */
   public boolean isExcludedSourceSubtreeDir(final Path sourceRelative) {
      if (matchesSubtreeExclude(sourceRelative, subtreeExcludeSource))
         return true;
      return isSubtreePrunedByGlobalExcludeAndIncludes(sourceRelative);
   }

   /**
    * Conservative check used in combination with a global EXCLUDE filter that matches all descendants to determine
    * whether a directory subtree can be pruned entirely because it cannot contain any included files.
    *
    * The check is intentionally narrow:
    * <ul>
    * <li>It is only active when {@link #hasGlobalExcludeForSubtrees} is true.</li>
    * <li>It only uses {@link #includePatternHints}, i.e. simple INCLUDE patterns without \"**\" or complex glob
    * constructs.</li>
    * <li>If we are unsure, we return {@code false} and let normal traversal handle the directory.</li>
    * </ul>
    */
   private boolean isSubtreePrunedByGlobalExcludeAndIncludes(final Path dirRelative) {
      if (!hasGlobalExcludeForSubtrees)
         return false;

      final var hints = includePatternHints;
      if (hints == null || hints.length == 0)
         return false;

      final int dirDepth = dirRelative.getNameCount();
      if (dirDepth == 0)
         return false;

      final var dirSegments = new @NonNull String[dirDepth];
      for (var i = 0; i < dirDepth; i++) {
         dirSegments[i] = dirRelative.getName(i).toString();
      }

      for (final var hint : hints) {
         final var patSegs = hint.segments;
         final int maxIdx = Math.min(dirDepth, patSegs.length);

         boolean prefixMatches = true;
         for (var i = 0; i < maxIdx; i++) {
            if (!globSegmentMatches(patSegs[i], dirSegments[i])) {
               prefixMatches = false;
               break;
            }
         }

         // If the directory path is compatible with the INCLUDE pattern prefix, then some descendant path may
         // match the INCLUDE (either exactly or via the implicit "/**" variant), so we must not prune.
         if (prefixMatches)
            return false;
      }

      // No INCLUDE pattern can ever match anything under this directory while a global EXCLUDE will catch all
      // descendants, so the subtree is safe to prune.
      return true;
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

   /**
    * Very small glob matcher used for single path segments. Supports '*' and '?' wildcards only. Any pattern
    * containing path separators must not be passed here.
    */
   private static boolean globSegmentMatches(final String pattern, final String value) {
      if ("*".equals(pattern))
         return true;

      // Fast path: no wildcards
      if (pattern.indexOf('*') == -1 && pattern.indexOf('?') == -1)
         return pattern.equals(value);

      int p = 0;
      int v = 0;
      int starIndex = -1;
      int valueIndexAtStar = -1;

      while (v < value.length()) {
         if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == value.charAt(v))) {
            // Single-character match: advance both
            p++;
            v++;
         } else if (p < pattern.length() && pattern.charAt(p) == '*') {
            // Remember position of '*' and the value position where it started matching
            starIndex = p++;
            valueIndexAtStar = v;
         } else if (starIndex != -1) { // CHECKSTYLE:IGNORE .*
            // No direct match, but we saw a '*' before: backtrack so '*' matches one more character
            p = starIndex + 1;
            v = ++valueIndexAtStar;
         } else
            // No '*' to fall back to and current characters do not match
            return false;
      }

      // Consume trailing '*' in the pattern
      while (p < pattern.length() && pattern.charAt(p) == '*') {
         p++;
      }
      return p == pattern.length();
   }
}
