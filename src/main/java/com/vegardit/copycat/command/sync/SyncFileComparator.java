/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;

import org.eclipse.jdt.annotation.Nullable;

import com.vegardit.copycat.util.FileAttrs;

/**
 * Encapsulates the sync command's regular-file quick check.
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
final class SyncFileComparator {

   /*
    * toleratedTimestampDelta is the single source of truth for whether tolerance changed the
    * decision. Duration.ZERO means no tolerance was applied; a real tolerated delta cannot be zero
    * because exact timestamp matches return before delta calculation.
    */
   record Result(@Nullable SyncFileCopyCause copyCause, boolean sourceOlderSkipped, Duration toleratedTimestampDelta) {

      boolean shouldCopy() {
         return copyCause != null;
      }

      boolean timestampToleranceApplied() {
         return !toleratedTimestampDelta.isZero();
      }
   }

   /*
    * This comparator runs once per regular-file match. Reuse the common zero-delta decisions so the
    * default exact-compare path stays allocation-free; only tolerated non-zero deltas need per-call state.
    */
   private static final Result IN_SYNC = new Result(null, false, Duration.ZERO);
   private static final Result NEWER = new Result(SyncFileCopyCause.NEWER, false, Duration.ZERO);
   private static final Result OLDER = new Result(SyncFileCopyCause.OLDER, false, Duration.ZERO);
   private static final Result OLDER_SKIPPED = new Result(null, true, Duration.ZERO);
   private static final Result LARGER = new Result(SyncFileCopyCause.LARGER, false, Duration.ZERO);
   private static final Result SMALLER = new Result(SyncFileCopyCause.SMALLER, false, Duration.ZERO);

   static Result compareRegularFiles(final BasicFileAttributes sourceAttrs, final FileAttrs targetAttrs, final Duration timestampTolerance,
         final boolean excludeOlderFiles) {

      final FileTime sourceTime = sourceAttrs.lastModifiedTime();
      final FileTime targetTime = targetAttrs.lastModifiedTime();
      final int rawTimeCompare = sourceTime.compareTo(targetTime);

      if (rawTimeCompare == 0)
         return compareSizes(sourceAttrs, targetAttrs, Duration.ZERO);

      if (!timestampTolerance.isZero()) {
         final Duration timestampDelta = absoluteDifference(sourceTime, targetTime);
         if (timestampDelta.compareTo(timestampTolerance) <= 0) {
            /*
             * The tolerance is only an equality shim for filesystem timestamp precision loss. Once the
             * times are treated as equal, keep the existing size check so same-window size changes still copy.
             */
            return compareSizes(sourceAttrs, targetAttrs, timestampDelta);
         }
      }

      /*
       * With exact matching, or when the delta is outside tolerance, preserve the original newer/older
       * decision without carrying diagnostic delta data through the hot path.
       */
      if (rawTimeCompare > 0)
         return NEWER;

      // rawTimeCompare == 0 returned above, so the remaining non-newer case is older.
      if (excludeOlderFiles)
         return OLDER_SKIPPED;
      return OLDER;
   }

   private static Result compareSizes(final BasicFileAttributes sourceAttrs, final FileAttrs targetAttrs,
         final Duration toleratedTimestampDelta) {
      final var sizeCompare = Long.compare(sourceAttrs.size(), targetAttrs.size());
      if (toleratedTimestampDelta.isZero()) {
         if (sizeCompare > 0)
            return LARGER;
         if (sizeCompare < 0)
            return SMALLER;
         return IN_SYNC;
      }

      if (sizeCompare > 0)
         return new Result(SyncFileCopyCause.LARGER, false, toleratedTimestampDelta);
      if (sizeCompare < 0)
         return new Result(SyncFileCopyCause.SMALLER, false, toleratedTimestampDelta);
      return new Result(null, false, toleratedTimestampDelta);
   }

   private static Duration absoluteDifference(final FileTime first, final FileTime second) {
      if (first.compareTo(second) >= 0)
         return Duration.between(second.toInstant(), first.toInstant());
      return Duration.between(first.toInstant(), second.toInstant());
   }

   private SyncFileComparator() {
   }
}
