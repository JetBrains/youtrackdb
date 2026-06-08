# Design mutation log — token-economy-oriented planning

## Mutation 1 — 2026-06-08 — phase1-creation (design.md)

**Diff summary**: Created `design.md` for token-economy-oriented planning. The design adds source-file overlap as an advisory tie-breaker on the workflow's two existing token levers (step fill in `track-review.md`, track sizing in `planning.md`), framed co-locate-first with adjacency as the fallback. Seven sections: Overview, Core Concepts, a Workflow decision flowchart, and four topic sections (the token model, track-level packing and cut seams, step-level overlap-aware fill, advisory enforcement). Decision records D1-D5 and invariants S1-S2 live inline in each section's `### References` footer per the YTDB-1083 discipline (introduce once at the home section, gist-plus-pointer thereafter); the `### References` heading name is retained because YTDB-1083's footer rename has not landed.

**Mechanical checks** (target=design): PASS (after 1 fix — Overview body split from 4 to 5 paragraphs so each of the five required Overview elements sits on its own non-empty line).

**Adversarial** (phase1-creation, before cold-read): 4 findings, 0 blocker, 2 should-fix, 2 suggestion. The pass ground-truthed five design claims against the live workflow files; claims on the fill rule, the maximize/fixed-tax framing, and the co-location-vs-adjacency asymmetry held. A1 (should-fix) corrected a false premise — the design had called the Phase 2 structural review "plan-file-only", but the review reads every pending track file including `## Interfaces and Dependencies`, and the existing argumentation gate fires on out-of-bounds footprint count, not on overlap. The fix reframed the advisory record as one reviewer-judgment criterion bullet in the structural review (blast radius 2 → 3 edit sites). A2 (should-fix) distinguished "the sizing-rule paraphrases stay edit-free because the rule is unchanged" from "the structural-review prompt gains one new criterion". A3 (suggestion) folded a note that S1 subordination is authoring discipline, not a Phase 2 gate. A4 (suggestion) dropped the "user asked for both" appeal from D2's rationale.

**Cold-read** (scope: whole-doc): PASS, 0 blocker, 0 should-fix, 2 suggestions. Applied the actionable one (renumber D-codes to document order: track-level D4 → D3, step-level D5 → D4, advisory D3 → D5). The second suggestion (a navigability note on the roadmap sentence) needed no change.

**Findings**:
- should-fix (adversarial A1): false plan-file-only premise corrected; advisory record reframed as a reviewer-judgment criterion in the structural review.
- should-fix (adversarial A2): sizing-paraphrase-unchanged vs new-criterion distinction added to S2 and the Overview.
- suggestion (adversarial A3): S1 enforcement-locus note folded into the advisory section.
- suggestion (adversarial A4): D2 rationale trimmed of the user-request appeal.
- suggestion (cold-read): D-code renumber to document order — applied.
- suggestion (cold-read): roadmap navigability note — no change needed.

**Iterations**: 2 of 3 (PASS)
