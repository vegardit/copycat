/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import net.sf.jstuff.core.SystemUtils;
import net.sf.jstuff.core.io.Processes;
import net.sf.jstuff.core.io.Processes.ProcessWrapper;

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
      return executeAsync(script, null);
   }

   /**
    * Execute the given PowerShell script asynchronously.
    *
    * <p>
    * If {@code timeout} is non-null and positive, the spawned process is killed when the timeout elapses.
    * </p>
    *
    * @param script
    *           full PowerShell script (can be multi-line)
    * @param timeout
    *           optional timeout after which the process is killed
    */
   public static CompletableFuture<Result> executeAsync(final String script, final @Nullable Duration timeout) {
      Objects.requireNonNull(script, "script");
      final var executable = EXECUTABLE;
      if (executable == null)
         return CompletableFuture.failedFuture(new IllegalStateException("No PowerShell executable found in PATH"));

      final var out = new StringBuilder();
      final var err = new StringBuilder();

      try {
         final ProcessWrapper proc = Processes.builder(executable) //
            .withArgs("-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
               "$code=[Console]::In.ReadToEnd();& ([ScriptBlock]::Create($code))") //
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
            .start();

         CompletableFuture<Result> future = proc.onExit().thenApply(pw -> new Result(pw.exitStatus(), out.toString(), err.toString()));
         if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            future = future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((result, ex) -> {
               final Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
               if (cause instanceof TimeoutException) {
                  proc.kill();
               }
            });
         }
         return future;
      } catch (final IOException ex) {
         return CompletableFuture.failedFuture(new RuntimeException("Failed to start PowerShell process", ex));
      }
   }

   public static CompletableFuture<Integer> executeOnConsoleAsync(final String script) {
      return executeOnConsoleAsync(script, null);
   }

   /**
    * Execute the given PowerShell script asynchronously (stdout/stderr inherited by the current process).
    *
    * <p>
    * If {@code timeout} is non-null and positive, the spawned process is killed when the timeout elapses.
    * </p>
    */
   public static CompletableFuture<Integer> executeOnConsoleAsync(final String script, final @Nullable Duration timeout) {
      Objects.requireNonNull(script, "script");
      final var executable = EXECUTABLE;
      if (executable == null)
         return CompletableFuture.failedFuture(new IllegalStateException("No PowerShell executable found in PATH"));

      try {
         final ProcessWrapper proc = Processes.builder(executable).withArgs("-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
            "$code=[Console]::In.ReadToEnd();& ([ScriptBlock]::Create($code))").withInput(script) // feed script
            .withInheritOutput() // stdout -> real console
            .withInheritError() // stderr -> real console
            .start();

         CompletableFuture<Integer> future = proc.onExit().thenApply(Processes.ProcessWrapper::exitStatus);
         if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            future = future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((result, ex) -> {
               final Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
               if (cause instanceof TimeoutException) {
                  proc.kill();
               }
            });
         }
         return future;
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
