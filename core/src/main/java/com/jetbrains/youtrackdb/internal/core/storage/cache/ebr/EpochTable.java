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
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
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

  // Shared Cleaner for all EpochTable instances. Used to release slots
  // when their owning thread dies and the SlotHandle becomes unreachable.
  // One daemon thread per JVM is acceptable — slot release is infrequent
  // and non-blocking.
  private static final Cleaner CLEANER = Cleaner.create();

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
  // The array reference is volatile to support dynamic growth (see grow()).
  private volatile long[] slots;

  // Per-slot released guard. Prevents double-free when both the Cleaner and
  // scan-based reclamation attempt to reclaim the same slot. Only the first
  // CAS from 0 → 1 wins; the loser is a no-op.
  // Indexed by logical slot index. Grown in lockstep with slots under
  // growLock. Replaced atomically on grow (AtomicIntegerArray cannot be
  // resized in place).
  private volatile AtomicIntegerArray slotReleased;

  // Per-slot WeakReference to the owning Thread. Used by computeSafeEpoch()
  // to detect dead threads and reclaim their slots eagerly, without waiting
  // for the Cleaner. Indexed by logical slot index (not padded). Grown in
  // lockstep with the slots array under growLock. See "Scan-based slot
  // reclamation" in the implementation plan for the full rationale.
  private volatile WeakReference<Thread>[] slotOwners;

  // Per-slot Cleaner.Cleanable handle returned by Cleaner.register(). Stored
  // so that a new thread reusing a slot can cancel the previous owner's
  // pending Cleaner action before resetting slotReleased to 0. Without this,
  // a dead thread's Cleaner could fire after the reset, CAS 0→1 on the
  // freshly reset slotReleased, and return the slot to the free list while
  // the new thread is actively using it — a use-after-free. Calling
  // oldCleanable.clean() deregisters the action idempotently (safe even if
  // the Cleaner or scan-based reclamation already ran). Indexed by logical
  // slot index. Grown in lockstep with slotOwners under growLock.
  private volatile Cleaner.Cleanable[] slotCleanables;

  // Lock for dynamic array growth. Serializes grow() to prevent concurrent
  // resizes. Also used by releaseSlot() and reclaimSlot() to ensure their
  // CAS targets the current arrays.
  // Lock ordering: evictionLock → growLock (from reclaimSlot);
  // growLock only (from grow, releaseSlot). No cycles.
  private final ReentrantLock growLock = new ReentrantLock();

  // Free list of reusable slot indices. Threads that die return their slot
  // index here (via Cleaner or scan-based reclamation in computeSafeEpoch).
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
    @SuppressWarnings("unchecked")
    WeakReference<Thread>[] owners = new WeakReference[initialSlotCount];
    this.slotOwners = owners;
    this.slotReleased = new AtomicIntegerArray(initialSlotCount);
    this.slotCleanables = new Cleaner.Cleanable[initialSlotCount];
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
    // Read the volatile slots and slotOwners references once. If a concurrent
    // grow() swaps the arrays, we scan the snapshots we captured. Any newly
    // added slots hold INACTIVE (never entered), so missing them does not
    // change the computed minimum.
    long[] slotsSnapshot = slots;
    WeakReference<Thread>[] ownersSnapshot = slotOwners;
    // Iterate only the padded slot positions (stride = SLOT_STRIDE).
    for (int i = 0; i < slotsSnapshot.length; i += SLOT_STRIDE) {
      long slotValue = (long) SLOTS_HANDLE.getAcquire(slotsSnapshot, i);
      if (slotValue == INACTIVE) {
        // Slot is inactive — check if the owning thread is dead.
        // If the WeakReference is cleared, the thread can never call
        // enter() again, so this slot can be safely reclaimed.
        // See "Scan-based slot reclamation" in the implementation plan
        // for the full correctness argument.
        int logicalIndex = i / SLOT_STRIDE;
        if (logicalIndex < ownersSnapshot.length) {
          WeakReference<Thread> ref = ownersSnapshot[logicalIndex];
          if (ref != null && ref.get() == null) {
            reclaimSlot(logicalIndex);
          }
        }
        // INACTIVE does not affect min — skip.
      } else if (slotValue < min) {
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
   * allocates a new slot index from the free list or the next-index counter,
   * registers a Cleaner action to release the slot when the handle becomes
   * unreachable, and cancels any pending Cleaner action from a previous
   * owner of the reused slot.
   */
  private SlotHandle getOrAllocateSlot() {
    SlotHandle handle = threadLocal.get();
    if (handle != null) {
      return handle;
    }
    int slotIndex = allocateSlotIndex();

    // Cancel any pending Cleaner action from the previous owner of this slot.
    // Without this, the dead thread's Cleaner could fire after slotReleased is
    // reset to 0 below, CAS 0→1 on the freshly reset flag, and return the slot
    // to the free list while the new thread is actively using it — a
    // use-after-free. Cleanable.clean() is idempotent: if the Cleaner already
    // ran, the internal action is a no-op (the slotReleased CAS fails). In all
    // cases, clean() deregisters the Cleanable from the Cleaner's reference
    // queue, ensuring the old action can never fire after this point.
    //
    // AIOOBE safety: slotIndex came from either the free list (always within
    // the current array bounds) or allocateSlotIndex() which calls grow()
    // before returning. grow() publishes slotCleanables (volatile write) before
    // slots (volatile write), and the calling thread saw the grown slots array
    // (or held growLock), so the volatile read of slotCleanables here sees the
    // resized array.
    Cleaner.Cleanable oldCleanable = slotCleanables[slotIndex];
    if (oldCleanable != null) {
      oldCleanable.clean();
    }

    // Note: `handle` remains reachable from this stack frame throughout the
    // initialization sequence below, so the Cleaner cannot fire prematurely
    // between slotReleased.set(0) and threadLocal.set(handle).
    handle = new SlotHandle(slotIndex);
    // Must precede slotReleased.set(0) — that volatile write acts as
    // the release barrier that publishes this plain write to reclaimSlot()'s
    // re-check (which uses the CAS's acquire semantics to observe it).
    // Note: this plain write races benignly with grow() — if grow() copies
    // the old slotOwners array before this write lands, the new array retains
    // the stale value. The re-check in reclaimSlot() could theoretically see
    // the stale ref, but this window is nanoseconds (between getOrAllocateSlot
    // returning and enter()'s epoch write making the slot non-INACTIVE). See
    // the implementation plan ("Slot reuse writes and concurrent grow()") for
    // the full argument.
    slotOwners[slotIndex] = new WeakReference<>(Thread.currentThread());
    slotReleased.set(slotIndex, 0); // mark as not-yet-released (release fence)
    Cleaner.Cleanable cleanable = CLEANER.register(handle,
        new ReleaseSlotAction(this, slotIndex));
    slotCleanables[slotIndex] = cleanable;
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
   * in {@code grow()}, after all auxiliary arrays ({@code slotOwners},
   * {@code slotCleanables}, {@code slotReleased}).
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

      // Grow slotOwners in lockstep.
      WeakReference<Thread>[] currentOwners = slotOwners;
      @SuppressWarnings("unchecked")
      WeakReference<Thread>[] newOwners = new WeakReference[newSlotCount];
      if (currentOwners != null) {
        System.arraycopy(currentOwners, 0, newOwners, 0,
            Math.min(currentOwners.length, newSlotCount));
      }
      slotOwners = newOwners; // volatile write

      // Grow slotCleanables in lockstep.
      Cleaner.Cleanable[] currentCleanables = slotCleanables;
      Cleaner.Cleanable[] newCleanables = new Cleaner.Cleanable[newSlotCount];
      if (currentCleanables != null) {
        System.arraycopy(currentCleanables, 0, newCleanables, 0,
            Math.min(currentCleanables.length, newSlotCount));
      }
      slotCleanables = newCleanables; // volatile write

      // AtomicIntegerArray cannot be resized in place — replace it.
      // Copy existing released flags; new slots default to 0 (not released).
      AtomicIntegerArray currentReleased = slotReleased;
      AtomicIntegerArray newReleased = new AtomicIntegerArray(newSlotCount);
      if (currentReleased != null) {
        for (int j = 0; j < currentReleased.length(); j++) {
          newReleased.set(j, currentReleased.get(j));
        }
      }
      slotReleased = newReleased; // volatile write

      // IMPORTANT: `slots` must be published LAST, after all auxiliary arrays
      // (slotOwners, slotCleanables, slotReleased). This guarantees that a
      // reader (computeSafeEpoch) that sees the new `slots` array also sees the
      // new auxiliary arrays via JMM transitivity: auxiliary volatile writes hb→
      // slots volatile write (program order within grow()), and if the reader's
      // slots volatile read sees the new array, it forms a hb chain through to
      // the auxiliary array reads.
      //
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
   * Releases a slot back to the free list. Called by the Cleaner when the
   * owning thread's {@link SlotHandle} becomes unreachable (thread died or
   * the ThreadLocal was cleared).
   *
   * <p>Acquires {@code growLock} to ensure the {@code slotReleased} CAS targets
   * the current {@link AtomicIntegerArray} (not a stale copy being replaced by
   * {@link #grow()}). Lock ordering: {@code releaseSlot()} acquires
   * {@code growLock} only (called from the Cleaner thread, no other lock held).
   *
   * <p>Uses {@code setRelease} for the INACTIVE write — same access mode as
   * {@link #exit()} for consistency: both perform the same logical operation
   * (mark slot inactive). See the implementation plan for the full rationale.
   *
   * @param slotIndex the logical slot index to release
   */
  void releaseSlot(int slotIndex) {
    growLock.lock();
    try {
      // CAS guards against double-free: if scan-based reclamation in
      // computeSafeEpoch() already reclaimed this slot, the CAS fails and we skip.
      // Only the first path to set released = 1 performs the actual release.
      if (!slotReleased.compareAndSet(slotIndex, 0, 1)) {
        return; // already reclaimed by scan-based reclamation
      }
      // Read the current volatile slots reference — never a stale array
      // captured at registration time. If grow() replaced the array since
      // the slot was allocated, this still writes to the live array.
      SLOTS_HANDLE.setRelease(slots, slotIndex * SLOT_STRIDE, INACTIVE);
      slotOwners[slotIndex] = null; // help GC
      freeList.offer(slotIndex);
    } finally {
      growLock.unlock();
    }
  }

  /**
   * Reclaims a slot whose owning thread is dead. Uses a CAS on
   * {@code slotReleased} to prevent double-free with the Cleaner.
   * Called from {@link #computeSafeEpoch()} scans, which may run under
   * evictionLock (from {@code reclaimRetired()} or {@code assistReclamation()})
   * or outside it (from {@code waitForSafeEpoch()}). In either case,
   * this method only requires {@code growLock} for correctness.
   *
   * <p>Acquires {@code growLock} to ensure the CAS targets the current
   * {@link AtomicIntegerArray}. Without this, a TOCTOU race exists: the
   * volatile read of {@code slotReleased} could return the old array, then
   * {@code grow()} could copy the old value (0) to a new array and publish it
   * before the CAS executes. The CAS would succeed on the dead old array
   * while the new array retains the stale 0 — allowing a second CAS (from
   * the Cleaner or {@code Cleanable.clean()}) to also succeed and offer the
   * same index to the free list twice. Holding {@code growLock} makes the
   * read + CAS atomic w.r.t. {@code grow()}'s copy-and-publish sequence.
   *
   * <p><b>TOCTOU guard — re-check after CAS:</b> The caller
   * ({@code computeSafeEpoch()}) reads {@code slotOwners[i]} with a plain
   * array read OUTSIDE {@code growLock} and decides the owning thread is dead
   * ({@code WeakReference} cleared). Between that read and the CAS inside
   * this method, a new thread may have taken the slot from the free list
   * and reset {@code slotReleased} to 0 (volatile write / release).
   * On weakly-ordered architectures (ARM, RISC-V), the caller's earlier
   * plain read of {@code slotOwners[i]} may observe a stale value (e.g.,
   * the previous dead thread's cleared {@code WeakReference}) even though
   * the new thread has already written a live {@code WeakReference}.
   * The CAS sees the new thread's 0 and succeeds — stealing the slot
   * while the new thread is actively using it.
   *
   * <p><b>The fix:</b> after the CAS succeeds, re-read {@code slotOwners}
   * from the current volatile field (not the caller's snapshot) and verify
   * the owning thread is still dead. The CAS on {@code slotReleased} has
   * acquire semantics; if it read the new thread's volatile write of 0, the
   * release-acquire pairing guarantees the new thread's prior plain write to
   * {@code slotOwners[i]} is visible. If the re-check finds a live thread,
   * the CAS is undone ({@code slotReleased} reset to 0) and the slot is NOT
   * returned to the free list.
   *
   * <p>Lock ordering: when evictionLock is held ({@code reclaimRetired()} /
   * {@code assistReclamation()} path), the nesting is evictionLock → growLock.
   * When called from {@code waitForSafeEpoch()} (outside evictionLock), only
   * {@code growLock} is acquired. {@code grow()} acquires growLock only,
   * {@code releaseSlot()} acquires growLock only — no cycles in either case.
   *
   * @param logicalIndex the logical slot index to reclaim
   */
  private void reclaimSlot(int logicalIndex) {
    growLock.lock();
    try {
      if (slotReleased.compareAndSet(logicalIndex, 0, 1)) {
        // Re-check: the slot may have been reused by a new thread between
        // the caller's slotOwners read (outside growLock) and this CAS.
        // The CAS has acquire semantics: if it read 0 from the new thread's
        // volatile write (slotReleased.set(i, 0)), the release-acquire
        // pairing guarantees the new thread's prior slotOwners[i] write
        // (plain, but ordered before the volatile write by program order)
        // is visible here. Reading the current slotOwners volatile field
        // (not the caller's snapshot) ensures we see the latest array even
        // if grow() replaced it.
        WeakReference<Thread>[] currentOwners = slotOwners; // volatile read
        WeakReference<Thread> ref = currentOwners[logicalIndex];
        if (ref != null && ref.get() != null) {
          // A live thread owns this slot — undo the CAS and bail out.
          // The new thread will eventually die and be reclaimed by a
          // future scan or by the Cleaner.
          slotReleased.set(logicalIndex, 0);
          return;
        }
        // Thread is confirmed dead (or slot has no owner). Safe to reclaim.
        // Write null to the current slotOwners array (not the caller's
        // snapshot) to help GC and to prevent future scans from seeing
        // the stale cleared WeakReference.
        currentOwners[logicalIndex] = null;
        freeList.offer(logicalIndex);
      }
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

  /**
   * Cleaner action that releases a slot when the owning thread's
   * {@link SlotHandle} becomes unreachable. Implemented as a separate class
   * (not a lambda closing over {@code this}) to avoid preventing GC of the
   * {@code SlotHandle} — the Cleaner must hold a strong reference to the
   * action, so the action must not reference the tracked object.
   *
   * <p>The action holds a reference to the {@code EpochTable} rather than to
   * the {@code slots} array directly, so that {@code releaseSlot()} reads the
   * current volatile {@code slots} field (never a stale array from before a
   * {@code grow()}).
   */
  private static final class ReleaseSlotAction implements Runnable {
    private final EpochTable epochTable;
    private final int slotIndex;

    ReleaseSlotAction(EpochTable epochTable, int slotIndex) {
      this.epochTable = epochTable;
      this.slotIndex = slotIndex;
    }

    @Override
    public void run() {
      epochTable.releaseSlot(slotIndex);
    }
  }
}
