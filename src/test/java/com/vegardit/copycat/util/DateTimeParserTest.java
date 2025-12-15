/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive unit tests for {@link DateTimeParser}.
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class DateTimeParserTest {

   private static LocalDateTime parse(final String raw) {
      return DateTimeParser.parseDateTime(raw, DateTimeParser.DateOnlyInterpretation.START_OF_DAY);
   }

   @Nested
   @DisplayName("Absolute Date/Time Formats")
   class AbsoluteDateTimeTests {

      @Test
      @DisplayName("Parse date only (YYYY-MM-DD)")
      void testDateOnly() {
         final LocalDateTime result = parse("2024-12-25");
         assertThat(result).hasYear(2024).hasMonthValue(12).hasDayOfMonth(25).hasHour(0).hasMinute(0).hasSecond(0);
      }

      @Test
      @DisplayName("Parse date only with END_OF_DAY interpretation")
      void testDateOnlyEndOfDay() {
         final LocalDateTime result = DateTimeParser.parseDateTime("2024-12-25", DateTimeParser.DateOnlyInterpretation.END_OF_DAY);
         assertThat(result).hasYear(2024).hasMonthValue(12).hasDayOfMonth(25).hasHour(23).hasMinute(59).hasSecond(59).hasNano(999_999_999);
      }

      @Test
      @DisplayName("Parse date with hour requires minutes (YYYY-MM-DD HH not supported)")
      void testDateWithHourRequiresMinutes() {
         // Hour-only format is not supported as it's ambiguous
         assertThatThrownBy(() -> parse("2024-12-25 14")).isInstanceOf(DateTimeParseException.class).hasMessageContaining(
            "Unsupported date/time");
      }

      @Test
      @DisplayName("Parse date with hour and minute (YYYY-MM-DD HH:mm)")
      void testDateWithHourMinute() {
         final LocalDateTime result = parse("2024-12-25 14:30");
         assertThat(result).hasYear(2024).hasMonthValue(12).hasDayOfMonth(25).hasHour(14).hasMinute(30).hasSecond(0);
      }

      @Test
      @DisplayName("Parse full date-time (YYYY-MM-DD HH:mm:ss)")
      void testFullDateTime() {
         final LocalDateTime result = parse("2024-12-25 14:30:45");
         assertThat(result).hasYear(2024).hasMonthValue(12).hasDayOfMonth(25).hasHour(14).hasMinute(30).hasSecond(45);
      }

      @Test
      @DisplayName("Parse ISO format with T separator")
      void testIsoFormatWithT() {
         final LocalDateTime result = parse("2024-12-25T14:30:45");
         assertThat(result).hasYear(2024).hasMonthValue(12).hasDayOfMonth(25).hasHour(14).hasMinute(30).hasSecond(45);
      }

      @Test
      @DisplayName("Parse ISO format with UTC designator 'Z'")
      void testIsoFormatWithUtcDesignator() {
         final String input = "2025-10-26T22:00:00Z";
         final LocalDateTime result = parse(input);
         final LocalDateTime expected = LocalDateTime.ofInstant(Instant.parse(input), ZoneId.systemDefault());
         assertThat(result).isEqualTo(expected);
      }

      @Test
      @DisplayName("Parse with leading/trailing whitespace")
      void testWithWhitespace() {
         final LocalDateTime result = parse("  2024-12-25 14:30  ");
         assertThat(result).hasYear(2024).hasMonthValue(12).hasDayOfMonth(25).hasHour(14).hasMinute(30);
      }

      @Test
      @DisplayName("Invalid date format throws exception")
      void testInvalidDateFormat() {
         assertThatThrownBy(() -> parse("2024/12/25")).isInstanceOf(DateTimeParseException.class).hasMessageContaining(
            "Unsupported date/time");
      }

      @Test
      @DisplayName("Invalid date values throw exception")
      void testInvalidDateValues() {
         assertThatThrownBy(() -> parse("2024-13-32")).isInstanceOf(DateTimeParseException.class);
      }
   }

   @Nested
   @DisplayName("ISO-8601 Duration Formats")
   class IsoDurationTests {

      @Test
      @DisplayName("Parse PT duration (hours, minutes, seconds)")
      void testPtDuration() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("in PT3H2M10S");

         assertThat(result).isAfter(now);
         assertThat(ChronoUnit.SECONDS.between(now, result)).isCloseTo(3 * 3600 + 2 * 60 + 10, within(2L));
      }

      @Test
      @DisplayName("Parse P duration with days")
      void testPDurationWithDays() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("in P2DT3H");

         assertThat(result).isAfter(now);
         assertThat(ChronoUnit.HOURS.between(now, result)).isCloseTo(2 * 24 + 3, within(1L));
      }

      @Test
      @DisplayName("Parse PT duration - hours only")
      void testPtHoursOnly() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("in PT5H");

         assertThat(ChronoUnit.HOURS.between(now, result)).isCloseTo(5, within(1L));
      }

      @Test
      @DisplayName("Parse PT duration - minutes only")
      void testPtMinutesOnly() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("in PT30M");

         assertThat(ChronoUnit.MINUTES.between(now, result)).isCloseTo(30, within(1L));
      }

      @Test
      @DisplayName("Parse ISO duration with 'ago' for past times")
      void testIsoDurationPastSupported() {
         // Supports ISO-8601 durations combined with 'ago' to express past times,
         // e.g. "PT1H ago" is roughly 1 hour in the past.
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("PT1H ago");

         assertThat(result).isBefore(now);
         assertThat(ChronoUnit.MINUTES.between(result, now)).isCloseTo(60, within(2L));
      }

      @Test
      @DisplayName("ISO durations without 'in' or 'ago' default to past")
      void testPlainIsoDurationDefaultsToPast() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("PT1H");

         assertThat(result).isBefore(now);
         assertThat(ChronoUnit.MINUTES.between(result, now)).isCloseTo(60, within(2L));
      }

      @Test
      @DisplayName("Invalid ISO duration throws exception")
      void testInvalidIsoDuration() {
         assertThatThrownBy(() -> parse("PT")).isInstanceOf(DateTimeParseException.class);
      }
   }

   @Nested
   @DisplayName("Special Keywords")
   class SpecialKeywordTests {

      @Test
      @DisplayName("Parse 'today' keyword")
      void testToday() {
         final LocalDateTime result = parse("today");
         final LocalDateTime expected = LocalDate.now().atStartOfDay();
         assertThat(result).isEqualTo(expected);
      }

      @Test
      @DisplayName("Parse 'today' keyword with END_OF_DAY interpretation")
      void testTodayEndOfDay() {
         final LocalDateTime result = DateTimeParser.parseDateTime("today", DateTimeParser.DateOnlyInterpretation.END_OF_DAY);
         final LocalDateTime expected = LocalDate.now().atTime(LocalTime.MAX);
         assertThat(result).isEqualTo(expected);
      }

      @Test
      @DisplayName("Parse 'yesterday' keyword")
      void testYesterday() {
         final LocalDateTime result = parse("yesterday");
         final LocalDateTime expected = LocalDate.now().minusDays(1).atStartOfDay();
         assertThat(result).isEqualTo(expected);
      }

      @Test
      @DisplayName("Parse 'tomorrow' keyword")
      void testTomorrow() {
         final LocalDateTime result = parse("tomorrow");
         final LocalDateTime expected = LocalDate.now().plusDays(1).atStartOfDay();
         assertThat(result).isEqualTo(expected);
      }

      @Test
      @DisplayName("Parse 'yesterday' with time")
      void testYesterdayWithTime() {
         final LocalDateTime result = parse("yesterday 14:30");
         final LocalDateTime expected = LocalDate.now().minusDays(1).atTime(14, 30);
         assertThat(result).isEqualTo(expected);
      }

      @Test
      @DisplayName("Parse 'today' with time")
      void testTodayWithTime() {
         final LocalDateTime result = parse("today 09:15:30");
         final LocalDateTime expected = LocalDate.now().atTime(9, 15, 30);
         assertThat(result).isEqualTo(expected);
      }

      @Test
      @DisplayName("Parse 'tomorrow' with time")
      void testTomorrowWithTime() {
         final LocalDateTime result = parse("tomorrow 23:59");
         final LocalDateTime expected = LocalDate.now().plusDays(1).atTime(23, 59);
         assertThat(result).isEqualTo(expected);
      }

      @Test
      @DisplayName("Case insensitive keywords")
      void testCaseInsensitiveKeywords() {
         final LocalDateTime today1 = parse("TODAY");
         final LocalDateTime today2 = parse("Today");
         final LocalDateTime today3 = parse("today");

         assertThat(today1).isEqualTo(today2).isEqualTo(today3);
      }
   }

   @Nested
   @DisplayName("Relative Time Formats")
   class RelativeTimeTests {

      @Test
      @DisplayName("Parse 'ago' format with days")
      void testDaysAgo() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("3d ago");

         assertThat(result).isBefore(now);
         assertThat(ChronoUnit.DAYS.between(result, now)).isCloseTo(3, within(1L));
      }

      @Test
      @DisplayName("Parse 'ago' format with hours")
      void testHoursAgo() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("5 hours ago");

         assertThat(result).isBefore(now);
         assertThat(ChronoUnit.HOURS.between(result, now)).isCloseTo(5, within(1L));
      }

      @Test
      @DisplayName("Parse 'in' format with minutes")
      void testInMinutes() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("in 30 minutes");

         assertThat(result).isAfter(now);
         assertThat(ChronoUnit.MINUTES.between(now, result)).isCloseTo(30, within(1L));
      }

      @Test
      @DisplayName("Parse 'in' format with seconds")
      void testInSeconds() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("in 45 secs");

         assertThat(result).isAfter(now);
         assertThat(ChronoUnit.SECONDS.between(now, result)).isCloseTo(45, within(2L));
      }

      @Test
      @DisplayName("Parse complex relative time (multiple units)")
      void testComplexRelativeTime() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("2d 3h 15m ago");

         assertThat(result).isBefore(now);
         final long totalMinutes = ChronoUnit.MINUTES.between(result, now);
         assertThat(totalMinutes).isCloseTo(2 * 24 * 60 + 3 * 60 + 15, within(2L));
      }

      @Test
      @DisplayName("Parse with various unit aliases")
      void testUnitAliases() {
         final LocalDateTime now = LocalDateTime.now();

         // Days aliases
         assertThat(ChronoUnit.DAYS.between(parse("1d ago"), now)).isCloseTo(1, within(1L));
         assertThat(ChronoUnit.DAYS.between(parse("1 day ago"), now)).isCloseTo(1, within(1L));
         assertThat(ChronoUnit.DAYS.between(parse("2 days ago"), now)).isCloseTo(2, within(1L));

         // Hours aliases
         assertThat(ChronoUnit.HOURS.between(parse("1h ago"), now)).isCloseTo(1, within(1L));
         assertThat(ChronoUnit.HOURS.between(parse("1 hour ago"), now)).isCloseTo(1, within(1L));
         assertThat(ChronoUnit.HOURS.between(parse("2 hours ago"), now)).isCloseTo(2, within(1L));

         // Minutes aliases
         assertThat(ChronoUnit.MINUTES.between(parse("1m ago"), now)).isCloseTo(1, within(1L));
         assertThat(ChronoUnit.MINUTES.between(parse("1 min ago"), now)).isCloseTo(1, within(1L));
         assertThat(ChronoUnit.MINUTES.between(parse("1 minute ago"), now)).isCloseTo(1, within(1L));
         assertThat(ChronoUnit.MINUTES.between(parse("2 minutes ago"), now)).isCloseTo(2, within(1L));

         // Seconds aliases
         assertThat(ChronoUnit.SECONDS.between(parse("1s ago"), now)).isCloseTo(1, within(2L));
         assertThat(ChronoUnit.SECONDS.between(parse("1 sec ago"), now)).isCloseTo(1, within(2L));
         assertThat(ChronoUnit.SECONDS.between(parse("1 second ago"), now)).isCloseTo(1, within(2L));
         assertThat(ChronoUnit.SECONDS.between(parse("2 seconds ago"), now)).isCloseTo(2, within(2L));
      }

      @Test
      @DisplayName("Case insensitive parsing")
      void testCaseInsensitive() {
         final LocalDateTime now = LocalDateTime.now();

         final LocalDateTime result1 = parse("IN 5 HOURS");
         final LocalDateTime result2 = parse("in 5 hours");
         final LocalDateTime result3 = parse("In 5 Hours");

         assertThat(ChronoUnit.HOURS.between(now, result1)).isCloseTo(5, within(1L));
         assertThat(ChronoUnit.HOURS.between(now, result2)).isCloseTo(5, within(1L));
         assertThat(ChronoUnit.HOURS.between(now, result3)).isCloseTo(5, within(1L));
      }

      @Test
      @DisplayName("Parse without 'ago' or 'in' defaults to past")
      void testDefaultToPast() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("3h 30m");

         // Without 'ago' or 'in', it defaults to past
         assertThat(result).isBefore(now);
         assertThat(ChronoUnit.MINUTES.between(result, now)).isCloseTo(3 * 60 + 30, within(2L));
      }
   }

   @Nested
   @DisplayName("Bug Reproduction Tests")
   class BugTests {

      @Test
      @DisplayName("FIXED: Incomplete input validation - garbage in middle now rejected")
      void testIncompleteInputValidation() {
         // Fixed: Parser now validates that entire input is consumed
         // "garbage" in the middle is properly rejected
         assertThatThrownBy(() -> parse("3d garbage 2h ago")).isInstanceOf(DateTimeParseException.class).hasMessageContaining(
            "Unsupported date/time");
      }

      @Test
      @DisplayName("FIXED: Extra text after valid input now rejected")
      void testExtraTextIgnored() {
         // Fixed: Extra text after valid relative time is now rejected
         assertThatThrownBy(() -> parse("5h ago and some extra text")).isInstanceOf(DateTimeParseException.class).hasMessageContaining(
            "Unsupported date/time");
      }

      @Test
      @DisplayName("FIXED: Unknown units now cause parse failure")
      void testSilentUnitSkipping() {
         // Fixed: Unknown units now cause the parser to fail instead of being silently skipped
         assertThatThrownBy(() -> parse("3x 2h ago")).isInstanceOf(DateTimeParseException.class).hasMessageContaining(
            "Unsupported date/time");
      }

      @Test
      @DisplayName("FIXED: Overflow protection for large relative values")
      void testOverflowProtection() {
         // Very large relative offsets should now fail with a clear parse error
         assertThatThrownBy(() -> parse("999999999d ago")).isInstanceOf(DateTimeParseException.class).hasMessageContaining(
            "Unsupported date/time");

         assertThatThrownBy(() -> parse("99999999999999d ago")).isInstanceOf(DateTimeParseException.class).hasMessageContaining(
            "Unsupported date/time");
      }

      @Test
      @DisplayName("FIXED: Partial matches now rejected")
      void testPartialMatchAccepted() {
         // Fixed: If only part of the input matches, it's now properly rejected
         assertThatThrownBy(() -> parse("3d xyz")).isInstanceOf(DateTimeParseException.class).hasMessageContaining("Unsupported date/time");
      }
   }

   @Nested
   @DisplayName("Edge Cases and Error Handling")
   class EdgeCaseTests {

      @Test
      @DisplayName("Empty string throws exception")
      void testEmptyString() {
         assertThatThrownBy(() -> parse("")).isInstanceOf(IllegalArgumentException.class).hasMessage("input is blank");
      }

      @Test
      @DisplayName("Whitespace-only string throws exception")
      void testWhitespaceOnly() {
         assertThatThrownBy(() -> parse("   \t\n  ")).isInstanceOf(IllegalArgumentException.class).hasMessage("input is blank");
      }

      @Test
      @DisplayName("Zero values in relative time")
      void testZeroValues() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("0d 0h 0m ago");

         assertThat(ChronoUnit.SECONDS.between(result, now)).isCloseTo(0, within(2L));
      }

      @Test
      @DisplayName("Mixed formats not supported")
      void testMixedFormats() {
         // Cannot mix absolute and relative formats
         assertThatThrownBy(() -> parse("2024-12-25 3h ago")).isInstanceOf(DateTimeParseException.class);
      }

      @Test
      @DisplayName("Invalid unit in relative time")
      void testInvalidUnit() {
         assertThatThrownBy(() -> parse("3 weeks ago")).isInstanceOf(DateTimeParseException.class).hasMessageContaining(
            "Unsupported date/time");
      }

      @Test
      @DisplayName("Negative values in relative time")
      void testNegativeValues() {
         // Negative values are not valid in the current regex pattern
         assertThatThrownBy(() -> parse("-3d ago")).isInstanceOf(DateTimeParseException.class);
      }

      @Test
      @DisplayName("Very long input strings")
      void testVeryLongInput() {
         final String longInput = "1d ".repeat(1000) + "ago";
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse(longInput);

         assertThat(result).isBefore(now);
         // Should process all 1000 days
         assertThat(ChronoUnit.DAYS.between(result, now)).isCloseTo(1000, within(1L));
      }

      @Test
      @DisplayName("Boundary dates")
      void testBoundaryDates() {
         // Test parsing of boundary dates
         assertThatCode(() -> parse("1970-01-01")).doesNotThrowAnyException();

         assertThatCode(() -> parse("9999-12-31")).doesNotThrowAnyException();

         // Invalid dates should throw
         assertThatThrownBy(() -> parse("0000-01-01")).isInstanceOf(DateTimeParseException.class);
      }
   }

   @Nested
   @DisplayName("Performance and Special Cases")
   class PerformanceTests {

      @Test
      @DisplayName("Multiple spaces between tokens")
      void testMultipleSpaces() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("3d     2h     15m     ago");

         assertThat(result).isBefore(now);
         final long totalMinutes = ChronoUnit.MINUTES.between(result, now);
         assertThat(totalMinutes).isCloseTo(3 * 24 * 60 + 2 * 60 + 15, within(2L));
      }

      @Test
      @DisplayName("Tabs and newlines in input")
      void testTabsAndNewlines() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("3d\t2h\n15m ago");

         assertThat(result).isBefore(now);
         final long totalMinutes = ChronoUnit.MINUTES.between(result, now);
         assertThat(totalMinutes).isCloseTo(3 * 24 * 60 + 2 * 60 + 15, within(2L));
      }

      @Test
      @DisplayName("Leading zeros in numbers")
      void testLeadingZeros() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("003d 002h ago");

         assertThat(result).isBefore(now);
         assertThat(ChronoUnit.HOURS.between(result, now)).isCloseTo(3 * 24 + 2, within(1L));
      }

      @Test
      @DisplayName("Order of units in complex expressions")
      void testUnitOrder() {
         final LocalDateTime now = LocalDateTime.now();

         // Test different orderings produce same result
         final LocalDateTime result1 = parse("1d 2h 3m ago");
         final LocalDateTime result2 = parse("2h 1d 3m ago");
         final LocalDateTime result3 = parse("3m 2h 1d ago");

         final long minutes1 = ChronoUnit.MINUTES.between(result1, now);
         final long minutes2 = ChronoUnit.MINUTES.between(result2, now);
         final long minutes3 = ChronoUnit.MINUTES.between(result3, now);

         assertThat(minutes1).isCloseTo(1 * 24 * 60 + 2 * 60 + 3, within(2L));
         assertThat(minutes2).isCloseTo(minutes1, within(2L));
         assertThat(minutes3).isCloseTo(minutes1, within(2L));
      }

      @Test
      @DisplayName("Repeated units accumulate")
      void testRepeatedUnits() {
         final LocalDateTime now = LocalDateTime.now();
         final LocalDateTime result = parse("2h 3h ago");

         // Both hour values should be added: 2h + 3h = 5h
         assertThat(ChronoUnit.HOURS.between(result, now)).isCloseTo(5, within(1L));
      }
   }
}
