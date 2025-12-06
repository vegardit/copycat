/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import net.sf.jstuff.core.SystemUtils;
import net.sf.jstuff.core.io.Processes;

/**
 * Simple asynchronous PowerShell wrapper for Windows.
 *
 * <p>
 * It prefers {@code pwsh.exe} when available and falls back to {@code powershell.exe}.
 * </p>
 *
 * <p>
 * This class is intentionally minimal and does not try to keep sessions open.
 * Each call spawns a new PowerShell process.
 * </p>
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class WindowsPowerShell {

   public record Result(int exitCode, String stdout, String stderr) {
   }

   private static final @Nullable Path EXECUTABLE = detectExecutable();

   private WindowsPowerShell() {
   }

   public static boolean isAvailable() {
      return EXECUTABLE != null;
   }

   /**
    * Execute the given PowerShell script asynchronously.
    *
    * @param script full PowerShell script (can be multi-line)
    */
   public static CompletableFuture<Result> executeAsync(final String script) {
      Objects.requireNonNull(script, "script");
      final var executable = EXECUTABLE;
      if (executable == null)
         return CompletableFuture.failedFuture(new IllegalStateException("No PowerShell executable found in PATH"));

      final var out = new StringBuilder();
      final var err = new StringBuilder();

      try {
         return Processes.builder(executable) //
            .withArgs("-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "$code=[Console]::In.ReadToEnd();Invoke-Expression $code") //
            .withInput(script) //
            .withRedirectOutput(line -> {
               synchronized (out) {
                  out.append(line).append(System.lineSeparator());
               }
            }) //
            .withRedirectError(line -> {
               synchronized (err) {
                  err.append(line).append(System.lineSeparator());
               }
            }) //
            .start() //
            .onExit().thenApply(pw -> new Result(pw.exitStatus(), out.toString(), err.toString()));
      } catch (final IOException ex) {
         return CompletableFuture.failedFuture(new RuntimeException("Failed to start PowerShell process", ex));
      }
   }

   private static @Nullable Path detectExecutable() {
      if (!SystemUtils.IS_OS_WINDOWS)
         return null;

      final @NonNull String[] candidates = {"pwsh.exe", "powershell.exe"};

      for (final var candidate : candidates) {
         final var path = SystemUtils.findExecutable(candidate, false);
         if (path != null && Files.exists(path))
            return path;
      }
      return null;
   }
}
