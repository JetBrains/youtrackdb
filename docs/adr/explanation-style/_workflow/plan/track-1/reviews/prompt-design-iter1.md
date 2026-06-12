<!--MANIFEST
dimension: prompt-design
agent: review-workflow-prompt-design
iteration: 1
review_target: "Track 1, Step 1 (0f2834a245~1..0f2834a245)"
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

### WP1 [Minor] — opt-out marker referenced by anchor only, never inlined as a literal prefix

- File: `.claude/workflow/prompts/technical-review.md` (line 113), `.claude/workflow/prompts/risk-review.md` (line 110), `.claude/workflow/prompts/adversarial-review.md` (line 282)
- Axis: deterministic decision rules
- Cost: determinism of the new trigger branch rests on an out-of-prompt lookup; a reviewer that does not open `conventions.md §1.7(k)` cannot match the marker text.
- Issue: The extended trigger names the new opt-out marker only by section anchor (`§1.7(k)` prose-rule self-application opt-out marker sentence) and never inlines its literal stable prefix (`This plan uses the §1.7 prose-rule self-application opt-out:`). The sub-agent must resolve the anchor in `conventions.md` to learn the exact case-sensitive string to test against the plan's `### Constraints`. This is not a regression: the adjacent unchanged `§1.7(b)` reference and the `§1.7(d)` staged-read block use the identical anchor-only resolution pattern, and pinning the literal prefix in conventions (`§1.7(k)`) is the deliberate single-source-of-truth design (D6/R2). The edit is otherwise a clean, deterministic two-branch disjunction — header parenthetical and body condition stay in lockstep within each file, both branches are exact case-sensitive prefix matches, and the staged-read blocks correctly stay `§1.7(b)`-only. The remark is flagged only because it is the one spot in these three edits where the new branch's testability depends on a lookup the prompt does not carry inline.
- Suggestion: Optional and below the bar for action given the established convention. If the trio is ever revised for self-containment, inline the literal prefix once at first use (e.g. parenthetically: "the `§1.7(k)` opt-out marker sentence, stable prefix `This plan uses the §1.7 prose-rule self-application opt-out:`") so the prefix match is testable without a second-file read, while conventions stays the canonical definition. Leaving it anchor-only is acceptable and keeps these three blocks parallel to their existing `§1.7(b)` / `§1.7(d)` references.

## Evidence base
