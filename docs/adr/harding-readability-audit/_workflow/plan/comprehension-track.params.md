# comprehension-review params — tracks, Step-4b cold-read (outer gate)

## Inputs (forward to prompts/design-review.md)
- target: tracks
- scope: whole-doc
- design_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/design.md
- design_mechanics_path: (none)
- plan_dir: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/plan/
- plan_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/implementation-plan.md

No research_log_path (the comprehension gate reads no log, S1/S2). No
output_path (Step-4b track cold-read returns the verdict inline). The
seed↔track fidelity criterion (full tier) checks track-1.md `## Decision Log`
records stay faithful to the frozen design.md D-records named in design_path.
