<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 4, matches: 4}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
flags: [CONTRACT_OK]
-->

## Findings

No performance findings. The step is performance-clean: every added check
sits off the per-record path or on the rare schema-carry commit branch, and
the plan-cache bypass is a per-session early-return that never touches the
shared cache for other sessions. The refutation traces for each focal
question are in the Evidence base below.

## Evidence base

Each cert below is a potential-concern hypothesis run through a cost trace and
a scale check. All four were refuted (no issue), so each is rendered in full
per the YTDB-1069 roster rule (a refuted claim appears in full; only a
survived-as-issue claim compresses to one line). Reference-accuracy facts
(caller sets, method bodies) are PSI-backed; mcp-steroid was reachable and the
open project `transactional-schema-b4l1mcdq` matches the working tree.

#### C1 Plan-cache bypass adds negligible per-query cost — REFUTED (focal Q1a)
CLAIM TESTED: the new `db.getTxSchemaState() != null` guard in
`YqlExecutionPlanCache.getInternal`/`putInternal` slows the common no-tx fast
path.

COST TRACE for `YqlExecutionPlanCache.getInternal` (YqlExecutionPlanCache.java:140)
and `putInternal` (:99):
- OPERATION: `getTxSchemaState()` = `getTransactionInternal()` (returns the
  `currentTx` field; the leading `assertIfNotActive()` is `-ea`-only) +
  `tx.isActive()` (boolean) + `tx.getCustomData(TX_SCHEMA_STATE_KEY)`, whose
  body is `userData.get(iName)` (a plain `Map.get`, no lock, no allocation).
- COMPLEXITY: O(1) per invocation; one map lookup at worst.
- INVOCATION UNIT: PSI find-usages shows `YqlExecutionPlanCache.get` has 4
  callers, all `createExecutionPlan` (Select/Match/CreateEdge/DeleteEdge
  planners). The probe runs once per query planning, never per record.

SCALE CHECK: at 1M queries the guard adds ~1M map lookups (tens of ms of CPU
total), dwarfed by the planning and scan work each query already does.
Negligible at every scale. The pre-existing per-call
`getConfiguration().getValueAsLong(COMMAND_TIMEOUT)` at :128 is a heavier
constant on the same path and is untouched by this step.

#### C2 Bypass does not poison or evict entries for concurrent sessions — REFUTED (focal Q1b)
CLAIM TESTED: a schema-tx session's bypass corrupts or drops cache entries
that concurrent pure-data sessions rely on.

TRACE: both bypasses are pure early-returns. `getInternal` returns `null`
(the caller re-plans) and `putInternal` returns without calling `cache.put`.
Neither calls `invalidate()`, `cache.invalidateAll()`, or any eviction. The
guard keys on `db.getTxSchemaState()`, which is per-session transaction state,
so only the schema-tx session skips the cache; a concurrent pure-data session
sees `getTxSchemaState() == null` and reads/writes the shared Guava cache
unchanged. The whole-cache invalidation on schema commit
(`onSchemaUpdate`/`onIndexManagerUpdate` → `invalidate()`) is pre-existing and
outside this step. No poisoning, no cross-session eviction.

#### C3 checkCollectionLimits and iterator/scan-set changes add no per-record or per-query cost — REFUTED (focal Q2)
CLAIM TESTED: relaxing `checkCollectionLimits` and the provisional-id iterator
branches cost per-record or per-query time outside a schema tx.

COST TRACE for `RecordIdInternal.checkCollectionLimits` (RecordIdInternal.java:69):
- The change is comparison-constant-only: `collectionId < -2` became
  `collectionId < Short.MIN_VALUE`. Both are single integer compares against a
  compile-time constant; the branch structure and the second `> COLLECTION_MAX`
  check are identical. The new error-message string concatenation is on the
  throw path only, never on the happy path.
- FREQUENCY: PSI find-usages returns 14 references; the production callers are
  `RecordId` constructor, `RecordIdInternal.fromString`, and
  `ChangeableRecordId.setCollectionId`/`setCollectionAndPosition`. The
  `RecordId` constructor is per-record on scan paths, so this method is hot —
  but its cost is unchanged (same two compares).

COST TRACE for the iterator/scan-set edits:
- `RecordIteratorCollection.initialize` (:107) and `initStorageIterator`
  (:218) add `SchemaShared.isProvisionalCollectionId(collectionId)` (a single
  `<= -2` compare). Both run once per iterator setup, not per record; the
  per-record `hasNext()` loop is untouched.
- `FetchFromClassExecutionStep` constructor loop (:110) and `canBeCached()`
  (:218) add `isProvisionalCollectionId` checks that run once per plan
  construction, O(collections-per-class), not per record.

SCALE CHECK: outside a schema tx no provisional id exists, so every added
predicate is a cheap compile-time-constant compare on the same setup paths
that already ran. No measurable per-record or per-query cost at any scale.

#### C4 Commit-time RID rewrite scales with tx record count only — REFUTED (focal Q3)
CLAIM TESTED: `rewriteProvisionalRecordCollectionIds` scales with the schema
or collection count, or is otherwise a hot-path cost.

COST TRACE for `AbstractStorage.rewriteProvisionalRecordCollectionIds`
(AbstractStorage.java:2489, called at :2678):
- OPERATION: `new ArrayList<>(frontendTransaction.getRecordOperationsInternal())`
  (O(tx record count) copy, needed because the identity-change listeners re-key
  the live record-operation map mid-iteration), then one loop over the copy. Per
  provisional record it calls `txSchemaState.getResolvedCollectionId(id)`, whose
  body is `provisionalToReal.get(id)` on an `Int2IntOpenHashMap` (O(1), no
  autoboxing).
- COMPLEXITY: O(tx record count). No dependence on the number of classes,
  collections, or indexes.
- INVOCATION UNIT: reached only inside the schema-carry commit branch (guarded
  by `schemaContext != null`). Pure-data commits never run it. The
  low-schema-change-rate premise (plan Constraints) makes this branch rare.

SCALE CHECK: for a schema-carry commit the pass costs one array copy plus one
linear scan of the transaction's own record operations, matching the O(n)
passes `computeCommitWorkingSet` and the temp-position allocation loop already
make. On the rare schema-carry path this is acceptable and correct; the copy is
required for the mid-iteration re-key, so fusing it into an existing pass would
trade correctness margin for a negligible gain on a rare path. Scales with tx
record count only, as intended (I-A2).

Note: the new `assert !isProvisionalCollectionId(...)` in
`computeCommitWorkingSet` (:2431) is `-ea`-only, so it carries zero production
cost; under `-ea` it re-reads `record.getIdentity()`, which the loop body reads
anyway.
