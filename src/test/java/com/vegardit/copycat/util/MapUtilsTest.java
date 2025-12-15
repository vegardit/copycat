/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class MapUtilsTest {

   @Test
   void getBooleanParsesAndOptionallyRemoves() {
      final var map = new HashMap<String, Object>();
      assertThat(MapUtils.getBoolean(map, "x", false)).isNull();

      map.put("b1", true);
      assertThat(MapUtils.getBoolean(map, "b1", false)).isTrue();
      assertThat(map).containsKey("b1");

      map.put("b2", "true");
      assertThat(MapUtils.getBoolean(map, "b2", true)).isTrue();
      assertThat(map).doesNotContainKey("b2");
   }

   @Test
   void getFileTimeSinceDateOnlyUsesLocalStartOfDay() {
      final TimeZone originalTz = TimeZone.getDefault();
      try {
         TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

         final Map<String, Object> map = new HashMap<>();
         map.put("since", "2024-01-01");

         final FileTime ft = MapUtils.getFileTime(map, "since", false, DateTimeParser.DateOnlyInterpretation.START_OF_DAY);
         assertThat(ft).isNotNull();
         assert ft != null;

         final LocalDateTime t = LocalDateTime.ofInstant(ft.toInstant(), ZoneId.systemDefault());
         assertThat(t.toLocalDate()).isEqualTo(LocalDate.of(2024, 1, 1));
         assertThat(t.toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
      } finally {
         TimeZone.setDefault(originalTz);
      }
   }

   @Test
   void getFileTimeSinceUntilDateTimeKeepsProvidedTime() {
      final TimeZone originalTz = TimeZone.getDefault();
      try {
         TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

         final Map<String, Object> mapSince = new HashMap<>();
         mapSince.put("since", "2024-01-01T14:30");
         final FileTime sinceFt = MapUtils.getFileTime(mapSince, "since", false, DateTimeParser.DateOnlyInterpretation.START_OF_DAY);
         assertThat(sinceFt).isNotNull();
         assert sinceFt != null;
         final LocalDateTime since = LocalDateTime.ofInstant(sinceFt.toInstant(), ZoneId.systemDefault());
         assertThat(since.toLocalDate()).isEqualTo(LocalDate.of(2024, 1, 1));
         assertThat(since.toLocalTime()).isEqualTo(LocalTime.of(14, 30));

         final Map<String, Object> mapUntil = new HashMap<>();
         mapUntil.put("until", "2024-12-31 08:15");
         final FileTime untilFt = MapUtils.getFileTime(mapUntil, "until", false, DateTimeParser.DateOnlyInterpretation.END_OF_DAY);
         assertThat(untilFt).isNotNull();
         assert untilFt != null;
         final LocalDateTime until = LocalDateTime.ofInstant(untilFt.toInstant(), ZoneId.systemDefault());
         assertThat(until.toLocalDate()).isEqualTo(LocalDate.of(2024, 12, 31));
         assertThat(until.toLocalTime()).isEqualTo(LocalTime.of(8, 15));
      } finally {
         TimeZone.setDefault(originalTz);
      }
   }

   @Test
   void getFileTimeUntilDateOnlyUsesLocalEndOfDay() {
      final TimeZone originalTz = TimeZone.getDefault();
      try {
         TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

         final Map<String, Object> map = new HashMap<>();
         map.put("until", "2024-12-31");

         final FileTime ft = MapUtils.getFileTime(map, "until", false, DateTimeParser.DateOnlyInterpretation.END_OF_DAY);
         assertThat(ft).isNotNull();
         assert ft != null;

         final LocalDateTime t = LocalDateTime.ofInstant(ft.toInstant(), ZoneId.systemDefault());
         assertThat(t.toLocalDate()).isEqualTo(LocalDate.of(2024, 12, 31));
         assertThat(t.toLocalTime()).isEqualTo(LocalTime.MAX);
      } finally {
         TimeZone.setDefault(originalTz);
      }
   }

   @Test
   void getIntegerParsesNumbersAndStrings() {
      final var map = new HashMap<String, Object>();
      map.put("n", 12L);
      map.put("s", "34");

      assertThat(MapUtils.getInteger(map, "n", false)).isEqualTo(12);
      assertThat(MapUtils.getInteger(map, "s", true)).isEqualTo(34);
      assertThat(map).doesNotContainKey("s");
   }

   @Test
   void getIntegerThrowsHelpfulErrorOnInvalidValue() {
      final var map = new HashMap<String, Object>();
      map.put("threads", "nope");

      assertThatThrownBy(() -> MapUtils.getInteger(map, "threads", false)) //
         .isInstanceOf(IllegalArgumentException.class) //
         .hasMessageContaining("Cannot parse attribute [threads]") //
         .hasMessageContaining("as integer");
   }

   @Test
   void getPathParsesOrReturnsNull() {
      final var map = new HashMap<String, Object>();
      assertThat(MapUtils.getPath(map, "p", false)).isNull();

      map.put("p", "a/b");
      assertThat(MapUtils.getPath(map, "p", false)).isEqualTo(Path.of("a/b"));
   }

   @Test
   void getPathThrowsHelpfulErrorOnInvalidValue() {
      final var map = new HashMap<String, Object>();
      map.put("p", "\u0000");

      assertThatThrownBy(() -> MapUtils.getPath(map, "p", false)) //
         .isInstanceOf(IllegalArgumentException.class) //
         .hasMessageContaining("Cannot parse attribute [p]") //
         .hasMessageContaining("as path");
   }

   @Test
   void getStringListNormalizesInPlace() {
      final var list = new ArrayList<>();
      list.add(1);
      list.add(true);
      list.add("x");

      final var map = new HashMap<String, Object>();
      map.put("l", list);

      final var result = MapUtils.getStringList(map, "l", true);
      assertThat(map).doesNotContainKey("l");

      assertThat(result).isSameAs(list);
      assertThat(result).containsExactly("1", "true", "x");
      assertThat(result).allMatch(String.class::isInstance);
   }

   @Test
   void getStringListRejectsNonLists() {
      final var map = new HashMap<String, Object>();
      map.put("l", "nope");

      assertThatThrownBy(() -> MapUtils.getStringList(map, "l", false)) //
         .isInstanceOf(IllegalArgumentException.class) //
         .hasMessageContaining("Cannot parse attribute [l]") //
         .hasMessageContaining("as a list");
   }
}
