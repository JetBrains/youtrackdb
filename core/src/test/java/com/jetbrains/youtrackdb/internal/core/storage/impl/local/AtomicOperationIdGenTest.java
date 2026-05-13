package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Unit tests for {@link AtomicOperationIdGen}. Pins the three observable behaviours of the
 * monotonic id generator: {@link AtomicOperationIdGen#nextId()} returns strictly increasing
 * values, {@link AtomicOperationIdGen#setStartId(long)} resets the underlying counter so the
 * next {@code nextId()} returns {@code id + 1}, and {@link AtomicOperationIdGen#getLastId()}
 * exposes the last generated id without advancing it. Also exercises monotonic uniqueness
 * under concurrent load, since the class is the source of WAL operation ids and a missed
 * increment would corrupt the WAL ordering.
 */
public class AtomicOperationIdGenTest {

  /**
   * A fresh generator starts at 0; the first {@code nextId()} returns 1 and advances the
   * internal counter. This pins the post-incrementAndGet contract.
   */
  @Test
  public void testNextIdStartsAtOne() {
    var gen = new AtomicOperationIdGen();
    assertThat(gen.getLastId()).isZero();
    assertThat(gen.nextId()).isEqualTo(1L);
    assertThat(gen.getLastId()).isEqualTo(1L);
  }

  /**
   * Repeated {@code nextId()} calls return strictly increasing values (1, 2, 3, ...).
   */
  @Test
  public void testNextIdIsStrictlyIncreasing() {
    var gen = new AtomicOperationIdGen();
    for (long expected = 1; expected <= 100; expected++) {
      assertThat(gen.nextId()).isEqualTo(expected);
    }
    assertThat(gen.getLastId()).isEqualTo(100L);
  }

  /**
   * After {@code setStartId(N)}, the very next {@code nextId()} returns {@code N + 1} (because
   * {@code nextId()} is incrementAndGet). Verifies the counter is reset, not adjusted.
   */
  @Test
  public void testSetStartIdResetsCounter() {
    var gen = new AtomicOperationIdGen();
    gen.nextId();
    gen.nextId();
    gen.nextId();
    assertThat(gen.getLastId()).isEqualTo(3L);

    gen.setStartId(42L);
    assertThat(gen.getLastId()).isEqualTo(42L);
    assertThat(gen.nextId()).isEqualTo(43L);
    assertThat(gen.getLastId()).isEqualTo(43L);
  }

  /**
   * {@code setStartId} accepts the same value as the current last id (idempotent reset).
   */
  @Test
  public void testSetStartIdToCurrent() {
    var gen = new AtomicOperationIdGen();
    gen.nextId();
    gen.nextId();
    long current = gen.getLastId();

    gen.setStartId(current);
    assertThat(gen.getLastId()).isEqualTo(current);
    assertThat(gen.nextId()).isEqualTo(current + 1);
  }

  /**
   * {@code setStartId} accepts arbitrary values, including a value lower than the current id
   * (e.g., during WAL restore the id is re-seeded to the last persisted operation id).
   */
  @Test
  public void testSetStartIdCanRewindForWalRestore() {
    var gen = new AtomicOperationIdGen();
    for (int i = 0; i < 1000; i++) {
      gen.nextId();
    }
    assertThat(gen.getLastId()).isEqualTo(1000L);

    // Simulate WAL restore: seed the gen to a smaller value.
    gen.setStartId(7L);
    assertThat(gen.getLastId()).isEqualTo(7L);
    assertThat(gen.nextId()).isEqualTo(8L);
  }

  /**
   * {@code getLastId} must be a pure read — it must not advance the counter.
   */
  @Test
  public void testGetLastIdDoesNotAdvance() {
    var gen = new AtomicOperationIdGen();
    gen.nextId();
    long before = gen.getLastId();
    long secondRead = gen.getLastId();
    assertThat(secondRead).isEqualTo(before);
    assertThat(gen.nextId()).isEqualTo(before + 1);
  }

  /**
   * Concurrent {@code nextId()} calls from multiple threads must produce strictly unique ids
   * because the WAL ordering depends on it. Total expected ids: {@code threads * iterations}.
   */
  @Test
  public void testNextIdIsUniqueUnderConcurrency() throws Exception {
    int threads = 8;
    int iterations = 5_000;
    var gen = new AtomicOperationIdGen();
    var barrier = new CyclicBarrier(threads);
    var done = new CountDownLatch(threads);
    var error = new AtomicReference<Throwable>();
    var allIds = new ArrayList<long[]>(threads);
    for (int i = 0; i < threads; i++) {
      allIds.add(new long[iterations]);
    }

    var workers = new ArrayList<Thread>(threads);
    for (int t = 0; t < threads; t++) {
      int idx = t;
      var thread = new Thread(() -> {
        try {
          var local = allIds.get(idx);
          barrier.await();
          for (int i = 0; i < iterations; i++) {
            local[i] = gen.nextId();
          }
        } catch (Throwable th) {
          error.compareAndSet(null, th);
        } finally {
          done.countDown();
        }
      });
      thread.setDaemon(true);
      workers.add(thread);
    }
    workers.forEach(Thread::start);
    assertThat(done.await(30, TimeUnit.SECONDS))
        .as("all generator threads finish within 30s")
        .isTrue();
    if (error.get() != null) {
      throw new AssertionError(error.get());
    }

    // Collect into a set; total unique ids must equal threads * iterations.
    var seen = new HashSet<Long>(threads * iterations);
    for (var arr : allIds) {
      for (long id : arr) {
        seen.add(id);
      }
    }
    assertThat(seen).hasSize(threads * iterations);
    // Final getLastId must match the highest id observed.
    assertThat(gen.getLastId()).isEqualTo(threads * iterations);
  }
}
