# absorption-check params — Step 4b round 1

## Inputs
- target: tracks
- research_log_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
- draft_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/plan
- design_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/design.md

Two-way coverage match between the research log's load-bearing decisions and each
`plan/track-N.md` `## Decision Log` under `draft_path`. In-scope per track means the
decision constrains a file/interface that track touches (its `## Interfaces and
Dependencies`). Note: D13 (track decomposition) is a plan-structure decision realized by
the four-track split itself and recorded as each track's sizing justification, not a
track-implementation DR — do not flag it as log-missing-from-draft. D6-R is one logical
decision recorded in both Track 3 and Track 4; that is intended, not a draft-invents
duplicate.
