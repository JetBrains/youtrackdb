---
source_files:
  - core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/**
  - core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/base/DurableComponent.java
  - core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperation*.java
related_docs:
  - docs/internals/disk-cache.md
---

# Epoch-Based Reclamation for the Disk Cache

## Problem Statement

The disk cache (`LockFreeReadCache` + `WTinyLFUPolicy`) uses **per-entry reference counting**
to protect cache entries from eviction while they are in use. Every `loadForRead`/`loadForWrite`
call does `CacheEntry.acquireEntry()` (CAS increment on `state`), and every
`releaseFromRead`/`releaseFromWrite` does `releaseEntry()` (CAS decrement).

Note: `CachePointer.readersWritersReferrer` and `referrersCount` are **not** updated
per-access on the in-cache read path. They are updated structurally — once when the entry
is created (`incrementReadersReferrer()` in `WOWCache.load()`) and once during eviction
(`decrementReadersReferrer()` in `purgeEden()`). The per-access CAS cost on the read hot
path is **2 CAS operations per page access** (`acquireEntry` + `releaseEntry` on
`CacheEntry.state`).

These CAS operations are **write memory barriers on every page access**, which is the hottest
path in the storage engine. The cost is:
1. **Cache-line bouncing** — the `state` field is written by every thread that touches the
   page, invalidating the cache line across cores.
2. **Pipeline stalls** — every CAS is a full `lock cmpxchg` which serializes the store buffer.
3. **Failed-freeze retries** — during eviction, `freeze()` can only succeed when `state == 0`.
   Under load many entries have `state > 0`, so `WTinyLFUPolicy.purgeEden()` moves them back
   to eden and retries, adding latency to the eviction path.

Profiling (see `docs/disk-cache-ebr/write-barriers-read-path-analysis.md`) confirmed these
barriers as a top contributor to read-path overhead.

## Goal

Replace per-entry reference counting with **epoch-based memory reclamation (EBR)** so that:
- The **read path** (load page, read, release) performs **no atomic writes to shared state**
  on the entry or pointer.
- **Eviction** defers actual reclamation until all threads that could have observed the entry
  have exited their critical sections.
- The critical section is bounded by the existing
  `calculateInsideComponentOperation` / `executeInsideComponentOperation` methods on
  `DurableComponent`, which already wrap every page access in a clearly scoped block.

## Background: How the Current Design Works

### Entry Lifecycle (reference counting)

```
state >= 0  →  ALIVE  (value = # of active holders)
state == 0  →  RELEASED  (alive, no holders — eligible for freeze)
state == -1 →  FROZEN  (eviction candidate, no new acquires)
state == -2 →  DEAD  (removed from CHM, pointer being freed)
```

- `acquireEntry()` — CAS `state` from `n` to `n+1` (fails if `state < 0`)
- `releaseEntry()` — CAS `state` from `n` to `n-1`
- `freeze()` — CAS `state` from `0` to `-1` (only succeeds when no holders)
- `makeDead()` — CAS `state` from `-1` to `-2`

### CachePointer Lifecycle

`CachePointer` maintains:
- `referrersCount` (total references) — when it reaches 0, the direct memory buffer is released
  back to `ByteBufferPool`.
- `readersWritersReferrer` (packed readers + writers counts) — used by `WritersListener` to track
  dirty-page exclusivity for the write cache.

Both are updated via CAS, but only **structurally** for the in-cache read path
(once on entry creation, once on eviction) — not per-access. Per-access CAS
occurs on the write path (`incrementWritersReferrer` / `decrementWritersReferrer`)
and on the `silentLoadForRead` outside-cache path.

### Eviction (WTinyLFUPolicy)

Eviction runs under `evictionLock` (ReentrantLock). For each victim candidate:
1. Try `victim.freeze()` — succeeds only if `state == 0`.
2. If freeze succeeds: `data.remove(victim)`, `victim.makeDead()`, release pointer.
3. If freeze fails (entry is held): move victim back to eden and try another.

### Component Operations

`DurableComponent` methods `calculateInsideComponentOperation` and
`executeInsideComponentOperation` delegate to `AtomicOperationsManager`, which acquires
an exclusive lock on the component for the duration of the lambda. All page loads and
releases happen inside these lambdas.

## Design: Epoch-Based Reclamation

### Core Idea

Instead of tracking "is anyone holding this entry right now?" with per-entry CAS,
we track "has every thread that **might** have seen this entry finished its current
operation?" using a **global epoch counter** and **per-thread epoch snapshots**.

A thread entering a disk-cache critical section records the current global epoch.
A thread leaving the critical section clears its recorded epoch. An entry that is
logically evicted at epoch `E` can only have its memory reclaimed once **no thread**
has a recorded epoch `<= E`.

### Epoch Table Design

We use a dynamically-growable, cache-line-padded array of epoch slots — one per
thread. This is similar to the `LongAdder` / `Striped64` pattern in the JDK, which
grows its cells array under contention, and to the existing `AtomicOperationsTable`
pattern already used in this codebase.

```java
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

  static final long INACTIVE = Long.MAX_VALUE;

  // Cache-line padding: 128 bytes per slot = 16 longs. Only the first long in
  // each 16-long group holds the epoch value; the remaining 15 are unused padding.
  // This prevents false sharing between threads writing to adjacent slots.
  // Slot i is stored at array index i * SLOT_STRIDE.
  private static final int SLOT_STRIDE = 128 / Long.BYTES;  // 16

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
  // The array reference is volatile to support dynamic growth (see Slot Assignment).
  private volatile long[] slots;

  // Per-slot WeakReference to the owning Thread. Used by computeSafeEpoch() to
  // detect dead threads and reclaim their slots eagerly, without waiting for the
  // Cleaner. Indexed by logical slot index (not padded). Grown in lockstep with
  // the slots array under growLock. See "Scan-based slot reclamation" below.
  private volatile WeakReference<Thread>[] slotOwners;

  // Per-slot released guard. Prevents double-free when both the Cleaner and
  // scan-based reclamation attempt to reclaim the same slot. Only the first
  // CAS from 0 → 1 wins; the loser is a no-op. Indexed by logical slot index.
  // Grown in lockstep with slotOwners under growLock. Replaced atomically
  // on grow (AtomicIntegerArray cannot be resized in place).
  private volatile AtomicIntegerArray slotReleased;

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
}
```

Slot and global epoch access uses `VarHandle` with **different access modes
depending on the ordering requirements**:

**`enter()` — opaque write + full fence + grow-safety retry:**
```java
// The epoch is read once, BEFORE the retry loop. This is intentional:
// if grow() causes a retry while the global epoch concurrently advances,
// the retry writes the original (now stale-by-one) epoch value. This is
// conservative — the thread appears to have entered one epoch earlier,
// which can only delay reclamation (never cause use-after-free). Re-reading
// the epoch on each retry would add an unnecessary opaque read per iteration
// for a negligible improvement in reclamation latency during the rare
// grow() + epoch-advance race.
long epoch = (long) GLOBAL_EPOCH_HANDLE.getOpaque(this);
int paddedIndex = slotIndex * SLOT_STRIDE;
long[] s;
do {
  s = slots;                                              // volatile read
  SLOTS_HANDLE.setOpaque(s, paddedIndex, epoch);
  VarHandle.fullFence();
} while (s != slots);                                     // volatile read — detect grow
```
— **no CAS**, opaque write to a thread-owned cache line followed by a
**StoreLoad fence** (`VarHandle.fullFence()`). The fence is required to
guarantee that the slot write is visible to other threads (specifically the
reclaimer) **before** the entering thread can read any shared data structure
(e.g., `ConcurrentHashMap.get()`). Without the fence, the slot write could
remain in the store buffer (x86) or be reordered past subsequent loads
(ARM) while the thread proceeds to access entries in the CHM. The reclaimer
could then read the stale `INACTIVE` value, compute a safeEpoch that is too
high, and free an entry the thread is about to access — a use-after-free.

**Grow-safety retry loop:** The `do`/`while` loop guards against a race
with `grow()`. If `grow()` replaces the `slots` array between the volatile
read (loop top) and the fence, the opaque write lands in the **old** array.
`computeSafeEpoch()` reads the **new** array, where the slot still holds the
stale `INACTIVE` value copied during `grow()`, making this thread invisible
to the reclaimer — a use-after-free. The post-fence re-read of `slots`
detects the replacement (`s != slots`) and retries on the new array.

**Correctness argument:** When the loop exits (`s == slots`), the thread's
epoch is written to the array that `slots` currently references. Any
*subsequent* `grow()` call will `System.arraycopy` from this array,
preserving the epoch value in the new array. The timing argument:

- `grow()` copies **after** our fenced write → the copy includes our epoch
  (the `fullFence()` ensures global visibility on real hardware before the
  second volatile read of `slots`).
- `grow()` copies **before** our write or **concurrently** → `grow()` publishes
  a new array via `slots = newSlots` (volatile write). Our post-fence volatile
  re-read of `slots` observes the new reference → loop retries.

Since `grow()` is rare (only when thread count exceeds array capacity), the
loop body executes **once** in the common case. The retry adds one extra
volatile read of `slots` (which is a plain load on x86, `ldar` on ARM) —
negligible cost.

Opaque (rather than volatile) is used for the store itself because the
fence already provides the required StoreLoad barrier; a volatile store
would add a redundant release fence before the store. On x86, the fence
compiles to `mfence` (or `lock addl $0, (%rsp)`); on ARM, it compiles to
`dmb ish`. This is still **significantly cheaper** than the two CAS
operations (`lock cmpxchg`) it replaces: one fence vs. two serializing
read-modify-write instructions that also bounce cache lines across cores.

This approach matches the established pattern in **crossbeam-epoch** (Rust),
which uses `Relaxed` store + `SeqCst` fence in its `pin()` operation for
the same reason.

**`exit()` — release write:**
`SLOTS_HANDLE.setRelease(slots, slotIndex * SLOT_STRIDE, INACTIVE)`
— **no CAS**, single release write. Release semantics are required here:
all memory accesses within the critical section (page data reads, pointer
dereferences) must be ordered **before** the slot is marked `INACTIVE`.
Without release, a weakly-ordered CPU (e.g., ARM) could reorder page data
reads past the `INACTIVE` store, allowing the reclaimer to free memory that
the thread is still reading. On x86 (TSO), release compiles to a plain store
(no extra cost); on ARM, it compiles to `stlr` (store-release, lightweight).

**`exit()` and `grow()` — liveness only, no safety issue:** `exit()` has the
same TOCTOU on the `slots` reference as `enter()`: if `grow()` replaces the
array between the volatile read and the release write, the `INACTIVE` value
lands in the old array. The new array retains the stale epoch value copied
during `grow()`, causing `computeSafeEpoch()` to see a phantom active slot.
Unlike `enter()`, this is **not** a safety violation — it is conservative
(delays reclamation, never causes use-after-free). The phantom clears itself
on the thread's next `enter()`/`exit()` cycle, which writes to the current
array. No retry loop is needed here.

**`advanceEpoch()` — opaque write:**
`GLOBAL_EPOCH_HANDLE.setOpaque(this, epoch + 1)` — called under `evictionLock`
so no contention. Opaque is sufficient for two reasons:

1. **Under `evictionLock`** (the common case — `reclaimRetired()` in
   `drainBuffers()` / `assistReclamation()`): the lock's unlock provides the
   necessary release fence, and `computeSafeEpoch()` is called within the
   same lock section — same-thread opaque coherence guarantees visibility.

2. **Outside `evictionLock`** (the `waitForSafeEpoch()` path in `clearFile()`
   / `close()` / `clear()`): the calling thread is the same thread that wrote
   the epoch under the lock. Opaque provides per-variable coherence: a
   thread's own opaque writes are always visible to its subsequent opaque
   reads of the same variable. So `computeSafeEpoch()`'s `getOpaque` read
   of `globalEpoch` sees the value written by the same thread's
   `advanceEpoch()`, regardless of whether the lock is still held.

**Slot lifecycle summary:**
- `enter()`: opaque write + `fullFence()` in a retry loop (StoreLoad barrier
  ensures slot is visible to reclaimer before thread reads shared data;
  retry loop ensures the write targets the current `slots` array even if
  `grow()` races — see "Grow-safety retry loop" above)
- `exit()`: release write (ensures critical section completes before exit)
- `releaseSlot()`: release write (consistency with `exit()` — same logical
  operation; see "Slot reuse" in Slot Assignment for rationale)
- `advanceEpoch()`: opaque write (evictionLock provides ordering under-lock;
  same-thread coherence provides ordering for outside-lock callers like
  `waitForSafeEpoch()`)

The critical property: `enter()` and `exit()` are **writes to
thread-local slots** — no contended atomics, no cache-line bouncing between
threads. In the common case (no concurrent `grow()`), `enter()` executes
the loop body exactly once.

**JMM formal analysis — enter() / computeSafeEpoch() pairing:**

The correctness of EBR depends on a critical visibility guarantee: if a
thread T has entered its critical section (written epoch E to its slot) and
subsequently reads an entry from the CHM, then the reclaimer must see T's
slot as `<= E` (not stale `INACTIVE`). Otherwise, the reclaimer could compute
a `safeEpoch >= E`, reclaim the entry, and cause a use-after-free.

The `exit()` → `computeSafeEpoch()` pairing is straightforward:
`setRelease` in `exit()` pairs with `getAcquire` in `computeSafeEpoch()`.
When the reclaimer's acquire read sees `INACTIVE`, the JMM guarantees a
happens-before edge: all of T's critical-section memory accesses completed
before the `INACTIVE` write, and the reclaimer observes them. This pairing
is formally correct under the JMM.

The `enter()` → `computeSafeEpoch()` pairing is more subtle. The `enter()`
path uses `setOpaque` (not `setRelease`) for the slot write, followed by
`fullFence()`. The reclaimer reads slots with `getAcquire`. In the JMM:

- **Opaque** provides per-variable coherence: for a single slot, reads and
  writes maintain a total order consistent with each thread's program order.
  The reclaimer will never see a value **older** than a value it has already
  observed for the same slot. However, opaque provides **no cross-variable
  ordering** — it does not form a happens-before edge with an acquire read.

- **`fullFence()`** constrains thread-local reordering: no loads or stores
  after the fence can be reordered before loads or stores preceding the fence,
  within the same thread. This ensures T's slot write is ordered before T's
  subsequent CHM read in T's program order. However, the JMM defines
  `fullFence()` as a thread-local reordering constraint, not as a cross-thread
  visibility guarantee.

The gap: the JMM does **not** formally guarantee that `setOpaque` +
`fullFence()` on thread T makes the slot value visible to another thread's
`getAcquire` read by any specific point. In the pure JMM model, the reclaimer
could, in theory, read the stale `INACTIVE` value even after T has executed
the fence and proceeded to read from the CHM.

**Why this is safe in practice (hardware-level argument):**

On all real hardware (x86, ARM, RISC-V), the combination of opaque store +
StoreLoad fence provides a stronger guarantee than the JMM requires:

- **x86 (TSO):** The `fullFence()` compiles to `mfence` (or `lock addl $0,
  (%rsp)`), which flushes the store buffer. After the fence completes, T's
  slot write is in the cache coherence domain — visible to all cores. Any
  subsequent `getAcquire` (which compiles to a plain load on x86, since TSO
  provides LoadLoad + LoadStore for free) on another core will see the value.

- **ARM (ARMv8):** The `fullFence()` compiles to `dmb ish` (full barrier).
  After the barrier, T's store is ordered before any subsequent loads/stores
  and is guaranteed to be visible to other cores' acquire loads (`ldar`).
  The ARM memory model (which is formally defined) guarantees this visibility.

- **RISC-V:** The `fullFence()` compiles to `fence rw,rw`. Combined with the
  acquire load (`fence r,rw` + load), this provides the same guarantee.

In all cases, the hardware cache coherence protocol ensures that once a store
passes through the StoreLoad barrier, it is globally visible. An acquire load
on any other core will observe it.

**JMM-strict alternative — `setVolatile`:**

For a purely JMM-provable implementation, `enter()` could use `setVolatile`
instead of `setOpaque` + `fullFence()`. A volatile write establishes a
happens-before edge with any subsequent volatile read (or acquire read) of the
same variable that sees the written value. The cost comparison:

- **x86:** `setVolatile` compiles to `mov` + `mfence` (or `lock xchg`).
  `setOpaque` + `fullFence()` compiles to `mov` + `mfence`. Identical cost.
  The only difference is that `setVolatile` includes a redundant release fence
  (StoreStore + LoadStore) before the store, which x86 provides for free (TSO).

- **ARM:** `setVolatile` may compile to `dmb ish` + `str` + `dmb ish` on
  older ARMv8, or to `stlr` on ARMv8.3+ (which provides sequential
  consistency). `setOpaque` + `fullFence()` compiles to `str` + `dmb ish`.
  On older ARMv8, `setVolatile` adds an extra `dmb ish` before the store.
  On ARMv8.3+, the cost is equivalent.

**Decision: use `setOpaque` + `fullFence()`** for consistency with the
crossbeam-epoch pattern and to avoid the redundant pre-store fence. The
hardware-level correctness argument is sound on all target platforms (x86 and
ARM). If a future JVM implementation were to run on hardware without cache
coherence (no such hardware exists in practice), this would need revisiting.

If desired, this can be changed to `setVolatile` at any time with no
functional or meaningful performance impact — the difference is at most one
redundant fence instruction on older ARM.

**Safe epoch (`computeSafeEpoch`):**

The safe epoch is the largest epoch value `S` such that **no thread is currently
in a critical section that started at or before `S`**. Any retired entry with
`retireEpoch <= S` is guaranteed to be unreachable by all threads and can be
physically reclaimed.

Computation: scan all slots and find the minimum active (non-`INACTIVE`) value.
The safe epoch is `minActiveSlot - 1`. If all slots are `INACTIVE` (no threads
in critical sections), `min` stays at `currentEpoch` (its initial value), so
the safe epoch is `currentEpoch - 1`. Entries retired at `currentEpoch` are
**not** yet reclaimable — they become reclaimable after the next epoch advance.

Slot reads use **acquire** semantics (`getAcquire`). This serves two purposes:

1. **Pairing with `exit()` (`setRelease`):** When the reclaimer reads
   `INACTIVE`, the release-acquire pair establishes a happens-before edge.
   All of the thread's critical-section memory accesses (page data reads,
   pointer dereferences) are guaranteed to have completed before the
   `INACTIVE` write, and the reclaimer observes this ordering. This is
   formally correct under the JMM.

2. **Pairing with `enter()` (`setOpaque` + `fullFence()`):** When the
   reclaimer reads an active epoch value `E`, it knows the thread is in a
   critical section that started at epoch `E`. The reclaimer will not reclaim
   entries with `retireEpoch >= E`. The visibility of this slot value is
   guaranteed by the hardware-level argument above (the `fullFence()` in
   `enter()` ensures the store is globally visible before the entering thread
   reads shared data). See "JMM formal analysis" above for the detailed
   argument.

**Defensive guard against `globalEpoch` corruption:** The `min` variable is
initialized to `currentEpoch`, and slot values can only lower it (active
slots hold valid epochs < `INACTIVE`). The only way `min` could reach
`INACTIVE` is if `currentEpoch` itself equals `INACTIVE` — meaning
`globalEpoch` itself is `INACTIVE` — which should never happen. However, as a
defensive measure, `computeSafeEpoch` explicitly checks for this corruption
and returns `-1` (reclaim nothing) rather than allowing `Long.MAX_VALUE - 1`
to propagate as a safe epoch, which would catastrophically reclaim all
retired entries.

```java
long computeSafeEpoch() {
  long currentEpoch = (long) GLOBAL_EPOCH_HANDLE.getOpaque(this);
  // Defensive check: globalEpoch must never be INACTIVE. If it is,
  // something has gone badly wrong — refuse to reclaim anything.
  if (currentEpoch == INACTIVE) {
    return -1;
  }
  long min = currentEpoch;
  // Read the volatile slots reference once. If a concurrent grow() swaps
  // the array, we scan the snapshot we captured. Any newly added slots
  // hold a value equal to the current globalEpoch (guaranteed by cache
  // coherence on real hardware — see "JMM formal analysis" above), so
  // missing them does not change the computed minimum (min is initialized
  // to currentEpoch). This is neutral, not conservative or aggressive.
  long[] slotsSnapshot = slots;
  WeakReference<Thread>[] ownersSnapshot = slotOwners;
  // Iterate only the padded slot positions (stride = SLOT_STRIDE).
  for (int i = 0; i < slotsSnapshot.length; i += SLOT_STRIDE) {
    long slotValue = (long) SLOTS_HANDLE.getAcquire(slotsSnapshot, i);
    if (slotValue == INACTIVE) {
      // Slot is inactive — check if the owning thread is dead.
      // If the WeakReference is cleared, the thread can never call
      // enter() again, so this slot can be safely reclaimed.
      // See "Scan-based slot reclamation" below for the full argument.
      int logicalIndex = i / SLOT_STRIDE;
      if (logicalIndex < ownersSnapshot.length
          && ownersSnapshot[logicalIndex] != null
          && ownersSnapshot[logicalIndex].get() == null) {
        reclaimSlot(logicalIndex);
      }
      // INACTIVE does not affect min — skip.
    } else if (slotValue < min) {
      min = slotValue;
    }
  }
  // min is the oldest active epoch. Entries retired strictly before min
  // are safe to reclaim. If all slots are INACTIVE, min == currentEpoch
  // (since INACTIVE > any valid epoch), so safeEpoch == currentEpoch - 1:
  // entries retired before the current epoch can be reclaimed.
  return min - 1;
}

/**
 * Reclaims a slot whose owning thread is dead. Uses a CAS on
 * slotReleased to prevent double-free with the Cleaner.
 * Called from computeSafeEpoch() scans, which may run under
 * evictionLock (from reclaimRetired() or assistReclamation())
 * or outside it (from waitForSafeEpoch()). In either case,
 * this method only requires growLock for correctness.
 *
 * Acquires growLock to ensure the CAS targets the current
 * AtomicIntegerArray. Without this, a TOCTOU race exists: the
 * volatile read of slotReleased could return the old array, then
 * grow() could copy the old value (0) to a new array and publish it
 * before the CAS executes. The CAS would succeed on the dead old
 * array while the new array retains the stale 0 — allowing a second
 * CAS (from the Cleaner or Cleanable.clean()) to also succeed and
 * offer the same index to the free list twice. Holding growLock makes
 * the read + CAS atomic w.r.t. grow()'s copy-and-publish sequence.
 *
 * TOCTOU guard — re-check after CAS: The caller
 * (computeSafeEpoch()) reads slotOwners[i] with a plain array
 * read OUTSIDE growLock and decides the owning thread is dead
 * (WeakReference cleared). Between that read and the CAS inside
 * this method, a new thread may have taken the slot from the free
 * list and reset slotReleased to 0 (volatile write / release).
 * On weakly-ordered architectures (ARM, RISC-V), the caller's
 * earlier plain read of slotOwners[i] may observe a stale value
 * (e.g., the previous dead thread's cleared WeakReference) even
 * though the new thread has already written a live WeakReference.
 * The CAS sees the new thread's 0 and succeeds — stealing the
 * slot while the new thread is actively using it.
 *
 * The fix: after the CAS succeeds, re-read slotOwners from the
 * current volatile field (not the caller's snapshot) and verify
 * the owning thread is still dead. The CAS on slotReleased has
 * acquire semantics; if it read the new thread's volatile write
 * of 0, the release-acquire pairing guarantees the new thread's
 * prior plain write to slotOwners[i] is visible. If the re-check
 * finds a live thread, the CAS is undone (slotReleased reset to
 * 0) and the slot is NOT returned to the free list.
 *
 * Correctness argument for the re-check: slotReleased[i] can
 * only transition from 1 to 0 via slotReleased.set(i, 0) in the
 * slot-reuse initialization sequence (line "slotReleased.set(
 * slotIndex, 0)"). That volatile write is preceded (in the new
 * thread's program order) by slotOwners[i] = new WeakReference<>
 * (Thread.currentThread()). The CAS's acquire read of 0 therefore
 * establishes a happens-before from the new thread's set(0),
 * which transitively includes the slotOwners write. The re-check
 * reading slotOwners[logicalIndex] after the CAS is guaranteed
 * to see the new thread's WeakReference (or a later value).
 *
 * Lock ordering: when evictionLock is held (reclaimRetired() /
 * assistReclamation() path), the nesting is evictionLock → growLock.
 * When called from waitForSafeEpoch() (outside evictionLock), only
 * growLock is acquired. grow() acquires growLock only, releaseSlot()
 * acquires growLock only — no cycles in either case.
 *
 * Cost: one uncontended lock acquisition per dead-thread slot during
 * scan — cold path only.
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
      WeakReference<Thread>[] currentOwners = slotOwners;  // volatile read
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
```

Example: global epoch is 10, three threads have slots `[7, INACTIVE, 9]`.
`min = 7`, so `safeEpoch = 6`. Entries retired at epoch 6 or earlier can be
reclaimed. The thread in slot 0 entered at epoch 7 and may still hold
references to entries that were in the CHM at that time, so epoch 7+ entries
must wait.

### Slot Assignment

Each thread is assigned a dedicated slot index the first time it enters a critical
section. The index is cached in a `ThreadLocal<SlotHandle>` for subsequent accesses.

**Allocation:** A `ConcurrentLinkedQueue<Integer>` free list holds available slot
indices. A thread first tries `freeList.poll()`; if empty, it allocates a new index
via `AtomicInteger.getAndIncrement()`. If the index exceeds the current array
capacity, the array is grown (see below).

**Initial size:** `availableProcessors() * 4` logical slots, which translates
to an array of `availableProcessors() * 4 * SLOT_STRIDE` longs (each logical
slot occupies `SLOT_STRIDE` = 16 array elements for cache-line padding). This
is generous for typical workloads; growth is rare in practice and only occurs
under high thread counts.

**Dynamic growth:** When a thread needs a slot index beyond the current array
capacity, the array is grown under a dedicated `growLock` (a simple
`ReentrantLock`, separate from `evictionLock`). `growLock` also serializes
against `reclaimSlot()` and `releaseSlot()`, which acquire it to ensure
their `slotReleased` CAS targets the current `AtomicIntegerArray` rather
than a stale copy being replaced by `grow()`. Lock ordering:
`evictionLock` → `growLock` (from `reclaimSlot()`); `growLock` only (from
`grow()` and `releaseSlot()`). No cycles.

```java
private void grow(int requiredIndex) {
  growLock.lock();
  try {
    long[] current = slots;
    int requiredPaddedIndex = requiredIndex * SLOT_STRIDE;
    if (requiredPaddedIndex < current.length) {
      return;  // another thread already grew
    }
    // Grow in terms of logical slots, then multiply by stride for the array.
    int currentSlotCount = current.length / SLOT_STRIDE;
    int newSlotCount = Math.max(currentSlotCount * 2, requiredIndex + 1);
    int newSize = newSlotCount * SLOT_STRIDE;
    long[] newSlots = new long[newSize];
    // Copy existing slots. All new slots are 0, but must be INACTIVE.
    System.arraycopy(current, 0, newSlots, 0, current.length);
    // Fill only the padded slot positions with INACTIVE; padding longs
    // remain 0 (never read).
    for (int i = current.length; i < newSize; i += SLOT_STRIDE) {
      newSlots[i] = INACTIVE;
    }
    // Grow slotOwners and slotReleased in lockstep.
    @SuppressWarnings("unchecked")
    WeakReference<Thread>[] newOwners = new WeakReference[newSlotCount];
    WeakReference<Thread>[] currentOwners = slotOwners;
    if (currentOwners != null) {
      System.arraycopy(currentOwners, 0, newOwners, 0,
          Math.min(currentOwners.length, newSlotCount));
    }
    slotOwners = newOwners;  // volatile write

    // Grow slotCleanables in lockstep.
    Cleaner.Cleanable[] currentCleanables = slotCleanables;
    Cleaner.Cleanable[] newCleanables = new Cleaner.Cleanable[newSlotCount];
    if (currentCleanables != null) {
      System.arraycopy(currentCleanables, 0, newCleanables, 0,
          Math.min(currentCleanables.length, newSlotCount));
    }
    slotCleanables = newCleanables;  // volatile write

    // AtomicIntegerArray cannot be resized in place — replace it.
    // Copy existing released flags; new slots default to 0 (not released).
    AtomicIntegerArray currentReleased = slotReleased;
    AtomicIntegerArray newReleased = new AtomicIntegerArray(newSlotCount);
    if (currentReleased != null) {
      for (int j = 0; j < currentReleased.length(); j++) {
        newReleased.set(j, currentReleased.get(j));
      }
    }
    slotReleased = newReleased;  // volatile write

    // IMPORTANT: `slots` must be published LAST, after all auxiliary arrays.
    // This guarantees that a reader (computeSafeEpoch) that sees the new
    // `slots` array also sees the new `slotOwners`, `slotCleanables`, and
    // `slotReleased`. The JMM provides this via transitivity: slotOwners
    // volatile write hb→ slots volatile write (program order within grow()),
    // and if the reader's slots volatile read sees the new array, it forms
    // a hb chain: slotOwners-write hb→ slots-write hb→ reader's slots-read
    // hb→ reader's slotOwners-read (program order). By transitivity, the
    // reader's slotOwners-read sees the new array (or a later value).
    //
    // The converse case (reader sees old `slots` but new `slotOwners`) is
    // also safe: the reader iterates only the old (smaller) `slots` array,
    // so new-but-larger `slotOwners` entries beyond the old array's range
    // are never accessed. For indices within the old array, the new
    // `slotOwners` was populated by System.arraycopy from the old one,
    // so existing values are preserved.
    //
    // Volatile write publishes the new array. Readers (computeSafeEpoch)
    // read the volatile `slots` reference once and scan that snapshot.
    // A reader that sees the old array may miss newly added slots. Those
    // slots hold a value equal to the current globalEpoch (guaranteed by
    // cache coherence on real hardware — see "JMM formal analysis"), so
    // missing them does not change the computed minimum (which is
    // initialized to currentEpoch). This is neutral: neither delays nor
    // accelerates reclamation. It is NOT a safety violation.
    //
    // Safety w.r.t. concurrent enter()/exit():
    // - enter() uses a retry loop that re-reads `slots` after the fence.
    //   If grow() replaced the array, enter() detects the change and
    //   retries on the new array — see "Grow-safety retry loop" above.
    // - exit() may write INACTIVE to the old array if it races with
    //   grow(). This is a liveness issue only (delays reclamation),
    //   not a safety violation — see "exit() and grow()" note above.
    slots = newSlots;
  } finally {
    growLock.unlock();
  }
}
```

This follows the `LongAdder` / `Striped64` pattern: start small, grow on
demand, never shrink. Growth is O(thread count) in the worst case, but
slot reuse via `Cleaner` (platform threads) and scan-based reclamation
(virtual threads — see "Scan-based slot reclamation" below) means the array
rarely grows beyond the high-water mark of concurrently active threads.

**Slot reuse:** When a thread dies, its slot must be returned to the free list.
We use `SlotHandle` — a small helper object held by the `ThreadLocal` — combined
with `java.lang.ref.Cleaner`:

```java
private static final Cleaner CLEANER = Cleaner.create();

class SlotHandle {
  final int slotIndex;
  boolean active;  // thread-local, no synchronization needed; used for reentrancy check
  // registered with CLEANER on allocation
}

// On thread-local init:
// Cancel any pending Cleaner action from the previous owner of this slot.
// Without this, the dead thread's Cleaner could fire after slotReleased is
// reset to 0 below, CAS 0→1 on the freshly reset flag, and return the slot
// to the free list while the new thread is actively using it — a
// use-after-free. Cleanable.clean() is idempotent: if the Cleaner already
// ran, or scan-based reclamation already reclaimed the slot, the internal
// action is a no-op (the slotReleased CAS fails). In all cases, clean()
// deregisters the Cleanable from the Cleaner's reference queue, freeing
// memory and ensuring the old action can never fire after this point.
Cleaner.Cleanable oldCleanable = slotCleanables[slotIndex];
if (oldCleanable != null) {
    oldCleanable.clean();  // deregisters the old action
}
var handle = new SlotHandle(slotIndex);
slotOwners[slotIndex] = new WeakReference<>(Thread.currentThread());
slotReleased.set(slotIndex, 0);  // mark as not-yet-released
Cleaner.Cleanable cleanable = CLEANER.register(handle,
    () -> epochTable.releaseSlot(slotIndex));
slotCleanables[slotIndex] = cleanable;
threadLocal.set(handle);
```

**Slot reuse writes and concurrent `grow()`:** The plain writes to
`slotOwners[slotIndex]` and `slotCleanables[slotIndex]` during slot reuse
are NOT protected by `growLock`. If `grow()` runs concurrently, it could
copy the old array (containing the stale value) into the new array before
the plain write lands. The plain write then targets the old array, and the
new array retains the stale value.

This is benign for the same reason as the `exit()` vs. `grow()` race: the
stale value in the new array is conservative, not dangerous. Specifically:

- **`slotOwners`**: The new array retains the old `WeakReference` (or
  `null`). On the next `computeSafeEpoch()` scan, if the slot is `INACTIVE`,
  the scan checks the stale `slotOwners` entry. If it looks like a dead
  thread, `reclaimSlot()` fires — but the re-check after the CAS (see
  "TOCTOU guard" above) reads the current `slotOwners` volatile field and
  sees the live thread (the `slotReleased.set(0)` volatile write acts as a
  release barrier for the `slotOwners` plain write). The CAS is undone.
  On the thread's next `enter()`, the `fullFence()` makes all prior writes
  globally visible, so subsequent scans see the correct `slotOwners` value.

- **`slotCleanables`**: The new array retains the old `Cleanable` reference.
  If the old `Cleanable` is `null` (slot was freshly allocated, never used)
  or has already been cleaned (via `oldCleanable.clean()` above), this is
  harmless — the action is a no-op. On the next slot reuse cycle, the new
  owner reads `slotCleanables[slotIndex]` and calls `clean()` on whatever
  reference it finds, which is idempotent.

In both cases, the stale value self-corrects on the thread's next
`enter()`/`exit()` cycle or slot reuse. No safety violation occurs.

```java
// In EpochTable:
void releaseSlot(int slotIndex) {
  // Acquire growLock for the same reason as reclaimSlot(): the volatile
  // read of slotReleased and the CAS must be atomic w.r.t. grow()'s
  // copy-and-publish of the AtomicIntegerArray. Without this, the CAS
  // could land on a stale (old) array while the new array retains 0,
  // enabling a second reclamation of the same slot.
  //
  // Lock ordering: releaseSlot() acquires growLock only (called from
  // Cleaner thread or Cleanable.clean(), no other lock held). No
  // cycles with evictionLock → growLock in reclaimSlot().
  growLock.lock();
  try {
    // CAS guards against double-free: if scan-based reclamation in
    // computeSafeEpoch() already reclaimed this slot (detected the owning
    // thread's WeakReference as cleared), the CAS fails and we skip.
    // Only the first path to set released = 1 performs the actual release.
    if (!slotReleased.compareAndSet(slotIndex, 0, 1)) {
      return;  // already reclaimed by scan-based reclamation
    }
    // Read the current volatile slots reference — never a stale array
    // captured at registration time. If grow() replaced the array since
    // the slot was allocated, this still writes to the live array.
    SLOTS_HANDLE.setRelease(slots, slotIndex * SLOT_STRIDE, INACTIVE);
    slotOwners[slotIndex] = null;  // help GC
    freeList.offer(slotIndex);
  } finally {
    growLock.unlock();
  }
}
```

**Why `setRelease` (not `setOpaque`) in `releaseSlot()`:** This method writes
`INACTIVE` to mark a slot as no longer active — the same logical operation as
`exit()`, which uses `setRelease` with an explicit justification: all memory
accesses performed by the owning thread during its last critical section must
be ordered **before** the slot is marked `INACTIVE`, so that the reclaimer
cannot free memory the thread was still reading.

The same argument applies to `releaseSlot()`. Although the owning thread has
already died (the Cleaner fires after the ThreadLocal becomes unreachable),
the thread's last `exit()` call wrote `INACTIVE` with release semantics —
so by the time the Cleaner runs, all critical-section accesses are long
completed. In this narrow sense, `setOpaque` would be technically safe: the
prior `exit()` already established the necessary ordering.

However, `setRelease` is used here for two reasons:

1. **Consistency:** `releaseSlot()` and `exit()` perform the same logical
   operation (mark slot inactive). Using different access modes for the same
   operation invites confusion and makes it harder to reason about correctness
   during review. A reader seeing `setOpaque` in `releaseSlot()` would
   naturally ask "why is this weaker than `exit()`?" — and the answer
   ("because the thread is already dead") requires non-local reasoning about
   the Cleaner lifecycle. Using the same mode eliminates this question.

2. **Defense in depth:** If a future code change introduces a path where
   `releaseSlot()` is called while the thread is still alive (e.g., an
   explicit `unregister()` method for thread pool reuse), `setOpaque` would
   be a latent bug — the slot could be marked `INACTIVE` before the thread's
   in-progress critical section completes, making it invisible to the
   reclaimer. `setRelease` is safe in all scenarios.

The cost difference is zero on x86 (both compile to a plain store under TSO)
and negligible on ARM (`stlr` vs `str` — one instruction, no barrier).

The cleaner action calls a method on `EpochTable` rather than closing over
the `slots` array reference directly. This avoids a subtle bug: if `grow()`
replaces the `slots` array between slot allocation and cleaner execution,
a captured reference would point to the stale (old) array, and the
`INACTIVE` write would be lost. By reading the volatile `slots` field
inside `releaseSlot()`, the write always targets the current live array.
Additionally, `releaseSlot()` holds `growLock` while reading `slotReleased`
and performing the CAS, ensuring the CAS targets the current
`AtomicIntegerArray` even if `grow()` is replacing it concurrently.

When the thread dies, its `ThreadLocal` value becomes unreachable, the `Cleaner`
action fires, the slot is marked `INACTIVE`, and its index is returned to the free
list. This is reliable on JDK 21+ (no finalization dependency) and requires no
polling or manual cleanup.

**Scan-based slot reclamation (virtual thread support):**

The Cleaner-based approach works well for platform threads (long-lived, few
slots), but is insufficient for virtual threads. With many short-lived virtual
threads, each one allocates a slot on its first `enter()`. The slot is cached
in the `ThreadLocal` and released by the Cleaner when the `SlotHandle` becomes
unreachable. However, the Cleaner depends on GC collecting the `SlotHandle`
object, which may lag significantly behind virtual thread creation rate —
especially under large heaps or infrequent GC. This causes two problems:

1. **Array growth**: Slots accumulate faster than the Cleaner reclaims them,
   causing the array to grow to thousands of entries (each padded to 128
   bytes).
2. **Scan cost**: `computeSafeEpoch()` scans all allocated slots. With 100K
   slots at 128-byte stride, the scan reads 12.8 MB of data — potentially
   10ms per reclamation pass if the data exceeds L3 cache.

To address this, `computeSafeEpoch()` **eagerly reclaims slots for dead
threads** during its scan, using a `WeakReference<Thread>` per slot. Each
slot stores a `WeakReference` to its owning `Thread` (set during slot
allocation — see code above). During the scan, when a slot is `INACTIVE`,
the scan checks `slotOwners[i].get()`. If the `WeakReference` is cleared
(returns `null`), the owning thread is dead and can never call `enter()`
again — the slot is a reclamation candidate.

However, the scan's `slotOwners[i]` read is a **plain array read outside
`growLock`**, so it can observe a stale value on weakly-ordered architectures
(ARM, RISC-V). Between the scan's read and the `slotReleased` CAS inside
`reclaimSlot()`, a new thread may have taken the slot from the free list,
written a live `WeakReference` to `slotOwners[i]` (plain write), and reset
`slotReleased` to 0 (volatile write / release). The scan would still see
the old dead thread's cleared `WeakReference`, and the CAS would succeed on
the new thread's 0 — stealing the slot.

To prevent this, `reclaimSlot()` performs a **re-check of `slotOwners`
after the CAS succeeds**. The CAS on `slotReleased` has acquire semantics;
if it read the new thread's volatile write of 0, the release-acquire
pairing guarantees the new thread's prior plain write to `slotOwners[i]`
is visible. If the re-check finds a live thread, the CAS is undone
(`slotReleased` reset to 0) and the slot is not returned to the free list.
See the `reclaimSlot()` Javadoc for the full correctness argument.

**Slot theft and double-free prevention:** Four mechanisms work together
to prevent a slot from being returned to the free list while a live thread
owns it, and to prevent the same slot from being returned more than once:

1. **`slotReleased` CAS guard (Cleaner vs. scan):** Both the Cleaner
   (`releaseSlot()`) and scan-based reclamation (`reclaimSlot()`) do
   `compareAndSet(slotIndex, 0, 1)` — only the first one to succeed
   performs the actual reclamation (`freeList.offer()`). The loser is a
   no-op. See the `releaseSlot()` and `reclaimSlot()` code above.

2. **Re-check after CAS in `reclaimSlot()` (scan vs. new owner):**
   After the CAS succeeds, `reclaimSlot()` re-reads the current
   `slotOwners` volatile field and checks whether the owning thread is
   still dead. The CAS has acquire semantics: if it read the new
   thread's volatile write of `slotReleased = 0`, the release-acquire
   pairing guarantees the new thread's prior plain write to
   `slotOwners[i]` is visible. If the re-check finds a live thread, the
   CAS is undone (`slotReleased` reset to 0) and the slot is NOT
   returned to the free list. This closes a TOCTOU window between the
   caller's plain read of `slotOwners[i]` (outside `growLock`) and the
   CAS (inside `growLock`), which is exploitable on weakly-ordered
   architectures (ARM, RISC-V) where the plain read can observe a stale
   cleared `WeakReference` even though a new live thread has already
   taken the slot. See the `reclaimSlot()` Javadoc for the full
   correctness argument.

3. **`Cleanable.clean()` on slot reuse (old Cleaner vs. new owner):**
   When a new thread takes a slot from the free list, it calls
   `oldCleanable.clean()` **before** resetting `slotReleased` to 0. This
   deregisters the old Cleaner action, ensuring it can never fire after
   the reset. Without this step, the following race would be possible:
   scan-based reclamation sets `slotReleased = 1` and returns the slot
   to the free list; a new thread resets `slotReleased = 0`; then the
   dead thread's Cleaner fires, CAS `0 → 1` succeeds, and the slot is
   returned to the free list a second time — while the new thread is
   actively using it. Cancelling the old `Cleanable` before the reset
   closes this window. `Cleanable.clean()` is idempotent: if the Cleaner
   already ran, or scan reclaimed the slot, the internal action
   (`releaseSlot()`) finds `slotReleased = 1` and the CAS fails — the
   call is a harmless no-op that only deregisters the `Cleanable`.

4. **Ordering invariant:** On reuse, `oldCleanable.clean()` runs first,
   then `slotReleased.set(slotIndex, 0)`, then `CLEANER.register()`
   stores the new `Cleanable`. This ensures at most one live `Cleanable`
   per slot at any time.

**Cost analysis:**

| Dimension | Cost |
|---|---|
| `enter()` / `exit()` hot path | **Zero** — no changes to the hot path |
| `computeSafeEpoch()` scan | +1 `WeakReference.get()` per INACTIVE slot (plain field read) |
| Memory per slot | +~32 bytes (`WeakReference` object) + 1 int in `slotReleased` + 1 ref in `slotCleanables` |
| Slot allocation (cold path) | +1 `WeakReference` constructor + `slotReleased.set()` + `oldCleanable.clean()` (reuse only) |

The scan-based reclamation detects dead threads on the next
`computeSafeEpoch()` scan after GC clears the `WeakReference`. Under
sustained load (frequent drain cycles), this is within one drain cycle
after GC — much faster than waiting for the Cleaner thread to wake up and
process its reference queue.

**GC dependency:** Both the Cleaner and the `WeakReference` depend on GC
to detect thread death. With infrequent GC (large heap, low allocation
rate), dead threads' `WeakReference`s remain live across multiple scans,
and slot accumulation continues until the next GC. This is acceptable in
practice: sustained virtual thread creation implies sustained allocation,
which implies regular GC. The pathological case (burst of virtual threads
followed by silence) produces a one-time slot accumulation that clears on
the next GC cycle.

**No reentrancy — enforced by runtime check:** Nested component operations are
prohibited by design. Each `calculateInsideComponentOperation` /
`executeInsideComponentOperation` is a leaf-level operation that does not
call back into the `AtomicOperationsManager` to start another component
operation. If this invariant were violated, the inner `enter()` would
overwrite the slot with a newer epoch, and the inner `exit()` would set the
slot to `INACTIVE` while the outer critical section is still active — a
use-after-free bug.

To catch violations unconditionally (including production), `SlotHandle`
tracks an `active` flag (plain `boolean`, thread-local — no synchronization
needed):

- **`enter()`**: `if (handle.active) throw new IllegalStateException("EBR critical section is not reentrant")`;
  then set `handle.active = true`, write epoch to slot, execute fence
  (with grow-safety retry loop — see `enter()` description above).
- **`exit()`**: `if (!handle.active) throw new IllegalStateException("EBR exit without matching enter")`;
  set `handle.active = false`, write `INACTIVE` (release).

The cost is a single field read on a thread-local object per `enter()` /
`exit()` call — negligible. Unlike an assertion, this check is **never
disabled**, so a reentrancy bug in production immediately throws rather than
silently causing a use-after-free.

### Integration Points

#### 1. Critical Section Boundaries

The critical section for EBR is the scope during which a thread holds references to
cache entries. We add `enterCriticalSection()` / `exitCriticalSection()` directly
to `LockFreeReadCache`:

```java
// In LockFreeReadCache:
// retiredListThreshold = Math.max(maxCacheSize / 100, 64);

public void enterCriticalSection() {
  if (retiredListSize >= retiredListThreshold) {
    assistReclamation();
  }
  epochTable.enter();  // asserts not already active; writes epoch + fence
}

public void exitCriticalSection() {
  epochTable.exit();   // asserts active; writes INACTIVE (release)
}

private void assistReclamation() {
  if (evictionLock.tryLock()) {
    try {
      epochTable.advanceEpoch();
      reclaimRetired();
    } finally {
      evictionLock.unlock();
    }
  }
  // If tryLock fails, another thread is already draining — skip and proceed.
}
```

These methods are also exposed on the `ReadCache` interface so that
`DurableComponent` and `AtomicOperationsManager` can call them without
coupling to the concrete implementation. The slot index is managed internally
by the thread-local `SlotHandle`, so the caller does not need to pass a stamp.

**Backpressure on enter:** Before entering the critical section, the thread
checks a `volatile int retiredListSize` against a threshold
(`max(maxCacheSize / 100, 64)` — 1% of cache size with a floor of 64
entries). The floor prevents pathological behavior with very small caches
(e.g., test configurations with `maxCacheSize = 100` would otherwise trigger
backpressure on nearly every enter with a threshold of 1). If exceeded, the
thread attempts `tryLock()` on `evictionLock` and runs a reclamation pass
(advance epoch + reclaim). If the lock is already held (another thread is
draining), it skips — no blocking, no deadlock. This is self-regulating:
threads that generate cache pressure assist with cleanup. The fast path
(below threshold) is a single volatile read — effectively free.

**Interaction with `drainBuffers()`:** `assistReclamation()` is a lightweight
reclaim-only pass — it advances the epoch and reclaims retired entries, but
does **not** drain the read/write buffers or run the eviction policy. This is
intentional: buffer draining and eviction are heavier operations that should
only run during regular drain cycles. Multiple epoch advances between drain
cycles (from assist + drain) are harmless — the epoch space is effectively
unlimited (`long`), and each advance simply makes older retired entries
reclaimable sooner. Partial reclamation by assist followed by more
reclamation in the next `drainBuffers()` is also harmless — `reclaimRetired()`
drains from the head of a FIFO deque and stops at the first non-reclaimable
entry, so multiple passes are idempotent.

**Where to call them:**

The critical section is scoped at the **component operation level** — each individual
`calculateInsideComponentOperation` / `executeInsideComponentOperation` invocation.
This keeps critical sections short (single page access or small batch of page accesses)
and ensures the retired list drains quickly.

**Important**: `AtomicOperation` (transaction) level scoping is explicitly **not** used.
Transactions can be long-lived (spanning many component operations, I/O waits, user
logic), so holding an epoch slot for the entire transaction would prevent reclamation
and lead to OOM as the retired list grows unboundedly.

`AtomicOperationsManager.calculateInsideComponentOperation` /
`executeInsideComponentOperation` wrap the lambda with enter/exit:

```java
// In AtomicOperationsManager:
public void executeInsideComponentOperation(
    final AtomicOperation atomicOperation,
    final DurableComponent component,
    final TxConsumer consumer) {
  Objects.requireNonNull(atomicOperation);
  acquireExclusiveLockTillOperationComplete(atomicOperation, component);
  readCache.enterCriticalSection();
  try {
    consumer.accept(atomicOperation);
  } finally {
    readCache.exitCriticalSection();
  }
}
```

The epoch protects all page accesses within the lambda.

**Exception-safety invariant:** `enterCriticalSection()` is intentionally placed
**outside** the `try` block. This is the standard Java resource-acquisition
pattern: if `enterCriticalSection()` throws, the `finally` block does not
execute, so `exitCriticalSection()` is not called on a slot that was never
entered. This is correct **only if** `enterCriticalSection()` is atomic with
respect to the epoch slot — it either fully completes (slot written + fence
executed) or throws without writing the slot.

This invariant holds because `enterCriticalSection()` calls
`assistReclamation()` **before** `epochTable.enter()`. If `assistReclamation()`
throws (e.g., a bug in `reclaimRetired()`), no slot write has occurred —
`epochTable.enter()` was never called. If `epochTable.enter()` itself were to
throw after writing the slot (e.g., due to a JVM-level error), the slot would
be left active indefinitely, delaying reclamation — a liveness issue but not a
safety violation. In practice, `enter()` cannot throw: the only operations are
VarHandle accesses (which do not throw for valid indices) and `fullFence()`
(which does not throw). The `grow()` call during slot allocation guarantees
the array is large enough before `enter()` runs, so
`ArrayIndexOutOfBoundsException` cannot occur.

**Maintainer rule:** Do not reorder `assistReclamation()` to run after
`epochTable.enter()` — doing so would break this invariant, because an
exception in `assistReclamation()` would leave the slot active with no
matching `exit()`.

#### 2. Entry Eviction (Deferred Reclamation)

Currently, `WTinyLFUPolicy.purgeEden()` calls `entry.freeze()` and immediately
reclaims the pointer if freeze succeeds (`state == 0`). With EBR:

1. **Retire the entry** (under `evictionLock`): Call `entry.retire()` — CAS from
   `0` to `RETIRED`. This can **fail** if a writer holds the entry (`state > 0`),
   in which case eviction moves the entry back to eden and tries another victim
   (same as today's `freeze()` failure path).
2. **Remove from CHM and LRU lists** (under `evictionLock`):
   `data.remove(pageKey, entry)` (two-arg form) makes the entry unreachable — no
   new thread can obtain it from the CHM. The two-arg variant ensures we only
   remove the exact entry we just retired: `doLoadForRead()` runs without
   `evictionLock`, so a concurrent `data.compute()` could theoretically replace
   the retired entry between `retire()` (step 1) and `data.remove()`. In
   practice this cannot happen today (compute returns the existing entry even if
   retired), but the two-arg form is a defensive safeguard against future
   changes to the compute lambda — same pattern as the current `purgeEden()`.
   If `data.remove(pageKey, entry)` returns `false` (entry was replaced), skip
   the `cacheSize` decrement and retired-list append — the replacement entry is
   a live entry owned by whoever inserted it. Decrement
   `cacheSize` (same as today's `purgeEden()` does after `data.remove()`). This
   must happen at removal time, not at deferred reclamation time, because
   `cacheSize` drives drain-cycle triggering (107% forced drain) and eviction
   decisions — a stale high count would cause excessive eviction.
3. **Record `retireEpoch`** (under `evictionLock`): Set `entry.retireEpoch` to the
   current `globalEpoch`. Add the entry to the **retired list** and increment
   `retiredListSize` (volatile write — visible to outside-lock backpressure check
   in `enterCriticalSection()`).
4. **Physical reclamation**: Performed eagerly during every drain cycle. Since drain
   cycles already run under `evictionLock` and are triggered frequently (on every
   cache miss, on write buffer activity, and forced when cache exceeds 107% capacity),
   this is the most aggressive reclamation strategy without adding a dedicated thread.

**Critical ordering invariant — retire before CHM removal:**

`retire()` (step 1) **must** succeed before `data.remove()` (step 2). If the
entry were removed from the CHM first (while `state == 0`), another thread
could immediately create a new entry for the same page via `data.compute()`.
A concurrent writer that already obtained the old entry (but hasn't called
`acquireEntry()` yet) could then race with the new entry. By retiring first
(setting `state = RETIRED`), we ensure that any concurrent `acquireEntry()` on
this entry fails, and the writer's retry loop in `doLoadForWrite()` will pick
up the new entry.

**Ordering between steps 2–4:** All four steps run atomically under
`evictionLock`. The epoch cannot advance while the lock is held (both
`advanceEpoch()` in `drainBuffers()` and `assistReclamation()` require
`evictionLock`), so no concurrent `reclaimRetired()` can run between steps.
The ordering between `data.remove()` (step 2), `retireEpoch` recording
(step 3), and retired list append (step 4) is therefore not
safety-critical — no other thread under `evictionLock` can interleave.
The listed order (`remove` → `record epoch` → `append`) is maintained as a
logical convention: `retireEpoch` conceptually marks "the epoch at which
the entry became unreachable from the CHM", so recording it after removal
keeps the semantics clean.

**Prerequisite — ConcurrentHashMap linearizability:** The retire-before-remove
invariant depends on `ConcurrentHashMap` providing linearizable operations:
once `data.remove(key)` returns, no concurrent or subsequent `data.get(key)`
call returns the removed entry. Java's `ConcurrentHashMap` guarantees this —
`get()` reads node chains via volatile references, so it observes all prior
structural modifications (including `remove()`). This is what ensures that a
thread entering an EBR critical section **after** CHM removal cannot obtain
a reference to the removed entry.

The required order within eviction is:

```
retire()  →  data.remove()  →  record retireEpoch  →  add to retired list
   (1)           (2)                 (3)                     (4)
```

All steps run under `evictionLock`, so no additional synchronization is needed
between them.

**Drain cycle integration** — every `drainBuffers()` / `emptyBuffers()` call
performs three steps in order:

```
1. Advance the global epoch (single opaque write).
2. Reclaim: scan the retired list, free all entries with retireEpoch <= safeEpoch.
3. Drain read/write buffers and run eviction policy (existing logic).
```

Advancing the epoch **before** reclamation ensures that entries retired in the
previous drain cycle become reclaimable as soon as all threads that were active
during that cycle have exited their critical sections. Reclaiming **before**
eviction ensures maximum free memory is available for the eviction decisions
that follow.

```java
// In LockFreeReadCache, called under evictionLock:
private void drainBuffers() {
  epochTable.advanceEpoch();
  reclaimRetired();
  drainWriteBuffer();
  drainReadBuffers();
}

private void emptyBuffers() {
  epochTable.advanceEpoch();
  reclaimRetired();
  emptyWriteBuffer();
  drainReadBuffers();
}

private void reclaimRetired() {
  long safeEpoch = epochTable.computeSafeEpoch();
  CacheEntry entry;
  while ((entry = retiredList.peek()) != null && entry.retireEpoch() <= safeEpoch) {
    retiredList.poll();
    retiredListSize--;       // volatile write; visible to outside-lock backpressure check
    assert retiredListSize >= 0 : "retiredListSize underflow: " + retiredListSize;
    entry.getCachePointer().decrementReadersReferrer();
    entry.clearCachePointer();
  }
}
```

The retired list is an `ArrayDeque<CacheEntry>` (not concurrent — accessed
only under `evictionLock`). No separate `RetiredEntry` wrapper is needed:
`CacheEntry` itself carries a `retireEpoch` field (plain `long`, set during
`retire()` under `evictionLock`). This intrusive approach eliminates one
wrapper allocation per eviction, reducing GC pressure under high eviction
rates. Entries are appended during eviction and drained from the head during
reclamation, so ordering is naturally FIFO by epoch.
A `volatile int retiredListSize` is maintained alongside the deque. All
mutations (increment on append, decrement on reclaim) happen under
`evictionLock`, so no CAS is needed — mutual exclusion guarantees atomicity.
Because the field is declared `volatile`, every `retiredListSize++` /
`retiredListSize--` is a volatile write, which ensures visibility to
`enterCriticalSection()` when it reads the counter **outside the lock** for
the backpressure check. A stale read is harmless (triggers reclamation one
cycle early or late).

#### 3. CacheEntry Simplification

With EBR, the `CacheEntry.state` field changes meaning:

- **No more reference counting on the read path.** The read-side `doLoad()`
  path removes `acquireEntry()` / `releaseEntry()` entirely — these CAS
  operations are unnecessary because the caller is already inside an EBR
  critical section.
- **`acquireEntry()` / `releaseEntry()` are retained on the write path**
  (`loadForWrite` / `releaseFromWrite`) and on the `silentLoadForRead` path.
  See "Write Path" and "Outside-Cache Entries" below for rationale.
- **Simplified lifecycle:**
  ```
  ALIVE    (state >= 0) →  entry is in the CHM, accessible
                            value = number of active write holders
  RETIRED  (state = -1) →  entry removed from CHM, awaiting epoch-safe reclamation
  ```
  The separate FROZEN / DEAD states are no longer needed. `retire()` replaces
  both `freeze()` and `makeDead()` as a single transition.
- `retire()` replaces `freeze()`: CAS from `0` to `-1` (same as the current
  `freeze()`). This **must** be a CAS, not a plain write — it races with
  `acquireEntry()` on the write path. If `state > 0` (a writer holds the
  entry), `retire()` fails and eviction moves the entry back to eden, exactly
  like today's `freeze()` failure path.
- `retireEpoch` field (plain `long`, initialized to `Long.MAX_VALUE`) is added
  directly to `CacheEntry` to record the epoch at which the entry was retired.
  This eliminates the need for a separate `RetiredEntry` wrapper object (see
  retired list below). The `Long.MAX_VALUE` default is a defensive choice: if
  a non-retired entry were accidentally added to the retired list,
  `reclaimRetired()` would never satisfy `retireEpoch <= safeEpoch` (since
  `safeEpoch` is at most `currentEpoch`, which is far below `Long.MAX_VALUE`),
  preventing premature reclamation. With the Java default of `0`, the entry
  would appear reclaimable immediately (`0 <= safeEpoch` for any non-negative
  safeEpoch), silently causing a use-after-free. The field is set to the
  actual epoch value during `retire()` under `evictionLock`, before the entry
  is appended to the retired list.

  **Note:** The default `retireEpoch` value (`Long.MAX_VALUE`) is numerically
  identical to the `INACTIVE` sentinel used for epoch slots. These are
  unrelated: `INACTIVE` is a slot-level marker ("thread not in critical
  section"), while `retireEpoch` is entry-level metadata ("epoch at
  retirement"). They never interact — `retireEpoch` is compared only against
  `safeEpoch` in `reclaimRetired()`, and `INACTIVE` is compared only against
  slot values in `computeSafeEpoch()`. The shared numeric value is a
  coincidence, not a design coupling.

**Why `acquireEntry()` is not needed on the read path (`doLoad()`):**

The current `doLoad()` has a `while(true)` retry loop that exists solely because
`acquireEntry()` can fail — when eviction freezes an entry between `data.get()`
and `acquireEntry()`. With EBR, this race is harmless for reads:

1. **Memory safety**: The thread is inside an EBR critical section before
   `doLoad()` is called. Even if eviction removes the entry from the CHM and
   retires it, the entry cannot be physically reclaimed until the thread exits
   its critical section.
2. **Data correctness**: A retired entry contains the same page data as a
   freshly loaded one — it's the same disk page.
3. **LRU tracking**: `afterRead()` posts the entry to the read buffer, which
   eventually calls `WTinyLFUPolicy.onAccess()`. That method currently checks
   `!cacheEntry.isDead()` and skips dead entries. With EBR, `FROZEN` and `DEAD`
   are collapsed into `RETIRED` (state = -1), so this guard must be updated to
   use `isRetired()` (or an equivalent check for `state < 0`). Posting a
   retired entry is harmless — the updated guard simply skips it.

   **Reclaimed entries in the read buffer — `clearCachePointer()` safety:**
   A retired entry may sit in the read buffer (striped ring buffer) for an
   arbitrary duration before being drained. During that time, `reclaimRetired()`
   may call `clearCachePointer()` on it, setting `dataPointer` to `null`. This
   is safe because the entire drain path — `BoundedBuffer.drainTo()` →
   `WTinyLFUPolicy.onAccess()` — only accesses `pageKey` (for frequency
   sketch increment), `state` (for the `isRetired()` / `isDead()` guard),
   and LRU link fields (`next`, `prev`, `container`). **No method in the
   drain path dereferences `dataPointer`.** Verified against
   `WTinyLFUPolicy.onAccess()`, `LRUList.contains()`, `LRUList.moveToTheTail()`,
   `LRUList.remove()`, `FrequencySketch.increment()`, and
   `BoundedBuffer.drainTo()`.

   **Maintainer rule:** `onAccess()` and the LRU list operations must **never**
   call `getCachePointer()`, `acquireExclusiveLock()`, `acquireSharedLock()`,
   or any other method that dereferences `dataPointer`. Doing so would
   introduce a `NullPointerException` on reclaimed entries still in the read
   buffer.
4. **`data.compute()` path**: The compute lambda holds CHM's per-bucket lock,
   so eviction cannot concurrently remove the same key. The returned entry is
   guaranteed to be in the CHM when compute returns.

Therefore the read-side `doLoadForRead()` eliminates the CAS and the retry
loop. The flow still has two branches — **cache hit** (`data.get()` returns
non-null → `afterRead()` → return) and **cache miss** (`data.get()` returns
null → `data.compute()` to load and insert → `afterAdd()` → return) — but
neither branch performs a CAS on `CacheEntry.state` or retries on acquire
failure. The retry loop in the current `doLoad()` exists solely because
`acquireEntry()` can fail; with EBR protecting the read path, that loop is
removed.

**Reading a retired entry between `retire()` and `data.remove()`:** Eviction's
`retire()` and `data.remove()` are sequential under `evictionLock`, but
`doLoadForRead()` runs without `evictionLock`. A reader can call `data.get()`
(or `data.compute()`) between these two steps and obtain a reference to an
entry that is already retired (state = `RETIRED`) but still present in the CHM.
This is safe for all the reasons listed above:

- **Memory safety**: EBR prevents physical reclamation (point 1).
- **Data correctness**: The retired entry's buffer contains the same page data
  (point 2). No in-place mutation can occur because a writer calling
  `acquireEntry()` on the retired entry would fail (state < 0), forcing the
  writer's retry loop to wait until eviction removes the entry and the writer
  creates or finds a fresh one.
- **LRU tracking**: `afterRead()` posts the entry to the read buffer;
  `onAccess()` checks `isRetired()` and skips it (point 3).
- **`data.compute()` path**: If `data.compute()` finds a retired entry in the
  CHM, the lambda returns it unchanged (the existing `else` branch). The reader
  uses the retired entry's valid data. Eviction's subsequent
  `data.remove(pageKey, entry)` (two-arg form) still matches and removes it.

The window between `retire()` and `data.remove()` is tiny (sequential under
`evictionLock`), so this race is infrequent. When it occurs, the reader simply
uses slightly stale cache metadata (the entry will be removed momentarily) with
no data correctness or safety impact.

**Why `acquireEntry()` IS still needed on the write path (`loadForWrite()`):**

The write path must prevent eviction from retiring an entry while a thread
holds it for modification. Without this, a dangerous CachePointer divergence
can occur:

1. Thread A: `loadForWrite()` → `doLoad()` → gets entry E with CachePointer P1
2. Thread A: acquires exclusive lock on P1, begins modifying page data
3. Eviction: `retire(E)` succeeds (no `acquireEntry` → `state == 0`),
   removes E from CHM, adds to retired list
4. Thread B: `doLoad()` → `data.get()` → null → `data.compute()` →
   `writeCache.load()` → P1 is not yet in `writeCachePages` (Thread A
   hasn't called `releaseFromWrite` yet) → `loadFileContent()` reads from
   disk → creates NEW CachePointer P2 → creates entry E' with P2 →
   E' inserted into CHM
5. Thread A: `releaseFromWrite()` → `data.compute()` → finds E' in CHM →
   `writeCache.store(P1)` → `writeCachePages` now has P1 → returns E'

Result: CHM has E' pointing to P2 (stale disk data), while `writeCachePages`
has P1 (Thread A's modified data). **Thread A's writes are invisible to
readers** who access the page through the CHM.

This divergence also causes a cascading failure: when a future Thread C
does `loadForWrite()` → gets E' (P2) → modifies P2 → `releaseFromWrite()`
→ `writeCache.store(P2)` → inside `store()`, `writeCachePages.get(pageKey)`
returns P1 ≠ P2 → `assert pagePointer.equals(dataPointer)` fails. In
production (assertions disabled), Thread C's writes are silently lost.

**Fix:** `loadForWrite()` retains `acquireEntry()` (CAS from `n` to `n+1`),
and `releaseFromWrite()` retains `releaseEntry()` (CAS from `n` to `n-1`).
Eviction's `retire()` (CAS from `0` to `-1`) fails when `state > 0`,
preventing the entry from being evicted while a writer holds it. The
`doLoad()` retry loop is retained for the write path to handle the race
where `retire()` succeeds between `data.get()` and `acquireEntry()`.

This reuses the existing mechanism without change — only the **read path**
drops `acquireEntry()` / `releaseEntry()`. The write path cost (one CAS
on enter, one CAS on exit) is negligible compared to the exclusive lock it
already acquires.

#### 4. CachePointer — No Changes Needed

The `readersWritersReferrer` and `referrersCount` fields on `CachePointer`
are **already structural-only** for the read path in the current code:

- **Readers referrer**: Incremented once in `WOWCache.load()` when the entry is
  created (structural). Decremented once during eviction / reclamation
  (structural). **Not** called per-access for in-cache reads. With EBR, the
  only change is that the structural `decrementReadersReferrer()` moves from
  immediate eviction (in `purgeEden()`) to deferred reclamation (in
  `reclaimRetired()`). The `WritersListener` callbacks (`addOnlyWriters` /
  `removeOnlyWriters`) still fire correctly because the readers and writers
  counts track structural ownership, not per-access usage.

- **Writers referrer**: Unchanged. Incremented by `WOWCache.doPutInCache()` when
  a dirty page enters the write cache, decremented by `WOWCache` flush when the
  page is written to disk and removed from `writeCachePages`. Only modified under
  the `lockManager` exclusive page lock.

- **referrersCount**: Unchanged. Tracks total structural references (1 from read
  cache via readers referrer, 1 from write cache via writers referrer per dirty
  copy). Buffer is freed when `referrersCount` reaches 0.

- **`silentLoadForRead` / outside-cache path**: Unchanged. These paths call
  `incrementReadersReferrer()` on load and `decrementReadersReferrer()` on
  release, same as today.

No `CachePointer` code changes are required for EBR. The existing structural
reference counting is correct and works as-is with deferred reclamation.

### What Changes on the Hot Path

**Clarification — pointer ref counting is already structural:**

The current `loadForRead` / `releaseFromRead` for **in-cache** entries does
**not** call `incrementReadersReferrer()` / `decrementReadersReferrer()` per
access. CachePointer's readers referrer is incremented once when the entry is
created (structural, inside `WOWCache.load()` on cache miss) and decremented
once during eviction (structural, inside `purgeEden()`). The per-access CAS
cost on the read hot path is only the 2 CAS operations on `CacheEntry.state`
(`acquireEntry` + `releaseEntry`). With EBR, these are removed.

The `readersWritersReferrer` CAS does occur on the **write path** (via
`incrementWritersReferrer()` / `decrementWritersReferrer()`) and on the
**`silentLoadForRead`** outside-cache path (via `incrementReadersReferrer()` /
`decrementReadersReferrer()`). These paths are unchanged by EBR.

| Operation | Before (per access) | After (per access) |
|---|---|---|
| Component operation enter | Nothing | Opaque write to thread-local slot + StoreLoad fence + volatile re-read of `slots` for grow-safety (once per component op) |
| Component operation exit | Nothing | Release write to thread-local slot (once per component op) |
| `loadForRead` (cache hit) | CAS on `state` (acquireEntry) | Nothing (return entry directly) |
| `loadForRead` (cache miss) | CAS on `state` (acquireEntry) + structural pointer CAS in `WOWCache.load()` | Structural pointer CAS in `WOWCache.load()` only (no entry CAS) |
| `releaseFromRead` (in-cache) | CAS on `state` (releaseEntry) | Nothing |
| `loadForWrite` | CAS on `state` (acquireEntry) | CAS on `state` (acquireEntry) — **unchanged** |
| `releaseFromWrite` | CAS on `state` (releaseEntry) | CAS on `state` (releaseEntry) — **unchanged** |
| Eviction | CAS on `state` (freeze, may fail) | CAS on `state` (retire, may fail if writers hold entry) |
| Eviction reclamation | Immediate (`decrementReadersReferrer`) | Deferred (`decrementReadersReferrer` in `reclaimRetired()`) |

**Net result for reads**: Each `loadForRead` / `releaseFromRead` on the cache
hit path goes from **2 CAS operations** (on `CacheEntry.state`) to **nothing**.
The only new per-access cost is the epoch enter/exit — **1 opaque write +
1 StoreLoad fence + 1 volatile read** (enter, for grow-safety) and
**1 release write** (exit) — but this cost is paid **once per component
operation**, which may include multiple page accesses. The fence on enter is
cheaper than the CAS it replaces (one barrier vs. a serializing
read-modify-write that bounces cache lines), the extra volatile read is a
plain load on x86 (`ldar` on ARM), and the exit is a plain store on x86.
This eliminates all cross-core cache-line invalidation on the read path.

A component operation that reads N pages (cache hits) previously paid 2N CAS
operations; with EBR it pays 1 opaque write + 1 fence + 1 volatile read +
1 release write, regardless of N.

**Write path**: unchanged — retains 1 CAS on `acquireEntry()` and 1 CAS on
`releaseEntry()`. This is necessary to prevent CachePointer divergence (see
"Write Path" section below). The cost is negligible relative to the exclusive
lock the write path already acquires.

### Handling Edge Cases

#### File Deletion / Truncation / Close

`clearFile()` currently iterates pages and calls `freeze()` on each, failing if any
entry has `state > 0`. With EBR, the `state` field still tracks write holders
(see "Write Path" above), so `retire()` can still fail if `state > 0`.

**API change:** The current `clearFile(long fileId, int filledUpTo, WriteCache
writeCache)` iterates page indices `0..filledUpTo`, doing a CHM lookup for each
page — many of which may not be in the cache (O(`filledUpTo`) lookups, sparse
hits). With EBR, `clearFile(long fileId)` scans the CHM once and filters by
file ID, visiting only entries that actually exist in the cache. The
`filledUpTo` and `writeCache` parameters are no longer needed. Callers that
pass these parameters must be updated accordingly.

**Precondition — no concurrent writers on the file's pages:** `clearFile()` is
called during file close or deletion, after the component's exclusive lock has
been acquired (via `acquireExclusiveLock()` on `DurableComponent`) and all
in-flight component operations on that file have completed. This guarantees
`state == 0` for all entries belonging to the file — no writer holds any of
them. The higher-level locking protocol (`AtomicOperationsManager` exclusive
lock on the component) enforces this invariant.

**Error handling on `retire()` failure:** If `retire()` fails (CAS returns
`false` because `state > 0`), `retireAndRemoveEntriesForFile()` **must**
throw `IllegalStateException` immediately — the entry must **not** be
removed from the CHM. Continuing past a failed retire would cause
CachePointer divergence: removing the entry from the CHM while the writer
still references it allows a concurrent reload to create a new entry with a
different CachePointer, and the writer's `releaseFromWrite()` would store the
old CachePointer into `writeCachePages`, diverging from the CHM entry. A
failed `retire()` CAS indicates a violated precondition (the exclusive lock
should have drained all writers), so throwing is the correct response — it
surfaces the bug immediately rather than allowing silent data corruption.

**Why `retire()` before CHM removal is essential in `clearFile()`:**
Concurrent readers in EBR critical sections may have obtained a reference to
an entry via `data.get()` before `clearFile()` removes it from the CHM. These
readers call `afterRead()`, which posts the entry to the read buffer. When
the read buffer is later drained (in a future drain cycle, under
`evictionLock`), `onAccess()` checks `isRetired()` and skips retired entries.
If the entry were removed from the CHM and LRU lists **without** first
setting its state to `RETIRED`, `onAccess()` would see `state == 0` and
attempt to move the entry within the LRU — but the entry is no longer in any
list, causing LRU data structure corruption. Calling `retire()` first (CAS
from `0` to `RETIRED`) ensures `onAccess()` correctly skips these entries.

The approach:

1. **Flush the current thread's read batch** (`flushCurrentThreadReadBatch()`)
   before acquiring `evictionLock` — ensures this thread's pending read-buffer
   entries are visible to the drain in step 2.
2. **Acquire `evictionLock`.**
3. **Drain read and write buffers** (`emptyBuffers()`). The write buffer may
   contain entries that have been inserted into the CHM (via `data.compute()`)
   but not yet added to the policy's LRU lists. If not drained, these entries
   would be found in the CHM during step 4 but would not be in any LRU list,
   causing `retireAndRemoveEntriesForFile()` to skip their LRU removal. While
   `WTinyLFUPolicy.onRemove()` handles missing entries gracefully (the
   `contains()` checks fall through), draining first maintains consistency
   with the current `clearFile()` behavior and ensures accurate LRU bookkeeping.
   The read buffer is drained for the same reason: pending `onAccess()` calls
   update LRU positions, and draining them before removal avoids processing
   stale entries in future drain cycles.
4. **Retire each entry** — call `retire()` (CAS from `0` to `RETIRED`) on every
   entry belonging to the file. The precondition guarantees `state == 0` for all
   of them, so the CAS always succeeds. If `retire()` fails on any entry, throw
   `IllegalStateException` — do **not** proceed with CHM removal (see "Error
   handling on `retire()` failure" above). This step is essential: a concurrent reader
   that obtained a reference to the entry (via `data.get()` before CHM removal)
   may post it to the read buffer via `afterRead()`. When the read buffer is
   drained in a future drain cycle, `onAccess()` checks `isRetired()` and skips
   the entry. Without this step, `onAccess()` would see `state == 0` and attempt
   to move the entry in the LRU lists — but the entry is no longer in any list,
   causing data structure corruption.
5. Remove entries from CHM (making them unreachable) and from LRU lists.
6. Also drain the retired list for entries belonging to this file (previously
   evicted entries that are no longer in the CHM — see "Why the retired list
   must be drained for the file" below).
7. Record the current `globalEpoch` as a **local** `retireEpoch` variable.
   This single value is used for `waitForSafeEpoch()` — it is guaranteed to
   be `>=` the per-entry `retireEpoch` of all previously retired entries
   (which were retired at earlier epochs), so waiting for this value is
   sufficient to cover all collected entries. The per-entry `retireEpoch`
   fields on `liveEntries` are **not** set because these entries bypass the
   retired list and `reclaimRetired()` entirely — they are reclaimed directly
   after `waitForSafeEpoch()`. (Their default value of `Long.MAX_VALUE` acts
   as a safety net: if a code change accidentally routes them through
   `reclaimRetired()`, they would never satisfy `retireEpoch <= safeEpoch`,
   preventing premature reclamation.)
8. Advance the epoch (`globalEpoch++`) — this is safe because `clearFile()`
   holds `evictionLock`.
9. **Release `evictionLock`**, then call `waitForSafeEpoch(retireEpoch)` which
   **spins/parks outside the lock** until `computeSafeEpoch() >= retireEpoch`.
   Releasing the lock is essential: threads that are currently inside critical
   sections need to finish and call `exit()`, and threads that enter new
   critical sections after step 8 will record an epoch > `retireEpoch` (so
   they don't block reclamation). If `waitForSafeEpoch` were called under
   `evictionLock`, it would deadlock — no drain cycle could advance the epoch
   or make progress while the lock is held.
10. Once `waitForSafeEpoch` returns, all threads that could have observed the
    removed entries have exited their critical sections. Physically reclaim
    the entries (release pointers back to `ByteBufferPool`).
11. Proceed with file close/delete.

```java
// In LockFreeReadCache:
void clearFile(long fileId) {
  flushCurrentThreadReadBatch();
  long retireEpoch;

  // Two populations of entries to reclaim:
  // 1. liveEntries — currently in the CHM, retired + removed here.
  // 2. previouslyRetired — already in the retired list from prior eviction
  //    cycles (no longer in CHM). These have retireEpoch values from earlier
  //    epochs, but waiting for the retireEpoch recorded below (current epoch)
  //    is sufficient since it is >= all of their individual retireEpochs.
  List<CacheEntry> liveEntries;
  List<CacheEntry> previouslyRetired;

  evictionLock.lock();
  try {
    // Drain read/write buffers so that all pending entries are processed
    // into the LRU lists before we retire and remove entries for this file.
    emptyBuffers();
    // Retire entries first (CAS 0 → RETIRED) so that concurrent readers
    // who obtained a reference via data.get() before CHM removal will see
    // isRetired() == true in onAccess() and skip the LRU update.
    // Precondition guarantees state == 0 for all file entries, so retire()
    // always succeeds. If any retire() CAS fails, throws IllegalStateException
    // without removing the entry from the CHM (precondition violation).
    liveEntries = retireAndRemoveEntriesForFile(fileId);  // retire + remove from CHM + LRU
    previouslyRetired = removeRetiredEntriesForFile(fileId);  // drain from retired list
    retireEpoch = epochTable.currentEpoch();
    epochTable.advanceEpoch();
  } finally {
    evictionLock.unlock();
  }

  // Spin/park outside the lock until all pre-existing critical sections exit.
  epochTable.waitForSafeEpoch(retireEpoch);

  // Now safe to reclaim — no thread can hold references to these entries.
  for (var entry : liveEntries) {
    entry.getCachePointer().decrementReadersReferrer();
    entry.clearCachePointer();
  }
  for (var entry : previouslyRetired) {
    entry.getCachePointer().decrementReadersReferrer();
    entry.clearCachePointer();
  }
}

private List<CacheEntry> retireAndRemoveEntriesForFile(long fileId) {
  // Scan the CHM for entries belonging to fileId. For each entry:
  // 1. retire() — CAS from 0 to RETIRED. Precondition guarantees state == 0,
  //    so this always succeeds. If the CAS fails, throw IllegalStateException
  //    immediately — do NOT remove the entry from the CHM (see "Error handling
  //    on retire() failure" above).
  // 2. data.remove(pageKey, entry) — two-arg form, removes from CHM.
  // 3. policy.onRemove(entry) — removes from LRU lists.
  // 4. cacheSize.decrementAndGet().
  // Called under evictionLock, so no concurrent eviction can interleave.
  var result = new ArrayList<CacheEntry>();
  for (var it = data.entrySet().iterator(); it.hasNext(); ) {
    var mapEntry = it.next();
    var entry = mapEntry.getValue();
    if (entry.getFileId() != fileId) {
      continue;
    }
    if (!entry.retire()) {
      throw new IllegalStateException(
          "Failed to retire entry " + mapEntry.getKey()
              + ": state != 0 (precondition violated — writers still active)");
    }
    it.remove();                       // remove from CHM
    policy.onRemove(entry);            // remove from LRU lists
    cacheSize.decrementAndGet();
    result.add(entry);
  }
  return result;
}

private List<CacheEntry> removeRetiredEntriesForFile(long fileId) {
  // Scan the retired list and remove entries matching fileId.
  // Called under evictionLock, so no concurrent modification.
  //
  // Note: ArrayDeque.iterator().remove() is O(n) per removal (shifts
  // elements), giving O(n*k) worst case where k is the number of matching
  // entries. This is acceptable because the retired list is bounded by the
  // backpressure threshold (1% of cache size, floor 64). If profiling shows
  // this is a bottleneck for large files, the retired list could be changed
  // to a LinkedList (O(1) iterator removal) or partitioned by file ID.
  var result = new ArrayList<CacheEntry>();
  var it = retiredList.iterator();
  while (it.hasNext()) {
    var entry = it.next();
    if (entry.getFileId() == fileId) {
      it.remove();
      retiredListSize--;
      assert retiredListSize >= 0 : "retiredListSize underflow: " + retiredListSize;
      result.add(entry);
    }
  }
  return result;
}
```

**Why the retired list must be drained for the file:**

Entries that were evicted (retired) before `clearFile()` are in the retired
list but no longer in the CHM. Without `removeRetiredEntriesForFile()`,
`removeEntriesForFile()` would miss them (it scans the CHM, where they are
already absent). These orphaned entries would:

1. **Delay buffer release:** Their direct memory buffers stay allocated until
   the next `reclaimRetired()` pass, even though the file is being
   closed/deleted and the caller expects all resources to be freed.
2. **Hold stale pointers:** The retired list would contain entries for a file
   that no longer exists, complicating resource lifetime reasoning.

Draining the retired list under `evictionLock` is safe — the retired list is
an `ArrayDeque` accessed only under `evictionLock`. The drained entries are
collected into a separate `previouslyRetired` list and reclaimed after
`waitForSafeEpoch()`, ensuring no thread can still hold references to them.

Note: This scan is O(retired list size), not O(file pages). For the typical
case where the retired list is small (bounded by the 1% backpressure
threshold), this is negligible. If needed, the retired list could be indexed
by file ID, but this optimization is unlikely to be necessary.

`waitForSafeEpoch` polls `computeSafeEpoch()` in a spin loop with
progressive backoff (`Thread.onSpinWait()` → `Thread.yield()` →
`LockSupport.parkNanos()`). A bounded timeout (default 30 seconds,
configurable via `GlobalConfiguration.DISK_CACHE_EBR_SAFE_EPOCH_TIMEOUT_MS`)
triggers an `IllegalStateException` — a thread stuck in a critical section
that long indicates a bug, not a legitimate workload. The timeout is
configurable to accommodate environments with unusually long GC pauses (e.g.,
large heaps under G1/ZGC) or test suites that run with debugging agents.

**Deadlock hazard — must not be called inside an EBR critical section:**
`waitForSafeEpoch(retireEpoch)` spins until all slots with epoch
`<= retireEpoch` become `INACTIVE`. If the calling thread itself is inside
a critical section (its slot holds epoch `<= retireEpoch`), the condition
can never be satisfied — the thread blocks waiting for itself to exit.
This invariant is enforced at runtime: `waitForSafeEpoch` reads the
calling thread's `ThreadLocal<SlotHandle>`. If the `SlotHandle` is `null`
(the thread has never entered a critical section), the check passes — no
slot was allocated, so the thread cannot be blocking itself. If the
`SlotHandle` exists and its `active` flag is `true`, the method throws
`IllegalStateException` immediately rather than spinning to timeout.
All callers (`clearFile()`, `close()`, `clear()`) satisfy this naturally:
they run after the component's exclusive lock has drained all in-flight
operations, so no epoch is active on the calling thread. **Maintainer
rule:** never call `waitForSafeEpoch` from within a component operation or
any other code path where `enterCriticalSection()` has been called.

**Progress guarantee:** `waitForSafeEpoch(retireEpoch)` does **not** require
the global epoch to advance further. It only requires that all threads which
were in a critical section at or before `retireEpoch` exit. Specifically:

- A thread that entered at epoch `<= retireEpoch` and is still active blocks
  progress — `computeSafeEpoch()` returns `<= retireEpoch - 1 < retireEpoch`.
  Once that thread calls `exit()` (release write of `INACTIVE`), its slot no
  longer contributes to the minimum.
- A thread that enters **after** `advanceEpoch()` (step 8) records epoch
  `> retireEpoch`, so it does not block `waitForSafeEpoch`.
- When all pre-existing threads have exited, all slots are either `INACTIVE`
  or hold an epoch `> retireEpoch`. `computeSafeEpoch()` returns
  `>= retireEpoch`, and `waitForSafeEpoch` completes.

No drain cycle, no epoch advancement, and no `evictionLock` acquisition is
needed during the wait — only the natural completion of in-flight critical
sections. This is why releasing `evictionLock` before the wait is essential:
it allows threads inside critical sections to finish their component
operations normally (which may involve `tryToDrainBuffers()` under
`evictionLock`), rather than blocking on the lock.

#### Write Path (loadForWrite / releaseFromWrite)

The write path **retains `acquireEntry()` / `releaseEntry()`** (one CAS
each). This is required to prevent eviction from retiring an entry while
a writer holds it — see "Why `acquireEntry()` IS still needed on the write
path" in the CacheEntry Simplification section above for the detailed race
analysis.

The write path flow with EBR:

1. `loadForWrite()` calls `doLoadForWrite()`, which includes the `while(true)`
   retry loop and `acquireEntry()` CAS — same as today's `doLoad()`.
2. `loadForWrite()` acquires exclusive lock on the CachePointer.
3. Caller modifies page data.
4. `releaseFromWrite()`:
   a. `data.compute()` → `writeCache.store()` (stores CachePointer in write cache)
   b. Releases exclusive lock on CachePointer
   c. `releaseEntry()` (CAS decrement on state)

The EBR critical section (entered at the component operation level) protects
the entry from physical reclamation. The `acquireEntry()` / `releaseEntry()`
CAS prevents eviction from removing the entry from the CHM (and thus
prevents CachePointer divergence).

The additional per-write CAS cost is negligible: the write path already
acquires an exclusive lock (ReentrantReadWriteLock write lock on the
CachePointer), which involves the same kind of CAS. One more CAS on top
of that is not measurable.

**Brief spin on retired entry:** A writer in `doLoadForWrite()` can encounter a
retired entry between eviction's `retire()` and `data.remove()` (both sequential
under `evictionLock`, but `doLoadForWrite()` runs without it). In this window,
`data.get()` returns the retired entry, `acquireEntry()` fails (state < 0), and
the retry loop iterates. On the next iteration, `data.get()` either returns the
same retired entry (if `data.remove()` hasn't executed yet) or `null` (if it
has). This is the same behavior as today's code with frozen entries — the writer
briefly spins until eviction completes the removal. The window is tiny
(sequential statements under `evictionLock`), so in practice the retry executes
at most a few iterations. This is not a livelock risk: the eviction thread makes
forward progress independently of the writer.

For dirty-page tracking (`WritersListener`), we keep the writers count
on `CachePointer` since it is only modified under the exclusive lock
(no contention).

#### Page Reload After Retirement (CachePointer Sharing)

When an entry E is retired (removed from CHM, added to the retired list) and
another thread subsequently loads the same page, the new entry E' may share
the same `CachePointer` as the retired entry E. This section explains why this
is safe in all cases.

**Dirty page (P1 is in `writeCachePages`):**

When a page has been modified, `releaseFromWrite()` calls `writeCache.store()`,
which puts the `CachePointer` P1 into `writeCachePages` and calls
`incrementWritersReferrer()` on P1. This writer referrer is the key safety
mechanism: it keeps P1's direct memory buffer alive regardless of what happens
to reader referrers.

```
Eviction                    Thread B (reloads same page)
────────                    ────────────────────────────
retire(E), remove from CHM
add E to retired list
                            doLoadForRead() → data.get() → null
                            data.compute() → writeCache.load():
                              writeCachePages.get(pageKey) → P1
                              P1.incrementReadersReferrer()  // readers: 1→2
                            create E' with P1, insert in CHM
```

E (retired list) and E' (CHM) now both point to the **same P1**. The reference
counts on P1 are:

| Event | Readers | Writers | referrersCount |
|---|---|---|---|
| E created (entry enters cache) | 1 | 0 | 1 |
| Page modified, stored in write cache | 1 | 1 | 2 |
| E retired, E' created via writeCache.load() | 2 | 1 | 3 |
| E reclaimed (`decrementReadersReferrer`) | 1 | 1 | 2 |
| Write cache flushes P1, removes from writeCachePages | 1 | 0 | 1 |
| E' eventually retired and reclaimed | 0 | 0 | 0 → buffer freed |

At no point does `referrersCount` drop to 0 prematurely. The writer referrer
from `writeCachePages` acts as a safety net: even if all reader referrers are
removed, the buffer stays alive until the write cache flushes and releases it.

Thread B's `writeCache.load()` returns P1 (the modified data), so Thread B
sees all prior writes. **No data loss.**

**Reclamation before reload (dirty page):**

If E is reclaimed before any thread reloads the page:

```
Eviction                    Reclamation              Thread B (later)
────────                    ───────────              ────────────────
retire(E), remove from CHM
add E to retired list
                            safeEpoch >= retireEpoch
                            P1.decrementReadersReferrer()
                              → readers: 1→0
                              → writers: 1 (from write cache)
                              → referrersCount: 2→1
                              → buffer NOT freed
                            E.clearCachePointer()
                                                     data.get() → null
                                                     writeCache.load() → P1
                                                     P1.incrementReadersReferrer()
                                                       → readers: 0→1
                                                       → referrersCount: 1→2
                                                     create E' with P1
```

P1's writer referrer (from `writeCachePages`) keeps `referrersCount > 0` after
reclamation removes E's reader referrer. Thread B still gets P1 with all
modifications intact. **No data loss.**

**Clean page (P1 is NOT in `writeCachePages`):**

If the page was never modified, there is no entry in `writeCachePages`. When
Thread B loads the same page, `writeCache.load()` finds nothing and calls
`loadFileContent()`, which reads the page from disk into a **new** CachePointer
P2. E (retired list) has P1, E' (CHM) has P2. These are independent pointers
with identical disk data.

When E is reclaimed, `decrementReadersReferrer()` on P1 drops readers to 0.
With no writer referrer, `referrersCount` reaches 0 and P1's buffer is freed.
This is correct — P1 is no longer needed, and P2 holds the same data. **No
data loss.**

**`clearCachePointer()` does not affect the CachePointer object:**

`clearCachePointer()` simply sets the entry's `dataPointer` field to `null`.
It does not modify the `CachePointer` object itself, so other references to
the same `CachePointer` (from E' or from `writeCachePages`) remain valid.

**Summary of safety guarantees:**

| Scenario | Pointer sharing | Buffer freed? | Data loss? |
|---|---|---|---|
| Dirty page, reload before reclamation | E and E' share P1 | No (readers=1, writers=1) | No |
| Dirty page, reclamation before reload | P1 alive via writer ref | No (referrersCount>0) | No |
| Clean page, reload before reclamation | E has P1, E' has P2 | P1 freed on reclamation | No (P2 = same disk data) |
| Clean page, reclamation before reload | P1 freed, P2 loaded from disk | P1 freed correctly | No (P2 = same disk data) |

#### Thread Starvation / Long Critical Sections

If a thread enters a critical section and stalls (GC pause, long I/O), it
holds back reclamation for entries retired after its recorded epoch. This is
the standard EBR trade-off. Mitigations:
- Critical sections are scoped to **individual component operations**, not
  entire transactions. This keeps them very short (single page access or a
  small batch within one index/cluster operation). Between component operations
  within a transaction, the thread is outside the epoch and does not block
  reclamation.
- The retired list is bounded; if it exceeds a threshold, we can yield/park
  reclamation attempts until the stalled thread exits.
- Worst case: memory is held longer but never leaked. When the thread exits
  its critical section, all deferred entries become reclaimable.

**Threads that die mid-critical-section:** If a thread terminates inside a
critical section (e.g., due to an `Error` or `OutOfMemoryError`), the slot
holds a non-`INACTIVE` epoch value, blocking reclamation. In normal operation
this cannot happen: the `exit()` call is in a `finally` block inside
`AtomicOperationsManager` (see "Exception-safety invariant" above), so even
unchecked exceptions trigger `exitCriticalSection()`. The only scenarios that
bypass `finally` are deprecated `Thread.stop()` or JVM-level crashes.

The scan-based slot reclamation in `computeSafeEpoch()` checks `slotOwners`
only for **INACTIVE** slots, so it does not detect threads that die
mid-critical-section. In this case, the Cleaner is the fallback: when the
dead thread's `SlotHandle` becomes GC-eligible, the Cleaner fires
`releaseSlot()`, which sets the slot to `INACTIVE` and returns it to the
free list. Until GC collects the `SlotHandle`, the stale epoch blocks
reclamation — a bounded liveness issue, not a safety violation.

**Interaction with `waitForSafeEpoch()` timeout:** If a thread dies
mid-critical-section via `Thread.stop()` and GC does not collect the
`SlotHandle` within the 30-second `waitForSafeEpoch` timeout, `clearFile()`
/ `close()` / `clear()` will throw `IllegalStateException`. This is
acceptable: `Thread.stop()` has been deprecated since JDK 1.2 and removed
in JDK 21 (the minimum JDK for this project). JVM-level crashes are
unrecoverable regardless. In both cases, the alternative — waiting
indefinitely for a slot that can never be cleared — is worse than failing
fast with a clear error message.

#### Outside-Cache Entries and `silentLoadForRead`

`silentLoadForRead` is used by `DiskStorage.backupPagesWithChanges()` for
backup operations. It runs **outside** any component operation, so no EBR
critical section is active. The method has **two paths**:

1. **Page already in cache** (`data.get()` returns non-null): The returned
   entry is an in-cache entry (`insideCache = true`). Since no epoch protects
   the caller, `acquireEntry()` is still needed to prevent eviction from
   reclaiming the entry's pointer while the caller reads it.

2. **Page not in cache**: `data.compute()` loads the page from disk, creates
   a new `CacheEntryImpl` with `insideCache = false`, and returns `null` from
   the compute lambda — so the entry is **not inserted into the CHM**. It's
   captured via a side channel (`updatedEntry[0]`). This outside-cache entry
   has a clear single-owner lifecycle.

Both paths call `acquireEntry()` and the corresponding `releaseFromRead()`
(which calls `releaseEntry()`). For outside-cache entries, `releaseFromRead()`
additionally calls `decrementReadersReferrer()` to release the pointer's direct
memory buffer back to `ByteBufferPool` immediately — no deferred reclamation.

**These paths retain the existing reference-counting mechanism** (`acquireEntry()`
/ `releaseEntry()` CAS on `state`, `incrementReadersReferrer()` /
`decrementReadersReferrer()` on the pointer).

**Rationale**: Requiring callers to be inside an EBR critical section would be
fragile — `silentLoadForRead` is called from backup/recovery code paths that
run outside `calculateInsideComponentOperation` /
`executeInsideComponentOperation` boundaries, where no epoch is active. Forgetting
to enter the epoch before using such an entry would silently produce a
correctness bug with no compile-time or runtime warning. Reference counting
is simple, self-contained, and already correct for this use case. The per-entry
CAS cost is acceptable because `silentLoadForRead` is infrequent compared to
the hot `doLoad` read path that EBR optimizes.

#### Cache Shutdown / Close

`LockFreeReadCache.close()` must reclaim **all** entries (both live in the CHM
and deferred in the retired list) and release their direct memory buffers back
to `ByteBufferPool`. With EBR, physical reclamation requires waiting for all
threads to exit their critical sections, same as `clearFile()`.

**Precondition — no concurrent component operations:** `close()` is called
during storage shutdown (`DiskStorage.shutdown()`), after
`OperationsFreezer.prohibitNewOperations()` has blocked all new operations and
`OperationsFreezer.waitTillAllOperationsComplete()` has drained in-flight ones.
This guarantees that no thread is inside a component operation — and therefore
no thread is inside an EBR critical section — when `close()` begins. All epoch
slots are `INACTIVE`.

The approach:

1. **Flush the current thread's read batch** (`flushCurrentThreadReadBatch()`)
   before acquiring `evictionLock`.
2. **Acquire `evictionLock`.**
3. **Drain read and write buffers** (`emptyBuffers()`). Same rationale as
   `clearFile()`: ensures all pending entries are processed into the LRU
   lists before retirement, maintaining consistent LRU bookkeeping. Under
   normal shutdown this is largely cosmetic (the cache is going away), but
   it prevents stale buffer entries from interacting with the retirement
   path in unexpected ways.
4. **Retire and remove all entries from the CHM and LRU lists.** For each
   entry, call `retire()` (CAS from `0` to `RETIRED`) before removal —
   same as `clearFile()` — to protect against any straggling read-buffer
   drain (see `clearFile()` discussion above for rationale). The
   precondition guarantees `state == 0` for all entries (no writers), so
   every `retire()` CAS succeeds. If any `retire()` CAS fails, throw
   `IllegalStateException` without removing that entry from the CHM
   (precondition violation — see `clearFile()` error handling discussion).
5. **Drain the retired list completely.** Previously retired entries (from
   prior eviction cycles) are collected into a separate list. These entries
   have `retireEpoch` values from earlier epochs; waiting for the
   `retireEpoch` recorded in step 6 (current epoch) is sufficient since it
   is ≥ all of their individual `retireEpoch` values.
6. **Record `retireEpoch`** as the current global epoch.
7. **Advance the epoch.**
8. **Release `evictionLock`.**
9. **Call `waitForSafeEpoch(retireEpoch)`.** Because the precondition
   guarantees no threads are in critical sections, all slots are `INACTIVE`
   and `computeSafeEpoch()` returns `>= retireEpoch` immediately — the wait
   completes without spinning. This step is included for **defense in depth**:
   if the precondition were violated (a thread still in a critical section),
   the wait would block until that thread exits, preventing a use-after-free
   rather than silently corrupting memory. The bounded timeout (30 seconds)
   surfaces the violation as an `IllegalStateException`.
10. **Physically reclaim all collected entries:** call
    `decrementReadersReferrer()` and `clearCachePointer()` on each.

Both `close()` and `clear()` share the same retire-wait-reclaim logic via
a private `retireAndReclaimAll(boolean resetCacheSize)` helper. The only
difference is that `clear()` resets `cacheSize` to 0 (the cache remains
operational), while `close()` does not (the cache is going away).

```java
// In LockFreeReadCache:
void close() {
  retireAndReclaimAll(false);
}

private List<CacheEntry> drainRetiredList() {
  // Called under evictionLock.
  var result = new ArrayList<CacheEntry>();
  CacheEntry entry;
  while ((entry = retiredList.poll()) != null) {
    retiredListSize--;
    assert retiredListSize >= 0 : "retiredListSize underflow: " + retiredListSize;
    result.add(entry);
  }
  return result;
}
```

**Interaction with `OperationsFreezer`:** The freezer and EBR remain
independent (see "Open Questions" item 4). The freezer's
`prohibitNewOperations()` / `waitTillAllOperationsComplete()` sequence
guarantees the EBR precondition (no active critical sections) without any
coupling between the two mechanisms. EBR's `waitForSafeEpoch` is a redundant
safety net — it adds no cost under normal shutdown (immediate return) and
catches precondition violations under abnormal conditions.

#### Cache Clear (`clear()`)

`LockFreeReadCache.clear()` removes **all** entries from the cache without
shutting it down — the cache remains usable afterwards. It is called during
test cleanup and other non-shutdown scenarios.

**Precondition — no concurrent component operations:** Same as `close()`.
`clear()` is called after the component's exclusive lock has been acquired and
all in-flight component operations have completed. This guarantees `state == 0`
for all entries (no writers) and no thread is inside an EBR critical section.

The approach is identical to `close()` (both delegate to
`retireAndReclaimAll()`), with one addition: `clear()` passes
`resetCacheSize = true`.

```java
// In LockFreeReadCache:
void clear() {
  retireAndReclaimAll(true);
}
```

**Shared helper — `retireAndReclaimAll()`:**

```java
/**
 * Retires all entries, waits for safe epoch, and physically reclaims.
 * Used by both close() and clear().
 *
 * @param resetCacheSize if true, reset cacheSize to 0 (clear() — cache
 *                       remains operational); if false, skip (close()).
 */
private void retireAndReclaimAll(boolean resetCacheSize) {
  flushCurrentThreadReadBatch();
  long retireEpoch;

  // Two populations of entries to reclaim:
  // 1. liveEntries — currently in the CHM, retired + removed here.
  // 2. previouslyRetired — already in the retired list from prior eviction
  //    cycles (no longer in CHM). These have retireEpoch values from earlier
  //    epochs, but waiting for the retireEpoch recorded below (current epoch)
  //    is sufficient since it is >= all of their individual retireEpochs.
  List<CacheEntry> liveEntries;
  List<CacheEntry> previouslyRetired;

  evictionLock.lock();
  try {
    // Drain read/write buffers so that all pending entries are processed
    // into the LRU lists before we retire and remove all entries.
    emptyBuffers();
    // Retire + remove all entries from CHM and LRU lists.
    // Throws IllegalStateException if any retire() CAS fails
    // (precondition violation).
    liveEntries = retireAndRemoveAllEntries();
    // Drain the entire retired list.
    previouslyRetired = drainRetiredList();
    retireEpoch = epochTable.currentEpoch();
    epochTable.advanceEpoch();
    if (resetCacheSize) {
      cacheSize.set(0);
    }
  } finally {
    evictionLock.unlock();
  }

  // Defense in depth: wait for any (unexpected) in-flight critical sections.
  // Under normal shutdown / clear, all slots are INACTIVE and this returns
  // immediately.
  epochTable.waitForSafeEpoch(retireEpoch);

  // Physically reclaim all entries.
  for (var entry : liveEntries) {
    entry.getCachePointer().decrementReadersReferrer();
    entry.clearCachePointer();
  }
  for (var entry : previouslyRetired) {
    entry.getCachePointer().decrementReadersReferrer();
    entry.clearCachePointer();
  }
}
```

Both `close()` and `clear()` call `flushCurrentThreadReadBatch()` +
`emptyBuffers()` before retirement to ensure all pending read/write buffer
entries are processed into the LRU lists first — for `clear()` this matches
the current implementation's behavior; for `close()` it is largely cosmetic
(the cache is going away) but prevents stale buffer entries from interacting
with the retirement path in unexpected ways.

## Implementation Phases

### Phase 1: EpochTable Infrastructure

**Files to create:**
- `core/.../storage/cache/ebr/EpochTable.java` — epoch table with padded slots

**Key design decisions:**
- Initial slot count: `Runtime.getRuntime().availableProcessors() * 4`; grows
  dynamically (2x) under `growLock` when exhausted (LongAdder/Striped64 pattern)
- Padding: 128 bytes per slot (2 cache lines, covers Intel + ARM) via stride-based
  indexing (`SLOT_STRIDE = 16`). Slot `i` lives at array index `i * SLOT_STRIDE`.
  The `long[]` array is sized as `numSlots * SLOT_STRIDE`; intervening elements
  are unused padding.
- Epoch type: `long` (overflow-safe). The epoch increments once per drain cycle.
  At the most aggressive realistic rate — one drain cycle per microsecond
  (1 million/sec) — a `long` counter overflows after `2^63 / 10^6 / 3600 /
  24 / 365 ≈ 292,471 years`. In practice, drain cycles are far less frequent
  (triggered on cache miss or write-buffer activity, typically milliseconds
  apart), so overflow is not a concern for any realistic deployment lifetime.
- All access via `VarHandle` (`MethodHandles.arrayElementVarHandle(long[].class)`
  for per-slot access, field `VarHandle` for global epoch). Access modes:
  `setOpaque` + `VarHandle.fullFence()` for enter, `setOpaque` for advanceEpoch,
  `setRelease` for exit, `getAcquire` for reclaimer slot reads.
  No `AtomicLong` / `AtomicLongArray` wrappers.
- No reentrancy: nested component operations are prohibited by design.
  `SlotHandle.active` (plain `boolean`) + unconditional `if` check in
  `enter()` / `exit()` catches violations in all environments including
  production. Cost: one thread-local field read per call — negligible.
- Virtual thread support: `WeakReference<Thread>` per slot + scan-based
  reclamation in `computeSafeEpoch()`. Dead threads' slots are eagerly
  reclaimed during scans without waiting for the Cleaner. Slot theft and
  double-free prevention via four layers: (1) `AtomicIntegerArray
  slotReleased` CAS guard between Cleaner and scan paths, (2) re-check
  of `slotOwners` after the CAS succeeds in `reclaimSlot()` — uses the
  CAS's acquire semantics to observe the new thread's `slotOwners` write,
  undoes the CAS if the thread is alive (closes the TOCTOU between the
  caller's stale plain read and the CAS on weakly-ordered architectures),
  (3) `Cleanable.clean()` on slot reuse to deregister the old Cleaner
  action before resetting `slotReleased`, (4) ordering invariant
  (`clean()` → reset → register). Zero hot-path cost — the
  `WeakReference` is created during slot allocation and checked only
  during scans under `evictionLock`.

**Tests:**
- Unit tests for EpochTable: concurrent enter/exit, safeEpoch computation correctness
- Unit test: verify that nested `enter()` throws `IllegalStateException`
- Unit test: verify scan-based slot reclamation — allocate slots, clear the
  `WeakReference` (simulating thread death), run `computeSafeEpoch()`, verify
  slots are returned to the free list
- Unit test: verify double-free prevention — both Cleaner and scan attempt to
  reclaim the same slot, verify only one succeeds (slot appears in free list
  exactly once)
- Unit test: verify `reclaimSlot()` re-check prevents slot theft — thread A
  dies, thread B takes the slot from the free list and resets `slotReleased`
  to 0 (but has not yet called `enter()`). Simulate a scan that calls
  `reclaimSlot()` with a stale dead-thread `WeakReference`. Verify: the CAS
  on `slotReleased` succeeds (reads B's 0), the re-check sees B's live
  `WeakReference`, the CAS is undone (`slotReleased` reset to 0), and the
  slot is NOT returned to the free list. Verify B can still use the slot.
- Unit test: verify `Cleanable` cancellation on slot reuse — thread A dies,
  scan reclaims its slot (sets `slotReleased = 1`, returns to free list),
  thread B takes the slot, calls `oldCleanable.clean()`, resets
  `slotReleased = 0`, registers new `Cleanable`. Verify A's Cleaner action
  never fires after the reset (the `Cleanable` was deregistered by `clean()`).
  Verify B's slot is not returned to the free list spuriously.
- Stress test: many virtual threads entering/exiting while reclamation scans
  run — verify slot count stays bounded by concurrently active threads, not
  total virtual thread count
- Stress tests: many threads entering/exiting while reclamation scans run

### Phase 2: AtomicOperationsManager Integration

**Files to modify:**
- `ReadCache.java` — add `enterCriticalSection()` / `exitCriticalSection()` to interface
  with default no-op implementations (`default void enterCriticalSection() {}`,
  `default void exitCriticalSection() {}`). This ensures that other `ReadCache`
  implementations (e.g., `DummyReadCache`, test mocks) compile without change.
  Only `LockFreeReadCache` overrides with the real EBR logic.
- `LockFreeReadCache.java` — implement `enterCriticalSection()` / `exitCriticalSection()`
  (delegates to `epochTable.enter()` / `epochTable.exit()` with backpressure check)
- `AtomicOperationsManager.java` — enter/exit epoch around component operation lambdas

**Changes:**
1. `calculateInsideComponentOperation` / `executeInsideComponentOperation`: wrap the
   lambda invocation with `readCache.enterCriticalSection()` / `exitCriticalSection()`.
   Each component operation is a short-lived critical section — a single page access
   or a small batch of page accesses within one index/cluster operation.

**Note:** `AtomicOperation` (transaction) boundaries are **not** used as critical
sections. Transactions can be arbitrarily long and holding an epoch slot for their
duration would block reclamation and cause OOM. Instead, each component operation
within a transaction gets its own short-lived critical section. Between component
operations the thread is outside the epoch, allowing reclamation to proceed.

**Why this must come before removing reference counting:** At the end of this phase,
every component operation is wrapped in enter/exit, so all cache accesses are inside
an EBR critical section. The existing reference counting still runs in parallel —
this is redundant but safe. The next phases can then remove reference counting
knowing that EBR is already protecting all entries.

### Phase 3: Remove Reference Counting from Read Path

**Files to modify:**
- `LockFreeReadCache.java` — split `doLoad()` into read/write variants, integrate
  retired list scanning into `drainBuffers()`
- `WTinyLFUPolicy.java` — change eviction from freeze-based to retire-based
- `CacheEntryImpl.java` — simplify state machine (collapse FROZEN + DEAD into RETIRED)

**Changes:**
1. **Split `doLoad()` into two variants:**
   - `doLoadForRead()`: No `acquireEntry()` CAS, no retry loop. After finding/creating
     the entry in the CHM, return it directly (the caller is already in an EBR critical
     section — see "Why `acquireEntry()` is not needed on the read path" above).
   - `doLoadForWrite()`: Retains `acquireEntry()` CAS and the `while(true)` retry loop.
     This is required to prevent CachePointer divergence — see "Why `acquireEntry()` IS
     still needed on the write path" above.
2. `releaseFromRead()`: For in-cache entries, remove `releaseEntry()` CAS. The entry
   stays alive; epoch protects it from reclamation. For outside-cache entries
   (`!insideCache()`), `releaseEntry()` and `decrementReadersReferrer()` are retained.
3. `releaseFromWrite()`: **No changes.** Retains `releaseEntry()` CAS.
4. `silentLoadForRead()`: **No changes.** Retains `acquireEntry()` / `releaseEntry()`
   because it is called from backup/recovery code outside any EBR critical section
   (see "Outside-Cache Entries and `silentLoadForRead`" above).
5. `purgeEden()`: Replace `freeze()` with `retire()` — CAS from `0` to `RETIRED`.
   If `state > 0` (a writer holds the entry), `retire()` fails and eviction moves the
   entry back to eden, same as today's `freeze()` failure path. On success, use
   `data.remove(pageKey, entry)` (two-arg form, same as today) to remove from CHM,
   decrement `cacheSize`, add to retired list with current epoch, and increment
   `retiredListSize` (volatile write). If the two-arg `data.remove()` returns
   `false` (entry was replaced — defensive guard), skip the `cacheSize` decrement
   and retired-list append. The `cacheSize` decrement happens at CHM removal time
   (not at deferred reclamation) because it drives drain-cycle triggering and
   eviction decisions. **Do not** call `decrementReadersReferrer()` here — it
   moves to `reclaimRetired()`.
6. `drainBuffers()`: Add epoch increment + `reclaimRetired()` call.
7. `reclaimRetired()`: For each reclaimable entry (retireEpoch <= safeEpoch), call
   `decrementReadersReferrer()` and `clearCachePointer()`. This is the structural
   pointer decrement that was previously done immediately in `purgeEden()` — it is
   now deferred until all threads that could have observed the entry have exited
   their critical sections.

### Phase 4: Cleanup and Validation

**Note:** The original plan had a separate "Phase 4: Simplify CachePointer" that
proposed removing per-access pointer reference counting from the read path.
Investigation of the current code revealed that `incrementReadersReferrer()` /
`decrementReadersReferrer()` are **already structural-only** for in-cache reads
(called once on entry creation in `WOWCache.load()` and once on eviction in
`purgeEden()`). There are no per-access pointer CAS operations to remove. Phase 3
moves the structural `decrementReadersReferrer()` from immediate eviction to
deferred reclamation (`reclaimRetired()`), which is the only change needed. See
"CachePointer — No Changes Needed" above. The former Phase 4 is therefore merged
into Phase 3, and this phase is renumbered.

1. **`acquireEntry()` / `releaseEntry()`**: Retain on `CacheEntry` interface — still
   needed by `loadForWrite` / `releaseFromWrite` (prevents CachePointer divergence)
   and by `silentLoadForRead` (called from backup/recovery code outside EBR critical
   sections). Verify that `doLoadForRead()` and `releaseFromRead()` (in-cache path)
   no longer call them.
2. Remove `FROZEN` and `DEAD` states — replace with single `RETIRED` state.
   `retire()` replaces both `freeze()` and `makeDead()`: CAS from `0` to `-1`.
3. Remove `freeze()` / `makeDead()` from `CacheEntry` interface — replace with
   `retire()`.
4. Update all `isDead()` / `isFrozen()` guards to use `isRetired()` (or
   equivalent `state < 0` check). In particular, `WTinyLFUPolicy.onAccess()`
   currently checks `!cacheEntry.isDead()` to skip evicted entries — this must
   be changed to `!cacheEntry.isRetired()` so that retired entries posted to
   the read buffer are correctly skipped.
5. Verify all three paths that retain `acquireEntry()`:
   - **`loadForWrite` / `releaseFromWrite`**: entry stays in CHM while writer holds
     it; `retire()` fails when `state > 0`.
   - **`silentLoadForRead` (in-cache path)**: entry found in CHM, protected by ref
     count; `retire()` fails when `state > 0`.
   - **`silentLoadForRead` (outside-cache path)**: entry not in CHM, protected by
     `acquireEntry()` + `decrementReadersReferrer()` on release.
6. Run full test suite, integration tests, and benchmarks.

### Phase 5: Comprehensive Testing

This phase defines all tests required to validate the EBR implementation —
covering the `EpochTable` in isolation, the integrated cache behavior, edge
cases, and concurrent safety. Tests are organized into four groups:

- **Group A — EpochTable unit tests** (pure `EpochTable` logic, no cache)
- **Group B — CacheEntry state machine unit tests** (simplified lifecycle)
- **Group C — LockFreeReadCache integration tests** (cache + EBR together)
- **Group D — Concurrency stress tests** (thread-safety under load)

**Test framework**: JUnit 4 with `surefire-junit47` runner (consistent with
existing `core` module tests). Concurrent tests use
`ConcurrentTestHelper` / `TestBuilder` from `test-commons` where applicable,
or raw `ExecutorService` + `Future` + `CountDownLatch` / `CyclicBarrier` for
fine-grained thread coordination. Assertions use AssertJ where available,
plain JUnit otherwise.

**Test location**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/`
- `ebr/EpochTableTest.java` — Group A
- `ebr/EpochTableStressTest.java` — Group A stress tests
- `chm/CacheEntryStateTest.java` — Group B
- `chm/LockFreeReadCacheEBRTest.java` — Group C
- `chm/LockFreeReadCacheEBRStressIntegrationTest.java` — Group D

---

#### Group A — EpochTable Unit Tests

**File: `ebr/EpochTableTest.java`**

**A1. Basic enter/exit lifecycle**
- Create `EpochTable`. Call `enter(slotIndex)`, verify slot holds the
  current global epoch (via `computeSafeEpoch()` returning `epoch - 1`).
  Call `exit(slotIndex)`, verify `computeSafeEpoch()` returns
  `currentEpoch - 1` (no active slots).

**A2. `computeSafeEpoch` — all slots inactive**
- Create `EpochTable`, advance epoch to `E`. With no threads in critical
  sections, `computeSafeEpoch()` must return `E - 1`.

**A3. `computeSafeEpoch` — single active slot**
- Thread enters at epoch `E`. Advance epoch to `E+3`. `computeSafeEpoch()`
  must return `E - 1` (the active slot holds back reclamation). Thread
  exits. `computeSafeEpoch()` must return `E+3 - 1 = E+2`.

**A4. `computeSafeEpoch` — multiple active slots at different epochs**
- Three threads enter at epochs `E`, `E+1`, `E+2` (advancing between each
  enter). `computeSafeEpoch()` must return `E - 1` (minimum active epoch).
  Thread at `E` exits. `computeSafeEpoch()` must return `E+1 - 1 = E`.
  Remaining threads exit. `computeSafeEpoch()` must jump to
  `currentEpoch - 1`.

**A5. `advanceEpoch` — monotonic increment**
- Call `advanceEpoch()` 100 times. Verify `currentEpoch()` returns 100
  (starting from 0). Verify `computeSafeEpoch()` returns 99 with no
  active slots.

**A6. `computeSafeEpoch` — defensive guard against INACTIVE globalEpoch**
- Use reflection to set `globalEpoch` to `Long.MAX_VALUE` (`INACTIVE`).
  Verify `computeSafeEpoch()` returns `-1` (refuse to reclaim).

**A7. Reentrancy — nested `enter()` throws**
- Call `enter()`, then call `enter()` again on the same thread. Verify
  `IllegalStateException` is thrown with message containing "not reentrant".
  Verify that after the exception, the original critical section is still
  active (slot still holds the epoch). Call `exit()` to clean up — must
  succeed.

**A8. Unmatched `exit()` throws**
- Call `exit()` without a preceding `enter()`. Verify
  `IllegalStateException` is thrown with message containing "without
  matching enter".

**A9. Slot allocation — first enter allocates slot**
- Create `EpochTable` on a fresh thread. Call `enter()`. Verify that
  the thread got a valid slot index (between 0 and initial capacity).
  Call `exit()`.

**A10. Slot reuse via free list**
- Allocate a slot on thread A (enter + exit). Simulate thread A death
  (trigger Cleaner or call `releaseSlot()` directly). Start thread B.
  Thread B's `enter()` should reuse thread A's slot index (poll from
  free list). Verify by checking that no array growth occurred (slot
  count unchanged).

**A11. Dynamic array growth**
- Create `EpochTable` with initial capacity `N`. Allocate `N+1` slots
  (each on a separate thread). Verify the array grew (new capacity >=
  `N+1`). Verify all existing slot values were preserved during growth
  (enter on all threads, check `computeSafeEpoch()`). Verify new slots
  default to `INACTIVE`.

**A12. `enter()` grow-safety retry loop**
- Requires white-box testing: instrument or subclass `EpochTable` to
  force `grow()` to run between the opaque write and the post-fence
  volatile read in `enter()`. Verify that `enter()` retries and the
  epoch value is correctly written to the new array. Verify
  `computeSafeEpoch()` sees the active slot.

**A13. `exit()` races with `grow()` — conservative behavior**
- Start thread T in a critical section. Force `grow()` from another
  thread. Have T call `exit()`. Verify that even if `exit()` writes to
  the old array (missed the new one), `computeSafeEpoch()` returns a
  value <= the safe epoch (conservative — delays reclamation). On T's
  next `enter()`/`exit()` cycle on the new array, verify
  `computeSafeEpoch()` returns the correct value.

**A14. Scan-based slot reclamation — dead thread detection**
- Allocate a slot on thread A (enter + exit, so slot is INACTIVE).
  Obtain the `WeakReference<Thread>` for slot A. Clear the reference
  (simulate GC collecting the thread). Call `computeSafeEpoch()`.
  Verify: (a) the slot index was returned to the free list, (b)
  `slotOwners[index]` is `null`, (c) `slotReleased[index]` is 1.

**A15. Double-free prevention — Cleaner vs. scan race**
- Allocate a slot on thread A (enter + exit). Trigger both
  `releaseSlot(index)` (Cleaner path) and `reclaimSlot(index)` (scan
  path) concurrently from two threads using a `CyclicBarrier`. Verify:
  the slot index appears in the free list exactly **once**. One of
  the two calls' CAS must fail.

**A16. `reclaimSlot()` re-check prevents slot theft**
- Thread A allocates slot S, enters, exits, then dies (WeakReference
  cleared). Thread B takes slot S from the free list, writes a new
  live `WeakReference` to `slotOwners[S]`, and resets `slotReleased`
  to 0 — but does NOT call `enter()` yet. A scan calls
  `reclaimSlot(S)` using the stale dead-thread WeakReference. Verify:
  the CAS on `slotReleased` succeeds (reads B's 0), the re-check sees
  B's live `WeakReference`, the CAS is undone (`slotReleased` reset to
  0), and the slot is NOT returned to the free list. Thread B can then
  `enter()` and `exit()` normally.

**A17. `Cleanable` cancellation on slot reuse**
- Thread A allocates slot S, enters, exits, dies. Scan reclaims slot S
  (sets `slotReleased = 1`, returns to free list). Thread B takes S,
  calls `oldCleanable.clean()` (should be no-op since scan already
  reclaimed), resets `slotReleased = 0`, registers new `Cleanable`.
  Verify: A's Cleaner action does not fire after B's reset. B's slot
  remains active and is not spuriously returned to the free list.

**A18. `waitForSafeEpoch` — immediate return when all inactive**
- Create `EpochTable`, advance epoch to `E`. No threads in critical
  sections. Call `waitForSafeEpoch(E)`. Verify it returns immediately
  (within a few milliseconds, measured wall-clock).

**A19. `waitForSafeEpoch` — blocks until thread exits**
- Thread T enters at epoch `E`. Advance epoch. Call
  `waitForSafeEpoch(E)` on another thread — it must not return. After
  a brief delay, have T call `exit()`. Verify `waitForSafeEpoch`
  returns promptly (within a few hundred milliseconds of T's exit).

**A20. `waitForSafeEpoch` — throws if called inside critical section**
- Enter a critical section on the current thread. Call
  `waitForSafeEpoch(currentEpoch)`. Verify `IllegalStateException` is
  thrown immediately (not after timeout). Exit the critical section.

**A21. `waitForSafeEpoch` — timeout fires on stuck slot**
- Configure a short timeout (e.g., 500ms). Enter a critical section
  on thread T and do not exit. Call `waitForSafeEpoch` from another
  thread. Verify `IllegalStateException` is thrown after approximately
  the configured timeout. Clean up: exit T's critical section.

**A22. `currentEpoch()` — same-thread coherence with `advanceEpoch()`**
- Call `advanceEpoch()` on thread T. Immediately call
  `currentEpoch()` on the same thread T. Verify the returned value
  equals the pre-advance value + 1. (Validates opaque same-thread
  coherence.)

---

**File: `ebr/EpochTableStressTest.java`**

**A23. Concurrent enter/exit — epoch safety invariant**
- `N` threads (e.g., 16), each performing 100K iterations of:
  `enter()` → read `currentEpoch()` → verify slot epoch <=
  `currentEpoch` → `exit()`. One dedicated thread advances the epoch
  every 100μs. No assertion failures allowed. Validates that
  `computeSafeEpoch()` never returns a value higher than the minimum
  active slot.

**A24. Concurrent enter/exit with growth**
- Start with initial capacity 4. Spawn 64 threads, each doing
  enter/exit in a tight loop (10K iterations). Verify: no
  `ArrayIndexOutOfBoundsException`, `computeSafeEpoch()` always
  returns a valid value, and all threads complete without error.

**A25. Virtual thread slot exhaustion stress**
- Spawn 10K virtual threads (via `Thread.ofVirtual()`), each doing:
  `enter()` → short sleep (1ms) → `exit()`. Concurrently, a
  reclamation thread calls `computeSafeEpoch()` in a loop (which
  triggers scan-based slot reclamation for dead virtual threads).
  After all virtual threads complete, verify: (a) the slot array did
  NOT grow to 10K entries (reclamation kept it bounded), (b) the
  final slot count is bounded by the peak concurrency (not total
  thread count), (c) no double-free (each slot index appears at most
  once in the free list — drain and count).

**A26. Concurrent `grow()` + `enter()` + `computeSafeEpoch()`**
- Three thread groups running concurrently for 5 seconds:
  - Group 1 (8 threads): `enter()` → short work → `exit()` loop
  - Group 2 (1 thread): forces `grow()` repeatedly (allocates new
    slot indices beyond current capacity)
  - Group 3 (2 threads): calls `computeSafeEpoch()` in a loop
  Verify: no exceptions, `computeSafeEpoch()` never returns a value
  that would allow reclaiming an entry visible to an active thread
  (checked via an auxiliary `AtomicLong` tracking the minimum entered
  epoch across all threads — `computeSafeEpoch()` must be < this
  minimum).

**A27. Concurrent `releaseSlot()` + `reclaimSlot()` + slot reuse**
- Spawn 100 threads that each: allocate a slot, enter, exit, then
  die. Concurrently, a reclamation thread calls `computeSafeEpoch()`
  repeatedly (triggering scan-based reclamation). Additionally, 50
  new threads start and reuse slots from the free list. After all
  threads complete, verify: no duplicate slot indices in the free
  list, no `IllegalStateException` from slot operations, total
  allocated slots <= peak concurrency + free list size.

---

#### Group B — CacheEntry State Machine Unit Tests

**File: `chm/CacheEntryStateTest.java`**

**B1. `retire()` from state 0 — succeeds**
- Create a `CacheEntryImpl` with `state == 0`. Call `retire()`. Verify
  it returns `true` and `isRetired()` returns `true`.

**B2. `retire()` from state > 0 — fails (writer holds entry)**
- Create a `CacheEntryImpl`. Call `acquireEntry()` (state → 1). Call
  `retire()`. Verify it returns `false` and `isAlive()` returns `true`.
  Call `releaseEntry()` to clean up.

**B3. `retire()` from RETIRED state — fails (already retired)**
- Create a `CacheEntryImpl`. Call `retire()` (succeeds). Call
  `retire()` again. Verify it returns `false`.

**B4. `acquireEntry()` on retired entry — fails**
- Create a `CacheEntryImpl`. Call `retire()`. Call `acquireEntry()`.
  Verify it returns `false`.

**B5. `releaseEntry()` on alive entry — decrements correctly**
- Create a `CacheEntryImpl`. Call `acquireEntry()` twice (state → 2).
  Call `releaseEntry()` (state → 1). Verify `isReleased()` is `false`.
  Call `releaseEntry()` (state → 0). Verify `isReleased()` is `true`.

**B6. `retireEpoch` default is `Long.MAX_VALUE`**
- Create a `CacheEntryImpl`. Verify `retireEpoch()` returns
  `Long.MAX_VALUE`. This ensures non-retired entries are never
  accidentally reclaimable.

**B7. `retireEpoch` set during `retire()` under eviction**
- Create a `CacheEntryImpl`. Simulate eviction: set `retireEpoch` to
  epoch 42. Verify `retireEpoch()` returns 42.

**B8. `isRetired()` replaces `isDead()` and `isFrozen()`**
- Verify `isRetired()` returns `true` iff `state < 0`. Verify
  `isAlive()` returns `true` iff `state >= 0`. The old `FROZEN` (-1)
  and `DEAD` (-2) states are collapsed into `RETIRED` (-1).

**B9. Concurrent `retire()` vs. `acquireEntry()` — mutual exclusion**
- Spawn N threads: half call `retire()`, half call `acquireEntry()`,
  all starting from a `CyclicBarrier`. Verify: exactly one of
  `retire()` or `acquireEntry()` succeeds (they are mutually exclusive
  when `state == 0`). If `retire()` wins, `acquireEntry()` returns
  `false`. If `acquireEntry()` wins first, `retire()` returns `false`
  until all holders release.

---

#### Group C — LockFreeReadCache Integration Tests

**File: `chm/LockFreeReadCacheEBRTest.java`**

These tests use a real `LockFreeReadCache` with a mocked `WriteCache`
(following the `MockedWriteCache` pattern from existing tests). Cache
size is small (64–256 entries) to force frequent eviction.

**C1. Read path — no CAS on `CacheEntry.state`**
- Enter critical section. Load a page for read (`loadForRead`). Verify
  the entry's `state` is 0 (no `acquireEntry()` was called). Release
  from read (`releaseFromRead`). Verify `state` is still 0 (no
  `releaseEntry()` was called). Exit critical section.

**C2. Write path — CAS on `CacheEntry.state` is retained**
- Enter critical section. Load a page for write (`loadForWrite`).
  Verify the entry's `state` is 1 (`acquireEntry()` was called).
  Release from write (`releaseFromWrite`). Verify `state` is 0
  (`releaseEntry()` was called). Exit critical section.

**C3. Read hit — returns entry directly without retry loop**
- Pre-populate the cache with page P. Enter critical section. Call
  `loadForRead(P)`. Verify it returns the same entry (cache hit) on
  the first call without internal retry. Exit critical section.

**C4. Read miss — triggers `data.compute()` and afterAdd**
- Enter critical section. Call `loadForRead(P)` for a page not in
  cache. Verify: (a) the entry was loaded from the write cache
  (via `writeCache.load()`), (b) the entry is now in the CHM,
  (c) `cacheSize` incremented by 1. Exit critical section.

**C5. Eviction retires entries — deferred reclamation**
- Fill the cache beyond capacity to trigger eviction. Verify: (a)
  evicted entries are in state `RETIRED`, (b) evicted entries are
  NOT in the CHM, (c) evicted entries ARE in the retired list, (d)
  `decrementReadersReferrer()` has NOT been called yet (deferred).

**C6. `reclaimRetired()` frees entries when safe**
- Retire entries at epoch `E`. Advance epoch to `E+1`. Ensure no
  threads are in critical sections. Call `reclaimRetired()` (via
  triggering a drain cycle). Verify: (a) retired entries with
  `retireEpoch <= safeEpoch` have been reclaimed (CachePointer
  cleared), (b) `retiredListSize` decremented correctly, (c) the
  direct memory buffer was returned to `ByteBufferPool`.

**C7. `reclaimRetired()` does NOT free entries with active readers**
- Thread T enters at epoch `E`. Advance epoch to `E+1`. Retire entries
  at epoch `E+1`. Advance again. `computeSafeEpoch()` returns `E - 1`
  (T holds back reclamation). Call `reclaimRetired()`. Verify: entries
  with `retireEpoch = E+1` are NOT reclaimed. Have T exit. Now
  `reclaimRetired()` should reclaim them.

**C8. Read of retired entry between `retire()` and `data.remove()`**
- This tests the window described in the plan. Use a `CyclicBarrier`
  to coordinate: eviction thread pauses between `retire()` and
  `data.remove()` (requires instrumentation or a test hook). Reader
  thread calls `data.get()` during this window, obtains the retired
  entry. Verify: (a) the entry is readable (data is valid), (b)
  `afterRead()` posts it to the read buffer, (c) when the read buffer
  is drained, `onAccess()` checks `isRetired()` and skips the LRU
  update (no LRU corruption).

**C9. Writer on retired entry — `acquireEntry()` fails, retry works**
- Set up a page in the cache. Coordinate: eviction retires the entry,
  writer calls `loadForWrite()` which calls `doLoadForWrite()`. The
  writer's `acquireEntry()` fails (state < 0). Verify: the retry loop
  iterates, and on the next iteration (after `data.remove()` completes),
  a new entry is created via `data.compute()` and the writer succeeds.

**C10. Backpressure — `assistReclamation()` on enter**
- Fill the retired list above the threshold (`max(cacheSize/100, 64)`).
  Call `enterCriticalSection()`. Verify: `assistReclamation()` was
  triggered (epoch was advanced, some retired entries were reclaimed).
  The thread proceeds to enter normally after the assist.

**C11. Backpressure — `tryLock` fails gracefully**
- Hold `evictionLock` on thread A. Fill the retired list above
  threshold. Thread B calls `enterCriticalSection()`. Verify: B's
  `assistReclamation()` calls `tryLock()` which fails (A holds the
  lock). B proceeds to enter the critical section anyway (no blocking,
  no deadlock). Release lock on A.

**C12. `clearFile()` — retires and waits for safe epoch**
- Pre-populate cache with pages from file F. Have thread T enter a
  critical section (reading a page from file F). Call `clearFile(F)` on
  another thread — this should: (a) retire all entries for F, (b)
  remove them from CHM, (c) advance epoch, (d) release `evictionLock`,
  (e) spin in `waitForSafeEpoch()` until T exits. Have T exit. Verify:
  `clearFile` returns, all entries for F are physically reclaimed
  (pointers cleared), no entries for other files were affected.

**C13. `clearFile()` — drains retired list for the file**
- Evict some entries from file F (they go to the retired list). Then
  call `clearFile(F)`. Verify: (a) previously retired entries for F
  were removed from the retired list via `removeRetiredEntriesForFile`,
  (b) they were physically reclaimed after `waitForSafeEpoch`, (c)
  entries for other files remain in the retired list untouched.

**C14. `clearFile()` — `retire()` failure throws**
- Simulate a precondition violation: have a writer hold an entry for
  file F (state > 0) while `clearFile(F)` runs. Verify:
  `retireAndRemoveEntriesForFile()` throws `IllegalStateException`
  when `retire()` CAS fails. The entry is NOT removed from the CHM.

**C15. `close()` — retires all entries, waits, reclaims**
- Pre-populate cache with entries. Ensure no threads are in critical
  sections (precondition). Call `close()`. Verify: (a) all entries
  retired and removed from CHM, (b) retired list drained, (c) all
  entries physically reclaimed (pointers cleared), (d)
  `waitForSafeEpoch` returned immediately (all slots INACTIVE).

**C16. `clear()` — retires all, resets `cacheSize`, cache remains usable**
- Pre-populate cache. Call `clear()`. Verify: (a) all entries reclaimed,
  (b) `cacheSize` is 0, (c) the cache is still usable — loading a new
  page succeeds and inserts into the CHM.

**C17. `silentLoadForRead` — retains reference counting**
- Call `silentLoadForRead(P)` for a page in the cache. Verify:
  `acquireEntry()` was called (state > 0). Call `releaseFromRead`.
  Verify: `releaseEntry()` was called (state back to 0). This confirms
  the outside-EBR path still uses reference counting.

**C18. `silentLoadForRead` — outside-cache entry lifecycle**
- Call `silentLoadForRead(P)` for a page NOT in the cache. Verify: (a)
  the returned entry has `insideCache() == false`, (b) the entry is NOT
  in the CHM, (c) `releaseFromRead` calls both `releaseEntry()` and
  `decrementReadersReferrer()`, freeing the buffer immediately.

**C19. Eviction with active writer — `retire()` fails, entry moves back**
- Pre-populate cache to capacity. Hold a write lock on entry E (via
  `loadForWrite`). Trigger eviction that selects E as a victim. Verify:
  `retire()` CAS fails (state > 0), E is moved back to eden (not
  removed from CHM), and eviction selects a different victim.

**C20. `onAccess()` skips retired entries in read buffer**
- Retire an entry E. Post E to the read buffer (via `afterRead()`).
  Trigger a drain cycle that processes the read buffer. Verify:
  `WTinyLFUPolicy.onAccess()` calls `isRetired()` on E and skips
  the LRU update — no list manipulation occurs for E.

**C21. `clearCachePointer()` safety for entries in read buffer**
- Retire entry E, reclaim E (calls `clearCachePointer()`, setting
  `dataPointer` to null). E is still in the read buffer (not yet
  drained). Trigger a drain cycle. Verify: `onAccess()` processes E
  without `NullPointerException` — it checks `isRetired()` first and
  skips, never dereferencing `dataPointer`.

**C22. Page reload after retirement — dirty page shares CachePointer**
- Modify page P via `loadForWrite` + `releaseFromWrite` (stores
  CachePointer P1 in `writeCachePages`). Evict the entry for P
  (retired, added to retired list). Reload P via `loadForRead`.
  Verify: the new entry E' shares CachePointer P1 (from
  `writeCachePages`), not a fresh disk read. Both E (retired) and E'
  (live) point to the same P1. Reclaim E. Verify:
  `referrersCount` on P1 is still > 0 (writer referrer keeps it alive).

**C23. Page reload after retirement — clean page gets new CachePointer**
- Load page P (clean, never modified). Evict the entry. Reload P.
  Verify: the new entry E' has a different CachePointer P2 (loaded
  from disk). Reclaim the old entry. Verify: P1's buffer is freed
  (referrersCount reaches 0).

**C24. `retiredListSize` invariant — never negative**
- Perform a sequence of evictions and reclamations. After each
  `reclaimRetired()` call, verify `retiredListSize >= 0`. After
  `clearFile()`, verify `retiredListSize >= 0`. After `close()`,
  verify `retiredListSize == 0`.

**C25. Epoch advancement in `drainBuffers()` — correct ordering**
- Trigger a drain cycle. Verify the ordering: (1) `advanceEpoch()`
  was called first, (2) `reclaimRetired()` ran second (using the new
  epoch for safeEpoch computation), (3) buffer draining and eviction
  ran last (new entries retired at the advanced epoch).

**C26. `emptyBuffers()` in `clearFile()` processes pending entries**
- Insert entries into the CHM via `data.compute()` but don't drain
  the write buffer (entries are in the buffer but not yet in LRU
  lists). Call `clearFile()`. Verify: `emptyBuffers()` inside
  `clearFile()` processed the pending entries into LRU lists before
  `retireAndRemoveEntriesForFile()` ran, so LRU removal succeeded
  cleanly.

---

#### Group D — Concurrency Stress Tests

**File: `chm/LockFreeReadCacheEBRStressIntegrationTest.java`**

All stress tests run for a configurable duration (default 10 seconds,
overridable via system property for CI). They use `ConcurrentTestHelper`
where appropriate.

**D1. Concurrent reads + eviction — no use-after-free**
- Cache size: 128 entries. Key space: 1024 pages (Zipfian distribution).
  N reader threads (e.g., 8) each performing 100K iterations of:
  `enterCriticalSection()` → `loadForRead(randomPage)` → read data
  from CachePointer (verify checksum/magic bytes) → `releaseFromRead`
  → `exitCriticalSection()`. Eviction runs naturally from cache
  pressure. Verify: (a) no `NullPointerException` from accessing a
  reclaimed pointer, (b) no data corruption (checksum matches), (c)
  `assertConsistency()` passes at the end.

**D2. Concurrent reads + writes + eviction**
- Cache size: 128. Key space: 512. 4 reader threads + 4 writer threads.
  Readers: same as D1. Writers: `enterCriticalSection()` →
  `loadForWrite(randomPage)` → modify data (write magic bytes) →
  `releaseFromWrite` → `exitCriticalSection()`. Verify: (a) no
  use-after-free, (b) no CachePointer divergence (writer's pointer is
  always the same as the CHM entry's pointer), (c) readers see either
  old or new data (both valid), never garbage, (d)
  `assertConsistency()` passes.

**D3. Concurrent reads + `clearFile()`**
- Cache populated with pages from files F1, F2, F3. 8 reader threads
  reading from all files. One thread calls `clearFile(F2)` midway
  through the test. Verify: (a) `clearFile` completes without error,
  (b) after `clearFile`, no entries for F2 remain in the cache, (c)
  entries for F1 and F3 are unaffected, (d) subsequent reads of F2
  pages load fresh from disk.

**D4. Concurrent reads + `close()`**
- 8 reader threads reading pages. After 2 seconds, signal shutdown:
  `OperationsFreezer.prohibitNewOperations()` +
  `waitTillAllOperationsComplete()`. Then call `close()`. Verify: (a)
  `close()` completes without error (all slots INACTIVE by the time
  `waitForSafeEpoch` is called), (b) all entries physically reclaimed.

**D5. Backpressure under high eviction rate**
- Cache size: 64 (very small). Key space: 10K (high miss rate →
  frequent eviction → large retired list). 16 reader threads. Verify:
  (a) `retiredListSize` stays bounded (the 1% threshold + backpressure
  mechanism prevents unbounded growth), (b) no OOM from accumulated
  retired entries, (c) track peak `retiredListSize` — should not
  exceed `2 * threshold` for sustained periods.

**D6. Writer prevents eviction — `retire()` failure and retry**
- Cache size: 64. 4 writer threads holding entries for extended periods
  (simulate slow writes with brief sleep). 4 reader threads. Eviction
  runs frequently due to small cache. Verify: (a) `retire()` CAS
  fails for entries held by writers (state > 0), (b) eviction moves
  these entries back to eden and selects other victims, (c) no
  deadlock between writers and eviction, (d) all writers eventually
  complete.

**D7. `waitForSafeEpoch` progress — no stuck threads**
- 8 reader threads doing enter/exit in a tight loop. One thread
  calls `clearFile(F)` which internally calls `waitForSafeEpoch`.
  Verify: `waitForSafeEpoch` completes within a reasonable time
  (well under the 30-second timeout). Measure the actual wait duration
  — it should be on the order of the component operation duration
  (microseconds to low milliseconds), not seconds.

**D8. Mixed workload — reads, writes, eviction, clearFile, backpressure**
- The "kitchen sink" test. Cache size: 128. Key space: 2048.
  - 8 reader threads (Zipfian)
  - 4 writer threads (uniform random)
  - 1 file-clear thread (clears a random file every 500ms)
  - Natural eviction + backpressure from cache pressure
  Run for 10 seconds. Verify: (a) no exceptions, (b) no
  use-after-free (data integrity checks in readers), (c)
  `assertConsistency()` after each clearFile and at the end, (d)
  `retiredListSize >= 0` throughout, (e) all threads complete.

**D9. Virtual threads — slot management under churn**
- Cache size: 256. Spawn 1000 virtual threads, each doing 100
  iterations of: `enterCriticalSection()` → `loadForRead(randomPage)`
  → `releaseFromRead` → `exitCriticalSection()`. Concurrently, drain
  cycles run (advancing epoch, triggering scan-based slot reclamation).
  After all virtual threads complete and GC runs, verify: (a) slot
  array size is bounded (not 1000 slots), (b) free list contains
  reclaimed slots, (c) no leaked slot resources, (d) cache is
  consistent.

**D10. Epoch monotonicity under concurrent advancement**
- Multiple threads call `assistReclamation()` concurrently (each
  trying `tryLock` on `evictionLock`). Track epoch values observed by
  each thread. Verify: epoch is strictly monotonically increasing
  (never decreases, never duplicates when observed under the lock).

---

#### Test Infrastructure Notes

**Verification helpers to add to `LockFreeReadCache` (test-visible):**
- `assertConsistency()` — already exists. Extend to verify: (a) no
  entries in the CHM are in `RETIRED` state (retired entries must be
  removed from CHM), (b) `retiredListSize` matches `retiredList.size()`,
  (c) `cacheSize` matches `data.size()`.
- `getRetiredListSize()` — package-private accessor for test assertions.
- `getRetiredList()` — package-private accessor returning a snapshot (copy)
  for test inspection.
- `getEpochTable()` — package-private accessor for direct `EpochTable`
  inspection in unit tests.

**Test-only hooks (package-private, annotated `@VisibleForTesting`):**
- `EpochTable.getSlotCount()` — returns current number of allocated logical
  slots.
- `EpochTable.getFreeListSize()` — returns current free list size.
- `EpochTable.getSlotValue(int logicalIndex)` — returns the raw epoch value
  in a slot (for white-box assertions).

**Write pattern for stress tests:** Each writer writes a recognizable pattern
to the page buffer — e.g., a magic header (4 bytes) + thread ID (8 bytes) +
sequence number (8 bytes) + CRC32 of the payload. Readers verify the CRC32
to detect data corruption from use-after-free or CachePointer divergence.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Delayed reclamation increases memory pressure | Backpressure on `enterCriticalSection()`: threads assist reclamation via `tryLock` when retired list exceeds `max(maxCacheSize / 100, 64)` |
| Stalled thread holds back reclamation | Critical sections are very short; monitor max retired list depth |
| Complexity of slot management | Reuse existing `AtomicOperationsTable` pattern already proven in this codebase |
| Correctness of epoch ordering | Formal argument: a thread that read an entry must have entered before the entry was retired; reclamation waits for all such threads to exit. Verified by stress tests. |
| Write path correctness | Write path retains `acquireEntry()` / `releaseEntry()` CAS to prevent CachePointer divergence. `retire()` (CAS from 0 to -1) fails when writers hold the entry, same as today's `freeze()`. EBR only removes read-path CAS. Low risk. |
| Virtual thread slot exhaustion | Scan-based reclamation in `computeSafeEpoch()` eagerly detects dead threads via `WeakReference<Thread>` and returns slots to the free list — zero hot-path cost. Bounded by concurrent active threads, not total virtual thread count. GC-dependent detection (same as Cleaner) but faster: no Cleaner-thread delay, slot is reclaimed on the next scan after GC clears the reference. `Cleanable.clean()` on slot reuse deregisters the old Cleaner action before `slotReleased` is reset, preventing the old-Cleaner-after-reset race. `reclaimSlot()` re-checks `slotOwners` after the CAS succeeds, using the CAS's acquire semantics to detect concurrent slot reuse by a live thread — prevents slot theft on weakly-ordered architectures (ARM, RISC-V). |

## Open Questions

1. ~~**Epoch advancement frequency**~~: **Decided — per drain cycle.** The epoch is
   advanced at the start of every `drainBuffers()` / `emptyBuffers()` call, which
   already fires on every cache miss, write buffer activity, and forced drain at
   107% capacity. This is aggressive enough for eager reclamation without adding
   a dedicated timer or per-operation overhead.
2. ~~**Retired list bound**~~: **Decided — `max(maxCacheSize / 100, 64)`,
   backpressure on enter.** The threshold is 1% of the cache size with a floor
   of 64 entries. The floor prevents pathological behavior with very small
   caches (e.g., test configurations). When the retired list exceeds this
   threshold, threads entering a critical section assist reclamation via
   `tryLock` on `evictionLock` + `advanceEpoch()` + `reclaimRetired()`. If the
   lock is already held, the thread skips and proceeds — no blocking, no
   deadlock risk. This is self-regulating: threads that generate cache pressure
   share the cleanup work. The fast-path cost (below threshold) is a single
   volatile read.
3. ~~**`silentLoadForRead` outside-cache path**~~: **Decided — keep reference
   counting.** Outside-cache entries retain the existing `incrementReadersReferrer()`
   / `decrementReadersReferrer()` mechanism. Requiring callers to be inside an EBR
   critical section would be fragile: these entries can be accessed outside component
   operation boundaries (recovery, schema reads, direct buffer access) where no epoch
   is active. Reference counting is simple, self-contained, and the per-entry CAS cost
   is acceptable for this infrequent path.
4. ~~**Integration with `OperationsFreezer`**~~: **Decided — remain independent.**
   The two mechanisms serve fundamentally different purposes:
   - **OperationsFreezer**: a **blocking barrier** for write operations. It can
     *prevent new operations from starting* (`freezeRequests > 0` causes
     `startOperation()` to park the calling thread) and *waits for in-flight
     operations to drain* (`operationsCount.sum() == 0`). Used during checkpoints
     and storage shutdown to guarantee no concurrent writes.
   - **EBR epoch table**: a **non-blocking reclamation guard** for the read cache.
     It never blocks threads from entering critical sections — it only defers
     memory reclamation until all readers have exited.

   The freezer's ability to *block* new operations has no analogue in EBR and is
   essential for checkpoint correctness. Conversely, EBR's per-slot epoch tracking
   is irrelevant to the freezer's drain-and-block protocol. Merging them would
   add complexity without benefit. They operate at different layers
   (storage transaction lifecycle vs. cache memory lifecycle) and remain independent.
