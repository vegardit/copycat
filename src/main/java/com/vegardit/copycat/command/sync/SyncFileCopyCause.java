/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

/**
 * Copy reasons emitted by the sync file-copy path when a source file should be copied.
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
enum SyncFileCopyCause {
   NEW,
   REPLACE,
   NEWER,
   OLDER,
   LARGER,
   SMALLER
}
