/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;

import com.vegardit.copycat.util.FileAttrs;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class SyncFileComparatorTest {

   private record TestAttrs(FileTime lastModifiedTime, long size) implements BasicFileAttributes {

      @Override
      public FileTime creationTime() {
         return lastModifiedTime;
      }

      @Override
      public @Nullable Object fileKey() {
         // BasicFileAttributes allows an absent file key, and the comparator never reads it.
         return null;
      }

      @Override
      public boolean isDirectory() {
         return false;
      }

      @Override
      public boolean isOther() {
         return false;
      }

      @Override
      public boolean isRegularFile() {
         return true;
      }

      @Override
      public boolean isSymbolicLink() {
         return false;
      }

      @Override
      public FileTime lastAccessTime() {
         return lastModifiedTime;
      }
   }

   private static BasicFileAttributes attrs(final Instant lastModified, final long size) {
      return new TestAttrs(FileTime.from(lastModified), size);
   }

   private static FileAttrs targetAttrs(final Instant lastModified, final long size) {
      return FileAttrs.get(Path.of("target.txt"), attrs(lastModified, size));
   }

   @Test
   void treatsTimestampDeltaWithinToleranceAsEqualWhenSizesMatch() {
      final Instant base = Instant.parse("2026-06-16T12:00:00Z");

      final var result = SyncFileComparator.compareRegularFiles( //
         attrs(base.plusSeconds(1), 100), //
         targetAttrs(base, 100), //
         Duration.ofSeconds(2), //
         false);

      assertThat(result.shouldCopy()).isFalse();
      assertThat(result.copyCause()).isNull();
      assertThat(result.timestampToleranceApplied()).isTrue();
      assertThat(result.toleratedTimestampDelta()).isEqualTo(Duration.ofSeconds(1));
   }

   @Test
   void stillCopiesDifferentSizeWhenTimestampDeltaIsWithinTolerance() {
      final Instant base = Instant.parse("2026-06-16T12:00:00Z");

      final var result = SyncFileComparator.compareRegularFiles( //
         attrs(base.plusSeconds(1), 101), //
         targetAttrs(base, 100), //
         Duration.ofSeconds(2), //
         false);

      assertThat(result.shouldCopy()).isTrue();
      assertThat(result.copyCause()).isEqualTo(SyncFileCopyCause.LARGER);
      assertThat(result.timestampToleranceApplied()).isTrue();
      assertThat(result.toleratedTimestampDelta()).isEqualTo(Duration.ofSeconds(1));
   }

   @Test
   void copiesNewerSourceWhenTimestampDeltaExceedsTolerance() {
      final Instant base = Instant.parse("2026-06-16T12:00:00Z");

      final var result = SyncFileComparator.compareRegularFiles( //
         attrs(base.plusSeconds(3), 100), //
         targetAttrs(base, 100), //
         Duration.ofSeconds(2), //
         false);

      assertThat(result.shouldCopy()).isTrue();
      assertThat(result.copyCause()).isEqualTo(SyncFileCopyCause.NEWER);
      assertThat(result.timestampToleranceApplied()).isFalse();
      assertThat(result.toleratedTimestampDelta()).isEqualTo(Duration.ZERO);
   }

   @Test
   void skipsOlderSourceWhenExcludeOlderFilesIsEnabledAndTimestampDeltaExceedsTolerance() {
      final Instant base = Instant.parse("2026-06-16T12:00:00Z");

      final var result = SyncFileComparator.compareRegularFiles( //
         attrs(base, 100), //
         targetAttrs(base.plusSeconds(3), 100), //
         Duration.ofSeconds(2), //
         true);

      assertThat(result.shouldCopy()).isFalse();
      assertThat(result.copyCause()).isNull();
      assertThat(result.sourceOlderSkipped()).isTrue();
      assertThat(result.toleratedTimestampDelta()).isEqualTo(Duration.ZERO);
   }

   @Test
   void exactTimestampMatchDoesNotApplyToleranceEvenWhenConfigured() {
      final Instant base = Instant.parse("2026-06-16T12:00:00Z");

      final var result = SyncFileComparator.compareRegularFiles( //
         attrs(base, 100), //
         targetAttrs(base, 100), //
         Duration.ofSeconds(2), //
         false);

      assertThat(result.shouldCopy()).isFalse();
      assertThat(result.timestampToleranceApplied()).isFalse();
      assertThat(result.toleratedTimestampDelta()).isEqualTo(Duration.ZERO);
   }

   @Test
   void zeroToleranceUsesExactTimestampComparison() {
      final Instant base = Instant.parse("2026-06-16T12:00:00Z");

      final var result = SyncFileComparator.compareRegularFiles( //
         attrs(base.plusSeconds(1), 100), //
         targetAttrs(base, 100), //
         Duration.ZERO, //
         false);

      assertThat(result.shouldCopy()).isTrue();
      assertThat(result.copyCause()).isEqualTo(SyncFileCopyCause.NEWER);
      assertThat(result.timestampToleranceApplied()).isFalse();
      assertThat(result.toleratedTimestampDelta()).isEqualTo(Duration.ZERO);
   }
}
