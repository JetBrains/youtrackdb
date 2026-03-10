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
  // Global epoch, incremented periodically (e.g. on each eviction drain cycle).
  private final AtomicLong globalEpoch = new AtomicLong(0);

  // Per-slot epoch values. INACTIVE means the thread is not in a critical section.
  // Padded to avoid false sharing.
  private final PaddedAtomicLong[] slots; // one per max concurrent thread

  static final long INACTIVE = Long.MAX_VALUE;
}
```

**Slot lifecycle:**
- `enter()`: `slot[tid] = globalEpoch.get()` (plain or opaque write — **no CAS**)
- `exit()`: `slot[tid] = INACTIVE` (plain or opaque write — **no CAS**)
- `safeToReclaim(epoch)`: scan all slots; if every active slot has value `> epoch`,
  the epoch is safe.

The critical property: `enter()` and `exit()` are **single writes to thread-local
slots** — no contended atomics, no cache-line bouncing between threads.

### Slot Assignment

Threads obtain slot indices via `ThreadLocal<Integer>` backed by an atomic counter.
Slot count is bounded by `availableProcessors() * 4` (configurable). If more threads
arrive than slots, they fall back to a slow path (acquire a slot from a reuse pool).

To avoid unbounded slot growth, we use a **slot reuse** strategy: when a thread exits
permanently (detected via `ThreadLocal.remove()` or `Thread.onSpinWait()` polling),
its slot is returned to a free list.

### Integration Points

#### 1. Critical Section Boundaries

The critical section for EBR is the scope during which a thread holds references to
cache entries. We add two methods to `LockFreeReadCache` (or a wrapper):

```java
public long enterCriticalSection() {
  return epochTable.enter();  // returns slot index or epoch stamp
}

public void exitCriticalSection(long stamp) {
  epochTable.exit(stamp);
}
```

**Where to call them:**

The critical section is scoped at the **component operation level** — each individual
`calculateInsideComponentOperation` / `executeInsideComponentOperation` invocation.
This keeps critical sections short (single page access or small batch of page accesses)
and ensures the retired list drains quickly.

**Important**: `AtomicOperation` (transaction) level scoping is explicitly **not** used.
Transactions can be long-lived (spanning many component operations, I/O waits, user
logic), so holding an epoch slot for the entire transaction would prevent reclamation
and lead to OOM as the retired list grows unboundedly.

Add `componentOperationStart()` / `componentOperationStop()` methods to
`DurableComponent` that call `readCache.enterCriticalSection()` /
`readCache.exitCriticalSection()`:

```java
// In DurableComponent:
protected long componentOperationStart() {
  return readCache.enterCriticalSection();
}

protected void componentOperationStop(long stamp) {
  readCache.exitCriticalSection(stamp);
}
```

Integration points:
- `AtomicOperationsManager.calculateInsideComponentOperation` /
  `executeInsideComponentOperation`: enter epoch before the lambda, exit after.
  The epoch protects all page accesses within the lambda.

#### 2. Entry Eviction (Deferred Reclamation)

Currently, `WTinyLFUPolicy.purgeEden()` calls `entry.freeze()` and immediately
reclaims the pointer if freeze succeeds (`state == 0`). With EBR:

1. **Logical eviction** (under `evictionLock`): Remove the entry from the CHM and
   from the LRU lists. Record the current `globalEpoch` as the entry's `retireEpoch`.
   Add the entry to a **retired list**.
2. **No freeze/state check needed** — the entry is already unreachable from the CHM,
   so no new thread can obtain it.
3. **Physical reclamation**: Periodically (during drain cycles or a background thread),
   scan the retired list. For each retired entry whose `retireEpoch` is safe to reclaim
   (all threads have advanced past it), release the `CachePointer`'s direct memory.

```java
// In WTinyLFUPolicy or a new EpochReclaimManager:
class RetiredEntry {
  final CacheEntry entry;
  final long retireEpoch;
}

private final Queue<RetiredEntry> retiredList = new ConcurrentLinkedQueue<>();

void reclaimRetired() {
  long safeEpoch = epochTable.computeSafeEpoch();
  RetiredEntry re;
  while ((re = retiredList.peek()) != null && re.retireEpoch <= safeEpoch) {
    retiredList.poll();
    re.entry.getCachePointer().decrementReadersReferrer();
    re.entry.clearCachePointer();
  }
}
```

#### 3. Epoch Advancement

The global epoch should be incremented regularly to allow reclamation to make
progress. Natural trigger points:

- **Each eviction drain cycle** (`drainBuffers()` / `emptyBuffers()` under
  `evictionLock`): increment epoch at the start.
- **Each reclamation scan**: after incrementing, scan for entries that can be freed.
- Optionally, a **background reclamation thread** if the retired list grows beyond
  a threshold (amortized, low overhead).

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
| `loadForRead` enter | CAS on `state` (acquireEntry) | Plain write to thread-local slot |
| `loadForRead` exit | CAS on `state` (releaseEntry) | Plain write to thread-local slot |
| `loadForRead` pointer | CAS on `readersWritersReferrer` + CAS on `referrersCount` | Nothing |
| `releaseFromRead` pointer | CAS on `readersWritersReferrer` + CAS on `referrersCount` | Nothing |
| Eviction freeze check | CAS on `state` (may fail, retry) | Plain read (always succeeds — no holders to check) |
| Eviction reclamation | Immediate | Deferred (amortized in drain cycles) |

**Net result**: The read path goes from **4 CAS operations** (2 on state, 2 on pointer)
to **2 plain writes** to a thread-local epoch slot. This eliminates all write memory
barriers and cross-core cache-line invalidation on the read path.

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
`decrementReadersReferrer()` on release. With EBR, these entries bypass epoch
tracking entirely — they are single-use and their pointer is released
immediately on `releaseFromRead`. This path can keep direct pointer management
since it is not contended (single owner).

## Implementation Phases

### Phase 1: EpochTable Infrastructure

**Files to create:**
- `core/.../storage/cache/ebr/EpochTable.java` — epoch table with padded slots
- `core/.../storage/cache/ebr/RetiredEntryQueue.java` — queue of entries awaiting reclamation

**Key design decisions:**
- Slot count: `Runtime.getRuntime().availableProcessors() * 4` (default), configurable
- Padding: 128 bytes per slot (2 cache lines, covers Intel + ARM)
- Epoch type: `long` (effectively unbounded at 1 increment per drain cycle)
- `enter()` / `exit()` use `VarHandle` opaque-mode writes (ordered within the thread,
  no cross-thread happens-before — sufficient because we only need eventual visibility
  during reclamation scans, which are infrequent)

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

### Phase 4: DurableComponent Integration

**Files to modify:**
- `DurableComponent.java` — add `componentOperationStart()` / `componentOperationStop()`
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

1. **Epoch advancement frequency**: Should we increment per drain cycle (current plan),
   per N operations, or on a timer? Trade-off: more frequent = faster reclamation but
   more scans; less frequent = more deferred memory but fewer scans.
2. **Retired list bound**: What threshold triggers synchronous reclamation? Proportional
   to cache size (e.g., 1% of maxCacheSize)?
3. **`silentLoadForRead` outside-cache path**: Keep reference counting for this path
   (single-owner, not contended) or also EBR-ify it?
4. **Integration with `OperationsFreezer`**: The freezer already uses a count-based
   barrier for write operations. Should the EBR epoch table subsume this, or remain
   independent?
