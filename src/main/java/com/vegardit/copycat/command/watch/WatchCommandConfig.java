/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.watch;

import com.vegardit.copycat.command.sync.AbstractSyncCommandConfig;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public class WatchCommandConfig extends AbstractSyncCommandConfig<WatchCommandConfig> {

   @Override
   protected WatchCommandConfig newInstance() {
      return new WatchCommandConfig();
   }
}
