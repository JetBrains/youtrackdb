<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 7, matches: 7}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
flags: [CONTRACT_OK]
-->

## Findings

No crash-safety or durability defects survived verification. The step adds no
WAL record types, changes no recovery path, and keeps every durability-relevant
mutation inside the existing commit atomic operation. The one commit-path change
(`AbstractStorage.applyCommitOperations` line 2616-2626) is a purely in-memory
snapshot rebuild whose failure rides the commit's already-tested rollback and
undo path. All seven crash-safety hypotheses traced below refuted to safe.

## Evidence base

#### C1 — A throw at the commit-path snapshot rebuild (line 2625) rides the failure path — REFUTED (safe)
Hypothesis: the new `session.getMetadata().rebuildThreadLocalSchemaSnapshot()`
at `AbstractStorage.java:2625` runs inside the open commit window; a throw there
could strand the atomic operation or leak the in-memory registry mutations that
`reconcileCollections` and `enrollReconciledIndexRecords` already applied.
Trace: the call sits inside the `try` opened at line 2516, after
`startTxCommit(atomicOperation)` (2515). `rebuildThreadLocalSchemaSnapshot`
throws only `IllegalStateException` (a `RuntimeException`) or whatever
`makeSnapshot()` raises (also runtime). Either is caught by the
`catch (IOException | RuntimeException | AssertionError e)` at line 2735, which
sets `error` and rethrows. The `finally` at 2754 then runs `rollback(error,
atomicOperation)` (WAL revert of every buffered structural and record write) and,
because `structurePublished` was set `true` at line 2522 before reconciliation,
runs the full in-memory undo: `undoReconciledCollections` (2763),
`undoReconciledIndexEngines` (2772), `restoreReconciledDroppedIndexEngines`
(2780), and `undoAppliedMembership` (2784). The rebuild is placed after
reconciliation and enrollment but before any record allocation or `commitEntry`,
so no record write has happened yet at the throw point. A crash at that exact
instant leaves the WAL without an end record, so open-time replay discards the
whole atomic unit. Verdict: the throw path is the same one Track 4 and Step 2
already exercise; no new durability gap.

#### C2 — The held-pin guard could spuriously throw and fail every class-creating commit — REFUTED (safe)
Hypothesis: `rebuildThreadLocalSchemaSnapshot` throws when `immutableCount == 0`
(`MetadataDefault.java:118`); if the commit reached line 2625 with no pin held,
every class-creating commit would fail. Trace (PSI find-usages of
`makeThreadLocalSchemaSnapshot`): the commit pins the snapshot once at entry,
`AbstractStorage.commit` line 2339, before both the schema-carry and pure-data
branches, so `immutableCount >= 1` by the time control reaches line 2625. The
matching decrement is `clearThreadLocalSchemaSnapshot` in the outermost `finally`
of `applyCommitOperations` (line 2865), which runs on every exit. The rebuild
swaps `immutableSchema` in place and leaves the count untouched, so pin and unpin
stay balanced across the rebuild. `commitSucceedsWhenTheTransactionCreatesAClass...`
in the new test passes, confirming the guard does not fire on the live path.
Verdict: the guard is satisfied by construction on the commit path.

#### C3 — A provisional collection id (`<= -2`) could reach durable bytes — REFUTED (safe)
Hypothesis: an entity of a tx-created class carries a deferred collection
(`DatabaseSessionEmbedded` returns `RID.COLLECTION_ID_INVALID`); if the pinned
snapshot were still stale at the working-set build, `getCollectionForNewInstance`
would hand back a provisional id and a record could be written to it. Trace: two
independent guards close this. First, the rebuild at 2625 (gated on
`!getResolvedCollectionIds().isEmpty()`, non-empty exactly when reconciliation
created a real collection for a provisional one) refreshes the pinned snapshot so
`computeCommitWorkingSet` (`AbstractStorage.java:2459-2465`) resolves the
reconciled real id. Second, even absent the rebuild, `doGetAndCheckCollection`
(line 2070) calls `checkCollectionSegmentIndexRange(collectionId)` which rejects
a negative id before any allocation or write, so a provisional id is a fail-stop
(rollback), never a silent write. Sentinel disjointness confirmed:
`isProvisionalCollectionId` is `<= -2` (`SchemaShared.java:114`, ceiling `-2`)
while `RID.COLLECTION_ID_INVALID == -1` (`RID.java:32`), so the deferral sentinel
is never mistaken for a provisional id. Verdict: no provisional id can reach
durable bytes; worst case is a clean rollback.

#### C4 — The non-atomic `version++` on a volatile field is a durability hazard — REFUTED (safe)
Hypothesis: `SchemaShared.resolveProvisionalCollectionIds` now does `version++`
(`SchemaShared.java:570`, `NonAtomicOperationOnVolatileField` suppressed); a lost
or torn increment could strand a stale-version cache across recovery. Trace: the
call at `AbstractStorage.java:2543-2545` runs on the tx-local `SchemaShared` copy,
a session-private instance discarded at commit end, under its own write lock, so
there is no concurrent writer to race the increment. `version` is an in-memory
cache-generation counter for snapshot invalidation; it is never serialized and is
re-derived from a freshly parsed schema on reopen, so it carries no recovery
state. It is not an LSN and does not gate any WAL or page apply. Verdict: purely
in-memory cache-invalidation bookkeeping; no durability or recovery impact. (Any
residual concern is a concurrency-dimension question, not crash safety.)

#### C5 — The rebuild does record I/O or page/WAL writes inside the atomic op — REFUTED (safe)
Hypothesis: `rebuildThreadLocalSchemaSnapshot` -> `SchemaProxy.makeSnapshot()` ->
(tx-aware branch, since `txState != null`) -> `txState.getTxLocalSchema()
.makeUncachedSnapshot(session)` could load records or touch pages while the commit
holds the write lock and the atomic operation is open. Trace: `makeUncachedSnapshot`
(`SchemaShared.java:360-367`) acquires the tx-local schema read lock and returns
`new ImmutableSchema(this, session)`, an in-memory projection of the class graph;
it performs no `session.load`, no page read, and no WAL append. The tx-local write
lock taken at 2546 is released at 2580, so the read-lock acquire at rebuild time
does not self-conflict. Even if a snapshot build did call `session.load`, the
commit window is open (`enterCommitWindow` at 2908), which routes such reads through
the lock-free substrate rather than re-entering the non-reentrant `stateLock`.
Verdict: the rebuild contributes nothing to the atomic operation's WAL footprint.

#### C6 — The guard `!getResolvedCollectionIds().isEmpty()` could miss a case that needs the rebuild — REFUTED (safe)
Hypothesis: a schema-carry commit that needs a fresh pinned snapshot before the
working-set build might not satisfy the guard, leaving a stale snapshot. Trace:
`getResolvedCollectionIds()` is the provisional->real map populated by
`reconcileCollections` (line 2527), non-empty exactly when the transaction created
at least one collection (the only source of provisional ids, `<= -2`). The pinned
snapshot's only staleness relative to the working-set build is a provisional
collection id; `toStream` and `enrollReconciledIndexRecords` between the pin and
the build do not change class or collection structure. When no collection was
created, the pinned snapshot carries only real ids and the working-set build is
correct without a rebuild. The guard therefore matches the need precisely. A
provisional id created but never resolved would trip the `allCollectionIdsResolved`
assert (`SchemaShared.java:581`) inside `resolveProvisionalCollectionIds`, a
fail-stop that routes to rollback. Verdict: no missed rebuild case.

#### C7 — The committer-side promotion read race is a durability regression — REFUTED (safe, out of dimension)
Hypothesis: the track's Surprises log flags that the committer's own promotion read
(`fromStream` -> `session.load`, `AbstractStorage.java:2843`) races concurrent
readers and can drive the storage to error state under sustained concurrent schema
commits, and asks Step 3 to account for it. Trace: this diff does not modify the
promotion block (2841-2859) — it is unchanged Track 4 code. The failure mode is a
throw in promotion caught at 2846, which logs and calls `setInError`. Crucially the
commit is already durable at that point (`endTxCommit` at 2800 flushed the WAL and
applied the pages), and the recovery contract is that a reopen re-parses the schema
from the durable records, so no data is lost or corrupted — the divergence
self-corrects on reopen. Verdict: this is an availability/concurrency boundary
(YTDB-1101 shape), not a crash-safety or durability defect, and it is neither
introduced nor worsened by this step. Left to the concurrency reviewer / Track 7.
