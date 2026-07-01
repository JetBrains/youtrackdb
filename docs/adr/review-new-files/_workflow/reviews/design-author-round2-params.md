# design-author params — Track 1 authoring, round 2 (flagged-passage re-draft)

- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/review-new-files/docs/adr/review-new-files/_workflow/plan
- research_log_path: /home/andrii0lomakin/Projects/ytdb/review-new-files/docs/adr/review-new-files/_workflow/research-log.md
- design_path: (none — design_gate=no, minimal)
- round: 2
- flagged_passages: (the three below)

Re-draft only the flagged passages in `plan/track-1.md`; leave every other
section, the line-1 stamp, the orchestrator blockquotes, and all placeholders
untouched. These are prose-shape fixes — no new code grounding is required
(none demands a worked example). Preserve the decision content of D1 exactly
(the decision does not change; only its prose readability does).

## Flagged passage 1 — `## Purpose / Big Picture`, the orientation paragraph (~line 12)
Axis: over-dense. The paragraph folds the whole causal chain
(guard-skips-the-new-file → no-delta-entry → context-block-marks-it-out-of-scope
→ ships-unreviewed) into two long sentences, and names "staged copy", "delta
file", and the `if [ -f "$live" ]` guard load-bearingly before they are glossed
in `## Context and Orientation`.
Fix: lead with the plain claim already present ("a brand-new file ships
unreviewed while the machinery reports a clean pass"), then break the
guard → no-delta → out-of-scope → unreviewed chain into one link per sentence.
Add a one-clause forward pointer to the `## Context and Orientation` gloss so
the first use of "staged copy"/"delta file" is not naked.

## Flagged passage 2 — `## Decision Log` D1 Why (~line 33)
Axis: hard-to-read (sentence shape). The sentence "Editing a subset leaves a
contradiction a consistency review flags: a loop that now emits a NEW marker
under a preamble that still says it only writes a delta when the live file
exists, or a post-loop line that still restricts the trigger to adds 'that has
a live counterpart'." stacks a colon-introduced two-branch enumeration with
nested subordinate clauses; the subject-verb spine is lost before the branches
resolve.
Fix: a lead sentence naming the contradiction, then the two failure examples as
a short period-separated pair — one idea per sentence.

## Flagged passage 3 — `## Decision Log` D1 Why (~line 33)
Axis: hard-to-read (idiom). "the exact bug, one indirection later" names no
concrete referent; the reader cannot pin down which indirection.
Fix: replace with the literal mechanism, e.g. "which reintroduces the same
unreviewed-file bug, this time through the non-empty delta file rather than the
missing guard branch."
