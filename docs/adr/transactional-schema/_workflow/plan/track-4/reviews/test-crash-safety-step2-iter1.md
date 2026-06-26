<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 0, suggestion: 3}
index:
  - {id: TY1, sev: suggestion, loc: SchemaDeguardTest.java:295-315, anchor: "### TY1 ", cert: C5, basis: "I-A2 has two halves; this step's tests pin only the tx-local-view half (provisional <= -2 in the in-tx view). The durable-bytes half (commit -> reload -> real id survives, no <= -2 on disk) needs the Step 3 reconciliation that does not exist yet, so it is correctly deferred — but no test carries a breadcrumb to that deferral. Traceability gap, not a vacuous test."}
  - {id: TY2, sev: suggestion, loc: SchemaDeguardTest.java:327-350, anchor: "### TY2 ", cert: C6, basis: "Crash-before-commit (as distinct from explicit rollback) is not exercised here. Track defers it to Step 3 (LocalPaginatedStorageRestoreFromWALIT close-copy-restore, leaning on Track 1). Correct deferral; the rollback test carries no note pointing to where the crash variant lands."}
  - {id: TY3, sev: suggestion, loc: SchemaEmbedded.java:383-384, anchor: "### TY3 ", cert: C7, basis: "The provisional branch produces collectionIds[i] from allocateProvisionalCollectionId(); the predicate-split sites downstream rely on every such id satisfying isProvisionalCollectionId (<= -2). Zero-cost assert at the allocation site would catch a counter that wrapped past Integer.MIN_VALUE to a non-negative value. Low value (needs ~2^31 allocations in one tx); recordResolvedCollectionId already asserts the same invariant."}
evidence_base: {section: "## Evidence base", certs: 7, matches: 7}
cert_index:
  - {id: C1, verdict: CONFIRMED-SAFE, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED-SAFE, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED-SAFE, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED-SAFE, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7, verdict: CONFIRMED, anchor: "#### C7 "}
flags: [CONTRACT_OK]
-->

## Findings

### TY1 [suggestion] I-A2's durable-bytes half is correctly deferred to Step 3, but no test carries a breadcrumb to that deferral

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java` (lines 295-315 `inTransactionCreateCarriesProvisionalCollectionId`; lines 423-462 `txSchemaStateProvisionalToRealCarrierRoundTrips`)

**Production code:** `core/.../metadata/schema/SchemaEmbedded.java` (lines 383-384, the provisional allocation); `core/.../metadata/schema/TxSchemaState.java` (lines 223-225, the allocator; 236-251, the carrier)

I-A2 as the track states it has two halves: (a) an in-tx `createClass` exposes a provisional `<= -2` id **in the tx-local view only**, and (b) **no provisional id reaches durable bytes** — verified by commit-then-reload showing a real id with no `<= -2` survivor (track Validation: "commits and restarts ... no provisional id surviving"; "the class resolves to its collections and each record resolves to its class — no provisional id reached durable bytes (I-A2)").

This step's tests pin only half (a). `inTransactionCreateCarriesProvisionalCollectionId` asserts the in-tx id is `<= -2`; the carrier round-trip test drives the provisional→real map in isolation with a hand-fed `42`. Neither commits and reloads to confirm a real id replaces the provisional one on disk.

That is correct for Step 2: the provisional→real resolution and the property-value re-point that make the durable bytes real do not exist yet — they are Step 3's reconciliation core (track Concrete Steps §3, "resolve provisional ids through the patch list"). A commit-then-reload test written now would either fail (no resolution wired) or pass vacuously (the create never reaches a real collection). So the durable-bytes half is genuinely un-writable at this step.

**Why it is only informational:** the half that *is* testable at Step 2 (the tx-local provisional exposure) is tested precisely and non-vacuously — see C1. The defect this guards against (a `<= -2` id durably serialized, the F58 silent-corruption case) is unreachable until Step 3 wires the resolution.

**Refutation considered:** I checked whether the carrier round-trip test or the multi-class test inadvertently reaches durable bytes — neither commits; both run inside `executeInTx`/read the in-memory `TxSchemaState` only. I checked whether `inTransactionCreateCarriesProvisionalCollectionId` could be extended to commit-then-reload at this step — it cannot, because no consumer resolves the provisional id yet, so a post-commit reload would observe the create never materialized. The deferral is structural, not an omission.

**Suggestion:** add a one-line comment to `inTransactionCreateCarriesProvisionalCollectionId` (or the class-level Javadoc) noting that the durable-bytes half of I-A2 (commit → reload → real id, no `<= -2` survivor) lands with the Step 3 reconciliation core, so a later reader does not mistake the present tx-local-view assertion for the full I-A2 guard. No code change required to ship this step.

### TY2 [suggestion] Crash-before-commit (distinct from explicit rollback) is not exercised; correct deferral, missing breadcrumb

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaDeguardTest.java` (lines 327-350 `rolledBackInTransactionCreateLeavesNoCollectionOnDisk`)

**Production code:** `core/.../metadata/schema/SchemaEmbedded.java` (lines 383-384) — the create no longer calls `session.addCollection`, so it touches no storage collection during the tx.

The I-A1 acceptance has two failure modes: an **explicit `rollback()`** and a **crash injected after the tx body and before commit**. The track is explicit that both must leave storage byte-for-byte unchanged ("Repeat with a crash injected after the transaction body and before commit; recovery leaves the same clean state (I-A1, leaning on Track 1)"; Idempotence and Recovery → "Crash-before-commit (I-A1). Leans on Track 1's `ensureFileForReplay`; reuses the `LocalPaginatedStorageRestoreFromWALIT` close-copy-restore pattern").

`rolledBackInTransactionCreateLeavesNoCollectionOnDisk` covers the explicit-rollback mode well and storage-soundly (see C2). It does **not** cover the crash-before-commit mode — there is no close-without-flush / WAL-replay simulation. For Step 2 that is the right call: the crash variant needs the `LocalPaginatedStorageRestoreFromWALIT` harness in a separate IT class, and crash-before-commit on the *provisional* path is now trivially a no-op (no file was ever created), so the load-bearing crash test belongs in Step 3 where a partially-reconciled commit can actually leave files. The track assigns it there.

**Why it is only informational:** at Step 2 the create writes nothing to storage on either rollback or crash-before-commit; the explicit-rollback test already proves the no-write property end to end against the storage registry. A crash-before-commit test here would assert the same empty-delta the rollback test asserts, against an even simpler pre-state.

**Refutation considered:** I checked whether the rollback test could stand in for the crash test — it cannot in general (rollback runs the undo path; a crash skips it), but at Step 2 both converge on "no file was created", so the gap is benign *for this step's production change*. I checked the track's deferral target is real: `LocalPaginatedStorageRestoreFromWALIT` and the close-copy-restore pattern are named in the Idempotence and Recovery section as the Step 3 vehicle.

**Suggestion:** add a one-line note to `rolledBackInTransactionCreateLeavesNoCollectionOnDisk` stating that the crash-before-commit variant of I-A1 (close-without-commit + WAL replay) lands in Step 3 via the `LocalPaginatedStorageRestoreFromWALIT` harness, so the reader sees the rollback test is deliberately the explicit-rollback half. No code change required.

### TY3 [suggestion] Optional zero-cost assert pinning the provisional-id invariant at the allocation site

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaEmbedded.java` (line 383-384)

**Evidence:** the INVARIANT analysis in C7.

**Invariant:** every id the provisional branch stores into `collectionIds[i]` must satisfy `SchemaShared.isProvisionalCollectionId(...)` (i.e. `<= -2`). The whole predicate-split this step introduces (`collectionId == ABSTRACT_COLLECTION_ID` skip vs `<= -2` pending-real treatment) depends on the allocator never producing `-1` or a non-negative value. `allocateProvisionalCollectionId()` post-decrements from `-2`, so the only way to violate this is integer underflow past `Integer.MIN_VALUE` wrapping to a non-negative id — needs ~2^31 allocations in a single transaction.

**Suggested assertion:**
```java
if (provisional) {
  var provisionalId = txState.allocateProvisionalCollectionId();
  assert SchemaShared.isProvisionalCollectionId(provisionalId)
      : "provisional collection id allocator produced a non-provisional id: " + provisionalId;
  collectionIds[i] = provisionalId;
} else {
  collectionIds[i] = session.addCollection(collectionName);
}
```

**Catches:** a provisional-id counter that wrapped past `Integer.MIN_VALUE` (or any future allocator refactor that returns `-1` or a non-negative id), which would otherwise route the id down the abstract-marker skip or the real-id branch at the downstream map sites and silently corrupt the tx-local reverse map.

**Why this is only a suggestion (low value):** the wrap requires 2^31 allocations in one tx, which no realistic schema transaction approaches, and `TxSchemaState.recordResolvedCollectionId` (lines 237-240) already asserts `isProvisionalCollectionId(provisionalCollectionId)` on the resolution side, so a wrapped id would also trip that assert at commit. The allocation-site assert moves the detection earlier (at production rather than resolution) but protects an invariant that is already partly covered. Ship-optional.

## Evidence base

#### C1 `inTransactionCreateCarriesProvisionalCollectionId` non-vacuously pins the tx-local provisional exposure — CONFIRMED-SAFE
Survived: asserts `isProvisionalCollectionId(collectionId)` (`<= -2`) AND `assertNotEquals(ABSTRACT_COLLECTION_ID, collectionId)` for every id of a plain in-tx-created class, with `collectionIds.length > 0` guarding against a vacuous empty-array loop. Would fail under the old eager path (which produced a non-negative real id), so it genuinely pins the create-no-longer-allocates-real precondition for I-A1.

#### C2 `rolledBackInTransactionCreateLeavesNoCollectionOnDisk` is storage-grounded, not in-memory-only — CONFIRMED-SAFE
The decisive question for this dimension. PSI-traced `session.getCollectionNames()` → `DatabaseSessionEmbedded.getCollectionNames()` → `storage.getCollectionNames()` → `AbstractStorage.getCollectionNames()` returns `Collections.unmodifiableSet(collectionMap.keySet())` under `stateLock.readLock()`. `collectionMap` is the **storage-layer real-collection registry**, written only by `registerCollection(StorageCollection)` (`AbstractStorage`) — the publish half of the real-collection create path — NOT the schema's `collectionsToClasses` and NOT the in-memory `SchemaShared` view. Under the old eager self-commit, `session.addCollection("straycollectionprobe_N")` would register the collection in `collectionMap`, and because the self-commit completed independently of the user tx, that entry would survive `session.rollback()`; the test's `assertEquals(collectionsBefore, collectionsAfter)` plus the `startsWith("straycollectionprobe_")` prefix sweep would then fail. Under the new provisional allocation no `addCollection` runs, so `collectionMap` is untouched. The test therefore detects a stray collection on disk, not merely an in-memory-schema absence. The committed-view checks (`committed.existsClass(...)`) use `getSharedContext().getSchema()`, PSI-confirmed to return `SharedContext.schema` directly (the durable committed `SchemaShared`), bypassing the tx-routing proxy — so they are a genuine committed-view assertion, complementary to the storage-registry check rather than a substitute for it.

#### C3 `provisionalCollectionIdsAreUniqueWithinMultiClassTransaction` would catch a duplicate-returning allocator — CONFIRMED-SAFE
Survived: collects every provisional id from both classes into a `HashSet<Integer>`, asserting `allProvisionalIds.add(collectionId)` returns `true` for each (so a repeat fails immediately), then asserts `allProvisionalIds.size() == first.len + second.len` (so a silent collapse of two ids into one set entry also fails). An allocator that returned a duplicate (e.g. a missing decrement, or a per-class reset) trips both checks. The test additionally PSI-validly probes the in-memory reverse map: `session.getTxSchemaState().getTxLocalSchema().getClassByCollectionId(id)` → `SchemaShared.getClassByCollectionId` → `collectionsToClasses.get(id)`, asserting it resolves to `"MultiClassA"` — exercising the new pending-real population at `addCollectionClassMap` for `<= -2` ids. A missed in-memory-map split site would leave the provisional id unmapped and fail this resolution.

#### C4 `inTransactionAbstractCreateStillReadsMinusOne` and `abstractClassMarkerStaysSkippedAtInMemoryMapSites` pin the `-1` vs `<= -2` disjointness — CONFIRMED-SAFE
Survived: the abstract-create test asserts exactly one collection id equal to `ABSTRACT_COLLECTION_ID` (`-1`) and `!isProvisionalCollectionId(-1)`, pinning that the abstract path takes the `{-1}` branch and never the provisional allocator. The marker test drives the single `-1` through the committed (eager) path's `checkCollectionsAreAbsent` (create succeeds, no false duplicate), `addCollectionClassMap` (`committed.getClassByCollectionId(-1) == null`, marker never entered), and `removeCollectionClassMap` (drop succeeds, marker stays unmapped) — confirming the predicate split left the abstract half behaviourally unchanged. Both read the committed `SchemaShared` via `getSharedContext().getSchema()` (durable view, C2), so the assertions are real.

#### C5 I-A2 durable-bytes half (commit → reload → real id, no `<= -2` survivor) is not tested at this step — CONFIRMED (see TY1)
The step's tests pin the tx-local-view half of I-A2 only. The durable-bytes half requires the Step 3 provisional→real resolution + property-value re-point (track Concrete Steps §3), which does not exist at Step 2; a commit-then-reload test written now would pass vacuously (the create never materializes a real collection) or fail (no resolution wired). Correctly deferred; the gap is a missing in-test breadcrumb, not a vacuous or wrong assertion. `txSchemaStateProvisionalToRealCarrierRoundTrips` exercises the carrier (`recordResolvedCollectionId`/`getResolvedCollectionId` round-trip, the `-1` not-resolved sentinel via the `defaultReturnValue`, the strictly-decreasing allocator, and `getResolvedCollectionIds().size() == 1`) in isolation with a hand-fed real id, which is the right unit-level coverage of the substrate at this step but does not touch durable bytes.

#### C6 Crash-before-commit variant of I-A1 is not exercised — CONFIRMED (see TY2)
`rolledBackInTransactionCreateLeavesNoCollectionOnDisk` covers the explicit-`rollback()` failure mode storage-soundly (C2). The crash-before-commit mode (close-without-flush + WAL replay) is not simulated here; the track assigns it to Step 3 via the `LocalPaginatedStorageRestoreFromWALIT` close-copy-restore harness, leaning on Track 1's `ensureFileForReplay`. Correct deferral — at Step 2 the provisional create writes nothing on either failure mode, so the crash variant would assert the same empty storage delta the rollback test already proves. The gap is a missing breadcrumb to the Step 3 vehicle, not a coverage hole in this step's production change.

#### C7 Allocation-site provisional-id invariant is enforced only downstream (at resolution), not at production — CONFIRMED (see TY3)
At `SchemaEmbedded.createCollections:383-384` the provisional branch stores `txState.allocateProvisionalCollectionId()` into `collectionIds[i]` with no check that the result satisfies `isProvisionalCollectionId` (`<= -2`). The predicate split this step introduces routes the id by its sign-class at every in-memory map site, so an id that wrapped past `Integer.MIN_VALUE` to a non-negative value would be mis-routed. Current enforcement: `TxSchemaState.recordResolvedCollectionId` (lines 237-240) asserts the invariant on the *resolution* side at commit, but nothing asserts it at the *allocation* side. A zero-cost assert at the allocation site moves detection earlier; low value because the wrap needs ~2^31 single-tx allocations and the existing resolution-side assert already partly covers it. Assert candidate: YES (zero cost, no side effects), priority low.
