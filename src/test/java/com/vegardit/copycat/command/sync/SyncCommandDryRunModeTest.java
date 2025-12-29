/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class SyncCommandDryRunModeTest {

   @Test
   void mixingDryRunAndNonDryRunTasksIsRejected(@TempDir final Path tempDir) throws IOException {
      final Path src1 = Files.createDirectory(tempDir.resolve("src1"));
      final Path dst1 = Files.createDirectory(tempDir.resolve("dst1"));
      final Path src2 = Files.createDirectory(tempDir.resolve("src2"));
      final Path dst2 = Files.createDirectory(tempDir.resolve("dst2"));

      final Path cfg = tempDir.resolve("cfg.yaml");
      Files.writeString(cfg, "" //
            + "sync:\r\n" //
            + "- source: '" + src1 + "'\r\n" //
            + "  target: '" + dst1 + "'\r\n" //
            + "  dry-run: true\r\n" //
            + "- source: '" + src2 + "'\r\n" //
            + "  target: '" + dst2 + "'\r\n");

      final var cmd = new SyncCommand();
      final var cli = new CommandLine(cmd);
      cli.parseArgs("--config", cfg.toString());

      assertThatThrownBy(cmd::call) //
         .isInstanceOf(ParameterException.class) //
         .hasMessageContaining("Mixing dry-run and non-dry-run sync tasks is not supported");
   }
}
