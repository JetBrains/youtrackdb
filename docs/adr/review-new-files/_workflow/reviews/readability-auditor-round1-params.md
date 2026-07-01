# readability-auditor params — Track 1, round 1

- target: tracks
- target_path: /home/andrii0lomakin/Projects/ytdb/review-new-files/docs/adr/review-new-files/_workflow/plan/track-1.md
- range: 1-147

<!-- slice_count and total_lines intentionally omitted: this is the track-path
     per-file fan-out (one spawn per track file, whole-file range), so the
     whole-doc guard does not apply. -->

## Standing anchors
There is no `implementation-plan.md` and no plan Component Map (this is a
`minimal`, single-track change). The only standing anchor is this track's own
`## Purpose / Big Picture`, which is inside your whole-file range. Audit the
whole file (lines 1-147) cold.
