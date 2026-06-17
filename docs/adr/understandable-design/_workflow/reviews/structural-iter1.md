<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

# Structural review — iteration 1 (Phase 2, reviewer-plan)

Plan: `docs/adr/understandable-design/_workflow/implementation-plan.md`
Tracks: `plan/track-1.md`, `plan/track-2.md` (both `[ ]` pending)
Design: `docs/adr/understandable-design/_workflow/design.md` (present — `full` tier)
Tier: `full` (ledger `tier=full`, `s17=workflow-modifying`). Structural pass and the DESIGN DOCUMENT block both run.

PASS — no structural findings. The plan, the two track files, and the frozen design are internally coherent and executable. Every criterion category was checked and cleared; details below.

## Findings

(none)

## Evidence base

Structural review produces no certificates (`certs: 0`). This section records the per-category check results so a re-run can confirm coverage.

**SCOPE INDICATORS** — both tracks carry a `**Scope:**` line with an `~N files` footprint and a coverage list (T1 `~9 files`, T2 `~5 files`). Both coverage-list cardinalities are plausible against the footprint count. No `- [ ] Step:` items and no `*(provisional)*` markers in the plan. PASS.

**ORDERING & DEPENDENCIES** — T1 precedes T2 in the checklist; T1 is the dependency root (no `**Depends on:**`); T2 carries `**Depends on:** Track 1`. The graph is acyclic; T2's reuse of T1 roles is captured by the annotation; no earlier track depends on a later one. PASS.

**TRACK DESCRIPTIONS** — each pending track has full `## Purpose / Big Picture`, `## Context and Orientation`, `## Plan of Work`, and `## Interfaces and Dependencies` sections, substantive enough for just-in-time decomposition. Each has a track-level Mermaid flowchart (T1: 5 nodes; T2: 6) where 3+ components interact. Both plan-file intro paragraphs are 3 sentences (within the 1–3 bound; the third sentence is the `Detailed description in plan/track-N.md` pointer). PASS.

**TRACK SIZING** — both tracks are intentionally under the ~12-file floor and each carries a written `Track sizing justification` paragraph in its `## Interfaces and Dependencies`. The justifications are coherent: the cut is the preferred dependency-boundary cut (T2 depends hard on T1's roles, loop, and by-reference contract), and T2 maximize-bundles its three autonomous downstream units. A documented out-of-bounds/under-filled track passes the rule. T1 and T2 are consecutive, so the non-consecutive-overlap clause does not apply; the only shared paths (`.claude/agents/` distinct new files, `conventions.md` distinct conditional sections) are co-located in adjacent tracks. T2's internal phase-sequencing (concern 3 gated on concern 1 + T1's by-reference) is explicitly justified by the maximize rule. PASS.

**ARCHITECTURE NOTES** — the plan carries the thin cross-track `## Component Map` (Mermaid + annotated bullets), covering only touched components plus their orchestrators, each annotated with what changes. Each pending track carries a `## Decision Log` (T1: 17 DRs; T2: 3 DRs), each DR with alternatives / rationale / risks / `**Implemented in**` / `**Full design**`. Invariants live in each track's combined `## Invariants & Constraints` with `verified by …` testable assertions; integration points live in each `## Interfaces and Dependencies`. PASS.

**DESIGN DOCUMENT** (`full` — runs) — `design.md` exists; has an `## Overview`; a `classDiagram` (8 classes ≤ ~12) for the 5 new agent roles + 3 orchestrators with following prose; a `sequenceDiagram` (5 participants ≤ ~8) for the authoring loop with prose; all diagrams Mermaid, no external/image references (D12 holds the line on Mermaid); every diagram paired with explanatory prose; dedicated sections for the non-obvious parts present in the plan (read-scope invariants, the 4a/4b collapse and its crash-recovery re-spec, cost levers); no concurrency/locking section needed (the design has no locking). PASS. (Design-vs-plan/track consistency: see the D18 note below.)

**DECISION TRACEABILITY** — every track DR names `**Implemented in**`; every non-obvious choice (D10 PSI residual, D15 by-reference hard requirement, D18 S2 extension, etc.) has a corresponding track DR. PASS.

**CONSISTENCY** — track descriptions, the Decision Logs, the plan Component Map, and the scope indicators tell one story. No contradiction between T1 and T2 found (5 total roles = T1's 4 + de-warm plus T2's 1 fidelity check; author allow-list, absorption `model: sonnet`, and by-reference contract are stated consistently across all three documents). PASS.

**BLOAT** — mechanical line-count checks all clear: longest track DR is ~6 lines (≤ ~30); every invariant entry is a single bullet (≤ ~5); every integration-point bullet is ≤ 3 lines; Component-Map intent bullets are ≤ 5 lines; no DR is marked `(SUPERSEDED …)`; the plan file is 80 lines (≤ ~1,500 / ~30K tokens). PASS.

**D-record and S-invariant homing** — seed D1–D19 (all 19 in `design.md`) are homed across the two tracks' Decision Logs: T1 holds D1–D9, D11(edit-design facet), D12, D13, D14, D16, D17, D18, D19; T2 holds D10, D11(create-plan facet), D15. D11's dual-faceting is by design (one logical decision, two authoring points), not a duplicate. S1–S7 are homed across the tracks' `## Invariants & Constraints` (T1: S1–S5, S7; T2: S3, S4, S6, S7).

**Seed↔track fidelity (`full`-tier)** — applied to all seed D-records except D18. All checked records (D1–D17, D19) are substantively equivalent between the `design.md` seed and the matching track DR.

**Expected, non-finding divergence on D18 (Phase-4-deferred, NOT raised).** The frozen `design.md` D18 seed and the §"The S2 and S3 read-scope invariants" / Overview text still attribute the canonical S2 read-scope statement and the wording-update deliverable to `conventions.md`. Track 1's D18 (and S2 in `## Invariants & Constraints`, the plan Component Map, and the track's `## Context and Orientation`) now attribute the canonical S2 statement to `research.md` §"Read-scope discipline (S2)" with a restatement in `design-document-rules.md`, and leave the `conventions.md` descriptive cross-refs alone. This was corrected by the Phase 2 consistency review (finding CR3, resolved with the user); the design-side correction is deferred to the Phase 4 `design-final.md` reconciliation under the "design.md is frozen — Phase 2 does not mutate it" rule. Restoring the track DR to seed-equivalence would re-introduce the misattribution CR3 fixed, so per the consistency prompt's treatment of a revised DR diverging from the frozen design, this divergence is expected and is recorded here as already-resolved / Phase-4-deferred, not as an open structural finding.
