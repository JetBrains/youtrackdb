<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A6, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Track 5 adversarial gate verification — iteration 2

BLUF: PASS. All four should-fix findings (A1/A2/A3/A5) and both suggestions
(A4/A6) are resolved by the orchestrator's track-file edits. The iteration-1
evidence verdicts stand (PSI-confirmed then, spot-re-confirmed now); this pass
checks only that each fix landed correctly and introduced no inconsistency. No
new finding. The three symbol anchors the edits newly name resolve at HEAD:
`EntityImpl.java:4194-4210` (version cache), `MetadataDefault` pin methods
(@78/@88/@106), and the method-relative `AbstractStorage` commit-path names.

## Findings

<!-- Pure-verdict pass: no new finding surfaced. -->

## Evidence base

#### Verify A1: D21 commit-path guard is now definite, not conditional
- **Original issue**: the commit-entry snapshot is pinned from committed state before
  reconcile, so ordering alone does not save the commit-path read; the caveat said
  "verify or guard" without committing to a guard.
- **Fix applied**: D21 Risks/Caveats (1) (track-5.md:60) now states "The guard is
  definite, not conditional: after provisional-id resolution and before the working-set
  build, clear the pin and force-rebuild the snapshot (or resolve the working-set
  collection through the reconciled real id directly)." Plan of Work (track-5.md:126-131)
  repeats the definite guard with the same two arms.
- **Re-check**:
  - Location: D21 record risk (1); Plan of Work paragraph 2.
  - Current state: the mandatory guard is committed with two named arms (clear-pin +
    force-rebuild, or resolve through the reconciled real id). Matches A1's proposed fix
    arm (b) plus its alternative.
  - Criteria met: the plan no longer relies on ordering alone; the pin defeat is
    explicitly closed before the working-set build (`computeCommitWorkingSet`, def@2371
    per PSI, runs after `reconcileCollections` @2781).
- **Regression check**: checked the ordering prose against PSI — "run after
  `reconcileCollections`, before the trailing `forceSnapshot`" matches HEAD. No new
  issue.
- **Verdict**: VERIFIED

#### Verify A2: force-rebuild invalidates the pinned MetadataDefault.immutableSchema
- **Original issue**: `getImmutableSchemaSnapshot` returns the pinned `immutableSchema`
  without re-invoking `makeSnapshot`; D15 force-rebuild of the tx-local `SchemaShared`
  alone does not refresh a pinned read.
- **Fix applied**: D21 Risks/Caveats (3) names three cache layers — "the tx-local
  `SchemaShared` snapshot, the pinned `MetadataDefault.immutableSchema`, and the snapshot
  version." Plan of Work (track-5.md:122-124) directs the widened force-rebuild to
  invalidate all three, naming the pinned `MetadataDefault.immutableSchema` explicitly.
- **Re-check**:
  - Location: D21 record risk (3); Plan of Work paragraph 2.
  - Current state: pin invalidation is now an explicit sub-step, distinct from the
    tx-local `SchemaShared` rebuild. PSI confirms `getImmutableSchemaSnapshot`@106,
    `makeThreadLocalSchemaSnapshot`@78, `clearThreadLocalSchemaSnapshot`@88 — the pin
    lifecycle the fix targets exists as cited.
  - Criteria met: the "reuse D15's rebuild" wording is no longer under-specified.
- **Regression check**: three-layer list is internally consistent with A3's version bump
  (layer 3). No new issue.
- **Verdict**: VERIFIED

#### Verify A3: snapshot version advances so EntityImpl.immutableClazz re-resolves
- **Original issue**: `EntityImpl.immutableClazz` is version-cached; a mid-tx
  property/rule change is skipped unless `immutableSchema.getVersion()` advances (I-P5
  silently fails).
- **Fix applied**: D21 Risks/Caveats (3) cites `EntityImpl.java:4194-4210` and requires
  the snapshot version to advance on force-rebuild. Plan of Work bumps the snapshot
  version (layer 3 of the three-layer invalidation). A new Validation line
  (track-5.md:202-204) exercises a property/rule add on an already-resolved class and
  asserts enforcement on a second entity.
- **Re-check**:
  - Location: D21 record risk (3); Plan of Work; Validation & Acceptance line.
  - Current state: PSI confirms the cited region exactly — `if (immutableClazz == null)`
    @4194, the `else if (immutableSchemaVersion != immutableSchema.getVersion())` branch
    @4207-4210 that returns the stale class when the version is unchanged. The anchor is
    accurate, not drifted.
  - Criteria met: version bump discipline is stated and test-covered.
- **Regression check**: the new Validation line is coherent with A2's pin invalidation
  (both fire on the same force-rebuild). No new issue.
- **Verdict**: VERIFIED

#### Verify A5: provisional-id exposure handled at the non-planner readers
- **Original issue**: the tx-aware 174-caller snapshot exposes the provisional collection
  id to non-planner readers (security id→name, serializers), which risk (2) scoped only
  to the planner.
- **Fix applied**: D21 Risks/Caveats (2) (track-5.md:60) extends D13's skip-unbuilt
  treatment to "every snapshot reader that resolves a collection for a
  provisional-collection class" — naming `FetchFromClassExecutionStep`, security id→name,
  and serialization alongside the WHERE-block planner. Plan of Work (track-5.md:132-137)
  repeats the extension. Two new Validation lines (track-5.md:205-208 non-planner
  serialize/id→name; 212-214 query fallthrough) cover it.
- **Re-check**:
  - Location: D21 record risk (2); Plan of Work paragraph 2; Validation lines.
  - Current state: the non-planner readers are enumerated exactly as A5's proposed fix
    requested. The "In scope" list (track-5.md:245-248) itemizes the D13 planner
    extension for provisional-collection classes.
  - Criteria met: the enumeration now covers serialize/security, not the planner alone.
- **Regression check**: the extension reuses the `TxSchemaState` carrier that the
  create-side index gap already uses (Plan of Work paragraph 3, track-5.md:151-155) — no
  contradictory mechanism introduced. No new issue.
- **Verdict**: VERIFIED

#### Verify A4 (accepted suggestion): stale AbstractStorage line anchors replaced
- **Original issue**: D21 risk (1) cited `AbstractStorage` line numbers
  (2410/2473/2528/2691) that no longer match HEAD.
- **Fix applied**: D21 risk (1) now uses method-relative anchors — "run after
  `reconcileCollections`, before the trailing `forceSnapshot`" — with no bare numeric
  `AbstractStorage` line cites remaining in the D21 record.
- **Re-check**:
  - Location: D21 record risk (1) (track-5.md:60).
  - Current state: PSI confirms `computeCommitWorkingSet`@2371, `reconcileCollections`
    @2781; the prose no longer pins numeric lines that drift. Plan of Work says "after
    provisional-id resolution" as prose (PSI finds no `resolveProvisionalCollectionIds`
    method — so this is correctly left descriptive, not a symbol cite).
  - Criteria met: no stale anchor can misdirect the implementer.
- **Regression check**: scanned the whole D21 record and Plan of Work for surviving
  numeric `AbstractStorage` anchors — none. No new issue.
- **Verdict**: VERIFIED

#### Verify A6 (accepted decomposition guidance): D21 not fused into index-overlay work
- **Original issue**: keep D21 snapshot-tx-awareness as its own step cluster so a
  mid-Phase-B split stays clean; no track-file prose edit required.
- **Fix applied**: taken as decomposition guidance. `## Concrete Steps` (track-5.md:171)
  is still the Phase A placeholder, so no step fusion has occurred. The Plan of Work keeps
  D21 in its own paragraph (track-5.md:118-137), distinct from the index-overlay paragraph
  (track-5.md:101-116); the "In scope" list itemizes the tx-aware snapshot as a separate
  bullet (track-5.md:245-248).
- **Re-check**:
  - Location: Concrete Steps placeholder; Plan of Work paragraph separation; In-scope
    list.
  - Current state: nothing fuses D21 into the index-overlay work in a way that blocks a
    separate step cluster; the structural separation is preserved for Phase B.
  - Criteria met: guidance honored; the upcoming decomposition can grade the D21 cluster
    on its own risk.
- **Regression check**: the A1/A2/A3/A5 edits added to the D21 paragraph do not bleed
  into the index-overlay paragraph. No new issue.
- **Verdict**: VERIFIED

## Summary

PASS — 6/6 findings resolved (A1/A2/A3/A5 VERIFIED, A4/A6 VERIFIED as accepted
suggestion/guidance). 0 new findings. 0 blockers.
