<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: T1, sev: should-fix, loc: "SchemaShared.java:501", anchor: "### T1 ", cert: "Premise: version-gate bump", basis: "Bumping CURRENT_VERSION_NUMBER to 5 collides with VERSION_NUMBER_V5; the gate keeps accepting a legacy value the new format cannot parse unless the V5 accept-branch is handled"}
  - {id: T2, sev: should-fix, loc: "plan/track-2.md:34,66; design.md:441", anchor: "### T2 ", cert: "Edge case: selective per-class write under storage-leads", basis: "Selective per-class write and its F59 root-omission hazard depend on D6 dirty tracking owned by Track 4; under Track 2 storage-leads saveInternal->toStream rewrites everything, so the F59 failure cannot manifest in Track 2 code"}
  - {id: T3, sev: suggestion, loc: "plan/track-2.md:35", anchor: "### T3 ", cert: "Premise: new-class record-RID allocation", basis: "Track-2 D14 cites D2 (provisional collection ids, Track 4) for new-class temp->persistent RID; record RIDs resolve through the ordinary save tx, a different mechanism from D2"}
  - {id: T4, sev: suggestion, loc: "DatabaseExport.java:380", anchor: "### T4 ", cert: "Integration: CURRENT_VERSION_NUMBER read by DatabaseExport", basis: "Bumping the constant changes the exporter's schema-version field; the cross-track coupling to Track 8's import/version gate is unstated in the track"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 6}
cert_index:
  - {id: "Premise: version-gate bump", verdict: PARTIAL, anchor: "#### Premise: version-gate bump "}
  - {id: "Edge case: selective per-class write under storage-leads", verdict: WRONG, anchor: "#### Edge case: selective per-class write under storage-leads "}
  - {id: "Premise: new-class record-RID allocation", verdict: PARTIAL, anchor: "#### Premise: new-class record-RID allocation "}
  - {id: "Integration: CURRENT_VERSION_NUMBER read by DatabaseExport", verdict: MISMATCHES, anchor: "#### Integration: CURRENT_VERSION_NUMBER read by DatabaseExport "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: Premise: version-gate bump (PARTIAL)
**Location**: track-2.md Plan of Work line 66 ("Bump `CURRENT_VERSION_NUMBER` and turn the version check on open into a reject-and-redirect gate"); `SchemaShared.java:501`.
**Issue**: The version gate at `SchemaShared.fromStream` line 501 is `schemaVersion != CURRENT_VERSION_NUMBER && VERSION_NUMBER_V5 != schemaVersion`. `CURRENT_VERSION_NUMBER = 4`, `VERSION_NUMBER_V5 = 5` (the legacy 2.0-M1/M2 compat value). The track says "bump `CURRENT_VERSION_NUMBER`" without saying to what or what happens to the `VERSION_NUMBER_V5` accept-branch. Two failure shapes:
- Bump to `5` → `CURRENT_VERSION_NUMBER == VERSION_NUMBER_V5`, so the constant collides with the legacy compat constant and the gate semantics get muddled.
- Bump to `6` (or any value != 5) → a real old-format database at version `4` is correctly rejected, but the gate still ACCEPTS a version-`5` database (the surviving `VERSION_NUMBER_V5 != schemaVersion` term). A version-`5` record under the new per-class link-set format is not parseable by the new `fromStream`, so it would not be rejected-and-redirected; it would fall through to the per-class read path and fail in a less controlled way.

PSI confirms `VERSION_NUMBER_V5` has exactly one usage (the gate, line 501) and `VERSION_NUMBER_V4` has zero usages. The "turn the version check into a reject-and-redirect gate" sentence reads as if the gate is new; it already exists and already throws a `ConfigurationException` with an export/import redirect message. The real work is bumping the constant AND deciding the fate of the `VERSION_NUMBER_V5` accept-branch.
**Proposed fix**: In the track's Plan of Work and D14, state the new value explicitly and the V5 disposition. The clean form is to drop the `VERSION_NUMBER_V5` accept-term from the gate (so the gate accepts only the new `CURRENT_VERSION_NUMBER`) and bump `CURRENT_VERSION_NUMBER` to a value distinct from both `4` and `5` — i.e., the gate becomes `schemaVersion != CURRENT_VERSION_NUMBER` alone. Add a step assertion: opening any pre-bump version (both `4` and the legacy `5`) is rejected-and-redirected, not parsed. Note the message text already exists and only needs to stay.

### T2 [should-fix]
**Certificate**: Edge case: selective per-class write under storage-leads (WRONG)
**Location**: track-2.md D14 lines 34-35, Plan of Work line 67 ("Wire the root-record dirtiness rule so a property-create ... and an alter-add-collection ... put the root in the write set"); Validation line 84-86 (the F59 regression); `design.md` lines 441-456.
**Issue**: The track conflates two things that live in different tracks. The **format change** (root record links to per-class records; `SchemaClassImpl` gains a record-RID field bound at load; round-trip RID preservation) is fully achievable in Track 2. The **selective per-class write** ("write only the changed class records plus the root when its non-link payload changes") and its **F59 root-omission hazard** are NOT Track-2 properties:

- The track's Context (lines 56-57) states Track 2 "runs the change under today's storage-leads model (the inversion arrives in Tracks 3 and 4)." Confirmed in code: every schema mutation calls `saveInternal` at `releaseSchemaWriteLock` (`SchemaShared.java:433`), which runs `session.executeInTx(transaction -> toStream(session))` (`SchemaShared.java:826`). Today's `toStream` rewrites the **entire** schema record unconditionally; there is no "write set" and no dirty tracking.
- D14 itself says "per-property dirty tracking (D6) limits the write to the classes that actually changed" and "D6's dirty tracking puts the root in the write set." D6 is owned by **Track 4** (`implementation-plan.md:367`, `Implemented in: Track 4`). PSI confirms no per-class dirty flag exists on `SchemaClassImpl` today. The changed-class set that drives the per-class commit lives in `TxSchemaState` (Track 3) and the selective write happens at `AbstractStorage.commit` reconciliation (Track 4) per `design.md` §"Commit-time reconciliation" (lines 339-347).
- Therefore the F59 failure ("root left out of the write set → null `globalRef` NPE + colliding collection name") **cannot manifest in Track-2 code**: under storage-leads, `toStream` always writes the root with its current `globalProperties` and `collectionCounter`. F59 is a hazard of the Track-4 selective-write path, and the regression that demonstrates it needs the Track-3/4 tx machinery to even reach the failing state.

The F59 mechanism is real (verified: `SchemaPropertyImpl.toStream` persists `globalId` on the class record at `SchemaPropertyImpl.java:664`; at load `globalRef = owner.owner.getGlobalPropertyById(globalId)` at `SchemaPropertyImpl.java:564`, which returns null for a missing slot; the global-property table and counter live on the root via `globalProperties`/`collectionCounter` at `SchemaShared.java:659,666`). The issue is purely one of track ownership and timing, not correctness of the failure analysis.
**Proposed fix**: Scope Track 2 to the format only. Reword the Plan of Work so Track 2 establishes the per-class record format under storage-leads, where `toStream` writes the full schema (root + every class record) on every change — i.e. NO write-amplification reduction is observable yet at Track 2. Move the "write only the changed class plus the root" optimization, the "root-record dirtiness rule," and the F59 regression to Track 4 (where D6/D2/commit reconciliation land), or state explicitly that Track 2 delivers the format and Track 4 delivers the selective-write win that the format enables. Adjust the Validation/Acceptance bullets: Track 2's testable acceptance is "a one-class change still serializes correctly across the per-class records and round-trips the per-class RID," not "writes only that class's record" (which is unobservable until Track 4) and not the F59 restart regression (which requires the Track-4 path). The decomposer should not write a Track-2 step that asserts the selective-write or F59 behavior.

### T3 [suggestion]
**Certificate**: Premise: new-class record-RID allocation (PARTIAL)
**Location**: track-2.md D14 Risks line 35 ("a new class is a new record (temp→persistent RID at commit, D2)").
**Issue**: D14 cites **D2** for the new-class temp→persistent RID. D2 (`implementation-plan.md:116`) is "Provisional collection ids, resolved at commit" — a Track-4 mechanism about negative COLLECTION ids (`<= -2`), not record RIDs. A new class's record RID resolves through the ordinary save transaction's temp→persistent record-id path, which is independent of D2's provisional-collection-id scheme. Under Track 2's storage-leads `saveInternal` (a real committing tx), creating a fresh per-class entity and getting its persistent RID is already feasible via the ordinary record path; it does not depend on D2.
**Proposed fix**: Drop or correct the "D2" reference in D14's Risks bullet. If the intent is to flag that new-class records get temp RIDs that resolve at commit under the Track-4 tx model, say "the ordinary temp→persistent record-id resolution at commit" rather than citing D2 (collection ids). This is a cross-reference accuracy fix, low impact.

### T4 [suggestion]
**Certificate**: Integration: CURRENT_VERSION_NUMBER read by DatabaseExport (MISMATCHES)
**Location**: track-2.md Plan of Work line 66 (version bump); `DatabaseExport.java:380`.
**Issue**: PSI find-usages on `CURRENT_VERSION_NUMBER` returns three sites: the gate (`SchemaShared.java:501`), `toStream` (`SchemaShared.java:648`), and `DatabaseExport.java:380` (`jsonGenerator.writeNumberField("schema-version", SchemaShared.CURRENT_VERSION_NUMBER)`). Bumping the constant in Track 2 changes the `schema-version` number the exporter writes into every export manifest. Track 8 owns export/import and the version reject-and-redirect gate (D20), so an export produced by new binaries will carry the bumped number — correct, but unstated. The track lists "no public schema API change" but does not note this read by the export tool.
**Proposed fix**: Add a one-line note to the track's `## Interfaces and Dependencies` (or the inter-track-dependency bullet) that bumping `CURRENT_VERSION_NUMBER` is read by `DatabaseExport` (the exported `schema-version` field) and that Track 8's import/version gate must agree on the bumped value. No Track-2 code change beyond the constant; this is a coordination note so Track 8 does not regress the export number.

## Evidence base

#### Premise: SchemaShared exists
- **Track claim**: "Today `SchemaShared` serializes every class into one EMBEDDEDSET inside a single schema record" (Context).
- **Search performed**: PSI `JavaPsiFacade.findClass` on `com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaShared`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaShared.java`.
- **Actual behavior**: Class resolves. `toStream` (line 644) sets `entity.setProperty("classes", classesEntities, PropertyType.EMBEDDEDSET)` at line 657 over `new HashSet<>(classes.values())`; the root carries `globalProperties` (line 659), `collectionCounter` (line 666), `blobCollections` (line 669). `CURRENT_VERSION_NUMBER = 4` (line 62).
- **Verdict**: CONFIRMED
- **Detail**: Single-record EMBEDDEDSET format, version constant, and root payload all match the track's description.

#### Premise: SchemaClassImpl exists and has no record-RID field today
- **Track claim**: "`SchemaClassImpl` has no record-RID field today (F45)"; "Add the net-new record-RID field to `SchemaClassImpl`."
- **Search performed**: PSI `findClass` on `...schema.SchemaClassImpl`; grep over `SchemaClassImpl.java` for `RecordId`/`identity`/`RID`/`recordId`.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaClassImpl.java`; `toStream` at line 569.
- **Actual behavior**: Class resolves. `toStream` returns `session.newEmbeddedEntity()` (line 570) — an EMBEDDED entity, not a persistent record, and carries no own RID. The only `RID` use is property-link resolution (line 1354), unrelated to a class-record RID. No dirty/modified flag on the class.
- **Verdict**: CONFIRMED
- **Detail**: Net-new RID field claim is accurate. Note the per-class split must change `toStream` from emitting an embedded entity to writing/updating a standalone RID-pointed record.

#### Premise: IndexManagerAbstract.load binds each index by RID from a CONFIG_INDEXES link set
- **Track claim**: "bound at load from the schema-record link set exactly as `IndexManagerAbstract.load` binds each index"; "mirroring the index manager's `CONFIG_INDEXES` link set (F20)."
- **Search performed**: PSI `findClass` on `...index.IndexManagerAbstract`; grep over its source for `CONFIG_INDEXES`/`load`/`getLinkSet`.
- **Code location**: `IndexManagerAbstract.java:52` (`CONFIG_INDEXES = "indexes"`), `:191-206` (`load`), `:216` (link-set add).
- **Actual behavior**: `load` reads `entity.getLinkSet(CONFIG_INDEXES)` (line 195), iterates each linked `indexIdentifiable`, loads the per-index entity (`transaction.loadEntity(indexIdentifiable)`, line 198), and creates the index instance passing the `indexIdentifiable` RID (line 202). New index is linked by `indexEntity.getOrCreateLinkSet(CONFIG_INDEXES).add(index.getIdentity())` (line 216). The load runs inside an active tx.
- **Verdict**: CONFIRMED
- **Detail**: The pattern the track mirrors is real and precise. `SchemaShared.load`/`reload`/genesis wrap `fromStream` in `session.executeInTx` (lines 358, 716), so per-class records can be loaded by link the same way.

#### Premise: the version gate already exists and accepts two values
- **Track claim**: "turn the version check on open into a reject-and-redirect gate pointing at export/import."
- **Search performed**: Read `SchemaShared.fromStream` lines 487-509; PSI find-usages on `VERSION_NUMBER_V4`, `VERSION_NUMBER_V5`, `CURRENT_VERSION_NUMBER`.
- **Code location**: `SchemaShared.java:501-509`.
- **Actual behavior**: Gate is `schemaVersion != CURRENT_VERSION_NUMBER && VERSION_NUMBER_V5 != schemaVersion` → throws `ConfigurationException` with an export/import redirect message. `CURRENT_VERSION_NUMBER = 4`, `VERSION_NUMBER_V4 = 4` (0 usages), `VERSION_NUMBER_V5 = 5` (1 usage: the gate). A null `schemaVersion` returns early without throwing (treated as empty schema).
- **Verdict**: PARTIAL
- **Detail**: The gate is not new; it exists and already redirects. The track must define the bumped constant value and the disposition of the `VERSION_NUMBER_V5` accept-branch. Drives T1.

#### Integration: callers of toStream / fromStream / SchemaClassImpl.toStream
- **Plan claim**: "no public schema API change"; serialization is internal.
- **Actual entry point**: `SchemaShared.toStream` (1 usage: `save`, `SchemaShared.java:826`); `SchemaShared.fromStream` (2 usages: load paths, lines 364, 719); `SchemaClassImpl.toStream` (1 usage: `SchemaShared.java:655`); `SchemaClassImpl.fromStream` (2 usages: `SchemaShared.java:537,540`).
- **Caller analysis**: PSI find-usages. All callers are inside `SchemaShared`. Open-time entry is `SchemaShared.load` (callers `SchemaProxy.java:282`, `SharedContext.java:117`) and `reload` (7 callers incl. `SharedContext.java:159`, `YouTrackDBInternalEmbedded.java:688,700`).
- **Breaking change risk**: Low. The serialization surface is fully internal to `SchemaShared`; the per-class split changes how these methods read/write but does not change any public schema API. No external caller of `toStream`/`fromStream`.
- **Verdict**: MATCHES
- **Detail**: Confirms the track's "no public schema API change" claim and a contained refactor surface.

#### Integration: CURRENT_VERSION_NUMBER read by DatabaseExport
- **Plan claim**: implicit — bumping the constant is described as a local schema-version change.
- **Actual entry point**: `DatabaseExport.java:380` reads `SchemaShared.CURRENT_VERSION_NUMBER` and writes it as the manifest `schema-version` field.
- **Caller analysis**: PSI find-usages on the field (3 sites: gate, `toStream`, `DatabaseExport`).
- **Breaking change risk**: The exporter's emitted number changes with the bump. Track 8 (export/import, D20) must agree on the bumped value for the version reject-and-redirect gate to round-trip.
- **Verdict**: MISMATCHES
- **Detail**: The cross-track read is unstated in the track. Drives T4 (a coordination note, not a Track-2 code change).

#### Premise: new-class record-RID allocation cited as D2
- **Track claim**: D14 Risks "a new class is a new record (temp→persistent RID at commit, D2)."
- **Search performed**: Read D2 in `implementation-plan.md:116-121`; read `SchemaShared.saveInternal` (line 817) and `save` (line 826).
- **Code location**: `implementation-plan.md:116`; `SchemaShared.java:826`.
- **Actual behavior**: D2 is "Provisional collection ids, resolved at commit" — negative collection-id sentinels (`<= -2`), a Track-4 concern, not record RIDs. New-class record RIDs resolve through the ordinary save tx (`session.executeInTx`), independent of D2.
- **Verdict**: PARTIAL
- **Detail**: Cross-reference is imprecise; drives T3.

#### Edge case: selective per-class write under storage-leads
- **Trigger**: a one-class change committed under Track-2 code, then a restart and read (the F59 scenario).
- **Code path trace**:
  1. Mutation entry → `releaseSchemaWriteLock` @ `SchemaShared.java:423`.
  2. `modificationCounter == 1` and storage is `AbstractStorage` → `saveInternal(session)` @ line 433.
  3. `saveInternal` → `session.executeInTx(transaction -> toStream(session))` @ line 826 — a self-committing micro-tx.
  4. `toStream` rewrites the WHOLE schema record (root + all class entities) @ lines 644-670; root always carries current `globalProperties` and `collectionCounter`.
- **Outcome**: Under storage-leads, every schema change writes the full schema, so there is no "write set" to exclude the root from, and F59 (null `globalRef` / colliding collection name) cannot occur in Track-2 code. The selective per-class write and the F59 hazard require the Track-3 changed-class set and Track-4 D6 dirty tracking + commit reconciliation (`design.md:339-347`), none of which exist at Track 2.
- **Track coverage**: The track describes the selective write and F59 as Track-2 deliverables (D14 lines 34-35, Plan of Work line 67, Validation lines 84-86), but they belong to Track 4. WRONG verdict.
- **Verdict (cert)**: WRONG — drives T2.

#### Premise: F59 failure mechanism (global-property slot + collection counter on root)
- **Track claim**: "a committed property-create restarts into a null `globalRef` NPE and a stale counter regenerates colliding collection names (F59)."
- **Search performed**: grep over `SchemaPropertyImpl.java` for `globalRef`/`globalId`; read `SchemaShared` global-property and counter handling.
- **Code location**: `SchemaPropertyImpl.java:664` (persist `globalId`), `:562-569` (resolve `globalRef` from `globalId`); `SchemaShared.java:758` (`getGlobalPropertyById` is `@Nullable`), `:611-617` (counter load), `:659,666` (root toStream of table + counter).
- **Actual behavior**: A class property persists its `globalId` on its own record (line 664) and resolves `globalRef = owner.owner.getGlobalPropertyById(globalId)` at load (line 564), which returns null for a missing slot. The global-property table and counter live on the root. If the root were excluded from a write, on reopen the slot is missing (null `globalRef`) and the counter reverts (colliding names).
- **Verdict**: CONFIRMED
- **Detail**: The failure analysis is correct. Its relevance is to Track 4 (where root-exclusion is possible), not Track 2 — see T2.
