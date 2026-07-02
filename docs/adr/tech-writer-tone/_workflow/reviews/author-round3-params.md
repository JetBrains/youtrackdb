# design-author params — phase1-creation, round 3

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/research-log.md
- round: 3
- flagged_passages: (9 auditor findings + 1 mechanical should-fix + 1 absorption-driven D-record update. Edit only these; preserve the line-1 stamp byte-for-byte.)

## Flagged passages (round-2 auditor findings)

1. design.md:34 — glossary. "the cold-read prompt" in the four-consumer list resolves to no concrete artifact and mismatches §Class Design's fourth consumer. Align with `house-style.md:20`'s actual consumer list and name the file (`prompts/design-review.md`); keep list and diagram consistent.
2. design.md:185 — glossary. "the standing anchors" used with definite article, defined nowhere. Gloss in one clause at first use: the `## Overview` and `## Core Concepts` sections the auditor always reads alongside its slice.
3. design.md:205-206 — word choice. "Six style rules … leave `house-style.md`" — replace "leave" with "are removed from".
4. design.md:310-315 — split the two enumerations (three prose sections; five structured surfaces) into two sentences, and gloss "ExecPlan" in one clause at first use (the 15-section per-track execution-plan template from `conventions-execution.md §2.1`).
5. design.md:322-323 and :330 — "the carve" → "the carve-out" or name it explicitly ("restricting the voice to the prose sections").
6. design.md:500 and :519 — "skepticism toward shallow depth-dressing" still a coined metaphor. Replace with plain: "skepticism toward shallow content dressed up to look deep" at :500; short back-reference at :519.
7. design.md:663 — "licenses" → "permits" (match :665's wording).
8. design.md:680 — "do double duty" idiom → literal ("serve a second purpose: the dual-register guard").
9. design.md:602 and :604 — drop the "the user asked / the user named" conversation attributions; motivate from the shown problem (the bold-prefix summary running into the detail), which :603-605 already carries.

## Mechanical should-fix

10. Overview over its length cap (`overview-length` finding from `design-mechanical-checks.py`). Trim the Overview back under the cap — the numbered change-list can lose subordinate clauses that §Class Design and the topic sections already carry; do not delete the BLUF gloss or the reader-naming sentence.

## Absorption-driven update (D10)

11. The research log now carries **D10** (Adopt YTDB-1163's two BLUF-hardening rules), appended and gate-cleared after your round-2 write. Update §"Hardening the section BLUF lead" to seed it as a proper D-record:
    - Footer: cite **D10** as the owning decision (D4 stays as the related Summary-carrier decision; drop the D7 citation unless the section leans on the disposition criteria).
    - Fold in D10's gate-strengthened content: (a) **scope** — the two rules bind every section lead on workflow-markdown surfaces and the issue/PR BLUF, including surfaces with no Summary block; the `### Summary` sub-heading is the design-doc *carrier* of the lead, not the rules' whole scope; (b) **composition with the connect-backward opener** (the D9 transfer rule): the self-contained plain claim comes first, the backward link rides as a subordinate clause or the following sentence, never substituting for the claim — every agent touching the section-opening slot (writing-style reviewer, readability auditor, comprehension reviewer) reads one ranked rule; (c) the third acceptance site is anchored **by name** (the self-check item requiring the first paragraph to state the decision or symptom directly), not by ordinal "#9", because the D1/D7 removals renumber the list.
    - Re-read the log's D10 entry verbatim before writing; keep the section's prose faithful to it.
