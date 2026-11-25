package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class FileLifecycleStepTest {

  @Rule
  public final Timeout timeout = Timeout.seconds(30);

  @Test
  public void completedFutureDefersPreexistingInterruptUntilClose() throws Exception {
    final var completed = CompletableFuture.completedFuture(42);
    try {
      final var step = new FileLifecycleStep("storage", "File shrink");
      Thread.currentThread().interrupt();

      assertEquals(Integer.valueOf(42), step.await(completed));
      assertFalse(Thread.currentThread().isInterrupted());
      assertThrows(ThreadInterruptedException.class, step::close);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void firstInterruptionRemainsTheCauseAfterFurtherInterruptsAtCompletionAndCleanup()
      throws Exception {
    final var allowCompletion = new CountDownLatch(1);
    final var original = new AtomicReference<InterruptedException>();
    final var future = new FutureTask<>(() -> {
      assertTrue(allowCompletion.await(10, TimeUnit.SECONDS));
      return 42;
    }) {
      @Override
      public Integer get() throws InterruptedException, ExecutionException {
        try {
          final var result = super.get(10, TimeUnit.SECONDS);
          // Deliver another interrupt after completion but before the caller leaves await.
          Thread.currentThread().interrupt();
          return result;
        } catch (InterruptedException interrupted) {
          original.set(interrupted);
          allowCompletion.countDown();
          throw interrupted;
        } catch (TimeoutException timedOut) {
          throw new AssertionError(timedOut);
        }
      }
    };

    final var worker = Thread.ofVirtual().start(future);
    try {
      final var step = new FileLifecycleStep("storage", "File shrink");
      Thread.currentThread().interrupt();

      assertEquals(Integer.valueOf(42), step.await(future));
      assertFalse(Thread.currentThread().isInterrupted());
      // A cleanup-time interrupt must not replace the interruption already observed by await.
      Thread.currentThread().interrupt();
      final var failure = assertThrows(ThreadInterruptedException.class, step::close);
      assertSame(original.get(), failure.getCause());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
      allowCompletion.countDown();
      assertTrue(worker.join(Duration.ofSeconds(10)));
    }
  }
}
