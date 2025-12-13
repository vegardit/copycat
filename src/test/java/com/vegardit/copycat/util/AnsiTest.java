/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class AnsiTest {

   private static final String ESC = "\u001B[";

   @Test
   void cursorUpLineAndClearMatchesEnabledState() {
      final var cursor = Ansi.cursorUpLineAndClear();
      if (cursor.isEmpty()) {
         assertThat(Ansi.render("@|red x|@")).doesNotContain(ESC);
      } else {
         assertThat(cursor).isEqualTo(ESC + "1A" + ESC + "2K");
      }
   }

   @Test
   void renderRemovesMarkersAndOptionallyAddsEscapes() {
      assertThat(Ansi.render((String) null)).isEqualTo("null");
      assertThat(Ansi.render("plain")).isEqualTo("plain");

      final var rendered = Ansi.render("a @|red,faint hello|@ b");
      assertThat(rendered).contains("hello").doesNotContain("@|").doesNotContain("|@");

      if (rendered.contains(ESC)) {
         assertThat(rendered).isEqualTo("a " + ESC + "31;2mhello" + ESC + "0m b");
      } else {
         assertThat(rendered).isEqualTo("a hello b");
      }
   }

   @Test
   void renderWithFormatArgs() {
      assertThat(Ansi.render("Hello %s", "world")).isEqualTo("Hello world");
   }

   @Test
   void stripMarkersKeepsInnerText() {
      assertThat(Ansi.stripMarkers(null)).isNull();
      assertThat(Ansi.stripMarkers("plain")).isEqualTo("plain");
      assertThat(Ansi.stripMarkers("@|red hello|@")).isEqualTo("hello");
      assertThat(Ansi.stripMarkers("a @|red hello|@ b")).isEqualTo("a hello b");
   }

   @Test
   void stripMarkersLeavesBrokenMarkersUnchanged() {
      assertThat(Ansi.stripMarkers("@|red hello")).isEqualTo("@|red hello");
      assertThat(Ansi.stripMarkers("@|red|@")).isEqualTo("@|red|@");
   }
}
