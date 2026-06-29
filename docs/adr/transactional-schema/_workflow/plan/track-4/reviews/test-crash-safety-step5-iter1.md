<!--
MANIFEST
dimension: test-crash-safety
step: 5
iteration: 1
commit_range: 920fa2aa00298238abf4bfa2eaca520a6b453cb4~1..920fa2aa00298238abf4bfa2eaca520a6b453cb4
verdict: CHANGES_REQUESTED
findings_total: 3
blockers: 0
should_fix: 1
suggestions: 2
evidence_base: "## Evidence base"
cert_index: [C1, C2, C3, C4, C5]
flags: []
index:
  - id: TY1
    sev: should-fix
    anchor: "#ty1-no-negative-test-that-a-class-rename-leaves-the-root-record-unwritten-the-rename-arm-of-the-write-amplification-win"
    loc: "SchemaCommitReconciliationTest.java (new tests, whole block lines 614-829); SchemaShared.java:158-184 (toStream selective root-write decision)"
    cert: C3
    basis: "create + drop both write the root (tested); the structurally-inert rename must NOT write the root (writeRootPayload==false && linkSetChanged==false) and has no test; PSI confirms setName has no tx-active throw-guard, so the test is writable today"
  - id: TY2
    sev: suggestion
    anchor: "#ty2-add-a-zero-cost-assert-that-every-still-live-changed-class-name-was-consumed-by-the-selective-write-loop"
    loc: "SchemaShared.java:99-138 (toStream per-class loop, selective branch)"
    cert: C4
    basis: "the method's own Javadoc names the case-mismatch silent-skip hazard (a changed class's record write silently skipped); no assert verifies the changed set was consumed; the drop subtlety makes the condition non-trivial, so extract to a helper"
  - id: TY3
    sev: suggestion
    anchor: "#ty3-the-restart-counter-test-does-not-pin-the-pre-restart-collision-free-baseline"
    loc: "SchemaCommitReconciliationTest.java:621-651 (classCreateAdvancesCounterPersistedThroughRestartSoNamesDoNotCollide)"
    cert: C5
    basis: "the test asserts uniqueness only after the restart; a pre-restart uniqueness assertion would localize a regression to the persistence step vs the generator, and a counter-value assertion would tighten it further"
-->

## Findings

### TY1 [should-fix] No negative test that a class rename leaves the root record unwritten (the rename arm of the write-amplification win)

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java` (new test block, diff lines 614-829)
**Production code**: `SchemaShared.java:158-184` — the selective root-write decision (`final boolean linkSetChanged = classLinks != null; if (changedLower == null || writeRootPayload || linkSetChanged) { … }`)

**Evidence**: CRASH POINT / RECOVERY CHECK (cert C3). The step adds three tests that exercise the *positive* root-write arms: `classCreateWritesRootForTheNewClassLink` (link-set grew → root written), `classDropWritesRootForTheRemovedClassLink` (link-set shrank → root written), and `changingOneClassDoesNotRewriteAnUnrelatedClassRecord` (an unrelated *per-class* record stays out of the write set). The production Javadoc the step adds spends its longest passage reasoning about the one arm none of these cover:

> "A class rename rewrites that class's record but leaves the root link set and payload untouched, so the root is not rewritten"

That is the rename case, where `writeRootPayload == false` (the counter, global-property table, and blob set are all unchanged by a rename) **and** `linkSetChanged == false` (the renamed class keeps its bound RID, so no link add or remove fires, so `classLinks` stays `null`). It is the only path through the new `if` guard that takes the *false* branch and leaves the root record entirely untouched — the write-amplification win for a rename. No test asserts the root `recordVersion` is unchanged after a rename, so the false branch of the central new conditional is unverified. A regression that over-wrote the root on every commit (keying the write on "any class changed" rather than on a real payload-or-link delta) would pass all three new tests and silently lose the win this step exists to deliver.

**Missing scenario**: A class is created and committed; the root record's version is captured. In a second transaction the class is renamed and committed. The renamed class's *per-class* record must be rewritten (its version bumps), but the *root* record must NOT be rewritten (its version is unchanged), and after a durable reload the new name resolves and the collection ids are unchanged (the rename is structurally inert, D9). PSI confirms `SchemaClassEmbedded.setName` carries no `session.getTransactionInternal().isActive()` throw-guard (unlike `addProperty`), so this test is writable end-to-end today.

**Why it matters**: The rename arm is the load-bearing negative case for I-U1. The positive arms (create/drop write the root) only prove the root is written when it *should* be; they cannot catch the over-write regression. Without the rename test, the assertion "the root stays out of the write set unless its payload or link set actually changed" rests entirely on the white-box `rootPayloadDiffersFrom` test, which checks the *predicate* but not that `toStream` *acts* on a `false` result by leaving the record untouched. The two together would close the loop; today the `toStream` side of the false branch is untested.

**Suggested test**:
```java
/**
 * A class rename is structurally inert (D9): it rewrites only the renamed class's per-class record
 * and must leave the root record untouched, because the rename changes neither the class link set
 * (the bound RID is unchanged) nor the root non-link payload (counter, global-property table, and
 * blob set are all unchanged). This is the rename arm of the write-amplification win — the false
 * branch of the selective root-write guard. A regression that rewrote the root on every commit
 * would fail here while passing the create/drop root-write tests.
 */
@Test
public void classRenameRewritesOnlyTheClassRecordAndLeavesTheRootUnwritten() {
  session.executeInTx(tx -> session.getMetadata().getSchema().createClass("RenameFrom"));

  var classRid = schemaShared().getClass("RenameFrom").getRecordId();
  var rootRid = schemaShared().getIdentity();
  var idsBefore = schemaShared().getClass("RenameFrom").getCollectionIds();
  var classVersionBefore = recordVersion(classRid);
  var rootVersionBefore = recordVersion(rootRid);

  session.executeInTx(
      tx -> session.getMetadata().getSchema().getClass("RenameFrom").setName(session, "RenameTo"));

  assertTrue("the renamed class's per-class record must be rewritten",
      recordVersion(classRid) > classVersionBefore);
  assertEquals(
      "a structurally-inert rename must NOT rewrite the root record (the write-amplification win)",
      rootVersionBefore, recordVersion(rootRid));

  // The rename survives a durable round trip with its collection ids unchanged.
  schemaShared().reload(session);
  reOpen("admin", ADMIN_PASSWORD);
  assertNotNull("the renamed class must resolve under its new name after reload",
      schemaShared().getClass("RenameTo"));
  assertEquals("a rename keeps its collection ids (structurally inert, D9)",
      java.util.Arrays.toString(idsBefore),
      java.util.Arrays.toString(schemaShared().getClass("RenameTo").getCollectionIds()));
}
```

---

### TY2 [suggestion] Add a zero-cost assert that every still-live changed-class name was consumed by the selective-write loop

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:99-138` (the per-class loop, selective branch)

**Evidence**: INVARIANT analysis (cert C4). The method's own Javadoc states the hazard the case-insensitive lowering exists to prevent:

> "match on the lowercased name rather than risk a case mismatch silently skipping a changed class's record write"

The lowering closes the case-mismatch path, but nothing verifies the *outcome*: that every name the transaction marked changed (and that is still a live class) actually matched a live class in the loop and had its per-class record rewritten. A future refactor of `markClassChanged`, the name-normalization, or the live-class iteration could re-open the silent-skip path, and it would surface only as a missing per-class record after a restart (the F58-shaped silent corruption) — invisible to a passing commit.

**Invariant**: On the selective path (`changedLower != null`), every entry in `changedLower` that names a still-live class must have been visited and rewritten by the per-class loop. (Names that were marked changed but are *not* live — a dropped class records its name via `markClassChanged` too, per Track 3 — are handled by the separate link-set drop loop, so the assert condition is "consumed by the live loop OR not present in `realClasses`", not "consumed by the live loop".)

**Suggested assertion** (the condition is non-trivial because of the drop subtlety, so extract it to a helper per the JaCoCo+`assert` guidance in architecture.md):
```java
// after the per-class loop, before the drop loop:
assert allChangedLiveClassesWereWritten(changedLower, realClasses, writtenClassNamesLower)
    : "a changed class that is still live was not rewritten by the selective per-class write; "
        + "a changed-class name failed to match a live class (the case/normalization silent-skip "
        + "hazard the lowercasing exists to prevent)";
```
where the loop accumulates `writtenClassNamesLower` (the lowercased names it rewrote or created) and the helper checks that every `changedLower` entry is either in `writtenClassNamesLower` or absent from the live set (a drop).

**Catches**: A normalization or marking regression that drops a changed class's per-class record from the write set — caught during any `-ea` test that renames/alters a class, instead of only on a post-restart schema read.

---

### TY3 [suggestion] The restart counter test does not pin the pre-restart collision-free baseline

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java:621-651` (`classCreateAdvancesCounterPersistedThroughRestartSoNamesDoNotCollide`)

**Evidence**: RECOVERY CHECK (cert C5). The test is sound — it genuinely exercises the restart (`reload` re-parses the persisted root record, which re-derives `collectionCounter` from the record bytes; on a regression that omitted the root write the persisted record carries the stale counter, and the second class draws a colliding suffix). The single observable, though, is "the post-restart collection-name set has no duplicates." That assertion fires only after both classes and the restart, so a failure does not distinguish a persistence regression (the root write was omitted) from a name-generator defect (the generator produced a collision independent of persistence). Adding a pre-restart uniqueness assertion, and ideally asserting the *persisted* counter value advanced across the restart, would localize the failure to the persistence step this test owns.

**Missing scenario**: Assert collection-name uniqueness once after the first create (before `reload`/`reOpen`) to establish that the generator itself is collision-free, so the post-restart duplicate can only come from a reverted counter; optionally read the root record's `collectionCounter` property after the restart and assert it is >= the value the first create advanced it to.

**Why it matters**: It is a localization/diagnosability improvement, not a coverage gap — the test already catches the regression. Without it, a red test points at "names collide" rather than at "the counter did not persist," costing a debugging hop. Low priority because the headline regression is caught either way.

**Suggested test** (augment in place):
```java
// after the first create, before reload/reOpen:
var firstNames = new ArrayList<>(session.getCollectionNames());
assertEquals("the generator must already be collision-free before the restart",
    firstNames.size(), new HashSet<>(firstNames).size());
// (existing reload + reOpen + second create + post-restart uniqueness assertion follow)
```

---

## Evidence base

Phase-3 recovery-path verification of every claim the new tests and asserts rest on. A claim that survived the refutation check (CONFIRMED, no finding) is one line; a refuted or finding-producing claim is shown in full.

#### C1 — The restart counter test genuinely re-parses persisted bytes (CONFIRMED)
`reload(session)` calls `session.load(identity)` then `fromStream`, and `fromStream` re-derives `collectionCounter` and the global-property table from the loaded entity (PSI: `SchemaShared.reload`, `SchemaShared.fromStream`), so the regression is observable even on the default MEMORY profile, because the page cache holds the actually-written root-record bytes, not the live `SchemaShared` Java field. The `reOpen` after the `reload` adds a fresh storage open + shared-context re-parse on top; it strengthens the check rather than masking it. The test does not pass for the wrong reason. (This is why the Step-4 TY5 "reload only bites on disk" caveat does NOT apply to the new counter/payload tests — those re-parse record *bytes*, unlike a collection-file-existence check.)

#### C2 — The durable round-trip assertions on create/drop prove the root reached the page cache (CONFIRMED)
`classCreateWritesRootForTheNewClassLink` and `classDropWritesRootForTheRemovedClassLink` assert `recordVersion(rootRid)` advanced (the root was written), then `reload` + `reOpen` and re-check membership; the membership survives a `fromStream` re-parse of the persisted root link set. `recordVersion` loads the record fresh in a separate transaction and reads `getVersion()`, which increments on each storage write, so version-unchanged is a faithful "this record was not rewritten" observable and version-advanced is a faithful "was rewritten" observable.

#### C3 — Create and drop write the root; the structurally-inert rename must NOT (REFUTED → TY1)
The selective root-write guard `if (changedLower == null || writeRootPayload || linkSetChanged)` (SchemaShared.java:165) has a false branch reached only when the payload is unchanged AND the link set is unchanged — the rename case (same bound RID, no counter/global/blob change). The new tests cover the create and drop arms (both take the true branch via `linkSetChanged`) but not the rename arm (the false branch). `SchemaClassEmbedded.setName` has no tx-active throw-guard (PSI), so the negative test is writable today. The false branch of the central new conditional is untested.

#### C4 — The case-insensitive changed-class match is keyed correctly but its outcome is unguarded (REFUTED → TY2)
`changedLower` is built from `getChangedClasses()` (PSI: `@Nonnull`, the live backing set; `markClassChanged` records the name the mutation saw — a create records its created name, a rename the new name) and matched against each live class's lowercased `getName()`. The keying is correct. What is missing is an assert that every still-live changed name was consumed by the loop; the Javadoc itself names the silent-skip hazard the lowering guards against, but only a runtime assert would catch a future regression. The condition is non-trivial because a dropped class's name is also in `getChangedClasses()` yet is intentionally absent from the live loop, so the assert must exempt drops.

#### C5 — The white-box `rootPayloadDiffersFrom` test is a faithful proxy and the F59 end-to-end gap is honestly documented (CONFIRMED)
PSI confirms `SchemaClassEmbedded.addProperty` (the single funnel for all `createProperty` overloads) throws `SchemaException("Cannot create property … inside a transaction")` when `session.getTransactionInternal().isActive()`, so the F59 global-property-table arm is genuinely unreachable end-to-end through the API. `findOrCreateGlobalProperty` is append-only (`id = properties.size()`, only adds), validating the slot-count-plus-signature comparison `rootPayloadDiffersFrom` performs. The white-box test drives all three payload arms (counter via `nextCollectionIndex`, blob via `addBlobCollection(ABSTRACT_COLLECTION_ID)`, global table via a direct `createGlobalProperty(session, name, type, newId)`) on a `copyForTx` copy and asserts both the no-change (no difference) and each-change (difference) outcomes; all four production signatures resolve (PSI). The gap (property-create throw-guarded, so only the white-box proxy plus the reachable counter arm cover F59) is stated in both the test Javadoc and the track Step 5 prompt — documented, not silently skipped. The two production `assert` statements under review are appropriate: `lock.isWriteLockedByCurrentThread()` (PSI: `lock` is `ReentrantReadWriteLock`, which exposes that query) protects the real write-lock-held serialization precondition, and the relocated `c.getRecordId() != null` assert protects the bound-RID-before-link-set invariant; both are pre-existing from Track 2 (context, not added lines) and carry zero production cost.
