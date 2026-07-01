<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: R7, sev: suggestion, loc: track-5.md:191-193, anchor: "### R7 ", cert: E9, basis: "the R4 fix settled the D12 v1 boundary as a loud rejection in Plan of Work (114-116), but the acceptance line still carries the pre-settlement disjunction 'loud rejection ... or accept with a documented heap envelope', so the test description contradicts the now-settled decision"}
verdicts:
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
  - {id: R5, verdict: VERIFIED}
  - {id: R6, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

## Findings

### R7 [suggestion]
**Certificate**: E9 (the R4-settled boundary vs. the still-conditional acceptance line)
**Location**: `## Validation and Acceptance` populated-class-build line (track-5.md:191-193); settled in `## Plan of Work` (track-5.md:114-116); residual open phrasing also in D12 Risks/Caveats (track-5.md:46).
**Issue**: The R4 fix settled the D12 v1 boundary firmly in the Plan of Work — "v1 builds only an empty source collection, and a build whose source collection is non-empty is a loud rejection pointing at YTDB-1064; the accept-with-documented-heap-envelope alternative is deferred to YTDB-1064" (track-5.md:114-116). But the matching acceptance line still reads "A populated-class build beyond the v1 size bound behaves per the settled boundary decision (loud rejection pointing at YTDB-1064, **or** accept with a documented heap envelope)" (track-5.md:191-193), carrying the pre-settlement disjunction. An implementer reading only the acceptance line sees the decision as still open, and the test it names (assert-the-exception vs. assert-the-build-completes) is exactly the arm-dependent test R4 flagged. The decomposer should not have to cross-reference the Plan of Work to learn which arm to test. (D12 Risks/Caveats at track-5.md:46 also still calls this "the open Phase-1 decision this track settles"; that phrasing is defensible as the record of what the track resolves, but the acceptance line is the sharper contradiction because it directly describes a test.)
**Proposed fix**: Rewrite the acceptance line to the settled arm only: a populated (non-empty source collection) build is rejected loudly with a message pointing at YTDB-1064; assert the rejection, not a build-completes-within-envelope path. Optionally tighten D12 Risks/Caveats:46 to state the boundary is now settled to loud-reject rather than leaving it phrased as open.

## Evidence base

#### E9 The R4-settled boundary is not reflected in the acceptance line
- **Fix under re-check (R4)**: Plan of Work settles the v1 populated-class boundary as a loud rejection pointing at YTDB-1064, with the heap-envelope alternative deferred to YTDB-1064 (track-5.md:114-116). VERIFIED as applied for the decision itself.
- **Residual**: the acceptance line (track-5.md:191-193) retains the disjunctive "loud rejection ... or accept with a documented heap envelope," so the test description still spans both arms.
- **Severity**: suggestion — documentation-accuracy only; the decision is unambiguously settled in the Plan of Work, so decomposition can proceed by reading it, but the acceptance line is the artifact that becomes a test method and should name the single settled arm.

---

## Verification certificates

#### Verify R1: force-rebuild invalidating only the tx-local SchemaShared leaves the memoised snapshot / entity cache stale on the commit path
- **Original issue** (iter-1, should-fix): `resolveProvisionalCollectionIds` patches the tx-local class arrays but does not `forceSnapshot()`, so a snapshot memoised during the tx (and `EntityImpl.getImmutableSchemaClass`'s version-keyed `immutableClazz` cache) still holds the provisional id; `computeCommitWorkingSet` then reads a `<= -2` id and `doGetAndCheckCollection` throws. The plan proposed a conditional "confirm or guard."
- **Fix applied**: D21 Risk (1) now states "The guard is definite, not conditional: after provisional-id resolution and before the working-set build, clear the pin and force-rebuild the snapshot (or resolve the working-set collection through the reconciled real id directly)" (track-5.md:60). D21 Risk (3) names three-layer invalidation — tx-local `SchemaShared` snapshot, pinned `MetadataDefault.immutableSchema`, and the snapshot version, "because `EntityImpl.getImmutableSchemaClass` ... caches `immutableClazz` and re-resolves only when `immutableSchema.getVersion()` advances" (track-5.md:60). Plan of Work repeats both the definite ordering and the three-layer chain (track-5.md:121-132). The commit-path acceptance line (track-5.md:209-211) is the regression guard.
- **Re-check**:
  - Track-file location: D21 Risk (1)+(3) (track-5.md:60); Plan of Work "Guard the commit-path read definitely" (track-5.md:126-132) and the three-layer invalidation clause (track-5.md:121-124).
  - Current state: the guard is definite (a mandated step with an ordering constraint — after provisional-id resolution, before the working-set build), and all three cache layers plus the `EntityImpl` version-keyed re-resolution are named. Matches the iter-1 proposed fix.
  - Criteria met: definite commit-path guard; three-layer cache invalidation; version-bump requirement for the entity cache.
- **Regression check**: checked the ordering-constraints paragraph (track-5.md:164-168) — the "snapshot force-rebuild must fire on every mid-tx index or class/property change before a later read" constraint is consistent with the widened trigger; no contradiction introduced.
- **Verdict**: VERIFIED

#### Verify R2: makeSnapshot tx-awareness needs an explicit outside-tx no-op and non-planner reader enumeration
- **Original issue** (iter-1, should-fix): the change flips read semantics for 94 production readers; the plan did not state the outside-tx no-op invariant (route through `resolve()`-style null-check, never seed) nor enumerate the non-planner readers (serializer, security) against the provisional-collection / unbuilt-engine surface.
- **Fix applied**: D21 Risk (4) — "`makeSnapshot` tx-awareness is a strict no-op outside a schema/index tx (no tx-local seed read), preserving the committed fast path" (track-5.md:60). Plan of Work — "resolve the tx-local `SchemaShared` when a schema or index tx is active — and stay a strict no-op otherwise, so the committed fast path is untouched" (track-5.md:118-120). Risk (2) enumerates the non-planner readers: "the fetch-step collection-scan setup (`FetchFromClassExecutionStep` ...), security id→name resolution, and serialization, alongside the WHERE-block planner" (track-5.md:60); Plan of Work restates it (track-5.md:132-137). Acceptance line 205-208 tests serializer + security id→name; line 212-214 tests the query fallthrough.
- **Re-check**:
  - Track-file location: D21 Risk (2)+(4) (track-5.md:60); Plan of Work (track-5.md:118-120, 132-137); acceptance lines (track-5.md:205-208, 212-214).
  - Current state: strict-no-op invariant stated; non-planner readers (fetch-step, security id→name, serialization) enumerated and each covered by the skip/provisional-collection treatment; tested. Matches the iter-1 proposed fix, including the "never seed" intent captured by "no tx-local seed read."
  - Criteria met: outside-tx no-op invariant; non-planner reader enumeration; read-side non-seeding.
- **Regression check**: checked that the fast-path claim does not conflict with the D15 force-rebuild widening — the no-op applies outside a schema/index tx, the force-rebuild inside one; consistent. Clean.
- **Verdict**: VERIFIED

#### Verify R3: failed engine-creating commit orphans the id-named engine .grb on the in-memory profile
- **Original issue** (iter-1, should-fix): the I-A4 engine arm asserted only registry-cleanliness, not that the on-disk/in-cache engine component is gone; the in-memory profile does not revert an eager engine-file `addFile` on rollback (the YTDB-1175 shape), so the plan needed a create-side fresh-atomic-op revert mirroring `undoReconciledCollections` plus an in-memory-profile test.
- **Fix applied**: Plan of Work gap (1) — "the create-side engine-file revert mirrors Track 4's component-guarded arm in `undoReconciledCollections` (a fresh atomic op, guarded on the component being present, a no-op on disk), because the default in-memory profile does not revert an eager engine-file `addFile` on rollback" (track-5.md:144-147). Acceptance I-A4 (track-5.md:215-219) — "The assertion runs on both the default in-memory profile and the disk profile; the in-memory profile caught the equivalent Track-4 collection-arm leak (YTDB-1175)."
- **Re-check**:
  - Track-file location: Plan of Work gap (1) (track-5.md:140-147); acceptance I-A4 engine arm (track-5.md:215-219).
  - Codebase confirmation (PSI): `AbstractStorage.undoReconciledCollections` exists (bodyLen 3211) and carries a create-side revert arm — it drops each commit-created collection's surviving in-memory-engine structure via `revertCreatedCollectionStructure(realId, collection)`, guarded on `collection != null` (component-present), with a Javadoc noting "in-memory engine only." The track file's "component-guarded arm in `undoReconciledCollections` ... a no-op on disk" is an accurate description of the pattern to mirror.
  - Current state: create-side engine-file revert named and anchored to the real `undoReconciledCollections` pattern; the acceptance test now runs on both profiles and cites YTDB-1175. Matches the iter-1 proposed fix.
  - Criteria met: fresh-atomic-op, component-present-guarded, no-op-on-disk engine revert; dual-profile acceptance; id-reuse assertion (track-5.md:215-217).
- **Regression check**: checked that the deferred-publish rule (Plan of Work / ordering constraint track-5.md:168) is unchanged and that the revert is create-side only — no conflict with the drop-side gap (3). Clean.
- **Verdict**: VERIFIED

#### Verify R4: the D12 v1 empty-class boundary is an unmade decision
- **Original issue** (iter-1, should-fix): D12 carried an unmade decision (loud-reject vs. documented-heap-envelope) into the track; the two arms produce different code and different tests, pushing a DESIGN_DECISION into Phase B.
- **Fix applied**: Plan of Work — "Settle the v1 populated-class boundary: v1 builds only an empty source collection, and a build whose source collection is non-empty is a loud rejection pointing at YTDB-1064; the accept-with-documented-heap-envelope alternative is deferred to YTDB-1064" (track-5.md:114-116).
- **Re-check**:
  - Track-file location: Plan of Work (track-5.md:114-116).
  - Current state: the decision is settled to the loud-reject arm with a concrete "empty source collection" scope and the envelope alternative explicitly deferred. The core decision R4 asked to make is made, so decomposition can write the build step and its test deterministically. Matches the iter-1 proposed fix on the decision itself.
  - Criteria met: the arm is chosen; the scope is "empty source collection"; the deferred alternative points at YTDB-1064.
- **Regression check**: the acceptance line (track-5.md:191-193) was NOT updated to the settled arm and still carries the disjunction — a residual documentation inconsistency the fix introduced. Filed as R7 (suggestion). The core decision is fully settled in the Plan of Work, so this does not block; it is a doc-accuracy follow-up, not a re-open.
- **Verdict**: VERIFIED (with new suggestion R7 for the un-updated acceptance line)

#### Verify R5 (accepted suggestion): D21 cites stale AbstractStorage offsets
- **Original issue** (iter-1, suggestion): D21 Risk (1) cited stale absolute offsets (2410/2528/2473/2691); the mechanism was correct but the anchors drifted, wasting a lookup.
- **Fix applied**: the track-file D21 Risk (1) now uses method-relative anchors — "run after `reconcileCollections` and before the trailing `forceSnapshot`" (track-5.md:60, 126-129) — with the brittle absolute numbers removed. The plan-file D21 seed (implementation-plan.md:252) keeps its historical offsets per the D7 live-carrier model (the track file is the live carrier; the plan seed is a frozen record).
- **Re-check**:
  - Track-file location: D21 Risk (1) (track-5.md:60) and Plan of Work commit-path guard (track-5.md:126-129) — method-relative, no bare `AbstractStorage.java:NNNN` offsets.
  - Plan-file location: D21 Risk (1) (implementation-plan.md:252) retains 2410/2528/2473/2691 — the accepted-suggestion resolution (keep the seed's historical offsets under D7).
  - Current state: the live carrier (track file) is offset-free and method-anchored; the plan seed's offsets are intentionally retained. Matches the described resolution exactly.
  - Criteria met: reference-accuracy — decomposition anchors on the offset-free track file.
- **Regression check**: confirmed the method-relative phrasing names the correct call ordering (reconcile before working-set build, working-set build before trailing forceSnapshot), consistent with the R1 verified ordering. Clean.
- **Verdict**: VERIFIED (accepted suggestion resolved as described)

#### Verify R6 (accepted suggestion): the provisional-collection index gap throws at handle-build, not commit
- **Original issue** (iter-1, suggestion): the plan described the gap as resolved "at commit re-resolve," but the throw fires earlier at handle-build (`findCollectionsByIds` -> `getCollectionNameById` returns null for `id < 0`), before commit; the resolution must intercept at handle-build too.
- **Fix applied**: Plan of Work gap (2) — "the interception is at the deferred handle-build (`IndexManagerEmbedded.createIndex` resolving collection ids via `findCollectionsByIds`, which throws on a `<= -2` id today), not only at the commit-time re-resolve" (track-5.md:152-155). Acceptance line 220-222 covers the end-to-end deferred-handle resolution.
- **Re-check**:
  - Track-file location: Plan of Work gap (2) (track-5.md:147-155); acceptance line (track-5.md:220-222).
  - Codebase confirmation (PSI): `IndexManagerEmbedded.createIndex(DatabaseSessionEmbedded, String, String, IndexDefinition, int[], ProgressListener, Map, String)` calls `findCollectionsByIds`; `findCollectionsByIds` calls `getCollectionNameById` and throws `IndexException(... "Collection with id " + collectionId + " does not exist.")` when the name is null (as for a negative/provisional id). The track file's "throws on a `<= -2` id ... via `findCollectionsByIds`" is accurate.
  - Current state: the handle-build interception point is named explicitly and distinguished from the commit-time re-resolve (two resolution points). Matches the iter-1 proposed fix.
  - Criteria met: interception at the deferred `createIndex` handle-build via `findCollectionsByIds`; second re-resolve at commit.
- **Regression check**: checked that the D21 provisional-collection treatment (Risk 2) and this create-time index gap use the same `TxSchemaState` provisional-name carrier — consistent; no duplication conflict. Clean.
- **Verdict**: VERIFIED (accepted suggestion resolved as described)

---

## Summary

**PASS.** All six iter-1 risk findings (R1–R4 should-fix, R5–R6 suggestion) are resolved as described by the orchestrator's track-file edits, PSI-confirmed where an edit newly named a symbol (R3 `undoReconciledCollections` component-guarded revert arm; R6 `findCollectionsByIds` throw-on-negative-id at the `IndexManagerEmbedded.createIndex` deferred branch). One new low-severity inconsistency the R4 fix left behind: the populated-class-build acceptance line (track-5.md:191-193) still carries the pre-settlement "loud rejection ... or accept with a documented heap envelope" disjunction, contradicting the now-settled Plan-of-Work decision — filed as R7 (suggestion). No blocker or should-fix survives; the suggestion does not gate.
