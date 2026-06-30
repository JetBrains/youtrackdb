<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
verdicts:
  - {id: S1, verdict: VERIFIED}
  - {id: S2, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

## Verification certificates

#### Verify S1: Track-5 plan-file intro over the 1-3 sentence cap
- **Original issue**: the Track-5 plan-file checklist intro ran 5 paragraphs / ~15 sentences before `**Scope:**`, over the 1-3 sentence cap; three of the four `Also …` paragraphs (the I-A4/TB2 engine-cleanliness arm, the create-time provisional-collection index gap, the drop-side `dropIndex` no-op) were not present in `track-5.md` at the plan-file's specificity.
- **Fix applied**: (a) the three missing mechanisms were moved into `track-5.md` — a new paragraph in `## Plan of Work` numbered (1) failed-commit engine cleanliness, (2) create-time provisional-collection index gap, (3) drop-side commit half, plus three new lines in `## Validation and Acceptance`; (b) the plan-file Track-5 intro was trimmed to three sentences with a one-sentence nod to the three items pointing at `track-5.md`.
- **Re-check**:
  - Plan / track file location: `implementation-plan.md` Track-5 checklist entry (lines 470-480, intro before `**Scope:**`); `track-5.md` `## Plan of Work` (lines 128-145) and `## Validation and Acceptance` (lines 189-197).
  - Current state: the intro is three sentences — (1) "Give indexes a tx-local definition overlay … and make the planner skip an unbuilt index." (2) "Completes the I-A7 membership-ripple routing Track 3 de-guarded, and makes the immutable snapshot tx-aware so same-tx schema changes reach validation and serialization (D21)." (3) "Also lands three items the Track-4 completion review surfaced — the index-engine half of the failed-commit registry-cleanliness criterion (I-A4/TB2), the create-time provisional-collection index gap, and the drop-side `dropIndex` commit half — each detailed in `track-5.md`'s `## Plan of Work` and `## Validation and Acceptance`." `**Scope:**` and `**Depends on:**` follow, preserved. In `track-5.md` the three mechanisms now appear at full specificity: `## Plan of Work` carries the numbered (1)/(2)/(3) paragraph with the concrete symbols (`IndexManagerEmbedded.createIndex` deferred path, `getCollectionNameById` null-on-negative, `IndexException("Collection with id -2 does not exist")`, `IndexManagerEmbedded.java:590-600`, `markClassChanged`), and `## Validation and Acceptance` carries three matching acceptance lines (engine-arm cleanliness/id-reuse, create+index provisional resolution, drop-index registry+engine removal).
  - Criteria met: 1-3 sentence intro cap (now exactly three sentences); no mechanism content lost (the three items live in `track-5.md` at equal-or-greater specificity); faithful move (the new Plan-of-Work paragraph's content matches the previously-plan-file-only `Also …` items, and the intro's nod points at the exact two sections that now hold them).
- **Regression check**: the trimmed intro stays consistent with the `**Scope:**` line — Scope names `SchemaProxy.makeSnapshot` / the snapshot tx-awareness, the `computeCommitWorkingSet` commit-path guard, and same-tx-validation tests, which the intro's D21 sentence and `track-5.md` introduce. The one em-dash pair in sentence 3 is within a single sentence and under the em-dash cap. The new `## Plan of Work` paragraph (lines 128-145) does not duplicate or contradict the D21 paragraph (99-126), the overlay description (100-112), or the drop content earlier in the section: item (3) is the commit-removal half and explicitly opens "Beyond the tx-dropped overlay above," cross-referencing rather than colliding with the tx-dropped overlay category (line 102-103). Checked the intro, `**Scope:**`/`**Depends on:**` lines, and both moved-into sections — clean.
- **Verdict**: VERIFIED

#### Verify S2: missing plan-file invariant for D21's same-tx schema-contract enforcement
- **Original issue**: D21 adds same-tx schema-contract enforcement, but the Invariants block had no invariant for it (I-P2/I-P3/I-P4 cover only the index overlay).
- **Fix applied** (user chose "Add I-P5 + IP bullet"): added I-P5 (Track 5) to the Invariants block stating the snapshot's tx-aware class/property/constraint view enforcing a same-tx-created constraint in `EntityImpl.validate()` (D21); added a new Integration Point bullet noting `EntityImpl.validate()` / serialization read the contract through tx-aware `SchemaProxy.makeSnapshot()` (Track 5).
- **Re-check**:
  - Plan location: `implementation-plan.md` Invariants block (I-P5 at lines 282-284) and Integration Points (bullet at lines 305-307).
  - Current state: I-P5 reads "during a schema or index tx the immutable snapshot reflects tx-local classes, property types, and constraint rules, so `EntityImpl.validate()` enforces a same-tx-created constraint instead of silently skipping it (D21)" — 3 lines. The Integration Point bullet reads "`EntityImpl.validate()` and entity serialization read the schema contract through the tx-aware `SchemaProxy.makeSnapshot()`, so a same-tx schema change is enforced on that transaction's own entities (Track 5)" — 3 lines.
  - Criteria met: I-P5 present and ≤5 lines (3); the id is unique and sequential (I-P1, then I-P2/I-P3/I-P4, then I-P5 — no collision); it maps to `track-5.md`'s constraint-enforcement acceptance lines (177-182: strict-mode/mandatory/notnull/type/regex enforced same-tx, plus property-type/constraint added to an existing class). The Integration Point bullet present and ≤3 lines (3); it maps to `track-5.md` `## Context and Orientation` (87-92) and `## Plan of Work` (115-123).
- **Regression check**: I-P5 does not collide with any existing invariant id or restate I-P2/I-P3/I-P4 (those cover overlay/query-usability/final-state; I-P5 covers the contract view). The new Integration Point bullet does not collide with the other four — each names a distinct layer/track (mutex-engage Track 3, commit-signal Track 4, planner Track 5, validate/serialize Track 5, version-gate Track 8); the two Track-5 bullets address different read sites (the planner's index set vs `EntityImpl.validate()`'s contract). Neither addition pushes a block over a sizing budget: the Invariants block gained three lines, Integration Points three lines, both within the strategic-view scope. Checked the Invariants block, Integration Points, and the mapped `track-5.md` sections — clean.
- **Verdict**: VERIFIED

## Findings

<!-- No new findings surfaced by the re-scan. -->

## Evidence base

<!-- Minimal: this review reads no codebase. certs: 0. -->

## Summary

PASS — both prior findings (S1, S2) VERIFIED, no fix-shifted regressions, no new findings.
