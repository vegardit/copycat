/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class SyncCommandCliArgsTest {

   @Test
   void testBeforeAndUntilAreMutuallyExclusive() {
      final var cmd = new SyncCommand();
      final var cli = new CommandLine(cmd);

      assertThat(catchThrowable(() -> cli.parseArgs("C:\\\\src", "C:\\\\dst", "--until", "2024-12-31", "--before", "2024-12-31")))
         .isInstanceOf(ParameterException.class);

      assertThat(catchThrowable(() -> cli.parseArgs("C:\\\\src", "C:\\\\dst", "--before", "2024-12-31", "--until", "2024-12-31")))
         .isInstanceOf(ParameterException.class);
   }

   @Test
   void testDateOnlyBeforeUsesLocalStartOfDayAndIsExclusive() {
      final var cmd = new SyncCommand();
      new CommandLine(cmd).parseArgs("C:\\\\src", "C:\\\\dst", "--before", "2024-12-31");

      final FileTime modifiedBefore = cmd.cfgCLI.modifiedBefore;
      assertThat(modifiedBefore).isNotNull();
      assert modifiedBefore != null;

      assertThat(cmd.cfgCLI.modifiedTo).isNull();

      final LocalDateTime before = LocalDateTime.ofInstant(modifiedBefore.toInstant(), ZoneId.systemDefault());
      assertThat(before.toLocalDate()).isEqualTo(LocalDate.of(2024, 12, 31));
      assertThat(before.toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
   }

   @Test
   void testDateOnlySinceUntilUsesLocalDayBounds() {
      final var cmd = new SyncCommand();
      new CommandLine(cmd).parseArgs("C:\\\\src", "C:\\\\dst", "--since", "2024-01-01", "--until", "2024-12-31");

      final FileTime modifiedFrom = cmd.cfgCLI.modifiedFrom;
      assertThat(modifiedFrom).isNotNull();
      assert modifiedFrom != null;

      final FileTime modifiedTo = cmd.cfgCLI.modifiedTo;
      assertThat(modifiedTo).isNotNull();
      assert modifiedTo != null;

      final LocalDateTime since = LocalDateTime.ofInstant(modifiedFrom.toInstant(), ZoneId.systemDefault());
      final LocalDateTime until = LocalDateTime.ofInstant(modifiedTo.toInstant(), ZoneId.systemDefault());

      assertThat(since.toLocalDate()).isEqualTo(LocalDate.of(2024, 1, 1));
      assertThat(since.toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
      assertThat(until.toLocalDate()).isEqualTo(LocalDate.of(2024, 12, 31));
      assertThat(until.toLocalTime()).isEqualTo(LocalTime.MAX);
   }
}
