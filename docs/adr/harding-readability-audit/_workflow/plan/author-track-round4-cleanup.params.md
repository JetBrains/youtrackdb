# design-author params — tracks, Step-4b round 4 (S5 budget-exhaustion cleanup)

The 3-round inner-loop budget is spent and the readability auditor is
oscillating (8 → 3 → 5 should-fix findings on prose of stable quality, the
live-loop variance this branch fixes but cannot dogfood). This is the S5
user-is-the-gate cleanup pass: apply the cheap unambiguous fixes below, then
the loop exits to the outer comprehension gate.

## Inputs
- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/plan/
- plan_dir: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/plan/
- research_log_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/design.md
- round: 4

## flagged_passages (cheap fixes — re-draft only these, in place; stamp untouched)

- **F2 (track-1.md:134, D8 Alternatives) — dangling reference.** Remove
  "(D4 as originally drafted)": it points at a prior draft of D4 the reader
  cannot see (current D4 is section-keyed settled-state). Let the inline
  description of the calibrated-hold alternative stand on its own.
- **F1 (track-1.md:39, D1 Risks/Caveats) — metaphor + long sentence.** Replace
  "two enforcers fighting over one job" with the literal phrase already used at
  line 58 ("a deliberate redundant double-check, not a redundancy bug"); split
  the gap-closing claim and the independence claim into two sentences.
- **F4 (track-1.md:108, D6) — vague phrasing.** Replace "leaves the mess and
  splits the glob" with the literal outcome: "leaves the author and absorption
  params files in `plan/` and forces the resume glob to read two directories."
- **F3 (track-1.md:212, Plan of Work warm-up) — unglossed prompt-cache model.**
  Add a one-clause gloss of why the delay warms the cache, e.g. "the first
  spawn populates a shared prompt-cache prefix the later spawns reuse, so
  spacing them out makes all but the first cheap" — so "pay N cold prefixes"
  is reconstructable.
- **F5 (track-1.md:9, Purpose) — compound modifier.** Simplify "spread
  per-passage attention thin enough to miss real findings" to a plainer
  construction, e.g. "reading each passage too shallowly to catch real
  findings."
