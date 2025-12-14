/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.Nullable;

/**
 * Parses a duration expression into a {@link Duration}.
 *
 * <p>
 * Supported inputs:
 * </p>
 * <ul>
 * <li>ISO-8601 duration: {@code PT10M}, {@code P3DT2H}, ...</li>
 * <li>Human readable segments: {@code 2h 30m}, {@code 10m}, {@code 3d}, {@code 45s}</li>
 * </ul>
 *
 * <p>
 * Negative values are rejected.
 * </p>
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class DurationParser {

   static final Pattern HUMAN_READABLE_DURATION_PATTERN = Pattern.compile(
      "(\\d+)\\s*(d(?:ays?)?|h(?:ours?)?|m(?:in(?:s|utes?)?)?|s(?:ec(?:s|onds?)?)?)", Pattern.CASE_INSENSITIVE);
   private static final Pattern NEGATIVE_SEGMENT_PATTERN = Pattern.compile("(^|\\s)-\\d+");

   enum Unit {
      DAYS(ChronoUnit.DAYS, "d", "day", "days"),
      HOURS(ChronoUnit.HOURS, "h", "hour", "hours"),
      MINUTES(ChronoUnit.MINUTES, "m", "min", "mins", "minute", "minutes"),
      SECONDS(ChronoUnit.SECONDS, "s", "sec", "secs", "second", "seconds");

      final ChronoUnit chronoUnit;
      final String[] aliases;

      Unit(final ChronoUnit cu, final String... a) {
         chronoUnit = cu;
         aliases = a;
      }

      static @Nullable Unit of(final String token) {
         final String t = token.toLowerCase(Locale.ROOT);
         for (final Unit u : values()) {
            for (final String a : u.aliases)
               if (a.equals(t))
                  return u;
         }
         return null;
      }
   }

   /**
    * @throws IllegalArgumentException if the input is blank
    * @throws DateTimeParseException if the input cannot be parsed as duration
    */
   @SuppressWarnings("null")
   public static Duration parseDuration(final String raw) {
      final String str = raw.strip();
      if (str.isEmpty())
         throw new IllegalArgumentException("input is blank");

      if (str.startsWith("-") || NEGATIVE_SEGMENT_PATTERN.matcher(str).find())
         throw failNegative(raw);

      // ISO-8601 duration
      try {
         return Duration.parse(str);
      } catch (final DateTimeParseException ignored) { /* fall through */ }

      // Human readable segments, e.g. "2h 30m"
      final Matcher m = HUMAN_READABLE_DURATION_PATTERN.matcher(str);
      if (!m.find())
         throw fail(raw);

      m.reset();
      int lastEnd = 0;
      Duration total = Duration.ZERO;
      while (m.find()) {
         if (m.start() > lastEnd && !str.substring(lastEnd, m.start()).trim().isEmpty())
            throw fail(raw);

         final long value;
         try {
            value = Long.parseLong(m.group(1));
         } catch (final NumberFormatException ex) {
            throw fail(raw);
         }

         final var unit = DurationParser.Unit.of(m.group(2));
         if (unit == null)
            throw fail(raw); // Don't skip unknown units, fail instead

         try {
            total = total.plus(switch (unit) {
               case DAYS -> Duration.ofDays(value);
               case HOURS -> Duration.ofHours(value);
               case MINUTES -> Duration.ofMinutes(value);
               case SECONDS -> Duration.ofSeconds(value);
            });
         } catch (final ArithmeticException ex) {
            throw fail(raw);
         }

         lastEnd = m.end();
      }

      if (lastEnd < str.length() && !str.substring(lastEnd).trim().isEmpty())
         throw fail(raw);

      return total;
   }

   private static DateTimeParseException fail(final String in) {
      return new DateTimeParseException("Unsupported duration: \"" + in + '"', in, 0);
   }

   private static DateTimeParseException failNegative(final String in) {
      return new DateTimeParseException("Negative durations are not supported: \"" + in + '"', in, 0);
   }

   private DurationParser() {
   }
}
