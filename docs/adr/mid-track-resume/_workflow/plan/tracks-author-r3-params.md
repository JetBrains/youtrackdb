# design-author params — create-plan Step 4b (track authoring), round 3

- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/plan/
- research_log_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/design.md
- round: 3

## Round 3: fix only the two flagged passages below

Both track files are authored and nearly clean. Two small readability snags remain.
Fix exactly these (no re-grounding needed — they are wording fixes). Touch nothing
else: not line 1, line 2, `## Progress`, the continuous-log sections, or any
Decision Log record content.

### flagged_passages

**track-1.md ~line 190 (hard-to-read, § Plain language).** The four-element hyphen
compound "pre-this-change-ledger signal" parses two ways. Phrase:
"The empty case is the unambiguous pre-this-change-ledger signal (D3, owned by
Track 2)." Reword so the reader does not re-segment, e.g. "the empty case is the
unambiguous signal of a ledger written before this change (D3, owned by Track 2)".

**track-2.md ~lines 196-197 (hard-to-read, § Plain language).** Ambiguous pronoun
"it" whose nearest noun (`review-done-track-open`) is misleading. Phrase:
"The track-completion append (boundary 4) carries it past review." Name the actor:
"The track-completion append (boundary 4) carries the single-step track past review."

## Reminders

- House style applies. Return only a thin summary — never the drafted content.
- Locate each passage by content; line numbers shift as you edit.
