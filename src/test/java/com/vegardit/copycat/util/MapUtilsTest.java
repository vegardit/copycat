/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;

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
   void getFileTimeUsesSystemDefaultZone() {
      final var map = new HashMap<String, Object>();
      final var ldt = LocalDateTime.of(2024, 1, 2, 3, 4, 5);
      map.put("t", ldt);

      final FileTime ft = MapUtils.getFileTime(map, "t", true);
      assert ft != null;
      assertThat(map).doesNotContainKey("t");

      final var expected = FileTime.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
      assertThat(ft.toInstant()).isEqualTo(expected.toInstant());
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
   void getLocalDateTimeParsesStringOrReturnsInstance() {
      final var map = new HashMap<String, Object>();
      final var ldt = LocalDateTime.of(2024, 12, 25, 14, 30, 45);
      map.put("a", ldt);
      map.put("b", "2024-12-25T14:30:45");

      assertThat(MapUtils.getLocalDateTime(map, "a", false)).isSameAs(ldt);
      assertThat(MapUtils.getLocalDateTime(map, "b", false)).isEqualTo(ldt);
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
