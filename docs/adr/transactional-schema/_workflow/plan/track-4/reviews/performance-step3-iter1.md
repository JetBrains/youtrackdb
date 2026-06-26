<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: PF1, sev: suggestion, loc: AbstractStorage.java:391, anchor: "### PF1 ", cert: C1, basis: "Per-read fast path gains one barrier-free ThreadLocal.get() + array load + branch; negligible next to the existing read-lock acquire it sits beside"}
evidence_base: {section: "## Evidence base", certs: 4, matches: 4}
cert_index:
  - {id: C1, verdict: CONFIRMED-as-non-issue, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED-as-non-issue, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED-as-non-issue, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED-as-non-issue, anchor: "#### C4 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [suggestion] Per-read fast-path cost is one barrier-free ThreadLocal read; perf-neutral, not a regression

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 391, 970-973, 4786-4789)

**Issue**: The step adds an `isCommitWindowActive()` branch to two hot read methods,
`getPhysicalCollectionNameById` and `readRecordInternal`. The focus question asks
whether the pure-data path's common-case cost is a single cheap `ThreadLocal` read
or whether there is avoidable work. It is a single cheap read, and the fast path is
unchanged in every other respect, so the change is perf-neutral on the pure-data path.
This is recorded as a suggestion-level confirmation, not a defect: no action is
required.

The new per-read work on the pure-data path is, in full:

- one `commitWindowDepth.get()` — a `ThreadLocal.get()`;
- one `int[]` element load (`[0]`) — a plain array read, no memory barrier;
- one integer compare against zero and one branch;
- reuse of the cached `final boolean lockFree` in the `finally` (no second
  `ThreadLocal.get()`).

The result is cached in a local, so `ThreadLocal.get()` runs exactly once per read,
not once on entry and once on exit. On the pure-data path `lockFree` is false, so
`stateLock.readLock().lock()` / `unlock()` still run exactly as before — the lock
behavior is byte-for-byte the old fast path with one branch in front of it.

**Evidence**: see `## Evidence base` C1 (hot-path premise, PSI), C2 (cost trace),
C3 (comparison to the existing read-lock cost), C4 (scale validation).

COST TRACE for the pure-data fast path (`getPhysicalCollectionNameById` /
`readRecordInternal`):
  OPERATION: one `ThreadLocal.get()`, one `int[]` element load, one compare+branch,
    then the unchanged `stateLock.readLock()` acquire/release.
  COMPLEXITY: O(1) per read, additive constant.
  ALLOCATIONS: zero on the read path. The `int[1]` holder is allocated once per
    thread by `ThreadLocal.withInitial`, on first window use, never on a read.
  LOCK HOLD TIME: unchanged — the read lock is still held for exactly the same body.
  I/O: none added.

SCALE CHECK:
  AT PRODUCTION SCALE (1M+ reads/sec): one extra barrier-free `ThreadLocal.get()`
    plus an array load and a predictable branch is a single-digit-nanosecond constant
    with no allocation and no fence; it is dwarfed by the read-lock acquire that runs
    on the same path. VERDICT: NEGLIGIBLE.

**Impact**: No measurable latency, throughput, GC, or memory effect on the pure-data
read path. The change meets the "perf-neutral-to-positive on the pure-data path" bar
the step set.

**Suggestion**: No change required. Two optional, non-blocking notes for the
implementer or a later step:

- The `ThreadLocal<int[]>` with an `int[1]` holder is the right idiom: incrementing
  `depth[0]` mutates the array in place and avoids a `ThreadLocal.set()` map write on
  every `enterCommitWindow()` / `exitCommitWindow()`. Keep it; a plain
  `ThreadLocal<Integer>` would box and re-`set()` on each enter/exit and be strictly
  worse. No action.
- `enterCommitWindow` / `exitCommitWindow` have no production call site yet (PSI shows
  only Javadoc `{@link}` references and tests — C1). The per-read branch is therefore
  always taken on the false arm today, so even the commit-window path carries no read
  cost until a later step wires the commit. When that step lands, the lock-free read it
  enables is strictly faster than the deadlock it replaces on that (rare, schema-commit)
  path, so the net effect stays neutral-to-positive. No action this step.

## Evidence base

#### C1 Hot-path premise — confirmed via PSI find-usages. CONFIRMED-as-non-issue.
`getPhysicalCollectionNameById` and `readRecordInternal` are on hot read paths.
PSI `ReferencesSearch` (mcp-steroid, project `transactional-schema-b4l1mcdq`,
verified open and matching the working tree via `steroid_list_projects`) shows:
`getPhysicalCollectionNameById` is called from `DatabaseSessionEmbedded.executeReadRecord`,
`executeExists`, and `getCollectionNameById` — the per-record-read and per-existence-check
entry points. `readRecordInternal` is called from `AbstractStorage.readRecord` — the
record-read path on a cache miss. The remaining `getPhysicalCollectionNameById` /
`readRecordInternal` references PSI attributes to `enterCommitWindow` are Javadoc
`{@link}` targets, not call sites (PSI resolves Javadoc links as references). The
hot-path classification is therefore correct, which is exactly why per-read overhead
is worth tracing — and the trace clears it.

#### C2 Cost trace of the added fast-path work. CONFIRMED-as-non-issue.
On the pure-data path `isCommitWindowActive()` returns false and the read lock is
still taken. The added work is one `commitWindowDepth.get()` (`ThreadLocal.get()`),
one `int[]` element load, one compare, one branch, and reuse of the cached
`final boolean lockFree` in `finally`. An `int[]` element read carries no memory
barrier (unlike a volatile field), so no StoreLoad/StoreStore fence is added. The
result is cached, so `ThreadLocal.get()` runs once per read, not twice. Zero
allocation on the read path: the `int[1]` holder comes from `ThreadLocal.withInitial`
once per thread on first window use, never on a read. The lock hold time and the
locked body are unchanged.

#### C3 Comparison to the existing per-read read-lock cost. CONFIRMED-as-non-issue.
`stateLock` is a `ScalableRWLock`. Its `sharedLock()` (the existing per-read acquire,
still run on the pure-data path) already does `entry.get()` (a `ThreadLocal.get()`),
then `currentReadersState.set(SRWL_STATE_READING)` — a full volatile write with a
StoreLoad barrier, the most expensive fence on x86 — then `stampedLock.isWriteLocked()`
(a volatile read); `sharedUnlock()` does another `entry.get()` plus a `lazySet`. The
step's new branch (one barrier-free `ThreadLocal.get()` + array load) is small next to
the barrier-carrying lock acquire it sits beside. The change is additive and perf-neutral,
not a regression. It is not perf-positive on the pure-data path either: that path never
skips the lock, so no read-lock cost is removed there.

#### C4 Scale validation. CONFIRMED-as-non-issue.
At 1M+ reads/sec the added per-read cost is a single-digit-nanosecond constant: one
`ThreadLocal.get()`, one array load, one predictable branch, no allocation, no fence.
It is dominated by the read-lock acquire on the same path and by the actual record I/O
the read performs. Verdict NEGLIGIBLE at every scale. The step's perf-neutral-to-positive
goal for the pure-data path is met; no regression is introduced.
