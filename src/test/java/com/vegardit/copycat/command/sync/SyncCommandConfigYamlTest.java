/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.asNonNull;
import static org.assertj.core.api.Assertions.*;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.vegardit.copycat.util.YamlUtils;

/**
 * Tests YAML-based config parsing for sync tasks, including since/until semantics.
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class SyncCommandConfigYamlTest {

   @Nested
   @DisplayName("Absolute date/time in YAML")
   class AbsoluteYamlTests {

      @Test
      @DisplayName("Parse ISO dates for since/until from YAML")
      void testAbsoluteSinceUntilFromYaml() {
         final String yaml = """
            defaults:
              copy-acl: false
              delete: true
              delete-excluded: true
              dry-run: false
              exclude-older-files: false
              exclude-hidden-files: false
              exclude-system-files: true
              exclude-hidden-system-files: false
              threads: 2

            sync:
            - source: C:\\\\src
              target: C:\\\\dst
              since: 2024-01-01
              until: 2024-12-31
            """;

         final Map<String, Object> root = YamlUtils.parseYaml(new BufferedReader(new StringReader(yaml)));

         @SuppressWarnings("unchecked")
         final var defaultsMap = (Map<String, Object>) root.get("defaults");
         final var defaultsCfg = new SyncCommandConfig();
         defaultsCfg.applyFrom(defaultsMap, true);

         @SuppressWarnings("unchecked")
         final var syncList = asNonNull((List<Map<String, Object>>) root.get("sync"));
         final Map<String, Object> taskMap = syncList.get(0);
         final var taskCfg = new SyncCommandConfig();
         taskCfg.applyFrom(taskMap, true);

         // apply defaults like AbstractSyncCommand would do
         taskCfg.applyFrom(defaultsCfg, false);
         taskCfg.applyDefaults();

         assertThat(taskCfg.source).isNotNull();
         assertThat(taskCfg.target).isNotNull();

         final var modifiedFrom = taskCfg.modifiedFrom;
         assert modifiedFrom != null;

         final var modifiedTo = taskCfg.modifiedTo;
         assert modifiedTo != null;

         final LocalDateTime since = LocalDateTime.ofInstant(modifiedFrom.toInstant(), ZoneId.systemDefault());
         final LocalDateTime until = LocalDateTime.ofInstant(modifiedTo.toInstant(), ZoneId.systemDefault());

         assertThat(since.toLocalDate()).isEqualTo(LocalDate.of(2024, 1, 1));
         assertThat(until.toLocalDate()).isEqualTo(LocalDate.of(2024, 12, 31));
      }
   }

   @Nested
   @DisplayName("Relative expressions in YAML")
   class RelativeYamlTests {

      @Test
      @DisplayName("Parse 'ago' relative expressions in YAML")
      void testSinceAgoFromYaml() {
         final String yaml = """
            sync:
            - source: C:\\\\src
              target: C:\\\\dst
              since: "3h ago"
            """;

         final Map<String, Object> root = YamlUtils.parseYaml(new BufferedReader(new StringReader(yaml)));

         @SuppressWarnings("unchecked")
         final var syncList = asNonNull((List<Map<String, Object>>) root.get("sync"));
         final Map<String, Object> taskMap = syncList.get(0);
         final var taskCfg = new SyncCommandConfig();
         taskCfg.applyFrom(taskMap, true);
         taskCfg.applyDefaults();

         final var modifiedFrom = taskCfg.modifiedFrom;
         assert modifiedFrom != null;

         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime since = LocalDateTime.ofInstant(modifiedFrom.toInstant(), ZoneId.systemDefault());

         assertThat(since).isBefore(now);
         assertThat(ChronoUnit.MINUTES.between(since, now)).isCloseTo(3 * 60, within(2L));
      }

      @Test
      @DisplayName("Parse ISO duration with 'ago' in YAML")
      void testIsoDurationAgoFromYaml() {
         final String yaml = """
            sync:
            - source: C:\\\\src
              target: C:\\\\dst
              since: "PT1H ago"
            """;

         final Map<String, Object> root = YamlUtils.parseYaml(new BufferedReader(new StringReader(yaml)));

         @SuppressWarnings("unchecked")
         final var syncList = asNonNull((List<Map<String, Object>>) root.get("sync"));
         final Map<String, Object> taskMap = syncList.get(0);
         final var taskCfg = new SyncCommandConfig();
         taskCfg.applyFrom(taskMap, true);
         taskCfg.applyDefaults();

         final var modifiedFrom = taskCfg.modifiedFrom;
         assert modifiedFrom != null;

         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime since = LocalDateTime.ofInstant(modifiedFrom.toInstant(), ZoneId.systemDefault());

         assertThat(since).isBefore(now);
         assertThat(ChronoUnit.MINUTES.between(since, now)).isCloseTo(60, within(2L));
      }

      @Test
      @DisplayName("Relative expressions without 'ago' or 'in' in YAML default to past")
      void testRelativeWithoutDirectionInYamlDefaultsToPast() {
         final String yaml = """
            sync:
            - source: C:\\\\src
              target: C:\\\\dst
              since: "3h 30m"
            """;

         final Map<String, Object> root = YamlUtils.parseYaml(new BufferedReader(new StringReader(yaml)));

         @SuppressWarnings("unchecked")
         final var syncList = asNonNull((List<Map<String, Object>>) root.get("sync"));
         final Map<String, Object> taskMap = syncList.get(0);
         final var taskCfg = new SyncCommandConfig();
         taskCfg.applyFrom(taskMap, true);
         taskCfg.applyDefaults();

         final var modifiedFrom = taskCfg.modifiedFrom;
         assert modifiedFrom != null;

         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime since = LocalDateTime.ofInstant(modifiedFrom.toInstant(), ZoneId.systemDefault());

         assertThat(since).isBefore(now);
         assertThat(ChronoUnit.MINUTES.between(since, now)).isCloseTo(3 * 60 + 30, within(2L));
      }
   }

   @Test
   @DisplayName("Parse exclude-other-links from YAML")
   void testExcludeOtherLinksFromYaml() {
      final String yaml = """
         sync:
         - source: C:\\\\src
           target: C:\\\\dst
           exclude-other-links: true
         """;

      final Map<String, Object> root = YamlUtils.parseYaml(new BufferedReader(new StringReader(yaml)));

      @SuppressWarnings("unchecked")
      final var syncList = asNonNull((List<Map<String, Object>>) root.get("sync"));
      final Map<String, Object> taskMap = syncList.get(0);
      final var taskCfg = new SyncCommandConfig();
      taskCfg.applyFrom(taskMap, true);
      taskCfg.applyDefaults();

      assertThat(taskCfg.excludeOtherLinks).isTrue();
   }

   @Test
   @DisplayName("Parse stall-timeout from YAML")
   void testStallTimeoutFromYaml() {
      final String yaml = """
         sync:
         - source: C:\\\\src
           target: C:\\\\dst
           stall-timeout: 2m
         """;

      final Map<String, Object> root = YamlUtils.parseYaml(new BufferedReader(new StringReader(yaml)));

      @SuppressWarnings("unchecked")
      final var syncList = asNonNull((List<Map<String, Object>>) root.get("sync"));
      final Map<String, Object> taskMap = syncList.get(0);
      final var taskCfg = new SyncCommandConfig();
      taskCfg.applyFrom(taskMap, true);
      taskCfg.applyDefaults();

      assertThat(taskCfg.stallTimeout).isEqualTo(Duration.ofMinutes(2));
   }

   @Test
   @DisplayName("Parse numeric stall-timeout from YAML as minutes")
   void testNumericStallTimeoutFromYamlDefaultsToMinutes() {
      final String yaml = """
         sync:
         - source: C:\\\\src
           target: C:\\\\dst
           stall-timeout: 2
         """;

      final Map<String, Object> root = YamlUtils.parseYaml(new BufferedReader(new StringReader(yaml)));

      @SuppressWarnings("unchecked")
      final var syncList = asNonNull((List<Map<String, Object>>) root.get("sync"));
      final Map<String, Object> taskMap = syncList.get(0);
      final var taskCfg = new SyncCommandConfig();
      taskCfg.applyFrom(taskMap, true);
      taskCfg.applyDefaults();

      assertThat(taskCfg.stallTimeout).isEqualTo(Duration.ofMinutes(2));
   }
}
