/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;

import org.eclipse.jdt.annotation.Nullable;

import net.sf.jstuff.core.SystemUtils;
import net.sf.jstuff.core.logging.Logger;

/**
 * Minimal ANSI support for coloring and simple cursor movement.
 *
 * <p>
 * Understands the {@code @|style text|@} markers used throughout the codebase
 * and converts them to ANSI escape sequences. This intentionally supports only
 * the small set of styles used by copycat.
 * </p>
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class Ansi {

   private static final Logger LOG = Logger.create();

   private static final String ESC = "\u001B[";

   static {
      if (SystemUtils.IS_OS_WINDOWS && WindowsPowerShell.isAvailable()) {
         try {
            try (InputStream is = Ansi.class.getResourceAsStream("/enable-ansi-colors.ps1")) {
               if (is != null) {
                  final var script = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                  WindowsPowerShell.executeOnConsoleAsync(script, Duration.ofSeconds(2)).join();
               }
            }
         } catch (final Throwable ex) { // CHECKSTYLE:IGNORE .*
            if (ex instanceof InterruptedException) {
               Thread.currentThread().interrupt();
            }
            LOG.warn("Failed to enable ANSI support. " + ex.getClass().getName() + ": " + ex.getMessage());
         }
      }
   }

   private static final boolean ENABLED = isEnabled();

   private static String applyStyles(final String styleSpec, final String segment) {
      if (!ENABLED)
         return segment;

      final String[] tokens = styleSpec.split("[,;+]");
      final var codes = new ArrayList<String>();

      for (String token : tokens) {
         token = token.trim();
         if (token.isEmpty()) {
            continue;
         }
         final String normalized = token.toLowerCase(Locale.ROOT);
         switch (normalized) {
            case "red":
               codes.add("31");
               break;
            case "green":
               codes.add("32");
               break;
            case "yellow":
               codes.add("33");
               break;
            case "magenta":
               codes.add("35");
               break;
            case "faint":
               // dim
               codes.add("2");
               break;
            default:
               break;
         }
      }

      if (codes.isEmpty())
         return segment;

      return ESC + String.join(";", codes) + "m" + segment + ESC + "0m";
   }

   /**
    * Moves the cursor one line up and clears that line.
    */
   public static String cursorUpLineAndClear() {
      if (!ENABLED)
         return "";
      return ESC + "1A" + ESC + "2K";
   }

   private static boolean isEnabled() {
      final String noColor = System.getenv("NO_COLOR");
      if (noColor != null && !noColor.isEmpty())
         return false;
      return true;
   }

   private static String processMarkers(final String text, final boolean stripOnly) {
      final var textLen = text.length();
      if (textLen < 1)
         return "";

      final int markerPos = text.indexOf("@|");
      if (markerPos == -1)
         return text;

      final var sb = new StringBuilder(textLen + 16);
      int pos = 0;
      while (true) {
         final int start = text.indexOf("@|", pos);
         if (start == -1) {
            sb.append(text, pos, textLen);
            break;
         }
         sb.append(text, pos, start);

         final int styleStart = start + 2;
         final int styleEnd = text.indexOf(' ', styleStart);
         if (styleEnd == -1) {
            sb.append(text, start, textLen);
            break;
         }

         final int end = text.indexOf("|@", styleEnd + 1);
         if (end == -1) {
            sb.append(text, start, textLen);
            break;
         }

         final String styleSpec = text.substring(styleStart, styleEnd);
         final String segment = text.substring(styleEnd + 1, end);
         sb.append(stripOnly ? segment : applyStyles(styleSpec, segment));

         pos = end + 2;
      }
      return sb.toString();
   }

   /**
    * Renders {@code @|style text|@} markers to ANSI escape sequences.
    */
   public static String render(final @Nullable String text) {
      if (text == null)
         return "null";

      return processMarkers(text, false);
   }

   /**
    * Renders and applies {@link String#format(String, Object...)}.
    */
   public static String render(final @Nullable String template, final Object... args) {
      return String.format(render(template), args);
   }

   /**
    * Removes {@code @|style text|@} markers but keeps the inner text.
    */
   public static @Nullable String stripMarkers(final @Nullable String text) {
      if (text == null)
         return null;
      return processMarkers(text, true);
   }

   private Ansi() {
   }
}
