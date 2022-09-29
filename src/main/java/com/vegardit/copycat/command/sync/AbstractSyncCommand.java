/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.yaml.snakeyaml.Yaml;

import com.vegardit.copycat.command.AbstractCommand;
import com.vegardit.copycat.util.YamlUtils;

import net.sf.jstuff.core.SystemUtils;
import net.sf.jstuff.core.logging.Logger;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public abstract class AbstractSyncCommand<C extends AbstractSyncCommandConfig<C>> extends AbstractCommand {

   private static final Logger LOG = Logger.create();

   protected static final LinkOption[] NOFOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

   protected static final CopyOption[] SYMLINK_COPY_OPTIONS = { //
      LinkOption.NOFOLLOW_LINKS, //
      StandardCopyOption.COPY_ATTRIBUTES, //
      StandardCopyOption.REPLACE_EXISTING //
   };

   private final Supplier<C> cfgInstanceFactory;
   protected final C cfgCLI;
   private C cfgYamlDefaults;
   private List<C> cfgYamlSyncTasks;

   protected AbstractSyncCommand(final Supplier<C> cfgInstanceFactory) {
      this.cfgInstanceFactory = cfgInstanceFactory;
      this.cfgCLI = cfgInstanceFactory.get();
   }

   protected abstract void doExecute(List<C> tasks) throws Exception;

   @Override
   protected final void execute() throws Exception {
      // if SOURCE is set, TARGET must be set too
      if (cfgCLI.source != null && cfgCLI.target == null)
         throw new ParameterException(commandSpec.commandLine(), "Missing required parameter: 'TARGET'");

      // if no tasks are configured in YAML, SOURCE/TARGET must be set
      if (cfgCLI.source == null && cfgYamlSyncTasks == null)
         throw new ParameterException(commandSpec.commandLine(), "Missing required parameters: 'SOURCE', 'TARGET'");

      final var taskCfgs = new ArrayList<C>();
      if (cfgYamlSyncTasks == null) {
         cfgCLI.applyFrom(cfgYamlDefaults, false);
         cfgCLI.applyDefaults();
         cfgCLI.compute();
         taskCfgs.add(cfgCLI);
      } else {
         for (final var cfgYamlTask : cfgYamlSyncTasks) {
            cfgYamlTask.applyFrom(cfgCLI, false);
            cfgYamlTask.applyFrom(cfgYamlDefaults, false);
            cfgYamlTask.applyDefaults();
            cfgYamlTask.compute();
            taskCfgs.add(cfgYamlTask);
         }
      }

      for (final var taskCfg : taskCfgs) {
         if (Files.exists(taskCfg.targetRootAbsolute, NOFOLLOW_LINKS) && Files.isSameFile(taskCfg.sourceRootAbsolute,
            taskCfg.targetRootAbsolute))
            throw new ParameterException(commandSpec.commandLine(), "Source and target path point to the same filesystem entry ["
               + taskCfg.sourceRootAbsolute.toRealPath() + "]!");

         if (taskCfg.copyACL && SystemUtils.IS_OS_WINDOWS && !SystemUtils.isRunningAsAdmin()) {
            LOG.warn("Option --copy-acl was specified but process is not running with elevated administrative permissions."
               + " ACL will be copied but excluding ownership information.");
         }
      }

      doExecute(taskCfgs);
   }

   @SuppressWarnings("unchecked")
   @Option(names = "--config", paramLabel = "<path>", description = "Path to a YAML config file.")
   private void setConfig(final String configPath) throws IOException {
      try (var in = Files.newBufferedReader(Path.of(configPath))) {
         final Map<String, Object> yamlCfg = new Yaml().load(in);

         // process defaults
         final var yamlDefaults = (Map<String, Object>) yamlCfg.remove("defaults");
         if (yamlDefaults != null) {
            cfgYamlDefaults = cfgInstanceFactory.get();
            final var unusedParams = cfgYamlDefaults.applyFrom(yamlDefaults, true);
            if (!unusedParams.isEmpty()) {
               yamlCfg.put("defaults", unusedParams);
            }
         }

         // process sync tasks
         final var yamlSyncTasks = (List<Map<String, Object>>) yamlCfg.remove("sync");
         if (yamlSyncTasks != null && !yamlSyncTasks.isEmpty()) {
            cfgYamlSyncTasks = new ArrayList<>();

            for (final var yamlSyncTask : yamlSyncTasks) {
               final var taskCfg = cfgInstanceFactory.get();
               final var unusedParams = taskCfg.applyFrom(yamlSyncTask, true);

               if (!unusedParams.isEmpty()) {
                  ((List<Map<String, Object>>) yamlCfg.computeIfAbsent("sync", k -> new ArrayList<>())).add(unusedParams);
               }
               cfgYamlSyncTasks.add(taskCfg);
            }
         }

         if (!yamlCfg.isEmpty()) {
            LOG.warn("The following settings found in the config file are unknown:\n" + YamlUtils.toYamlString(yamlCfg));
         }
      }
   }

   @Option(names = "--copy-acl", description = "Copy file permissions (ACL) for newly copied files.")
   private void setCopyACL(final boolean copyACL) {
      cfgCLI.copyACL = copyACL;
   }

   @Option(names = "--delete-excluded", description = "Delete excluded files/directories from target.")
   private void setDeleteExcluded(final boolean deleteExcluded) {
      cfgCLI.deleteExcluded = deleteExcluded;
   }

   @Option(names = "--exclude", split = ",", paramLabel = "<pattern>", description = "Glob pattern for files/directories to be excluded from sync.")
   private void setExcludes(final String[] excludes) {
      cfgCLI.excludes = List.of(excludes);
   }

   @Option(names = "--exclude-hidden-files", description = "Don't synchronize hidden files.")
   private void setExcludeHiddenFiles(final boolean excludeHiddenFiles) {
      cfgCLI.excludeHiddenFiles = excludeHiddenFiles;
   }

   @Option(names = "--exclude-hidden-system-files", description = "Don't synchronize hidden system files.")
   private void setExcludeHiddenSystemFiles(final boolean excludeHiddenSystemFiles) {
      cfgCLI.excludeHiddenSystemFiles = excludeHiddenSystemFiles;
   }

   @Option(names = "--exclude-system-files", description = "Don't synchronize system files.")
   private void setExcludeSystemFiles(final boolean excludeSystemFiles) {
      cfgCLI.excludeSystemFiles = excludeSystemFiles;
   }

   @Parameters(index = "0", arity = "0..1", paramLabel = "SOURCE", description = "Directory to copy from files.")
   private void setSourceRoot(final String source) {
      try {
         cfgCLI.source = Path.of(source);
      } catch (final InvalidPathException ex) {
         throw new ParameterException(commandSpec.commandLine(), "Source path: " + ex.getMessage());
      }
   }

   @Parameters(index = "1", arity = "0..1", paramLabel = "TARGET", description = "Directory to copy files to.")
   private void setTargetRoot(final String target) {
      try {
         cfgCLI.target = Path.of(target);
      } catch (final InvalidPathException ex) {
         throw new ParameterException(commandSpec.commandLine(), "Target path: " + ex.getMessage());
      }
   }
}
