# Track 1: Rewrite the section length cap and propagate

## Purpose / Big Picture

After this track lands, the Phase C `review-workflow-writing-style` agent stops flagging ExecPlan structured-field paragraph blocks for the section length cap, and the rule reads as a soft cap with a categorical exemption list plus a padding-based finding criterion for free-form prose.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Rewrite `house-style.md § Structural rules` "Section length cap" as a soft cap with a categorical exemption (template-bound content) plus a padding-based finding criterion for free-form prose. Propagate the wording to the four other declarative sites (`house-style.md § Self-check` step 7, `review-workflow-writing-style.md` frontmatter `description:`, key rules list, review criteria). Add back-references from `episode-format-reference.md § Episode length rule` and `conventions-execution.md §2.2`.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was discovered" when the finding affects future steps or other tracks. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices, scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion summary at Phase C. -->

## Context and Orientation

The rule lives in `.claude/output-styles/house-style.md`. Five declarative sites currently restate the cap; the writing-style reviewer reads `house-style.md` once at review start, but its frontmatter `description:` is loaded into every system reminder, so the reviewer's restatements are also load-bearing.

**In-scope files:**

- `.claude/output-styles/house-style.md` — line 262 inside `## Structural rules` carries the rule body. Line 363 inside `## Self-check` step 7 carries the self-check restatement.
- `.claude/agents/review-workflow-writing-style.md` — frontmatter `description:` (line 3, always loaded), key rules list (line 18), review criteria block (lines 70-72, `### Section length`).
- `.claude/workflow/episode-format-reference.md` — `## Episode length rule` at line 346. Add a one-line back-reference to the new house-style exemption.
- `.claude/workflow/conventions-execution.md` — `## 2.2 Episode Formats` at line 284. Add a back-reference near the field-template description.

**Out of scope:**

- `.claude/scripts/design-mechanical-checks.py` — enforces a different cap (lines per `##` section on design.md only). Per Non-Goals in the plan.
- `.claude/skills/ai-tells/SKILL.md` — audits a different cross-section of house-style rules (vocabulary, sentence patterns); does not touch section length.
- The episode template field set itself (`What was done`, `Key files`, etc.) — only the length rule changes.

**Concrete deliverables:**

- A new "Section length cap exception" clause in `house-style.md § Structural rules` enumerating five exempt categories plus the padding-based finding criterion.
- Self-check step 7 rewritten to match.
- Three reviewer-agent restatements rewritten to point at the soft-cap rule and the exempt categories.
- Back-references added in `episode-format-reference.md § Episode length rule` and `conventions-execution.md §2.2 Episode Formats`.

## Plan of Work

Three edits, each landing in a separate commit so each is independently reviewable:

1. **Rewrite the rule body in `house-style.md`.** Replace the existing single-bullet "Section length cap" rule at line 262 with the soft-cap-plus-exemption pair. Update the matching self-check entry at step 7 (line 363) so the self-check enumerates the same exemptions. The exemption clause names five categories; the padding-based finding criterion cites the relevant house-style sections (`§ Banned vocabulary`, `§ Banned sentence patterns`, `§ Elegant variation`) by name so the reviewer's check is explicit.

2. **Update the reviewer-agent restatements.** In `.claude/agents/review-workflow-writing-style.md`, rewrite three sites: the frontmatter `description:` line (always loaded into every system reminder — keep wording ≤ ~150 chars), the key rules list line (the `**200-word section cap**` bullet), and the review criteria block under `### Section length`. All three use consistent wording naming the soft cap, the exempt categories, and the padding-based finding criterion.

3. **Add back-references.** In `.claude/workflow/episode-format-reference.md § Episode length rule`, append a one-line pointer to `house-style.md § Structural rules` "Section length cap exception." In `.claude/workflow/conventions-execution.md §2.2 Episode Formats`, add a back-reference to the same exemption alongside the field-template description.

**Ordering:** Step 1 lands first (defines the rule). Step 2 lands second (the reviewer-agent restatements quote the new rule). Step 3 lands last (back-references resolve forward to the new rule). The Phase A decomposer may merge steps 1 and 2 if the diff is small enough; the boundaries above are guidance, not contracts.

**Invariants to preserve:**

- The current cap value (200 words) stays — only the framing changes.
- House-style cross-references already in place from other workflow docs continue to resolve. The section name (`## Structural rules`) does not change; the rule body inside it changes.
- The reviewer's frontmatter `description:` stays ≤ ~150 chars per the always-loaded budget.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step. Empty at Phase 1. -->

## Validation and Acceptance

After all three commits land:

- `grep "Section length cap exception" .claude/output-styles/house-style.md` returns the new clause inside `## Structural rules`.
- The reviewer agent's three restatements (frontmatter `description:`, key rules list, review criteria) name the soft cap and the exempt categories. `grep -n "section length\|length cap" .claude/agents/review-workflow-writing-style.md` returns three matches whose wording is consistent with `house-style.md`.
- `grep "house-style.*Section length cap exception\|house-style.md § Structural rules" .claude/workflow/episode-format-reference.md .claude/workflow/conventions-execution.md` returns the back-references.
- A Phase C track-level writing-style review on a track with substantive multi-paragraph episodes (≥400 words in a structured-field block) does not produce a section-length finding against those paragraphs. Behavioral acceptance, verified on the next real Phase C run.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't belong to one specific step. Per-step episode content lives in ## Episodes above. -->

## Interfaces and Dependencies

**File-scope boundaries** listed under `## Context and Orientation` above.

**Compatibility:**

- Pre-existing `house-style.md` references from other workflow docs continue to resolve. The section name `## Structural rules` does not change; only the rule body inside it changes.
- The mechanical-check script (`design-mechanical-checks.py`) is unaffected — it enforces a different cap and reads a different file set.
- The `ai-tells` skill is unaffected — it audits vocabulary, sentence patterns, and openers/closers, not section length.

**Inter-track dependencies:** This is the only track in the plan. No downstream tracks consume its output.

**Library / function signatures:** N/A — this is a workflow-doc change. No code APIs touched.
