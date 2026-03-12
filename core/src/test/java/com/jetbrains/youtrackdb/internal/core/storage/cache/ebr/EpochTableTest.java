/*
 * Copyright 2010-2025 OrientDB LTD (http://orientdb.com)
 * Copyright 2010-2025 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.storage.cache.ebr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Unit tests for {@link EpochTable} — Group A basic (A1–A12).
 *
 * <p>Tests cover the core enter/exit lifecycle, computeSafeEpoch variants,
 * advanceEpoch monotonicity, defensive guards, reentrancy/unmatched-exit
 * checks, slot allocation, slot reuse, dynamic growth, and the grow-safety
 * retry loop.
 */
public class EpochTableTest {

  // Default timeout for the EpochTable in tests (5 seconds).
  private static final long TEST_TIMEOUT_MS = 5_000L;

  // VarHandle for reflective access to globalEpoch in A6.
  private static final VarHandle GLOBAL_EPOCH_HANDLE;

  static {
    try {
      GLOBAL_EPOCH_HANDLE = MethodHandles.privateLookupIn(
          EpochTable.class, MethodHandles.lookup())
          .findVarHandle(EpochTable.class, "globalEpoch", long.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * A1. Basic enter/exit lifecycle.
   *
   * <p>Create EpochTable, enter a critical section, verify the slot holds
   * the current global epoch (computeSafeEpoch returns epoch - 1). Exit,
   * verify computeSafeEpoch returns currentEpoch - 1 (no active slots).
   */
  @Test
  public void testBasicEnterExitLifecycle() {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);

    long epochBeforeEnter = table.currentEpoch();
    table.enter();

    // With one active slot at epochBeforeEnter, safeEpoch = epochBeforeEnter - 1.
    assertThat(table.computeSafeEpoch()).isEqualTo(epochBeforeEnter - 1);

    table.exit();

    // After exit, all slots inactive, safeEpoch = currentEpoch - 1.
    assertThat(table.computeSafeEpoch()).isEqualTo(table.currentEpoch() - 1);
  }

  /**
   * A2. computeSafeEpoch — all slots inactive.
   *
   * <p>Advance epoch to E. With no threads in critical sections,
   * computeSafeEpoch must return E - 1.
   */
  @Test
  public void testComputeSafeEpochAllInactive() {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);

    // Advance epoch several times.
    long e = 0;
    for (int i = 0; i < 5; i++) {
      e = table.advanceEpoch();
    }

    assertThat(table.computeSafeEpoch()).isEqualTo(e - 1);
  }

  /**
   * A3. computeSafeEpoch — single active slot.
   *
   * <p>Thread enters at epoch E. Advance epoch to E+3. computeSafeEpoch
   * must return E - 1 (the active slot holds back reclamation). Thread
   * exits. computeSafeEpoch must return (E+3) - 1 = E+2.
   */
  @Test
  public void testComputeSafeEpochSingleActiveSlot() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);

    // Record epoch E before thread enters.
    long e = table.currentEpoch();

    // Thread enters at epoch E.
    AtomicReference<Throwable> error = new AtomicReference<>();
    CyclicBarrier enteredBarrier = new CyclicBarrier(2);
    CyclicBarrier exitBarrier = new CyclicBarrier(2);

    Thread t = new Thread(() -> {
      try {
        table.enter();
        enteredBarrier.await();
        exitBarrier.await();
        table.exit();
      } catch (Throwable ex) {
        error.set(ex);
      }
    });
    t.start();
    enteredBarrier.await();

    // Advance epoch 3 times: E+1, E+2, E+3.
    table.advanceEpoch();
    table.advanceEpoch();
    long ePlus3 = table.advanceEpoch();
    assertThat(ePlus3).isEqualTo(e + 3);

    // Active slot at E holds back reclamation.
    assertThat(table.computeSafeEpoch()).isEqualTo(e - 1);

    // Let the thread exit.
    exitBarrier.await();
    t.join();
    assertThat(error.get()).isNull();

    // All slots inactive now — safeEpoch = (E+3) - 1 = E+2.
    assertThat(table.computeSafeEpoch()).isEqualTo(ePlus3 - 1);
  }

  /**
   * A4. computeSafeEpoch — multiple active slots at different epochs.
   *
   * <p>Three threads enter at epochs E, E+1, E+2 (advancing between each).
   * computeSafeEpoch must return E - 1 (minimum active epoch). Thread at E
   * exits. computeSafeEpoch must return E. Remaining threads exit.
   * computeSafeEpoch must jump to currentEpoch - 1.
   */
  @Test
  public void testComputeSafeEpochMultipleActiveSlots() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);

    long e = table.currentEpoch();
    AtomicReference<Throwable> error = new AtomicReference<>();

    // Barriers: each thread signals when entered, waits for exit signal.
    CyclicBarrier entered1 = new CyclicBarrier(2);
    CyclicBarrier exit1 = new CyclicBarrier(2);
    CyclicBarrier entered2 = new CyclicBarrier(2);
    CyclicBarrier exit2 = new CyclicBarrier(2);
    CyclicBarrier entered3 = new CyclicBarrier(2);
    CyclicBarrier exit3 = new CyclicBarrier(2);

    // Thread 1 enters at epoch E.
    Thread t1 = new Thread(() -> {
      try {
        table.enter();
        entered1.await();
        exit1.await();
        table.exit();
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    t1.start();
    entered1.await();

    // Advance to E+1, thread 2 enters.
    table.advanceEpoch();
    Thread t2 = new Thread(() -> {
      try {
        table.enter();
        entered2.await();
        exit2.await();
        table.exit();
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    t2.start();
    entered2.await();

    // Advance to E+2, thread 3 enters.
    table.advanceEpoch();
    Thread t3 = new Thread(() -> {
      try {
        table.enter();
        entered3.await();
        exit3.await();
        table.exit();
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    t3.start();
    entered3.await();

    // Minimum active epoch is E, so safeEpoch = E - 1.
    assertThat(table.computeSafeEpoch()).isEqualTo(e - 1);

    // Thread at E exits.
    exit1.await();
    t1.join();
    assertThat(error.get()).isNull();

    // Now minimum is E+1, safeEpoch = E+1 - 1 = E.
    assertThat(table.computeSafeEpoch()).isEqualTo(e);

    // Remaining threads exit.
    exit2.await();
    exit3.await();
    t2.join();
    t3.join();
    assertThat(error.get()).isNull();

    // All inactive, safeEpoch = currentEpoch - 1.
    assertThat(table.computeSafeEpoch()).isEqualTo(table.currentEpoch() - 1);
  }

  /**
   * A5. advanceEpoch — monotonic increment.
   *
   * <p>Call advanceEpoch 100 times. Verify currentEpoch returns 100
   * (starting from 0). Verify computeSafeEpoch returns 99 with no active
   * slots.
   */
  @Test
  public void testAdvanceEpochMonotonicIncrement() {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);
    assertThat(table.currentEpoch()).isEqualTo(0L);

    for (int i = 0; i < 100; i++) {
      long returned = table.advanceEpoch();
      assertThat(returned).isEqualTo(i + 1);
    }

    assertThat(table.currentEpoch()).isEqualTo(100L);
    assertThat(table.computeSafeEpoch()).isEqualTo(99L);
  }

  /**
   * A6. computeSafeEpoch — defensive guard against INACTIVE globalEpoch.
   *
   * <p>Use reflection to set globalEpoch to Long.MAX_VALUE (INACTIVE).
   * Verify computeSafeEpoch returns -1 (refuse to reclaim).
   */
  @Test
  public void testComputeSafeEpochDefensiveInactiveGuard() {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);

    // Set globalEpoch to INACTIVE (Long.MAX_VALUE) via VarHandle.
    GLOBAL_EPOCH_HANDLE.setVolatile(table, EpochTable.INACTIVE);

    assertThat(table.computeSafeEpoch()).isEqualTo(-1L);
  }

  /**
   * A7. Reentrancy — nested enter() throws.
   *
   * <p>Call enter(), then call enter() again on the same thread. Verify
   * IllegalStateException is thrown with message containing "not reentrant".
   * Verify that after the exception, the original critical section is still
   * active (slot still holds the epoch). Call exit() to clean up — must
   * succeed.
   */
  @Test
  public void testReentrancyThrows() {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);

    table.enter();
    long epochBeforeReenter = table.currentEpoch();

    assertThatThrownBy(table::enter)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not reentrant");

    // Original critical section is still active.
    assertThat(table.computeSafeEpoch()).isEqualTo(epochBeforeReenter - 1);

    // Clean up — exit must succeed.
    table.exit();
    assertThat(table.computeSafeEpoch()).isEqualTo(table.currentEpoch() - 1);
  }

  /**
   * A8. Unmatched exit() throws.
   *
   * <p>Call exit() without a preceding enter(). Verify
   * IllegalStateException is thrown with message containing "without
   * matching enter".
   */
  @Test
  public void testUnmatchedExitThrows() {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);

    assertThatThrownBy(table::exit)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("without matching enter");
  }

  /**
   * A9. Slot allocation — first enter allocates slot.
   *
   * <p>Create EpochTable on a fresh thread. Call enter(). Verify that
   * the thread got a valid slot index (>= 0 and < slot count).
   * Call exit().
   */
  @Test
  public void testSlotAllocationOnFirstEnter() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);
    int initialSlotCount = table.getSlotCount();
    AtomicInteger slotIndex = new AtomicInteger(-1);
    AtomicReference<Throwable> error = new AtomicReference<>();

    Thread t = new Thread(() -> {
      try {
        table.enter();
        slotIndex.set(table.getSlotIndex());
        table.exit();
      } catch (Throwable ex) {
        error.set(ex);
      }
    });
    t.start();
    t.join();

    assertThat(error.get()).isNull();
    assertThat(slotIndex.get()).isBetween(0, initialSlotCount - 1);
  }

  /**
   * A10. Slot reuse via free list.
   *
   * <p>Allocate a slot on thread A (enter + exit). Call releaseSlot
   * directly to simulate thread death. Start thread B. Thread B's
   * enter() should reuse thread A's slot index. Verify no array
   * growth occurred.
   */
  @Test
  public void testSlotReuseViaFreeList() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);
    int initialSlotCount = table.getSlotCount();
    AtomicInteger slotA = new AtomicInteger(-1);
    AtomicInteger slotB = new AtomicInteger(-1);

    // Thread A allocates a slot, enters, exits.
    Thread threadA = new Thread(() -> {
      table.enter();
      slotA.set(table.getSlotIndex());
      table.exit();
    });
    threadA.start();
    threadA.join();

    // Simulate thread A death: release its slot back to the free list.
    int slotAIndex = slotA.get();
    assertThat(slotAIndex).isGreaterThanOrEqualTo(0);
    table.releaseSlot(slotAIndex);
    assertThat(table.getFreeListSize()).isGreaterThanOrEqualTo(1);

    // Thread B should reuse thread A's slot index.
    Thread threadB = new Thread(() -> {
      table.enter();
      slotB.set(table.getSlotIndex());
      table.exit();
    });
    threadB.start();
    threadB.join();

    assertThat(slotB.get()).isEqualTo(slotAIndex);
    // No growth occurred.
    assertThat(table.getSlotCount()).isEqualTo(initialSlotCount);
  }

  /**
   * A11. Dynamic array growth.
   *
   * <p>Create EpochTable, determine initial capacity N. Allocate N+1 slots
   * (each on a separate thread). Verify the array grew (new capacity >=
   * N+1). Verify all existing slot values were preserved during growth.
   * Verify new slots default to INACTIVE.
   */
  @Test
  public void testDynamicArrayGrowth() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);
    int initialSlotCount = table.getSlotCount();

    // We need N+1 threads to force growth. Each thread enters and holds.
    List<Thread> threads = new ArrayList<>();
    CyclicBarrier allEntered = new CyclicBarrier(initialSlotCount + 2);
    CyclicBarrier exitSignal = new CyclicBarrier(initialSlotCount + 2);
    AtomicReference<Throwable> error = new AtomicReference<>();
    AtomicInteger allocatedSlots = new AtomicInteger(0);

    for (int i = 0; i < initialSlotCount + 1; i++) {
      Thread t = new Thread(() -> {
        try {
          table.enter();
          allocatedSlots.incrementAndGet();
          allEntered.await();
          exitSignal.await();
          table.exit();
        } catch (Throwable ex) {
          error.compareAndSet(null, ex);
        }
      });
      threads.add(t);
      t.start();
    }

    // Wait for all threads to enter.
    allEntered.await();
    assertThat(error.get()).isNull();

    // Verify growth occurred.
    assertThat(table.getSlotCount()).isGreaterThanOrEqualTo(initialSlotCount + 1);

    // All N+1 threads are active — computeSafeEpoch should return epoch 0 - 1 = -1
    // (all entered at epoch 0).
    assertThat(table.computeSafeEpoch()).isEqualTo(-1L);

    // New slots beyond those allocated should be INACTIVE.
    int newSlotCount = table.getSlotCount();
    for (int i = initialSlotCount + 1; i < newSlotCount; i++) {
      assertThat(table.getSlotValue(i)).isEqualTo(EpochTable.INACTIVE);
    }

    // Release all threads.
    exitSignal.await();
    for (Thread t : threads) {
      t.join();
    }

    // After all exit, safeEpoch should be currentEpoch - 1.
    assertThat(table.computeSafeEpoch()).isEqualTo(table.currentEpoch() - 1);
  }

  /**
   * A12. enter() grow-safety retry loop.
   *
   * <p>Force grow() to run between the opaque write and the post-fence
   * volatile read in enter(). Verify that enter() retries and the epoch
   * value is correctly written to the new array. Verify computeSafeEpoch
   * sees the active slot.
   *
   * <p>White-box approach: allocate enough threads to fill the initial
   * capacity, then have one more thread enter — this triggers grow().
   * The grow-safety retry in enter() detects the slots array change and
   * re-writes the epoch to the new array. We verify by checking
   * computeSafeEpoch after the growth.
   */
  @Test
  public void testEnterGrowSafetyRetryLoop() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);
    int initialSlotCount = table.getSlotCount();

    // Fill up all initial slots — each thread enters and holds.
    List<Thread> holdingThreads = new ArrayList<>();
    CyclicBarrier allHolding = new CyclicBarrier(initialSlotCount + 1);
    CyclicBarrier releaseAll = new CyclicBarrier(initialSlotCount + 1);
    AtomicReference<Throwable> error = new AtomicReference<>();

    for (int i = 0; i < initialSlotCount; i++) {
      Thread t = new Thread(() -> {
        try {
          table.enter();
          allHolding.await();
          releaseAll.await();
          table.exit();
        } catch (Throwable ex) {
          error.compareAndSet(null, ex);
        }
      });
      holdingThreads.add(t);
      t.start();
    }
    allHolding.await();
    assertThat(error.get()).isNull();

    // Advance epoch so the new thread enters at a distinct epoch.
    long epochAfterAdvance = table.advanceEpoch();

    // This thread will trigger growth because all initial slots are taken.
    // It enters and holds its critical section via barriers so we can verify
    // state while it's alive.
    AtomicInteger growthThreadSlot = new AtomicInteger(-1);
    CyclicBarrier growthEntered = new CyclicBarrier(2);
    CyclicBarrier growthExit = new CyclicBarrier(2);
    Thread growthThread = new Thread(() -> {
      try {
        table.enter();
        growthThreadSlot.set(table.getSlotIndex());
        growthEntered.await();
        growthExit.await();
        table.exit();
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    growthThread.start();
    growthEntered.await();
    assertThat(error.get()).isNull();

    // The array must have grown.
    assertThat(table.getSlotCount()).isGreaterThan(initialSlotCount);

    // The growth thread's slot should hold the advanced epoch.
    int slot = growthThreadSlot.get();
    assertThat(slot).isGreaterThanOrEqualTo(0);
    assertThat(table.getSlotValue(slot)).isEqualTo(epochAfterAdvance);

    // computeSafeEpoch must see all active slots, including the one after growth.
    // The holding threads entered at epoch 0, so min = 0, safeEpoch = 0 - 1 = -1.
    assertThat(table.computeSafeEpoch()).isEqualTo(-1L);

    // Release the growth thread.
    growthExit.await();
    growthThread.join();

    // Release all holding threads.
    releaseAll.await();
    for (Thread t : holdingThreads) {
      t.join();
    }
    assertThat(error.get()).isNull();

    // All threads exited — safeEpoch = currentEpoch - 1.
    assertThat(table.computeSafeEpoch()).isEqualTo(table.currentEpoch() - 1);
  }
}
