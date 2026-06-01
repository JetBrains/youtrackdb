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

## Mutation 3 — 2026-06-01 — content-edit (design.md)

**Diff summary**: Phase 2 plan-review resolution of consistency finding CR5
(user-approved). Two edits recording that Track 2 amends `conventions.md
§1.7(d)` to bring review agents on a workflow-modifying plan into the
staged-first precedence scope, rather than relying on a rule whose current
text scopes precedence to the implementer and excludes reviewers. (1) The
§"Core Concepts" "§1.7(d) reads precedence" entry now notes §1.7(d) as
written excludes reviewers and that this design amends it. (2) A new paragraph
in §"Read-side staging awareness" explains the amendment: §1.7(d)'s "no
consumer has a staged copy" rationale is the YTDB-1038 bug itself, so the work
amends §1.7(d) and drops the stale rationale; the amendment rides in Track 2
alongside the caveat. Line-1 stamp preserved (content-edit).

**Mechanical checks** (target=design): PASS — 0 findings
**Cold-read** (scope: bounded — §"Read-side staging awareness" + §"Core
Concepts" + §"Selection-side staging awareness" + §"Phase A criteria for
workflow-machinery tracks" + Overview): PASS — 0 structural findings, 1
suggestion recorded

**Findings**:
- suggestion: the §1.7(d) amendment is a genuine new decision (scope-expansion
  of an existing convention) folded under D2 rather than carrying its own
  D-code. Cold-read rated traceability intact — D2's Risks/Caveats records the
  amendment plus the rejected "caveat overrides" alternative, and track-2.md
  documents the rationale. Recorded, not applied — left folded under D2.

**Iterations**: 1 of 3 (PASS). Mechanical and bounded cold-read both passed on
the first round; no fixes needed.

## Mutation 4 — 2026-06-01 — structural-rewrite (design.md)

**Diff summary**: Folded D5 (review-target delta-scoping for staged copies —
Track 4, under the YTDB-1038 umbrella) into `design.md` as a second facet of
§"Read-side staging awareness", per the post-Track-1 inline replan. Read-side
gains a TL;DR clause, a two-paragraph mechanism (orchestrator pre-stages a
`diff <live> <staged>` delta in the Phase C diff-staging step and the
high-risk Phase B step-review setup; the canonical reviewer context block
scopes findings to that delta), an edge-case bullet (fires only on a
first-creation new-file add; keys off the staged prefix, so no marker), and D5
in the References footer. Propagated the count/topology changes: the Overview
reading sentence and the "three concerns, one per issue" paragraph (now naming
the read side's two mechanisms); Core Concepts seven→eight with a new
"Staged-copy review delta" entry; Class Design "three additions, three
reach"→"four", a `DELTA` node plus `DELTA -->|"two context blocks"| DIM` edge
in the topology diagram, and the reach sentence; S2 extended (TL;DR +
paragraph) to cover the delta note; Consistency References D-records += D5. The
top-level "three concerns" framing is preserved — D5 folds under YTDB-1038
rather than becoming a fourth section. Line-1 stamp preserved (structural-rewrite).

**Mechanical checks** (target=design): PASS — 0 findings
**Cold-read** (scope: whole-doc): PARTIAL → resolved — 1 should-fix, 1 suggestion

**Findings**:
- should-fix (applied): D5 misattribution in the Overview "closes all three"
  paragraph. An Overview-cap trim had conjoined "pre-stages a review delta" to
  the caveat sentence, making the prompt caveat the grammatical subject when
  every other mention attributes the delta to the orchestrator (Core Concepts,
  Class Design, Read-side, and the Overview's own "orchestrator-staged"
  summary). Fixed by removing the misattributing clause from para 4; para 5
  already introduces the delta with correct attribution, so the Overview no
  longer sets up a mental model the body contradicts.
- suggestion (applied): the topology edge `DELTA --> DIM` overstated the
  delta's reach — the `DIM` node groups three prompts, but the delta reaches
  only the two parallel context blocks (step-implementation 4(a),
  track-code-review), not the `dimensional-review-gate-check.md` gate prompt.
  Relabeled the edge `DELTA -->|"two context blocks"| DIM`. Below should-fix
  (the prose was already exact); applied because the relabel is cheap and
  strictly more accurate.

**Iterations**: 2 of 3 (PASS). Iteration 1 applied the fold-in; mechanical
flagged the Overview at 45 lines, which three sub-trims (push delta detail down
into the Read-side section, tighten the orchestrator sentence, drop the
navigation roadmap line) cleared to 40; the whole-doc cold-read then flagged
the misattribution should-fix and the edge suggestion. Iteration 2 applied both
(clause removal + edge relabel) and re-ran mechanical (PASS, stamp intact on
line 1). The full cold-read was not re-spawned: the should-fix was resolved by
removing the exact clause the reviewer flagged, and the edge relabel is the
reviewer's verbatim suggested fix.
