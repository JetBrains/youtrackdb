# readability-auditor params — create-plan Step 4b (track path), round 2, track file 1/1

- target: tracks
- target_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/plan/track-1.md
- range: 1-201

## Notes for this spawn

- Track-path per-file fan-out: one spawn per track file, whole-file range.
  `slice_count` and `total_lines` are intentionally absent (the whole-doc
  guard stays inert on the track path).
- Standing anchors (`target=tracks`): each track's `## Purpose / Big Picture`.
  There is no plan file (single-track change), so no plan Component Map
  anchor exists — fold in only the anchors present.
- The prose sections (`## Purpose / Big Picture`, `## Context and
  Orientation`, `## Plan of Work`) are the audit surface for prose findings;
  the structured sections (`## Decision Log` records, `## Invariants &
  Constraints`, `## Interfaces and Dependencies`) are registry-terse by
  design — audit them for clarity, not for narrative shape.
- The file is authored under the CURRENT live house-style rules (the rules
  this change stages are not live yet); audit against the live
  `house-style.md`.
