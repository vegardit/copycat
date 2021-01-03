/*
 * Copyright 2020 by Vegard IT GmbH (https://vegardit.com) and contributors.
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
   private final LongAdder filesCopiedDuration = new LongAdder();
   private final LongAdder filesCopiedSize = new LongAdder();
   private final LongAdder filesDeleted = new LongAdder();
   private final LongAdder filesDeletedDuration = new LongAdder();
   private final LongAdder filesDeletedSize = new LongAdder();
   private long startAt;

   private boolean statsLogged = false;

   public synchronized void logStats() {
      if (statsLogged)
         return;

      statsLogged = true;

      LOG.info("***************************************");
      LOG.info("The following errors occurred during sync:");
      for (final var error : errors) {
         LOG.error(error);
      }
      LOG.info("***************************************");
      LOG.info("Source dirs scanned: %s", dirsScanned);
      LOG.info("Source files scanned: %s", filesScanned);
      LOG.info("Source files copied: %s (%s) @ %s/s", filesCopied, //
         FileUtils.byteCountToDisplaySize(filesCopiedSize.longValue()), //
         filesCopiedSize.longValue() == 0 ? "0 bytes"
            : FileUtils.byteCountToDisplaySize((long) (filesCopiedSize.longValue() / (filesCopiedDuration.longValue() / 1000.0))) //
      );
      LOG.info("Target files deleted: %s (%s) @ %s/s", filesDeleted, //
         FileUtils.byteCountToDisplaySize(filesDeletedSize.longValue()), //
         filesDeletedSize.longValue() == 0 ? "0 bytes"
            : FileUtils.byteCountToDisplaySize((long) (filesDeletedSize.longValue() / (filesDeletedDuration.longValue() / 1000.0))) //
      );
      LOG.info("Errors: %s", errors.size());
      LOG.info("Duration: %s", DurationFormatUtils.formatDurationWords(System.currentTimeMillis() - startAt, true, true));
      LOG.info("***************************************");
   }

   public void onDirDeleted(final long duration, final long fileCount, final long fileSize) {
      filesDeletedDuration.add(duration);
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
      errors.add(ex.getClass().getSimpleName() + ": " + Strings.trim(ex.getMessage()));
   }

   public void onFileCopied(final long duration, final long size) {
      filesCopiedDuration.add(duration);
      filesCopied.increment();
      filesCopiedSize.add(size);
   }

   public void onFileDeleted(final long duration, final long size) {
      filesDeletedDuration.add(duration);
      filesDeleted.increment();
      filesDeletedSize.add(size);
   }

   public void start() {
      startAt = System.currentTimeMillis();
   }
}
