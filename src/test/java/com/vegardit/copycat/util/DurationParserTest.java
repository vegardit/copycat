/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.Test;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class DurationParserTest {

   @Test
   void parseIso8601() {
      assertThat(DurationParser.parseDuration("PT10M")).isEqualTo(Duration.ofMinutes(10));
   }

   @Test
   void parseHumanReadable() {
      assertThat(DurationParser.parseDuration("2h 30m")).isEqualTo(Duration.ofMinutes(150));
      assertThat(DurationParser.parseDuration("10m")).isEqualTo(Duration.ofMinutes(10));
      assertThat(DurationParser.parseDuration("45s")).isEqualTo(Duration.ofSeconds(45));
      assertThat(DurationParser.parseDuration("3d")).isEqualTo(Duration.ofDays(3));
   }

   @Test
   void rejectsNegativeValues() {
      assertThatThrownBy(() -> DurationParser.parseDuration("-PT10M")) //
         .isInstanceOf(DateTimeParseException.class) //
         .hasMessageContaining("Negative durations are not supported");
      assertThatThrownBy(() -> DurationParser.parseDuration("1h -2m")) //
         .isInstanceOf(DateTimeParseException.class) //
         .hasMessageContaining("Negative durations are not supported");
   }

   @Test
   void rejectsUnsupportedFormats() {
      assertThatThrownBy(() -> DurationParser.parseDuration("10")).isInstanceOf(DateTimeParseException.class);
      assertThatThrownBy(() -> DurationParser.parseDuration("10 minutes ago")).isInstanceOf(DateTimeParseException.class);
   }
}
