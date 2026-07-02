<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 1, suggestion: 4}
index:
  - {id: TB1, sev: should-fix, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java:195, anchor: "### TB1 ", cert: C1, basis: "sole test of the provisional backward-scan branch asserts only row count; ORDER BY @rid DESC has no re-sort so wrong order goes uncaught"}
  - {id: TB2, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java:1122, anchor: "### TB2 ", cert: C2, basis: "membership-ripple test asserts recorded set non-empty, not that it names the subclass collection or bounds size"}
  - {id: TB3, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java:562, anchor: "### TB3 ", cert: C3, basis: "no-provisional-leak test asserts only non-empty serialized bytes; fixture has no link so the leak path is never set up"}
  - {id: TB4, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java:185, anchor: "### TB4 ", cert: C4, basis: "test name/lead Javadoc claim provisional-id resolution; body exercises real-id path; provisional resolver branch untested"}
  - {id: TB5, sev: suggestion, loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java:87, anchor: "### TB5 ", cert: C5, basis: "assertThrows(ValidationException) with no message check; passes for any validation failure, not only the regexp"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 5}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
flags: [CONTRACT_OK]
-->

## Findings

### TB1 [should-fix] `ORDER BY @rid DESC` over a tx-created class asserts only row count, so the backward-scan provisional branch's ordering is unverified

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java`, method `sameTransactionQueryOfATxCreatedClassReturnsTheTransactionsOwnRows` (~line 195).

**Issue**: The `order by @rid desc` sub-query is the only test that drives the new provisional-collection backward-scan branch in `RecordIteratorCollection` (the `moveTxIdBackward()` call and the `initStorageIterator()` early return added for `isProvisionalCollectionId(collectionId)`). It asserts only `rs.stream().count() == 2L`. The forward `select from` sub-query sorts the names before comparing, so it does not pin order either.

**Evidence** (ASSERTION PRECISION + FALSIFIABILITY): `SelectExecutionPlanner.handleClassAsTarget` (SelectExecutionPlanner.java:2125-2147) maps `ORDER BY @rid DESC` to `orderByRidAsc=false` on the `FetchFromClassExecutionStep` and sets `info.orderApplied = true`; no separate ORDER BY sort step is chained, so the fetch step's reverse-iterator order *is* the query result order. MUTATION: if `moveTxIdBackward()` served the provisional-collection rows in ascending or arbitrary order, the query would return them in the wrong order while `count()` stayed 2, so the test would PASS — coverage-driven false confidence over the one branch whose entire reason to exist is descending order.

**Missing behavior**: the descending RID order of the provisional-collection backward scan.

**Suggested fix**:
```java
try (var rs = session.query("select from TxQueriedClass order by @rid desc")) {
  var names = rs.stream().map(r -> (String) r.getProperty("name")).toList();
  assertEquals("the backward provisional scan must serve rows in descending RID order",
      List.of("pending-2", "pending-1"), names);
}
```

### TB2 [suggestion] Membership-ripple overlay test asserts the recorded set is non-empty, not that it names the subclass collection

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java`, method `membershipRippleRecordsCollectionAddIntoOverlay` (~line 1122).

**Issue**: The assertion message claims "the recorded membership-add must name at least the subclass's collection", but the assertion is `assertFalse(added.isEmpty())`. It verifies that *something* was recorded, not *which* collection, and puts no bound on the set size.

**Evidence** (ASSERTION PRECISION): `overlay.getMembershipAdded().get(indexName)` is the set of collection names rippled into the superclass index. MUTATION: a regression that recorded the wrong collection (the superclass's own collection, or an extra entry) would leave `added` non-empty and the test would PASS. The end-to-end positive coverage in `CommitTimeIndexBuildTest.committedMembershipChangeMakesParentIndexCoverSubclassRows` uses a *committed* child (real collection), so it does not backstop the provisional-subclass overlay path this test targets.

**Missing behavior**: that the recorded add names exactly the tx-created subclass's collection.

**Suggested fix**: resolve the subclass's provisional collection name from `session.getTxSchemaState()` and assert `added.contains(subCollectionName)` plus `added.size() == 1`; at minimum assert `added.size() == 1` so a spurious extra entry fails the test.

### TB3 [suggestion] The serialization half of the no-provisional-leak test asserts only non-empty bytes

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java`, method `serializationAndCollectionNameResolutionLeakNoProvisionalCollectionId` (~line 562).

**Issue**: The id-to-name half is asserted precisely (`assertNull` per provisional id). The serialization half asserts only `assertNotNull(bytes)` and `bytes.length > 0` — a smoke check, not a leak check. The fixture entity carries a single scalar `name` property and no link, so there is no path by which a provisional collection id (`<= -2`) could reach the serialized bytes; the byte-length assertion cannot fail for the reason the test name states.

**Evidence** (FALSIFIABILITY): MUTATION: if serialization embedded a provisional collection id (for example in a serialized link RID), this test would not detect it, because no link is present and the assertion only checks byte length. Relative to the D21 risk-(2) contract the test names, the serialization arm is effectively vacuous.

**Missing behavior**: a falsifiable check that a provisional id is absent from durable serialization output, which requires a fixture with a link into a tx-created-class entity (whose RID carries `<= -2`).

**Suggested fix**: add a link to a second tx-created-class entity, commit, reopen, and assert the persisted link RID's collection id is `>= 0`; or narrow the test name and Javadoc to the id-to-name path it actually verifies.

### TB4 [suggestion] `deferredIndexCreateResolvesCollectionWithoutThrowing` name and lead Javadoc claim provisional-id resolution but the body exercises the real-id path

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java`, method `deferredIndexCreateResolvesCollectionWithoutThrowing` (~line 185).

**Issue**: The method name and lead Javadoc sentence describe "a class created earlier in the same transaction, then indexed later in that transaction, resolves its provisional collection id (`<= -2`)". The body creates the class and property at the top level (committed) and only creates the index inside a transaction, so the deferred handle resolves a *real* collection id through `session.getCollectionNameById`. The provisional branch of `resolveDeferredCollectionNames` (IndexManagerEmbedded.java:699, `txState.getProvisionalCollectionName(...)`) is never reached.

**Evidence** (BEHAVIOR TRACE + reference check): grep confirms `resolveDeferredCollectionNames` has one production caller (IndexManagerEmbedded.java:525, the deferred `createIndex` path) and no test drives its provisional branch; `getProvisionalCollectionName`'s only test callers (SchemaDeguardTest.java:883/885) exercise the carrier directly, not through the resolver. MUTATION: if the provisional branch were broken (threw, or returned the wrong carried name) every test in the track would still pass. The second Javadoc paragraph correctly notes the pure same-tx class-plus-property-plus-index path is unreachable until in-transaction property creation is de-guarded (a later track), so the branch is forward-looking; the concern is the misleading name, not a required new test now.

**Missing behavior**: none required this track (the provisional resolver is forward-looking); the finding is the name/Javadoc claiming coverage the body does not provide.

**Suggested fix**: rename to something like `deferredIndexCreateOnACommittedClassResolvesItsRealCollectionWithoutThrowing`, and drop or relabel the lead sentence so it does not read as coverage of the provisional-id branch.

### TB5 [suggestion] `regexpAdded...` asserts only that a `ValidationException` is thrown, not that it is the regexp violation

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java`, method `regexpAddedToACommittedClassPropertyIsEnforcedInsideTheSameTransaction` (~line 87).

**Issue**: `assertThrows(ValidationException.class, entity::validate)` does not check that the exception carries the regexp constraint or the offending property. The sibling strict-mode test pins `message.contains("STRICT")`; this one is less specific.

**Evidence** (ASSERTION PRECISION): the class carries only the regexp constraint today, so a `ValidationException` here can only be the regexp violation and the test is correct. It is not future-proof: if the fixture later gained another constraint, an unrelated `ValidationException` would satisfy the assertion and the test would no longer prove the regexp is enforced. Low blast radius.

**Missing behavior**: that the failure is specifically the regexp check.

**Suggested fix**:
```java
var thrown = assertThrows(ValidationException.class, entity::validate);
assertTrue("the failure must be the regexp check, got: " + thrown.getMessage(),
    thrown.getMessage().toLowerCase(java.util.Locale.ROOT).contains("regex")
        || thrown.getMessage().contains("code"));
```

## Evidence base

#### C1 CONFIRMED — TB1 survives refutation: planner sets `orderByRidAsc=false` + `info.orderApplied=true` (SelectExecutionPlanner.java:2128-2145) with no chained sort step, so the reverse-iterator order is the result order and the count-only assertion cannot catch a wrong-order backward provisional scan.

#### C2 CONFIRMED — TB2 survives refutation: `assertFalse(added.isEmpty())` is satisfied by any non-empty recorded set, so a wrong-collection or extra-entry regression passes; no other test covers the provisional-subclass overlay-membership path.

#### C3 CONFIRMED — TB3 survives refutation: `assertNotNull(bytes)` + `bytes.length > 0` is a happy-path smoke check; with a link-free fixture there is no provisional-id-bearing byte to detect, so the serialization arm cannot fail for its stated reason.

#### C4 CONFIRMED — TB4 survives refutation: grep shows `resolveDeferredCollectionNames`'s provisional arm (IndexManagerEmbedded.java:699) is driven by no test; the named test resolves a committed real collection, so the name overstates its coverage.

#### C5 CONFIRMED — TB5 survives refutation: the bare `assertThrows(ValidationException.class, ...)` matches any validation failure; correct today because the regexp is the only constraint, but not falsifiable against a mis-attributed failure.

#### C6 REFUTED — candidate NOT a finding
Candidate: `indexCreatedAndPopulatedInSameTransactionContainsRowsAfterCommit` and its siblings assert `getRids(...).size()` rather than the exact RID set, so they look shallow.
Refutation: for a NOTUNIQUE index the meaningful contract is the key multiplicity (two "alpha", one "beta"), and the RIDs are engine-generated, so pinning them would couple the test to allocation order rather than behavior. The size assertions are the precise, falsifiable form here, and `indexBuildReflectsFinalTransactionState` additionally pins `size(session) == 1` plus per-key presence/absence for the updated and deleted rows. No finding.

#### C7 REFUTED — candidate NOT a finding
Candidate: the failed-commit tests (`failedEngineCreatingCommitLeavesNoPhantomEngineAndReusesId`, `failedDropCommitLeavesTheSurvivingCommittedIndexUsable`) and `buildOnNonEmptySourceCollectionIsLoudlyRejected` catch a broad `RuntimeException` with an empty (comment-only) body, which reads as an over-broad exception test.
Refutation: each is non-vacuous. The build and drop tests install a `postEngineBuildTestHook` whose first act asserts the engine IS registered (create side) or is NOT registered (drop side) at the fault point via `isIndexEngineRegisteredInCommitWindow`, proving the injected fault fired at the intended window rather than passing vacuously; the load-bearing checks run after the failure (no phantom engine and engine-id reuse for the create side; the surviving committed index returns its committed row for the drop side) and would fail on a broken revert or restore arm. The non-empty-source test narrows its broad catch with `message.contains("YTDB-1064")`. Each also guards the no-throw case with a preceding `fail(...)`. No finding.
