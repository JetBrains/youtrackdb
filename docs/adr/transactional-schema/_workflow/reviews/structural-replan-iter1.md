<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: S1, sev: should-fix, loc: "implementation-plan.md:464-473 (Track 5 intro)", anchor: "### S1 ", cert: "", basis: "Track-5 plan-file intro runs 5 paragraphs / ~15 sentences over the 1-3 cap; three folded findings are not yet in the track file"}
  - {id: S2, sev: suggestion, loc: "implementation-plan.md:256-293 (Invariants)", anchor: "### S2 ", cert: "", basis: "No plan-file invariant for the D21 same-tx schema-contract enforcement; testable assertions live in track-5.md Validation only"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

## Findings

### S1 [should-fix]
**Location**: `implementation-plan.md` Track-5 plan-file checklist entry, the intro prose before `**Scope:**` — lines 464-473 (the original intro paragraph at 464-469 plus four `Also …` paragraphs at 470, 471, 472, 473).
**Issue**: The Track-5 intro paragraph runs five paragraphs and roughly fifteen sentences before `**Scope:**`, over the 1-3 sentence cap (`planning.md` §Per-section budget at a glance, "Track checklist intro | 1-3 sentences"; structural-review TRACK DESCRIPTIONS check, "an intro that runs 4+ sentences or spans multiple paragraphs has expanded into territory that belongs in the track file"). The plan checklist is loaded at every `/execute-tracks` session startup, so each extra intro sentence is paid by every remaining Phase A/B/C session. This is the expected finding the replan preview flagged; it still holds after the D21 fold.

The standard mechanical fix is "trim the intro and move the long-form material to the matching track section" — but a bare trim here would lose content, because three of the four `Also …` paragraphs are **not present in `track-5.md`** at the specificity the plan-file carries them:
- The I-A4 index-engine-cleanliness arm / TB2 (line 470: assert `indexEngines` / `indexEngineNameMap` carry no entry after a failed engine-creating commit and ids are reused) — absent from `track-5.md` entirely (no `I-A4`, no `indexEngines` no-entry assertion, no id-reuse acceptance line).
- The create-time provisional-collection index gap (line 471: `IndexException("Collection with id -2 does not exist")`, `getCollectionNameById` returns null for `< 0`, resolve `<= -2` via `TxSchemaState`) — the specific mechanism is absent from `track-5.md`.
- The drop-side `dropIndex` no-op (line 472: `IndexManagerEmbedded.java:590-600` only calls `markClassChanged`, the index survives commit, plus the "tighten the drop comment" task) — the specific mechanism is absent from `track-5.md`.

The D21 fold itself (line 473) is faithfully mirrored in `track-5.md` (`## Decision Log` D21, `## Context and Orientation`, `## Plan of Work`, and four `## Validation and Acceptance` lines), so the snapshot paragraph trims cleanly; the other three do not.
**Proposed fix**: Replace the Track-5 plan-file intro (lines 464-473) with a compact 1-3 sentence intro plus the `**Scope:**` and `**Depends on:**` lines — for example the original first paragraph (464-469) alone, which already states the track's purpose and the I-A7 completion. Before deleting paragraphs 2-4 (lines 470-472), **move** their content into `track-5.md`: the I-A4/TB2 engine-cleanliness arm into `## Plan of Work` and a `## Validation and Acceptance` line; the create-time provisional-collection gap mechanism into `## Plan of Work` (it already names the commit-time engine build that must re-resolve the provisional id); and the drop-side `dropIndex` no-op mechanism plus the drop-comment-tightening task into `## Plan of Work` (the track file currently covers the drop only generically at "drive engine creation and drops from the changed-index set", line 106). Paragraph 5 (the D21 snapshot, line 473) can be trimmed without a move since `track-5.md` already carries it.
**Classification**: mechanical
**Justification**: TRACK DESCRIPTIONS intro-cap is a mechanical sentence-count rule; the bloat-fix destination rule routes long-form material to the matching track section, and "All BLOAT findings are `mechanical` by construction" (§`mechanical`). The move-not-delete step is part of the same mechanical fix because the rule moves material to the track section rather than dropping it.

### S2 [suggestion]
**Location**: `implementation-plan.md` Architecture Notes → Invariants block, lines 256-293 (specifically the I-P series at 279-281, Track 5's invariants).
**Issue**: D21 adds a substantial new behavioral contract — the tx-aware immutable snapshot enforces a same-tx-created class, property type, or constraint rule on that transaction's own entities (closing the silent `EntityImpl.validate()` constraint-skip). The plan-file Invariants block carries no invariant for it: I-P2 / I-P3 / I-P4 (Track 5) cover only the index overlay, the snapshot rebuild on mid-tx index change, query-usability, and the final-state build — none states the snapshot's tx-aware class/property/constraint view. The new contract's testable assertions do exist where the execution agent reads them (`track-5.md` `## Validation and Acceptance` lines 158-169: the strict-mode/mandatory/notnull/type/regex constraint-enforcement test, the alter-add-constraint test, the commit-path-guard test, and the provisional-collection query test), and the Invariants intro explicitly defers full statements to the track files and research log, so this is a strategic-view lag rather than a missing assertion.
**Proposed fix**: Add one invariant line to the Track-5 group, e.g. "**I-P5** (Track 5) — during a schema or index tx the immutable snapshot reflects tx-local classes, property types, and constraint rules, so `EntityImpl.validate()` enforces a same-tx-created constraint instead of silently skipping it (D21)." Map it to the constraint-enforcement acceptance lines already in `track-5.md`. Optional: add an Integration Point bullet noting that `EntityImpl.validate()` / serialization read the schema contract through the tx-aware `SchemaProxy.makeSnapshot()` (Track 5), since the current Integration Points block lists the planner index-set read but not the validation/serialize snapshot read.
**Classification**: design-decision
**Justification**: "Architecture Notes gaps — missing ... Invariants ... where the scope boundary is ambiguous ... requires the user's rationale" (§`design-decision`). Suggestion severity because the testable assertions are present in the track file's Validation section and the Invariants intro defers full statements there by design.

## Evidence base

This is a structural plan-quality review; it reads no codebase and produces no certificates (`certs: 0`). Verification rested on cross-checking the plan file, the five pending track files (4, 5, 6, 7, 8), `design.md` structure, and `planning.md` §Track descriptions / §Per-section budget.
