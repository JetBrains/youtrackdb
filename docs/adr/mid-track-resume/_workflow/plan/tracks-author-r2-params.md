# design-author params — create-plan Step 4b (track authoring), round 2

- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/plan/
- research_log_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/design.md
- round: 2

## Round 2: re-draft only the flagged passages below

Both track files are already authored (round 1). The readability auditors flagged
the passages below. Re-ground only these passages (per your grounding-cost
discipline — do not re-ground or rewrite the whole document), fix each, and return
a thin summary. Do not touch line 1 (the stamp), line 2 (the title), `## Progress`,
the continuous-log sections, or any Decision Log record (absorption passed — the
decisions are correct, leave them).

### flagged_passages

**track-1.md:198-201 (over-dense, § Mechanism traces).** The roster_scan
continuation-join mechanism is one run-on sentence chaining four steps with a
wedged pattern literal. Break it into a short numbered list, one step per line:
(1) buffer a column-0 `N. ` line that carries no `risk:`; (2) append following
non-column-0 continuation lines; (3) stop at the next column-0 step line, the next
`## ` heading, or EOF; (4) read the `risk:` tail and status checkbox from the joined
text. Pull the terminator pattern onto its own line or into a fenced/inline-code
span. NOTE: `[0-9]*". "` is **correct bash** (`[0-9]*` then the quoted literal
`". "` = a digit, then anything, then `. `) — keep the pattern exactly; the fix is
to stop wedging it mid-sentence, not to change it.

**track-2.md (glossary-introduction, § Glossary-introduction) — phase-enum no-B
gloss.** The whole cadence turns on a quirk the cold reader is never told: the
top-level phase enum is `{0, A, C, D}` with no `B`, so a track executing Phase B is
recorded under `phase=C`, and the Phase-A→Phase-C transition is written "A→C" with
no B in the name. Add a one-clause gloss at first load-bearing use in
`## Context and Orientation` (near the "Phase A, Phase B, and Phase C complete"
sentence, ~line 100). This also resolves the apparent contradiction between that
sentence and the "A→C" boundary naming / "every `phase=C` track" framing.

**track-2.md (glossary-introduction) — "roster" not glossed at first use.**
"roster" (the `## Concrete Steps` numbered step list) is load-bearing from the
Purpose section but first connected to a meaning only much later. Gloss it at first
use in Purpose or Context — e.g. "the roster (the `## Concrete Steps` numbered step
list)". One clause for "wrap-fixed" too (the roster parser miscounts a step whose
description wraps onto continuation lines).

**track-2.md:213-216 (over-dense, § Mechanism traces) — 4-way mapping restated as
prose.** The four boundary→slug mappings are comma-chained into one sentence while
the same four already appear as a table earlier in `## Plan of Work`. Either break
them onto separate sub-bullets or replace the prose enumeration with a back-reference
to the append-cadence table.

**track-2.md:47 and :162-163 (hard-to-read, § Plain language) — "wart" metaphor.**
Replace "the latent wart that today's `Step implementation [x]` flip is uncommitted
at session end" with literal phrasing, e.g. "the previously-uncommitted
`Step implementation [x]` flip", at both locations.

## Reminders

- House style applies. Return only a thin summary — never the drafted content.
- Line numbers above are from the round-1 draft; locate the passages by their
  content, not the exact line, since your edits shift line numbers.
