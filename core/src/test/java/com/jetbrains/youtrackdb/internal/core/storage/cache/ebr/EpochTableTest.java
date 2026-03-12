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
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Unit tests for {@link EpochTable} — Group A (A1–A22).
 *
 * <p>A1–A12 (basic): core enter/exit lifecycle, computeSafeEpoch variants,
 * advanceEpoch monotonicity, defensive guards, reentrancy/unmatched-exit
 * checks, slot allocation, slot reuse, dynamic growth, and the grow-safety
 * retry loop.
 *
 * <p>A13–A22 (advanced): exit+grow race (conservative), scan-based
 * reclamation, double-free prevention, reclaimSlot re-check, Cleanable
 * cancellation, waitForSafeEpoch (immediate, blocks-until-exit,
 * throws-if-inside, timeout), currentEpoch coherence.
 */
public class EpochTableTest {

  // Default timeout for the EpochTable in tests (5 seconds).
  private static final long TEST_TIMEOUT_MS = 5_000L;

  // VarHandles for reflective access to EpochTable internals.
  private static final VarHandle GLOBAL_EPOCH_HANDLE;
  private static final VarHandle SLOT_OWNERS_HANDLE;
  private static final VarHandle SLOT_RELEASED_HANDLE;

  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
          EpochTable.class, MethodHandles.lookup());
      GLOBAL_EPOCH_HANDLE = lookup
          .findVarHandle(EpochTable.class, "globalEpoch", long.class);
      SLOT_OWNERS_HANDLE = lookup
          .findVarHandle(EpochTable.class, "slotOwners", WeakReference[].class);
      SLOT_RELEASED_HANDLE = lookup
          .findVarHandle(EpochTable.class, "slotReleased",
              AtomicIntegerArray.class);
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

  // -----------------------------------------------------------------------
  // Group A advanced (A13–A22)
  // -----------------------------------------------------------------------

  /**
   * A13. exit() races with grow() — conservative behavior.
   *
   * <p>Thread T enters a critical section. Another thread forces grow().
   * T calls exit(). Even if exit()'s release write lands on the old array
   * (missed the new one), computeSafeEpoch() returns a value <= the safe
   * epoch (conservative — delays reclamation, never causes use-after-free).
   * On T's next enter()/exit() cycle on the new array, computeSafeEpoch()
   * returns the correct value.
   */
  @Test
  public void testExitRacesWithGrowConservative() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);
    int initialSlotCount = table.getSlotCount();

    AtomicReference<Throwable> error = new AtomicReference<>();
    CyclicBarrier enteredBarrier = new CyclicBarrier(2);
    CyclicBarrier growDoneBarrier = new CyclicBarrier(2);
    CyclicBarrier exitDoneBarrier = new CyclicBarrier(2);

    long epochAtEntry = table.currentEpoch();

    // Thread T enters a critical section at epoch 0.
    Thread t = new Thread(() -> {
      try {
        table.enter();
        enteredBarrier.await();
        // Wait for grow to complete before exiting — this maximizes the
        // chance that exit()'s release write targets the old array.
        growDoneBarrier.await();
        table.exit();
        exitDoneBarrier.await();
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    t.start();
    enteredBarrier.await();

    // Fill remaining initial slots to force growth on the next allocation.
    // We already have one slot (T's), so allocate initialSlotCount more
    // to trigger grow().
    List<Thread> fillerThreads = new ArrayList<>();
    CyclicBarrier fillEntered = new CyclicBarrier(initialSlotCount + 1);
    CyclicBarrier fillExit = new CyclicBarrier(initialSlotCount + 1);
    for (int i = 0; i < initialSlotCount; i++) {
      Thread filler = new Thread(() -> {
        try {
          table.enter();
          fillEntered.await();
          fillExit.await();
          table.exit();
        } catch (Throwable ex) {
          error.compareAndSet(null, ex);
        }
      });
      fillerThreads.add(filler);
      filler.start();
    }
    fillEntered.await();
    assertThat(error.get()).isNull();

    // Growth must have occurred.
    assertThat(table.getSlotCount()).isGreaterThan(initialSlotCount);
    growDoneBarrier.await();

    // T calls exit() — may land on old or new array.
    exitDoneBarrier.await();
    t.join();
    assertThat(error.get()).isNull();

    // computeSafeEpoch must be conservative: it should not return a value
    // higher than epochAtEntry - 1 while filler threads are still active.
    // Filler threads entered at epoch 0, so safeEpoch <= 0 - 1 = -1.
    assertThat(table.computeSafeEpoch()).isLessThanOrEqualTo(epochAtEntry - 1);

    // Release fillers.
    fillExit.await();
    for (Thread filler : fillerThreads) {
      filler.join();
    }
    assertThat(error.get()).isNull();

    // Advance epoch so the next enter is at a distinct epoch.
    long newEpoch = table.advanceEpoch();

    // A new thread enters and exits on the post-growth array.
    Thread t2 = new Thread(() -> {
      try {
        table.enter();
        table.exit();
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    t2.start();
    t2.join();
    assertThat(error.get()).isNull();

    // After the fresh cycle, computeSafeEpoch should be correct.
    assertThat(table.computeSafeEpoch()).isEqualTo(newEpoch - 1);
  }

  /**
   * A14. Scan-based slot reclamation — dead thread detection.
   *
   * <p>Allocate a slot on thread A (enter + exit, so slot is INACTIVE).
   * Obtain the WeakReference for slot A. Clear the reference (simulate GC
   * collecting the thread). Call computeSafeEpoch(). Verify: (a) the slot
   * index was returned to the free list, (b) slotOwners[index] is null,
   * (c) slotReleased[index] is 1.
   */
  @Test
  public void testScanBasedSlotReclamation() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);
    AtomicInteger slotIdx = new AtomicInteger(-1);

    // Thread A allocates a slot, enters, exits.
    Thread threadA = new Thread(() -> {
      table.enter();
      slotIdx.set(table.getSlotIndex());
      table.exit();
    });
    threadA.start();
    threadA.join();

    int idx = slotIdx.get();
    assertThat(idx).isGreaterThanOrEqualTo(0);

    // Access internal slotOwners and slotReleased via reflection.
    WeakReference<Thread>[] owners = getSlotOwners(table);
    AtomicIntegerArray released = getSlotReleased(table);

    // Precondition: slot has an owner and is not released.
    assertThat(owners[idx]).isNotNull();
    assertThat(released.get(idx)).isEqualTo(0);

    // Simulate GC collecting thread A by clearing the WeakReference.
    owners[idx].clear();

    // computeSafeEpoch() should detect the dead thread and reclaim the slot.
    table.computeSafeEpoch();

    // Re-read volatile fields after reclamation (grow may have replaced them,
    // though unlikely here).
    owners = getSlotOwners(table);
    released = getSlotReleased(table);

    // (a) The slot index was returned to the free list.
    assertThat(table.getFreeListSize()).isGreaterThanOrEqualTo(1);
    // (b) slotOwners[index] is null.
    assertThat(owners[idx]).isNull();
    // (c) slotReleased[index] is 1.
    assertThat(released.get(idx)).isEqualTo(1);
  }

  /**
   * A15. Double-free prevention — Cleaner vs. scan race.
   *
   * <p>Allocate a slot on thread A (enter + exit). Trigger both
   * releaseSlot(index) (Cleaner path) and reclaimSlot(index) (scan path
   * via computeSafeEpoch after clearing WeakRef) concurrently from two
   * threads. Verify: the slot index appears in the free list exactly once.
   */
  @Test
  public void testDoubleFreePrevention() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);
    AtomicInteger slotIdx = new AtomicInteger(-1);

    // Thread A allocates a slot, enters, exits.
    Thread threadA = new Thread(() -> {
      table.enter();
      slotIdx.set(table.getSlotIndex());
      table.exit();
    });
    threadA.start();
    threadA.join();

    int idx = slotIdx.get();
    assertThat(idx).isGreaterThanOrEqualTo(0);

    // Clear the WeakReference to simulate dead thread (for scan path).
    WeakReference<Thread>[] owners = getSlotOwners(table);
    owners[idx].clear();

    // Race both paths: releaseSlot (Cleaner) and computeSafeEpoch (scan).
    CyclicBarrier barrier = new CyclicBarrier(2);
    AtomicReference<Throwable> error = new AtomicReference<>();

    Thread cleanerPath = new Thread(() -> {
      try {
        barrier.await();
        table.releaseSlot(idx);
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    Thread scanPath = new Thread(() -> {
      try {
        barrier.await();
        table.computeSafeEpoch();
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });

    cleanerPath.start();
    scanPath.start();
    cleanerPath.join();
    scanPath.join();
    assertThat(error.get()).isNull();

    // The slot should appear in the free list exactly once.
    int freeListSize = table.getFreeListSize();
    // The free list should have exactly 1 entry for this slot.
    // We verify slotReleased is 1 (only one CAS succeeded).
    AtomicIntegerArray released = getSlotReleased(table);
    assertThat(released.get(idx)).isEqualTo(1);
    assertThat(freeListSize).isEqualTo(1);
  }

  /**
   * A16. reclaimSlot() re-check prevents slot theft.
   *
   * <p>Verifies the end-to-end invariant: when thread A dies and thread B
   * reuses slot S (via normal enter()), a concurrent computeSafeEpoch()
   * scan must NOT reclaim the slot out from under B. This tests the
   * reclaimSlot re-check path indirectly: if B has installed a live
   * WeakReference, the scan sees it and does not reclaim.
   *
   * <p>The TOCTOU re-check inside reclaimSlot is a safety net for the race
   * where computeSafeEpoch's snapshot of slotOwners differs from the current
   * volatile field (due to grow() swapping the array). This test verifies the
   * observable outcome: a live thread's slot is never stolen.
   */
  @Test
  public void testReclaimSlotReCheckPreventsSlotTheft() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);
    AtomicInteger slotIdx = new AtomicInteger(-1);

    // Thread A allocates slot S, enters, exits.
    Thread threadA = new Thread(() -> {
      table.enter();
      slotIdx.set(table.getSlotIndex());
      table.exit();
    });
    threadA.start();
    threadA.join();

    int idx = slotIdx.get();
    assertThat(idx).isGreaterThanOrEqualTo(0);

    // Simulate thread A death: release slot via releaseSlot.
    table.releaseSlot(idx);
    assertThat(table.getFreeListSize()).isGreaterThanOrEqualTo(1);

    // Thread B takes slot S via normal enter() — getOrAllocateSlot installs
    // a live WeakReference and resets slotReleased to 0.
    AtomicInteger slotB = new AtomicInteger(-1);
    CyclicBarrier bEntered = new CyclicBarrier(2);
    CyclicBarrier bExit = new CyclicBarrier(2);
    AtomicReference<Throwable> error = new AtomicReference<>();

    Thread threadB = new Thread(() -> {
      try {
        table.enter();
        slotB.set(table.getSlotIndex());
        bEntered.await();
        bExit.await();
        table.exit();
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    threadB.start();
    bEntered.await();
    assertThat(error.get()).isNull();

    // B should have reused A's slot.
    assertThat(slotB.get()).isEqualTo(idx);

    // B is active — slotReleased must be 0, slotOwners has live ref.
    assertThat(getSlotReleased(table).get(idx)).isEqualTo(0);
    WeakReference<Thread>[] owners = getSlotOwners(table);
    assertThat(owners[idx]).isNotNull();
    assertThat(owners[idx].get()).isNotNull();

    int freeListBefore = table.getFreeListSize();

    // computeSafeEpoch scans all slots. For slot S: it's active (not INACTIVE)
    // since B entered, so reclaimSlot is not even triggered. But even if
    // a timing artifact made the slot appear INACTIVE, the live WeakReference
    // would prevent reclamation via the re-check.
    table.computeSafeEpoch();

    // Slot must NOT have been reclaimed.
    assertThat(table.getFreeListSize()).isEqualTo(freeListBefore);
    assertThat(getSlotReleased(table).get(idx)).isEqualTo(0);

    // B can exit normally.
    bExit.await();
    threadB.join();
    assertThat(error.get()).isNull();

    // After B exits, the slot value should be INACTIVE.
    assertThat(table.getSlotValue(idx)).isEqualTo(EpochTable.INACTIVE);
  }

  /**
   * A17. Cleanable cancellation on slot reuse.
   *
   * <p>Thread A allocates slot S, enters, exits, dies. Scan reclaims slot S.
   * Thread B takes S, calls oldCleanable.clean() (should be no-op since scan
   * already reclaimed), resets slotReleased = 0, registers new Cleanable.
   * Verify: A's Cleaner action does not fire after B's reset. B's slot
   * remains active and is not spuriously returned to the free list.
   *
   * <p>This test verifies that getOrAllocateSlot correctly cancels old
   * Cleanable handles before resetting slotReleased, preventing use-after-free.
   */
  @Test
  public void testCleanableCancellationOnSlotReuse() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);
    AtomicInteger slotIdx = new AtomicInteger(-1);

    // Thread A allocates slot S, enters, exits.
    Thread threadA = new Thread(() -> {
      table.enter();
      slotIdx.set(table.getSlotIndex());
      table.exit();
    });
    threadA.start();
    threadA.join();

    int idx = slotIdx.get();
    assertThat(idx).isGreaterThanOrEqualTo(0);

    // Simulate scan-based reclamation: clear WeakRef, call computeSafeEpoch.
    WeakReference<Thread>[] owners = getSlotOwners(table);
    owners[idx].clear();
    table.computeSafeEpoch();

    // Verify scan reclaimed the slot.
    assertThat(getSlotReleased(table).get(idx)).isEqualTo(1);
    assertThat(table.getFreeListSize()).isGreaterThanOrEqualTo(1);

    // Thread B takes slot S via normal enter() — this calls
    // getOrAllocateSlot which handles Cleanable cancellation internally.
    AtomicInteger slotB = new AtomicInteger(-1);
    AtomicReference<Throwable> error = new AtomicReference<>();
    CyclicBarrier bEntered = new CyclicBarrier(2);
    CyclicBarrier bExit = new CyclicBarrier(2);

    Thread threadB = new Thread(() -> {
      try {
        table.enter();
        slotB.set(table.getSlotIndex());
        bEntered.await();
        bExit.await();
        table.exit();
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    threadB.start();
    bEntered.await();
    assertThat(error.get()).isNull();

    // Thread B should have reused slot S.
    assertThat(slotB.get()).isEqualTo(idx);

    // slotReleased must be 0 (B reset it).
    assertThat(getSlotReleased(table).get(idx)).isEqualTo(0);

    // B's slot is active — not in the free list.
    // Best-effort provocation: System.gc() is advisory, so the Cleaner may
    // not run within 100ms. Even if it doesn't fire during this test, the
    // critical invariant (slotReleased == 0) is also guarded by the CAS in
    // releaseSlot — if Cleanable.clean() in getOrAllocateSlot correctly
    // deregistered the old action, the old Cleaner can never fire.
    System.gc();
    Thread.sleep(100);

    // slotReleased should still be 0 — A's Cleanable was cancelled by B's
    // getOrAllocateSlot call, so the old Cleaner action is a no-op.
    assertThat(getSlotReleased(table).get(idx)).isEqualTo(0);

    // B can exit normally.
    bExit.await();
    threadB.join();
    assertThat(error.get()).isNull();

    // After B exits, slot value should be INACTIVE.
    assertThat(table.getSlotValue(idx)).isEqualTo(EpochTable.INACTIVE);
  }

  /**
   * A18. waitForSafeEpoch — immediate return when all inactive.
   *
   * <p>Create EpochTable, advance epoch twice to E+1. No threads in critical
   * sections. Call waitForSafeEpoch(E) where E is the first advance.
   * computeSafeEpoch returns E+1 - 1 = E >= E, so it returns immediately.
   */
  @Test
  public void testWaitForSafeEpochImmediateReturn() {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);

    // Advance epoch twice: first to E, then to E+1.
    // With all slots inactive, computeSafeEpoch() = (E+1) - 1 = E >= E.
    long e = table.advanceEpoch();
    table.advanceEpoch();

    long start = System.nanoTime();
    table.waitForSafeEpoch(e);
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    // Should return within a few milliseconds — no spinning needed.
    assertThat(elapsed).isLessThan(100);
  }

  /**
   * A19. waitForSafeEpoch — blocks until thread exits.
   *
   * <p>Thread T enters at epoch E. Advance epoch. Call waitForSafeEpoch(E)
   * on another thread — it must not return. After a brief delay, have T
   * call exit(). Verify waitForSafeEpoch returns promptly (within a few
   * hundred milliseconds of T's exit).
   */
  @Test
  public void testWaitForSafeEpochBlocksUntilExit() throws Exception {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);

    long e = table.currentEpoch();
    CyclicBarrier enteredBarrier = new CyclicBarrier(2);
    CountDownLatch exitLatch = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();

    // Thread T enters at epoch E.
    Thread t = new Thread(() -> {
      try {
        table.enter();
        enteredBarrier.await();
        // Wait for signal to exit.
        exitLatch.await();
        table.exit();
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    t.start();
    enteredBarrier.await();

    // Advance epoch so that waitForSafeEpoch(e) needs T to exit.
    table.advanceEpoch();

    // Call waitForSafeEpoch on a separate thread — it should block.
    AtomicBoolean waitReturned = new AtomicBoolean(false);
    AtomicReference<Throwable> waitError = new AtomicReference<>();
    Thread waiter = new Thread(() -> {
      try {
        table.waitForSafeEpoch(e);
        waitReturned.set(true);
      } catch (Throwable ex) {
        waitError.compareAndSet(null, ex);
      }
    });
    waiter.start();

    // Give the waiter time to spin — it should NOT return.
    Thread.sleep(200);
    assertThat(waitReturned.get()).isFalse();

    // Signal T to exit.
    exitLatch.countDown();
    t.join();
    assertThat(error.get()).isNull();

    // Waiter should return promptly after T's exit.
    waiter.join(2000);
    assertThat(waiter.isAlive()).isFalse();
    assertThat(waitError.get()).isNull();
    assertThat(waitReturned.get()).isTrue();
  }

  /**
   * A20. waitForSafeEpoch — throws if called inside critical section.
   *
   * <p>Enter a critical section on the current thread. Call
   * waitForSafeEpoch(currentEpoch). Verify IllegalStateException is thrown
   * immediately (not after timeout). Exit the critical section.
   */
  @Test
  public void testWaitForSafeEpochThrowsIfInsideCriticalSection() {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);

    table.enter();
    long epoch = table.currentEpoch();

    try {
      assertThatThrownBy(() -> table.waitForSafeEpoch(epoch))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("inside EBR critical section");
    } finally {
      table.exit();
    }
  }

  /**
   * A21. waitForSafeEpoch — timeout fires on stuck slot.
   *
   * <p>Configure a short timeout (500ms). Enter a critical section on
   * thread T and do not exit. Call waitForSafeEpoch from another thread.
   * Verify IllegalStateException is thrown after approximately the
   * configured timeout. Clean up: exit T's critical section.
   */
  @Test
  public void testWaitForSafeEpochTimeout() throws Exception {
    long timeoutMs = 500;
    EpochTable table = new EpochTable(timeoutMs);

    long e = table.currentEpoch();
    CyclicBarrier enteredBarrier = new CyclicBarrier(2);
    CountDownLatch cleanupLatch = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();

    // Thread T enters and holds the critical section.
    Thread t = new Thread(() -> {
      try {
        table.enter();
        enteredBarrier.await();
        cleanupLatch.await();
        table.exit();
      } catch (Throwable ex) {
        error.compareAndSet(null, ex);
      }
    });
    t.start();
    enteredBarrier.await();

    // Advance epoch so waitForSafeEpoch(e) requires T to exit.
    table.advanceEpoch();

    // Call waitForSafeEpoch — should timeout.
    long start = System.nanoTime();
    assertThatThrownBy(() -> table.waitForSafeEpoch(e))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("timed out");
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    // Should have taken approximately the timeout duration.
    // Allow generous margin for CI variability.
    assertThat(elapsed).isBetween(timeoutMs / 2, timeoutMs * 3);

    // Clean up: let T exit.
    cleanupLatch.countDown();
    t.join();
    assertThat(error.get()).isNull();
  }

  /**
   * A22. currentEpoch() — same-thread coherence with advanceEpoch().
   *
   * <p>Call advanceEpoch() on thread T. Immediately call currentEpoch()
   * on the same thread T. Verify the returned value equals the
   * pre-advance value + 1. (Validates opaque same-thread coherence.)
   */
  @Test
  public void testCurrentEpochCoherenceWithAdvanceEpoch() {
    EpochTable table = new EpochTable(TEST_TIMEOUT_MS);

    long before = table.currentEpoch();
    long advanced = table.advanceEpoch();
    long after = table.currentEpoch();

    assertThat(advanced).isEqualTo(before + 1);
    assertThat(after).isEqualTo(advanced);
  }

  // -----------------------------------------------------------------------
  // Reflective helpers for accessing EpochTable internals.
  // -----------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static WeakReference<Thread>[] getSlotOwners(EpochTable table) {
    return (WeakReference<Thread>[]) SLOT_OWNERS_HANDLE.getVolatile(table);
  }

  private static AtomicIntegerArray getSlotReleased(EpochTable table) {
    return (AtomicIntegerArray) SLOT_RELEASED_HANDLE.getVolatile(table);
  }
}
