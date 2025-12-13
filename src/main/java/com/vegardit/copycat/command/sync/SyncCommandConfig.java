/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static com.vegardit.copycat.util.MapUtils.*;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;

import com.vegardit.copycat.util.DurationParser;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public class SyncCommandConfig extends AbstractSyncCommandConfig<SyncCommandConfig> {

   public @Nullable Boolean dryRun;
   public @Nullable Boolean delete;
   public @Nullable Boolean excludeOlderFiles;
   public @Nullable Boolean ignoreErrors;
   public @Nullable Boolean ignoreSymlinkErrors;
   /**
    * Abort a multi-threaded sync run if no progress is observed for this long.
    * <p>
    * A value of {@link Duration#ZERO} disables stall detection.
    */
   public @Nullable Duration stallTimeout;
   public @Nullable Integer threads;

   @Override
   protected SyncCommandConfig newInstance() {
      return new SyncCommandConfig();
   }

   @Override
   public void applyDefaults() {
      final var defaults = newInstance();
      defaults.dryRun = false;
      defaults.delete = false;
      defaults.excludeOlderFiles = false;
      defaults.ignoreErrors = false;
      defaults.ignoreSymlinkErrors = false;
      defaults.stallTimeout = Duration.ofMinutes(10);
      defaults.threads = 2;
      applyFrom(defaults, false);
      super.applyDefaults();
   }

   @Override
   public void applyFrom(final @Nullable SyncCommandConfig other, final boolean override) {
      if (other == null)
         return;
      super.applyFrom(other, override);

      if (override && other.dryRun != null || dryRun == null) {
         dryRun = other.dryRun;
      }
      if (override && other.delete != null || delete == null) {
         delete = other.delete;
      }
      if (override && other.excludeOlderFiles != null || excludeOlderFiles == null) {
         excludeOlderFiles = other.excludeOlderFiles;
      }
      if (override && other.ignoreErrors != null || ignoreErrors == null) {
         ignoreErrors = other.ignoreErrors;
      }
      if (override && other.ignoreSymlinkErrors != null || ignoreSymlinkErrors == null) {
         ignoreSymlinkErrors = other.ignoreSymlinkErrors;
      }
      if (override && other.stallTimeout != null || stallTimeout == null) {
         stallTimeout = other.stallTimeout;
      }
      if (override && other.threads != null || threads == null) {
         threads = other.threads;
      }
   }

   @Override
   public Map<String, Object> applyFrom(final @Nullable Map<String, Object> config, final boolean override) {
      if (config == null || config.isEmpty())
         return Collections.emptyMap();

      final var cfg = new HashMap<>(config);
      final var defaults = new SyncCommandConfig() {};
      defaults.dryRun = getBoolean(cfg, "dry-run", true);
      defaults.delete = getBoolean(cfg, "delete", true);
      defaults.excludeOlderFiles = getBoolean(cfg, "exclude-older-files", true);
      defaults.ignoreErrors = getBoolean(cfg, "ignore-errors", true);
      defaults.ignoreSymlinkErrors = getBoolean(cfg, "ignore-symlink-errors", true);
      defaults.stallTimeout = getDuration(cfg, "stall-timeout", true);
      defaults.threads = getInteger(cfg, "threads", true);
      applyFrom(defaults, override);
      return super.applyFrom(cfg, override);
   }

   private static @Nullable Duration getDuration(final Map<String, Object> map, final String key, final boolean remove) {
      final var value = remove ? map.remove(key) : map.get(key);
      if (value == null)
         return null;

      if (value instanceof final Number n) {
         final long minutes = n.longValue();
         if (minutes < 0)
            throw new IllegalArgumentException("Negative durations are not supported: \"" + minutes + '"');
         return minutes == 0 ? Duration.ZERO : Duration.ofMinutes(minutes);
      }

      final String raw = value.toString().strip();
      if (raw.isEmpty())
         return null;

      return parseDuration(raw, key);
   }

   static Duration parseDuration(final String raw, final String key) {
      // bare numbers are minutes (0 disables)
      try {
         final long asLong = Long.parseLong(raw);
         if (asLong < 0)
            throw new IllegalArgumentException("Negative durations are not supported: \"" + raw + '"');
         return asLong == 0 ? Duration.ZERO : Duration.ofMinutes(asLong);
      } catch (final NumberFormatException ignored) { /* fall through */ }

      try {
         return DurationParser.parseDuration(raw);
      } catch (final RuntimeException ex) {
         throw new IllegalArgumentException("Invalid duration value for [" + key + "]: " + raw, ex);
      }
   }
}
