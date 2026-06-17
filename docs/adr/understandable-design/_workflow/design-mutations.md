# Design mutations — understandable-design

## Mutation 1 — 2026-06-17 — phase1-creation (design.md)

**Diff summary**: Seeded `design.md` for the two-role authoring loop (YTDB-1130) as a
single file (no mechanics companion; 709 lines, under the length trigger). Structure:
Overview, Core Concepts (8 glossed concepts), Class Design (role/orchestrator
classDiagram), Workflow (sequenceDiagram), and four Parts — the two-role loop (author,
auditor, dual-clean inner loop); the de-warmed reviews (comprehension/structural plus
the phase4 fidelity check); wiring (edit-design rework, create-plan Step 4b, the 4a/4b
boundary collapse); and invariants/staging/cost (S2/S3 read-scope, staging + dogfood,
fan-out cost levers). 15 `##` sections; D1–D19 and S1–S7 seeded, each D-record
introduced once in an owning section's footer.

**Mechanical checks** (target=design): PASS (0 findings on the final draft).
**Cold-read** (scope: whole-doc): PASS. Iter 1 — PASS with 4 should-fix; iter 2 — PASS,
0 findings, all four fixes verified, absorption-completeness clean both ways.

**Findings**:
- iter1 should-fix: "episodes" glossed late → fixed (glossed at first load-bearing use in Core Concepts Fidelity entry).
- iter1 should-fix: 3 inline `(S1)`/`(S3)` parenthetical asides in prose → removed (footers carry the codes).
- iter1 should-fix: 3 paragraphs over the 1-em-dash cap → reduced to ≤1 each.
- iter1 should-fix: 15:12Z PR-description-scope log decision had no carrier home → added a Staging non-goal bullet.
- iter2: PASS, no findings. Two suggestion-level pre-existing items (the `### Decisions & invariants` footer heading vs the literal `### References`; "PSI" bare at Overview first use) recorded, not retried.

**Iterations**: 2 of 3 (PASS)
