<!--MANIFEST
dimension: bugs-concurrency
step: 5
iteration: 1
commit_range: 920fa2aa00298238abf4bfa2eaca520a6b453cb4~1..920fa2aa00298238abf4bfa2eaca520a6b453cb4
verdict: CHANGES_REQUESTED
counts: {blocker: 1, should-fix: 0, suggestion: 2}
evidence_base: 4
cert_index: [C1, C2, C3, C4]
flags: []
index:
  - id: BC1
    sev: blocker
    anchor: "#bc1-blocker--tx-local-alter-ops-that-omit-markclasschanged-are-silently-dropped-by-the-selective-write"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:1013-1030"
    cert: C1
    basis: "code-trace + PSI find-usages"
  - id: BC2
    sev: suggestion
    anchor: "#bc2-suggestion--rootpayloaddiffersfrom-throws-aioobe-if-the-tx-local-table-is-shorter-than-committed"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java:212-224"
    cert: C2
    basis: "code-trace"
  - id: BC3
    sev: suggestion
    anchor: "#bc3-suggestion--no-regression-test-pins-an-unmarked-alter-surviving-commit"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java:611-829"
    cert: C4
    basis: "test-trace"
-->

## Findings

### BC1 [blocker] — Tx-local alter ops that omit `markClassChanged` are silently dropped by the selective write

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (line 1013-1030)
- **Issue**: The selective filter writes an existing class's per-class record only when
  `changedLower.contains(c.getName().toLowerCase())` (line 1013-1014); any other live class falls into
  the `else` branch (line 1019-1030) that loads the record read-only and never re-streams it. The
  changed-class set is populated by `markClassChanged`, which only five schema-side sites call:
  `createClassInternal`, `dropClassInternal`, `changeClassName` (rename), `setAbstractInternal`, plus
  the index-membership paths in `IndexManagerEmbedded`. Every other tx-local per-class mutation routes
  through `SchemaProxedResource.resolveForWrite()` (which only re-resolves the delegate to the tx-local
  copy — it does **not** mark the class changed) and so leaves the changed-class set untouched.
  `setStrictMode`, `setCustom`/`clearCustom`, `setDescription`, `addSuperClass`/`setSuperClasses`
  (and their `*Internal` bodies) are reachable inside an active transaction today — none is throw-guarded,
  and `SchemaShared.saveInternal` returns early on the `txLocal` branch (line 1266-1272) instead of
  throwing — yet none calls `markClassChanged`. The mutated field (`strictMode`, `customFields`,
  `description`, `superClasses`) is part of what `SchemaClassImpl.toStream` serializes into the per-class
  record. After this step the class is absent from `getChangedClasses()`, the selective write skips its
  record, and the alter is lost: promotion (`committedSchema.fromStream`) re-parses the *stale* persisted
  record, so the committed in-memory schema reverts to the old value as well. The loss is silent on both
  the in-memory and durable sides.
- **Evidence**: Step 4 (`53207446ff`) called the full-write `toStream(session)`, which rewrote every
  class record unconditionally — confirmed by `git show 53207446ff:…/AbstractStorage.java`
  (`schemaContext.txLocalSchema().toStream(session);` at line 2501). This step swaps that call for the
  selective overload `toStream(session, changedClasses, writeRootPayload)` (AbstractStorage.java
  line 2506-2510). PSI find-usages of `TxSchemaState.markClassChanged` returns exactly the five schema
  sites above plus three `IndexManagerEmbedded` sites (and test calls); a text+PSI sweep of
  `SchemaClassEmbedded` confirms only `setAbstractInternal` marks changed, while
  `setStrictModeInternal`, `setDescriptionInternal`, `setCustomInternal`, `addSuperClassInternal`, and
  `setSuperClassesInternal` mutate their fields with no `markClassChanged` and no active-tx throw guard.
  The eager-save throw at `SchemaShared.java:1277` does not fire for these, because the tx-local copy
  short-circuits at line 1271 before reaching it.
- **Refutation considered**: (1) *Are these ops reachable in a transaction, or guarded?* Only
  `createProperty`/`dropProperty` (SchemaClassEmbedded:42,440,460) and the legacy non-tx-local save
  (SchemaShared:1277) throw inside a tx; `setStrictMode`/`setCustom`/`setDescription`/`addSuperClass`/
  `setSuperClasses` have no such guard and route to the tx-local copy via `resolveForWrite`. (2) *Does
  some ripple mark the class anyway?* `addSuperClassInternal`/`setSuperClassesInternal` mutate
  `superClasses` and call `addBaseClass`/`removeBaseClassInternal` but allocate no collection and never
  call `markClassChanged` (full bodies read); the strict/custom/description setters touch only scalar
  fields. (3) *Does promotion rescue it?* No — promotion re-parses from the persisted per-class records;
  a skipped record means promotion reads the old bytes, so the alter is lost in memory too. (4) *Is this
  a pre-existing bug, not introduced here?* Before this step the full write rewrote every class record,
  so the alter persisted; the selective filter introduced in this step is what creates the skip. The
  case-insensitive `changedLower` match (the focus's "different case" concern) is correct and does not
  bear on this — the names that *are* recorded match `getName()` case-insensitively; the defect is the
  classes that are never recorded at all. Confirmed.
- **Suggestion**: Make the changed-class signal complete rather than per-mutation. The most robust fix
  centralizes the marking at the single tx-local write choke point — have
  `SchemaProxedResource.resolveForWrite()` (or `rebindToTxLocal` on the class proxy) record the resolved
  class into the changed set whenever a write resolves to the tx-local copy, so every current and future
  alter op is covered without each remembering to mark. If a narrower fix is preferred for this step, add
  `markClassChanged` to `setStrictModeInternal`, `setDescriptionInternal`, `setCustomInternal`,
  `addSuperClassInternal`, and `setSuperClassesInternal` (and audit any other tx-reachable per-class
  setter against `SchemaClassImpl.toStream`'s serialized field set). Either way, add a regression test
  (see BC3) that alters a class with an op outside the create/drop/rename/abstract set inside a
  transaction, commits, reloads, and asserts the altered value survives.

### BC2 [suggestion] — `rootPayloadDiffersFrom` throws AIOOBE if the tx-local table is shorter than committed

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java` (line 212-224)
- **Issue**: The slot-by-slot loop iterates `id` over `this.properties.size()` and dereferences
  `committed.properties.get(id)` (line 219-221). The size-inequality early-return at line 212-214
  guards the unequal-size case, so the loop runs only when the two tables are the same length — making
  the current code safe. But the guard and the loop encode the invariant "the global-property table is
  append-only and the tx-local copy is a superset of committed" implicitly. If a future change ever lets
  the tx-local table be *shorter* than committed (a property-table compaction, a copy that drops a slot),
  the size check would still pass only on equality, and any code path that reordered these two checks, or
  a refactor that iterated `committed.properties.size()` against `this.properties.get(id)`, would throw
  `ArrayIndexOutOfBoundsException` rather than report a difference. This is latent, not live.
- **Evidence**: `findOrCreateGlobalProperty` (the append-only path) only ever calls
  `properties.add(properties.size(), …)`; `createGlobalProperty(id)` uses `ensurePropertiesSize(id)` +
  `set(id, …)`, which can grow the table with null padding but never shrink it. So today
  `this.properties.size() >= committed.properties.size()` always holds for a tx-local copy seeded from
  committed, and the early-return handles the strictly-greater case. The risk is purely defensive.
- **Refutation considered**: Is this reachable today? No — the append-only invariant holds and the size
  guard precedes the loop. Reported as a suggestion only because the Javadoc asserts the append-only
  property as load-bearing without a guard that would fail loudly if it were ever violated.
- **Suggestion**: Iterate to `Math.max` (or `Math.min` with an explicit comment) of the two sizes, or
  add an `assert this.properties.size() >= committed.properties.size()` documenting the relied-on
  direction, so a future violation surfaces as a clear assertion rather than an AIOOBE.

### BC3 [suggestion] — No regression test pins an unmarked alter surviving commit

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaCommitReconciliationTest.java` (line 611-829)
- **Issue**: The five new tests cover create (`classCreateAdvancesCounter…`, `classCreateWritesRoot…`),
  drop (`changingOneClass…`, `classDropWritesRoot…`), and the white-box payload detector
  (`rootPayloadDiffersFrom…`). None exercises a tx-local *alter* whose op does not call `markClassChanged`
  (the BC1 surface: strict-mode, custom field, description, superclass). The selective-write contract
  "every changed class's record is rewritten" is therefore unverified for the alter family, which is
  exactly where the BC1 silent-skip lives. The diff's own Javadoc on `classCreateAdvancesCounter…` notes
  the global-property arm "is not reachable yet" because property-create is throw-guarded — but the
  strict/custom/description/superclass alters are *not* throw-guarded, so they are reachable and untested.
- **Evidence**: Reading the five test bodies (line 611-829) shows assertions on record version
  (`recordVersion`), collection-name uniqueness, and root link membership for create/drop only; no test
  mutates `setStrictMode`/`setCustom`/`setDescription`/`addSuperClass` inside a tx and asserts the value
  after commit+reload.
- **Refutation considered**: Could an existing test elsewhere cover this? `SchemaDeguardTest` covers
  create/drop/rename/abstract/index isolation-and-rollback, not the survival of an alter through commit
  on the selective path. No coverage found.
- **Suggestion**: Add a test that, inside a transaction, applies one alter from the unmarked set to an
  existing committed class, commits, forces a durable reload (`reload` + `reOpen`), and asserts the
  altered value persisted. With BC1 unfixed this test is RED; with BC1 fixed it is the regression guard.

## Evidence base

#### C1 — Changed-class set is incomplete for tx-local alter ops (BC1)
- **Method**: PSI find-usages of `TxSchemaState.markClassChanged` over project scope + full-body reads
  of the candidate alter ops in `SchemaClassEmbedded`, plus `git show` of the Step 4 call site.
- **Findings**: `markClassChanged` callers (schema side) = `SchemaShared.changeClassName`,
  `SchemaEmbedded.createClassInternal`, `SchemaEmbedded.dropClassInternal`,
  `SchemaClassEmbedded.setAbstractInternal`; (index side) three `IndexManagerEmbedded` sites. Full bodies
  of `setStrictModeInternal`, `setDescriptionInternal`, `setCustomInternal`, `addSuperClassInternal`,
  `setSuperClassesInternal` mutate serialized fields with no `markClassChanged` and no active-tx guard.
  `SchemaProxedResource.resolveForWrite()` re-resolves to the tx-local copy but does not mark.
  `SchemaShared.saveInternal` line 1266-1272 returns early on `txLocal` (no throw). Step 4
  (`53207446ff`) used full-write `toStream(session)`; this step replaced it with the selective overload.
- **Verdict**: CONFIRMED — selective write silently drops unmarked tx-local alters; regression introduced by this step.

#### C2 — `rootPayloadDiffersFrom` AIOOBE is latent, guarded by the append-only invariant (BC2)
- **Method**: Read `rootPayloadDiffersFrom` (diff line 205-226), `findOrCreateGlobalProperty`,
  `createGlobalProperty`, and the `properties` field declaration.
- **Findings**: `findOrCreateGlobalProperty` appends only; `createGlobalProperty(id)` grows with null
  padding, never shrinks. Size-inequality early-return precedes the indexed loop, so the loop runs only
  on equal sizes. `this.properties.size() >= committed.properties.size()` holds for a tx-local copy.
- **Verdict**: REFUTED as a live bug; reported as a defensive suggestion only.

#### C3 — Read-only-first link-set acquisition and bare-load semantics are correct
- **Method**: Read `EntityImpl.getLinkSet` / `getOrCreateLinkSet` and `computeCommitWorkingSet`.
- **Findings**: `getLinkSet` returns null when the property is absent and only reads `getProperty`
  (no dirty); `getOrCreateLinkSet` returns the existing set without re-setting the property when present
  (no re-dirty). The commit working set is built from `frontendTransaction.getRecordOperationsInternal()`,
  so a bare `session.load(boundRid)` (no `toStream`) enrolls no record operation and stays out of the
  write set. `existingLinks != null` is the correct "an existing link set was present" signal, and
  `classLinks != null` (set only when a create or drop calls `getOrCreateLinkSet`) is a correct
  "link set changed" signal. Null-safety on `existingLinks` is handled (line 986-990).
- **Verdict**: CONFIRMED CORRECT — the read-only-first design, the lazy mutable-handle acquisition, the
  `linkSetChanged` signal, and the case-insensitive `changedLower` match are sound.

#### C4 — `writeRootPayload` timing and the rename/counter interaction are consistent (BC3 context)
- **Method**: Read `reconcileCollections`, `resolveProvisionalCollectionIds`, `nextCollectionIndex`,
  `addBlobCollection`, and the four-lock acquisition + commit-window framing in `applyCommitOperations`.
- **Findings**: `rootPayloadDiffersFrom` is computed against the committed schema while the committed
  write lock is held (four-lock order takes `committedSchema.acquireSchemaWriteLock` first), so the
  field-direct read of `committed.properties`/`collectionCounter`/`blobCollections` is safe.
  `resolveProvisionalCollectionIds` (run just before the comparison) patches only `collectionIds` arrays
  and rebuilds `collectionsToClasses`; it does not touch `collectionCounter`, `blobCollections`, or
  `properties`, so it cannot skew the payload-diff signal. A create advances `collectionCounter` (true
  diff) and also changes the link set (`linkSetChanged`), so both arms agree. A pure rename advances
  none of the three payload components and keeps the class RID (no link change), so the root is correctly
  left untouched while the renamed class's per-class record is rewritten (it is in the changed set under
  its new name).
- **Verdict**: CONFIRMED CORRECT — no timing or rename hazard in the root-payload decision.
