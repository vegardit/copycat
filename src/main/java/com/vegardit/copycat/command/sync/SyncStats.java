/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;

import net.sf.jstuff.core.Strings;
import net.sf.jstuff.core.logging.Logger;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public class SyncStats {
   private static final Logger LOG = Logger.create();

   private final LongAdder dirsScanned = new LongAdder();
   private final LongAdder filesScanned = new LongAdder();
   private final Queue<String> errors = new ConcurrentLinkedDeque<>();
   private final LongAdder filesCopied = new LongAdder();
   private final LongAdder filesCopiedDurationMillis = new LongAdder();
   private final LongAdder filesCopiedSize = new LongAdder();
   private final LongAdder filesDeleted = new LongAdder();
   private final LongAdder filesDeletedDurationMillis = new LongAdder();
   private final LongAdder filesDeletedSize = new LongAdder();
   private long startAt;

   private boolean statsLogged = false;

   public synchronized void logStats() {
      if (statsLogged)
         return;

      statsLogged = true;

      if (!errors.isEmpty()) {
         LOG.warn("***************************************");
         LOG.warn("The following errors occurred during sync:");
         for (final var error : errors) {
            LOG.error(error);
         }
      }
      LOG.info("***************************************");
      LOG.info("Source dirs scanned: %s", dirsScanned);
      LOG.info("Source files scanned: %s", filesScanned);
      LOG.info("Source files copied: %s (%s) @ %s/s", filesCopied, //
         FileUtils.byteCountToDisplaySize(filesCopiedSize.longValue()), //
         filesCopiedSize.longValue() == 0 ? "0 bytes"
               : FileUtils.byteCountToDisplaySize((long) (filesCopiedSize.longValue() / (filesCopiedDurationMillis.longValue() / 1000.0))) //
      );
      LOG.info("Target files deleted: %s (%s) @ %s/s", filesDeleted, //
         FileUtils.byteCountToDisplaySize(filesDeletedSize.longValue()), //
         filesDeletedSize.longValue() == 0 ? "0 bytes"
               : FileUtils.byteCountToDisplaySize((long) (filesDeletedSize.longValue() / (filesDeletedDurationMillis.longValue() / 1000.0))) //
      );
      LOG.info("Errors: %s", errors.size());
      LOG.info("Duration: %s", DurationFormatUtils.formatDurationWords(System.currentTimeMillis() - startAt, true, true));
      LOG.info("***************************************");
   }

   public void onDirDeleted(final long duration, final long fileCount, final long fileSize) {
      filesDeletedDurationMillis.add(duration);
      filesDeleted.add(fileCount);
      filesDeletedSize.add(fileSize);
   }

   public void onDirScanned() {
      dirsScanned.increment();
   }

   public void onFileScanned() {
      filesScanned.increment();
   }

   public void onError(final Exception ex) {
      errors.add(ex.getClass().getSimpleName() + ": " + Strings.trimNullable(ex.getMessage()));
   }

   public void onFileCopied(final long durationMillis, final long size) {
      filesCopiedDurationMillis.add(durationMillis);
      filesCopied.increment();
      filesCopiedSize.add(size);
   }

   public void onFileDeleted(final long durationMillis, final long size) {
      filesDeletedDurationMillis.add(durationMillis);
      filesDeleted.increment();
      filesDeletedSize.add(size);
   }

   public synchronized void reset() {
      dirsScanned.reset();
      filesScanned.reset();
      errors.clear();
      filesCopied.reset();
      filesCopiedDurationMillis.reset();
      filesCopiedSize.reset();
      filesDeleted.reset();
      filesDeletedDurationMillis.reset();
      filesDeletedSize.reset();
      startAt = 0;
      statsLogged = false;
   }

   public void start() {
      startAt = System.currentTimeMillis();
   }
}
