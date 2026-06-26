<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 3, suggestion: 2}
index:
  - {id: R1, sev: should-fix, loc: SchemaShared.java:501, anchor: "### R1 ", cert: A1, basis: "version gate still accepts VERSION_NUMBER_V5; a bare CURRENT_VERSION_NUMBER bump lets a V5 db be parsed by the new per-class reader instead of cleanly rejected"}
  - {id: R2, sev: should-fix, loc: DatabaseCompare.java:946, anchor: "### R2 ", cert: A2, basis: "convertSchemaDoc parses the schema record's classes as EMBEDDEDSET outside the track footprint; backup/restore + import/export ITs break when classes becomes a link set"}
  - {id: R3, sev: should-fix, loc: SchemaShared.java:817, anchor: "### R3 ", cert: E1, basis: "acceptance #1 'one-class change writes one record' is not deliverable by the storage-leads toStream persist path; setProperty dirties every per-class record; the write-limiting dirty tracking is D6/Track 4"}
  - {id: R4, sev: suggestion, loc: SchemaClassImpl.java:569, anchor: "### R4 ", cert: E2, basis: "class toStream returns newEmbeddedEntity; the split must save each class as a standalone record + getOrCreateLinkSet + delete-on-drop, mirroring addIndexInternalNoLock"}
  - {id: R5, sev: suggestion, loc: DatabaseExport.java:380, anchor: "### R5 ", cert: A3, basis: "export manifest stamps CURRENT_VERSION_NUMBER into schema-version; bumping in Track 2 changes export output before Track 8's importer (EXPORTER_VERSION 14->15) lands"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 5}
flags: [CONTRACT_OK]
-->

## Findings

### R1 [should-fix]
**Certificate**: A1 (Assumption — the open-time version gate is a clean reject for any non-current format)
**Location**: track Plan of Work ("bump `CURRENT_VERSION_NUMBER` and turn the version check on open into a reject-and-redirect gate"); `SchemaShared.fromStream` @ `core/.../metadata/schema/SchemaShared.java:501`.
**Issue**: The only open-time schema-version gate is the branch at `fromStream:501`:
`schemaVersion != CURRENT_VERSION_NUMBER && VERSION_NUMBER_V5 != schemaVersion` throws the export/import redirect. The branch admits **two** accepted versions: the current number and the hard-coded `VERSION_NUMBER_V5 = 5`. Today `CURRENT_VERSION_NUMBER == VERSION_NUMBER_V4 == 4`, so V4 and V5 both pass. If the track bumps only `CURRENT_VERSION_NUMBER` (to 6, say) and leaves the `VERSION_NUMBER_V5` disjunct, a V5 database is still **accepted** and falls through into the per-class-link-set parser. The new `fromStream` will then read `getProperty("classes")` expecting a link set but find the old monolithic EMBEDDEDSET, so it silently misreads (or NPEs on a non-link value) instead of producing the intended clean "export and reimport" redirect. Likelihood: certain for any pre-bump database that opens; impact: a misleading failure mode on exactly the migration path D14/D20 exist to make clean.
**Proposed fix**: The track's `fromStream` rewrite must tighten the gate so the accepted set is exactly the new current number — drop or guard the `VERSION_NUMBER_V5` disjunct (it predates this format and its 2.0-M1/M2 compatibility comment no longer applies once the format diverges). Add an explicit decomposition step + test: open a fixture record carrying `schemaVersion = 4` (and one with `5`) under the new binaries and assert the redirect `ConfigurationException`, not a parse.

### R2 [should-fix]
**Certificate**: A2 (Assumption — `SchemaShared` is the only parser of the schema record's `classes` field)
**Location**: out-of-footprint coupling; `DatabaseCompare.convertSchemaDoc` @ `core/.../db/tool/DatabaseCompare.java:946-948`.
**Issue**: A second parser of the schema record's `"classes"` field lives outside `SchemaShared`. `DatabaseCompare.convertSchemaDoc` does `entity.getProperty("classes")` and re-sets it as `EMBEDDEDSET`, then iterates `entity.<Set<EntityImpl>>getProperty("classes")`. When the track switches `toStream` to write `"classes"` as a **link set** of per-class record identities, this code reads a different shape: the `EMBEDDEDSET` re-set and the `Set<EntityImpl>` cast no longer hold against a link set of RIDs. `DatabaseCompare` has no production callers (PSI find-usages: TEST only), but it is exercised by live regression suites the track must keep green — `LocalPaginatedStorageRestoreFromWALIT`, `LocalPaginatedStorageRestoreTx`, the `StorageBackupMT*`/`StorageBackup*` ITs, and the `DbImportExport*` tests in the `tests` module. Those tests run after the format change and would break. The track's Interfaces section does not list `db/tool` in scope, so the break is latent. Likelihood: high (these are standing suites); impact: a green-to-red that looks like a regression in unrelated backup/restore code.
**Proposed fix**: Add `DatabaseCompare.convertSchemaDoc` to the track's in-scope footprint and update it to resolve the per-class records through the link set (load each linked entity), mirroring the `fromStream` change; or, if the comparison only needs logical-class equality, have it read through the loaded `SchemaShared` rather than re-parsing the raw record. Either way the decomposition needs an explicit step and the affected ITs must be in the track's test run, not deferred to Track 8.

### R3 [should-fix]
**Certificate**: E1 (Exposure — the storage-leads persist path `releaseSchemaWriteLock(iSave) -> saveInternal -> toStream`)
**Location**: track Validation acceptance #1 and Purpose ("write only the changed class records"); persist path `SchemaShared.saveInternal` @ `SchemaShared.java:817` reached from `releaseSchemaWriteLock(session, true)` @ `SchemaShared.java:433`.
**Issue**: Acceptance criterion #1 — "a one-class change writes only that class's record (plus the root when its payload changed), not the full schema" — is asserted as a Track-2 deliverable, but the Track-2 persist path cannot deliver it. The track runs under "today's storage-leads model" (its own Context note), where a structural change calls `releaseSchemaWriteLock(session, iSave=true)` → `saveInternal` → `session.executeInTx(toStream)`. `toStream` re-derives **every** class entity from the live `SchemaShared.classes` and writes each into the record via `EntityImpl.setProperty`, which calls `checkForBinding()` and marks the entity dirty unconditionally — there is no value-equality short-circuit at `EntityImpl.setProperty` (`core/.../record/impl/EntityImpl.java`). So a one-class change re-dirties and rewrites all per-class records. The mechanism that limits the write to the changed classes is per-property dirty tracking, which the plan's own D14 and D6 attribute to **Track 4** ("per-property dirty tracking (D6) limits the write to the classes that actually changed"). Likelihood: certain under the storage-leads persist path; impact: the headline write-amplification win is not demonstrable at Track 2's boundary, so a literal reading of acceptance #1 fails its own gate.
**Proposed fix**: Re-scope acceptance #1 for Track 2 to the **format** change (the record now links per-class entities; a class entity is an independently addressable record) and move the "writes only the changed record" measurement to a Track-4-completed end-to-end property where D6's dirty tracking is in place — or add a Track-2-local mechanism (only `setProperty`/save the per-class records whose source `SchemaClassImpl` is dirty) and a decomposition step + assertion that an unchanged class's record version does not advance after a sibling-class change. Pick one explicitly; the current wording promises the write reduction at a boundary that cannot observe it.

### R4 [suggestion]
**Certificate**: E2 (Exposure — per-class serialization shape change; the index-manager mirror)
**Location**: `SchemaClassImpl.toStream` @ `core/.../metadata/schema/SchemaClassImpl.java:569`; mirror at `IndexManagerAbstract.addIndexInternalNoLock` @ `core/.../index/IndexManagerAbstract.java:212`.
**Issue**: `SchemaClassImpl.toStream` today returns `session.newEmbeddedEntity()` — an embedded sub-entity that lives inside the single schema record and has no RID. The split requires each class entity to become a **standalone record**: saved to obtain a RID, that RID added to the root's link set, and (on drop) the record deleted and the link removed. This is a real shape change, not a field add, and it has to happen inside `saveInternal`'s `executeInTx` so the multi-record write is atomic. The risk is low because the codebase already runs this exact pattern: `addIndexInternalNoLock` does `indexEntity.getOrCreateLinkSet(CONFIG_INDEXES).add(index.getIdentity())`, and `IndexManagerAbstract.load` binds each index from `entity.getLinkSet(CONFIG_INDEXES)` + `transaction.loadEntity(...)`. Mirroring it is straightforward.
**Proposed fix**: Decompose the class-entity-becomes-record change as its own step (save-and-link on create, delete-and-unlink on drop, round-trip RID preservation) and name `addIndexInternalNoLock` / `IndexManagerAbstract.load` as the reference implementation to copy. The track already lists round-trip RID preservation in its acceptance set; this just makes the standalone-record conversion an explicit unit rather than folding it into "rework `toStream`/`fromStream`".

### R5 [suggestion]
**Certificate**: A3 (Assumption — the `CURRENT_VERSION_NUMBER` bump is internal to the schema record)
**Location**: `DatabaseExport.java:380` (`writeNumberField("schema-version", SchemaShared.CURRENT_VERSION_NUMBER)`); `DatabaseImport.java:504` (reads `schema-version`).
**Issue**: `CURRENT_VERSION_NUMBER` is not confined to the on-disk schema record. `DatabaseExport` stamps it into the export JSON's `schema-version` field, and `DatabaseImport` reads it back. Bumping the constant in Track 2 silently changes the value every export written after Track 2 carries, while the importer's acceptance of that value is Track 8's concern (D20, EXPORTER_VERSION 14→15). The plan's track ordering is correct — Track 8 depends on Track 2 — so the sequence is sound, but the cross-track coupling is unstated in Track 2's Interfaces section, and an export produced between Track 2 and Track 8 would carry a `schema-version` the current importer may not expect. Likelihood: low (intra-branch, ordered); impact: a confusing import failure if anyone exports/imports across the Track 2→8 gap.
**Proposed fix**: Note the `DatabaseExport`/`DatabaseImport` `schema-version` coupling in Track 2's Interfaces ("Inter-track dependencies") so Track 8 picks up the importer-side acceptance of the bumped value, and so a reviewer who sees `DatabaseExport.java` touched by the constant bump understands it is intentional and Track-8-completed.

## Evidence base

#### A1 Assumption: the open-time version gate is a clean reject for any non-current format
- **Track claim**: "Bump `CURRENT_VERSION_NUMBER` and turn the version check on open into a reject-and-redirect gate pointing at export/import" (Plan of Work); acceptance #4: "Opening an old-format (pre-bump) database ... is rejected with a redirect to export/import."
- **Evidence search**: PSI `findClass` on `SchemaShared` + member listing; grep for `CURRENT_VERSION_NUMBER`/`VERSION_NUMBER_V5`/`schemaVersion` across `core/src/main` (the only main-code references are in `SchemaShared` plus the export/import stamp).
- **Code evidence**: `SchemaShared.java:62-66` declares `CURRENT_VERSION_NUMBER = 4`, `VERSION_NUMBER_V4 = 4`, `VERSION_NUMBER_V5 = 5`. The gate at `SchemaShared.java:501` is `else if (schemaVersion != CURRENT_VERSION_NUMBER && VERSION_NUMBER_V5 != schemaVersion) { throw new ConfigurationException(...export...reimport...) }`. The accepted set is `{CURRENT_VERSION_NUMBER, 5}`, not `{current}`.
- **Verdict**: UNVALIDATED
- **Detail**: The gate already exists and already redirects, so the track's "turn the version check into a reject-and-redirect gate" is partly a no-op — the work is the bump. But the `VERSION_NUMBER_V5` disjunct means a bare bump does not reject V5; the assumed clean-reject behavior holds only if the rewrite also removes/guards that disjunct. Produces R1.

#### A2 Assumption: SchemaShared is the only parser of the schema record's classes field
- **Track claim**: in-scope is "`SchemaShared` serialization (`toStream`/`fromStream`)"; out-of-scope lists only the migrator, the tx-local view, and index-manager record changes. Implies no other code reads the raw `"classes"` field.
- **Evidence search**: grep `getProperty("classes")` / `getProperty("globalProperties")` / `getProperty("collectionCounter")` / `getProperty("blobCollections")` across `core/src/main`, excluding `SchemaShared.java`; PSI `ReferencesSearch` on `DatabaseCompare` to classify caller scope (TEST vs MAIN).
- **Code evidence**: `DatabaseCompare.java:946-948` reads `entity.getProperty("classes")`, re-sets it `EMBEDDEDSET`, and iterates `entity.<Set<EntityImpl>>getProperty("classes")` (method `convertSchemaDoc`). PSI find-usages of `DatabaseCompare`: all callers are TEST — backup/restore ITs (`LocalPaginatedStorageRestoreFromWALIT`, `LocalPaginatedStorageRestoreTx`, `StorageBackupMT*`, `StorageBackup*`) and `tests` module `DbImportExport*`.
- **Verdict**: CONTRADICTED
- **Detail**: A second parser of the schema record's `classes` field exists outside `SchemaShared`, and it hard-codes the EMBEDDEDSET shape. It is test-only (no production blast radius), but it is reached by standing IT suites that the track must keep green. Produces R2.

#### E1 Exposure: the storage-leads schema-persist path
- **Track claim**: "write only the changed class records plus the root when its non-link payload changes" (Plan of Work); acceptance #1: "A one-class change writes only that class's record ... not the full schema."
- **Critical path trace**:
  1. Entry: `SchemaEmbedded.doCreateClass` / `doDropClass` ends with `releaseSchemaWriteLock(session)` @ `SchemaEmbedded.java:136/320/406`.
  2. `SchemaShared.releaseSchemaWriteLock(session, iSave=true)` @ `SchemaShared.java:423` — on `modificationCounter == 1` and an `AbstractStorage`, calls `saveInternal(session)` @ `SchemaShared.java:433`.
  3. `SchemaShared.saveInternal` @ `SchemaShared.java:817` — `session.executeInTx(transaction -> toStream(session))` then `forceSnapshot()`.
  4. `SchemaShared.toStream` @ `SchemaShared.java:644` — loops `for (var c : realClases) classesEntities.add(c.toStream(session))` and `entity.setProperty("classes", classesEntities, EMBEDDEDSET)`, plus `globalProperties`, `collectionCounter`, `blobCollections` on the root.
  5. `EntityImpl.setProperty` calls `checkForBinding()` and marks the entity dirty with no value-equality short-circuit (`EntityImpl.java`, every `setProperty` overload routes through `checkForBinding()`).
- **Blast radius**: under this path a one-class change re-derives and re-dirties every per-class record, so the write set is all classes, not one. The write-amplification reduction depends on per-property dirty tracking (D6) that the plan assigns to Track 4.
- **Existing safeguards**: the persist runs inside `executeInTx`, so the multi-record write is atomic (rollback-safe). `ImmutableSchema` (`ImmutableSchema.java:62-88`) reads only the live `SchemaShared` getters, not the record, so the read-side snapshot is transparent to the format change — that part of the blast radius is bounded.
- **Residual risk**: MEDIUM — correctness is fine (atomic, snapshot-transparent); the risk is that acceptance #1's measurable claim is not observable at Track 2's boundary. Produces R3.

#### E2 Exposure: per-class serialization shape change (embedded sub-entity to standalone record)
- **Track claim**: "each class serializes into its own record"; "Add the net-new record-RID field to `SchemaClassImpl`, bound at load from the link set the same way the index manager binds each index."
- **Critical path trace**:
  1. `SchemaClassImpl.toStream` @ `SchemaClassImpl.java:569` returns `session.newEmbeddedEntity()` — embedded, no RID.
  2. Called only from `SchemaShared.toStream:655` (PSI find-usages: single caller).
  3. Read side: `SchemaShared.fromStream:537/540` calls `SchemaClassImpl.fromStream(c)` over `entity.getProperty("classes")` (today an EMBEDDEDSET of embedded entities).
  4. Mirror target: `IndexManagerAbstract.addIndexInternalNoLock:212` does `indexEntity.getOrCreateLinkSet(CONFIG_INDEXES).add(index.getIdentity())`; `IndexManagerAbstract.load:191` binds each index from `entity.getLinkSet(CONFIG_INDEXES)` + `transaction.loadEntity(indexIdentifiable)`.
- **Blast radius**: the conversion is local to `SchemaShared.toStream`/`fromStream` and `SchemaClassImpl.toStream` (all single-caller per PSI); it does not ripple into the read-time snapshot. The work is: save each class entity for a RID, link it, delete-and-unlink on drop, and bind the RID at load.
- **Existing safeguards**: the index manager runs the identical pattern in production, so the approach is proven; round-trip RID preservation is already an acceptance criterion.
- **Residual risk**: LOW — proven mirror, bounded callers. Produces R4 (decomposition clarity), not a correctness blocker.

#### A3 Assumption: the CURRENT_VERSION_NUMBER bump is internal to the schema record
- **Track claim**: "Bump `CURRENT_VERSION_NUMBER`" listed only against the open-time gate; Interfaces "Signatures" names "the schema version constant" with no export/import note.
- **Evidence search**: grep `CURRENT_VERSION_NUMBER` across `core/src/main`.
- **Code evidence**: `DatabaseExport.java:380` `jsonGenerator.writeNumberField("schema-version", SchemaShared.CURRENT_VERSION_NUMBER)`; `DatabaseImport.java:504` reads `schema-version`. The constant is consumed by the export/import path, not only the open-time gate.
- **Verdict**: UNVALIDATED
- **Detail**: The bump changes the export manifest's `schema-version` value. Track ordering (Track 8 depends on Track 2) keeps this safe, but the coupling is unstated in Track 2's Interfaces. Produces R5.
