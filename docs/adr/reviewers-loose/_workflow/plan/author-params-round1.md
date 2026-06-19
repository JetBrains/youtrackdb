# design-author params — Step-4b round 1 (reviewers-loose, minimal tier)

- target: tracks
- round: 1
- output_path: /home/andrii0lomakin/Projects/ytdb/reviewers-loose/docs/adr/reviewers-loose/_workflow/plan/
- track_file: /home/andrii0lomakin/Projects/ytdb/reviewers-loose/docs/adr/reviewers-loose/_workflow/plan/track-1.md  (skeleton already on disk; line-1 stamp is set by the orchestrator — fill ONLY the prose below the section headers, do NOT touch line 1)
- research_log_path: /home/andrii0lomakin/Projects/ytdb/reviewers-loose/docs/adr/reviewers-loose/_workflow/research-log.md
- design_path: (none — minimal tier, no design.md; seed the carriers from the research log directly)
- codebase_path: /home/andrii0lomakin/Projects/ytdb/reviewers-loose

The decisions, the track boundary, the Decision Records, and the section homes are already settled (below). Your job is to write cold-readable prose for each section, grounded in the research log and the live workflow files (read them; do not invent). Return a thin summary only — never paste the drafted track file back.

## Settled shape

This is a `minimal`-tier, single-track, §1.7(k)-prose-rule-opt-out (edit-live) workflow fix for **YTDB-1076** (canonical) and its duplicate **YTDB-1147**. The track is the whole change's canonical record (no plan, no design).

### The bug (ground this against the live files; cite section/line)
A single-step `risk:high` track is reviewed by too few reviewers. Two live rules combine:
1. `review-agent-selection.md` §Step-level vs track-level routing narrows step-level review: at a high step only `review-bugs-concurrency` runs from the baseline group, and of the six workflow reviewers only `review-workflow-hook-safety` + `review-workflow-prompt-design` fire by their file-pattern globs. The other three baselines (`review-code-quality`, `review-test-behavior`, `review-test-completeness`) and the other four workflow reviewers (`review-workflow-consistency`, `-context-budget`, `-writing-style`, `-instruction-completeness`) **defer to the Phase C track pass**.
2. `code-review-protocol.md` §Single-step tracks and `track-code-review.md` §Single-Step Track **skip** the Phase C track pass when a single-step track's sole step is `risk:high`, on the premise "step-level dimensional review already ran against the identical diff … fully reviewed in Phase B."
Together: the deferred reviewers run nowhere. The skip premise is false — step-level ran only a subset. Worst case (a `.claude/workflow/*.md` high step) fires zero step-level reviewers (matches neither glob), so a single-step-high workflow-only track ships reviewed by nobody.

### The fix (two distinct edit obligations — do not collapse)
1. **Widen the step-level selection** in `review-agent-selection.md` §Step-level vs track-level routing (the declared single source of truth for step/track timing): when the high step under review is the **sole step of its track**, the step-level selection is NOT narrowed — run the **full track-pass-equivalent selection** (every baseline and every workflow reviewer the Phase C track pass would run), because that Phase C pass is skipped for single-step-high tracks. This covers both the four deferred workflow reviewers AND the three deferred baselines (the fix generalizes past the issue's workflow-reviewer framing to Java single-step-high tracks).
2. **Correct the false skip premise** in `code-review-protocol.md` §Single-step tracks and `track-code-review.md` §Single-Step Track: the skip is valid **only once the full track-pass-equivalent selection ran at the step**. Add a clause noting *why* the skip is then sound — these sections encode a review-decision threshold re-read fresh each Phase C, not a parsed schema (gate iter2 A6).

`step-implementation.md` sub-step 4a is **out of scope** (the opt-out forbids editing the orchestration loop); its existing `(see §Step-level vs track-level routing)` pointer carries the widened rule.

### Section content to write

**## Purpose / Big Picture** — BLUF: after this track, a single-step `risk:high` track runs the full track-pass-equivalent reviewer selection at the step, so the reviewers it would otherwise lose to the skipped Phase C track pass actually run. Then the intro paragraph: the bug (YTDB-1076/1147), one cohesive workflow-prose fix across three files, edited live under the §1.7(k) opt-out.

**## Context and Orientation** — the live state of the three in-scope files at branch start (what each section currently says), and the terminology a Phase B/C reader needs: step-level vs track-level review, the deferred-reviewer set, the single-step-high Phase C skip, the §1.7(k) opt-out. Note this is a workflow-machinery change held to its own changed rules (edit-live), so the fix self-applies to this branch's own review.

**## Plan of Work** — prose sequence of the three edits (widen in §Step-level routing; correct premise in code-review-protocol.md; correct premise note in track-code-review.md), the ordering (the routing rule is the anchor the premise corrections reference), the live-edit (opt-out) approach with no staged subtree, the TOC/reindex obligation (run `.claude/scripts/workflow-reindex.py --check` before committing since `.claude/**` section summaries may change — the toc-check CI gate is load-bearing), and the mandatory `/migrate-workflow` stamp-advance after the branch's last live `.claude/workflow` commit. Note the self-application expectation explicitly.

**## Interfaces and Dependencies** — In-scope: `.claude/workflow/review-agent-selection.md` (§Step-level vs track-level routing + its TOC summary if the summary changes), `.claude/workflow/code-review-protocol.md` (§Single-step tracks + TOC summary), `.claude/workflow/track-code-review.md` (§Single-Step Track + TOC summary). Out-of-scope: `.claude/workflow/step-implementation.md` (opt-out-excluded orchestration loop), `.claude/workflow/workflow.md:348` (mentions the skip but stays accurate). No inter-track dependencies (single track). No library/function signatures (prose change). No track-level Mermaid diagram (no 3+ interacting internal components).

**## Decision Log** — three full four-bullet records:

#### D1: Edit live under the §1.7(k) prose-rule opt-out, not §1.7 staging
- Alternatives considered: §1.7 staging (stage edits, promote at Phase 4); the prose-rule opt-out (edit live).
- Rationale: editing live makes the corrected rule active before this branch's own Phase C, so the fix self-applies and the single-step-high self-trap dissolves; the change moves no `_workflow/**` artifact schema, so the opt-out qualifies under §1.7(k) criteria 1+2.
- Risks/Caveats: opt-out forbids editing `step-implementation.md`; mandatory `/migrate-workflow` stamp-advance after the last live workflow commit; live edits to the branch's own rules must be re-validated on rebase.
- Implemented in: this track.

#### D2: Operative widening rule in §Step-level routing (SSOT); premise corrections in the two skip-gate files; step-implementation.md untouched
- Alternatives considered: qualify `step-implementation.md` sub-step 4a inline (rejected — opt-out-excluded orchestration loop, and §Step-level routing is the declared single source of truth that sub-step 4a consumes via its existing pointer); put the rule only in the skip-gate files (rejected — §Step-level routing governs which reviewers fire at a step, so the widening belongs there).
- Rationale: `review-agent-selection.md:104-106` declares §Step-level routing the single source of truth that the dispatch sites consume; sub-step 4a only restates and defers, so the pointer carries the widened rule without touching the orchestration loop.
- Risks/Caveats: sub-step 4a's inline `hook-safety, prompt-design` summary stays the default-case narrowing; the single-step-high widening lives only in §Step-level routing, so that paragraph must state the rule prominently enough that the dispatch applies it.
- Implemented in: this track.

#### D3: Widen to the full track-pass-equivalent selection — generalize past workflow reviewers
- Alternatives considered: widen only the four deferred workflow reviewers (the issue's literal framing); widen to the full track-pass selection (all deferred reviewers).
- Rationale: the same defer-to-track-pass mechanism drops the three deferred baselines (`review-code-quality`, `review-test-behavior`, `review-test-completeness`) for a single-step-high Java track; "full track-pass-equivalent selection" covers both classes with one rule and matches both issues' proposed-fix wording.
- Risks/Caveats: none material — the track pass already defines the full selection; the rule reuses it.
- Implemented in: this track.

**## Validation and Acceptance** — track-level acceptance from both issues:
- `review-agent-selection.md` §Step-level vs track-level routing carries the single-step-high full-selection rule.
- A single-step `risk:high` workflow-only track runs `review-workflow-consistency` plus the other always-on / glob-triggered workflow reviewers at the step.
- `code-review-protocol.md` §Single-step tracks and `track-code-review.md` §Single-Step Track state that the skip's "already covered / fully reviewed in Phase B" premise holds only once the full selection ran at the step.
- A single-step-high Java track runs the three deferred baselines at the step (the generalization).
(Per-step EARS/Gherkin lines are a Phase A placeholder — leave the placeholder comment.)

**## Invariants & Constraints** — testable:
- After the fix, a single-step `risk:high` track runs the full track-pass-equivalent selection at the step — verified by reading §Step-level vs track-level routing's single-step-high paragraph.
- The single-step-high Phase C skip is licensed only when the full selection ran at the step — verified by the corrected premise in `code-review-protocol.md` §Single-step tracks and `track-code-review.md` §Single-Step Track.
- The branch carries the §1.7(k) opt-out marker (phase-ledger `s17` = opt-out) and creates no `_workflow/staged-workflow/` subtree — verified by the ledger and the absent subtree.
- `workflow-reindex.py --check` passes after the edits (TOC/annotation consistency) — verified by the toc-check CI gate.
- Stamp-advance: `/migrate-workflow` runs after the branch's last live `.claude/workflow` commit — verified by line-1 stamps advanced to HEAD.

## House style
Apply `.claude/output-styles/house-style.md` in full (this is a durable `.md` artifact): BLUF lead, banned sentence/analysis patterns, gloss project-specific entities at first use, ≤200-word sections. Write for a mid-level developer who has only this track file — they must be able to reconstruct the change without the research log or the authoring conversation.
