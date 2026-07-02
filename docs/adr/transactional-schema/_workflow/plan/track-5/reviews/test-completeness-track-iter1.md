<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 4, suggestion: 2}
index:
  - {id: TC1, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/record/impl/EntityImpl.java:3949, anchor: "### TC1 ", cert: C1, basis: "I-P5 same-tx constraint enforcement tested only for strict-mode and regex; mandatory, notnull, type, min/max validateProperty branches unverified"}
  - {id: TC2, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:1060, anchor: "### TC2 ", cert: C2, basis: "commit-time build of a UNIQUE index whose same-tx rows carry a duplicate key is untested (doPut uniqueness + natural failed-commit cleanliness)"}
  - {id: TC3, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:906, anchor: "### TC3 ", cert: C3, basis: "committed membership-removal commit path (removeSuperClass ripple -> membershipRemoved) has no end-to-end test; only the add side is covered"}
  - {id: TC4, sev: should-fix, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:1103, anchor: "### TC4 ", cert: C4, basis: "failed-commit membership-revert arm undoAppliedMembership untested; the create-side and drop-side failure arms are tested, this third arm is not"}
  - {id: TC5, sev: suggestion, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java:1053, anchor: "### TC5 ", cert: C5, basis: "populateTxCreatedIndex null-key skip and multi-value Collection<?> key branches uncovered; every build test uses a scalar non-null STRING key"}
  - {id: TC6, sev: suggestion, loc: core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:3260, anchor: "### TC6 ", cert: C6, basis: "two tx-created indexes built in one commit (nextFreeIndexEngineId reuse across successive creates in one reconciliation) is untested"}
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

### TC1 [should-fix] Same-tx constraint enforcement (I-P5) is tested for only two of the six named constraint kinds

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/record/impl/EntityImpl.java` (validate, lines 3923-3952; per-property dispatch at line 3949-3951)
- **Missing scenario**: D21 / I-P5 exist because `EntityImpl.validate()` silently skipped every constraint on a same-tx-created class or property. The track's own Validation and Acceptance list names six kinds: strict-mode, mandatory, notnull, type, min/max, regex. The tests cover strict-mode (`strictModeOnTxCreatedClassIsEnforced...`, `strictModeAddedToACommittedClassInTx...`) and regex (`regexpAddedToACommittedClassProperty...`). No test drives a same-tx **mandatory**, **notnull**, **type (wrong Java type)**, or **min/max** violation.
- **Why it matters**: strict-mode is the class-level branch at line 3934-3946; regex is one branch inside `validateProperty`. Mandatory, notnull, type, and min/max are the other branches of `validateProperty`, each reading a different attribute (the mandatory flag, the notnull flag, the property type, min, max) off the `ImmutableSchemaProperty` the tx-local snapshot materializes. A regression where the tx-local snapshot build drops one of those attributes (or resolves the wrong property object for a mid-tx property change) would silently skip that one constraint while strict-mode and regex still pass, so the current tests would stay green while I-P5 is broken for the most common constraints (mandatory and type).
- **Evidence**: input-domain row `constraint kind = {strict, mandatory, notnull, type, min/max, regex}` for validation on a tx-created/modified class — tested only for `{strict, regex}`; the other four rows are NO (see C1).
- **Refutation considered**: Could regex + strict cover the others by proxy? No — they prove the class resolves non-null and `validateProperty` dispatches, but each remaining kind reads a distinct property attribute, so a per-attribute drop is invisible to them. Could an existing pre-D21 test cover them? No — pre-D21 the tx-created class resolved to null and every check was skipped, so no prior test can exist. Gap confirmed.
- **Suggested test**:
  ```java
  @Test
  public void mandatoryTypeAndNotnullAddedInTxAreEnforcedOnSameTxEntities() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("TxConstraintKinds");
    cls.createProperty("age", PropertyType.INTEGER);
    session.begin();
    try {
      var p = schema.getClass("TxConstraintKinds").getProperty("age");
      p.setMandatory(true);
      p.setNotNull(true);
      p.setMin("1");
      p.setMax("120");
      // mandatory: property absent
      var missing = (EntityImpl) session.newEntity("TxConstraintKinds");
      assertThrows(ValidationException.class, missing::validate);
      // type: wrong Java type for an INTEGER property
      var wrongType = (EntityImpl) session.newEntity("TxConstraintKinds");
      wrongType.setProperty("age", "not-an-int");
      assertThrows(ValidationException.class, wrongType::validate);
      // min/max: out of range
      var outOfRange = (EntityImpl) session.newEntity("TxConstraintKinds");
      outOfRange.setProperty("age", 999);
      assertThrows(ValidationException.class, outOfRange::validate);
    } finally {
      session.rollback();
    }
  }
  ```

### TC2 [should-fix] Commit-time build of a UNIQUE index whose same-tx rows carry a duplicate key is untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (`populateTxCreatedIndex`, lines 1021-1062, `doPut` at 1056/1060); `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexUnique.java` (`doPut` at line 53, which routes through `validatedPutIndexValue` with the unique validator)
- **Missing scenario**: The commit-time population feeds `doPut` per record. A tx-created **UNIQUE** index whose engine is unbuilt during the transaction never enforces uniqueness mid-tx (its tracked entries are stripped before the working set, per the Step 2 episode), so a transaction that creates a UNIQUE index and inserts two rows with the same key first meets uniqueness at the commit-time build. Every populated-build test uses NOTUNIQUE (`indexCreatedAndPopulated...` uses `alpha/beta/alpha`); the one UNIQUE build (`dropThenRecreate...ReplacesTheIndex`) inserts a single non-colliding row. No test drives a UNIQUE build over same-tx duplicate keys.
- **Why it matters**: two outcomes are unverified. (1) The build must reject the duplicate rather than silently accepting it (silent acceptance corrupts the unique invariant on durable bytes). (2) The rejection is a natural `doPut` exception (a duplicated-key exception, not the `InvalidIndexEngineIdException` the build's own catch wraps at line 815), so it propagates straight to the commit's failure-path arms. The failed-commit cleanliness tests only exercise an **injected** `CommandInterruptedException` through the post-build hook; a real duplicate-key failure is a different exception class reaching the same undo path, and whether it leaves no phantom engine and a reusable id is untested.
- **Evidence**: input-domain row `index type = {NOTUNIQUE built, UNIQUE built} x same-tx keys = {distinct, duplicate}` — the `UNIQUE x duplicate` cell is NO; the natural (non-injected) failed-commit exception path is NO (see C2).
- **Refutation considered**: Is uniqueness caught earlier so the build never sees the duplicate? No — during the tx the tx-created index is a deferred handle (indexId -1) and its tracked entries are stripped before the working set, so the build's re-derivation is the sole population and the sole uniqueness check. Does the injected-hook failed-commit test already cover the failure path? No — it throws a retry-family exception, not a duplicated-key exception; the class-of-exception difference is exactly what could route differently. Gap confirmed.
- **Suggested test**:
  ```java
  @Test
  public void uniqueIndexBuildRejectsSameTxDuplicateKeyAndLeavesNoPhantomEngine() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("UniqueBuildTarget");
    cls.createProperty("name", PropertyType.STRING);
    var indexName = "UniqueBuildTarget.name";
    session.begin();
    session.getMetadata().getSchema().getClass("UniqueBuildTarget")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "name");
    session.newEntity("UniqueBuildTarget").setProperty("name", "dup");
    session.newEntity("UniqueBuildTarget").setProperty("name", "dup");
    try {
      session.commit();
      fail("a UNIQUE build over same-tx duplicate keys must fail the commit");
    } catch (final RuntimeException expected) {
      // duplicate-key failure routed through the failure-path undo
    }
    assertFalse("a rejected UNIQUE build must leave no phantom index",
        session.getSharedContext().getIndexManager().existsIndex(indexName));
    assertEquals("the failed build's engine id must be freed", -1,
        ((AbstractStorage) session.getStorage()).loadIndexEngine(indexName));
    // a later valid build reuses the freed slot and succeeds
    session.executeInTx(tx -> session.getMetadata().getSchema().getClass("UniqueBuildTarget")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.UNIQUE, "name"));
    assertTrue(session.getSharedContext().getIndexManager().existsIndex(indexName));
  }
  ```

### TC3 [should-fix] The committed membership-removal commit path has no end-to-end test

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (`enrollReconciledIndexRecords` membership-removed loop, lines 906-916, calling `removeCollectionRecordAtCommit`); reachable via `SchemaClassImpl.removeCollectionFromIndexes` (line 1624) from `removePolymorphicCollectionId`, i.e. `removeSuperClass` inside a transaction
- **Missing scenario**: The overlay's `membershipRemoved` category has a full commit path — enroll re-writes the committed index's record removing the collection, and the parent index stops covering it — but no DB-level test drives it. `committedMembershipChangeMakesParentIndexCoverSubclassRows` covers only the **add** side (`addSuperClass`). There is no test that a committed `removeSuperClass` (or alter-remove-collection) makes the parent index stop covering the ex-subclass collection after commit.
- **Why it matters**: the add and remove sides are separate `IndexAbstract` mutators (`addCollectionRecordAtCommit` vs `removeCollectionRecordAtCommit`) and separate overlay maps. An error in the remove path — the collection stays in `collectionsToIndex`, so a dropped subclass's rows keep being indexed under the parent and a polymorphic lookup returns rows that should no longer be covered — would pass every current test. This is a wrong-results defect the missing test would catch. PSI confirms `removeCollectionFromIndex` is reachable from `removeSuperClass` (not dead code).
- **Evidence**: input-domain row `membership change = {added, removed}` end-to-end at commit — `added` is YES (`committedMembershipChange...`), `removed` is NO (see C3).
- **Refutation considered**: Does the overlay unit test (`recordMembershipRemovedStoresCollectionPerIndex`) cover it? No — it asserts only that the overlay records the pair; it never drives the commit persistence or the post-commit index membership. Is the remove path dead code? No — `removePolymorphicCollectionId -> removeCollectionFromIndexes -> removeCollectionFromIndex` is a live `removeSuperClass` path (grep-confirmed and PSI-consistent). Gap confirmed.
- **Suggested test**:
  ```java
  @Test
  public void committedMembershipRemovalMakesParentIndexStopCoveringExSubclassRows() {
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("MemRemParent");
    parent.createProperty("name", PropertyType.STRING);
    parent.createIndex("MemRemParent.name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    var child = schema.createClass("MemRemChild", parent);
    child.createProperty("name", PropertyType.STRING);
    var childCollection = session.getCollectionNameById(
        session.getMetadata().getSchema().getClass("MemRemChild").getCollectionIds()[0]);
    var index = session.getSharedContext().getIndexManager().getIndex("MemRemParent.name");
    assertTrue(new HashSet<>(index.getCollections()).contains(childCollection));

    session.executeInTx(tx -> session.getMetadata().getSchema().getClass("MemRemChild")
        .removeSuperClass(session.getMetadata().getSchema().getClass("MemRemParent")));

    assertFalse("after commit the parent index must no longer cover the ex-subclass collection",
        new HashSet<>(index.getCollections()).contains(childCollection));
  }
  ```

### TC4 [should-fix] The failed-commit membership-revert arm (`undoAppliedMembership`) is untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (`undoAppliedMembership`, lines 1103-1110), driven from the `AbstractStorage` commit failure path
- **Missing scenario**: The enroll phase mutates the committed `Index.collectionsToIndex` set **eagerly** and in memory (line 896-916), and a failed commit must revert it through `undoAppliedMembership`. The create-side (`failedEngineCreatingCommitLeavesNoPhantomEngineAndReusesId`) and drop-side (`failedDropCommitLeavesTheSurvivingCommittedIndexUsable`) failure arms both have tests; this third arm — a commit that carries a membership change and then fails — has none.
- **Why it matters**: this is exactly the isolation invariant the track protects. If `undoAppliedMembership` is wrong, a failed commit leaves the shared `Index.collectionsToIndex` permanently mutated: a collection stays added (or removed) in a process-shared committed index that other sessions read, with no rollback — the "mutating the shared Index (visible to other sessions and unreverted on rollback)" failure the design calls out. The post-build hook already exists as the fault-injection seam, so the test is cheap; the gap is that no one points it at a membership-changing commit.
- **Evidence**: input-domain row `failed-commit revert arm = {create-side, drop-side, membership}` — first two YES, membership NO (see C4).
- **Refutation considered**: Do the create/drop failure tests exercise the membership arm? No — neither installs a membership change, so `plan.appliedMembership()` is empty and `undoAppliedMembership` iterates nothing. Is the membership revert trivial enough to skip? No — it is the only guard keeping a shared committed index from carrying a rolled-back collection across sessions; a wrong add/remove polarity would corrupt shared state silently. Gap confirmed.
- **Suggested test**:
  ```java
  @Test
  public void failedCommitRevertsEagerMembershipMutation() {
    var storage = (AbstractStorage) session.getStorage();
    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("MemFailParent");
    parent.createProperty("name", PropertyType.STRING);
    parent.createIndex("MemFailParent.name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    var child = schema.createClass("MemFailChild");
    child.createProperty("name", PropertyType.STRING);
    var index = session.getSharedContext().getIndexManager().getIndex("MemFailParent.name");
    var before = new HashSet<>(index.getCollections());

    storage.setPostEngineBuildTestHook(() -> {
      throw new CommandInterruptedException(session.getDatabaseName(), "injected post-membership fault");
    });
    try {
      session.begin();
      child.addSuperClass(parent); // ripples the child collection into the parent index membership
      try {
        session.commit();
        fail("the membership-changing commit must fail when the hook throws");
      } catch (final RuntimeException expected) {
        // routed through undoAppliedMembership
      }
    } finally {
      storage.setPostEngineBuildTestHook(null);
    }
    assertEquals("a failed commit must revert the eager in-memory membership mutation",
        before, new HashSet<>(index.getCollections()));
  }
  ```

### TC5 [suggestion] The commit-time build's null-key and multi-value key branches are uncovered

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexManagerEmbedded.java` (`populateTxCreatedIndex`, lines 1049-1061: the `key instanceof Collection<?>` branch and the `!definition.isNullValuesIgnored() || key != null` null guard)
- **Missing scenario**: every populated-build test indexes a single scalar, non-null STRING property, so the population always takes the scalar-non-null path. The Collection-of-keys branch (a multi-value key, e.g. an index over an EMBEDDEDLIST/LINKLIST property) and the null-key handling (a row whose indexed property is null, with and without `nullValuesIgnored`) are never executed by the commit-time build.
- **Why it matters**: a multi-value index built at commit must emit one entry per element; a null-valued row must be included or skipped per `nullValuesIgnored`. A bug here (e.g. an NPE on a null key, or only the first list element indexed) is invisible today because no build test uses a non-scalar or null key.
- **Evidence**: input-domain row `build key shape = {scalar non-null, scalar null, multi-value Collection}` — only the first is exercised (see C5).
- **Refutation considered**: Is the multi-value branch dead for tx-created indexes? No — the same `getDocumentValueToIndex` contract that returns a Collection for multi-value indexes applies to a commit-time build; it is only untested here. Marginal severity because the v1 build is bounded to an empty source collection, so the branch runs only over the tx's own rows, and scalar STRING indexes dominate real usage. Kept as suggestion.
- **Suggested test**:
  ```java
  @Test
  public void buildIndexesMultiValueAndNullKeysFromTxRows() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("MultiValueBuild");
    cls.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    var indexName = "MultiValueBuild.tags";
    session.begin();
    session.getMetadata().getSchema().getClass("MultiValueBuild")
        .createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "tags");
    var e = (EntityImpl) session.newEntity("MultiValueBuild");
    e.setProperty("tags", List.of("red", "green"));
    var nullRow = (EntityImpl) session.newEntity("MultiValueBuild"); // tags null
    session.commit();
    var index = session.getSharedContext().getIndexManager().getIndex(indexName);
    assertEquals(1, session.computeInTx(tx -> index.getRids(session, "red").toList()).size());
    assertEquals(1, session.computeInTx(tx -> index.getRids(session, "green").toList()).size());
    // and assert the null-key row's presence/absence per nullValuesIgnored
  }
  ```

### TC6 [suggestion] Building two tx-created indexes in one commit is untested

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/CommitTimeIndexBuildTest.java`
- **Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (`nextFreeIndexEngineId`, line 3260, whose Javadoc claims id reuse "between successive creates within one reconciliation"); the create loop in `IndexManagerEmbedded.buildAndDropReconciledEngines` (line 998)
- **Missing scenario**: every create test builds exactly one tx-created index per commit (the drop test drops two, but no test creates two). The commit-local engine-id allocator's per-create advance — where the second create must not collide with the first's just-published slot — is never exercised.
- **Why it matters**: `nextFreeIndexEngineId` returns the first null slot; the correctness of building N indexes in one commit depends on each create publishing into its slot before the next scan. A regression that allocated the same id twice (or missed the second index) would only surface with two-or-more creates in a single transaction, which no test drives.
- **Evidence**: input-domain row `tx-created index count per commit = {1, >=2}` — only `1` is exercised for the create path (see C6).
- **Refutation considered**: Does the two-index drop test cover the allocator? No — the drop path uses `deleteIndexEngineInCommitWindow`, not the `nextFreeIndexEngineId` create allocator, so the multi-create reuse claim stays unverified. Marginal severity because same-tx multi-index creation is uncommon and each single-create test proves the allocator in isolation. Kept as suggestion.
- **Suggested test**:
  ```java
  @Test
  public void twoIndexesCreatedInOneTransactionBothBuildAndPublish() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("MultiCreate");
    cls.createProperty("a", PropertyType.STRING);
    cls.createProperty("b", PropertyType.STRING);
    session.begin();
    var c = session.getMetadata().getSchema().getClass("MultiCreate");
    c.createIndex("MultiCreate.a", SchemaClass.INDEX_TYPE.NOTUNIQUE, "a");
    c.createIndex("MultiCreate.b", SchemaClass.INDEX_TYPE.NOTUNIQUE, "b");
    session.commit();
    var im = session.getSharedContext().getIndexManager();
    assertTrue(im.existsIndex("MultiCreate.a"));
    assertTrue(im.existsIndex("MultiCreate.b"));
    assertTrue(im.getIndex("MultiCreate.a").getIndexId() >= 0);
    assertTrue(im.getIndex("MultiCreate.b").getIndexId() >= 0);
    assertNotEquals("the two builds must occupy distinct engine ids",
        im.getIndex("MultiCreate.a").getIndexId(), im.getIndex("MultiCreate.b").getIndexId());
  }
  ```

## Evidence base

Every claim below survived the Phase-4 refutation check as a genuine coverage gap, so each is rendered as a single line (per the YTDB-1069 survived-claim rendering).

#### C1 CONFIRMED — `EntityImpl.validate()` (line 3949) dispatches `validateProperty` for mandatory/notnull/type/min/max off the tx-aware snapshot; `TxAwareSchemaSnapshotTest` drives only strict-mode and regex, so four constraint kinds named in I-P5 and the acceptance list are unverified.

#### C2 CONFIRMED — `IndexUnique.doPut` (line 53) enforces uniqueness via `validatedPutIndexValue`; `populateTxCreatedIndex` (IndexManagerEmbedded:1056/1060) is the sole population/uniqueness point for a tx-created index, yet no build test pairs UNIQUE with a same-tx duplicate key, and the failed-commit tests use only an injected retry-family fault, not a natural duplicate-key exception.

#### C3 CONFIRMED — the `membershipRemoved` commit loop (IndexManagerEmbedded:906-916) is reachable from `removeSuperClass` via `SchemaClassImpl.removeCollectionFromIndexes` (line 1624, grep-confirmed); only the add side has an end-to-end test (`committedMembershipChangeMakesParentIndexCoverSubclassRows`).

#### C4 CONFIRMED — `undoAppliedMembership` (IndexManagerEmbedded:1103) reverts eager shared-state membership mutation on a failed commit; the create-side and drop-side failure arms have tests, but no test installs a membership change before an injected fault, so `plan.appliedMembership()` is always empty in the existing failure tests.

#### C5 CONFIRMED — `populateTxCreatedIndex` (IndexManagerEmbedded:1049-1061) has a `Collection<?>` multi-value branch and a null-key guard; every build test uses a scalar non-null STRING key, so neither branch executes.

#### C6 CONFIRMED — `nextFreeIndexEngineId` (AbstractStorage:3260) claims id reuse across successive creates in one reconciliation, but no test creates two tx-created indexes in one commit; the two-index drop test uses the delete path, not the create allocator.
