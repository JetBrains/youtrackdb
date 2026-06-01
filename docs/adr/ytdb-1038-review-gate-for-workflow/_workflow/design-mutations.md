# Design Mutations — ytdb-1038-review-gate-for-workflow

Append-only log of every `design.md` mutation. See
`.claude/workflow/design-document-rules.md § Mutation discipline § Review log`.

## Mutation 1 — 2026-06-01 — phase1-creation (design.md)

**Diff summary**: Seeded the initial `design.md` for the staging-aware
review-machinery plan. Seven sections: Overview, Core Concepts (six
concepts), Class Design (component-topology flowchart), Workflow (selection
flowchart + read sequence diagram), and one mechanism section each for the
selection-side fix (YTDB-1032), the read-side fix (YTDB-1038), and the
consistency invariants plus self-application carve-out. Single file, no
`design-mechanics.md` companion. Stamped at creation with workflow-sha
`f97512c02f4dbaaf66c7382397907580fd54391b`.

**Mechanical checks** (target=design): PASS
**Cold-read** (scope: whole-doc): PASS — 1 should-fix applied, 1 suggestion recorded

**Findings**:
- should-fix: four inline `(D2)`/`(D3)`/`(S1)`/`(S2)` parenthetical asides
  in the Read-side and Consistency sections were redundant with the grouped
  `### References` footers. Removed in iteration 2; the D/S codes survive in
  the footers.
- suggestion: add one Overview prerequisite sentence naming the `§1.7`
  staging convention as assumed reader knowledge. Recorded, not applied —
  the cold-read rated it below should-fix (the domain framing already
  implies a workflow-machinery maintainer audience), and the Overview is
  near its 40-line budget.

**Iterations**: 2 of 3 (PASS). Iteration 2 applied the cold-read's own
prescribed deletions, then re-ran mechanical checks (PASS, stamp intact on
line 1); the full cold-read was not re-spawned because the only change was
the reviewer's verbatim suggested fix.

## Mutation 2 — 2026-06-01 — structural-rewrite (design.md)

**Diff summary**: Expanded the design from two fixes to three after the user
folded the Phase A review gaps into scope (new issue YTDB-1046 filed).
Reframed the Overview to "three ways it goes stale" across the three review
layers (Phase 2 plan review, Phase A track review, Phase B/C dimensional
review). Added a seventh Core Concept (Phase A track review). Expanded the
Class Design topology diagram with the Phase A prompt cluster and the
criteria-addendum node. Widened the Read-side section's prompt set from five
to nine prompts (added the four Phase A prompts). Added a new section
"Phase A criteria for workflow-machinery tracks" (D4, S3) for the YTDB-1046
prose-criteria addendum. Extended the Consistency section with S3
(caveat/addendum uniformity) and a Phase A mention in the self-application
paragraph.

**Mechanical checks** (target=design): PASS
**Cold-read** (scope: whole-doc): PASS — 0 findings

**Findings**:
- should-fix: Overview exceeded the 40-line cap (41 lines) after the
  rewrite. Trimmed the roadmap paragraph to 39 lines in iteration 2.

**Iterations**: 2 of 3 (PASS). Iteration 1 applied the rewrite; mechanical
flagged Overview length; iteration 2 trimmed the roadmap, re-ran mechanical
(PASS, stamp intact on line 1), and the whole-doc cold-read returned PASS
with no findings.
