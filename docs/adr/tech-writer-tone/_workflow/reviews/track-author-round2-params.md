# design-author params — create-plan Step 4b (track authoring), round 2

- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/plan
- research_log_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/design.md
- round: 2
- flagged_passages: see below

## Flagged passages (round-1 readability findings; re-ground and re-draft only these)

All in `plan/track-1.md`. Do not touch line 1 (the stamp), the section
headings/order, the `## Decision Log` records, or any section not named here.
The absorption check returned clean (10/10), so no decision content changes —
these are prose-only fixes.

- **F1 (line ~109, `## Context and Orientation`):** "a range-sliced auditor
  and a warm coverage-matcher run in parallel … a de-warmed comprehension
  gate…" — the warm/cold/de-warmed distinction is never defined. Gloss it
  once at first use (warm = it reads the research log and prior context;
  cold/de-warmed = that context withheld so it judges the finished document
  alone), then use the terms freely.
- **F2 (line 5, `## Purpose / Big Picture` BLUF):** five deliverable clauses
  comma-chained after a colon, and the same list re-enumerated at lines ~9
  and ~111 — the reader parses the identical list three times. Keep the BLUF
  lead to the single top-line change (readability-first style regime staged
  for Phase-4 promotion) with at most a compact enumeration ONCE; do not
  restate the full list in the intro paragraph's closing sentence.
- **F3 (line ~111, `## Plan of Work` opening):** an eight-item comma-chain
  re-enumerating the deliverables a third time. Replace with a cross-reference
  ("the ~19 files listed under `## Interfaces and Dependencies`") or a short
  bulleted list — whichever reads better; do not keep the third enumeration.
- **F4 (lines 5, 9):** "internals-book" used as a proper reference with no
  gloss. Add a one-clause gloss at first use (the YouTrackDB internals book —
  the reader-praised teaching book whose voice rules this change borrows) or
  keep only the descriptive form.

Return a thin summary only (passages re-drafted, nothing else touched).
