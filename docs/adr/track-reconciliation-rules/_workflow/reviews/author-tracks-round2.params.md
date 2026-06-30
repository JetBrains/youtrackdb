# design-author params — Step-4b track authoring, round 2 (target=tracks)

- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/plan/
- research_log_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/design.md
- round: 2

## flagged_passages (one prose fix — no re-grounding)

1. **`plan/track-1.md` ≈line 97-98 (`## Decision Log`, D3 `low` bullet)** —
   hard-to-read garden path: "the blocker loop `low` already runs today
   continues until no blockers remain" drops the relative pronoun, so the
   reader parses "the blocker loop `low`" as the subject before "continues"
   forces a re-read. Insert the pronoun ("the blocker loop **that** `low`
   already runs today continues until no blockers remain") or restructure
   ("`low` keeps the blocker loop it runs today, now uncapped — it continues
   until no blockers remain").

Preserve everything else byte-for-byte (line-1 stamp, all other Decision
Records, sections, placeholders).
