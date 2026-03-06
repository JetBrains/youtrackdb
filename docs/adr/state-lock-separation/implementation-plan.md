# Implementation Plan: Lock-Free Data Structures + Reentrant `ScalableRWLock`

## Motivation

`AbstractStorage.stateLock` (a `ScalableRWLock`) is acquired in read mode on **every** storage
operation — record reads, index lookups, collection metadata queries, transaction commits, etc.
For a query reading N records via an index, this means ~N+1 lock/unlock pairs, each costing one
StoreLoad memory fence (~20-40 cycles on x86). Profiling shows this is measurable overhead on
the hot read path.

The lock protects three independent concerns conflated into one mechanism:
1. **Data structure access** — `collectionMap` (HashMap) and `indexEngines` (ArrayList) are not
   thread-safe for concurrent reads/writes.
2. **DDL mutual exclusion** — add/drop collection/index must not race with each other.
3. **Lifecycle safety** — close/drop/shutdown must wait for in-flight operations to complete.

This plan separates these three concerns, eliminating per-operation lock overhead on the
read path.

## Target Architecture

```
Current:  stateLock.readLock() on EVERY storage call (~120 sites)
          stateLock.writeLock() on DDL + lifecycle (~22 sites)

Proposed: Lock-free concurrent data structures                 (concern 1)
        + ddlLock (ReentrantLock) for DDL only                 (concern 2)
        + Reentrant stateLock (read side) for lifecycle guard  (concern 3)
```

The key insight: add **read-side reentrancy** to the existing `ScalableRWLock`. The first
`sharedLock()` on a thread pays the full Dekker cost (1 StoreLoad + 1 volatile read). All
subsequent nested `sharedLock()` calls on that thread are just
`ThreadLocal.get() + counter++` — **~1ns, zero memory barriers**. No new class needed.

DDL operations serialize against each other via `ddlLock` but do not block readers.
Close/drop uses the existing `stateLock.writeLock()` drain mechanism.

#### Cost comparison (per `readRecord()` call within a query):

| | Current SRWL | Reentrant SRWL (nested) |
|---|---|---|
| ThreadLocal.get() | 1 (~1ns) | 1 (~1ns) |
| StoreLoad fence | **1 (~5-40ns)** | **0** |
| Volatile read | 1 (~0.2ns) | 0 |
| Release store | 1 (~0.1ns) | 0 |
| Counter inc/dec | 0 | 1 (~0.1ns) |
| **Total** | **~6-42ns** | **~1.1ns** |

## Prerequisites

- All changes target `AbstractStorage.java` and its subclasses (`DiskStorage`,
  `DirectMemoryStorage`).
- `DiskStorage` has 3 additional `stateLock` usages: `create()` (write), `restoreFromBackup()`
  (write), `backup()` (read). `DirectMemoryStorage` has none.
- The `StorageCollection` and `BaseIndexEngine` objects are internally thread-safe (via
  `AtomicOperation` and page-level locking). The `stateLock` only protects the **lookup** of
  these objects, not their internal operations.
- `collections` is already a `CopyOnWriteArrayList` — reads are already lock-free.
- The `commit()` method already calls `doGetAndCheckCollection()` outside `stateLock` (lines
  1905, 1924), proving the pattern works.

---

## Step 1: Replace `collectionMap` with `ConcurrentHashMap`

**Goal**: Make `collectionMap` reads lock-free. Pure data structure swap, no lock removal yet.

### Changes

1. In `AbstractStorage.java`, change the field declaration (line 204):
   ```java
   // Before:
   private final Map<String, StorageCollection> collectionMap = new HashMap<>();
   // After:
   private final ConcurrentHashMap<String, StorageCollection> collectionMap = new ConcurrentHashMap<>();
   ```

2. No other changes needed — `ConcurrentHashMap` is a drop-in replacement for all operations
   used: `get()`, `put()`, `remove()`, `containsKey()`, `keySet()`, `size()`, `clear()`.

3. The `getCollectionNames()` method (line 1681) returns
   `Collections.unmodifiableSet(collectionMap.keySet())` — `ConcurrentHashMap.keySet()` returns
   a concurrent view, wrapped in unmodifiable. This is safe but now returns a **weakly
   consistent** view (may reflect concurrent mutations). This matches the desired semantics.

   **Semantic change**: With `HashMap`, callers got a snapshot of the key set at call time
   (protected by `stateLock`). With `ConcurrentHashMap`, the returned set is a live view —
   iteration may reflect concurrent DDL mutations. This is acceptable because:
   - All existing callers iterate the set immediately (no long-lived references).
   - The set was never guaranteed to be stable across DDL operations even before this change
     (it was only stable for the duration of the `stateLock.readLock()` hold).
   - After Step 6, when `stateLock.readLock()` is held for the TX duration, callers within
     a TX still have the same stability guarantee as today (DDL is blocked by lifecycle guard).

### Verification

- Run `./mvnw -pl core clean test` — all existing tests must pass.
- Run `./mvnw -pl core clean verify -P ci-integration-tests` — verify no regressions.

### Risk: None

`ConcurrentHashMap` has strictly stronger thread-safety guarantees than `HashMap`. The external
`stateLock` is still held for all accesses in this step, making this a pure safety improvement.

---

## Step 2: Replace `indexEngines` with `CopyOnWriteArrayList`

**Goal**: Make `indexEngines` reads lock-free. Pure data structure swap, no lock removal yet.

### Changes

1. In `AbstractStorage.java`, change the field declaration (line 224):
   ```java
   // Before:
   private final List<BaseIndexEngine> indexEngines = new ArrayList<>();
   // After:
   private final CopyOnWriteArrayList<BaseIndexEngine> indexEngines = new CopyOnWriteArrayList<>();
   ```

2. Review all `indexEngines` operations for COWAL compatibility:
   - `get(id)` — lock-free read, OK
   - `size()` — lock-free read, OK
   - `add(engine)` — COWAL copies array, OK (only during DDL, which is rare)
   - `set(id, null)` — COWAL copies array, OK (only during DDL)
   - `set(id, engine)` — COWAL copies array, OK
   - `clear()` — COWAL resets array, OK (only during shutdown)
   - `indexOf(engine)` at line 2128 — O(n) scan, only in `getIndexId()` which is not hot path.
     COWAL's `indexOf()` works correctly.
   - Iteration in `synch()`, `freeze()`, `closeIndexes()`, `doShutdown()` — COWAL's iterator is
     a snapshot, safe for concurrent modification.

### Verification

- Run `./mvnw -pl core clean test`
- Run `./mvnw -pl core clean verify -P ci-integration-tests`

### Risk: None

Same rationale as Step 1 — strictly stronger guarantees, lock still held.

---

## Step 3: Replace `indexEngineNameMap` with `ConcurrentHashMap`

**Goal**: Make `indexEngineNameMap` reads lock-free. Pure data structure swap.

### Changes

1. In `AbstractStorage.java`, change the field declaration (line 223):
   ```java
   // Before:
   private final Map<String, BaseIndexEngine> indexEngineNameMap = new HashMap<>();
   // After:
   private final ConcurrentHashMap<String, BaseIndexEngine> indexEngineNameMap = new ConcurrentHashMap<>();
   ```

2. Drop-in replacement — same operations used as `collectionMap`.

### Verification

- Run `./mvnw -pl core clean test`
- Run `./mvnw -pl core clean verify -P ci-integration-tests`

### Risk: None

---

## Step 4: Add read-side reentrancy to `ScalableRWLock`

**Goal**: Make `sharedLock()`/`sharedUnlock()` reentrant so nested calls on the same thread
skip all memory barriers. This is the core enabler for the performance win.

### Changes to `ScalableRWLock`

1. Add a `reentrantCount` field to `ReadersEntry`:

   ```java
   static final class ReadersEntry {
       public final AtomicInteger state;
       public int reentrantCount;  // plain int — only accessed by owning thread

       public ReadersEntry(AtomicInteger state) {
           this.state = state;
       }
   }
   ```

2. Modify `sharedLock()`:

   ```java
   public void sharedLock() {
       var localEntry = entry.get();
       if (localEntry == null) {
           localEntry = addState();
       }
       if (localEntry.reentrantCount++ > 0) {
           return;  // FAST PATH: already locked, just increment counter
       }
       // SLOW PATH: existing Dekker protocol (unchanged)
       final var currentReadersState = localEntry.state;
       while (true) {
           currentReadersState.set(SRWL_STATE_READING);
           if (!stampedLock.isWriteLocked()) {
               return;
           } else {
               currentReadersState.lazySet(SRWL_STATE_NOT_READING);
               while (stampedLock.isWriteLocked()) {
                   Thread.yield();
               }
           }
       }
   }
   ```

3. Modify `sharedUnlock()`:

   ```java
   public void sharedUnlock() {
       final var localEntry = entry.get();
       if (localEntry == null || localEntry.reentrantCount <= 0) {
           throw new IllegalMonitorStateException();
       }
       if (--localEntry.reentrantCount > 0) {
           return;  // FAST PATH: still held at outer level
       }
       localEntry.state.lazySet(SRWL_STATE_NOT_READING);
   }
   ```

   The `reentrantCount <= 0` guard prevents underflow: if `sharedUnlock()` is called more
   times than `sharedLock()`, it throws immediately rather than decrementing to a negative
   value that would leave the lock in an inconsistent state.

4. Similarly update `sharedTryLock()` and `sharedTryLockNanos()` to check reentrancy.

5. Add helper method for assertions:

   ```java
   public boolean isReadLocked() {
       var localEntry = entry.get();
       return localEntry != null && localEntry.reentrantCount > 0;
   }
   ```

### Tests

- Unit tests for `ScalableRWLock` reentrancy:
  - Nested `sharedLock()`/`sharedUnlock()` — counter tracks depth correctly
  - Nested lock does NOT trigger StoreLoad (verify via timing or counter inspection)
  - `exclusiveLock()` blocks while any thread holds nested read lock
  - `exclusiveLock()` succeeds after all nesting levels are unlocked
  - Mismatched unlock (more unlocks than locks) throws `IllegalMonitorStateException`
  - Thread termination with active reentrant count cleans up correctly
  - Concurrent stress test: multiple threads with nested reads + one writer

### Verification

- Run `./mvnw -pl core clean test`
- Existing `ScalableRWLock` tests must still pass (reentrancy is backward-compatible)

### Risk: Low

Adding reentrancy to the read side is a compatible extension. The write side
(`exclusiveLock()`) is unchanged — it still waits for all readers to drain. The Dekker
protocol correctness is unaffected because the `state` AtomicInteger still transitions
NOT_READING -> READING on the first (outermost) lock, and READING -> NOT_READING on the
last (outermost) unlock.

---

## Step 5: Introduce `ddlLock` for DDL operations

**Goal**: Add a dedicated `ReentrantLock` that serializes DDL operations (add/drop
collection/index, set collection attribute). This lock does NOT block read-path operations.

### Changes

1. Add field to `AbstractStorage.java`:
   ```java
   private final ReentrantLock ddlLock = new ReentrantLock();
   ```

2. In **each DDL method**, acquire `ddlLock` **in addition to** the existing `stateLock.writeLock()`:
   - `addCollection()` (2 overloads) — lines 1110, 1142
   - `dropCollection()` — line 1188
   - `setCollectionAttribute()` — line 4201
   - `addIndexEngine()` / `loadExternalIndexEngine()` — lines 2148, 2227
   - `deleteIndexEngine()` — line 2381

   Pattern:
   ```java
   ddlLock.lock();
   try {
       stateLock.writeLock().lock();
       try {
           // ... existing DDL logic ...
       } finally {
           stateLock.writeLock().unlock();
       }
   } finally {
       ddlLock.unlock();
   }
   ```

   This is temporary — both locks coexist during this step. The `stateLock.writeLock()` will be
   removed in a later step.

### Verification

- Run `./mvnw -pl core clean test`
- Run `./mvnw -pl core clean verify -P ci-integration-tests`

### Risk: Low

Adding an outer lock around an inner lock. Lock ordering is consistent (ddlLock always before
stateLock). No deadlock risk because no code path acquires them in reverse order.

---

## Step 6: Replace `stateLock.writeLock()` in DDL + Gremlin TX auto-close + TX-scoped read lock

**Goal**: Three changes in one atomic step:
1. Replace `stateLock.writeLock()` in DDL methods with `ddlLock` + `stateLock.readLock()`
2. Fix Gremlin traversals to auto-rollback TXs when the traversal completes
3. Pin the reentrant read lock for the duration of each transaction

These MUST be applied together. Part 1 converts DDL methods to use `readLock` + `ddlLock`,
which is a prerequisite for Part 3: `loadExternalIndexEngine()` is called during metadata
loading inside `executeInTx()` and currently uses `writeLock`. If Part 3 pins `readLock` at
TX begin before Part 1 converts DDL methods, the metadata loading path deadlocks (see
Missing Workflows §A for the full call chain analysis). Parts 2 and 3 must be together
because TX-scoped locking requires TXs to have bounded lifetimes.

### Part A: Replace `stateLock.writeLock()` in DDL with `ddlLock` + `readLock`

DDL methods no longer acquire `stateLock.writeLock()` — they use `ddlLock` +
`stateLock.readLock()` (reentrant). The write lock is reserved for lifecycle only.

DDL methods use `ddlLock` for mutual exclusion and `readLock` for lifecycle protection:

```java
public final int addCollection(...) {
    stateLock.readLock().lock();  // reentrant — lifecycle guard
    try {
        ddlLock.lock();
        try {
            checkOpennessAndMigration();
            if (collectionMap.containsKey(collectionName)) { throw ...; }
            makeStorageDirty();
            return atomicOperationsManager.calculateInsideAtomicOperation(
                atomicOperation -> doAddCollection(atomicOperation, collectionName));
        } finally {
            ddlLock.unlock();
        }
    } finally {
        stateLock.readLock().unlock();
    }
}
```

Apply to: `addCollection()` (2 overloads), `dropCollection()`, `setCollectionAttribute()`,
`addIndexEngine()`, `loadExternalIndexEngine()`, `deleteIndexEngine()`.

**`loadExternalIndexEngine()` special case**: This method accepts an external `AtomicOperation`
parameter (unlike other DDL methods that create their own via `atomicOperationsManager`). It is
called during metadata loading inside a TX (see Missing Workflows §A). The lock conversion is
the same pattern — replace `writeLock` with `readLock` + `ddlLock`. Since it accepts
an external `AtomicOperation` (it doesn't call `atomicOperationsManager.calculateInsideAtomicOperation`),
this is a straightforward lock swap:

```java
public int loadExternalIndexEngine(..., AtomicOperation atomicOperation) {
    stateLock.readLock().lock();  // lifecycle guard (reentrant inside TX)
    try {
        ddlLock.lock();
        try {
            checkOpennessAndMigration();
            // ... existing logic unchanged ...
        } finally {
            ddlLock.unlock();
        }
    } finally {
        stateLock.readLock().unlock();
    }
}
```

#### DDL Visibility Ordering

When adding a collection, update `collections` (COWAL) **before** `collectionMap`
(ConcurrentHashMap). For drops, reverse: remove from `collectionMap` first, then
`collections.set(id, null)`.

**Justification**: Readers look up collections in two ways: by name (via `collectionMap`) and
by ID (via `collections`). The `ddlLock` serializes DDL operations, but readers do not hold
`ddlLock`. Between the two non-atomic writes, a reader could observe a partial state:

- **Add (collections-first)**: A reader might see `collections[id] != null` but
  `collectionMap.get(name) == null`. This is safe because ID-based lookups are only used by
  code that already has a valid collection ID (from a record or prior name lookup). A
  name-based lookup returning `null` correctly reflects "not yet fully added."

- **Add (collectionMap-first, WRONG)**: A reader could look up by name, get the collection,
  then find `collections[id] == null` for the same ID — causing a `NullPointerException` in
  code that trusts name-based lookup implies ID-based availability.

- **Drop (collectionMap-first)**: A reader doing name lookup gets `null` (gone), while
  ID-based access still works briefly. This is safe — operations in-flight with a valid
  collection reference continue; new name-based lookups correctly see the collection as gone.

### Part B: Gremlin traversal auto-rollback

**Problem**: Gremlin traversals auto-start transactions via `tx.readWrite()` (calls
`READ_WRITE_BEHAVIOR.AUTO`) but never auto-close them. With TX-scoped reentrant locking, this
would pin the read lock indefinitely, blocking `shutdown()`.

**TinkerPop close chain** (traced from bytecode):
```
toList() / iterate() / close()
  -> DefaultTraversal.hasNext() returns false / iterate() finally block
    -> CloseableIterator.closeIterator(this)
      -> Traversal.close()              [default method]
        -> for each step: close if AutoCloseable
        -> notifyClose()                [default method — OVERRIDABLE IN DSL]
          -> DefaultTraversal: sets closed = true
```

`notifyClose()` is a `default` method on the `Traversal` interface and can be overridden
in `YTDBGraphTraversalDSL`.

**Solution**: Make `YTDBGraphStep` implement `AutoCloseable` and rollback auto-opened TXs in
its `close()` method. Use a `managedExternally` flag on `YTDBTransaction` to protect TXs
managed by `executeInTx()`/`computeInTx()`.

This is the natural place because `YTDBGraphStep.elements()` is where `tx.readWrite()` opens
the TX (line 99). The `Traversal.close()` default method iterates all steps and calls
`close()` on any that are `AutoCloseable` — so adding `AutoCloseable` to `YTDBGraphStep`
hooks into the existing traversal close chain automatically.

**Close chain with this change**:
```
toList() / iterate() / explicit close()
  -> DefaultTraversal.hasNext() returns false / iterate() finally block
    -> CloseableIterator.closeIterator(this)
      -> Traversal.close()                         [default method]
        -> for each step: if AutoCloseable, step.close()
          -> YTDBGraphStep.close()                 [NEW: rollback auto-opened TX]
        -> notifyClose()                           [sets closed = true]
```

#### Changes

1. **Add `managedExternally` flag** to `YTDBTransaction`:
   ```java
   private boolean managedExternally = false;

   public void setManagedExternally(boolean managed) {
       this.managedExternally = managed;
   }

   public boolean isManagedExternally() {
       return managedExternally;
   }
   ```
   Clear in `doCommit()` and `doRollback()` **in finally blocks** to ensure the flag is
   always reset even if commit/rollback throws:

   ```java
   @Override
   protected void doCommit() throws TransactionException {
       if (activeSession != null) {
           try {
               activeSession.commit();
           } finally {
               managedExternally = false;
               activeSession = null;
           }
       }
   }
   ```

   Apply the same `managedExternally = false` in the finally block of `doRollback()`.

2. **Make `YTDBGraphStep` implement `AutoCloseable`** and override `close()`:
   ```java
   public class YTDBGraphStep<S, E extends Element> extends GraphStep<S, E>
       implements HasContainerHolder<S, E>, AutoCloseable {

     @Override
     public void close() {
         super.close();  // closes internal iterator
         var graph = getGraph();
         var tx = graph.tx();
         if (tx.isOpen() && tx instanceof YTDBTransaction ytdbTx
             && !ytdbTx.isManagedExternally()) {
             tx.rollback();
         }
     }
   }
   ```

3. **Set `managedExternally` in `executeInTX()`/`computeInTx()`** wrappers:
   ```java
   public static <X extends Exception> void executeInTX(
       FailableConsumer<YTDBGraphTraversalSource, X> code,
       YTDBGraphTraversalSource g) throws X {
     var ok = false;
     var tx = (YTDBTransaction) g.tx();
     tx.setManagedExternally(true);
     try {
       code.accept(tx.begin(YTDBGraphTraversalSource.class));
       ok = true;
     } finally {
       tx.setManagedExternally(false);
       finishTx(ok, tx);
     }
   }
   ```
   Apply same pattern to all three `executeInTX`/`computeInTx` overloads.

#### Lifecycle examples

**Raw traversal** (`g.V().hasLabel("person").toList()`):
1. `YTDBGraphStep.elements()`: `tx.readWrite()` -> auto-opens TX
2. Traversal iterates all results
3. `hasNext()` returns false -> `Traversal.close()` -> `YTDBGraphStep.close()`
4. TX is open, not managed externally -> **rollback** -> read lock released

**`executeInTx()`** (`g.executeInTx(src -> src.V().toList())`):
1. `setManagedExternally(true)`
2. `YTDBGraphStep.elements()`: `tx.readWrite()` -> auto-opens TX
3. `toList()` iterates -> `Traversal.close()` -> `YTDBGraphStep.close()`
4. TX is open, but `managedExternally = true` -> **skip rollback**
5. `finishTx(ok=true)` -> **commit succeeds**
6. `setManagedExternally(false)`

**Partial iteration** (`try (var t = g.V()) { t.next(); }`):
1. `YTDBGraphStep.elements()`: `tx.readWrite()` -> auto-opens TX
2. `next()` returns one element -- traversal NOT exhausted
3. `close()` called by try-with-resources -> `Traversal.close()` -> `YTDBGraphStep.close()`
4. TX is open, not managed externally -> **rollback** -> read lock released

If the user does NOT close the traversal (`var x = g.V().next()` without try-with-resources),
the TX leaks -- same as any unclosed `AutoCloseable` resource. This is standard Java
resource-management semantics, not specific to this change.

### Part C: TX-scoped reentrant read lock

Since `stateLock` is a `protected` field in `AbstractStorage`, `FrontendTransactionImpl`
cannot access it directly. Add a public lifecycle guard API to `AbstractStorage`:

```java
public void acquireLifecycleReadGuard() {
    stateLock.readLock().lock();
}

public void releaseLifecycleReadGuard() {
    stateLock.readLock().unlock();
}
```

This avoids exposing the raw `stateLock` field. The method names make the intent clear
at call sites without leaking internal locking details.

1. **Transaction begin** — add lifecycle guard in `FrontendTransactionImpl.beginInternal()`:
   ```java
   void beginInternal() {
       atomicOperation = storage.startStorageTx();
       storage.acquireLifecycleReadGuard();  // outermost pin for this TX
       // ... rest of begin logic ...
   }
   ```

2. **Transaction end** — release lifecycle guard in commit/rollback cleanup:
   ```java
   void close() {
       try {
           // ... existing cleanup ...
           atomicOperation.deactivate();
           atomicOperation = null;
       } finally {
           storage.releaseLifecycleReadGuard();  // unpin
       }
   }
   ```

3. All ~52 existing `stateLock.readLock()`/`unlock()` calls in storage methods remain
   unchanged — they now hit the reentrant fast path when called within a TX.

### Verification

- Run `./mvnw -pl core clean test` — validates no regressions (DDL, TX, Gremlin paths)
- Run `./mvnw -pl core clean verify -P ci-integration-tests` — Cucumber feature tests
  (~1900 scenarios) validate Gremlin TX lifecycle thoroughly
- Specifically verify: tests that do `g.V().toList()` without explicit TX management
- Verify DDL operations (add/drop collection/index) still work under concurrent reads
- Verify metadata loading path (`SharedContext.load()` inside `executeInTx()`) does not deadlock

### Risk: Medium

**Part A (DDL lock swap)**: The `ddlLock` serializes DDL operations against each other. The
concurrent data structures make DDL-vs-read safe. `stateLock.readLock()` ensures DDL is blocked
during shutdown drain.

**Part B (Gremlin auto-rollback)**: Behavioral change. Users who rely on TXs staying open
after traversal completion will see different behavior. This is intentional — lingering
TXs are a resource leak. The `managedExternally` flag ensures `executeInTx()`/`computeInTx()`
are unaffected.

**Part C (TX-scoped read lock)**: The lifecycle guard is the main correctness-critical change.
If any code path forgets to release the guard, shutdown will hang. The `finally` block in
`FrontendTransactionImpl.close()` mitigates this.

---

## Step 7: Performance validation and cleanup

**Goal**: Verify the performance improvement and clean up any remaining issues.

### Tasks

1. **Fix `makeFuzzyCheckpoint()` status check bug**: Lines 3611 and 3618 use `||` instead
   of `&&` in the status check: `if (status != STATUS.OPEN || status != STATUS.MIGRATION)`.
   This is always true (tautology). Fix to `&&`. This should be fixed in this step (or
   earlier) to avoid confusion during testing.

2. **Benchmark**: Run JMH benchmarks in `tests/src/main/java/.../benchmarks/` to measure
   query throughput before and after the change. Focus on:
   - MATCH query benchmark (most affected — many record reads per query)
   - Index scan benchmark
   - Link traversal benchmark

3. **Review `checkOpennessAndMigration()` calls**: These remain unchanged and provide
   defense-in-depth. No removal needed.

4. **Documentation**: Update `CLAUDE.md` to reflect the new locking architecture.

### Verification

- Full CI pipeline: `./mvnw clean verify -P ci-integration-tests`
- Mutation testing pass
- Coverage gate pass

---

## Implementation Tracker

Each step = 1 commit = 1 session. Check off as completed.

| Done | Step | Description | Risk | Commit | Files | Verification |
|:----:|:----:|-------------|:----:|--------|-------|--------------|
| [x] | 1 | `collectionMap` → `ConcurrentHashMap` | None | 49050bc85f | `AbstractStorage.java` (1 line) | `./mvnw -pl core clean test` |
| [x] | 2 | `indexEngines` → `CopyOnWriteArrayList` | None | d336689f51 | `AbstractStorage.java` (1 line) | `./mvnw -pl core clean test` |
| [x] | 3 | `indexEngineNameMap` → `ConcurrentHashMap` | None | 1f338f175b | `AbstractStorage.java` (1 line) | `./mvnw -pl core clean test` |
| [x] | 4 | Add read-side reentrancy to `ScalableRWLock` | Low | 2be1c916df | `ScalableRWLock.java`, `ScalableRWLockTest.java` | `./mvnw -pl core clean test` |
| [x] | 5 | Introduce `ddlLock` (coexists with `stateLock`) | Low | 2a00e1a5d2 | `AbstractStorage.java` (~8 DDL methods) | `./mvnw -pl core clean test` then `-P ci-integration-tests` |
| [x] | 6 | DDL lock swap + Gremlin TX auto-close + TX-scoped read lock | Medium | a31032686b | `AbstractStorage.java`, `FrontendTransactionImpl.java`, `YTDBGraphFeatureTest.java`, `EmbeddedGraphFeatureTest.java` | `./mvnw -pl core clean test` then `-P ci-integration-tests` |
| [ ] | 7 | Fix `makeFuzzyCheckpoint()` bug + benchmark + cleanup | Low | | `AbstractStorage.java`, `CLAUDE.md` | `./mvnw clean verify -P ci-integration-tests` |

### Step dependencies

```
Steps 1─3 ─┐
            ├─ Step 5 ─┐
Step 4 ─────┘          ├─ Step 6 (atomic: Parts A+B+C) ── Step 7
                       │
                       └─ Part A (DDL lock swap) MUST be in same commit as Part C (TX-scoped readLock)
```

### Notes

- Steps 1-5 are purely additive (no behavior change) and can be landed independently with
  zero risk.
- Step 6 is the main behavioral change — its three parts are merged into a single atomic
  commit because Part A (DDL lock swap) is a prerequisite for Part C (TX-scoped `readLock`):
  `loadExternalIndexEngine()` is called during metadata loading inside a TX, and without
  converting it from `writeLock` to `readLock` + `ddlLock` first, the metadata loading path
  deadlocks (see Missing Workflows §A).
- Step 7 validates and fixes a pre-existing bug.

**Total changes**: ~10 lines in `ScalableRWLock`, ~2 methods + 1 field + ~8 DDL method
changes in `AbstractStorage`, ~4 lines in `FrontendTransactionImpl`, ~20 lines in
`YTDBTransaction` + `YTDBGraphStep` for Gremlin fix, 3 data structure type changes.
No new classes needed.

## Files Modified

### Core changes:
- `core/.../common/concur/lock/ScalableRWLock.java` — add reentrancy (~10 lines)
- `core/.../storage/impl/local/AbstractStorage.java` — `ddlLock` field, DDL method changes,
  `acquireLifecycleReadGuard()`/`releaseLifecycleReadGuard()` public methods
- `core/.../tx/FrontendTransactionImpl.java` — lifecycle guard at TX begin/end

### Gremlin TX fix (Step 6 Part B):
- `core/.../gremlin/YTDBTransaction.java` — `managedExternally` flag + clear in commit/rollback
- `core/.../gremlin/traversal/step/sideeffect/YTDBGraphStep.java` — implement `AutoCloseable`, rollback in `close()`

### Data structure changes (Steps 1-3):
- `core/.../storage/impl/local/AbstractStorage.java` — 3 field type changes

### Test files:
- Update: `core/.../common/concur/lock/ScalableRWLockTest.java` (add reentrancy tests)
- Existing tests should pass unchanged (behavioral equivalence)
- Cucumber feature tests (~1900 scenarios) validate Gremlin TX lifecycle

## Caller Analysis (from research)

### Write-lock method callers

| Method | Callers | Context |
|---|---|---|
| `shutdown()` | `YouTrackDBEnginesManager`, `YouTrackDBInternalEmbedded.close()` | Shutdown sequence |
| `create()` | `YouTrackDBInternalEmbedded.internalCreate()` | Database creation |
| `delete()` | `YouTrackDBInternalEmbedded.drop()` | Database deletion |
| `open()` | `YouTrackDBInternalEmbedded.internalOpen()` | Database open (4-phase sequential: read→write→read→write, see Missing Workflows §B) |
| `addCollection()` | `DatabaseSessionEmbedded.addCollection()`, `SharedContext.create()`, `SchemaEmbedded`, `DatabaseImport` | DDL via session |
| `dropCollection()` | `DatabaseSessionEmbedded.dropCollection()` | DDL via session |
| `setCollectionAttribute()` | `SchemaClassImpl.setName()` (collection rename) | DDL via schema |
| `addIndexEngine()` | `IndexAbstract.doCreate()`, `IndexAbstract.rebuild()` | DDL via index |
| `deleteIndexEngine()` | `IndexAbstract.doDelete()` | DDL via index |
| `loadExternalIndexEngine()` | `IndexAbstract.load()` via `SharedContext.load()` inside `executeInTx()` | Index loading during metadata init (see Missing Workflows §A) |
| `restoreFromBackup()` | DiskStorage-specific, called from `YouTrackDBInternalEmbedded` | Restore |

**No deadlock risks found**: No code path acquires `stateLock.readLock()` and then calls a
method that acquires `stateLock.writeLock()`. DDL callers go through session/schema layers
that do not hold any storage locks.

### TX scoping entry points

#### A. Transaction-scoped operations (get reentrant fast path via Step 6)

| Entry point | Class | Method |
|---|---|---|
| TX begin/commit/rollback | `DatabaseSessionEmbedded` | `begin()`, `commit()`, `rollback()` |
| TX wrappers | `DatabaseSessionEmbedded` | `executeInTx()`, `computeInTx()`, `executeInTxBatches()` |
| Internal commit | `DatabaseSessionEmbedded` | `internalCommit()` -> `storage.commit()` |

The `FrontendTransactionImpl` lifecycle (`beginInternal()` -> `commitInternal()`/`rollbackInternal()`)
is the natural scoping boundary.

#### B. Background tasks (no TX — existing per-method lock is the outermost call)

| Task | Class | Method | Lock interaction |
|---|---|---|---|
| Fuzzy checkpoint | `PeriodicFuzzyCheckpoint` | `run()` -> `storage.makeFuzzyCheckpoint()` | `stateLock.readLock()` via `tryLock` |
| WAL vacuum | `WALVacuum` | `run()` -> `storage.runWALVacuum()` | `stateLock.readLock()` |
| Periodic flush | `PeriodicFlushTask` | `run()` -> `wowCache.executePeriodicFlush()` | None (cache-level only) |

The first two pay the full Dekker cost once per invocation (no TX context), which is fine —
they run infrequently. `PeriodicFlushTask` operates at the cache level and does not interact
with `stateLock`.

#### C. Admin operations (no TX — per-method lock is outermost)

| Operation | Class | Method |
|---|---|---|
| Freeze | `DatabaseSessionEmbedded` | `freeze()` -> `storage.freeze()` |
| Backup | `DatabaseSessionEmbedded` | `backup()` -> `storage.backup()` |
| Synch | `AbstractStorage` | `synch()` — called from `freeze()` |

## Missing Workflows (added post-review)

### A. `loadExternalIndexEngine()` — called inside a TX during metadata loading

**CRITICAL**: `loadExternalIndexEngine()` (line 2143) acquires `stateLock.writeLock()`. It is called
from `IndexAbstract.load()` → `IndexManagerAbstract.load()` → `SharedContext.load()` →
`DatabaseSessionEmbedded.executeInTx()`. The call chain is:

```
DatabaseSessionEmbedded (line 458): executeInTx(tx -> sharedContext.load(this))
  → SharedContext.load() (line 103): database.executeInTx(tx -> indexManager.load(database))
    → IndexManagerAbstract.load() (line 211): createIndexInstance(tx, ...) → IndexAbstract constructor
      → IndexAbstract.load() (line 236): storage.loadExternalIndexEngine(metadata, props, atomicOp)
        → AbstractStorage.loadExternalIndexEngine() (line 2148): stateLock.writeLock().lock() ← DEADLOCK
```

**Currently works** because `executeInTx()` does NOT hold `stateLock` — each storage method
acquires/releases it independently. **With Step 6** (TX begin pins `stateLock.readLock()`),
`loadExternalIndexEngine()` trying `writeLock()` would deadlock the same thread.

**Solution**: `loadExternalIndexEngine()` is a **loading operation**, not user-facing DDL — it only
runs during initial metadata loading (binary format version ≤ 15) or restore. It is converted to
use `ddlLock` + `readLock` in Step 6 Part A, same as other DDL methods. Since it accepts an
external `AtomicOperation` (it doesn't call `atomicOperationsManager.calculateInsideAtomicOperation`),
this is a straightforward lock swap. See Step 6 Part A for the implementation.

**Ordering constraint**: This conversion (Part A) MUST be applied atomically with the TX-scoped
`readLock` addition (Part C). This is why Step 6 is a single atomic step with three parts.

### B. `open()` method — four-phase sequential lock pattern

The `open()` method (line 552) uses a complex sequential lock pattern that the plan should document
for completeness:

```
Phase 1 (line 554-565):  readLock — quick check if already open → release
Phase 2 (line 570-671):  writeLock — main initialization, openCollections, openIndexes → release
Phase 3 (line 676-691):  readLock — migration status verification → release
Phase 4 (line 693-710):  writeLock — final status transition to OPEN → release
```

All phases are **sequential** (each lock is fully released before the next is acquired), so there
is no nested lock or upgrade. This method is a lifecycle operation (not DDL), so it continues to
use `writeLock` and is **unaffected** by the DDL changes. No action needed, but the pattern should
be listed in the caller analysis table under "Write-lock method callers" for `open()`.

**Mid-phase unlock in Phase 2**: Inside Phase 2, `open()` temporarily releases `writeLock` to
await a migration latch (lines 575-578: `writeLock.unlock()` → `migration.await()` →
`writeLock.lock()`). During this window, another thread could call a DDL method. This is safe
because: (a) `status` is still `STATUS.MIGRATION` during the await, so `checkOpennessAndMigration()`
in any DDL method will reject the call; (b) `open()` re-acquires `writeLock` after the await and
re-checks status. After the changes, `ddlLock` does not interact with this window because `open()`
is a lifecycle operation that uses `writeLock`, not `ddlLock`.

Additionally, during Phase 2, `openIndexes()` (line 654) directly mutates `indexEngineNameMap` and
`indexEngines` (lines 789-793). This is safe because `writeLock` excludes all readers. After
Steps 1-3 (concurrent data structures), these mutations remain safe inside `writeLock`.

### C. `makeFuzzyCheckpoint()` — uses `sharedTryLockNanos()`

`makeFuzzyCheckpoint()` (line 3599) uses `stateLock.readLock().tryLock(1, TimeUnit.MILLISECONDS)`.
The plan's Step 4 mentions that `sharedTryLock()` and `sharedTryLockNanos()` need
reentrancy updates but does not provide the implementation.

**Pre-existing bug**: `makeFuzzyCheckpoint()` line 3611 has a status check:
```java
if (status != STATUS.OPEN || status != STATUS.MIGRATION) {
```
This condition is always `true` (a value cannot be both non-OPEN and non-MIGRATION
simultaneously is false — the `||` makes it a tautology). This should be `&&`:
```java
if (status != STATUS.OPEN && status != STATUS.MIGRATION) {
```
The same bug exists at line 3618. This should be fixed as a prerequisite or in the same PR
to avoid confusion during the lock changes. The bug causes `makeFuzzyCheckpoint()` to return
early after acquiring the lock (line 3618 check), which means it effectively never performs
the checkpoint when the storage is OPEN — but this may be masked by the fact that the retry
loop at line 3601 re-acquires the lock and the status check at 3618 has the same bug.

Here is the required implementation for `sharedTryLock()`:

```java
public boolean sharedTryLock() {
    var localEntry = entry.get();
    if (localEntry == null) {
        localEntry = addState();
    }
    if (localEntry.reentrantCount > 0) {
        localEntry.reentrantCount++;
        return true;  // FAST PATH: already locked
    }
    // SLOW PATH: existing Dekker protocol (unchanged)
    final var currentReadersState = localEntry.state;
    currentReadersState.set(SRWL_STATE_READING);
    if (!stampedLock.isWriteLocked()) {
        localEntry.reentrantCount = 1;
        return true;
    } else {
        currentReadersState.lazySet(SRWL_STATE_NOT_READING);
        return false;
    }
}
```

And for `sharedTryLockNanos()`:

```java
public boolean sharedTryLockNanos(long nanosTimeout) {
    var localEntry = entry.get();
    if (localEntry == null) {
        localEntry = addState();
    }
    if (localEntry.reentrantCount > 0) {
        localEntry.reentrantCount++;
        return true;  // FAST PATH: already locked
    }
    // SLOW PATH: existing Dekker protocol with timeout (unchanged)
    final var lastTime = System.nanoTime();
    final var currentReadersState = localEntry.state;
    while (true) {
        currentReadersState.set(SRWL_STATE_READING);
        if (!stampedLock.isWriteLocked()) {
            localEntry.reentrantCount = 1;
            return true;
        } else {
            currentReadersState.lazySet(SRWL_STATE_NOT_READING);
            if (nanosTimeout <= 0) {
                return false;
            }
            if (System.nanoTime() - lastTime < nanosTimeout) {
                Thread.yield();
            } else {
                return false;
            }
        }
    }
}
```

Note: On the slow path success, `reentrantCount` must be set to 1 (not incremented) since this
is the outermost acquisition.

## Resolved Questions

1. ~~**`freeze()`/`release()` interaction**~~ **RESOLVED**: `freeze()` calls
   `atomicOperationsManager.freezeWriteOperations()` which sets `freezeRequests > 0` in the
   `OperationsFreezer`. Any subsequent DDL calling `calculateInsideAtomicOperation()` ->
   `startToApplyOperations()` -> `OperationsFreezer.startOperation()` will **park** waiting
   for unfreeze. If DDL is already in-flight, `freezeWriteOperations()` waits for
   `operationsCount` to drop to 0. So DDL is correctly blocked during freeze through the
   `OperationsFreezer` -- no `ddlLock` interaction needed. The two mechanisms operate at
   different levels: `ddlLock` serializes DDL-vs-DDL, `OperationsFreezer` blocks all writes
   (including DDL) during freeze. Note: a DDL caller holding `ddlLock` may be parked inside
   `OperationsFreezer.startOperation()` during freeze. This is safe because `ddlLock` is a
   `ReentrantLock` — the parked thread holds it but no other DDL can proceed anyway (freeze
   blocks all writes). When unfreeze happens, the parked DDL thread resumes normally.

2. ~~**Cleaner interaction with reentrancy**~~ **RESOLVED**: The `ReadersEntry` cleanup action
   sets `state.set(SRWL_STATE_NOT_READING)`. After adding `reentrantCount`, if a thread dies
   while holding a reentrant lock (count > 0), the Cleaner correctly resets state to
   NOT_READING (unblocking writers). The orphaned `reentrantCount` value is irrelevant because
   the `ReadersEntry` is unreachable and the `ThreadLocal` reference is gone. No action needed.

3. ~~**Other background tasks**~~ **RESOLVED**: Beyond `PeriodicFuzzyCheckpoint` and `WALVacuum`,
   only `PeriodicFlushTask` exists — it operates at the `WOWCache` level (not `AbstractStorage`
   level) and does not interact with `stateLock`. No impact from this plan.
