<!--
MANIFEST
dimension: performance
prefix: PF
step: 4
track: 4
iteration: 1
commit_range: bc7eba6da8~1..bc7eba6da8
verdict: PASS
blocker_count: 0
should_fix_count: 0
suggestion_count: 3
findings:
  - id: PF1
    sev: suggestion
    anchor: "#pf1-threadlocalget-on-getindexengine-pure-data-index-apply-path"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:3646
    cert: C1
    basis: "PSI find-usages: getIndexEngine(int) has 44 usages; the production index-apply callers are IndexAbstract.acquireAtomicExclusiveLock/getStatistics/getHistogram"
  - id: PF2
    sev: suggestion
    anchor: "#pf2-nextfreecollectionid-linear-scan-per-created-collection"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:1071
    cert: C2
    basis: "Source read: O(collections.size()) scan called once per created collection inside reconcileCollections"
  - id: PF3
    sev: suggestion
    anchor: "#pf3-full-schema-reserialize-and-reparse-under-the-write-lock"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:931
    cert: C3
    basis: "Source read + D19/D12 contract: toStream + fromStream of the whole schema run once per schema commit under stateLock.writeLock()"
evidence_base: "## Evidence base"
cert_index:
  - C1
  - C2
  - C3
flags: []
-->

## Findings

### PF1 [suggestion] ThreadLocal.get() on getIndexEngine, the pure-data index-apply path

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 3646)

**Issue**: `getIndexEngine(int)` now begins with `if (isCommitWindowActive())`, which
is one `ThreadLocal.get()` plus an array deref plus an int compare on every call. Three
of the four methods this step makes window-aware are cold (`isClosed` is pool-lifecycle,
`getCollectionNames` is rare, `getCollectionIdByName` is per-name not per-record). But
`getIndexEngine` is the one on a write-workload hot path: PSI find-usages shows the
production callers are `IndexAbstract.acquireAtomicExclusiveLock` (the per-index-operation
commit-apply lock acquire), `getStatistics`, and `getHistogram`. On a pure-data commit
that touches K indexes, `acquireAtomicExclusiveLock` runs K times, so the consult is per
index op, not per commit.

**Evidence**:

```
COST TRACE for getIndexEngine at AbstractStorage.java:3646:
  OPERATION: isCommitWindowActive() -> commitWindowDepth.get()[0] > 0
  COMPLEXITY: O(1) per call
  DATA SCALE: one ThreadLocal lookup; the int[1] cell is allocated once per worker
    thread by withInitial and never remove()'d on the read path (remove() lives only in
    exitCommitWindow on the schema-commit branch), so a pure-data worker allocates it
    once and reuses it for the process lifetime.
  ALLOCATIONS: zero per call after the first call on a given thread.
  LOCK HOLD TIME: unchanged on the pure-data path (the consult runs before the readLock).
```

```
SCALE CHECK:
  AT SMALL SCALE: negligible.
  AT MEDIUM/PRODUCTION SCALE: a ThreadLocal.get() is a thread-map hash probe — cheap, but
    not free, and it sits ahead of the readLock acquire on a per-index-op path. It does
    not change complexity or allocation behavior; the read path stays lock-bounded as
    before.
  VERDICT: NEGLIGIBLE (reported as a suggestion, not because it MATTERS, but to record
    that the one genuinely hot window-aware method was checked and cleared).
```

**Impact**: Sub-nanosecond-class per-call cost on the index-apply path; no allocation, no
complexity change, the pure-data fast-path contract (I-U5) holds.

**Suggestion**: No change required. If a future profile ever flags this, the depth could
be cached in a field already read on the commit path, but that trades a clean per-thread
design for micro-savings that are not in evidence. Leave as is.

### PF2 [suggestion] nextFreeCollectionId linear scan per created collection

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 1071)

**Issue**: `nextFreeCollectionId()` scans `collections` from index 0 for the first null
slot, and `reconcileCollections` calls it once per provisional collection it resolves. A
commit that creates C classes (each owning up to ~8 collections for a vertex class) calls
it C×8 times, each an O(collections.size()) scan, so the allocator is O(creates ×
total-collection-count) per schema commit.

**Evidence**:

```
COST TRACE for nextFreeCollectionId at AbstractStorage.java:1071:
  OPERATION: linear scan of the shared collections ArrayList for the first null slot
  COMPLEXITY: O(M) per call, M = collections.size(); O(creates x M) per schema commit
  DATA SCALE: M = total collections in the database (grows with schema size, not data)
  ALLOCATIONS: none
  LOCK HOLD TIME: runs under the held stateLock.writeLock(), so the scan extends the
    write-lock hold for the schema commit's duration.
```

```
SCALE CHECK for a schema commit creating C classes against a DB with M collections:
  AT SMALL SCALE (M ~ tens): negligible.
  AT PRODUCTION SCALE (M ~ thousands of collections, a multi-class migration creating
    dozens of classes): the scan restarts from 0 each call and re-walks the same occupied
    prefix, so it is quadratic in the create count for a given M. Still bounded by the
    low schema-change rate (D19) and runs while concurrent data commits are excluded
    anyway, so the absolute cost is small and the contention window is the dominated term.
  VERDICT: MATTERS AT SCALE only for a pathological many-class single commit; NEGLIGIBLE
    for the expected one-or-few-class schema change.
```

**Impact**: Extends the exclusive write-lock hold on a many-class schema commit by a
quadratic-in-create-count factor; invisible for the common single-class change.

**Suggestion**: Optional. If a many-class single-commit path ever matters, resume the
scan from the last returned index (the slots below it were just filled in this
reconciliation, as the method's own Javadoc notes the publish-before-next-scan
invariant), turning O(creates × M) into roughly O(M + creates). Not worth doing now —
the schema-change rate is the load-bearing bound and the lock already excludes data
commits for the window.

### PF3 [suggestion] Full schema re-serialize and re-parse under the write lock

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 931, promotion; line 677, serialization)

**Issue**: A schema commit serializes the whole tx-local schema (`txLocalSchema.toStream`)
and, on success, re-parses the whole committed schema from disk
(`committedSchema.fromStream(session, committedRoot)` plus one `forceSnapshot`), both
inside the held `stateLock.writeLock()`. The per-class record format (Track 2) means
`toStream` only writes changed records via dirty-mark suppression, but the in-memory
`fromStream` re-parse rebuilds every class's derived state (inheritance,
`polymorphicCollectionIds`, subclass sets) for the whole schema, not just the changed
class. With a large schema this is the dominant CPU cost of the commit, and it runs while
data commits are excluded.

**Evidence**:

```
COST TRACE for promotion at AbstractStorage.java:931:
  OPERATION: session.load(root) + fromStream(whole schema) + forceSnapshot()
  COMPLEXITY: O(total classes) re-parse per schema commit (independent of which class
    changed); the load reads only the root + changed per-class records (Track 2 format),
    but the in-memory ripple recompute touches every class.
  DATA SCALE: total class count in the schema
  ALLOCATIONS: a fresh SchemaShared object graph for the re-parse (proportional to schema
    size); plus getProvisionalCollectionNames() one unmodifiable-wrapper allocation and
    getRealCollectionIds() two IntOpenHashSet allocations per schema commit.
  LOCK HOLD TIME: the full re-parse runs inside stateLock.writeLock() with the commit
    window open, so it is part of the data-commit exclusion window.
```

```
SCALE CHECK:
  AT SMALL/MEDIUM SCHEMA: negligible.
  AT LARGE SCHEMA (hundreds-to-thousands of classes): the whole-schema re-parse is the
    commit's dominant cost and the exclusion window's length. This is exactly the cost
    D8/D19/D12 accept by design: a full working SchemaShared copy "reuses the existing
    mutation machinery ... the copy is cheap and rare", and the write-lock hold is
    "bounded by the low schema-change rate."
  VERDICT: MATTERS AT SCALE, but it is a designed and accepted cost (D8, D19, D12), not a
    regression this step introduces — pre-step schema changes already rewrote the whole
    monolithic schema record per change.
```

**Impact**: On a large schema, the exclusive write-lock hold per schema commit scales with
total class count, not change size. Accepted per D8/D19; bounded by the schema-change
rate.

**Suggestion**: No change for this track. The design explicitly chose the full-copy /
full-re-parse model for correctness (free derived-state ripple) over an incremental
promotion. If large-schema schema-commit latency ever becomes a profile target, an
incremental promote-only-changed-classes path would be a separate optimization (out of
scope; would touch D8). Record it and move on.

## Evidence base

#### C1: getIndexEngine is the only window-aware method on a write-workload hot path; its added consult is a cached-ThreadLocal O(1) read with no allocation

CONFIRMED-as-non-issue (survived refutation): PSI find-usages on
`AbstractStorage.getIndexEngine(int)` returned 44 usages; the production index-apply
callers are `IndexAbstract.acquireAtomicExclusiveLock` (line 930), `getStatistics`
(944), `getHistogram` (956), `analyzeHistogram` (968), `setBulkLoading` (383),
`buildHistogramAfterFill` (413). The `int[1]` cell is allocated once per thread by
`ThreadLocal.withInitial` and `remove()` is reached only from `exitCommitWindow` on the
schema-commit branch, so a pure-data worker never re-allocates it.

#### C2: nextFreeCollectionId is an O(M) restart-from-zero scan called once per created collection, but gated behind the rare schema-commit branch

The claim that this is a per-record / per-query hot-path cost was refuted: `reconcileCollections`
(the sole caller) runs only inside `commitSchemaCarry`, which is entered only when
`session.getTxSchemaState() != null` (the `schemaCarry` branch at AbstractStorage.java:2298).
The pure-data commit branch (line 2302) never reaches it. The residual cost is a
quadratic-in-create-count scan confined to a single many-class schema commit, which the
low schema-change rate (D19) and the already-exclusive write lock dominate. Survives only
as a documented micro-optimization opportunity, not an issue.

#### C3: the whole-schema toStream/fromStream under the write lock is a designed, accepted cost, not a step-introduced regression

The claim that the full re-parse is a performance defect was refuted against the frozen
design. D8 explicitly chose "a full working SchemaShared copy [that] reuses the existing
mutation machinery, which recomputes the derived-state ripple ... for free; the copy is
cheap and rare." D19 accepts that "a schema commit excludes concurrent data commits for
its duration, bounded by the low schema-change rate," and D12 accepts the in-commit stall.
The pre-step path already rewrote the whole monolithic schema record on every schema
change, so the per-class format (Track 2) plus selective write (Step 5) is a net write-
amplification *reduction*, not an increase. The in-memory re-parse ripple is the
correctness price of free derived-state recomputation. Survives only as a recorded
large-schema scaling note.

#### Pure-data fast-path verification (the I-U5 performance contract)

The performance contract for this step is that the pure-data commit path must be
unaffected. Confirmed: the pure-data branch (AbstractStorage.java:2302) takes
`stateLock.readLock()` exactly as before and delegates to `applyCommitOperations(..., null,
...)`. The refactor extracts the former inline body into `computeCommitWorkingSet` /
`applyCommitOperations`; the only behavioral delta on the pure-data path is one extra
`CommitWorkingSet` record allocation per commit (holding references to the four
collections that were already allocated before — TreeMap, IdentityHashMap, TreeSet,
plus the relocated `result` ArrayList). That is one object per commit, not per record;
the per-record position-allocation and `commitEntry` loops are byte-for-byte the prior
logic. No per-record allocation, lock, or complexity change was introduced on the
pure-data path.
