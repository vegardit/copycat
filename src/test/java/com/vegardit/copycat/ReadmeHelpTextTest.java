/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.asNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class ReadmeHelpTextTest {

   private static final Pattern OPTION_NAME = Pattern.compile("(?m)^\\s*(?:-[A-Za-z0-9],\\s*)?(--[A-Za-z0-9][A-Za-z0-9-]*)\\b");

   private static void assertReadmeHelpMatchesCommand(final String subcommand) throws Exception {
      final Set<String> expectedOptions = extractOptionNames(generateHelp(subcommand));
      final Set<String> actualOptions = extractOptionNames(extractReadmeHelpBlock("copycat " + subcommand + " --help"));
      expectedOptions.remove("--version");

      assertThat(actualOptions) //
         .as("README.md %s --help block options", subcommand) //
         .containsExactlyElementsOf(expectedOptions);
   }

   private static Set<String> extractOptionNames(final String helpText) {
      final var names = new LinkedHashSet<String>();
      final Matcher m = OPTION_NAME.matcher(helpText);
      while (m.find()) {
         names.add(asNonNull(m.group(1)));
      }
      return names;
   }

   private static String extractReadmeHelpBlock(final String commandLine) throws Exception {
      final List<String> lines = Files.readAllLines(Path.of("README.md"), StandardCharsets.UTF_8);

      final String marker = "$ " + commandLine;
      int markerLine = -1;
      for (int i = 0; i < lines.size(); i++) {
         if (marker.equals(lines.get(i))) {
            markerLine = i;
            break;
         }
      }
      if (markerLine == -1)
         throw new IllegalStateException("README.md does not contain help marker line: " + marker);

      int start = markerLine + 1;
      while (start < lines.size() && lines.get(start).isBlank()) {
         start++;
      }

      final var block = new StringBuilder();
      for (int i = start; i < lines.size(); i++) {
         final String line = lines.get(i);
         if ("```".equals(line)) {
            break;
         }
         block.append(line).append('\n');
      }
      return block.toString();
   }

   private static String generateHelp(final String subcommand) {
      final var root = new CommandLine(new CopyCatMain());
      final var sub = root.getSubcommands().get(subcommand);
      if (sub == null)
         throw new IllegalArgumentException("Unknown subcommand: " + subcommand);

      final var sw = new StringWriter();
      sub.usage(new PrintWriter(sw), CommandLine.Help.Ansi.OFF);
      return sw.toString();
   }

   @Test
   void syncCommandHelpInReadmeIsUpToDate() throws Exception {
      assertReadmeHelpMatchesCommand("sync");
   }

   @Test
   void watchCommandHelpInReadmeIsUpToDate() throws Exception {
      assertReadmeHelpMatchesCommand("watch");
   }

}
