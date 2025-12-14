/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks progress timestamps using {@link System#nanoTime()} (monotonic clock).
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class ProgressTracker {

   public static final long MIN_UPDATE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

   private final AtomicLong lastProgressAtNanos = new AtomicLong(System.nanoTime());

   public void checkStalled(final long stallTimeoutMillis, final String operation) throws IOException {
      if (stallTimeoutMillis <= 0)
         return;

      final long stalledForMillis = millisSinceLastProgress();
      if (stalledForMillis < stallTimeoutMillis)
         return;

      throw new IOException(operation + " appears stuck (no progress for ~" + TimeUnit.MILLISECONDS.toMinutes(stalledForMillis) + " min).");
   }

   public void markProgress() {
      final long nowNanos = System.nanoTime();
      for (;;) {
         final long lastNanos = lastProgressAtNanos.get();
         if (nowNanos - lastNanos <= MIN_UPDATE_INTERVAL_NANOS || nowNanos <= lastNanos)
            return;
         if (lastProgressAtNanos.compareAndSet(lastNanos, nowNanos))
            return;
      }
   }

   public long millisSinceLastProgress() {
      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastProgressAtNanos.get());
   }

   public void reset() {
      lastProgressAtNanos.set(System.nanoTime());
   }
}
