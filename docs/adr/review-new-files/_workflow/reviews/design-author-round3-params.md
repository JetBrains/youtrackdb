# design-author params — Track 1 authoring, round 3 (flagged-passage re-draft)

- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/review-new-files/docs/adr/review-new-files/_workflow/plan
- research_log_path: /home/andrii0lomakin/Projects/ytdb/review-new-files/docs/adr/review-new-files/_workflow/research-log.md
- design_path: (none — design_gate=no, minimal)
- round: 3
- flagged_passages: (the two below)

Re-draft only the two flagged passages in `plan/track-1.md`; leave every other
section, the line-1 stamp, the orchestrator blockquotes, and all placeholders
untouched. Prose-shape fixes — no new code grounding needed. No decision
content changes.

## Flagged passage 1 — `## Purpose / Big Picture` orientation paragraph (~line 12)
Axis: over-dense. The sentence "The reviewer-facing context block then treats
the rest of every whole-file add as verbatim-copied, already-reviewed, out of
scope — which, with no delta entry to override it, marks the whole new file out
of scope." slides from "the rest of every whole-file add" to "the whole new
file" without stating why they coincide, and repeats "out of scope" around an
em-dash clause, forcing a reread.
Fix: split into two sentences and state the equivalence — the context block
marks everything past a delta boundary out of scope; a NEW file has no delta
entry, so there is no reviewed portion and the whole file is marked out of
scope.

## Flagged passage 2 — `## Interfaces and Dependencies` (or wherever it appears), ~line 89
Axis: plain language. "The `step-implementation.md` copy is identical modulo
the temp-file path (`step-{N}-{M}-delta`) and its two-extra-levels of
indentation."
Fix: replace "identical modulo" with "identical except for", and simplify "its
two-extra-levels of indentation" to "two extra levels of indentation".
