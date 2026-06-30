<!--MANIFEST
review: test-behavior
track: 4
iteration: 1
level: high
range: 1dd9c0424f40e7aa9ec90858f6eb4b235f3a2c5f..2f295a881fbe27ee887651df94fc9d4ed24b5bbc
verdict: CHANGES-REQUESTED
findings_total: 3
blocker: 1
should-fix: 1
suggestion: 1
evidence_base: present
cert_index: C1,C2,C3
flags: red-committed-test
index:
  - id: TB1
    sev: blocker
    anchor: "#tb1-blocker-committed-non-ignored-rename-test-is-red"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java:511"
    cert: C1
    basis: "PSI trace of setName -> resolveForWrite -> recordWriteTarget(old name) then changeClassName(new name); markClassChanged is Set.add"
  - id: TB2
    sev: should-fix
    anchor: "#tb2-should-fix-failed-commit-tests-skip-the-index-engine-registry-arm-of-i-a4"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java:256"
    cert: C2
    basis: "Acceptance criterion names indexEngines/indexEngineNameMap; both failed-commit tests use createClass only (no index)"
  - id: TB3
    sev: suggestion
    anchor: "#tb3-suggestion-1-second-negative-wait-as-the-blocked-signal"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java:493"
    cert: C3
    basis: "assertFalse(dataCommitted.await(1, SECONDS)) is the only block proof; timing-dependent"
-->

## Findings

### TB1 [blocker] Committed, non-@Ignore'd rename test is red

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java`, method `renameClassInsideTransactionRecordsNewNameOnly` (line 511)

**Issue**: The test asserts the tx-local changed-class set records the new name only after a rename (`assertFalse(state.getChangedClasses().contains("RenameBefore"))`, line 529-531). Production records **both** the old and the new name, so this assertion fails. The test is committed to the branch and is not `@Ignore`d, so it sits red in the suite. The track Surprises log (2026-06-29T16:47Z) flags it as branch-red and leaves its origin "unresolved" for a Phase C decision; the trace below resolves it: the test went red **inside Track 4 at Step 5** (`1f431495a8`), not pre-track.

**Evidence** (FALSIFIABILITY + BEHAVIOR TRACE, cert C1): `SchemaClassProxy.setName(String)` calls `resolveForWrite().setName(session, iName)`. `resolveForWrite()` (SchemaProxedResource.java:106-116) resolves the tx-local class — still named `RenameBefore` at this point — and immediately calls `recordWriteTarget(txState, resolved)`, which `SchemaClassProxy.recordWriteTarget` (line 52-59) implements as `txState.markClassChanged(resolved.getName())`, recording **`RenameBefore`**. Only afterward does `setNameInternal` -> `SchemaShared.changeClassName` (line 704-716) record `RenameAfter`. `TxSchemaState.markClassChanged` is `changedClasses.add(...)` on a `Set` (line 133-135), so the final set is `{RenameBefore, RenameAfter}`. The mutation that broke the test is `recordWriteTarget` at the proxy choke point, added by the Step-5 review-fix `1f431495a8` (`git log -S recordWriteTarget`); the test and the new-name-only recording in `changeClassName` were both added earlier by `00d81f43dc` (Track 3), where the test passed.

**Missing behavior / correctness note**: The assertion is now **stale**, not catching a real bug. I traced the downstream impact and the production contract is correct: the selective `SchemaShared.toStream(session, changedClassNames, writeRootPayload)` uses `changedClassNames` only for the write/skip decision in the per-class loop (line 1022-1042), matching against **live** class names; the stale `RenameBefore` entry matches no live class and is explicitly exempted by `allChangedLiveClassesWereWritten` (line 1049-1057, "a changed name that is not a live class is a drop ... exempt"). The drop loop (line 1061-1070) is keyed on record **RID** (`previouslyLinked` minus `liveRecords`), not on name, so the renamed class — same RID, still live — is never dropped. There is no data loss; the assertion over-specifies an internal detail the centralized choke point legitimately changed.

**Suggested fix**: Relax the assertion to its load-bearing half (the new name is recorded) and drop the old-name negative, or restate it as "the old name is harmless because the selective write keys the drop loop on RID, not name":

```java
// The rename records the new name; it may also leave the old name as a harmless duplicate
// (the proxy write choke point records the resolved class under its current name before the
// rename mutates it). The old name is benign: the selective write's drop loop is keyed on the
// per-class record RID, which the rename keeps, so no record is dropped.
assertTrue("the rename must record the new name in the tx-local changed-class set",
    state.getChangedClasses().contains("RenameAfter"));
// (Old-name negative removed: recordWriteTarget records the pre-rename name as a no-op duplicate.)
```

If the team instead wants the strict new-name-only invariant, the production fix is to record in `recordWriteTarget` after the mutation, or to special-case the rename path so the old name is never added — but that is a production change, out of this dimension's scope. Either way the red test must not ship.

### TB2 [should-fix] Failed-commit tests skip the index-engine registry arm of I-A4

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java`, methods `failedSchemaCommitLeavesNoPhantomRegistration` (line 256) and `failedSchemaCommitWithDropRestoresDroppedRegistration` (line 329)

**Issue**: The Validation and Acceptance criterion for I-A4 names four registries: "the in-memory registries (`collections` / `collectionMap` / `indexEngines` / `indexEngineNameMap`) carry no entry for the failed commit's structures." Both failed-commit tests create their fault subject with `createClass` only (lines 276, 331) — a vertex class allocates collections but builds no index engine. So the assertions only ever inspect the collection side (`session.getCollectionNames()`, `getCollectionNameById`). The deferred-publication-then-undo path for the index-engine registries (`indexEngines` / `indexEngineNameMap`, the `addIndexEngine` lambda's writes per D10/A1-R1) is never exercised by any failed-commit test.

**Evidence** (BEHAVIOR COVERAGE, cert C2): the diff's failed-commit tests assert contract point "collection registry unchanged" (VERIFIED, lines 289-314) and "drop-restore of collection registration" (VERIFIED, lines 363-371). Contract point "index-engine registry carries no phantom entry on a failed commit" is NOT CHECKED — no test injects the in-window fault on a commit that created an index engine. The acceptance criterion explicitly lists `indexEngines` / `indexEngineNameMap`.

**Missing behavior**: A failed schema-carry commit that created an index must leave `indexEngines` / `indexEngineNameMap` with no phantom entry, and the freed engine id must be reusable — the engine-axis mirror of the collection-axis assertion already made.

**Suggested fix**: Add an index to the failed-commit subject (or a third test) so the engine-registry undo runs:

```java
session.begin();
var cls = session.getMetadata().getSchema().createClass("FailAtApply");
cls.createProperty("name", PropertyType.STRING);
cls.createIndex("FailAtApply_name", SchemaClass.INDEX_TYPE.UNIQUE, "name");
// ... commit() faults in-window ...
// Then assert the engine registry is clean, mirroring the collection assertion:
assertFalse("a failed schema commit must leave no phantom index engine",
    indexManager.existsIndex("FailAtApply_name"));
// and confirm the next create reuses the freed engine id.
```

(Note: the track defers the commit-time engine *build* to Track 5, so a full end-to-end engine create may not yet be reachable through `createIndex` at commit. If the engine-registry publication is genuinely unreachable at this track's boundary, document that at the test surface as the reason the engine arm of I-A4 is collection-only here — the same self-documenting-placeholder style used for the `@Ignore`'d TY2 crash test — rather than leaving the named contract point silently uncovered.)

### TB3 [suggestion] 1-second negative-wait as the "blocked" signal

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java`, method `dataCommitSerializesBehindHeldSchemaWriteLock` (line 493)

**Issue**: The proof that the data commit is excluded by the held schema write lock is `assertFalse("the data commit must be blocked ...", dataCommitted.await(1, TimeUnit.SECONDS))` (line 493-494). A bounded negative wait is a timing assertion: a data commit that was slow for any unrelated reason (GC pause, scheduling) would also fail to complete in 1 s, passing the "blocked" assertion without proving exclusion. The test is well-built overall — `errors` captures any thrown failure and the post-release `dataCommitted.await(30s)` (line 500-501) confirms eventual completion — so this is a precision nit, not a false-confidence hole.

**Evidence** (ASSERTION PRECISION, cert C3): the only positive evidence of blocking is the 1-second timeout; the assertion would pass for "blocked" and for "merely slow, never started the lock acquire" alike. The comment at line 491-492 acknowledges the shape.

**Missing behavior**: Stronger evidence that the data thread is parked *on the lock* specifically. This is hard to assert deterministically without an owner-thread query (the track notes `ScalableRWLock` exposes none), so the current shape is a reasonable pragmatic choice.

**Suggested fix** (optional): before the negative wait, confirm the data thread reached the lock-acquire point (e.g. observe its `Thread.State` is `WAITING`/`TIMED_WAITING` via `dataThread.getState()`), turning "did not finish in 1 s" into "is parked":

```java
assertFalse("the data commit must be blocked while the schema write lock is held",
    dataCommitted.await(1, TimeUnit.SECONDS));
assertTrue("the data thread must be parked (not merely slow)",
    dataThread.getState() == Thread.State.WAITING
        || dataThread.getState() == Thread.State.TIMED_WAITING);
```

## Evidence base

#### C1: rename test is red because production records both names — CONFIRMED
PSI trace (`steroid_execute_code`, find-usages + method bodies): `SchemaClassProxy.setName(String)` -> `resolveForWrite()` records `resolved.getName()` (= `RenameBefore`, pre-mutation) via `recordWriteTarget` (SchemaClassProxy.java:58), then `changeClassName` records `RenameAfter` (SchemaShared.java:716); `markClassChanged` is `Set.add` (TxSchemaState.java:133-135). Final set `{RenameBefore, RenameAfter}` -> `assertFalse(contains("RenameBefore"))` fails. Introduced-commit trace (`git log -S`): test + `changeClassName` recording added by `00d81f43dc` (test green then); `recordWriteTarget` choke point added by Step-5 `1f431495a8` (test red since). Refutation attempted — "is it a real data-loss bug that raises severity?": refuted. Selective `toStream` (SchemaShared.java:1000-1070) uses `changedClassNames` only for write/skip (name match against live classes); drop loop is RID-keyed (`previouslyLinked` minus `liveRecords`, line 1061-1062); `allChangedLiveClassesWereWritten` exempts a changed name that is not a live class (line 1052). The stale old name is harmless to production; the defect is confined to the test (a red, build-breaking, stale assertion). Survives as a blocker on the test-behavior axis: a committed non-`@Ignore`d red test.

#### C2: failed-commit tests cover only the collection registry — CONFIRMED
Acceptance criterion (track-4.md Validation and Acceptance) names `collections` / `collectionMap` / `indexEngines` / `indexEngineNameMap`. `grep` over `SchemaCommitReconciliationTest.java`: `failedSchemaCommitLeavesNoPhantomRegistration` subject is `createClass("FailAtApply")` (line 276), `failedSchemaCommitWithDropRestoresDroppedRegistration` subject is `createClass("DropThenFail")` (line 331); neither calls `createIndex`. All failed-commit assertions read `getCollectionNames` / `getCollectionNameById`. The engine-registry undo arm of D10/A1-R1 is therefore unexercised. Refutation attempted — "is the engine arm tested elsewhere in the track diff?": the deferred-index tests in `SchemaDeguardTest` assert the index is *not* registered during a tx and remains unknown after rollback, but none injects an in-window commit fault, so none drives the deferred-publish-then-undo engine path. Gap stands.

#### C3: block proof is a timing-dependent negative wait — CONFIRMED (low severity)
`SchemaCommitReconciliationTest.java:493-494` is the sole positive block signal; passes for "blocked" and "merely slow" alike. Mitigations present and verified: `errors` AtomicReference (line 423, 509-511) surfaces any thrown failure; post-release `dataCommitted.await(30, SECONDS)` (line 500-501) proves eventual completion; `@Test(timeout = 60_000)` (line 409) is the deadlock net. Net effect: a weak intermediate assertion inside an otherwise-sound concurrency test — suggestion, not a confidence hole.
