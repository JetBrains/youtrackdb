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
`releaseFromRead`/`releaseFromWrite` does `releaseEntry()` (CAS decrement). On the `CachePointer`
side there is an additional pair of CAS operations on `readersWritersReferrer` plus a CAS on
`referrersCount`.

These CAS operations are **write memory barriers on every page access**, which is the hottest
path in the storage engine. The cost is:
1. **Cache-line bouncing** — the `state` and `referrersCount` fields are written by every thread
   that touches the page, invalidating the cache line across cores.
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

Both are updated via CAS on every acquire/release.

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

We use a fixed-size, cache-line-padded array of epoch slots — one per thread (or per
thread-local slot). This is similar to the existing `AtomicOperationsTable` pattern
already used in this codebase.

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
  // Accessed via SLOTS_HANDLE (opaque reads/writes).
  private final long[] slots;
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

**Safe epoch (`computeSafeEpoch`):**

The safe epoch is the largest epoch value `S` such that **no thread is currently
in a critical section that started at or before `S`**. Any retired entry with
`retireEpoch <= S` is guaranteed to be unreachable by all threads and can be
physically reclaimed.

Computation: scan all slots and find the minimum active (non-`INACTIVE`) value.
The safe epoch is `minActiveSlot - 1`. If all slots are `INACTIVE` (no threads
in critical sections), the safe epoch equals the current global epoch.

Slot reads use **acquire** semantics to pair with the release write in `exit()`.
This ensures that if the reclaimer sees `INACTIVE`, all the thread's prior
critical section memory accesses are guaranteed to have completed.

```java
long computeSafeEpoch() {
  long min = GLOBAL_EPOCH_HANDLE.getOpaque(this);
  for (int i = 0; i < slotCount; i++) {
    long slotValue = (long) SLOTS_HANDLE.getAcquire(slots, i);
    if (slotValue < min) {
      min = slotValue;
    }
  }
  // min is either globalEpoch (all slots INACTIVE, since INACTIVE = Long.MAX_VALUE)
  // or the oldest active epoch. Entries retired strictly before min are safe.
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
via `AtomicInteger.getAndIncrement()` (up to the array capacity).

**Slot count:** The array is sized to `availableProcessors() * 4` (configurable).
This is generous for typical workloads. If all slots are exhausted, the thread
falls back to a **shared overflow slot** that uses a traditional `AtomicLong`
counter (increment on enter, decrement on exit). This is slower but correct, and
only activates under extreme thread counts.

**Slot reuse:** When a thread dies, its slot must be returned to the free list.
We use `SlotHandle` — a small helper object held by the `ThreadLocal` — combined
with `java.lang.ref.Cleaner`:

```java
private static final Cleaner CLEANER = Cleaner.create();

class SlotHandle {
  final int slotIndex;
  // registered with CLEANER on allocation
}

// On thread-local init:
var handle = new SlotHandle(slotIndex);
CLEANER.register(handle, () -> {
  SLOTS_HANDLE.setOpaque(slots, slotIndex, INACTIVE);
  freeList.offer(slotIndex);
});
threadLocal.set(handle);
```

When the thread dies, its `ThreadLocal` value becomes unreachable, the `Cleaner`
action fires, the slot is marked `INACTIVE`, and its index is returned to the free
list. This is reliable on JDK 21+ (no finalization dependency) and requires no
polling or manual cleanup.

### Integration Points

#### 1. Critical Section Boundaries

The critical section for EBR is the scope during which a thread holds references to
cache entries. We add `enterCriticalSection()` / `exitCriticalSection()` directly
to `LockFreeReadCache`:

```java
// In LockFreeReadCache:
public long enterCriticalSection() {
  return epochTable.enter();  // returns slot index
}

public void exitCriticalSection(long stamp) {
  epochTable.exit(stamp);
}
```

These methods are also exposed on the `ReadCache` interface so that
`DurableComponent` and `AtomicOperationsManager` can call them without
coupling to the concrete implementation.

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
  final long stamp = readCache.enterCriticalSection();
  try {
    consumer.accept(atomicOperation);
  } finally {
    readCache.exitCriticalSection(stamp);
  }
}
```

The epoch protects all page accesses within the lambda.

#### 2. Entry Eviction (Deferred Reclamation)

Currently, `WTinyLFUPolicy.purgeEden()` calls `entry.freeze()` and immediately
reclaims the pointer if freeze succeeds (`state == 0`). With EBR:

1. **Logical eviction** (under `evictionLock`): Remove the entry from the CHM and
   from the LRU lists. Record the current `globalEpoch` as the entry's `retireEpoch`.
   Add the entry to a **retired list**.
2. **No freeze/state check needed** — the entry is already unreachable from the CHM,
   so no new thread can obtain it.
3. **Physical reclamation**: Performed eagerly during every drain cycle. Since drain
   cycles already run under `evictionLock` and are triggered frequently (on every
   cache miss, on write buffer activity, and forced when cache exceeds 107% capacity),
   this is the most aggressive reclamation strategy without adding a dedicated thread.

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
  RetiredEntry re;
  while ((re = retiredList.peek()) != null && re.retireEpoch <= safeEpoch) {
    retiredList.poll();
    re.entry.getCachePointer().decrementReadersReferrer();
    re.entry.clearCachePointer();
  }
}
```

The retired list is an `ArrayDeque<RetiredEntry>` (not concurrent — accessed
only under `evictionLock`). Entries are appended during eviction and drained
from the head during reclamation, so ordering is naturally FIFO by epoch.

#### 4. CacheEntry Simplification

With EBR, the `CacheEntry.state` field changes meaning:

- **No more reference counting.** `acquireEntry()` / `releaseEntry()` are removed.
- **Simplified lifecycle:**
  ```
  ALIVE  (state = 0)  →  entry is in the CHM, accessible
  RETIRED (state = -1) →  entry removed from CHM, awaiting epoch-safe reclamation
  DEAD   (state = -2)  →  memory freed
  ```
- `acquireEntry()` becomes a simple check: `return state == ALIVE` (plain read, no CAS).
- `freeze()` is replaced by a single CAS `ALIVE → RETIRED` (only under evictionLock,
  so effectively uncontended).

#### 5. CachePointer Simplification

The `readersWritersReferrer` CAS pair on every load/release is eliminated.

- **Readers referrer**: No longer needed per-access. The read cache holds one
  "structural" reader reference for the lifetime of the entry in the cache.
  Decremented once during physical reclamation.
- **Writers referrer**: Still needed for dirty-page tracking. Only modified on
  `loadForWrite` / `releaseFromWrite` (write path), which is much less frequent
  and already takes an exclusive lock.
- **referrersCount**: Simplified. The read cache holds 1 ref. The write cache
  holds 1 ref per dirty copy. No per-access increment/decrement.

### What Changes on the Hot Path

| Operation | Before (per access) | After (per access) |
|---|---|---|
| `loadForRead` enter | CAS on `state` (acquireEntry) | Opaque write to thread-local slot + StoreLoad fence |
| `loadForRead` exit | CAS on `state` (releaseEntry) | Release write to thread-local slot |
| `loadForRead` pointer | CAS on `readersWritersReferrer` + CAS on `referrersCount` | Nothing |
| `releaseFromRead` pointer | CAS on `readersWritersReferrer` + CAS on `referrersCount` | Nothing |
| Eviction freeze check | CAS on `state` (may fail, retry) | Plain read (always succeeds — no holders to check) |
| Eviction reclamation | Immediate | Deferred (amortized in drain cycles) |

**Net result**: The read path goes from **4 CAS operations** (2 on state, 2 on pointer)
to **1 opaque write + 1 StoreLoad fence** (enter) and **1 release write** (exit) on a
thread-local epoch slot. The fence on enter is cheaper than the CAS it replaces (one
barrier vs. a serializing read-modify-write that bounces cache lines), and the exit
is a plain store on x86. This eliminates all cross-core cache-line invalidation on the
read path.

### Handling Edge Cases

#### File Deletion / Truncation / Close

`clearFile()` currently iterates pages and calls `freeze()` on each, failing if any
entry has `state > 0`. With EBR:

1. Remove entries from CHM (making them unreachable).
2. Add them to the retired list with the current epoch.
3. Wait for epoch to advance past the safe point (synchronous barrier).
4. Then proceed with file close/delete.

This is safe because `clearFile` is called under `evictionLock` and during
storage shutdown when no concurrent operations should be running. If needed,
we can add a `waitForSafeEpoch(retireEpoch)` that busy-waits/parks until
all active threads have exited their critical sections.

#### Write Path (loadForWrite / releaseFromWrite)

The write path is less frequent and already acquires an exclusive lock on the
`CachePointer`. The EBR critical section still protects the entry from
reclamation. The only additional invariant: `releaseFromWrite` must call
`writeCache.store()` **before** exiting the critical section, which is
already the case.

For dirty-page tracking (`WritersListener`), we keep the writers count
on `CachePointer` since it is only modified under the exclusive lock
(no contention).

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

#### Outside-Cache Entries (insideCache = false)

Entries created with `insideCache = false` (e.g., `silentLoadForRead`) are not
part of the CHM and not managed by the eviction policy. These currently use
`decrementReadersReferrer()` on release. With EBR, these entries use the
same epoch-based critical section as regular cache entries. The caller must
be inside a critical section (enter/exit) when accessing the entry. On
`releaseFromRead`, the entry is added to the retired list with the current
epoch and reclaimed during the next drain cycle, just like in-cache entries.
This uniform approach eliminates a separate code path and reduces the risk
of correctness bugs from inconsistent handling.

## Implementation Phases

### Phase 1: EpochTable Infrastructure

**Files to create:**
- `core/.../storage/cache/ebr/EpochTable.java` — epoch table with padded slots
- `core/.../storage/cache/ebr/RetiredEntryQueue.java` — queue of entries awaiting reclamation

**Key design decisions:**
- Slot count: `Runtime.getRuntime().availableProcessors() * 4` (default), configurable
- Padding: 128 bytes per slot (2 cache lines, covers Intel + ARM)
- Epoch type: `long` (effectively unbounded at 1 increment per drain cycle)
- All access via `VarHandle` (`MethodHandles.arrayElementVarHandle(long[].class)`
  for per-slot access, field `VarHandle` for global epoch). Access modes:
  `setOpaque` + `VarHandle.fullFence()` for enter, `setOpaque` for advanceEpoch,
  `setRelease` for exit, `getAcquire` for reclaimer slot reads.
  No `AtomicLong` / `AtomicLongArray` wrappers.

**Tests:**
- Unit tests for EpochTable: concurrent enter/exit, safeEpoch computation correctness
- Stress tests: many threads entering/exiting while reclamation scans run

### Phase 2: Integrate EBR into LockFreeReadCache

**Files to modify:**
- `LockFreeReadCache.java` — add `enterCriticalSection()` / `exitCriticalSection()`,
  integrate retired list scanning into `drainBuffers()`
- `WTinyLFUPolicy.java` — change eviction from freeze-based to retire-based
- `CacheEntryImpl.java` — simplify state machine (remove reference counting)

**Changes:**
1. `doLoad()`: Remove `acquireEntry()` CAS. After finding/creating the entry in the CHM,
   return it directly (the caller is already in a critical section).
2. `releaseFromRead()`: Remove `releaseEntry()` CAS. The entry stays alive; epoch
   protects it from reclamation.
3. `purgeEden()`: Replace `freeze()` with `retire()` — remove from CHM, add to retired
   list with current epoch. No need to check state.
4. `drainBuffers()`: Add epoch increment + `reclaimRetired()` call.

### Phase 3: Simplify CachePointer

**Files to modify:**
- `CachePointer.java` — remove per-access `readersWritersReferrer` updates from read path
- `CacheEntryImpl.java` — remove `readCache.releaseFromRead()` CAS delegation

**Changes:**
1. `incrementReadersReferrer()` / `decrementReadersReferrer()` — called only during
   structural add (entry enters cache) and reclamation (entry leaves cache), not per access.
2. `WritersListener` tracking — only triggered from write path (exclusive lock held).
3. `referrersCount` — structural only (1 from read cache, 1 from write cache per dirty copy).

### Phase 4: AtomicOperationsManager Integration

**Files to modify:**
- `ReadCache.java` — add `enterCriticalSection()` / `exitCriticalSection()` to interface
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

### Phase 5: Cleanup and Validation

1. Remove `acquireEntry()` / `releaseEntry()` from `CacheEntry` interface.
2. Remove `FROZEN` state — replace with `RETIRED`.
3. Remove `freeze()` / `makeDead()` from `CacheEntry` interface — replace with `retire()`.
4. Update `silentLoadForRead` path (outside-cache entries).
5. Run full test suite, integration tests, and benchmarks.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Delayed reclamation increases memory pressure | Bound retired list size; trigger synchronous reclamation if threshold exceeded |
| Stalled thread holds back reclamation | Critical sections are very short; monitor max retired list depth |
| Complexity of slot management | Reuse existing `AtomicOperationsTable` pattern already proven in this codebase |
| Correctness of epoch ordering | Formal argument: a thread that read an entry must have entered before the entry was retired; reclamation waits for all such threads to exit. Verified by stress tests. |
| Write path correctness | Write path still uses exclusive locks; EBR only removes read-path CAS. Low risk. |

## Open Questions

1. ~~**Epoch advancement frequency**~~: **Decided — per drain cycle.** The epoch is
   advanced at the start of every `drainBuffers()` / `emptyBuffers()` call, which
   already fires on every cache miss, write buffer activity, and forced drain at
   107% capacity. This is aggressive enough for eager reclamation without adding
   a dedicated timer or per-operation overhead.
2. ~~**Retired list bound**~~: **Decided — 1% of maxCacheSize.** When the retired
   list exceeds this threshold, the drain cycle blocks (synchronous reclamation)
   until the list drains below the threshold or all active epochs advance.
3. ~~**`silentLoadForRead` outside-cache path**~~: **Decided — EBR-ify it.** Use
   the same epoch-based critical section for outside-cache entries. This ensures
   a single uniform approach across all cache paths, improving implementation
   robustness and eliminating the risk of forgetting to handle the two paths
   differently.
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
