package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Tests monotonic generation, recovery-floor installation, concurrency, and exhaustion. */
public class AtomicOperationIdGenTest {

  /** A fresh generator issues one first and exposes the issued value without advancing it. */
  @Test
  public void testNextIdStartsAtOneAndLastIdIsPureRead() {
    final var gen = new AtomicOperationIdGen();
    assertThat(gen.getLastId()).isZero();
    assertThat(gen.nextId()).isEqualTo(1);
    assertThat(gen.getLastId()).isEqualTo(1);
    assertThat(gen.getLastId()).isEqualTo(1);
  }

  /** Installing a highest-issued floor makes the next identifier strictly greater. */
  @Test
  public void testAdvanceInstallsHighestIssuedFloor() {
    final var gen = new AtomicOperationIdGen();
    gen.advanceToAtLeast(42);

    assertThat(gen.getLastId()).isEqualTo(42);
    assertThat(gen.nextId()).isEqualTo(43);
  }

  /** Reinstalling the current floor is idempotent. */
  @Test
  public void testAdvanceToCurrentFloorIsIdempotent() {
    final var gen = new AtomicOperationIdGen();
    gen.advanceToAtLeast(7);
    gen.advanceToAtLeast(7);

    assertThat(gen.getLastId()).isEqualTo(7);
  }

  /** A lower or negative recovery floor is rejected and cannot alter the current value. */
  @Test
  public void testAdvanceRejectsRewindAndNegativeFloor() {
    final var gen = new AtomicOperationIdGen();
    gen.advanceToAtLeast(10);

    assertThatThrownBy(() -> gen.advanceToAtLeast(9))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rewind");
    assertThatThrownBy(() -> gen.advanceToAtLeast(-1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(gen.getLastId()).isEqualTo(10);
  }

  /** Exhausting the signed range fails closed instead of wrapping into negative identifiers. */
  @Test
  public void testNextIdRejectsOverflow() {
    final var gen = new AtomicOperationIdGen();
    gen.advanceToAtLeast(Long.MAX_VALUE);

    assertThatThrownBy(gen::nextId)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exhausted");
    assertThat(gen.getLastId()).isEqualTo(Long.MAX_VALUE);
  }

  /** Concurrent callers receive one unique, gap-free range of logical identifiers. */
  @Test
  public void testNextIdIsUniqueUnderConcurrency() throws Exception {
    final int threads = 8;
    final int iterations = 5_000;
    final var gen = new AtomicOperationIdGen();
    final var barrier = new CyclicBarrier(threads);
    final var done = new CountDownLatch(threads);
    final var error = new AtomicReference<Throwable>();
    final var allIds = new ArrayList<long[]>(threads);
    for (int i = 0; i < threads; i++) {
      allIds.add(new long[iterations]);
    }

    for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
      final int index = threadIndex;
      final var thread =
          new Thread(
              () -> {
                try {
                  final var local = allIds.get(index);
                  barrier.await();
                  for (int i = 0; i < iterations; i++) {
                    local[i] = gen.nextId();
                  }
                } catch (Throwable failure) {
                  error.compareAndSet(null, failure);
                } finally {
                  done.countDown();
                }
              });
      thread.setDaemon(true);
      thread.start();
    }

    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    if (error.get() != null) {
      throw new AssertionError(error.get());
    }

    final var seen = new HashSet<Long>(threads * iterations);
    for (var identifiers : allIds) {
      for (long identifier : identifiers) {
        seen.add(identifier);
      }
    }
    assertThat(seen).hasSize(threads * iterations);
    assertThat(gen.getLastId()).isEqualTo(threads * iterations);
  }
}
