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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A cache-line-padded epoch table for epoch-based memory reclamation (EBR).
 *
 * <p>Each thread is assigned a dedicated slot. On entering a critical section, the thread
 * records the current global epoch in its slot. On exiting, the slot is set to {@link #INACTIVE}.
 * The reclaimer scans all slots to find the minimum active epoch; any retired entry with
 * {@code retireEpoch <= safeEpoch} (where {@code safeEpoch = minActiveSlot - 1}) can be
 * physically reclaimed.
 *
 * <p>Slot access uses {@link VarHandle} with carefully chosen access modes:
 * <ul>
 *   <li>{@code enter()}: opaque write + {@code fullFence()} + grow-safety retry loop</li>
 *   <li>{@code exit()}: release write</li>
 *   <li>{@code advanceEpoch()}: opaque write</li>
 *   <li>{@code computeSafeEpoch()}: acquire reads</li>
 * </ul>
 *
 * <p>See the implementation plan (docs/adr/disk-cache-ebr/implementation-plan.md) for
 * detailed memory ordering rationale and correctness arguments.
 */
public final class EpochTable {

  private static final VarHandle SLOTS_HANDLE =
      MethodHandles.arrayElementVarHandle(long[].class);

  private static final VarHandle GLOBAL_EPOCH_HANDLE;

  static {
    try {
      GLOBAL_EPOCH_HANDLE = MethodHandles.lookup()
          .findVarHandle(EpochTable.class, "globalEpoch", long.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Sentinel value indicating a slot is not in a critical section.
   * Chosen as {@code Long.MAX_VALUE} so that INACTIVE slots do not lower
   * the minimum during {@link #computeSafeEpoch()} scans.
   */
  static final long INACTIVE = Long.MAX_VALUE;

  // Cache-line padding: 128 bytes per slot = 16 longs. Only the first long in
  // each 16-long group holds the epoch value; the remaining 15 are unused padding.
  // This prevents false sharing between threads writing to adjacent slots.
  // Slot i is stored at array index i * SLOT_STRIDE.
  private static final int SLOT_STRIDE = 128 / Long.BYTES; // 16

  // Global epoch, incremented periodically (e.g. on each eviction drain cycle).
  // Accessed via GLOBAL_EPOCH_HANDLE (opaque reads/writes).
  // Initial value is 0 (Java default). This is intentional: with globalEpoch = 0
  // and all slots INACTIVE, computeSafeEpoch() returns -1 (= 0 - 1), so no
  // entries are reclaimable until the first advanceEpoch() call. This guarantees
  // at least one full epoch elapses between retirement and reclamation.
  @SuppressWarnings("unused")
  private volatile long globalEpoch;

  // Per-slot epoch values. INACTIVE means the thread is not in a critical section.
  // Each slot is padded to its own cache line via stride-based indexing: slot i
  // lives at index i * SLOT_STRIDE (128 bytes apart). The array is sized as
  // numSlots * SLOT_STRIDE.
  // Accessed via SLOTS_HANDLE for per-element access.
  // The array reference is volatile to support dynamic growth (see step 1.2).
  private volatile long[] slots;

  // Lock for dynamic array growth. Serializes grow() to prevent concurrent
  // resizes. Also used by releaseSlot() and reclaimSlot() (added in later
  // steps) to ensure their CAS targets the current arrays.
  // Lock ordering: evictionLock → growLock (from reclaimSlot);
  // growLock only (from grow, releaseSlot). No cycles.
  private final ReentrantLock growLock = new ReentrantLock();

  // Free list of reusable slot indices. Threads that die return their slot
  // index here (via Cleaner or scan-based reclamation in later steps).
  // New threads try the free list before allocating a fresh index.
  private final ConcurrentLinkedQueue<Integer> freeList = new ConcurrentLinkedQueue<>();

  // Next slot index to allocate when the free list is empty.
  private final AtomicInteger nextSlotIndex = new AtomicInteger(0);

  // ThreadLocal holding each thread's SlotHandle (assigned on first enter()).
  private final ThreadLocal<SlotHandle> threadLocal = new ThreadLocal<>();

  /**
   * Creates an EpochTable with initial capacity for
   * {@code availableProcessors() * 4} logical slots.
   */
  public EpochTable() {
    int initialSlotCount = Runtime.getRuntime().availableProcessors() * 4;
    int arraySize = initialSlotCount * SLOT_STRIDE;
    long[] s = new long[arraySize];
    // Fill all padded slot positions with INACTIVE.
    for (int i = 0; i < arraySize; i += SLOT_STRIDE) {
      s[i] = INACTIVE;
    }
    this.slots = s;
  }

  /**
   * Enters a critical section. Records the current global epoch in this thread's
   * slot. Must be paired with exactly one {@link #exit()} call.
   *
   * <p>Uses opaque write + {@code fullFence()} to ensure the slot write is visible
   * to the reclaimer before the entering thread reads any shared data. The retry
   * loop guards against a concurrent {@code grow()} replacing the slots array.
   *
   * @throws IllegalStateException if this thread is already inside a critical section
   *                                (reentrancy is not supported)
   */
  public void enter() {
    SlotHandle handle = getOrAllocateSlot();
    if (handle.active) {
      throw new IllegalStateException(
          "EBR critical section is not reentrant (thread="
              + Thread.currentThread().getName()
              + ", slot=" + handle.slotIndex + ")");
    }
    handle.active = true;

    // Read epoch once, BEFORE the retry loop. If grow() causes a retry while
    // the global epoch concurrently advances, the retry writes the original
    // (now stale-by-one) epoch value. This is conservative — the thread appears
    // to have entered one epoch earlier, which can only delay reclamation
    // (never cause use-after-free).
    long epoch = (long) GLOBAL_EPOCH_HANDLE.getOpaque(this);
    int paddedIndex = handle.slotIndex * SLOT_STRIDE;
    long[] s;
    do {
      s = slots; // volatile read
      SLOTS_HANDLE.setOpaque(s, paddedIndex, epoch);
      VarHandle.fullFence();
    } while (s != slots); // volatile read — detect grow
  }

  /**
   * Exits the critical section. Marks this thread's slot as {@link #INACTIVE}
   * using a release write, ensuring all critical-section memory accesses complete
   * before the slot is cleared.
   *
   * <p>No retry loop is needed: if {@code grow()} races and the release write
   * lands in the old array, the new array retains a stale epoch value. This is
   * conservative (delays reclamation, never causes use-after-free) and self-corrects
   * on the next enter/exit cycle.
   *
   * @throws IllegalStateException if this thread is not inside a critical section
   */
  public void exit() {
    SlotHandle handle = threadLocal.get();
    if (handle == null || !handle.active) {
      throw new IllegalStateException(
          "EBR exit without matching enter (thread="
              + Thread.currentThread().getName() + ")");
    }
    handle.active = false;
    SLOTS_HANDLE.setRelease(slots, handle.slotIndex * SLOT_STRIDE, INACTIVE);
  }

  /**
   * Advances the global epoch by one. Must be called under external synchronization
   * (e.g., the eviction lock) — concurrent unsynchronized calls will silently lose
   * epoch advances.
   *
   * <p>Uses opaque write — the eviction lock's unlock provides the necessary
   * release fence, and same-thread opaque coherence guarantees visibility for
   * callers that read the epoch on the same thread (e.g., {@code waitForSafeEpoch}).
   *
   * @return the new epoch value after advancing
   */
  public long advanceEpoch() {
    long current = (long) GLOBAL_EPOCH_HANDLE.getOpaque(this);
    long next = current + 1;
    GLOBAL_EPOCH_HANDLE.setOpaque(this, next);
    return next;
  }

  /**
   * Computes the safe epoch — the largest epoch value such that no thread is
   * currently in a critical section that started at or before that epoch. Any
   * retired entry with {@code retireEpoch <= safeEpoch} can be physically reclaimed.
   *
   * <p>Scans all slots using acquire reads, which pair with:
   * <ul>
   *   <li>{@code exit()}'s release write — establishes happens-before for
   *       INACTIVE observations</li>
   *   <li>{@code enter()}'s opaque write + fullFence — hardware-level visibility
   *       guarantee for active epoch observations</li>
   * </ul>
   *
   * @return the safe epoch, or -1 if the global epoch is corrupted
   */
  public long computeSafeEpoch() {
    long currentEpoch = (long) GLOBAL_EPOCH_HANDLE.getOpaque(this);
    // Defensive check: globalEpoch must never be INACTIVE. If it is,
    // something has gone badly wrong — refuse to reclaim anything.
    if (currentEpoch == INACTIVE) {
      return -1;
    }
    long min = currentEpoch;
    // Read the volatile slots reference once. If a concurrent grow() swaps
    // the array, we scan the snapshot we captured. Any newly added slots
    // hold INACTIVE (never entered), so missing them does not change the
    // computed minimum.
    long[] slotsSnapshot = slots;
    // Iterate only the padded slot positions (stride = SLOT_STRIDE).
    for (int i = 0; i < slotsSnapshot.length; i += SLOT_STRIDE) {
      long slotValue = (long) SLOTS_HANDLE.getAcquire(slotsSnapshot, i);
      if (slotValue != INACTIVE && slotValue < min) {
        min = slotValue;
      }
    }
    // min is the oldest active epoch. Entries retired strictly before min
    // are safe to reclaim. If all slots are INACTIVE, min == currentEpoch,
    // so safeEpoch == currentEpoch - 1.
    return min - 1;
  }

  /**
   * Returns the current global epoch value. Uses opaque read — same-thread
   * coherence with {@link #advanceEpoch()}.
   *
   * @return the current epoch
   */
  public long currentEpoch() {
    return (long) GLOBAL_EPOCH_HANDLE.getOpaque(this);
  }

  /**
   * Returns the slot index assigned to the calling thread, or -1 if the
   * thread has no slot. Package-private, for testing.
   */
  int getSlotIndex() {
    SlotHandle handle = threadLocal.get();
    return handle != null ? handle.slotIndex : -1;
  }

  /**
   * Returns the raw value stored in the given padded slot index.
   * Package-private, for testing.
   */
  long getSlotValue(int logicalIndex) {
    return (long) SLOTS_HANDLE.getAcquire(slots, logicalIndex * SLOT_STRIDE);
  }

  /**
   * Returns the current number of logical slots (allocated array capacity).
   * Package-private, for testing.
   */
  int getSlotCount() {
    return slots.length / SLOT_STRIDE;
  }

  /**
   * Returns the current free list size. Package-private, for testing.
   */
  int getFreeListSize() {
    return freeList.size();
  }

  /**
   * Gets or allocates a SlotHandle for the current thread. On first call,
   * allocates a new slot index from the free list or the next-index counter.
   */
  private SlotHandle getOrAllocateSlot() {
    SlotHandle handle = threadLocal.get();
    if (handle != null) {
      return handle;
    }
    int slotIndex = allocateSlotIndex();
    handle = new SlotHandle(slotIndex);
    threadLocal.set(handle);
    return handle;
  }

  /**
   * Allocates a slot index from the free list, or assigns the next
   * sequential index. If the index exceeds current capacity, triggers
   * {@link #grow(int)} to resize the slots array.
   */
  private int allocateSlotIndex() {
    Integer recycled = freeList.poll();
    if (recycled != null) {
      return recycled;
    }
    int index = nextSlotIndex.getAndIncrement();
    long[] currentSlots = slots;
    int paddedIndex = index * SLOT_STRIDE;
    if (paddedIndex >= currentSlots.length) {
      grow(index);
    }
    return index;
  }

  /**
   * Grows the slots array to accommodate at least {@code requiredIndex}.
   * Uses the LongAdder / Striped64 pattern: double the logical slot count,
   * copy existing values, fill new slots with {@link #INACTIVE}, and publish
   * the new array via a volatile write (the {@code slots} field is volatile).
   *
   * <p>Called under {@code growLock} to prevent concurrent resizes. If another
   * thread already grew the array to sufficient capacity, this method returns
   * immediately (double-checked locking).
   *
   * <p>The volatile write of {@code slots} must be the <b>last</b> publish
   * in {@code grow()}, after all auxiliary arrays (added in later steps).
   * This ensures readers that see the new {@code slots} array also see the
   * new auxiliary arrays via JMM transitivity.
   *
   * @param requiredIndex the logical slot index that triggered growth
   */
  private void grow(int requiredIndex) {
    growLock.lock();
    try {
      long[] current = slots;
      int requiredPaddedIndex = requiredIndex * SLOT_STRIDE;
      if (requiredPaddedIndex < current.length) {
        return; // another thread already grew
      }
      // Grow in terms of logical slots, then multiply by stride for the array.
      int currentSlotCount = current.length / SLOT_STRIDE;
      int newSlotCount = Math.max(currentSlotCount * 2, requiredIndex + 1);
      int newSize = newSlotCount * SLOT_STRIDE;
      long[] newSlots = new long[newSize];
      // Copy existing slots (preserves active epoch values and INACTIVE markers).
      System.arraycopy(current, 0, newSlots, 0, current.length);
      // Fill only the padded slot positions for new slots with INACTIVE;
      // padding longs remain 0 (never read).
      for (int i = current.length; i < newSize; i += SLOT_STRIDE) {
        newSlots[i] = INACTIVE;
      }
      // Auxiliary arrays (slotOwners, slotReleased, slotCleanables) will be
      // grown in lockstep here once they are added in steps 1.3/1.4.

      // IMPORTANT: `slots` must be published LAST, after all auxiliary arrays.
      // Volatile write publishes the new array. Readers (computeSafeEpoch)
      // read the volatile `slots` reference once and scan that snapshot.
      // A reader that sees the old array may miss newly added slots — those
      // slots hold INACTIVE (never entered), so missing them does not change
      // the computed minimum. This is neutral, not a safety violation.
      //
      // Safety w.r.t. concurrent enter()/exit():
      // - enter() uses a retry loop that re-reads `slots` after the fence.
      //   If grow() replaced the array, enter() detects the change and
      //   retries on the new array.
      // - exit() may write INACTIVE to the old array if it races with
      //   grow(). This is a liveness issue only (delays reclamation),
      //   not a safety violation.
      slots = newSlots; // volatile write — must be last
    } finally {
      growLock.unlock();
    }
  }

  /**
   * Per-thread handle caching the assigned slot index and reentrancy guard.
   * The {@code active} flag is thread-local (no synchronization needed) and
   * is checked unconditionally (including production) to catch reentrancy bugs
   * that would cause use-after-free.
   */
  static final class SlotHandle {
    final int slotIndex;
    boolean active;

    SlotHandle(int slotIndex) {
      this.slotIndex = slotIndex;
    }
  }
}
