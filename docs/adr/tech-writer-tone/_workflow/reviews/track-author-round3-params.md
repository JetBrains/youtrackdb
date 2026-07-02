# design-author params — create-plan Step 4b (track authoring), round 3

- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/plan
- research_log_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/design.md
- round: 3
- flagged_passages: see below

## Flagged passages (round-2 readability findings; re-draft only these)

Both in `plan/track-1.md`. Prose-only; do not touch line 1, headings/order,
or the `## Decision Log`.

- **F1 (line 5, `## Purpose / Big Picture`):** the five-move enumeration is
  one ~90-word comma-chained sentence with a nested parenthetical. Break it:
  keep "The regime bundles five moves:" and list the five moves as short
  bullets under it (one line each). Move the internals-book parenthetical
  gloss onto the voice bullet or into the line-9 paragraph — whichever reads
  cleaner, one gloss only.
- **F2 (line ~109, `## Context and Orientation`):** the gloss defines "cold"
  and "de-warmed" as one thing, but the text then applies them as distinct
  labels (auditor = cold, comprehension gate = de-warmed). Add the one-clause
  distinction: "cold" = an agent that never carried the authoring context;
  "de-warmed" = one that would normally run warm but has that context
  deliberately withheld.

Return a thin summary only.
