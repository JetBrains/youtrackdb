<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 3, suggestion: 3}
index:
  - {id: TC1, sev: should-fix, loc: SchemaDeguardTest.java:624, anchor: "### TC1 ", cert: C1, basis: "the abstract->concrete alter (setAbstract(false)) provisional-id producer is only ever rolled back, never committed; its provisional->real resolution at commit is wholly untested"}
  - {id: TC2, sev: should-fix, loc: SchemaCommitReconciliationTest.java:843, anchor: "### TC2 ", cert: C2, basis: "no committed test drives a property-level alter through the net-new SchemaPropertyProxy.recordWriteTarget hook; only the class-level SchemaClassProxy branch (setStrictMode) is covered"}
  - {id: TC3, sev: should-fix, loc: SchemaCommitReconciliationTest.java:673, anchor: "### TC3 ", cert: C3, basis: "the A3 cross-class provisional-id re-key (commit a superclass AND a same-tx subclass, subclass's polymorphicCollectionIds carries the parent's provisional id) is never committed; the two-class tests create independent, cross-reference-free classes"}
  - {id: TC4, sev: suggestion, loc: SchemaCommitReconciliationTest.java:782, anchor: "### TC4 ", cert: C4, basis: "the F59 global-property-table root-omission arm is only white-box tested via createGlobalProperty; no commit-then-restart end-to-end test (documented deferral pending property de-guarding)"}
  - {id: TC5, sev: suggestion, loc: SchemaCommitReconciliationTest.java:211, anchor: "### TC5 ", cert: C5, basis: "nextFreeCollectionId hole-reuse on the SUCCESS path (drop a class, then commit a create that reuses the freed slot via setCollection grow-with-gap) is exercised only after a FAILED commit"}
  - {id: TC6, sev: suggestion, loc: SchemaCommitReconciliationTest.java:88, anchor: "### TC6 ", cert: C6, basis: "a seeded-but-empty-changed-set schema-carry commit (commitSchemaCarry with getChangedClasses() empty) is never committed; the no-op-write boundary that still takes the four locks is unpinned"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 6}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
flags: [CONTRACT_OK]
-->

## Findings

### TC1 [should-fix] The abstract→concrete alter provisional-id producer is never committed

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java`
**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaClassEmbedded.java` (the `setAbstractInternal` provisional branch, diff lines 95-115) resolved through `AbstractStorage.reconcileCollections` and `SchemaShared.resolveProvisionalCollectionIds`.

**Missing scenario**: Switch an abstract class to concrete inside a transaction (`setAbstract(false)`) and **commit**, then assert the class gained a real (`>= 0`) collection that exists in storage and survives a reload.

**Why it matters**: The Decision Log (2026-06-26T12:52Z) and the Step 2 episode both record that there are *two* provisional-collection-id producers, not one: the create path (`SchemaEmbedded.createCollections`) and the abstract→concrete alter path (`SchemaClassEmbedded.setAbstractInternal`). The track's correctness rests on the commit resolving the provisional id from **both** producers before any record serializes. Every committed reconciliation test exercises only the create producer. The single `setAbstract(false)` test, `rolledBackInTransactionSetAbstractFalseLeavesNoCollectionOnDisk`, **rolls back** — it pins the tx-local-view and no-stray-collection halves but never drives the alter's provisional id through `reconcileCollections` → `recordResolvedCollectionId` → `resolveProvisionalCollectionIds`. So a regression in the alter producer's commit path — the patch list missing the alter-allocated id, the real collection created under the wrong carried name, or the `defaultCollectionId`/`collectionIds[0]` reassignment in `setAbstractInternal` (diff line 117-118) not being re-pointed — would commit a structurally broken concrete class and surface only as "class lost its collection" at the next open. The `toStream` provisional-id assert (a `-ea`-only guard) would catch a surviving `<= -2` id in tests, but only if a test actually *commits* this path; none does.

**Evidence**: Input-domain row "provisional-id producer = `setAbstractInternal` (abstract→concrete alter)" / boundary "commit (resolve to real)": tested NO (the only test rolls back). PSI confirms `SchemaShared.resolveProvisionalCollectionIds` is reached only from `AbstractStorage.applyCommitOperations`, and the test enumeration shows zero committed `setAbstract(false)`.

**Refutation considered**: Is the alter's commit path the same code as the create's, so the create tests cover it transitively? No — both producers allocate via `TxSchemaState.allocateProvisionalCollectionId`, but the alter mutates an *already-committed* class in place (it re-points `defaultCollectionId`, `collectionIds[0]`, and records the existing class changed), whereas a create builds a fresh class. The reconcile create-loop iterates `getProvisionalCollectionNames()` and is shared, but the per-class patch (`replaceProvisionalCollectionIds` over an existing class that already carried real-then-provisional ids) and the in-place reassignment are alter-specific and never committed. Confirmed gap; should-fix (silent post-commit structural corruption, not a crash).

**Suggested test**:
```java
@Test
public void inTransactionSetAbstractFalseResolvesToRealCollectionAtCommit() {
  session.getMetadata().getSchema().createAbstractClass("AlterToConcrete");

  session.executeInTx(
      tx -> session.getMetadata().getSchema().getClass("AlterToConcrete").setAbstract(false));

  var cls = session.getSharedContext().getSchema().getClass("AlterToConcrete");
  assertFalse("the class must be concrete after commit", cls.isAbstract());
  for (var id : cls.getCollectionIds()) {
    assertTrue("no provisional id may survive the alter commit, was " + id, id >= 0);
    assertNotNull("the resolved real collection must exist", session.getCollectionNameById(id));
  }
  // Survives a durable round trip with a real id.
  session.getSharedContext().getSchema().reload(session);
  reOpen("admin", ADMIN_PASSWORD);
  var after = session.getSharedContext().getSchema().getClass("AlterToConcrete");
  assertFalse(after.isAbstract());
  assertTrue(after.getCollectionIds()[0] >= 0);
}
```

### TC2 [should-fix] No committed test drives a property-level alter through `SchemaPropertyProxy.recordWriteTarget`

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java`
**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaPropertyProxy.java` (the net-new `recordWriteTarget` override, diff lines 345-357) → `SchemaProxedResource.resolveForWrite` → `SchemaShared.toStream` selective write.

**Missing scenario**: Inside a transaction, alter a *property* of a committed class (rename it, retype it, or change a property attribute) and commit, then assert the change survived through promotion and a reload.

**Why it matters**: Step 5's review found that several tx-reachable mutations did not call `markClassChanged` and would be silently dropped by the selective write; the fix added `recordWriteTarget` at the proxy choke point, with *two distinct subclass implementations*: `SchemaClassProxy.recordWriteTarget` marks the class itself, and `SchemaPropertyProxy.recordWriteTarget` marks the property's **owner class** (a property mutation is serialized inside the owner's per-class record). `attributeAlterOutsideCreateDropRenameSurvivesCommit` covers the `SchemaClassProxy` branch (via `setStrictMode`) end-to-end, but no committed test routes through the `SchemaPropertyProxy` branch. That branch is more error-prone: it resolves `resolved.getOwnerClass()` and marks *that* name, so a regression where the property's owner is resolved against the wrong (committed vs tx-local) class, or `getOwnerClass()` returns null on a freshly rebound property, would drop the owner class from the changed set — the change is then lost in memory (promotion re-parses the stale record) and on disk, with the commit reporting success. A property retype additionally grows the global-property table, coupling this to the F59 root-payload arm. The property text scan shows `createProperty` appears only in committed-path setup in `SchemaDeguardTest`, never as an in-tx property alter with a survives-commit assertion.

**Evidence**: Input-domain row "`recordWriteTarget` subclass = `SchemaPropertyProxy`" / boundary "in-tx property rename/retype then commit": tested NO. The `SchemaClassProxy` sibling branch is tested YES (`setStrictMode`, reload-verified). PSI/text: `setName(`/`setType(`/`createProperty` never appear in an in-tx-then-commit shape in either new test for a property of a committed class.

**Refutation considered**: Is the property branch unreachable because property mutations are still throw-guarded inside a transaction? The `rootPayloadDiffersFromDetectsEachPayloadComponent` Javadoc and the `classCreateAdvancesCounter...` Javadoc both note in-transaction property-create is "still throw-guarded against an active transaction." If a property *rename/retype on an existing property* is likewise throw-guarded today, the `SchemaPropertyProxy.recordWriteTarget` branch is unreachable from a user transaction and this drops to a suggestion. But the hook was added deliberately as live code (not a no-op), the choke-point Javadoc claims it fires "for every current and future class mutation," and the override dereferences `getOwnerClass()` as if reachable — so at minimum one test should pin whether a property alter is reachable-and-survives or still-guarded, so a later track de-guarding property ops cannot silently leave this branch uncovered. Confirmed as a should-fix on that basis; reduce to suggestion if property alters are verified fully throw-guarded for the whole of Part 1.

**Suggested test**:
```java
@Test
public void inTransactionPropertyAlterSurvivesCommitViaPropertyChokePoint() {
  // Build a committed class with a property.
  session.executeInTx(tx -> {
    var c = session.getMetadata().getSchema().createClass("PropAlterTarget");
    c.createProperty("p", PropertyType.STRING);
  });
  var classRid = session.getSharedContext().getSchema().getClass("PropAlterTarget").getRecordId();

  // If a property alter is reachable inside a tx, it must route through SchemaPropertyProxy and
  // survive commit + reload. If it still throws, pin that instead (assertThrows) so the contract is
  // explicit and a later de-guarding track must update this test.
  session.executeInTx(tx ->
      session.getMetadata().getSchema().getClass("PropAlterTarget").getProperty("p")
          .setName("renamed"));

  session.getSharedContext().getSchema().reload(session);
  reOpen("admin", ADMIN_PASSWORD);
  assertNotNull("the renamed property must survive the durable round trip",
      session.getSharedContext().getSchema().getClass("PropAlterTarget").getProperty("renamed"));
}
```

### TC3 [should-fix] Cross-class provisional-id re-key in one committed transaction (A3) is untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java`
**Production code**: `SchemaShared.resolveProvisionalCollectionIds` (the two-pass: patch every class's `collectionIds`/`polymorphicCollectionIds`, then rebuild `collectionsToClasses` wholesale — diff lines 553-581) and `SchemaClassImpl.replaceProvisionalCollectionIds` (the `polymorphicCollectionIds` patch, diff lines 199-220).

**Missing scenario**: In **one** transaction, create a superclass and a subclass of it, then commit. The subclass's `polymorphicCollectionIds` carries the *superclass's provisional collection id*; the commit must resolve every provisional id first, then re-key the reverse map, so the subclass ends up indexing the superclass's resolved real id.

**Why it matters**: D2's risk note and the Step 4 plan call out the A3 ordering explicitly: "a multi-class, multi-index commit resolves every provisional id to its real id first, then re-keys `collectionsToClasses` and re-points property values, so cross-class references settle before any record serializes." `resolveProvisionalCollectionIds` exists precisely for this — its two-pass structure and the wholesale reverse-map rebuild are only meaningful when one class's id array references an id another class produced. The only committed two-class test, `changingOneClassDoesNotRewriteAnUnrelatedClassRecord`, creates `DropMe` and `KeepMe` as **independent** classes with no inheritance, so each carries only its own provisional ids; the cross-class `polymorphicCollectionIds` re-key path is never driven through a commit. `membershipRippleRecordsChangedClassIntoTxLocalState` and `createClassInsideTransactionRecordsChangedClass` create a subclass-and-commit, but the superclass is **committed before the tx** (it already owns real ids), so there is no cross-class *provisional* reference to resolve. A regression in the two-pass ordering (rebuilding the reverse map before all classes are patched, or `replaceProvisionalCollectionIds` skipping `polymorphicCollectionIds`) would mis-key an inherited collection and route a subclass's records to the wrong collection — invisible to the current suite.

**Evidence**: Input-domain row "single-tx multi-class commit with cross-class collection reference (subclass inherits a same-tx superclass)": tested NO. PSI confirms `replaceProvisionalCollectionIds`'s only caller is `resolveProvisionalCollectionIds`, whose only commit caller is `applyCommitOperations`; the test enumeration shows no committed subclass-of-same-tx-superclass.

**Refutation considered**: Does `changingOneClassDoesNotRewriteAnUnrelatedClassRecord` (two classes, committed) cover the multi-class boundary adequately? It covers multi-class *write selectivity* but not cross-class *id resolution* — its two classes share no collection id, so the two-pass rebuild has nothing to settle between them. Is a same-tx subclass of a same-tx superclass reachable? Yes — `schema.createClass("Sub", superProxy)` where `superProxy` is the same tx's create resolves through the proxy and inherits the parent's (provisional) polymorphic ids. Confirmed gap; should-fix because mis-keyed inheritance silently misroutes records.

**Suggested test**:
```java
@Test
public void multiClassInheritanceInOneTxResolvesCrossClassProvisionalIds() {
  session.executeInTx(tx -> {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("InhParent");
    schema.createClass("InhChild", parent);   // child's polymorphic ids include parent's provisional id
  });

  var committed = session.getSharedContext().getSchema();
  var parent = committed.getClass("InhParent");
  var child = committed.getClass("InhChild");
  // Every collection id on both classes is a resolved real id...
  for (var id : child.getCollectionIds()) {
    assertTrue("child collection id must be resolved real, was " + id, id >= 0);
  }
  // ...and the child's polymorphic set contains the parent's resolved real default collection,
  // proving the cross-class re-key settled the inherited id, not a stale provisional one.
  var childPoly = new HashSet<Integer>();
  for (var id : child.getPolymorphicCollectionIds()) { childPoly.add(id); }
  assertTrue("child polymorphic ids must include the parent's resolved real default collection",
      childPoly.contains(parent.getDefaultCollectionId()));
}
```

### TC4 [suggestion] The F59 global-property-table root-omission arm is only white-box tested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java`
**Production code**: `SchemaShared.rootPayloadDiffersFrom` (the global-property-table comparison, diff lines 865-894) and the selective root write in `toStream` (diff lines 786-805).

**Missing scenario**: A committed transaction that grows the global-property table (a property-create), followed by a restart, asserting the root record was rewritten so the table — and not just the counter — survived (the actual F59 silent-cross-restart-corruption case D6 names).

**Why it matters**: F59 is the regression D6 and the track purpose flag as the silent corruption this guard prevents: omit the root after a property-create and the restart loses the `globalRef` and reverts the counter. The commit decides "rewrite the root" from `rootPayloadDiffersFrom`, which has three arms (counter, blob set, global-property table). The **counter** arm is verified end-to-end through a real commit-then-restart (`classCreateAdvancesCounterPersistedThroughRestartSoNamesDoNotCollide`). The **global-property-table** arm — the one whose name "F59" comes from a property-create — is exercised only by `rootPayloadDiffersFromDetectsEachPayloadComponent`, which calls `createGlobalProperty(...)` directly on a copy and asserts the predicate flips; no test commits a table-growing operation and reloads. So a regression in the *selective-write consumption* of the table arm (the predicate flips correctly but `toStream` still omits the root, or the table arm is computed against the wrong before-state) would not be caught by the durable path.

**Evidence**: Input-domain row "`rootPayloadDiffersFrom` arm = global-property table" / boundary "commit-then-restart end-to-end": tested NO (white-box predicate test only). The counter arm at the same boundary: tested YES. PSI shows `rootPayloadDiffersFrom` is consumed by `applyCommitOperations` and asserted only by the white-box test.

**Refutation considered**: The test file's own Javadoc explains the gap and its rationale: in-transaction property-create is still throw-guarded, so the table-grow operation is "not reachable yet," and an end-to-end test "waits for the property-operation de-guarding in a later track." This is a documented, justified deferral, so it is not a blocker or should-fix per the track-review guidance — but it is worth a suggestion-level breadcrumb test (an `@Ignore` placeholder mirroring `crashBeforeCommitOfSchemaCreate...`) so the F59 end-to-end gap is visible at the test surface the way the crash gap is, rather than living only in a Javadoc aside. Confirmed-but-low-value because the production arm is otherwise white-box covered and the operation is genuinely unreachable today.

**Suggested test**:
```java
@Test
@Ignore("F59 global-property-table arm end-to-end: in-tx property-create is still throw-guarded; "
    + "covered white-box by rootPayloadDiffersFromDetectsEachPayloadComponent until a later track "
    + "de-guards property ops")
public void propertyCreateRewritesRootSoGlobalTableSurvivesRestart() {
  // Intentionally empty placeholder so the F59 end-to-end gap is visible at the test surface,
  // matching crashBeforeCommitOfSchemaCreateLeavesNoCollectionAfterRestore.
}
```

### TC5 [suggestion] Commit-local id-allocator hole reuse is tested only after a failed commit

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java`
**Production code**: `AbstractStorage.nextFreeCollectionId` (first-null-slot scan, diff lines 1969-1983), `setCollection`/`setIndexEngine` grow-with-gap, and `reconcileCollections`'s create loop.

**Missing scenario**: In one committed transaction drop a class (freeing its collection slot), then in a **later committed** transaction create a class that the allocator places into that freed hole (`collections.get(realId) == null`, `realId < collections.size()`), and assert the reused-slot collection is correct and durable.

**Why it matters**: Step 1's surprise log flags that `setCollection`/`setIndexEngine` are grow-and-set so "Step 2's commit-local allocator must handle a reused hole id below the live size," and `reconcileCollections` carries a `-ea` assert that the allocator never returns an occupied slot. The success path through a reused hole (the grow-with-gap branch where `id < size`) is exercised only inside `failedSchemaCommitLeavesNoPhantomRegistration` — and there the reuse happens after a **rolled-back** create, i.e. the slot was freed by the failure-path undo, not by a committed drop. A committed drop-then-create-into-the-hole would drive `nextFreeCollectionId` to return a hole that a prior *successful* drop left, and `doCreateCollection` + `registerCollection` to populate it. A regression where the allocator returned a stale or occupied slot on the success path, or `setCollection` mishandled the below-size set, would corrupt a live collection — the assert guards it under `-ea`, but only if a committed test reaches the hole-reuse branch.

**Evidence**: Input-domain row "`nextFreeCollectionId` returns a reused hole (`realId < collections.size()`)" / boundary "after a *committed* drop, on the *success* path": tested NO. After a *failed* commit: tested YES (`ReuseAfterFail`). The append-at-end branch (`realId == size`) is the common case every create test hits.

**Refutation considered**: Does `droppedClassRemovesItsCollectionAcrossCommit` plus a later create cover it? No — that test drops and reloads, it never creates a new class afterward to reuse the freed slot, and after a reload the allocator re-seeds from the on-disk `collections` list (the hole may or may not persist). The specific in-memory hole-reuse on a live storage, success path, is uncovered. Suggestion severity: the `-ea` assert plus the failed-commit test give partial coverage, so a silent success-path corruption is unlikely but the positive boundary still merits one cheap assertion.

**Suggested test**:
```java
@Test
public void committedDropThenCreateReusesTheFreedCollectionSlot() {
  session.executeInTx(tx -> session.getMetadata().getSchema().createClass("HoleDrop"));
  var freedId = session.getSharedContext().getSchema().getClass("HoleDrop").getCollectionIds()[0];

  session.executeInTx(tx -> session.getMetadata().getSchema().dropClass("HoleDrop"));
  // The slot freedId is now null in collections; a subsequent create should be able to reuse it.
  session.executeInTx(tx -> session.getMetadata().getSchema().createClass("HoleReuse"));

  var reused = session.getSharedContext().getSchema().getClass("HoleReuse");
  for (var id : reused.getCollectionIds()) {
    assertTrue("reused slot must be a real id", id >= 0);
    assertNotNull("the reused-slot collection must resolve in storage",
        session.getCollectionNameById(id));
  }
}
```

### TC6 [suggestion] A seeded-but-empty-changed-set schema-carry commit is never committed

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java`
**Production code**: `AbstractStorage.commitSchemaCarry` / `applyCommitOperations` schema-carry branch (the `getChangedClasses()`-empty case feeding `toStream(session, changedClasses, writeRootPayload)` with an empty set).

**Missing scenario**: A transaction whose tx-schema-state was seeded (so `getTxSchemaState() != null` and the commit takes the four-lock write-lock branch) but whose net schema delta is empty — for example a create-then-drop of the same class within one tx, or an index op that nets to nothing — then commit, asserting the commit completes cleanly, the structural diff is a no-op, and the root/per-class records are untouched.

**Why it matters**: The commit branches to `commitSchemaCarry` purely on tx-schema-state presence (the `isWriteTransaction` fix ORs it in so a schema-only tx reaches `storage.commit`). Once on that branch the commit takes all four locks, reconciles, resolves, serializes with an empty changed set, and promotes — a path with zero record operations and an empty `getRealCollectionIds()` set difference. `dropUnknownIndexInsideTransactionDoesNotThrowAndRecordsNoChangedClass` seeds the state with an empty changed set but then **rolls back**, never reaching `commitSchemaCarry`. A regression where the empty-changed-set selective `toStream` over-wrote the root (the `allChangedLiveClassesWereWritten` assert false-tripping, or `writeRootPayload` mis-evaluated for a no-delta commit), or where the empty set-difference reconcile threw, would surface only on this boundary. This is the standard "empty transaction / first operation" boundary applied to the schema-carry commit.

**Evidence**: Input-domain row "schema-carry commit with `getChangedClasses()` empty" / boundary "commit (not rollback)": tested NO. Every committed schema-carry test records at least one changed class (a create, drop, rename, or attribute alter). The only empty-changed-set test rolls back.

**Refutation considered**: Is a seeded-but-empty-net commit reachable? A create-then-drop of the same class within one tx seeds the state and records both names in the changed set, but the class is absent from the tx-local copy at commit (a drop), so the selective write's drop loop and the set-difference both no-op for it — a genuine empty *structural* delta on a non-empty changed set. A pure no-op (begin/commit with no DDL) never seeds the state, so it correctly skips `commitSchemaCarry` — that case is not the gap. Suggestion severity because the shared apply body is heavily exercised by the non-empty tests and the empty case is mostly a degenerate subset; still, the no-delta boundary through the four-lock branch has a distinct risk profile (over-write, false assert) worth one test.

**Suggested test**:
```java
@Test
public void createThenDropSameClassInOneTxCommitsAsAStructuralNoOp() {
  var rootRid = session.getSharedContext().getSchema().getIdentity();
  var rootVersionBefore = recordVersion(rootRid);
  var namesBefore = new HashSet<>(session.getCollectionNames());

  session.executeInTx(tx -> {
    var schema = session.getMetadata().getSchema();
    schema.createClass("Ephemeral");
    schema.dropClass("Ephemeral");   // net structural delta is empty; changed set is non-empty
  });

  assertFalse("the ephemeral class must not be in the committed schema",
      session.getSharedContext().getSchema().existsClass("Ephemeral"));
  assertEquals("a structurally-empty schema-carry commit must leave the collection set unchanged",
      namesBefore, new HashSet<>(session.getCollectionNames()));
  // No leftover provisional-named collection, and the root is not spuriously rewritten beyond what
  // the link-set churn requires (pin whichever the chosen contract is so a regression is visible).
}
```

## Evidence base

#### C1 — abstract→concrete alter commit-resolve path uncovered — CONFIRMED
`SchemaClassEmbedded.setAbstractInternal` allocates a provisional id on the `txLocal && !seeding` branch (diff 98-113) and re-points `defaultCollectionId`/`collectionIds[0]`; Decision Log 2026-06-26T12:52Z names it the second provisional-id producer. PSI: `SchemaShared.resolveProvisionalCollectionIds` (the commit-time resolver) is reached only from `AbstractStorage.applyCommitOperations`. The PSI test enumeration shows the only `setAbstract(false)` test, `rolledBackInTransactionSetAbstractFalseLeavesNoCollectionOnDisk`, has `rollsBack=true commits=false`. Survived refutation: the alter mutates an existing class in place (distinct per-class patch + in-place reassignment), not shared with the create-producer path the committed tests cover.

#### C2 — SchemaPropertyProxy.recordWriteTarget branch untested — CONFIRMED
`SchemaPropertyProxy.recordWriteTarget` (diff 345-357) is a net-new override that marks `resolved.getOwnerClass().getName()`, a separate branch from `SchemaClassProxy.recordWriteTarget`. Text scan: `SchemaCommitReconciliationTest` exercises `setStrictMode` (3×, the class branch, reload-verified) and one `setName` (rename); no in-tx property rename/retype of a committed class with a survives-commit assertion. `createProperty` in `SchemaDeguardTest` (7×) is committed-path setup only. Survived refutation conditionally: if property alters on existing properties are fully throw-guarded inside a tx for all of Part 1 the branch is unreachable and this drops to suggestion — but the hook is live code dereferencing `getOwnerClass()` and its Javadoc claims "every current and future class mutation," so a test must pin reachable-and-survives vs still-guarded.

#### C3 — cross-class provisional re-key in one tx untested — CONFIRMED
`SchemaShared.resolveProvisionalCollectionIds` two-pass (patch all classes' arrays incl. `polymorphicCollectionIds` via `replaceProvisionalCollectionIds`, then rebuild `collectionsToClasses` wholesale — diff 553-581) exists for cross-class references; D2/Step-4 plan name the A3 resolve-then-re-key ordering. PSI: `replaceProvisionalCollectionIds`'s only caller is `resolveProvisionalCollectionIds`; its only commit caller is `applyCommitOperations`. Test enumeration: the only committed two-concrete-class tx (`changingOneClassDoesNotRewriteAnUnrelatedClassRecord`, `subclassCreate=false`) uses independent classes; subclass-create-and-commit tests (`membershipRippleRecordsChangedClassIntoTxLocalState`, `createClassInsideTransactionRecordsChangedClass`) build the subclass under a *committed* (real-id) superclass, so no cross-class provisional reference is resolved. Survived refutation: independent-class write-selectivity is not cross-class id resolution; same-tx subclass-of-same-tx-superclass is reachable via `createClass(name, superProxy)`.

#### C4 — F59 global-property-table arm white-box-only — CONFIRMED
`rootPayloadDiffersFrom` (diff 865-894) has three arms; PSI shows it is consumed by `applyCommitOperations` and asserted only by `SchemaCommitReconciliationTest.rootPayloadDiffersFromDetectsEachPayloadComponent` (4 references, all in that one white-box method). The counter arm is the only one with a committed-then-restart end-to-end test (`classCreateAdvancesCounter...`). The global-table arm's end-to-end test is absent by documented design (in-tx property-create still throw-guarded). Survived refutation as low-value: documented justified deferral, production arm otherwise white-box covered, operation genuinely unreachable today — suggestion-level breadcrumb only, not a re-flagged blocker.

#### C5 — success-path id-allocator hole reuse untested — CONFIRMED
`nextFreeCollectionId` (diff 1969-1983) returns the first null slot; `reconcileCollections` asserts (`-ea`) the returned slot is free; `setCollection`/`setIndexEngine` carry the grow-with-gap branch Step 1's surprise reserved for the commit-local allocator. The only test reaching a reused hole, `failedSchemaCommitLeavesNoPhantomRegistration` ("ReuseAfterFail"), frees the slot via the *failure-path* undo, not a committed drop. `droppedClassRemovesItsCollectionAcrossCommit` drops + reloads but never creates afterward. Survived refutation: success-path hole reuse on a live in-memory storage (no reload re-seed) is a distinct branch; suggestion severity given the `-ea` assert + failed-commit partial coverage.

#### C6 — empty-changed-set schema-carry commit untested — CONFIRMED
`AbstractStorage.commit` branches to `commitSchemaCarry` on `getTxSchemaState() != null` alone; the `isWriteTransaction` fix (diff in `DatabaseSessionEmbedded`/`FrontendTransactionImpl`) makes a schema-only tx reach it. The selective `toStream(session, changedClasses, writeRootPayload)` and the `allChangedLiveClassesWereWritten` assert run for any seeded commit. The only empty-changed-set test, `dropUnknownIndexInsideTransactionDoesNotThrowAndRecordsNoChangedClass`, rolls back (`commits=false rollsBack=true`), never reaching `commitSchemaCarry`. A create-then-drop-same-class-in-one-tx commits with a non-empty changed set but an empty *structural* delta — the uncovered boundary. Survived refutation: a pure no-DDL begin/commit never seeds the state and correctly skips the branch, so the gap is the seeded-but-no-net-delta commit, not the trivial empty tx; suggestion severity since the shared apply body is well exercised otherwise.
