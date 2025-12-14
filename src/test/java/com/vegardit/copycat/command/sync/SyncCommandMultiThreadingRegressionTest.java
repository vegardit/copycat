/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for a multi-threading bug where workers exit as soon as the shared queue is momentarily empty,
 * causing only one thread to do all work even if multiple threads are configured.
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class SyncCommandMultiThreadingRegressionTest {

   private static final class CoordinatedJobQueue<E> extends AbstractQueue<E> {
      private final ArrayDeque<E> jobs = new ArrayDeque<>();
      private final Set<String> workerThreads = new HashSet<>();

      private @Nullable String rootWorkerThread;
      private boolean emptyObservedByNonRoot;
      private boolean nonRootProcessedJob;

      /**
       * This queue is intentionally "biased" to make a specific race deterministic:
       * when only one initial job exists, one worker takes it and the queue becomes temporarily empty.
       * Other workers will observe that empty queue before follow-up jobs are enqueued.
       *
       * In the broken implementation, those workers treat "queue empty right now" as "done" and exit,
       * so only one thread processes all subsequently discovered directories.
       */
      @Override
      public synchronized boolean offer(final E e) {
         // When the root worker starts enqueuing follow-up work, delay adds until another worker has observed
         // the temporarily empty queue at least once. This makes the regression deterministic.
         if (rootWorkerThread != null && !emptyObservedByNonRoot) {
            final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!emptyObservedByNonRoot) {
               final long remainingNanos = deadlineNanos - System.nanoTime();
               if (remainingNanos <= 0) {
                  break;
               }
               try {
                  // Avoid wait(0) which would wait forever.
                  wait(Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 50)));
               } catch (final InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  break;
               }
            }
         }

         jobs.addLast(e);
         notifyAll();
         return true;
      }

      @Override
      public synchronized E poll() {
         final String threadName = Thread.currentThread().getName();

         if (jobs.isEmpty()) {
            if (rootWorkerThread != null && !threadName.equals(rootWorkerThread)) {
               emptyObservedByNonRoot = true;
               notifyAll();
            }
            return null;
         }

         if (rootWorkerThread == null) {
            rootWorkerThread = threadName;
         }

         // Give non-root workers a chance to pick up at least one enqueued job (when available).
         if (!nonRootProcessedJob && threadName.equals(rootWorkerThread)) {
            final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
            while (!nonRootProcessedJob && !jobs.isEmpty()) {
               final long remainingNanos = deadlineNanos - System.nanoTime();
               if (remainingNanos <= 0) {
                  break;
               }
               try {
                  // Avoid wait(0) which would wait forever.
                  wait(Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 25)));
               } catch (final InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  break;
               }
            }
            if (jobs.isEmpty())
               return null;
         }

         final @Nullable E job = jobs.pollFirst();
         if (job != null) {
            workerThreads.add(threadName);
            if (!threadName.equals(rootWorkerThread)) {
               nonRootProcessedJob = true;
               notifyAll();
            }
         }
         return job;
      }

      @Override
      public synchronized E peek() {
         return jobs.peekFirst();
      }

      @Override
      public synchronized Iterator<E> iterator() {
         return new ArrayDeque<>(jobs).iterator();
      }

      @Override
      public synchronized int size() {
         return jobs.size();
      }

      synchronized Set<String> workerThreads() {
         return Set.copyOf(workerThreads);
      }
   }

   @Test
   void testSyncWorkerDoesNotExitOnTemporaryEmptyQueue(@TempDir final Path tempDir) throws Exception {
      final Path sourceRoot = Files.createDirectory(tempDir.resolve("src"));
      final Path targetRoot = Files.createDirectory(tempDir.resolve("dst"));

      Files.createDirectories(sourceRoot.resolve("a"));
      Files.writeString(sourceRoot.resolve("a/file-a.txt"), "a");
      Files.createDirectories(sourceRoot.resolve("b"));
      Files.writeString(sourceRoot.resolve("b/file-b.txt"), "b");
      Files.createDirectories(sourceRoot.resolve("c"));
      Files.writeString(sourceRoot.resolve("c/file-c.txt"), "c");

      final var task = new SyncCommandConfig();
      task.source = sourceRoot;
      task.target = targetRoot;
      task.dryRun = true;
      task.threads = 2;
      task.applyDefaults();
      task.compute();

      final var cmd = new SyncCommand();
      final var sourceFilterCtx = task.toSourceFilterContext();
      final var targetFilterCtx = task.toTargetFilterContext();

      final Class<?> dirJobQueueClass = Class.forName("com.vegardit.copycat.command.sync.SyncCommand$DirJobQueue");
      final Method syncWorker = SyncCommand.class.getDeclaredMethod("syncWorker", SyncCommandConfig.class, FilterEngine.FilterContext.class,
         FilterEngine.FilterContext.class, dirJobQueueClass);
      syncWorker.setAccessible(true);

      final Class<?> dirJobClass = Class.forName("com.vegardit.copycat.command.sync.SyncCommand$DirJob");
      final Constructor<?> dirJobCtor = dirJobClass.getDeclaredConstructor(Path.class, Path.class);
      dirJobCtor.setAccessible(true);
      final Object rootJob = dirJobCtor.newInstance(task.sourceRootAbsolute, Paths.get("."));

      final var rawJobs = new CoordinatedJobQueue<>();
      rawJobs.offer(rootJob);

      final Constructor<?> dirJobQueueCtor = dirJobQueueClass.getDeclaredConstructor(int.class, Queue.class);
      dirJobQueueCtor.setAccessible(true);
      final Object dirJobs = dirJobQueueCtor.newInstance(2, rawJobs);

      final CountDownLatch start = new CountDownLatch(1);
      final ThreadFactory threadFactory = new ThreadFactory() {
         private int n;

         @Override
         public Thread newThread(final Runnable r) {
            final Thread t = new Thread(r);
            t.setName("sync-test-" + (++n));
            t.setDaemon(true);
            return t;
         }
      };

      final ExecutorService exec = Executors.newFixedThreadPool(2, threadFactory);
      try {
         final Future<?> f1 = exec.submit(() -> {
            await(start);
            invoke(syncWorker, cmd, task, sourceFilterCtx, targetFilterCtx, dirJobs);
         });
         final Future<?> f2 = exec.submit(() -> {
            await(start);
            invoke(syncWorker, cmd, task, sourceFilterCtx, targetFilterCtx, dirJobs);
         });

         start.countDown();

         // This should not time out; the regression should show up via the assertion below.
         f1.get(10, TimeUnit.SECONDS);
         f2.get(10, TimeUnit.SECONDS);
      } finally {
         exec.shutdownNow();
      }

      assertThat(rawJobs.workerThreads()).as("expected at least 2 workers to pick up directory jobs when threads=2")
         .hasSizeGreaterThanOrEqualTo(2);
   }

   private static void await(final CountDownLatch latch) {
      try {
         latch.await();
      } catch (final InterruptedException ex) {
         Thread.currentThread().interrupt();
      }
   }

   private static void invoke(final Method syncWorker, final SyncCommand cmd, final SyncCommandConfig task,
         final FilterEngine.FilterContext sourceFilterCtx, final FilterEngine.FilterContext targetFilterCtx, final Object dirJobs) {
      try {
         syncWorker.invoke(cmd, task, sourceFilterCtx, targetFilterCtx, dirJobs);
      } catch (final ReflectiveOperationException ex) {
         throw new RuntimeException(ex);
      }
   }
}
