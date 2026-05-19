# Track 3: Pointers in workflow prompts and review agents

## Purpose / Big Picture
After this track lands, every prose-producing workflow prompt (10 of 11) and every prose-producing review agent (18 of 19) carries a one-line cross-reference to `.claude/output-styles/house-style.md` via the Track 1 conventions.md anchor.

<!-- Reserved for Move 2. -->

One-line cross-references to `house-style.md` (and the Track 1 conventions.md anchor) in 10 workflow prompts (skip `design-review.md`, already verification-only per YTDB-836) and 18 prose-producing review agents (skip `review-workflow-writing-style.md`, already references house-style by name). Pointers cite the rule source and the tier that applies; no rule restatement.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Empty at Phase 1. -->

## Decision Log
<!-- Empty at Phase 1. -->

<!-- Reserved for Move 1. -->

## Outcomes & Retrospective
<!-- Empty at Phase 1. -->

## Context and Orientation

The workflow surface this track touches is large in file count but small in per-file edit size — every file gets one paragraph (or one inline line) added near the top, referencing the Track 1 conventions.md section and the relevant tier.

Workflow prompts under `.claude/workflow/prompts/` (11 total):
- `adversarial-review.md` — devil's-advocate Phase A review; produces findings prose; needs Tier-A pointer (the report lands in the working directory).
- `consistency-gate-verification.md` — gate verdict prose; needs Tier-A pointer.
- `consistency-review.md` — autonomous Phase 2 consistency review; produces findings prose; needs Tier-A pointer.
- `create-final-design.md` — produces `design-final.md` and `adr.md`; needs Tier-A pointer (durable artifacts).
- `design-review.md` — **already verification-only** post-YTDB-836; **skip**.
- `dimensional-review-gate-check.md` — gate verdict prose; needs Tier-A pointer.
- `review-gate-verification.md` — gate verdict prose; needs Tier-A pointer.
- `risk-review.md` — Phase A risk review; produces findings prose; needs Tier-A pointer.
- `structural-gate-verification.md` — gate verdict prose; needs Tier-A pointer.
- `structural-review.md` — autonomous Phase 2 structural review; produces findings prose; needs Tier-A pointer.
- `technical-review.md` — Phase A technical review; produces findings prose; needs Tier-A pointer.

Review agents under `.claude/agents/` (19 total):
- `code-reviewer.md` — review findings; Tier-A.
- `pr-reviewer.md` — PR review prose; Tier-A.
- `review-bugs-concurrency.md`, `review-code-quality.md`, `review-crash-safety.md`, `review-performance.md`, `review-security.md` — dimensional-review agents; findings prose; Tier-A.
- `review-test-behavior.md`, `review-test-completeness.md`, `review-test-concurrency.md`, `review-test-crash-safety.md`, `review-test-structure.md` — test-quality dimensional agents; findings prose; Tier-A.
- `review-workflow-consistency.md`, `review-workflow-context-budget.md`, `review-workflow-hook-safety.md`, `review-workflow-instruction-completeness.md`, `review-workflow-prompt-design.md` — workflow-machinery review agents; findings prose; Tier-A.
- `review-workflow-writing-style.md` — **already names house-style by name** (lines 7-13 of the file read in research); **skip**.
- `test-quality-reviewer.md` — Tier-A.

Pointer wording (template): one line near the top of the file (after the frontmatter, before the body's first heading) reading roughly:

> "When this agent writes prose (findings reports, gate-verdict prose, durable artifact bodies), it follows the project house-style at `.claude/output-styles/house-style.md`; see also `.claude/workflow/conventions.md § Writing style for Markdown and prose artifacts` for the canonical workflow-level pointer and the tier mapping."

The exact wording is settled during Phase A; the goal is one line, citation-only, no rule restatement.

## Plan of Work

The track delivers in two steps, by file family:

Step 1 — Add pointers to the 10 workflow prompts. Use `steroid_apply_patch` (multi-site literal-text patch) since the same one-paragraph insertion lands at the same logical position in every file (top of body, after frontmatter where present). Verify the exact wording against the Track 1 conventions.md anchor before patching.

Step 2 — Add pointers to the 18 review agents. Same approach — `steroid_apply_patch` for the multi-site insertion. Skip `review-workflow-writing-style.md` (already done).

Ordering constraints: Track 1 must be complete (the pointer text cites the new conventions.md section heading verbatim). Tracks 2, 4, 5 are independent and may interleave.

Invariants to preserve: every file's existing frontmatter (`---` block) stays intact and the YAML stays valid. The pointer paragraph never lands above the frontmatter. No file gets more than one pointer paragraph.

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Empty at Phase 1. -->

## Validation and Acceptance

- Every prompt in scope and every review agent in scope contains the canonical house-style pointer, verified by searching for a stable pointer-specific substring (e.g., `conventions.md § Writing style for Markdown and prose artifacts` — Phase A settles the exact substring) rather than the bare path `house-style` (which already appears incidentally in some in-scope files such as `agents/review-workflow-consistency.md:72`, and would false-positive the audit). The grep returns empty across the in-scope file set, per YTDB-837 acceptance bullet 3.
- The pointer wording is identical across files (modulo agent / prompt naming) so a future audit can use a stable substring search.
- The skipped files (`design-review.md`, `review-workflow-writing-style.md`) already contain a house-style reference, verified by `grep -l 'house-style' <those-files>` returning both paths.

<!-- Phase A placeholder. -->

<!-- Reserved for Move 3. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files (28 total):**

Workflow prompts (10):
- `.claude/workflow/prompts/adversarial-review.md`
- `.claude/workflow/prompts/consistency-gate-verification.md`
- `.claude/workflow/prompts/consistency-review.md`
- `.claude/workflow/prompts/create-final-design.md`
- `.claude/workflow/prompts/dimensional-review-gate-check.md`
- `.claude/workflow/prompts/review-gate-verification.md`
- `.claude/workflow/prompts/risk-review.md`
- `.claude/workflow/prompts/structural-gate-verification.md`
- `.claude/workflow/prompts/structural-review.md`
- `.claude/workflow/prompts/technical-review.md`

Review agents (18):
- `.claude/agents/code-reviewer.md`
- `.claude/agents/pr-reviewer.md`
- `.claude/agents/review-bugs-concurrency.md`
- `.claude/agents/review-code-quality.md`
- `.claude/agents/review-crash-safety.md`
- `.claude/agents/review-performance.md`
- `.claude/agents/review-security.md`
- `.claude/agents/review-test-behavior.md`
- `.claude/agents/review-test-completeness.md`
- `.claude/agents/review-test-concurrency.md`
- `.claude/agents/review-test-crash-safety.md`
- `.claude/agents/review-test-structure.md`
- `.claude/agents/review-workflow-consistency.md`
- `.claude/agents/review-workflow-context-budget.md`
- `.claude/agents/review-workflow-hook-safety.md`
- `.claude/agents/review-workflow-instruction-completeness.md`
- `.claude/agents/review-workflow-prompt-design.md`
- `.claude/agents/test-quality-reviewer.md`

**Explicitly skipped:**
- `.claude/workflow/prompts/design-review.md` — already verification-only post-YTDB-836.
- `.claude/agents/review-workflow-writing-style.md` — already names house-style by name.

**Inter-track dependencies:**
- **Upstream**: Track 1 (cross-references the new conventions.md section heading).
- **Downstream**: none.

**Compatibility requirements:** every file's YAML frontmatter stays intact and valid. No agent's `name:` or `description:` field changes (those are interface contracts read by the Claude Code skill loader). The pointer paragraph never lands inside the frontmatter block.

**Library / function signatures relevant to this track:** none — pure documentation edits across many files.
