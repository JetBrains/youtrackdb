# design-author params — tracks, Step-4b round 3 (re-ground flagged passages only)

## Inputs
- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/plan/
- plan_dir: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/plan/
- research_log_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/design.md
- round: 3

## flagged_passages (re-draft only these; do not re-ground the whole file)

Edit `plan/track-1.md` in place (line 1 stamp untouched). Three readability
findings:

- **F1 (track-1.md:134, in D8's Rejected bullet) — over-dense five-item
  sentence.** "Rejected: it buys early self-termination and a per-finding accept
  trail, but adds a hold concept the reader must track, a user-veto backstop
  that exists only because S4 leaves the prose axis with no second catcher, and
  a verbatim-quote ritual applied to findings that are often cold-spawn variance
  rather than real defects." Split into a short lead plus a 3-item list, one
  cost per line: e.g. "Rejected: the early self-termination and per-finding
  accept trail it buys are outweighed by three costs:" then three bullets — (1)
  a hold concept the reader must track; (2) a user-veto backstop that exists
  only because S4 leaves the prose axis with no second catcher; (3) a
  verbatim-quote ritual applied to findings that are often cold-spawn variance,
  not real defects.
- **F2 (track-1.md:261) — unglossed "maximize-bundle sizing".** Gloss the
  workflow-internal term in place at first use: e.g. "(maximize-bundle sizing:
  pack every unit that fits into the fewest tracks rather than splitting, so the
  change pays one review cycle, not several)".
- **F3 (track-1.md:9, and the milder use at :94) — metaphor "re-rolls ...
  through a fresh reader".** Replace with the literal verb: "re-runs
  already-settled prose past a fresh reader each round" (and at :94, the "re-rolls
  N track files" use — "re-runs N track files past a fresh reader each round").
