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

## Mutation 2 — 2026-06-08 — content-edit (design.md)

**Diff summary**: Reframed the token model after user feedback that the original undervalued merging. The first draft framed merging's benefit as "removes a cold read" (the small, overlap-specific effect) and called adjacency "saves almost nothing"; the user pointed out that merging unrelated changes saves agent startups (steps) and review-agent calls (tracks), independent of file overlap. Validated against Claude transcripts (75 sub-agents across 32 recent sessions, deduped by `message.id`): median sub-agent ~$2.47, review sub-agents $2.60-$3.40, step implementer ~$6.80, resident context 70K-160K, ~49% of workflow spend is sub-agents, master cost ≈ resident_context × turns (~85% cache re-reads). Reframed so the dominant lever is reducing the number of fresh agent contexts (an implementer per step, a review fan-out per track) — overlap-independent and already the existing maximize/fill rules' rationale — while source-file overlap is the second, smaller lever that fits more change per capped agent at the file-count cap, with the direct cold-read re-pay as a bonus. Adjacency stays the marginal fallback (removes no agent). Touched the Overview, the token-model TL;DR + three body paragraphs + edge cases + D1 + D2, the track-level packing paragraph, and the step-level TL;DR + adjacency caveat + D4. Decision records and invariants (D1-D5, S1-S2) are unchanged; only the framing and rationale moved. The measured magnitudes were added as evidence.

**Mechanical checks** (target=design): PASS (after 1 fix — a `dsc-ai-tell` fragmented-header flag because the reworded TL;DR opened with "token", echoing the heading "The token model"; dropped the word).

**Cold-read** (scope: whole-doc): PASS, 0 findings. Confirmed the dominant/second/marginal lever ordering is consistent across all four sections, no leftover old-framing sentence survives, and the magnitudes read as evidence rather than assertion.

**Findings**:
- (user feedback) original token model undervalued merging — reframed to lead with the overlap-independent per-agent saving, backed by measured transcript magnitudes.
- should-fix (mechanical): fragmented-header on the TL;DR opening — fixed.

**Iterations**: 2 of 3 (PASS)

## Mutation 3 — 2026-06-08 — content-edit (design.md)

**Diff summary**: Closed a producer/consumer completeness gap the user raised. The design described the reviewer side (the Phase 2 structural review flags an undocumented cross-track overlap-split as a `design-decision` finding, D5) but the planner-facing side was a soft "should justify" aside, so a planner could produce an unavoidable overlap-split without knowing a justification was expected and get flagged for it. The edit: (1) §"Track-level packing and cut seams" upgrades the producer sentence to an explicit requirement that names the reviewer check; (2) §"Advisory enforcement" adds that the criterion is one half of a pair, the planner-facing rule carrying the matching justification requirement; (3) adds invariant **S3** (home in §"Advisory enforcement", gist-plus-pointer cross-reference from §"Track-level packing and cut seams") — the planner-facing track-sizing rule and the reviewer-facing structural-review criterion land in the same change, neither ships without the other, so the producer is never flagged for a requirement it was not given. This clarifies that the `planning.md` edit carries both the cut-seam preference and the producer justification requirement; the edit-site count stays at three.

**Mechanical checks** (target=design): PASS.

**Cold-read** (scope: bounded — Track-level + Advisory + Overview): PASS, 0 findings. Confirmed the contract reads symmetrically (planner told → reviewer checks → S3 co-ships both), S3 is orthogonal to S1/S2, the introduce-once home/cross-reference split is correct, and no fourth edit site is implied.

**Findings**:
- (user feedback) reviewer-side check stated without the matching producer-side requirement — added the explicit planner requirement, the producer/consumer pairing, and invariant S3.

**Iterations**: 1 of 3 (PASS)
