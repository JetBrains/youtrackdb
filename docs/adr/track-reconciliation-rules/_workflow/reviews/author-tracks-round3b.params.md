# design-author params — Step-4b track authoring, round 3 cleanup (target=tracks)

- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/plan/
- research_log_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/design.md
- round: 3 (cleanup — two trivial fixes, one a regression from the prior round)

## flagged_passages (2 prose fixes in plan/track-1.md — prose only, no re-grounding)

1. **≈line 170-172 (D4.1 / `medium` moot note)** — hard-to-read: "The
   `medium` should-fix loop is already bounded by its cap-3, so no-progress
   detection is moot there until a surviving blocker carries the loop past
   three, at which point the blocker loop's no-progress gate takes over."
   folds three facts. Split into two sentences: (a) the `medium` should-fix
   loop is capped at three, so no-progress detection does not gate it; (b) once
   a surviving blocker carries iterations past three, the blocker loop's
   no-progress gate governs from there.
2. **≈line 186-190 (slow-vs-stuck contrast — a regression from the prior
   round's fix)** — hard-to-read subjectless fragment: "A `high` track that
   makes real but slow progress hits the context pause, hands off, and
   continues next session — real progress each iteration, so it never
   escalates." The trailing "— real progress each iteration, so it never
   escalates" is subjectless. Make it a full clause: "...continues next
   session. Because it makes real progress each iteration, it never escalates."

Preserve everything else byte-for-byte (line-1 stamp, all decision content,
sections, placeholders).
