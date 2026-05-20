# Track 3: Pointers in workflow prompts and review agents

## Purpose / Big Picture
After this track lands, every prose-producing workflow prompt (10 of 11) and every prose-producing review agent (18 of 19) carries a single citation paragraph cross-referencing `.claude/output-styles/house-style.md` via the Track 1 conventions.md §1.5 anchor.

<!-- Reserved for Move 2. -->

Single citation paragraphs cross-referencing `house-style.md` (and the Track 1 conventions.md §1.5 anchor) in 10 workflow prompts (skip `design-review.md`, already verification-only per YTDB-836) and 18 prose-producing review agents (skip `review-workflow-writing-style.md`, already references house-style by name). Pointers name the rule source, the canonical workflow anchor, and the four banned-section heading slugs verbatim; no rule restatement.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review
- [x] Track completion
- [x] 2026-05-19T16:28Z [ctx=safe] Review + decomposition complete
- [x] 2026-05-20T02:13Z [ctx=safe] Step 1 complete (commit 14c3d73fa3)
- [x] 2026-05-20T02:52Z [ctx=safe] Step 2 complete (commit 6b6f35edac)
- [x] 2026-05-20T03:07Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)
- [x] 2026-05-20T03:07Z [ctx=safe] Track complete

## Base commit
cc53adccf4d78ac51329473e54af4dd5b197d195

## Surprises & Discoveries
- mcp-steroid tools (including `steroid_apply_patch`) are not exposed in implementer sub-agent spawns; Track 3 pure-Markdown insertion steps use a native `Edit` fallback. Step 2 of this track and the Tier-A pointer steps in Tracks 4 and 5 will reuse the same fallback. See Episodes §Step 1.

## Decision Log
<!-- Empty at Phase 1. -->

<!-- Reserved for Move 1. -->

## Outcomes & Retrospective
- [x] Technical: PASS at iteration 1 (4 findings, 4 accepted)
- [x] Track-level code review iteration 1 — PASS. Fan-out spawned 5 workflow-review agents (consistency, prompt-design, instruction-completeness, context-budget, writing-style) per the workflow-only baseline-skip override; review-workflow-hook-safety not triggered (no hook / script / settings file in the diff). Iter-1 produced 0 blockers / 1 should-fix / 5 suggestions. Orchestrator accepted F1 (workflow-consistency should-fix: `consistency-review.md` pointer paragraph landed between a colon-terminated lead-in and its enumerated list — moved past item 4 of the artifacts list) and DROPped 5 suggestions (WP1-WP3 reopen D3's reference-only pointer design; WP4 reopens the byte-identical-wording trade-off; WB1's reviewer explicitly recommended keeping the duplication as the cheapest shape). Gate-check on the affected dimension returned VERIFIED with no new findings. Commit `05d0585bf6`.
- [x] Track complete (1 review iteration, 0 plan corrections, 0 unfixed findings).

## Context and Orientation

The workflow surface this track touches is large in file count but small in per-file edit size — every in-scope file gets one citation paragraph added near the top, referencing the Track 1 conventions.md §1.5 anchor and naming the four banned-section heading slugs verbatim.

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

Pointer wording (template): one paragraph inserted near the top of each file's body (per-family anchor below in §Plan of Work), reading verbatim:

> Prose produced by this file follows the project house-style at `.claude/output-styles/house-style.md`. See `.claude/workflow/conventions.md §1.5 Writing style for Markdown and prose artifacts` for the canonical workflow-level anchor and tier mapping; the four banned-section heading slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`.

The wording is byte-identical across all 28 in-scope files (same `new_string` for every `steroid_apply_patch` hunk), so a single pointer-specific substring drives the §Validation audit grep. The four heading slugs named verbatim align this pointer with the Track 2 reminder bodies and the `test_16_section_name_guard` drift check in `.claude/scripts/tests/test_house_style_hook.py`.

## Plan of Work

The track delivers in two steps, by file family. Insertion anchors differ because the families have different file-shape conventions.

**Step 1 — workflow prompts (10 files):** None of these files has YAML frontmatter; they open with an orientation paragraph. The insertion anchor is the closing line of the file's opening orientation paragraph plus the following blank line — the pointer paragraph lands immediately after the file's BLUF lead and before the next paragraph or first `## ` heading.

**Step 2 — review agents (18 files):** All review agents have YAML frontmatter. The insertion anchor is the closing `---` line of the frontmatter plus the line that follows (the latter making the per-file `old_string` unique). The pointer paragraph lands as a new blank-separated paragraph between them, strictly below the closing `---` so the frontmatter block stays intact.

Both steps use `steroid_apply_patch` (one call per step, N hunks per call where N is the file count in that family) per `.claude/workflow/conventions.md §1.4 *Tooling discipline* → "Other mcp-steroid routes"`. The same `new_string` (pointer paragraph from §Context) is appended at every site; only the per-file `old_string` anchor differs.

Ordering constraints: Track 1 must be complete (the pointer text cites the §1.5 heading verbatim and names the four banned-section heading slugs). Tracks 2, 4, 5 are independent and may interleave.

Invariants to preserve: every review-agent file's existing YAML frontmatter (`---` block) stays intact, with exactly one opening and one closing `---` line; YAML keys `name`, `description`, and `model` stay byte-identical. The pointer paragraph never lands above or inside the frontmatter. No file (prompt or agent) gets more than one pointer paragraph.

## Concrete Steps

1. Insert the §Context pointer paragraph into the 10 in-scope workflow prompts under `.claude/workflow/prompts/` via a single `steroid_apply_patch` call (10 hunks; one per file). Anchor each hunk on the closing line of the file's opening orientation paragraph plus the following blank line; append the pointer paragraph and a blank line. Run the §Validation greps after the patch lands and confirm 10 in-scope prompts contain the canonical substring and the two skipped files are untouched. — risk: low (default: pure documentation insertion; no semantic change)  [x] commit: 14c3d73fa3
2. Insert the §Context pointer paragraph into the 18 in-scope review agents under `.claude/agents/` via a single `steroid_apply_patch` call (18 hunks; one per file). Anchor each hunk on the closing `---` line of the frontmatter plus the line that follows; insert the pointer paragraph between them, blank-separated. Run the §Validation greps and the §Idempotence and Recovery frontmatter-integrity check after the patch lands; expect 28 total pointer hits (including Step 1) and `2` for each agent's `awk` count. — risk: low (default: pure documentation insertion; frontmatter integrity preserved by anchoring strictly below the closing `---`)  [x] commit: 6b6f35edac

## Episodes

### Step 1 — commit 14c3d73fa3b5c7c580e40ee74490af49f816b369, 2026-05-20T02:13Z [ctx=safe]
**What was done:** Inserted the canonical house-style pointer paragraph into the 10 in-scope workflow prompts under `.claude/workflow/prompts/`. Pointer wording is byte-identical across all 10 files and cross-references `.claude/output-styles/house-style.md` plus the Track 1 `conventions.md §1.5` anchor, naming the four banned-section heading slugs verbatim. Validation grep returns 10 in-scope hits; `design-review.md` retains its prior self-reference to house-style.

**What was discovered:** The mcp-steroid tool surface (including `steroid_apply_patch`) is not exposed to implementer sub-agent spawns. Implementer fell back to 10 native `Edit` calls. For pure-Markdown insertions with unique per-file anchors, the observable result is identical to a multi-hunk `steroid_apply_patch` (no PSI / VFS index dependency). Track 3 Step 2 and Tracks 4-5 will hit the same constraint and should use the same fallback.

**What changed from the plan:** Execution mechanism changed from one `steroid_apply_patch` call (10 hunks) to 10 native `Edit` calls. No deliverable change: same pointer wording, same anchors, same validation grep outcome. Affects Step 2 of this track (same mechanism) and the Tier-A pointer steps in Tracks 4 and 5.

**Key files:**
- `.claude/workflow/prompts/adversarial-review.md` (modified)
- `.claude/workflow/prompts/consistency-gate-verification.md` (modified)
- `.claude/workflow/prompts/consistency-review.md` (modified)
- `.claude/workflow/prompts/create-final-design.md` (modified)
- `.claude/workflow/prompts/dimensional-review-gate-check.md` (modified)
- `.claude/workflow/prompts/review-gate-verification.md` (modified)
- `.claude/workflow/prompts/risk-review.md` (modified)
- `.claude/workflow/prompts/structural-gate-verification.md` (modified)
- `.claude/workflow/prompts/structural-review.md` (modified)
- `.claude/workflow/prompts/technical-review.md` (modified)

### Step 2 — commit 6b6f35edacb9e7b5eadf15479f18e819f8b48b87, 2026-05-20T02:52Z [ctx=safe]
**What was done:** Inserted the canonical house-style pointer paragraph into the 18 in-scope review agents under `.claude/agents/`. Pointer wording matches Step 1 byte-for-byte. Each insertion is anchored strictly below the closing YAML frontmatter `---` line, so every agent's YAML block stays intact. All three §Validation audits pass: 28 total pointer hits across the 28 in-scope files, both skipped files (`design-review.md`, `review-workflow-writing-style.md`) retain their prior `house-style` self-references, every in-scope agent's `awk '/^---$/{c++} END{print c}'` reports `2`.

**What was discovered:** Confirmed the Step 1 finding about mcp-steroid tool surface absence in implementer spawns. The native-`Edit`-per-file fallback produces byte-identical results to a planned multi-hunk `steroid_apply_patch` for pure-Markdown insertions with unique per-file anchors. Step 1's Surprises promotion already covers this for Tracks 4 and 5.

**What changed from the plan:** Mechanism changed from one `steroid_apply_patch` call (18 hunks) to 18 native `Edit` calls. No deliverable change. Same affect-on-future-tracks note as Step 1.

**Key files:**
- `.claude/agents/code-reviewer.md` (modified)
- `.claude/agents/pr-reviewer.md` (modified)
- `.claude/agents/review-bugs-concurrency.md` (modified)
- `.claude/agents/review-code-quality.md` (modified)
- `.claude/agents/review-crash-safety.md` (modified)
- `.claude/agents/review-performance.md` (modified)
- `.claude/agents/review-security.md` (modified)
- `.claude/agents/review-test-behavior.md` (modified)
- `.claude/agents/review-test-completeness.md` (modified)
- `.claude/agents/review-test-concurrency.md` (modified)
- `.claude/agents/review-test-crash-safety.md` (modified)
- `.claude/agents/review-test-structure.md` (modified)
- `.claude/agents/review-workflow-consistency.md` (modified)
- `.claude/agents/review-workflow-context-budget.md` (modified)
- `.claude/agents/review-workflow-hook-safety.md` (modified)
- `.claude/agents/review-workflow-instruction-completeness.md` (modified)
- `.claude/agents/review-workflow-prompt-design.md` (modified)
- `.claude/agents/test-quality-reviewer.md` (modified)

## Validation and Acceptance

- Every in-scope workflow prompt (10) and every in-scope review agent (18) carries the canonical pointer paragraph, verified by searching for the pointer-specific substring `conventions.md §1.5 Writing style for Markdown and prose artifacts`. Run `grep -l 'conventions.md §1.5 Writing style for Markdown and prose artifacts' .claude/workflow/prompts/*.md .claude/agents/*.md | wc -l` and expect `28`. The bare substring `house-style` is NOT used as the audit token because it incidentally appears in `.claude/agents/review-workflow-consistency.md:72`; the §1.5-anchored substring sidesteps the collision.
- The pointer wording is byte-identical across all 28 in-scope files (one `new_string` per `steroid_apply_patch` hunk).
- The two explicitly skipped files (`.claude/workflow/prompts/design-review.md`, `.claude/agents/review-workflow-writing-style.md`) still self-reference house-style, verified by `grep -l 'house-style' .claude/workflow/prompts/design-review.md .claude/agents/review-workflow-writing-style.md` returning both paths.
- For each in-scope review agent, the YAML frontmatter remains intact with exactly one opening and one closing `---` line. Run for every agent file: `awk '/^---$/{c++} END{print c}' <agent>` and expect `2`. A file returning anything other than `2` indicates a malformed insertion and must be repaired before Phase B closes.

<!-- Reserved for Move 3. -->

## Idempotence and Recovery

Both steps are atomic via `steroid_apply_patch` — if any single hunk's `old_string` fails pre-flight validation, no edits land. On failure, fix the failing hunk's anchor (typically a stale `old_string` from a file that was edited between research and execution) and re-run the patch.

Re-runnable audit (paste into terminal to verify track state at any time):

```bash
# 1. Pointer presence across the 28 in-scope files (expect 28):
grep -l 'conventions.md §1.5 Writing style for Markdown and prose artifacts' \
  .claude/workflow/prompts/adversarial-review.md \
  .claude/workflow/prompts/consistency-gate-verification.md \
  .claude/workflow/prompts/consistency-review.md \
  .claude/workflow/prompts/create-final-design.md \
  .claude/workflow/prompts/dimensional-review-gate-check.md \
  .claude/workflow/prompts/review-gate-verification.md \
  .claude/workflow/prompts/risk-review.md \
  .claude/workflow/prompts/structural-gate-verification.md \
  .claude/workflow/prompts/structural-review.md \
  .claude/workflow/prompts/technical-review.md \
  .claude/agents/code-reviewer.md \
  .claude/agents/pr-reviewer.md \
  .claude/agents/review-bugs-concurrency.md \
  .claude/agents/review-code-quality.md \
  .claude/agents/review-crash-safety.md \
  .claude/agents/review-performance.md \
  .claude/agents/review-security.md \
  .claude/agents/review-test-behavior.md \
  .claude/agents/review-test-completeness.md \
  .claude/agents/review-test-concurrency.md \
  .claude/agents/review-test-crash-safety.md \
  .claude/agents/review-test-structure.md \
  .claude/agents/review-workflow-consistency.md \
  .claude/agents/review-workflow-context-budget.md \
  .claude/agents/review-workflow-hook-safety.md \
  .claude/agents/review-workflow-instruction-completeness.md \
  .claude/agents/review-workflow-prompt-design.md \
  .claude/agents/test-quality-reviewer.md | wc -l

# 2. Skipped files still self-reference house-style (expect both paths):
grep -l 'house-style' \
  .claude/workflow/prompts/design-review.md \
  .claude/agents/review-workflow-writing-style.md

# 3. Per-agent frontmatter integrity (expect '2' for each in-scope agent):
for f in .claude/agents/{code-reviewer,pr-reviewer,review-bugs-concurrency,review-code-quality,review-crash-safety,review-performance,review-security,review-test-behavior,review-test-completeness,review-test-concurrency,review-test-crash-safety,review-test-structure,review-workflow-consistency,review-workflow-context-budget,review-workflow-hook-safety,review-workflow-instruction-completeness,review-workflow-prompt-design,test-quality-reviewer}.md; do
  printf '%s: ' "$f"; awk '/^---$/{c++} END{print c}' "$f"
done
```

These audits run identically on a fresh clone and on a partially-applied track, so a partial Step 1 / Step 2 commit is recoverable: re-run the failed step's patch with the surviving anchors and re-audit.

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
