# design-author params — phase1-creation, round 3

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/research-log.md
- round: 3

## flagged_passages (readability findings to fix — prose only, no re-grounding needed)

All seven are prose-tightening fixes. Preserve the line-1 stamp, the flowchart,
all decision content, the TL;DRs, edge cases, and References footers. Do not
re-ground the code (these need no new code reads).

1. **≈line 50-51 (`## Core concepts`)** — over-dense restatement: "This design
   rests on four ideas, each defined here once and used without re-definition
   below." restates the TL;DR three lines above. Drop the sentence (the four
   bold sub-headings are self-evidently the four ideas).
2. **≈line 93-97 (`## The new Phase-C review loop`)** — over-dense: the
   paragraph after the TL;DR restates it near-verbatim. Delete the duplicated
   paragraph; if a diagram lead-in is wanted, keep one short sentence not
   already in the TL;DR.
3. **≈line 8 (`## Overview`)** — too-terse forward reference: "removes that
   fixed cap on the paths the user wants uncapped" — the reader cannot yet
   reconstruct which paths. Name the concrete referent, e.g. "removes the
   fixed cap where the complexity tag calls for it (the blocker loop at every
   level, and the should-fix loop on `high`)".
4. **≈line 184-185 (`## No-progress detection`)** — hard-to-read: the em-dash
   fragment "are findings shrinking" is a bare question wedged mid-sentence.
   Rewrite, e.g. "bounds the uncapped loops by the real convergence signal —
   whether findings are shrinking — not by a fixed iteration count."
5. **≈line 204-206 (`## No-progress detection` edge cases)** — hard-to-read:
   "The bounded `medium` should-fix loop is already bounded by its cap-3"
   doubles "bounded" and folds conditions. Drop the redundant word and split
   into two sentences.
6. **≈line 316-318 (`## Scope ...`)** — hard-to-read: subject split across a
   long em-dash aside before the verb, and "ships self-contradictory" drops
   its complement. Move the grep-authority aside to its own sentence and
   complete the complement ("self-contradictory text").
7. **≈line 342-345 (`### The full restate set`)** — hard-to-read: same dropped
   complement after "ships", and the grep regex wedged inline as a sentence
   object. Complete the complement and lift the grep command onto its own
   fenced/code line with a lead-in.

Also: lines 316-318 and 342-345 carry overlapping "every cap-3-keyed
mechanic ... grep is the authority ... ships self-contradictory" framing
across the section intro and the subsection — reconcile so the claim is
stated once, not twice. While you are touching the TL;DR sections, confirm no
other section's TL;DR is followed by a paragraph that merely restates it
(the round-2 mechanical fix added TL;DRs; dedup any that doubled the lead).
