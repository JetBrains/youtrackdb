# design-author params — phase1-creation round 2 (re-ground flagged passages only)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/research-log.md
- round: 2

## flagged_passages (readability-auditor round-1 findings — fix these in place; do NOT re-ground the whole document, and do NOT touch the Decision records / Invariants substance — only the prose)

1. design.md:23-26 — **over-dense**: a five-link causal chain (wrap → tail on continuation line → column-0 line lacks `risk:` → entry skipped → `ROSTER_STEP_COUNT` stays 0 → falls through to `steps-partial`) is folded into one comma-chained sentence. Linearize it: one link per sentence, or render as a short numbered list / fenced trace.
2. design.md:37 — **unglossed entity**: `determine_state` (the "non-ledger walk") is named in load-bearing Overview prose but never glossed. Add a one-clause gloss at first use (what it is, why a non-ledger walk exists distinct from `determine_state_from_ledger`), or add a Core Concepts entry.
3. design.md:16-19 — **hard-to-read**: one sentence carries three ideas (bash/markdown-not-Java framing + the "Class design" analogue mapping + the "Workflow" analogue mapping). Split into two or three sentences, one idea each.
4. design.md:100-101 — **padding/restatement**: the sentence restates the section's own TL;DR (lines ~93-96) almost verbatim. Drop the restated lead sentence; keep only the genuinely-new negative-scope claim ("Nothing about the phase enum / append atomicity / existing keys changes").
5. design.md:246-247 — **idiom**: "out of step with the track file" → use the literal "inconsistent with" (or "out of sync with").
6. design.md:346-349 — **hard-to-read**: one sentence carries four ideas (the decision + the rejected alternative + the quoted contrast pair it would conflate + the consequence). Split into two or three sentences.
7. design.md:535 — **metaphor**: "bigger blast radius" → state the literal scope ("touches more sites" / "a wider change", naming what grows — the phase enum and its consumers).
8. design.md:445-446 — **idiom (lower confidence)**: "for free" → "at no additional cost", or restate concretely (the wrap-fixed fallback already covers the criterion).
