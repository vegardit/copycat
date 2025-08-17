/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat;

import java.io.File;

import org.junit.jupiter.api.Test;

import picocli.AutoComplete;
import picocli.codegen.aot.graalvm.ReflectionConfigGenerator;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public class GeneratePicocliConfigsTest {

   @Test
   public void generateBashCompletion() {
      new File("target/bash").mkdirs();
      AutoComplete.main(CopyCatMain.class.getName(), "--force", "-o", "target/bash/bashcompletion.sh");
   }

   @Test
   public void generateGraalVMReflectionConfig() {
      ReflectionConfigGenerator.main( //
         CopyCatMain.class.getName(), //
         CopyCatMain.LoggingOptions.class.getName(), //
         "-o", "target/picocli-reflections.json" //
      );
   }
}
