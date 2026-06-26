<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: G1, sev: suggestion, loc: "plan/track-2.md:41", anchor: "### G1 ", cert: "Verify T3 residual", basis: "T3's named site (D14 Risks bullet 'temp->persistent RID at commit, D2') was left verbatim; the correction landed in Plan of Work (line 88) instead, so a Decision-Log-only reader still sees the imprecise D2 citation while the Plan of Work now says 'not D2'"}
  - {id: G2, sev: suggestion, loc: "implementation-plan.md:275", anchor: "### G2 ", cert: "Verify A4 residual", basis: "A4's track-side fix landed (F59 moved to Validation's 'Deferred to Track 4'), but the plan-level invariant I-U1 still bundles the Track-4 half ('the root is written exactly when its payload changes') as a Track-2 invariant with no Track-4 provenance note"}
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
  - {id: T4, verdict: VERIFIED}
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
  - {id: R5, verdict: VERIFIED}
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Phase A gate verification (iteration 2) — Track 2: Per-class schema records (D14)

Consolidated re-check of all 13 iteration-1 findings (technical T1-T4, risk R1-R5, adversarial A1-A4) against the amended track file and the plan's Track 2 scope line. All 13 fixes landed and introduced no regression: the version-gate trap (the only should-fix cluster, T1/R1/A3) is fully closed, `DatabaseCompare.convertSchemaDoc` is now in-scope (R2/A1), and selective-write/F59 are correctly localized to Track 4 across every operative section (T2/R3/A4). Two suggestion-level residuals remain in the Decision-Log block and the plan invariant; both are neutralized by the re-scoped operative sections and do not block the gate. PSI re-verified every Java symbol the fixes rest on. Overall: PASS.

## Verification certificates

#### Verify T1 / R1 / A3: version-gate bump trap (the two-accepted-versions hazard)
- **Original issue**: "Bump `CURRENT_VERSION_NUMBER`" was stated without a target value or a disposition for the `VERSION_NUMBER_V5 != schemaVersion` accept-arm. A bump to 5 collides with `VERSION_NUMBER_V5`; a bump to 6 leaves a legacy version-5 database silently accepted into the new link-set parser instead of reject-and-redirected.
- **Fix applied**: Plan of Work (track-2.md:100-107) now bumps `CURRENT_VERSION_NUMBER` to a value distinct from both `VERSION_NUMBER_V4` (4) and `VERSION_NUMBER_V5` (5), e.g. 6, AND tightens the gate to drop the `VERSION_NUMBER_V5 != schemaVersion` accept-arm so the predicate becomes `schemaVersion != CURRENT_VERSION_NUMBER` alone. Context (63-67) documents the existing gate and the two-accepted-versions trap. Validation (130-133) asserts reject-and-redirect for BOTH a version-4 record and a legacy version-5 record. Interfaces in-scope (161-162) lists the gate tightening.
- **Re-check**:
  - Track-file location: Plan of Work 100-107; Context 63-67; Validation 130-133; Interfaces 161-162.
  - Current state: the bump target is now explicit (distinct from 4 and 5), the V5 accept-arm is explicitly dropped, and the legacy-V5 reject is a named acceptance line — exactly the clean form T1/R1/A3 proposed.
  - PSI re-check: `CURRENT_VERSION_NUMBER = 4`, `VERSION_NUMBER_V4 = 4`, `VERSION_NUMBER_V5 = 5`. The gate predicate at `SchemaShared.java:501` is verbatim `schemaVersion != CURRENT_VERSION_NUMBER && VERSION_NUMBER_V5 != schemaVersion`, throwing the export/reimport `ConfigurationException`. `VERSION_NUMBER_V5` has exactly one usage (the gate); `VERSION_NUMBER_V4` has zero usages. The trap and its fix rest on accurate code facts.
  - Criteria met: premise (gate already exists, work is the bump + arm-drop) now stated; the assumed clean-reject behavior now holds because the rewrite removes the V5 disjunct.
- **Regression check**: Checked Context, Plan of Work, Validation, Interfaces for any surviving "turn the version check into a gate" phrasing that implies the gate is new — none; the track now says the redirect message "already exists and only needs to keep firing." No contradiction introduced. Clean.
- **Verdict**: VERIFIED

#### Verify T2 / R3 / A4: selective per-class write + F59 are Track-4-owned, not Track-2 deliverables
- **Original issue**: the track described the selective per-class write ("write only the changed class plus the root when its payload changed") and the F59 root-omission regression as Track-2 deliverables, but they require D6 dirty tracking (Track 4) and the Track-3 changed-class set; under Track 2's storage-leads `toStream` the full schema is always rewritten, so the write reduction is unobservable and F59 cannot manifest.
- **Fix applied**: Purpose (9-11, 17-23) re-scoped to the format only and states the selective write "lands in Track 4" and is "not yet observable here." Plan of Work (93-98) states storage-leads `saveInternal->toStream` rewrites the root and every class record, with the selective write / root-dirtiness rule / F59 depending on D6 + commit reconciliation (Track 4). Validation has a "Deferred to Track 4" subsection (138-144) holding the write-amplification win and the F59 restart regression. Interfaces out-of-scope (166-169) moves the selective write, the root-dirtiness rule, and the F59 regression to Track 4 (D6).
- **Re-check**:
  - Track-file location: Purpose 9-11/17-23; Plan of Work 93-98; Validation 138-144; Interfaces out-of-scope 166-169.
  - Current state: every operative section (the ones a decomposer reads to size and scope steps) now scopes Track 2 to the format and defers selective-write + F59 to Track 4. `grep` confirms all five F59 mentions outside the D14 record sit in deferral/out-of-scope/dependency context, never as a Track-2 acceptance line.
  - PSI re-check: `SchemaClassImpl.toStream` returns `newEmbeddedEntity` (embedded, no RID), confirming the format change is real and that no per-class dirty flag exists yet; `CURRENT_VERSION_NUMBER` usage at `SchemaShared.java:648` is the `toStream` wholesale write site. The storage-leads premise the re-scope rests on is accurate.
  - Criteria met: Track 2's testable acceptance is now round-trip + per-class-record reachability; the selective-write/F59 claims that cannot be observed at this boundary are deferred.
- **Regression check**: The D14 Decision Log block (40-41) still narrates the full D6/selective-write/F59 design rationale, but it (a) is the frozen-design-seeded canonical D-record that legitimately spans Track 2's format and Track 4's win, and (b) attributes the write limit to D6, which the plan maps to Track 4 — so it is internally consistent with the re-scoped sections and creates no Track-2-deliverable contradiction. Checked Purpose/Plan-of-Work/Validation/Interfaces for any residual "writes only that class" framed as Track-2-observable — none. The plan-level I-U1 residual is raised separately as G2. Clean.
- **Verdict**: VERIFIED

#### Verify T3: new-class record-RID resolves via the ordinary record path, not D2's collection-id scheme
- **Original issue**: D14's Risks bullet cites "D2" for the new-class temp->persistent RID, but D2 is provisional COLLECTION ids (negative sentinels, Track 4), a different mechanism from record RIDs.
- **Fix applied**: Plan of Work (87-88) now states "a new class's record RID resolves through the ordinary temp->persistent record-id path at commit, not D2's provisional-collection-id scheme."
- **Re-check**:
  - Track-file location: Plan of Work 87-88.
  - Current state: the misreading is explicitly corrected in the section the decomposer/implementer reads for the "how." PSI: D2 (`implementation-plan.md:116`) is "Provisional collection ids, resolved at commit," confirming the original mismatch and the correctness of the clarification.
  - Criteria met: the intent of T3 (disambiguate the new-class RID mechanism) is satisfied.
- **Regression check**: The named site — the D14 Risks bullet at line 41 — was left verbatim ("temp->persistent RID at commit, D2"), so a reader of the Decision Log alone still sees the imprecise citation while the Plan of Work now says the opposite. This is a minor internal-consistency residual; raised as G1 (suggestion). It does not reopen T3 because the operative correction landed.
- **Verdict**: VERIFIED

#### Verify T4 / R5: CURRENT_VERSION_NUMBER bump is read by DatabaseExport/DatabaseImport (cross-track coordination)
- **Original issue**: bumping the constant changes the `schema-version` field `DatabaseExport` writes and `DatabaseImport` reads; the coupling to Track 8 (EXPORTER_VERSION 14->15) was unstated.
- **Fix applied**: Interfaces inter-track note (177-179) now states the bump changes the `schema-version` field `DatabaseExport` writes and `DatabaseImport` reads, and that Track 8 (D20, EXPORTER_VERSION 14->15) must agree on the bumped value.
- **Re-check**:
  - Track-file location: Interfaces inter-track dependencies 177-179.
  - PSI re-check: `CURRENT_VERSION_NUMBER` find-usages returns exactly three sites — the gate (`SchemaShared.java:501`), `toStream` (`:648`), and `DatabaseExport.java:380`. `DatabaseImport` resolves. The coordination note matches the real read sites precisely.
  - Criteria met: the cross-track read is now documented so Track 8 picks up the importer-side acceptance and a reviewer who sees `DatabaseExport` touched understands it is intentional.
- **Regression check**: Checked the "Signatures" bullet (180-182) and the Track 8 entry (plan:407-416, D20/EXPORTER_VERSION 14->15) for agreement — consistent. Clean.
- **Verdict**: VERIFIED

#### Verify R2 / A1: DatabaseCompare.convertSchemaDoc added to footprint (the second raw-record schema parser)
- **Original issue**: `DatabaseCompare.convertSchemaDoc` parses the schema record's `"classes"` as an EMBEDDEDSET of embedded class docs and is exercised by standing backup/restore + import/export ITs that assert `differences == 0`; it was out of the in-scope list, so the link-set switch would silently break those suites.
- **Fix applied**: in-scope list (163-164) now includes `DatabaseCompare.convertSchemaDoc` ("the second raw-record `classes` parser, updated for the link set"). Context (66-70) describes the parser and the IT exposure. Plan of Work (108-111) describes updating it (resolve the linked records or drop the redundant root special-case). Validation (134-136) requires the standing `DatabaseCompare`-based ITs (`StorageBackup*`, `DbImportExport*`, `LocalPaginatedStorageRestore*`) stay green. Plan scope line (implementation-plan.md:353) bumped to ~9 files and names `DatabaseCompare.convertSchemaDoc`.
- **Re-check**:
  - Track-file location: Interfaces in-scope 163-164; Context 66-70; Plan of Work 108-111; Validation 134-136; plan scope line 353.
  - PSI re-check: `DatabaseCompare.convertSchemaDoc` at line 945 references `"classes"`. `ReferencesSearch` on `DatabaseCompare` returns 51 test references and 0 main references — confirming R2/A1's "test-only, no production blast radius, but reached by standing IT suites." Sample files include `LocalPaginatedStorageRestoreFromWALIT`, `StorageBackupTest`, `StorageBackupMTIT`, `StorageBackupMTRestoreIT`, `DbImportExportTest`, `DbImportStreamExportTest`, `LocalPaginatedStorageRestoreTx` — exactly the named green-must-stay suites.
  - Criteria met: footprint corrected to ~9 files; the parser is in-scope; the affected ITs are a named acceptance line, not deferred to Track 8.
- **Regression check**: Checked that the Validation IT requirement does not duplicate or conflict with Track 8's migration validation — Track 8 (plan:416) owns the migrator + version gate, distinct from Track 2's "keep the existing compare suites green." No overlap conflict. Clean.
- **Verdict**: VERIFIED

#### Verify R4: standalone-record shape decomposed (save/link on create, delete/unlink on drop)
- **Original issue**: `SchemaClassImpl.toStream` returns an embedded sub-entity with no RID; the split is a shape change (save each class as a standalone record, link it, delete-and-unlink on drop), not a field add, and was folded into "rework toStream/fromStream" without an explicit unit.
- **Fix applied**: Plan of Work (79-86) decomposes the change — save each class as a standalone record, add its RID to the root's link set on create, delete the record and unlink on drop, mirroring `IndexManagerAbstract.addIndexInternalNoLock` (`getOrCreateLinkSet(CONFIG_INDEXES).add(...)`) and `IndexManagerAbstract.load` (bind each entity from the link set), running inside `saveInternal`'s `executeInTx` for atomicity.
- **Re-check**:
  - Track-file location: Plan of Work 79-86.
  - PSI re-check: `SchemaClassImpl.toStream` (line 569) returns `newEmbeddedEntity` (confirmed). `IndexManagerAbstract.load` (line 191) reads `entity.getLinkSet(CONFIG_INDEXES)` and binds each via `transaction.loadEntity(indexIdentifiable)`; `addIndexInternalNoLock` (line 212) does `indexEntity.getOrCreateLinkSet(CONFIG_INDEXES).add(index.getIdentity())`. The mirror the track names is real and precise.
  - Criteria met: the standalone-record conversion is now an explicit unit with the proven index-manager reference implementation named.
- **Regression check**: Checked round-trip RID preservation is still an acceptance line (Validation 124-126) — present and consistent with the save/link decomposition. Clean.
- **Verdict**: VERIFIED

#### Verify A2: Track 1 dependency softened to an ordering convenience
- **Original issue**: the "depends on Track 1 (clean replay underneath)" rationale was over-stated — Track 2 creates schema records, not collection files, so its write path never emits a `FileCreatedWALRecord` and never exercises Track 1's missing-file replay fix; Track 4 is the real D10 consumer.
- **Fix applied**: Interfaces inter-track dependencies (170-176) now state the Track 1 dependency is "an ordering convenience — Track 2 creates schema records, not collection files, so its own write path does not exercise Track 1's `FileCreatedWALRecord` missing-file replay fix; sequencing it on a clean-replay base keeps the format change ahead of Track 4 (the real D10 consumer)."
- **Re-check**:
  - Track-file location: Interfaces 170-176.
  - Current state: the rationale matches A2's proposed wording; the `Depends on: Track 1` edge is kept (correct stack order, costs nothing). The prior-episode input confirms Track 1's `ensureFileForReplay` repairs a missing-file page redo, consumed by Track 4's I-A1 — consistent with "Track 4 is the real consumer."
  - Criteria met: the dependency is no longer presented as a functional crash-recovery prerequisite.
- **Regression check**: Checked the plan's Track 2 `Depends on:` edge still lists Track 1 (plan does not over-claim either) and that Track 4's dependency on Track 1 is intact (plan:376 lists Track 1). Clean.
- **Verdict**: VERIFIED

#### Verify A4 (and R3/T2 invariant facet): F59 root-omission regression deferred to Track 4
- **Original issue**: invariant I-U1's "root written exactly when its payload changes" and the F59 regression are unobservable under Track 2's wholesale `toStream`; the test that proves the load-bearing half must wait for Track 4's selective write.
- **Fix applied**: Validation's "Deferred to Track 4" subsection (138-144) holds the F59 restart regression with the explicit rationale that the root-omission hazard "only arises once selective per-class writes can omit the root." Track 2's own Validation (124-136) asserts only the observable properties (round-trip RID + payload preservation, per-class record reachability, the version-gate reject, IT-green).
- **Re-check**:
  - Track-file location: Validation 138-144.
  - PSI re-check: `toStream` at `SchemaShared.java:648` (wholesale write, the `CURRENT_VERSION_NUMBER` site) confirms the root is always written under storage-leads, so F59 has no constructible counterexample at Track 2 — the deferral is correct.
  - Criteria met: the F59 regression is now a Track-4 acceptance line; Track 2 asserts the weaker observable property.
- **Regression check**: The plan-level invariant I-U1 (`implementation-plan.md:275-276`) still states the Track-4 half ("the root is written exactly when its payload changes") under a "(Track 2)" label with no Track-4 provenance note. The track-side fix is correct; the plan invariant residual is raised as G2 (suggestion) and does not reopen A4 because the operative track Validation was re-scoped. Clean otherwise.
- **Verdict**: VERIFIED

## Findings

### G1 [suggestion]
**Certificate**: Verify T3 residual — D14 Risks bullet still cites D2 verbatim
**Location**: `plan/track-2.md:41` (D14 Risks/Caveats bullet, "a new class is a new record (temp->persistent RID at commit, D2)"); corrected counterpart at `plan/track-2.md:88`.
**Issue**: T3's named fix site was the D14 Risks bullet's "D2" citation. The fix instead added a clarifying sentence to the Plan of Work (line 88: "the ordinary temp->persistent record-id path at commit, not D2's provisional-collection-id scheme") and left the D14 Risks bullet verbatim. The two now disagree at the letter: the Decision Log says the new-class RID is a D2 matter, the Plan of Work says it is explicitly not D2. A reader who consults only the track's `## Decision Log` (the track-canonical live carrier) still sees the imprecise citation. The intent of T3 is satisfied — the operative Plan of Work is correct — so this is a low-impact text-consistency wrinkle, not a reopening.
**Proposed fix**: In the D14 Risks bullet, change "(temp->persistent RID at commit, D2)" to "(temp->persistent record-id at commit, the ordinary record path, not D2's collection-id scheme)" so the canonical D-record agrees with the Plan of Work. Optional; the Plan of Work already governs the implementation.

### G2 [suggestion]
**Certificate**: Verify A4 residual — plan invariant I-U1 still bundles the Track-4 half under a Track-2 label
**Location**: `implementation-plan.md:275-276` (invariant I-U1, "(Track 2) — per-class records remove write amplification, and the root is written exactly when its payload changes").
**Issue**: A4's track-side fix landed (the F59 regression is now in Track 2's "Deferred to Track 4" Validation subsection), but the plan's invariant list still states I-U1's second clause — "the root is written exactly when its payload changes" — as a Track-2 invariant. That clause is observable only once D6 selective writes (Track 4) can omit the root; under Track 2's wholesale `toStream` the root is always written, so the clause has no Track-2 counterexample. The invariant HOLDS as a design property; the residual is that its provenance label implies a Track-2 test home for a Track-4-tested half. A4 was a suggestion and proposed exactly this provenance note.
**Proposed fix**: Split I-U1's provenance, e.g. "(Track 2 delivers the per-class format; Track 4 makes 'root written exactly when its payload changes' observable and tests it)," or add a one-clause note that the second clause's test home is Track 4. Optional; the track-file Validation is already correctly scoped.

## Summary

PASS. All 13 iteration-1 findings (T1-T4, R1-R5, A1-A4) are VERIFIED — every fix landed in the amended track file (and the plan's Track 2 scope line for R2/A1) and introduced no regression or internal contradiction. The should-fix cluster is fully closed: the version-gate two-accepted-versions trap is fixed at the predicate level (drop the V5 accept-arm, bump to a value distinct from 4 and 5), `DatabaseCompare.convertSchemaDoc` is in-scope with the standing backup/restore/import ITs named as a green-must-stay acceptance line, and the selective-write/F59 behavior is localized to Track 4 across Purpose, Plan of Work, Validation, and Interfaces. PSI re-verified every load-bearing Java symbol claim (version constants and their exact usage sites, the gate predicate text, `SchemaClassImpl.toStream`'s embedded-entity return, `DatabaseCompare` test-only caller scope, the `IndexManagerAbstract` link-set mirror). Two suggestion-level residuals (G1: D14 Risks bullet still cites D2 verbatim while the Plan of Work corrects it; G2: plan invariant I-U1 still labels the Track-4 half as Track-2) are text-precision matters already neutralized by the correctly re-scoped operative sections; neither blocks the gate.
