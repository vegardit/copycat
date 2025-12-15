/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

import com.vegardit.copycat.command.sync.SyncCommandConfig;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class YamlUtilsTest {

   @Test
   void testSinceDateOnlyRendersAsLocalDateNotUtcShiftedDate() {
      final TimeZone originalTz = TimeZone.getDefault();
      try {
         TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

         final var cfg = new SyncCommandConfig();
         cfg.modifiedFrom = FileTime.from(LocalDate.of(2025, 11, 20).atStartOfDay(ZoneId.systemDefault()).toInstant());

         final String yaml = YamlUtils.toYamlString(cfg);
         assertThat(yaml).containsPattern("(?m)^since: ['\\\"]?2025-11-20['\\\"]?$");
         assertThat(yaml).doesNotContain("since: 2025-11-19");
      } finally {
         TimeZone.setDefault(originalTz);
      }
   }

   @Test
   void testDurationRendersAsScalar() {
      final var cfg = new SyncCommandConfig();
      cfg.stallTimeout = Duration.ofMinutes(10);

      final String yaml = YamlUtils.toYamlString(cfg);
      assertThat(yaml).containsPattern("(?m)^stall-timeout: ['\\\"]?10m['\\\"]?$");
      assertThat(yaml).doesNotContain("stall-timeout: {");
   }
}
