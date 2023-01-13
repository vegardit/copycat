/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import org.eclipse.jdt.annotation.Nullable;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class Booleans {

   public static boolean isTrue(@Nullable final Boolean val) {
      return val == Boolean.TRUE;
   }

   public static boolean not(@Nullable final Boolean val) {
      return val != Boolean.TRUE;
   }

   private Booleans() {
   }
}
