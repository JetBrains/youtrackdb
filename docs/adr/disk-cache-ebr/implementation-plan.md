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

  // Global epoch, incremented periodically (e.g. on each eviction drain cycle).
  // Accessed via GLOBAL_EPOCH_HANDLE (opaque reads/writes).
  @SuppressWarnings("unused")
  private volatile long globalEpoch;

  // Per-slot epoch values. INACTIVE means the thread is not in a critical section.
  // Each slot is padded to its own cache line (128 bytes) to avoid false sharing.
  // Accessed via SLOTS_HANDLE for per-element access.
  // The array reference is volatile to support dynamic growth (see Slot Assignment).
  private volatile long[] slots;
}
```

Slot and global epoch access uses `VarHandle` with **different access modes
depending on the ordering requirements**:

**`enter()` — opaque write + full fence:**
```java
SLOTS_HANDLE.setOpaque(slots, slotIndex, GLOBAL_EPOCH_HANDLE.getOpaque(this));
VarHandle.fullFence();
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
`SLOTS_HANDLE.setRelease(slots, slotIndex, INACTIVE)`
— **no CAS**, single release write. Release semantics are required here:
all memory accesses within the critical section (page data reads, pointer
dereferences) must be ordered **before** the slot is marked `INACTIVE`.
Without release, a weakly-ordered CPU (e.g., ARM) could reorder page data
reads past the `INACTIVE` store, allowing the reclaimer to free memory that
the thread is still reading. On x86 (TSO), release compiles to a plain store
(no extra cost); on ARM, it compiles to `stlr` (store-release, lightweight).

**`advanceEpoch()` — opaque write:**
`GLOBAL_EPOCH_HANDLE.setOpaque(this, epoch + 1)` — called under `evictionLock`
so no contention. Opaque is sufficient because the eviction lock's
unlock provides the necessary release fence.

**Slot lifecycle summary:**
- `enter()`: opaque write + `fullFence()` (StoreLoad barrier ensures slot
  is visible to reclaimer before thread reads shared data)
- `exit()`: release write (ensures critical section completes before exit)
- `advanceEpoch()`: opaque write (evictionLock provides ordering)

The critical property: `enter()` and `exit()` are **single writes to
thread-local slots** — no contended atomics, no cache-line bouncing between
threads.

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
in critical sections), the safe epoch equals the current global epoch.

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

**Defensive guard against `INACTIVE` leak:** The `min` variable is initialized
to `globalEpoch`, so it can only reach `Long.MAX_VALUE` (`INACTIVE`) if
`globalEpoch` itself is `INACTIVE` — which should never happen. However, as a
defensive measure, `computeSafeEpoch` explicitly checks for this case and
returns `globalEpoch` instead of `Long.MAX_VALUE - 1`, which would
catastrophically allow reclaiming nearly all retired entries.

```java
long computeSafeEpoch() {
  long currentEpoch = (long) GLOBAL_EPOCH_HANDLE.getOpaque(this);
  long min = currentEpoch;
  // Read the volatile slots reference once. If a concurrent grow() swaps
  // the array, we scan the snapshot we captured — safe because any newly
  // added slots hold a current epoch and missing them is conservative.
  long[] slotsSnapshot = slots;
  for (int i = 0; i < slotsSnapshot.length; i++) {
    long slotValue = (long) SLOTS_HANDLE.getAcquire(slotsSnapshot, i);
    if (slotValue < min) {
      min = slotValue;
    }
  }
  // If min is INACTIVE, all slots are inactive and globalEpoch was not
  // corrupted — safe epoch is the current global epoch. Guard against
  // the pathological case where globalEpoch == INACTIVE (should never
  // happen) to avoid returning Long.MAX_VALUE - 1.
  if (min == INACTIVE) {
    return currentEpoch;
  }
  // min is the oldest active epoch. Entries retired strictly before min
  // are safe to reclaim.
  return min - 1;
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

**Initial size:** `availableProcessors() * 4`. This is generous for typical
workloads; growth is rare in practice and only occurs under high thread counts.

**Dynamic growth:** When a thread needs a slot index beyond the current array
capacity, the array is grown under a dedicated `growLock` (a simple
`ReentrantLock`, separate from `evictionLock`):

```java
private void grow(int requiredIndex) {
  growLock.lock();
  try {
    long[] current = slots;
    if (requiredIndex < current.length) {
      return;  // another thread already grew
    }
    int newSize = Math.max(current.length * 2, requiredIndex + 1);
    long[] newSlots = new long[newSize];
    // Copy existing slots. All new slots are 0, but must be INACTIVE.
    System.arraycopy(current, 0, newSlots, 0, current.length);
    Arrays.fill(newSlots, current.length, newSize, INACTIVE);
    // Volatile write publishes the new array. Readers (computeSafeEpoch)
    // read the volatile `slots` reference once and scan that snapshot.
    // A reader that sees the old array may miss newly added slots, but
    // those slots were just written with a current epoch — missing them
    // is conservative (delays reclamation, never causes a safety violation).
    slots = newSlots;
  } finally {
    growLock.unlock();
  }
}
```

This follows the `LongAdder` / `Striped64` pattern: start small, grow on
demand, never shrink. Growth is O(thread count) in the worst case, but
slot reuse via `Cleaner` means the array rarely grows beyond the high-water
mark of concurrent threads.

**Slot reuse:** When a thread dies, its slot must be returned to the free list.
We use `SlotHandle` — a small helper object held by the `ThreadLocal` — combined
with `java.lang.ref.Cleaner`:

```java
private static final Cleaner CLEANER = Cleaner.create();

class SlotHandle {
  final int slotIndex;
  boolean active;  // thread-local, no synchronization needed; used for assertion
  // registered with CLEANER on allocation
}

// On thread-local init:
var handle = new SlotHandle(slotIndex);
CLEANER.register(handle, () -> epochTable.releaseSlot(slotIndex));
threadLocal.set(handle);

// In EpochTable:
void releaseSlot(int slotIndex) {
  // Read the current volatile slots reference — never a stale array
  // captured at registration time. If grow() replaced the array since
  // the slot was allocated, this still writes to the live array.
  SLOTS_HANDLE.setOpaque(slots, slotIndex, INACTIVE);
  freeList.offer(slotIndex);
}
```

The cleaner action calls a method on `EpochTable` rather than closing over
the `slots` array reference directly. This avoids a subtle bug: if `grow()`
replaces the `slots` array between slot allocation and cleaner execution,
a captured reference would point to the stale (old) array, and the
`INACTIVE` write would be lost. By reading the volatile `slots` field
inside `releaseSlot()`, the write always targets the current live array.

When the thread dies, its `ThreadLocal` value becomes unreachable, the `Cleaner`
action fires, the slot is marked `INACTIVE`, and its index is returned to the free
list. This is reliable on JDK 21+ (no finalization dependency) and requires no
polling or manual cleanup.

**No reentrancy — enforced by assertion:** Nested component operations are
prohibited by design. Each `calculateInsideComponentOperation` /
`executeInsideComponentOperation` is a leaf-level operation that does not
call back into the `AtomicOperationsManager` to start another component
operation. If this invariant were violated, the inner `enter()` would
overwrite the slot with a newer epoch, and the inner `exit()` would set the
slot to `INACTIVE` while the outer critical section is still active — a
use-after-free bug.

To catch violations early, `SlotHandle` tracks an `active` flag (plain
`boolean`, thread-local — no synchronization needed):

- **`enter()`**: `assert !handle.active : "EBR critical section is not reentrant"`;
  then set `handle.active = true`, write epoch to slot, execute fence.
- **`exit()`**: `assert handle.active`; set `handle.active = false`, write
  `INACTIVE` (release).

This adds zero cost in production (assertions disabled) and catches nesting
bugs immediately during development and testing.

### Integration Points

#### 1. Critical Section Boundaries

The critical section for EBR is the scope during which a thread holds references to
cache entries. We add `enterCriticalSection()` / `exitCriticalSection()` directly
to `LockFreeReadCache`:

```java
// In LockFreeReadCache:
public void enterCriticalSection() {
  if (retiredListSize.get() >= retiredListThreshold) {
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
checks a `volatile int retiredListSize` against a threshold (1% of
`maxCacheSize`). If exceeded, the thread attempts `tryLock()` on
`evictionLock` and runs a reclamation pass (advance epoch + reclaim). If
the lock is already held (another thread is draining), it skips — no
blocking, no deadlock. This is self-regulating: threads that generate cache
pressure assist with cleanup. The fast path (below threshold) is a single
volatile read — effectively free.

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

#### 2. Entry Eviction (Deferred Reclamation)

Currently, `WTinyLFUPolicy.purgeEden()` calls `entry.freeze()` and immediately
reclaims the pointer if freeze succeeds (`state == 0`). With EBR:

1. **Retire the entry** (under `evictionLock`): Call `entry.retire()` — CAS from
   `0` to `RETIRED`. This can **fail** if a writer holds the entry (`state > 0`),
   in which case eviction moves the entry back to eden and tries another victim
   (same as today's `freeze()` failure path).
2. **Remove from CHM and LRU lists** (under `evictionLock`): `data.remove()` makes
   the entry unreachable — no new thread can obtain it from the CHM.
3. **Record `retireEpoch`** (under `evictionLock`): Set `entry.retireEpoch` to the
   current `globalEpoch`. Add the entry to the **retired list**.
4. **Physical reclamation**: Performed eagerly during every drain cycle. Since drain
   cycles already run under `evictionLock` and are triggered frequently (on every
   cache miss, on write buffer activity, and forced when cache exceeds 107% capacity),
   this is the most aggressive reclamation strategy without adding a dedicated thread.

**Critical ordering invariant — retire before CHM removal:**

`retire()` (step 1) **must** succeed before `data.remove()` (step 2). This
order is essential for two reasons:

- **CachePointer divergence prevention:** If the entry were removed from the
  CHM first (while `state == 0`), another thread could immediately create a
  new entry for the same page via `data.compute()`. A concurrent writer that
  already obtained the old entry (but hasn't called `acquireEntry()` yet) could
  then race with the new entry. By retiring first (setting `state = RETIRED`),
  we ensure that any concurrent `acquireEntry()` on this entry fails, and the
  writer's retry loop in `doLoadForWrite()` will pick up the new entry.

- **Epoch safety:** `retireEpoch` (step 3) records the epoch at which the entry
  became unreachable from the CHM. If `retireEpoch` were recorded **before**
  CHM removal, a thread entering at that epoch could still find the entry in
  the CHM via `data.get()`, obtain a reference, and then the reclaimer could
  free it (since `retireEpoch <= safeEpoch`). Recording `retireEpoch` after
  CHM removal ensures that any thread which entered at or after `retireEpoch`
  will **not** find the entry in the CHM — it was already removed.

**CHM removal before retireEpoch recording** is equally critical and must not
be reversed. The required order within eviction is:

```
retire()  →  data.remove()  →  record retireEpoch  →  add to retired list
   (1)           (2)                 (3)                     (3)
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
`evictionLock`, so plain writes to the field are safe — mutual exclusion
guarantees atomicity, and the lock's release fence publishes the new value.
The field is declared `volatile` solely so that `enterCriticalSection()` can
read it **outside the lock** for the backpressure check with guaranteed
visibility. A stale read is harmless (triggers reclamation one cycle early or
late).

#### 4. CacheEntry Simplification

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
- `retireEpoch` field (plain `long`) is added directly to `CacheEntry` to record
  the epoch at which the entry was retired. This eliminates the need for a
  separate `RetiredEntry` wrapper object (see retired list below).

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
   eventually calls `WTinyLFUPolicy.onAccess()`. That method already checks
   `!cacheEntry.isDead()` and skips dead/retired entries, so posting a stale
   entry is harmless.
4. **`data.compute()` path**: The compute lambda holds CHM's per-bucket lock,
   so eviction cannot concurrently remove the same key. The returned entry is
   guaranteed to be in the CHM when compute returns.

Therefore the read-side `doLoad()` simplifies to a straight-line flow:
`data.get()` or `data.compute()`, then `afterRead()` / `afterAdd()`, return.
No CAS, no retry loop.

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

#### 5. CachePointer — No Changes Needed

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
| Component operation enter | Nothing | Opaque write to thread-local slot + StoreLoad fence (once per component op) |
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
1 StoreLoad fence** (enter) and **1 release write** (exit) — but this cost is
paid **once per component operation**, which may include multiple page accesses.
The fence on enter is cheaper than the CAS it replaces (one barrier vs. a
serializing read-modify-write that bounces cache lines), and the exit is a
plain store on x86. This eliminates all cross-core cache-line invalidation on
the read path.

A component operation that reads N pages (cache hits) previously paid 2N CAS
operations; with EBR it pays 1 opaque write + 1 fence + 1 release write,
regardless of N.

**Write path**: unchanged — retains 1 CAS on `acquireEntry()` and 1 CAS on
`releaseEntry()`. This is necessary to prevent CachePointer divergence (see
"Write Path" section below). The cost is negligible relative to the exclusive
lock the write path already acquires.

### Handling Edge Cases

#### File Deletion / Truncation / Close

`clearFile()` currently iterates pages and calls `freeze()` on each, failing if any
entry has `state > 0`. With EBR, the `state` field still tracks write holders
(see "Write Path" above), so `retire()` can still fail if `state > 0`.
The approach:

1. Remove entries from CHM (making them unreachable).
2. Record the current `globalEpoch` as `retireEpoch` for these entries.
3. Advance the epoch (`globalEpoch++`) — this is safe because `clearFile()`
   holds `evictionLock`.
4. **Release `evictionLock`**, then call `waitForSafeEpoch(retireEpoch)` which
   **spins/parks outside the lock** until `computeSafeEpoch() >= retireEpoch`.
   Releasing the lock is essential: threads that are currently inside critical
   sections need to finish and call `exit()`, and threads that enter new
   critical sections after step 3 will record an epoch > `retireEpoch` (so
   they don't block reclamation). If `waitForSafeEpoch` were called under
   `evictionLock`, it would deadlock — no drain cycle could advance the epoch
   or make progress while the lock is held.
5. Once `waitForSafeEpoch` returns, all threads that could have observed the
   removed entries have exited their critical sections. Physically reclaim
   the entries (release pointers back to `ByteBufferPool`).
6. Proceed with file close/delete.

```java
// In LockFreeReadCache:
void clearFile(long fileId) {
  List<CacheEntry> removed;
  long retireEpoch;

  evictionLock.lock();
  try {
    removed = removeEntriesForFile(fileId);  // remove from CHM + LRU
    removeRetiredEntriesForFile(fileId, removed);  // also drain from retired list
    retireEpoch = epochTable.currentEpoch();
    epochTable.advanceEpoch();
  } finally {
    evictionLock.unlock();
  }

  // Spin/park outside the lock until all pre-existing critical sections exit.
  epochTable.waitForSafeEpoch(retireEpoch);

  // Now safe to reclaim — no thread can hold references to these entries.
  for (var entry : removed) {
    entry.getCachePointer().decrementReadersReferrer();
    entry.clearCachePointer();
  }
}

private void removeRetiredEntriesForFile(long fileId, List<CacheEntry> sink) {
  // Scan the retired list (ArrayDeque) and remove entries matching fileId.
  // Called under evictionLock, so no concurrent modification.
  var it = retiredList.iterator();
  while (it.hasNext()) {
    var entry = it.next();
    if (entry.getFileId() == fileId) {
      it.remove();
      retiredListSize--;
      sink.add(entry);
    }
  }
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
added to the same `removed` list and reclaimed after `waitForSafeEpoch()`,
ensuring no thread can still hold references to them.

Note: This scan is O(retired list size), not O(file pages). For the typical
case where the retired list is small (bounded by the 1% backpressure
threshold), this is negligible. If needed, the retired list could be indexed
by file ID, but this optimization is unlikely to be necessary.

`waitForSafeEpoch` polls `computeSafeEpoch()` in a spin loop with
progressive backoff (`Thread.onSpinWait()` → `Thread.yield()` →
`LockSupport.parkNanos()`). A bounded timeout (e.g., 30 seconds) triggers
an `IllegalStateException` — a thread stuck in a critical section that long
indicates a bug, not a legitimate workload.

**Progress guarantee:** `waitForSafeEpoch(retireEpoch)` does **not** require
the global epoch to advance further. It only requires that all threads which
were in a critical section at or before `retireEpoch` exit. Specifically:

- A thread that entered at epoch `<= retireEpoch` and is still active blocks
  progress — `computeSafeEpoch()` returns `<= retireEpoch - 1 < retireEpoch`.
  Once that thread calls `exit()` (release write of `INACTIVE`), its slot no
  longer contributes to the minimum.
- A thread that enters **after** `advanceEpoch()` (step 3) records epoch
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

## Implementation Phases

### Phase 1: EpochTable Infrastructure

**Files to create:**
- `core/.../storage/cache/ebr/EpochTable.java` — epoch table with padded slots
- `core/.../storage/cache/ebr/RetiredEntryQueue.java` — queue of entries awaiting reclamation

**Key design decisions:**
- Initial slot count: `Runtime.getRuntime().availableProcessors() * 4`; grows
  dynamically (2x) under `growLock` when exhausted (LongAdder/Striped64 pattern)
- Padding: 128 bytes per slot (2 cache lines, covers Intel + ARM)
- Epoch type: `long` (effectively unbounded at 1 increment per drain cycle)
- All access via `VarHandle` (`MethodHandles.arrayElementVarHandle(long[].class)`
  for per-slot access, field `VarHandle` for global epoch). Access modes:
  `setOpaque` + `VarHandle.fullFence()` for enter, `setOpaque` for advanceEpoch,
  `setRelease` for exit, `getAcquire` for reclaimer slot reads.
  No `AtomicLong` / `AtomicLongArray` wrappers.
- No reentrancy: nested component operations are prohibited by design.
  `SlotHandle.active` (plain `boolean`) + assertion in `enter()` catches
  violations during development. Zero production cost.

**Tests:**
- Unit tests for EpochTable: concurrent enter/exit, safeEpoch computation correctness
- Unit test: assert that nested `enter()` throws `AssertionError`
- Stress tests: many threads entering/exiting while reclamation scans run

### Phase 2: AtomicOperationsManager Integration

**Files to modify:**
- `ReadCache.java` — add `enterCriticalSection()` / `exitCriticalSection()` to interface
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
   entry back to eden, same as today's `freeze()` failure path. On success, remove
   from CHM, add to retired list with current epoch. **Do not** call
   `decrementReadersReferrer()` here — it moves to `reclaimRetired()`.
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
4. Verify all three paths that retain `acquireEntry()`:
   - **`loadForWrite` / `releaseFromWrite`**: entry stays in CHM while writer holds
     it; `retire()` fails when `state > 0`.
   - **`silentLoadForRead` (in-cache path)**: entry found in CHM, protected by ref
     count; `retire()` fails when `state > 0`.
   - **`silentLoadForRead` (outside-cache path)**: entry not in CHM, protected by
     `acquireEntry()` + `decrementReadersReferrer()` on release.
5. Run full test suite, integration tests, and benchmarks.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Delayed reclamation increases memory pressure | Backpressure on `enterCriticalSection()`: threads assist reclamation via `tryLock` when retired list exceeds 1% of maxCacheSize |
| Stalled thread holds back reclamation | Critical sections are very short; monitor max retired list depth |
| Complexity of slot management | Reuse existing `AtomicOperationsTable` pattern already proven in this codebase |
| Correctness of epoch ordering | Formal argument: a thread that read an entry must have entered before the entry was retired; reclamation waits for all such threads to exit. Verified by stress tests. |
| Write path correctness | Write path retains `acquireEntry()` / `releaseEntry()` CAS to prevent CachePointer divergence. `retire()` (CAS from 0 to -1) fails when writers hold the entry, same as today's `freeze()`. EBR only removes read-path CAS. Low risk. |

## Open Questions

1. ~~**Epoch advancement frequency**~~: **Decided — per drain cycle.** The epoch is
   advanced at the start of every `drainBuffers()` / `emptyBuffers()` call, which
   already fires on every cache miss, write buffer activity, and forced drain at
   107% capacity. This is aggressive enough for eager reclamation without adding
   a dedicated timer or per-operation overhead.
2. ~~**Retired list bound**~~: **Decided — 1% of maxCacheSize, backpressure on
   enter.** When the retired list exceeds this threshold, threads entering a
   critical section assist reclamation via `tryLock` on `evictionLock` +
   `advanceEpoch()` + `reclaimRetired()`. If the lock is already held, the
   thread skips and proceeds — no blocking, no deadlock risk. This is
   self-regulating: threads that generate cache pressure share the cleanup work.
   The fast-path cost (below threshold) is a single volatile read.
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
