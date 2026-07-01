<!--
MANIFEST
dimension: performance
prefix: PF
target: d2b1632652~1..d2b1632652
step: 5.2
iteration: 1
verdict: PASS
blockers: 0
findings_total: 1
evidence_base: 4
cert_index: 1
flags: []
index:
  - id: PF1
    sev: suggestion
    anchor: "#pf1-per-handle-re-scan-of-the-transaction-record-operation-set"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:478
    cert: C1
    basis: "PSI: getRecordOperationsInternal() backed by recordOperations map; getTxCreatedIndexes() bound; O(1) getApproximateRecordsCount"
-->

# Performance review — Track 5, Step 2 (commit-time engine lifecycle) — iter 1

Verdict PASS, 0 blockers. The commit-time index build is on a per-schema-commit path
(cold relative to record/query traffic), the v1 empty-source bound is enforced before
any unbounded scan can start, and the population scan is bounded by the transaction's
own write count rather than the source collection size. The single finding is a
suggestion-level structural cleanup (per-handle re-scan) that only matters when a
single transaction creates several indexes, which the single-schema-writer premise (D5)
makes rare. D12's accepted build-under-lock stall is out of review scope and was not
re-litigated.

## Findings

### PF1 [suggestion] Per-handle re-scan of the transaction record-operation set

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (line 442-520)

**Issue**: `buildAndDropReconciledEngines` loops over `plan.created()` and calls
`populateTxCreatedIndex` once per created handle. Each call independently rebuilds the
`coveredCollectionIds` set and then re-iterates the whole
`transaction.getRecordOperationsInternal()` set from the top. The population work is
therefore `O(N_created × N_recordOps)` value-derivations rather than a single
`O(N_recordOps)` pass that fans each record operation out to the handles whose
collections cover it. `getRecordOperationsInternal()` returns a live `Map.values()`
view (`recordOperations.values()`), so re-iteration is cheap per element, but the
`getDocumentValueToIndex` + `getPropertyInternal` derivation and the `IntOpenHashSet`
rebuild repeat per handle.

**Evidence**: See cert C1. COST TRACE: outer loop over `plan.created()` (size
`N_created`), inner loop over `getRecordOperationsInternal()` (size `N_recordOps`,
bounded by the transaction's own writes — P2). Per inner iteration:
`getDocumentValueToIndex` reads one entity property and builds a key. TOTAL:
`O(N_created × N_recordOps)` key derivations per schema commit, plus `N_created`
`IntOpenHashSet` allocations. SCALE CHECK: AT SMALL SCALE (1 index, 3 rows) —
negligible. AT MEDIUM SCALE (1 index, tx with 10K writes) — one pass, negligible
relative to the record apply. AT PRODUCTION SCALE — the `N_created` factor is > 1
only when a single transaction creates multiple indexes on overlapping collections,
which the single-schema-writer premise (D5) plus the low schema-change rate (the plan's
load-bearing constraint) make rare. VERDICT: MATTERS AT SCALE, narrowly (multi-index
single transaction only); NEGLIGIBLE for the common one-index commit.

**Impact**: Redundant CPU (repeated key derivation) and a small allocation multiple
(`IntOpenHashSet` per handle) inside the exclusive commit lock, scaling with the number
of indexes created in one transaction. No effect for the single-index commit that
dominates.

**Suggestion**: If a multi-index single transaction is ever expected, invert the loops:
build a `collectionId → List<handle>` map once (from the created handles' covered
collections), then make a single pass over `getRecordOperationsInternal()`, routing each
non-deleted persistent-RID entity to the handles covering its collection. That collapses
the cost to `O(N_recordOps)` derivations and one scan. Given the current single-writer /
low-rate premise this is optional; leaving the straightforward per-handle form is a
defensible readability-over-micro-optimization trade-off.

## Evidence base

#### C1 — Population scan is bounded by tx size, not collection size; empty-source bound is enforced first and is O(1)

CONFIRMED as the load-bearing correctness-of-bound fact (survived the refutation check):
`populateTxCreatedIndex` iterates `transaction.getRecordOperationsInternal()`, which
`FrontendTransactionImpl` implements as `recordOperations.values()` (PSI) — the
transaction's own record-op set, bounded by the transaction's write count, never the
source collection size. The committed-row scan is empty by the v1 empty-source bound.
`rejectNonEmptySourceCollection` runs in the enroll phase (phase 1), strictly before
`buildAndDropReconciledEngines` / `populateTxCreatedIndex` (phase 2), and reads
`getApproximateRecordsCountInCommitWindow`, whose underlying
`getApproximateRecordsCount` is documented and implemented as an O(1)
incrementally-maintained counter (no page scan, PSI-confirmed). So no unbounded
populated-collection scan can begin: the guard is cheap and precedes the build, and the
build's own scan is tx-bounded. This refutes the candidate concern that a large source
collection could feed an unbounded in-lock scan the D12 decision did not sanction — it
cannot.

Refuted / non-passing candidate claims (shown in full):

- Candidate: "`nextFreeIndexEngineId` does a linear scan of `indexEngines` for a free
  slot, an O(engines) cost per created engine, possibly O(engines × N_created) per
  commit." REFUTED as a finding. `indexEngines` size is bounded by the total engine
  count (a schema-scale quantity, not a data-scale one), the scan is a plain array-index
  null check, and it runs per schema commit (cold, P1). It mirrors the existing
  `nextFreeCollectionId()` allocator the codebase already accepts on the same commit
  path. At any realistic engine count the linear scan is dominated by the engine
  file-create I/O that follows it. VERDICT: NEGLIGIBLE — not reported.

- Candidate: "`getTxCreatedIndexes()` allocates `new HashSet<>(txCreated.values())` and
  `buildEngineAtCommit` allocates a `new HashMap<>()` per handle — allocation pressure."
  REFUTED. Both allocations are per-schema-commit (P1), on collections sized by the
  number of tx-created indexes (typically 1). No hot-path allocation, no GC pressure at
  the schema-change rate. VERDICT: NEGLIGIBLE — not reported.

- Candidate: "the exclusive `stateLock.writeLock()` is held for the whole engine build
  and population, blocking concurrent data commits." REFUTED as out of scope: this is
  exactly D12's explicitly-accepted build-under-lock decision for v1 (the review target
  instructs not to re-litigate it), and the two lock-free commit-window primitives
  (`callIndexEngine` self-routing on `isCommitWindowActive`,
  `getApproximateRecordsCountInCommitWindow`) added here exist to keep the build off the
  non-reentrant `stateLock`, which is the correct handling. VERDICT: sanctioned by the
  design — not reported.

#### C2 — Per-schema-commit call frequency (cold path)

`enrollReconciledIndexRecords`, `buildAndDropReconciledEngines`, and
`populateTxCreatedIndex` are reachable only from the schema-carry branch of
`AbstractStorage.commit` (`indexPlan != null`, gated on
`schemaContext.txSchemaState().getIndexOverlay()` being non-empty). Pure-data commits
take the read-lock fast path and never enter these methods (D19). The
implementation-plan constraint names the low schema-change rate as the load-bearing
premise that makes the whole in-commit build acceptable, so per-commit cost that scales
with schema-object counts (engines, tx-created indexes) is not a data-traffic hot path.

#### C3 — `getCollections()` is a cheap unmodifiable view, not a defensive copy

`IndexAbstract.getCollections()` returns `Collections.unmodifiableSet(collectionsToIndex)`
(PSI), so the repeated `handle.getCollections()` calls in `rejectNonEmptySourceCollection`
and `populateTxCreatedIndex` do not each copy the membership set — they wrap it. The
`coveredCollectionIds` `IntOpenHashSet` rebuild in `populateTxCreatedIndex` is the only
per-handle collection-side allocation, and it is sized by the index's collection count
(small).

#### C4 — Index-changes / index-apply map pruning is O(N_created), map-keyed removals

The commit-body pruning after enrollment
(`indexOperations.remove(created.getName())` and
`txIndexChanges.remove(created.getName())` for each `indexPlan.created()`) iterates only
the created handles and does keyed `HashMap` removals (`getIndexOperations()` returns the
`indexEntries` map, PSI). Cost is `O(N_created)` keyed removals per schema commit — no
scan of the full index-changes set, no data-scale term.
