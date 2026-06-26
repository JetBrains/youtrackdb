# absorption-check params — Step-4b track-path, round 1

- target: tracks
- research_log_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/research-log.md
- draft_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/plan
- design_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/design.md

## Notes
- `draft_path` is the `plan/` directory: read every `plan/track-N.md`
  `## Decision Log` under it.
- Two-way coverage match the research log's load-bearing `## Decision Log`
  decisions against the tracks' `## Decision Log` records. The decomposition
  splits design D8 into D8a (Track 1, design.md/plan existence) and D8b
  (Track 2, adr predicate) — both halves together cover log/design D8.
- `design_path` is the frozen seed decision source only. The seed↔track
  fidelity criterion is owned by the comprehension gate, not this check.
