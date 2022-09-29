/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static com.vegardit.copycat.util.MapUtils.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public class SyncCommandConfig extends AbstractSyncCommandConfig<SyncCommandConfig> {

   public Boolean dryRun;
   public Boolean delete;
   public Boolean excludeOlderFiles;
   public Boolean ignoreErrors;
   public Boolean ignoreSymlinkErrors;
   public Integer threads;

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
      defaults.threads = 2;
      applyFrom(defaults, false);
      super.applyDefaults();
   }

   @Override
   public void applyFrom(final SyncCommandConfig other, final boolean override) {
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
      if (override && other.threads != null || threads == null) {
         threads = other.threads;
      }
   }

   @Override
   public Map<String, Object> applyFrom(final Map<String, Object> config, final boolean override) {
      if (config == null || config.isEmpty())
         return Collections.emptyMap();

      final var cfg = new HashMap<>(config);
      final var defaults = new SyncCommandConfig() {};
      defaults.dryRun = getBoolean(cfg, "dry-run", true);
      defaults.delete = getBoolean(cfg, "delete", true);
      defaults.excludeOlderFiles = getBoolean(cfg, "exclude-older-files", true);
      defaults.ignoreErrors = getBoolean(cfg, "ignore-errors", true);
      defaults.ignoreSymlinkErrors = getBoolean(cfg, "ignore-symlink-errors", true);
      defaults.threads = getInteger(cfg, "threads", true);
      applyFrom(defaults, override);
      return super.applyFrom(cfg, override);
   }
}
