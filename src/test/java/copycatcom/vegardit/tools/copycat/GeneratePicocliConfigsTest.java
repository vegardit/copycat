/*
 * Copyright 2020 by Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package copycatcom.vegardit.tools.copycat;

import java.io.File;

import org.junit.Test;

import com.vegardit.copycat.CopyCatMain;

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
      ReflectionConfigGenerator.main(CopyCatMain.class.getName(), "-o", "target/picocli-reflections.json");
   }
}
