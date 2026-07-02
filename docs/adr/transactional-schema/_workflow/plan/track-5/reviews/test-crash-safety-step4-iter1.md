<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 0, suggestion: 3}
index:
  - {id: TY1, sev: suggestion, loc: TxAwareSchemaSnapshotTest.java:131, anchor: "### TY1 ", cert: C1, basis: "I-A2 durable-bytes claim never read back across a storage reopen; verified only in-session"}
  - {id: TY2, sev: suggestion, loc: AbstractStorage.java:2509, anchor: "### TY2 ", cert: C2, basis: "no assert that the resolved real collection id is >= 0; a -1 resolution slips past the working-set assert and is silently masked"}
  - {id: TY3, sev: suggestion, loc: TxAwareSchemaSnapshotTest.java:208, anchor: "### TY3 ", cert: C3, basis: "no committed test with a mixed real+provisional working set exercising the rewrite's selectivity"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
flags: [CONTRACT_OK]
-->

## Findings

### TY1 [suggestion] I-A2 is verified only in-session; no reopen reads the record back from durable bytes

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java` (line 131, `commitSucceedsWhenTheTransactionCreatesAClassAndInsertsAnEntityOfIt`)
**Production code**: `AbstractStorage.java` (assert 2434-2435; `rewriteProvisionalRecordCollectionIds` 2490-2517; call site 2679-2680)

**Evidence (RECOVERY CHECK)**: I-A2 ("a provisional id never reaches durable bytes") is pinned two ways today, both in-session. (1) The `computeCommitWorkingSet` assert at 2434 fires on every commit under `-ea` (confirmed live: `core/pom.xml:36` argLine starts `-ea`), catching a provisional id at the last gather before serialization. (2) `commitSucceeds...` asserts the in-memory entity RID and the committed class's collection ids are all `>= 0` after commit, and a post-commit `select` returns the row. No test closes and reopens the storage to read the persisted record back from durable bytes.

**Missing scenario**: crash-analogue durability check. Create a class and insert an entity in one transaction, commit, close the storage so the commit flushes, reopen, then load/query the record and assert its collection id is real (`>= 0`) and its data survived. This is the literal form of I-A2's "durable bytes" claim. In a production JVM the assert is disabled, so `rewriteProvisionalRecordCollectionIds` is the only guard; a reopen confirms the bytes on disk (not just the in-memory / cache RID that the rewrite already patched) carry the reconciled real id.

**Why it matters**: a rewrite that wrote the wrong real id, or a serialization that persisted the provisional id while the JVM ran without `-ea`, would leave the record unreachable after restart (its collection is lost, per D2). The in-session query hits the class's real collection, so correct placement is covered functionally; the residual gap is durability across a restart. Coverage is already substantial (the assert plus Track 4's reconciliation reopen tests), so this is hardening, not an unprotected invariant.

**Suggested test** (disk profile, since in-memory storage does not persist across reopen):
```java
@Test
public void committedTxCreatedClassRecordCarriesARealCollectionIdAfterReopen() {
  // create class + insert in one tx, commit
  session.begin();
  session.getMetadata().getSchema().createClass("TxDurable");
  var e = (EntityImpl) session.newEntity("TxDurable");
  e.setProperty("name", "durable");
  var ridDuringTx = e.getIdentity();
  assertTrue(SchemaShared.isProvisionalCollectionId(ridDuringTx.getCollectionId()));
  session.commit();

  // reopen the database, bypassing the caches that already hold the rewritten RID
  reopenDatabase(); // close + open helper for the disk profile
  try (var rs = session.query("select from TxDurable where name = 'durable'")) {
    var row = rs.next();
    assertTrue("persisted record must carry a real collection id, got " + row.getIdentity(),
        row.getIdentity().getCollectionId() >= 0);
  }
}
```

### TY2 [suggestion] No assert that the resolved real collection id is a valid (`>= 0`) id

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (line 2502-2510, inside `rewriteProvisionalRecordCollectionIds`)

**Evidence (INVARIANT)**: after `txSchemaState.getResolvedCollectionId(collectionId)` returns a value other than `NO_RESOLUTION`, the code installs it as the record's real collection id with no check that it is a real (non-negative) collection. The downstream working-set assert at 2434 only tests `!isProvisionalCollectionId(id)`, which is `id >= -1`, so a resolution to `-1` (`COLLECTION_ID_INVALID`) passes that assert. Worse, `computeCommitWorkingSet`'s CREATED branch (2460-2469) then treats `collectionId == COLLECTION_ID_INVALID` as "fix from schema" and re-resolves it through `getCollectionForNewInstance`, silently masking the reconciliation bug and possibly landing the record in a different collection than the one reconciliation built.

**Invariant**: a resolved real collection id must be a real collection id, `realCollectionId >= 0`. Real collections are non-negative; `-1` is the abstract/invalid sentinel; `<= -2` is provisional. Reconciliation must resolve a provisional id to a real one.

**Suggested assertion** (immediately after the `NO_RESOLUTION` guard, before `setCollectionAndPosition`):
```java
assert realCollectionId >= 0
    : "reconciliation resolved provisional collection " + collectionId
        + " to non-real id " + realCollectionId + " for record " + rid;
```

**Catches**: a reconciliation defect that maps a provisional id to `-1` or another non-real value. Zero production cost, no side effects (pure comparison on an already-computed local, message built only on failure), and it fires at the rewrite site with a precise message rather than being papered over by the `COLLECTION_ID_INVALID` fix-up path downstream.

### TY3 [suggestion] No committed test with a mixed real + provisional working set

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxAwareSchemaSnapshotTest.java` (line 208, `sameTransactionQueryAndCommitReflectUpdatesAndDeletesOfTxCreatedClassRows`)
**Production code**: `AbstractStorage.java` `rewriteProvisionalRecordCollectionIds` (2496-2516) — the `if (!isProvisionalCollectionId) continue;` selectivity

**Evidence (TEST TRACE)**: every test that commits touches records of one tx-created class only, so their whole record set is provisional. `polymorphicQueryCachedBeforeTheTransactionSeesATxCreatedSubclassRows` (269) builds a scan set that mixes a real parent collection with a provisional child collection, which is the mixed shape, but it rolls back in `finally` and never commits. No committed test drives the rewrite loop over a working set that holds both a record already bound to a real collection (an insert/update into a pre-committed class) and a provisional-collection record in the same transaction.

**Missing scenario**: in one transaction, insert into a pre-existing committed class and create a class plus insert into it, then commit; assert both records land (the pre-committed-class record in its unchanged real collection, the tx-created-class record in its reconciled real collection). This exercises the rewrite skipping the real-collection record and rewriting only the provisional one, end to end through the durable commit.

**Why it matters**: the selectivity guard is the mechanism that keeps the rewrite from touching an already-real RID while still catching every provisional one. A regression that rewrote (or skipped) the wrong side would misplace a record; a mixed-commit test is the direct check. Low incremental risk given the delete test already commits multiple provisional records and the wider suite commits real-collection records, so this is completeness at the durable boundary rather than a live hole.

**Suggested test**:
```java
@Test
public void commitMixesAPreExistingClassInsertWithATxCreatedClassInsert() {
  var schema = session.getMetadata().getSchema();
  schema.createClass("Committed"); // committed before the tx: a real collection
  session.begin();
  var existing = (EntityImpl) session.newEntity("Committed"); // real collection id
  existing.setProperty("name", "real");
  schema.createClass("TxNew");                                 // provisional collection id
  var fresh = (EntityImpl) session.newEntity("TxNew");
  fresh.setProperty("name", "provisional");
  assertTrue(existing.getIdentity().getCollectionId() >= 0);
  assertTrue(SchemaShared.isProvisionalCollectionId(fresh.getIdentity().getCollectionId()));
  session.commit();

  try (var rs = session.query("select from Committed")) { assertEquals(1L, rs.stream().count()); }
  try (var rs = session.query("select from TxNew")) { assertEquals(1L, rs.stream().count()); }
  assertTrue(existing.getIdentity().getCollectionId() >= 0);
  assertTrue(fresh.getIdentity().getCollectionId() >= 0);
}
```

## Evidence base

#### C1 TY1 — no reopen/durable read-back for I-A2 (CONFIRMED)
I-A2 is pinned by the live 2434 assert (core `-ea` on) plus the in-session post-commit checks in `commitSucceeds...`; no test reads the record back from durable bytes across a reopen. Finding stands as hardening.

#### C2 TY2 — `realCollectionId >= 0` is unguarded and a `-1` resolution is silently masked (CONFIRMED)
The 2434 working-set assert accepts `id >= -1`, and the CREATED-branch `COLLECTION_ID_INVALID` fix-up (2460-2469) re-resolves a `-1`, so a reconciliation defect resolving to `-1` neither trips the assert nor fails loudly. Finding stands.

#### C3 TY3 — no committed mixed real+provisional working-set test (CONFIRMED)
All committing tests use a single tx-created class (all-provisional set); the only mixed-scan test rolls back. The rewrite selectivity is never exercised across a committed real+provisional set. Finding stands.

#### C4 "The I-A2 assert is a dead no-op because assertions are off" (REFUTED)
Assertions are enabled for core test runs: `core/pom.xml:36` opens its `<argLine>` with `-ea`. The `computeCommitWorkingSet` assert therefore fires in every core test that commits a tx-created-class entity (`commitSucceeds...`, `sameTransactionQueryAndCommitReflect...`, `queryPlanBuiltInside...`, and the polymorphic test's committed parent-row setup), so a provisional id reaching the working set would fail those tests. The assert is a live guard, not decorative. This is why TY1 is a suggestion, not a blocker: the invariant is genuinely pinned for tests.

#### C5 "The delete-through-commit rewrite path is untested" (REFUTED)
`sameTransactionQueryAndCommitReflectUpdatesAndDeletesOfTxCreatedClassRows` (208) creates three provisional-collection entities, deletes one, updates one, and commits. The deleted record's operation still carries a provisional collection id at commit time, so it drives `rewriteProvisionalRecordCollectionIds` on a DELETED operation (the trickiest arm: the rewrite must map it to the real collection where the never-persisted delete is a no-op, rather than throwing `NO_RESOLUTION`). The post-commit query asserts exactly the surviving final state `[after-update, kept]`. The commit/rollback-boundary coverage the review asked for (insert/update/delete-through-commit, rollback-leaves-no-reusable-plan via `rolledBackTxCreatedClassLeavesNoReusablePlanBehind` at 311) is present and goes beyond happy paths.

#### C6 "The new assert has side effects or duplicates an existing check" (REFUTED)
The 2434 assert condition calls only pure getters (`record.getIdentity().getCollectionId()`, `SchemaShared.isProvisionalCollectionId`), the failure message re-reads `record.getIdentity()` (also pure), and no `if`/`throw` earlier in `computeCommitWorkingSet` already tests provisionality. It is a genuine invariant check at the correct point (the last record gather before locking, position allocation, and serialization), not a restatement of a prior guard, and it carries zero production cost when disabled. The assert is well-placed and appropriate.
