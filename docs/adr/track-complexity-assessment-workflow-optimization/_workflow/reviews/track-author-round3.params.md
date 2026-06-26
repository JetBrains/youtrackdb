# design-author params — Step-4b track authoring, round 3

- target: tracks
- output_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/plan
- plan_dir: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/plan
- research_log_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/research-log.md
- design_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/design.md
- round: 3

## Settled decomposition (unchanged — honor it)

Same as before: do NOT touch `## Purpose / Big Picture`, the plan Component Map,
the DR titles/ownership/`**Full design**` pointers, the §1.7 note, the in-scope
file lists, or `## Invariants & Constraints`. Fix only the flagged passages.

## flagged_passages — re-draft only these (round-2 auditor findings)

All are mechanical house-style fixes (chained enumeration → list, idiom → plain
word, one-clause gloss). Keep decision content and seed fidelity intact; these
are pure prose-shape edits.

### track-1.md
1. **"emit-order invariant" clause (~lines 226-228), too-terse.** Gloss it in
   one clause at first use: name the token reader (the precheck code scanning
   bare `key=value` tokens) and why a bare field must precede the quoted
   `categories` field (so the quoted field's spaces do not end the bare-token
   scan early).
2. **"Concrete deliverables" sentence (~lines 191-199), over-dense.** Six
   deliverables chained with semicolons and nested parentheticals. Convert to a
   bulleted list, one deliverable per line (content unchanged, shape only).

### track-2.md
3. **D2 Rationale "Keeping it to two…" (~lines 43-50), hard-to-read.** Split the
   two stacked abstractions into two sentences: first that the change stays a
   structural tier-unbundling, then that the model swap is an independent
   experiment that can land later.
4. **D6 floor causal chain (~lines 135-140), over-dense.** Linearize the
   three-link chain into one sentence per link (specialists gated on the same
   triggers → domain and complexity correlate → suppression would subtract
   review in the dangerous direction); pull the `low`-track-`configuration`
   example onto its own sentence.
5. **D5 sub-step ordering (~lines 98-107), too-terse.** Gloss the sub-steps in
   place: "Phase A runs its strategic panel (sub-step 3) before it decomposes
   the track into steps (sub-step 4)".
6. **"ping-pong" idiom (~lines 108-114), hard-to-read.** Replace with the
   literal "so the decompose-then-re-review cycle cannot repeat".
7. **D8b carrier `iff … ; iff …` enumeration (~lines 215-219), over-dense.**
   Break the two predicates onto a short two-item list under the "re-derives
   from the axes" lead.
8. **"Concrete deliverables" sentence (~lines 306-316), over-dense.** Convert
   the semicolon-chained deliverable enumeration to a bulleted list, one
   deliverable per line.

Return only a thin summary. Never return the drafted track content.
