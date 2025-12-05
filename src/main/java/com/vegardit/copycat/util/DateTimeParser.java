/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.time.DateTimeException;
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
 * <p>
 * This utility provides a single entry point {@link #parseDateTime(String)}
 * that accepts a variety of absolute and relative input formats and normalizes
 * them into a {@link LocalDateTime} based on the current system clock.
 * </p>
 *
 * <p>
 * For a detailed list of supported formats and error conditions, see
 * {@link #parseDateTime(String)}.
 * </p>
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

   /**
    * Parses an absolute or relative date/time expression into a {@link LocalDateTime}.
    *
    * <h3>Accepted formats</h3>
    * <ul>
    * <li>Absolute calendar dates: {@code yyyy-MM-dd}, optionally with a time part
    * {@code HH:mm[:ss]} or {@code 'T'HH:mm[:ss]}, for example {@code "2024-12-25"},
    * {@code "2024-12-25 14:30"} or {@code "2024-12-25T14:30:45"}.</li>
    * <li>Keywords relative to today: {@code today}, {@code yesterday}, {@code tomorrow}
    * with an optional time part, for example {@code "today 09:30"} or
    * {@code "yesterday 23:59:10"}.</li>
    * <li>ISO-8601 durations: {@code PnDTnHnMnS} (for example {@code "PT3H2M10S"})
    * optionally combined with {@code in} or {@code ago}, for example {@code "in PT3H"}
    * or {@code "PT1H ago"}. Bare ISO durations without {@code in} or {@code ago} are
    * interpreted as offsets in the past relative to now (for example {@code "PT1H"}
    * is roughly one hour ago).</li>
    * <li>Human readable relative expressions using days, hours, minutes and seconds
    * (including aliases such as {@code d}, {@code day}, {@code days}, {@code h},
    * {@code hour}, {@code hours}, {@code m}, {@code min}, {@code minute},
    * {@code minutes}, {@code s}, {@code sec}, {@code second}, {@code seconds})
    * combined with {@code in} or {@code ago}, for example {@code "3d 2h 5m ago"} or
    * {@code "in 4 hours"}. Without {@code in} or {@code ago} such expressions are
    * interpreted as offsets in the past (for example {@code "3h 30m"}).</li>
    * </ul>
    *
    * <p>
    * Inputs are trimmed and parsed case insensitively where applicable. Unsupported
    * or mixed formats (for example combining an absolute date with relative modifiers)
    * result in a {@link DateTimeParseException}. Blank input results in an
    * {@link IllegalArgumentException}.
    * </p>
    *
    * @param raw user supplied date/time expression
    * @return parsed {@link LocalDateTime} instance
    * @throws IllegalArgumentException if the input is blank
    * @throws DateTimeParseException if the input cannot be parsed as any supported format
    */
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
            final LocalDate date;
            if (lowerStr.startsWith("yesterday")) {
               date = LocalDate.now().minusDays(1);
            } else if (lowerStr.startsWith("tomorrow")) {
               date = LocalDate.now().plusDays(1);
            } else {
               date = LocalDate.now();
            }

            // Try to parse the time part
            try {
               final String timePart = parts[1];
               if (timePart.matches("\\d{1,2}:\\d{2}(:\\d{2})?")) {
                  final String[] timeParts = timePart.split(":");
                  @SuppressWarnings("null")
                  final int hour = Integer.parseInt(timeParts[0]);
                  @SuppressWarnings("null")
                  final int minute = Integer.parseInt(timeParts[1]);
                  @SuppressWarnings("null")
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

      // use a single base time for ISO durations and relative expressions
      final LocalDateTime base = LocalDateTime.now();

      // ISO-8601 durations combined with "in"/"ago", e.g. "in PT2H", "PT2H ago"
      final String lowerForIso = str.toLowerCase(Locale.ROOT);
      if (lowerForIso.startsWith("in ")) {
         final String durPart = str.substring(3).trim();
         try {
            return base.plus(Duration.parse(durPart));
         } catch (final DateTimeParseException ignored) { /* fall through */ }
      } else if (lowerForIso.endsWith(" ago")) {
         final String durPart = str.substring(0, str.length() - 4).trim();
         try {
            return base.minus(Duration.parse(durPart));
         } catch (final DateTimeParseException ignored) { /* fall through */ }
      }

      // bare ISO-8601 durations default to past relative to now
      try {
         return base.minus(Duration.parse(str));
      } catch (final DateTimeParseException ignored) { /* fall through */ }

      // Check for mixed formats (date components with relative time keywords)
      if (str.matches(".*\\d{4}-\\d{2}-\\d{2}.*") && (str.contains(" ago") || lowerForIso.contains("in ")))
         throw fail(raw);

      // relative formats: default to past, unless "in ..." is used
      boolean future = false;
      if (lowerForIso.startsWith("in ")) {
         future = true;
         str = str.substring(3);
      } else if (lowerForIso.endsWith(" ago")) {
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

      LocalDateTime t = base;
      while (m.find()) {
         // Check for gaps between matches (invalid content)
         if (m.start() > lastEnd && !str.substring(lastEnd, m.start()).trim().isEmpty())
            throw fail(raw);

         final int value;
         try {
            @SuppressWarnings("null")
            final int parsed = Integer.parseInt(m.group(1));
            value = parsed;
         } catch (final NumberFormatException ex) {
            // Guard against numeric overflows in the amount specification itself
            throw fail(raw);
         }

         // Prevent absurdly large relative offsets even if they are still within LocalDateTime's range
         if (value > 100_000_000)
            throw fail(raw);
         @SuppressWarnings("null")
         final Unit unit = Unit.of(m.group(2));
         if (unit == null)
            throw fail(raw); // Don't skip unknown units, fail instead
         try {
            t = future ? t.plus(value, unit.chronoUnit) : t.minus(value, unit.chronoUnit);
         } catch (final DateTimeException ex) {
            // Guard against overflowing the LocalDateTime range with extreme values
            throw fail(raw);
         }
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
