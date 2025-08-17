/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.Nullable;

/**
 * Parses absolute or relative date/time expressions into {@link LocalDateTime}.
 *
 * <h3>Accepted formats</h3>
 * <ul>
 * <li>{@code YYYY-MM-DD[ HH[:mm[:ss]]]}</li>
 * <li>{@code YYYY-MM-DD'T'HH:mm[:ss]}</li>
 * <li>ISO-8601 durations &nbsp;({@code PT3H2M10S})</li>
 * <li>{@code 3d 2h 5m ago} / {@code in 4 hours}</li>
 * </ul>
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class DateTimeParser {

   @SuppressWarnings("null")
   private static final DateTimeFormatter ABSOLUTE = new DateTimeFormatterBuilder() //
      .appendPattern("yyyy-MM-dd") //
      .optionalStart().appendPattern("'T'HH:mm[:ss]").optionalEnd() //
      .optionalStart().appendPattern(" HH:mm[:ss]").optionalEnd() //
      .parseDefaulting(ChronoField.HOUR_OF_DAY, 0) //
      .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0) //
      .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0) //
      .toFormatter(Locale.ROOT);

   private static final Pattern RELATIVE = Pattern.compile("(\\d+)\\s*(d(?:ays?)?|h(?:ours?)?|m(?:in(?:s|utes?)?)?|s(?:ec(?:s|onds?)?)?)",
      Pattern.CASE_INSENSITIVE);

   private enum Unit {
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

   public static LocalDateTime parseDateTime(final String raw) {
      String str = raw.strip();
      if (str.isEmpty())
         throw new IllegalArgumentException("input is blank");

      // Handle special keywords
      final String lowerStr = str.toLowerCase(Locale.ROOT);
      if ("today".equals(lowerStr))
         return LocalDate.now().atStartOfDay();
      if ("yesterday".equals(lowerStr))
         return LocalDate.now().minusDays(1).atStartOfDay();
      if ("tomorrow".equals(lowerStr))
         return LocalDate.now().plusDays(1).atStartOfDay();

      // Handle "yesterday HH:mm" or "today HH:mm" format
      if (lowerStr.startsWith("yesterday ") || lowerStr.startsWith("today ") || lowerStr.startsWith("tomorrow ")) {
         final String[] parts = str.split(" ", 2);
         if (parts.length == 2) {
            LocalDate date;
            if (lowerStr.startsWith("yesterday"))
               date = LocalDate.now().minusDays(1);
            else if (lowerStr.startsWith("tomorrow"))
               date = LocalDate.now().plusDays(1);
            else
               date = LocalDate.now();

            // Try to parse the time part
            try {
               final String timePart = parts[1];
               if (timePart.matches("\\d{1,2}:\\d{2}(:\\d{2})?")) {
                  final String[] timeParts = timePart.split(":");
                  final int hour = Integer.parseInt(timeParts[0]);
                  final int minute = Integer.parseInt(timeParts[1]);
                  final int second = timeParts.length > 2 ? Integer.parseInt(timeParts[2]) : 0;

                  if (hour >= 0 && hour < 24 && minute >= 0 && minute < 60 && second >= 0 && second < 60)
                     return date.atTime(hour, minute, second);
               }
            } catch (final NumberFormatException ignored) { /* fall through */ }
         }
      }

      // absolute formats
      try {
         return LocalDateTime.from(ABSOLUTE.parse(str));
      } catch (final DateTimeParseException ignored) { /* fall through */ }

      // ISO-8601 durations
      try {
         return LocalDateTime.now().plus(Duration.parse(str));
      } catch (final DateTimeParseException ignored) { /* fall through */ }

      // Check for mixed formats (date components with relative time keywords)
      if (str.matches(".*\\d{4}-\\d{2}-\\d{2}.*") && (str.contains(" ago") || str.toLowerCase(Locale.ROOT).contains("in ")))
         throw fail(raw);

      // relative formats
      boolean future = false;
      if (str.toLowerCase(Locale.ROOT).startsWith("in ")) {
         future = true;
         str = str.substring(3);
      } else if (str.endsWith(" ago")) {
         str = str.substring(0, str.length() - 4);
      }

      // Check for negative values in relative time expressions
      if (str.matches(".*\\s-\\d+.*") || str.startsWith("-"))
         throw fail(raw);

      final Matcher m = RELATIVE.matcher(str);
      if (!m.find())
         throw fail(raw);

      // Validate that the entire string is consumed by the pattern
      m.reset();
      int lastEnd = 0;

      LocalDateTime t = LocalDateTime.now();
      while (m.find()) {
         // Check for gaps between matches (invalid content)
         if (m.start() > lastEnd && !str.substring(lastEnd, m.start()).trim().isEmpty())
            throw fail(raw);

         @SuppressWarnings("null")
         final int value = Integer.parseInt(m.group(1));
         @SuppressWarnings("null")
         final Unit unit = Unit.of(m.group(2));
         if (unit == null)
            throw fail(raw); // Don't skip unknown units, fail instead
         t = future ? t.plus(value, unit.chronoUnit) : t.minus(value, unit.chronoUnit);
         lastEnd = m.end();
      }

      // Check if there's unparsed content at the end
      if (lastEnd < str.length() && !str.substring(lastEnd).trim().isEmpty())
         throw fail(raw);

      return t;
   }

   private static DateTimeParseException fail(final String in) {
      return new DateTimeParseException("Unsupported date/time: \"" + in + '"', in, 0);
   }

   private DateTimeParser() {
   }

}
