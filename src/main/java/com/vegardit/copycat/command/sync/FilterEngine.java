/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.Nullable;

import com.vegardit.copycat.util.FileAttrs;
import com.vegardit.copycat.util.FileUtils;

import net.sf.jstuff.core.Strings;
import net.sf.jstuff.core.SystemUtils;

/**
 * Centralized filter evaluation according to {@code filter-behavior.md}.
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class FilterEngine {

   /**
    * Local representation of a single filter rule with precompiled matcher.
    */
   private record FilterRule(boolean include, String pattern, PathMatcher matcher) {
   }

   /**
    * Filter context derived from {@link AbstractSyncCommandConfig}.
    */
   public static final class FilterContext {
      final List<FilterRule> sourceRules;

      final @Nullable FileTime modifiedFrom;
      final @Nullable FileTime modifiedTo;
      final boolean modifiedToExclusive;
      final boolean excludeOtherLinks;
      final boolean excludeHiddenFiles;
      final boolean excludeHiddenSystemFiles;
      final boolean excludeSystemFiles;
      final boolean hasIncludeRules;
      final boolean hasGlobalExcludeAll;
      /**
       * When true, global catch-all EXCLUDE patterns like {@code ex:**} are ignored for directories so
       * that traversal is not blocked when INCLUDE rules exist. This is only desirable on the
       * source side; target-side filter evaluation (especially for delete decisions) must honor
       * {@code ex:**} for directories.
       */
      final boolean ignoreGlobalExcludeForDirs;

      private FilterContext(final List<FilterRule> sourceRules, // CHECKSTYLE:IGNORE .*
            final @Nullable FileTime modifiedFrom, final @Nullable FileTime modifiedTo, final boolean modifiedToExclusive, //
            final boolean excludeOtherLinks, //
            final boolean excludeHiddenFiles, final boolean excludeHiddenSystemFiles, final boolean excludeSystemFiles,
            final boolean hasIncludeRules, final boolean hasGlobalExcludeAll, final boolean ignoreGlobalExcludeForDirs) {
         this.sourceRules = sourceRules;
         this.modifiedFrom = modifiedFrom;
         this.modifiedTo = modifiedTo;
         this.modifiedToExclusive = modifiedToExclusive;
         this.excludeOtherLinks = excludeOtherLinks;
         this.excludeHiddenFiles = excludeHiddenFiles;
         this.excludeHiddenSystemFiles = excludeHiddenSystemFiles;
         this.excludeSystemFiles = excludeSystemFiles;
         this.hasIncludeRules = hasIncludeRules;
         this.hasGlobalExcludeAll = hasGlobalExcludeAll;
         this.ignoreGlobalExcludeForDirs = ignoreGlobalExcludeForDirs;
      }
   }

   private FilterEngine() {
      // no instances
   }

   /**
    * Builds a filter context from the given config according to the spec in {@code filter-behavior.md}.
    */
   public static FilterContext buildSourceFilterContext(final AbstractSyncCommandConfig<?> cfg) {
      return buildFilterContext(cfg, true);
   }

   /**
    * Builds a filter context for target-side evaluation according to {@code filter-behavior.md}.
    */
   public static FilterContext buildTargetFilterContext(final AbstractSyncCommandConfig<?> cfg) {
      return buildFilterContext(cfg, false);
   }

   @SuppressWarnings("resource")
   private static FilterContext buildFilterContext(final AbstractSyncCommandConfig<?> cfg, final boolean useSourceFs) {
      final var rules = new ArrayList<FilterRule>();
      boolean hasIncludeRules = false;
      boolean hasGlobalExcludeAll = false;
      final List<String> filters = cfg.fileFilters;
      if (filters != null) {
         final FileSystem fs = (useSourceFs ? cfg.sourceRootAbsolute : cfg.targetRootAbsolute).getFileSystem();
         for (final String filterSpec : filters) {
            String spec = filterSpec.trim();
            if (spec.isEmpty()) {
               continue;
            }

            final boolean include;
            if (Strings.startsWithIgnoreCase(spec, "in:")) {
               include = true;
               hasIncludeRules = true;
               spec = Strings.substringAfter(spec, ":");
            } else if (Strings.startsWithIgnoreCase(spec, "ex:")) {
               include = false;
               spec = Strings.substringAfter(spec, ":");
            } else
               throw new IllegalArgumentException("Illegal filter definition \"" + filterSpec
                     + "\". Must start with action prefix \"in:\" or \"ex:\".");

            if (SystemUtils.IS_OS_WINDOWS) {
               spec = Strings.replace(spec, "\\", "/");
            }
            spec = Strings.removeEnd(spec, "/");

            if (!include && isGlobalExcludeAllPattern(spec)) {
               hasGlobalExcludeAll = true;
            }

            if (spec.isEmpty()) {
               continue;
            }

            final var effectivePatterns = new ArrayList<String>(3);
            addUniquePattern(effectivePatterns, spec);

            /*
             * For patterns that conceptually allow matching at any depth (for example a pattern that
             * begins with a multi-segment wildcard followed by a directory name), also add a variant
             * without the leading segments so that a root-level directory is treated the same as
             * nested ones.
             */
            if (spec.startsWith("**/") && spec.length() > 3) {
               addUniquePattern(effectivePatterns, spec.substring(3));
            }

            /*
             * For EXCLUDE patterns that look like directory literals (no glob meta in the last segment) and do
             * not already use the "/**" subtree form and are not the global all-descendants catch-all patterns,
             * also add a "/**" variant so that the entire subtree is excluded. This keeps README-style patterns
             * like an exclude for "node_modules" intuitive without changing the semantics of explicit "xxx/**"
             * subtree excludes used only for traversal pruning.
             */
            if (!include && !spec.endsWith("/**") && !isGlobalExcludeAllPattern(spec)) {
               final int lastSlash = spec.lastIndexOf('/');
               final String lastSegment = lastSlash >= 0 ? spec.substring(lastSlash + 1) : spec;
               if (!hasGlobMeta(lastSegment)) {
                  addUniquePattern(effectivePatterns, spec + "/**");
               }
            }

            for (final String pattern : effectivePatterns) {
               final PathMatcher matcher = fs.getPathMatcher("glob:" + pattern);
               rules.add(new FilterRule(include, pattern, matcher));
            }
         }
      }

      final FileTime modifiedFrom = cfg.modifiedFrom;
      final FileTime modifiedTo;
      final boolean modifiedToExclusive;
      if (cfg.modifiedBefore != null) {
         modifiedTo = cfg.modifiedBefore;
         modifiedToExclusive = true;
      } else {
         modifiedTo = cfg.modifiedTo;
         modifiedToExclusive = false;
      }

      return new FilterContext( //
         rules.isEmpty() ? List.of() : List.copyOf(rules), //
         modifiedFrom, //
         modifiedTo, //
         modifiedToExclusive, //
         Boolean.TRUE.equals(cfg.excludeOtherLinks), //
         Boolean.TRUE.equals(cfg.excludeHiddenFiles), //
         Boolean.TRUE.equals(cfg.excludeHiddenSystemFiles), //
         Boolean.TRUE.equals(cfg.excludeSystemFiles), //
         hasIncludeRules, //
         hasGlobalExcludeAll, //
         useSourceFs);
   }

   /**
    * Returns true if the given source entry (file or directory) is included based on attribute and filter rules.
    *
    * @param filters
    *           precomputed filter context derived from the effective sync config
    * @param absolutePath
    *           absolute path of the source entry on the filesystem
    * @param relativePath
    *           path of the entry relative to the sync root, used for glob matching
    * @param attrs
    *           pre-resolved {@link FileAttrs} for {@code absolutePath}, used for directory/file classification and
    *           to reuse last-modified timestamps and other attributes during filter evaluation
    * @return {@code true} if the entry is considered included and should be processed
    * @throws IOException
    *            if filesystem attribute lookups required for filter evaluation fail
    */
   public static boolean includesSource(final FilterContext filters, final Path absolutePath, final Path relativePath,
         final FileAttrs attrs) throws IOException {
      final int firstMatchIndex = findFirstMatchingRuleIndex(filters, relativePath);
      return includesSource(filters, absolutePath, relativePath, attrs, firstMatchIndex);
   }

   /**
    * Internal overload that accepts the precomputed index of the first matching glob rule.
    *
    * @param firstMatchIndex
    *           index of the first matching rule in {@code filters.sourceRules}, or {@code -1} if no rule matches.
    *           This is passed in to avoid scanning the rule list twice in fast-paths (for example
    *           {@link #getFileAttrsIfIncluded(FilterContext, Path, Path, boolean)}).
    */
   private static boolean includesSource(final FilterContext filters, final Path absolutePath, final Path relativePath,
         final FileAttrs attrs, final int firstMatchIndex) throws IOException {

      if (filters.excludeOtherLinks && (attrs.isOtherSymlink() || attrs.isBrokenSymlink()))
         return false;

      // Hidden/system filters (apply to files and directories)
      if (filters.excludeHiddenSystemFiles || filters.excludeSystemFiles || filters.excludeHiddenFiles) {
         boolean isHidden = false;
         boolean isDosSystem = false;

         if (filters.excludeHiddenSystemFiles || filters.excludeHiddenFiles) {
            isHidden = Files.isHidden(absolutePath);
         }
         if (filters.excludeHiddenSystemFiles || filters.excludeSystemFiles) {
            isDosSystem = FileUtils.isDosSystemFile(absolutePath);
         }

         if (filters.excludeHiddenSystemFiles && isHidden && isDosSystem //
               || filters.excludeSystemFiles && isDosSystem //
               || filters.excludeHiddenFiles && isHidden)
            return false;
      }

      // Date/time filters (files only)
      final FileTime modifiedFrom = filters.modifiedFrom;
      final FileTime modifiedTo = filters.modifiedTo;
      if ((modifiedFrom != null || modifiedTo != null) && !attrs.isDir()) {
         FileTime lastModified = null;

         if (attrs.isSymlink()) {
            try {
               if (attrs.isFileSymlink()) {
                  lastModified = Files.getLastModifiedTime(absolutePath);
               }
            } catch (final IOException | SecurityException ex) {
               // ignore
            }
         } else if (attrs.isFile()) {
            lastModified = attrs.lastModifiedTime();
         }

         if (lastModified != null && (modifiedFrom != null && lastModified.compareTo(modifiedFrom) < 0 //
               || modifiedTo != null && (filters.modifiedToExclusive ? lastModified.compareTo(modifiedTo) >= 0
                     : lastModified.compareTo(modifiedTo) > 0)))
            return false;
      }

      if (firstMatchIndex == -1)
         return true;

      final List<FilterRule> rules = filters.sourceRules;

      // We already know that the rule at firstMatchIndex matches. Start scanning at that position and
      // only continue with later rules if the first match is ignored (for example global catch-all excludes
      // that are treated as "files-only" for directories on the source side).
      for (int i = firstMatchIndex; i < rules.size(); i++) {
         final FilterRule rule = rules.get(i);
         if (i != firstMatchIndex && !ruleMatches(rule, relativePath)) {
            continue;
         }

         // For source-side traversal, a global catch-all EXCLUDE like "ex:**" or "ex:**/*" must not by itself
         // prevent traversal when INCLUDE rules are present (see filter-behavior.md, section "Directories and ex:**").
         // In that situation we treat the catch-all as applying to files only; directories are handled by the
         // subtree pruning and lazy directory creation logic.
         //
         // When such a global EXCLUDE is present, we also treat the derived top-level variant "*" (introduced
         // for patterns like "**/*") as part of the same catch-all family for directories so that root-level
         // directories which may contain included descendants are not blocked either.
         //
         // Target-side evaluation (for delete decisions) must NOT use this exception; there we honor the
         // catch-all for directories as well. This is controlled via the ignoreGlobalExcludeForDirs flag
         // in the context.
         if (filters.ignoreGlobalExcludeForDirs && !rule.include && filters.hasIncludeRules && attrs.isDir() && (isGlobalExcludeAllPattern(
            rule.pattern) || filters.hasGlobalExcludeAll && "*".equals(rule.pattern))) {
            continue;
         }

         return rule.include;
      }
      return true;
   }

   /**
    * Returns file attributes if the given entry is considered included by the filter engine.
    * <p>
    * This method performs a fast, rule-only precheck to avoid touching file attributes for entries that are already
    * excluded by filename patterns (for example locked files on Windows). If the rule-only check excludes an entry and
    * the subtree is known to be prunable, then no filesystem attribute reads are performed.
    *
    * @param filters
    *           precomputed filter context
    * @param absolutePath
    *           absolute path of the entry on the filesystem
    * @param relativePath
    *           path relative to the sync root, used for glob matching
    * @param skipSubtreeScan
    *           {@code true} if the caller has determined that this path (or subtree) cannot contain included descendants
    *           and can thus be skipped safely when excluded by glob rules (see {@code isExcludedSourceSubtreeDir})
    * @return the resolved {@link FileAttrs} if the entry is included; {@code null} otherwise
    */
   public static @Nullable FileAttrs getFileAttrsIfIncluded(final FilterContext filters, final Path absolutePath, final Path relativePath,
         final boolean skipSubtreeScan) throws IOException {
      final int firstMatchIndex = findFirstMatchingRuleIndex(filters, relativePath);
      if (firstMatchIndex >= 0 //
            && (!filters.hasGlobalExcludeAll || skipSubtreeScan) //
            && !filters.sourceRules.get(firstMatchIndex).include()) //
         return null;

      final var attrs = FileAttrs.get(absolutePath);
      if (!includesSource(filters, absolutePath, relativePath, attrs, firstMatchIndex))
         return null;
      return attrs;
   }

   /**
    * Returns the index of the first filter rule that matches {@code relativePath}, using the same glob matching
    * semantics as {@link #includesSource(FilterContext, Path, Path, FileAttrs)}.
    * <p>
    * This is a pure rule-match lookup (no filesystem I/O and no hidden/system/date checks). It is used as a small
    * optimization so callers can compute "first match" once and avoid rescanning the rule list.
    *
    * @return index in {@code filters.sourceRules}, or {@code -1} if no rule matches
    */
   private static int findFirstMatchingRuleIndex(final FilterContext filters, final Path relativePath) {
      final List<FilterRule> rules = filters.sourceRules;
      if (rules.isEmpty())
         return -1;

      for (int i = 0; i < rules.size(); i++) {
         final FilterRule rule = rules.get(i);
         if (ruleMatches(rule, relativePath))
            return i;
      }
      return -1;
   }

   private static boolean ruleMatches(final FilterRule rule, final Path relativePath) {
      if (rule.matcher.matches(relativePath))
         return true;

      // Additional handling for simple subtree-exclude patterns ending with "/**" (for example
      // "bbb/**" or "**/node_modules/**") so that descendants of the directory are excluded even if
      // the underlying PathMatcher does not match them as entire paths.
      return !rule.include && rule.pattern.endsWith("/**") && matchesSubtreePattern(rule.pattern, relativePath);
   }

   /**
    * Returns true if the given directory is explicitly included by an {@code in:} filter (ignoring default-include semantics).
    */
   public static boolean isDirExplicitlyIncluded(final FilterContext filters, final Path dirRelative) {
      final List<FilterRule> rules = filters.sourceRules;
      if (rules.isEmpty())
         return false;

      for (final FilterRule rule : rules) {
         if (!rule.matcher.matches(dirRelative)) {
            continue;
         }
         return rule.include;
      }
      return false;
   }

   private static void addUniquePattern(final List<String> patterns, final String pattern) {
      if (pattern.isEmpty())
         return;
      if (!patterns.contains(pattern)) {
         patterns.add(pattern);
      }
   }

   private static boolean hasGlobMeta(final String segment) {
      for (var i = 0; i < segment.length(); i++) {
         final char ch = segment.charAt(i);
         if (ch == '*' || ch == '?' || ch == '[' || ch == ']' || ch == '{' || ch == '}')
            return true;
      }
      return false;
   }

   /**
    * Returns true if the given relative path is a descendant of the directory encoded in a simple subtree
    * pattern ending with "/**" (for example "bbb/**" or "** /node_modules/**").
    *
    * The check is intentionally conservative and only activated when the last segment has no glob meta
    * characters. The directory itself (for example "node_modules") is not considered a match; only paths with
    * at least one additional segment below it are.
    */
   private static boolean matchesSubtreePattern(final String pattern, final Path relativePath) {
      if (!pattern.endsWith("/**"))
         return false;

      String base = Strings.removeEnd(pattern, "/**");
      base = Strings.removeEnd(base, "/");
      if (base.isEmpty())
         return false;

      // Support patterns starting with "**/": allow the prefix to match at any depth.
      final boolean hasLeadingGlobStar = base.startsWith("**/");
      if (hasLeadingGlobStar) {
         base = base.substring(3);
      }
      if (base.isEmpty())
         return false;

      // Only accept literal path segments (no glob meta) for this fallback check.
      final String[] baseSegments = base.split("/");
      for (final String segment : baseSegments) {
         if (segment.isEmpty() || hasGlobMeta(segment))
            return false;
      }

      String pathStr = relativePath.toString();
      final boolean isWindows = SystemUtils.IS_OS_WINDOWS;
      if (isWindows) {
         pathStr = pathStr.replace('\\', '/');
      }

      if (pathStr.isEmpty())
         return false;

      final String[] parts = pathStr.split("/");
      // Need at least one descendant segment below the matched directory.
      if (parts.length <= baseSegments.length)
         return false; // must be a descendant, not the directory itself

      if (hasLeadingGlobStar) {
         // Sliding-window match for "**/prefix/**": look for prefix at any depth.
         final int maxStart = parts.length - baseSegments.length - 1;
         for (int start = 0; start <= maxStart; start++) {
            boolean matches = true;
            for (int i = 0; i < baseSegments.length; i++) {
               if (!(isWindows ? baseSegments[i].equalsIgnoreCase(parts[start + i]) : baseSegments[i].equals(parts[start + i]))) {
                  matches = false;
                  break;
               }
            }
            if (matches)
               return true;
         }
         return false;
      }

      // Exact prefix match for "prefix/**" (no leading "**/").
      for (int i = 0; i < baseSegments.length; i++) {
         if (!(isWindows ? baseSegments[i].equalsIgnoreCase(parts[i]) : baseSegments[i].equals(parts[i])))
            return false;
      }
      return true;
   }

   private static boolean isGlobalExcludeAllPattern(final String pattern) {
      if (pattern.isEmpty())
         return false;
      return "**".equals(pattern) || "**/*".equals(pattern);
   }
}
