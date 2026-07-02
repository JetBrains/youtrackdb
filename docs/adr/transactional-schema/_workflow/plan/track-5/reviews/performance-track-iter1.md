<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: PF1, sev: suggestion, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2698, anchor: "### PF1 ", cert: C1, basis: "O(R) record-op-list copy+scan on every schema-carry commit even with no tx-created class; avoidable with a provisional-names guard"}
  - {id: PF2, sev: suggestion, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxedResource.java:122, anchor: "### PF2 ", cert: C2, basis: "interleaved mid-tx DDL/DML rebuilds the whole ImmutableSchema per read-after-write; O(N^2) worst case inside one schema tx, accepted D21 tradeoff"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 2}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: AT-SCALE, anchor: "#### C2 "}
  - {id: C3, verdict: NEGLIGIBLE, anchor: "#### C3 "}
  - {id: C4, verdict: NEGLIGIBLE, anchor: "#### C4 "}
  - {id: C5, verdict: NEGLIGIBLE, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [suggestion] Provisional-id rewrite copies and scans the whole record set on schema commits that created no class

A schema-carrying commit that creates no class still copies and scans the
transaction's entire record-operation list. The work finds nothing, because a
record can carry a provisional collection id only when the transaction allocated
one, and only a class create allocates one.

**Cost trace.** `AbstractStorage.java:2698` calls
`rewriteProvisionalRecordCollectionIds` unconditionally inside the
`schemaContext != null` branch. The method (`AbstractStorage.java:2490`, copy at
`:2495`) runs `new ArrayList<>(frontendTransaction.getRecordOperationsInternal())`
and then iterates, testing `isProvisionalCollectionId` on each record. The
snapshot rebuild immediately above it (`:2681`) is guarded on
`getResolvedCollectionIds().isEmpty()`; the rewrite call is not guarded at all.

- Allocation: one `ArrayList` holding R element references, R = record operations
  in the transaction.
- Complexity: O(R) copy plus O(R) scan per schema-carrying commit.
- Applies only to schema-carrying commits. Pure-data commits never reach this
  branch, so the data-write fast path is untouched.

**Scale check.** R is bounded by the data records bundled into the same
transaction, not by the schema-change rate the load-bearing premise rests on. A
property add, an index create, or an index drop batched with a bulk data write in
one transaction allocates zero provisional ids yet pays a full-list copy and scan.
- 100 records: negligible.
- 100k records batched with one DDL op: a transient ~800 KB reference array plus a
  100k scan, still small next to the commit's record I/O, so this stays minor.

Verdict MATTERS AT SCALE, low magnitude. The cost is data-proportional, which sits
outside the "acceptable inside a schema tx" envelope (that envelope covers
schema-sized overhead, not work proportional to bundled data records).

**Fix.** Guard the call on provisional-id allocation, mirroring the guard already
present on the rebuild above it but keyed on the allocation map, not the resolution
map:

```java
if (!schemaContext.txSchemaState().getProvisionalCollectionNames().isEmpty()) {
  rewriteProvisionalRecordCollectionIds(frontendTransaction, schemaContext.txSchemaState());
}
```

`getProvisionalCollectionNames()` (`TxSchemaState.java:305`) is non-empty exactly
when the transaction created a class, which is the only way a record carries a
provisional id. This keeps the create-then-drop-with-rows loud-failure path intact:
that case did allocate a provisional id, so the guard still runs the rewrite and it
throws `NO_RESOLUTION`. A guard on `getResolvedCollectionIds()` would wrongly skip
that failure detection, so it is not the right predicate.

### PF2 [suggestion] Interleaved mid-tx DDL and DML rebuilds the full ImmutableSchema per read-after-write

Inside a schema or index transaction, every proxy-routed schema write invalidates
the session snapshot memo, so the next snapshot read rebuilds the whole
`ImmutableSchema`. An alternating write-then-read loop rebuilds once per pair, at
a cost proportional to the whole schema.

**Cost trace.** `SchemaProxedResource.resolveForWrite` (`:122`) calls
`session.forceRebuildTxSchemaSnapshot()` on every routed schema write, which nulls
the `TxSchemaState.overlaySnapshot` memo. The next `SchemaProxy.makeSnapshot()`
(`SchemaProxy.java:100`) rebuilds through `makeUncachedSnapshot`, constructing a
fresh `ImmutableSchema` over every tx-local class and re-resolving each class's
index list through the overlay routing seam. Cost per rebuild is O(total classes ×
indexes per class).

**Scale check.** The memo covers the common migration shape cheaply:
- All DDL first, then bulk DML: no reads interleave the DDL, so the memo stays null
  and no rebuild fires during the DDL phase; the first insert rebuilds once and the
  remaining inserts reuse the memo. One rebuild total.
- Alternating create-class then insert in one transaction (pathological): each
  create nulls the memo and each insert rebuilds the now-larger schema, giving
  O(N^2) over N created classes inside one transaction.

This lives entirely inside a schema/index transaction (rare per the premise) and is
the accepted D21 per-operation build cost; D8 explicitly rejected the incremental
overlay-map alternative. No action is required unless the interleaved pattern
becomes a target workload. Flagged so the cost is visible and the memo's role is
recorded as a cross-step invariant: Step 1 added the memo, Step 3 widened the
force-rebuild trigger from index changes to every schema write, so the two steps
together define this behavior.

## Evidence base

#### C1 CONFIRMED
`rewriteProvisionalRecordCollectionIds` runs an unconditional O(R) copy plus scan of the record-operation list on every schema-carrying commit; a commit with no tx-created class allocates no provisional id, so the work is pure waste, scales with bundled data-record count, and is removed by a `getProvisionalCollectionNames().isEmpty()` guard that preserves the create-then-drop-with-rows loud-failure path. Survives refutation. (PF1)

#### C2 AT-SCALE
Claim: the D21 force-rebuild (SchemaProxedResource.java:122, per routed schema write) interacting with the Step-1 snapshot memo forces a full `ImmutableSchema` rebuild on every read-after-write inside a schema tx.
- Small scale (few DDL ops): negligible; the memo absorbs consecutive reads.
- Common migration (all DDL, then bulk DML): one rebuild; the memo holds across the DML phase because reads do not invalidate it (only `resolveForWrite` does).
- Pathological (alternating create-class / insert in one tx): O(N^2) over N created classes, each rebuild proportional to the growing schema.
Not refuted: the quadratic path is real. Not escalated: it is confined to a single schema tx (rare per the premise), and it is the accepted D21 build cost with the incremental alternative already rejected in D8. Reported as a documented tradeoff, not an action item. (PF2)

#### C3 NEGLIGIBLE
Claim tested: the tx-aware snapshot and the index overlay regress the no-schema-tx read, plan-cache, and pure-data-commit fast paths. Refuted.
- `SchemaProxy.makeSnapshot()` outside a tx returns `delegate.makeSnapshot(session)` after a single `session.getTxSchemaState()` call. `getTxSchemaState()` (DatabaseSessionEmbedded) is `getTransactionInternal().isActive()`, which is false outside a tx and returns null immediately; inside an active pure-data tx it is one `userData.get()` HashMap lookup (`FrontendTransactionImpl.getCustomData`). O(1).
- `YqlExecutionPlanCache.get` and `put` add the same one-lookup `db.getTxSchemaState()` probe per query, O(1), and short-circuit to the committed path when it is null.
- `IndexManagerEmbedded.getClassRawIndexes` / `getClassIndexes` call `activeOverlay(session)`, which returns null after the same O(1) probe when no overlay is active, then defers to `super`. Byte-for-byte the old behavior plus one O(1) probe.
- `makeSnapshot` sits behind the `MetadataDefault.immutableSchema` memo and the `SchemaShared.snapshot` process cache, so it is not a per-read call.
- The pure-data commit path is untouched: `rewriteProvisionalRecordCollectionIds` and all three index-reconciliation phases run only under `schemaContext != null` / `indexPlan != null`, and the `computeCommitWorkingSet` provisional-id assert is `assert`-only (disabled without `-ea`).
All within "untouched or O(1)-guarded." NEGLIGIBLE.

#### C4 NEGLIGIBLE
Claim tested: the commit-time index build has O(K×R) and O(K×N) inner loops. `populateTxCreatedIndex` rescans all transaction record operations for each created index (K indexes × R record ops), and `nextFreeIndexEngineId` linear-scans `indexEngines` per created engine (K × N engines). Refuted as material: both run only in the schema-carry commit window (rare per the premise), K (indexes created per transaction) is small, and the v1 bound restricts the build to empty source collections, so the population re-derivation covers only the transaction's own inserts. `nextFreeIndexEngineId` mirrors the pre-existing `nextFreeCollectionId` allocator, so it introduces no new pattern. NEGLIGIBLE now and at realistic scale.

#### C5 NEGLIGIBLE
Claim tested: `SelectExecutionPlanner.findBestIndexFor` adds `.filter(isIndexBuilt)` to two index streams on every query, not only schema-tx queries, and `FetchFromClassExecutionStep.canBeCached()` iterates collection ids on every cache decision. Refuted: `IndexAbstract.getIndexId()` is a plain `return indexId` field read (verified by PSI), so `isIndexBuilt` is an O(1) predicate on a stream that already iterates the same candidates; `isProvisionalCollectionId` in `canBeCached()` is a single integer comparison per collection. No lock, no allocation, no extra traversal. NEGLIGIBLE.
