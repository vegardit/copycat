/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class ProgressTrackerTest {

   @Test
   void configuredForSmallStallTimeoutDoesNotFalsePositive() throws Exception {
      final var tracker = new ProgressTracker();
      tracker.configureForStallTimeoutMillis(200);
      tracker.reset();

      Thread.sleep(100);
      tracker.markProgress();

      Thread.sleep(150);

      assertThatCode(() -> tracker.checkStalled(200, "Test")).doesNotThrowAnyException();
   }
}
