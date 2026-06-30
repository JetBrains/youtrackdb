# design-author params — Step-4b track authoring, round 3 (target=tracks)

- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/plan/
- research_log_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/design.md
- round: 3

## flagged_passages (5 readability fixes in plan/track-1.md — prose only)

Preserve the line-1 stamp, all Decision content (the substance of D1–D4 and
their sub-decisions is correct — only the prose shape changes), and all
placeholders. No re-grounding.

1. **≈line 5-10 (`## Purpose / Big Picture` BLUF)** — over-dense: one sentence
   folds three policy facts (blocker-loop rule, should-fix-depth rule, safety-
   valve replacement). Lead with the one-line plain claim ("Phase-C
   termination is now keyed to the per-track complexity tag, not a fixed
   cap"), then break the three rules onto separate lines or a short list.
2. **≈line 122-131 (`## Decision Log`, D3.1)** — hard-to-read: the sentence
   "A should-fix finding that re-surfaces in a post-3 blocker-driven
   iteration is fixed opportunistically when the implementer is already
   touching that code, otherwise surfaced at track completion" stacks two
   conditions + a fallback. Split into two sentences (when it's fixed
   opportunistically; the otherwise-fallback).
3. **≈line 172-175 (no-progress escalation)** — over-dense run-on: one
   sentence carries trigger + action + what's surfaced + the same-shape-as-cap-3
   contrast. Split: one sentence for trigger/action/surfaced; a second for
   "same escalation shape as cap-3 exhaustion, fired on the no-progress signal
   instead of a fixed count."
4. **≈line 177-188 (slow-vs-stuck contrast)** — hard-to-read: spell the
   compound "slow-but-real-progress `high` track" as a short clause ("a
   `high` track that makes real but slow progress"), and replace the "⇒"
   glyph with a plain word ("so it never escalates").
5. **≈line 146-148 (no-new-machinery)** — hard-to-read: "grows new
   measurement machinery" is figurative and "so it adds none" elides its
   object. Use a literal verb and name the object: "...or the change would
   require new measurement machinery. It reads the gate-check verdict stream
   the loop already emits, so it adds no new machinery."
