# comprehension-review params — Step-4b track cold-read (de-warmed gate)

Forward this `## Inputs` block to `prompts/design-review.md`:

- target: tracks
- tier: full
- design_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/design.md
- scope: whole-doc
- mutation_kind: phase1-creation
- plan_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/implementation-plan.md
- plan_dir: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/plan

## Notes
- No `research_log_path` (the absorption cross-check is a separate spawn — you
  run no absorption check and no prose AI-tell axis).
- No `output_path` — return your verdict inline (the bounded comprehension
  verdict plus a summary-shaped `## Structural findings` list).
- `full` tier: run the seed↔track fidelity criterion against the frozen
  `design.md` seed for the track `## Decision Log` records. Note the
  decomposition splits design D8 into Track 1's D8a (design.md / plan existence)
  and Track 2's D8b (adr predicate) — both halves together are faithful to seed
  D8; this is a sanctioned split, not an infidelity.
- Two track files under `plan_dir`: `track-1.md`, `track-2.md`.
