/*
 * Copyright 2020 by Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.watch;

import org.apache.commons.lang3.NotImplementedException;

import com.vegardit.copycat.command.AbstractCommand;

import picocli.CommandLine;

/**
 * TODO
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
@CommandLine.Command(name = "watch", //
   description = "Continously watches a directory recusivly for changes and synchronizes them to another directory." //
)
public class WatchCommand extends AbstractCommand {

   @Override
   protected void execute() throws Exception {
      throw new NotImplementedException();
   }
}
