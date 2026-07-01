# design-author params — Track 1 authoring, round 1

- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/review-new-files/docs/adr/review-new-files/_workflow/plan
- research_log_path: /home/andrii0lomakin/Projects/ytdb/review-new-files/docs/adr/review-new-files/_workflow/research-log.md
- design_path: (none — design_gate=no, minimal)
- round: 1
- flagged_passages: (none — round 1)

## The file to author

There is exactly **one** track file: `plan/track-1.md`, already created under
`output_path` as a skeleton. **Line 1 is a `<!-- workflow-sha: … -->` stamp
owned by the orchestrator — preserve it byte-for-byte.** Fill the prose
sections below line 1 by editing the placeholders (`< … >`); leave the
`<!-- … -->` template/lifecycle comments and the `## Progress` checklist as-is.
The two blockquotes already in `## Purpose / Big Picture` (`**Complexity
(predicted):**` and `**Scope:**`) are orchestrator metadata — keep them, write
the BLUF and intro paragraph around them.

Sections to author (prose): `## Purpose / Big Picture` (BLUF + intro
paragraph), `## Context and Orientation`, `## Plan of Work`, `## Interfaces and
Dependencies`, `## Decision Log` (full inline four-bullet DRs), and
`## Invariants & Constraints`, plus the track-level `## Validation and
Acceptance` criteria. Leave `## Concrete Steps`, `## Idempotence and Recovery`,
and the continuous-log sections as their Phase-1 placeholders.

## Settled shape (ground from the research log; this is the orchestrator's briefing)

This is a `minimal`, single-track, workflow-modifying fix for YTDB-1179, in
`§1.7(k)` prose-rule opt-out mode (edits are live, no staging). Ground the
prose in the research log's `## Decision Log` (D1/D2/D3), `## Surprises &
Discoveries`, and `## Baseline`, and in the two live workflow files it edits.
This is a prose/bash change — no Java; use grep/Read (PSI not needed), and note
the code you cite by file:line.

The change: on a `§1.7`-staged track the delta-staging prep builds a
`diff <live> <staged>` delta per freshly-staged file only when a live
counterpart exists; a genuinely-new staged `.claude/**` file (no live
counterpart) gets no delta entry, and the reviewer-facing context block then
marks its whole-file add as out-of-scope verbatim copy, so it ships unreviewed.
The same defect is duplicated near-verbatim in **two** files, each across
**four** locations that must move together (this is the crux — see D1):

1. `.claude/workflow/track-code-review.md` — Phase C Startup step 8: preamble
   prose (~271), the bash loop `if [ -f "$live" ]` else-branch (~283-289),
   post-loop narration (~293), and the "Review-target delta for freshly-created
   staged copies" context block (~454-465).
2. `.claude/workflow/step-implementation.md` — Phase B step-level review: the
   byte-identical preamble (~486), loop else-branch (~498-504), post-loop
   narration (~508), and context block (~610-621).

The fix per location: (a) preamble states the no-live-counterpart case emits a
NEW-file marker; (b) the loop gains an `else` branch appending
`=== NEW staged file (no live counterpart): <staged> ===`; (c) post-loop drops
the "only … that has a live counterpart" restriction; (d) the context block is
**rewritten** so delta-scoped (copy-of-live → scope to the delta, rest is
out-of-scope verbatim) and NEW-marker (no live baseline → review the whole-file
add in full) are a per-entry, mutually-exclusive distinction, not an appended
note under the old blanket "out of scope" sentence.

Decision Records to seed (from the log, full four-bullet form):
- **D1** — Fix both files, all four defect locations per file (eight edit
  points); the context block is a rewrite, not an append.
- **D2** — The NEW-file marker emits the staged path (the diff locator).
- **D3** — §1.7(k) prose-rule opt-out; edit live, minimal shape.

Invariants & Constraints (verifiable by review/inspection — a prose change has
no unit test): a staged add with no live counterpart appears under a NEW marker;
the context block distinguishes NEW from delta-scoped; the two files stay
consistent (near-verbatim, modulo temp-path/indentation); ordinary
(non-workflow-modifying) plans are unaffected (the loop still yields an empty
delta file when there are no staged adds).
