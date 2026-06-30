<!--MANIFEST
dimension: code-quality
scope: track
track: 4
iteration: 1
verdict: PASS
blockers: 0
should_fix: 1
suggestions: 3
evidence_base:
  certs: 0
cert_index: []
flags: []
index:
  - id: CQ1
    sev: should-fix
    anchor: "#cq1-applycommitoperations-interleaves-reconciliation-apply-error-handling-and-undo-in-one-large-method"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2441
    cert: n/a
    basis: diff + source read + PSI method-span measurement
  - id: CQ2
    sev: suggestion
    anchor: "#cq2-commit-window-lock-skip-boilerplate-duplicated-at-five-read-sites"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:1402
    cert: n/a
    basis: diff + grep (5 occurrences)
  - id: CQ3
    sev: suggestion
    anchor: "#cq3-public-test-only-seam-setcommitwindowtesthook-on-production-storage-class"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2975
    cert: n/a
    basis: diff + PSI find-usages (test-only callers)
  - id: CQ4
    sev: suggestion
    anchor: "#cq4-singular-txschemastate-accessors-have-no-production-caller"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxSchemaState.java:1054
    cert: n/a
    basis: diff + PSI find-usages (singular accessors test-only)
-->

# Code Quality — Track 4 (track-level), iteration 1

## Findings

### CQ1 [should-fix] applyCommitOperations interleaves reconciliation, apply, error handling, and undo in one large method

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (lines 2441-2690)

**Issue**: `applyCommitOperations` is the method the review target singles out ("where the
reconciliation core, the lock-free primitives, and the undo path interleave"), and the concern holds
up: its body runs roughly 200 lines with four-deep nesting (`try` → `startTxCommit` → inner `try` →
`catch`/`finally`, each arm itself branched on `schemaContext != null`). Inside that single method it
(1) reconciles collections, (2) resolves provisional ids and serializes the tx-local schema under the
schema write lock with link-consistency suppression, (3) gathers the working set, (4) allocates
positions and rewrites temp RIDs in the new-record loop, (5) writes every record, (6) runs link-bag
and index ops, (7) on failure rolls back and undoes registry publication, and (8) on success promotes
the committed schema. The pure-data path threads through the same body via `schemaContext == null`
guards, so a reader must mentally track which statements fire on which branch throughout.

The track already extracted `computeCommitWorkingSet`, `reconcileCollections`,
`undoReconciledCollections`, and `commitSchemaCarry`, so the decomposition instinct is right — this is
the one remaining oversized unit. The schema-carry preamble inside the inner `try` (resolve provisional
ids → `acquireSchemaWriteLock` → `disableLinkConsistencyCheck` → `toStream` →
`enableLinkConsistencyCheck`/`releaseSchemaWriteLock` in `finally`, lines ~2480-2520) is a
self-contained unit with its own nested try/finally and would read far more clearly as a private
`serializeTxLocalSchema(schemaContext, session)` helper. That single extraction would drop the host
method's nesting depth and let the apply body read as the legacy sequence it still is.

**Suggestion**: Extract the schema-carry serialization preamble (provisional-id resolution + the
write-lock/link-consistency-suppressed `toStream`) into a named private helper, leaving
`applyCommitOperations` to orchestrate reconcile → serialize → gather → apply → (promote | undo). This
is a readability/maintainability refactor only; no behavior change is intended or implied.

### CQ2 [suggestion] Commit-window lock-skip boilerplate duplicated at five read sites

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (lines 1402, 2067, 2106, 4505, 5321)

**Issue**: Five read methods (`isClosed`, `getCollectionNames`, `getCollectionIdByName`,
`getPhysicalCollectionNameById`, `readRecordInternal`) now repeat the same five-line shape:

```java
final boolean lockFree = isCommitWindowActive();
if (!lockFree) {
  stateLock.readLock().lock();
}
try {
  ...
} finally {
  if (!lockFree) {
    stateLock.readLock().unlock();
  }
}
```

The conditional-lock idiom is identical at every site and was added incrementally across Steps 3, 4,
and 6, so the duplication only becomes visible at the track level. Each copy is a place a future change
(e.g. adding the same window-awareness to a sixth method, or fixing a clamp) must be applied by hand,
and a copy that drifts (forgetting the `!lockFree` guard on either the lock or the unlock) is a silent
lock-leak the compiler will not catch.

**Suggestion**: Consider a small private wrapper that centralizes the decision — for example a
`runUnderStateReadLockOrCommitWindow(Supplier<T>)` helper, or an `AutoCloseable`
`acquireStateReadLockUnlessCommitWindow()` used in try-with-resources — so each call site reduces to
the body plus one acquisition line. Optional; the current form is correct and individually readable.

### CQ3 [suggestion] Public test-only seam setCommitWindowTestHook on production storage class

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (lines 2966-2976)

**Issue**: `setCommitWindowTestHook(Runnable)` is declared `public` on `AbstractStorage` and exists only
to drive tests; PSI find-usages confirms every caller is in `SchemaCommitReconciliationTest` and no
production code sets a hook. The Javadoc is honest ("Test-only seam — no production code sets a hook"),
and the field itself is `private volatile`, so the design is deliberate and the blast radius is small.
It is `public` (not package-private like the sibling `enterCommitWindow`/`exitCommitWindow`) only
because the consuming test lives in a different package (`metadata.schema`) than the storage class.

The codebase has no `@VisibleForTesting` convention (zero occurrences in `core` main), so there is no
established annotation to apply; this is a soft convention nit rather than a violation. The mild concern
is that a `public` no-op-in-production mutator on a central storage class is an attractive nuisance — a
future caller could install a hook in production code with no compiler signal that it is test-only.

**Suggestion**: Keep the seam, but make the test-only intent harder to misuse: either tighten visibility
(e.g. package-private plus a same-package test shim, or a dedicated test-support accessor) or, if the
project adopts one, mark it `@VisibleForTesting`. Lowest-effort acceptable outcome is leaving it as-is
given the clear Javadoc — flagged for awareness, not correctness.

### CQ4 [suggestion] Singular TxSchemaState accessors have no production caller

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxSchemaState.java` (lines 1054, 1098)

**Issue**: `getProvisionalCollectionName(int)` and `getResolvedCollectionId(int)` are the per-id
accessors on the new provisional-id carrier. PSI find-usages shows production reconciliation
(`AbstractStorage`) reads only the plural map views — `getProvisionalCollectionNames()` and
`getResolvedCollectionIds()` — while the two singular accessors are referenced only from
`SchemaDeguardTest` (plus the class's own Javadoc cross-references). They are small, well-documented,
and exercised by the carrier round-trip test, so this is not dead code in the strict sense, but they do
add API surface the commit path does not consume.

**Suggestion**: Optional. If the singular accessors are intended only as a tested public contract for
future consumers, a one-line note to that effect would prevent a later reader assuming they are
load-bearing in the commit path; otherwise consider trimming to the plural views the production code
actually uses. No action required for correctness.

## Evidence base

No certificates: this dimension is evidence-trail-exempt (no refutation or certificate phase to
persist). Reference-accuracy claims in the findings were checked with mcp-steroid PSI find-usages and
`OverridingMethodsSearch` against the open `transactional-schema` project (confirmed matching the
working tree via `steroid_list_projects`); method spans and duplication counts were measured directly
on the source.
