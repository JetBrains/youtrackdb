<!--MANIFEST
dimension: prompt-design
agent: review-workflow-prompt-design
iteration: 1
review_target: "Track 1 cumulative (a743adad35..HEAD)"
evidence_base:
  certs: 0
cert_index: []
flags: []
index:
  - id: WP1
    sev: Minor
    anchor: "### WP1 [Minor]"
    loc: ".claude/workflow/prompts/technical-review.md:113; risk-review.md:110; adversarial-review.md:282"
    cert: n/a
    basis: judgment
-->

## Findings

### WP1 [Minor] — opt-out criteria-switch trigger names the marker by anchor only, never inlines its literal prefix

- File: `.claude/workflow/prompts/technical-review.md` (line 113), `.claude/workflow/prompts/risk-review.md` (line 110), `.claude/workflow/prompts/adversarial-review.md` (line 282)
- Axis: deterministic decision rules
- Cost: the new OR-branch's testability rests on an out-of-prompt lookup; a reviewer LLM that does not open `conventions.md §1.7(k)` cannot match the exact case-sensitive marker text against the plan's `### Constraints`.
- Issue: The extended "Workflow-machinery criteria" trigger fires on the `§1.7(b)` workflow-modifying marker prefix **or** the `§1.7(k)` prose-rule self-application opt-out marker prefix, but it names the new opt-out marker only by section anchor — it never inlines the literal stable prefix (`This plan uses the §1.7 prose-rule self-application opt-out:`). The sub-agent must resolve the `§1.7(k)` anchor in `conventions.md` to learn the exact string to test. This is the cumulative cross-file confirmation of the declined step-level finding (`reviews/prompt-design-iter1.md` WP1, Minor): it holds identically across all three prompts, which carry the byte-identical OR-extension. It is not a regression — the adjacent unchanged `§1.7(b)` reference and the `§1.7(d)` staged-read block use the same anchor-only resolution pattern, and pinning the literal prefix once in `§1.7(k)` is the deliberate single-source-of-truth design (D6/R2). Inlining a second copy of the pinned string in the three prompts would create a drift hazard against the canonical definition — the exact failure the marker pinning exists to prevent. The rest of the edit is a clean, deterministic two-branch disjunction: the header parenthetical ("workflow-modifying or `§1.7` opt-out plans") and the body condition stay in lockstep within each file, both branches are exact case-sensitive prefix matches, and the separate "Staged-read precedence" block correctly stays `§1.7(b)`-only (under the opt-out there is no staged subtree, so a `(k)`-gated staged read would be a check that can never fire — §1.7(l) states this and the prompts honor it).
- Suggestion: Optional and below the action bar given the established convention. If the trio is ever revised for self-containment, inline the literal prefix once at first use (parenthetically, e.g. "the `§1.7(k)` opt-out marker sentence, stable prefix `This plan uses the §1.7 prose-rule self-application opt-out:`") so the prefix match is testable without a second-file read, while `conventions.md §1.7(k)` stays the canonical definition. Leaving it anchor-only is acceptable and keeps these three blocks parallel to their existing `§1.7(b)` / `§1.7(d)` references.

## Evidence base
