<!-- workflow-sha: fa922b14ad70d24a51e1d7b3832c2090d1d658bc -->
# Track 1: Single-step high tracks run the full reviewer selection at the step

## Purpose / Big Picture

After this track, a single-step `risk:high` track runs the full track-pass-equivalent reviewer selection — every reviewer the Phase C track pass would run — at the step, so the reviewers it would otherwise lose to the skipped Phase C track pass actually run. (`risk:high` is the per-step review-intensity tag the workflow assigns from a change's blast radius.)

**This track's file changes:**
- **ADDED:** none.
- **MODIFIED:** `.claude/workflow/review-agent-selection.md` (§Step-level vs track-level routing — widen the single-step-high step-level selection and scope its three narrowing paragraphs to multi-step high tracks); `.claude/workflow/code-review-protocol.md` (§Single-step tracks — correct the skip premise); `.claude/workflow/track-code-review.md` (§Single-Step Track — correct the same premise).
- **REMOVED:** none.

The bug (YTDB-1076, with its duplicate YTDB-1147): a single-step `risk:high` track is reviewed by too few reviewers. At a high step the step-level dimensional review fires only a subset of the reviewer roster — `review-bugs-concurrency` from the baseline group, plus the workflow reviewers whose file-pattern globs match the changed files (`review-workflow-hook-safety`, `review-workflow-prompt-design`). The other three baselines and the other four workflow reviewers — the deferred-reviewer set enumerated under Context and Orientation — defer to the Phase C track pass. But when the track has exactly one step and that step is `risk:high`, `code-review-protocol.md` and `track-code-review.md` skip the Phase C track pass on the premise that step-level review already covered the identical diff. The deferred reviewers then run nowhere. The skip premise is false: step-level ran only a subset.

This is one cohesive workflow-prose fix across three `.claude/workflow/*.md` files, edited live under the §1.7(k) prose-rule self-application opt-out (a branch may edit judgment-layer workflow prose in place instead of staging it, so the corrected rule is active for the branch's own review).

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-22T07:34Z [ctx=safe] Review + decomposition complete
- [x] 2026-06-22T08:00Z [ctx=safe] Step 1 complete (commit 61d9767e42)

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->
- The single-step-high fix self-applied at Step 1's own review: under the live corrected rule the single-step `risk:high` workflow-only track drew the four workflow reviewers the Phase C track pass would run (consistency, context-budget, instruction-completeness, writing-style), where the pre-fix rule drew zero. So Phase C skips the code-review portion for this single-step-high track — the full selection already ran at the step. See Episodes §Step 1.
- Out-of-scope coherence residual: the inline step-level-narrowing summaries at `step-implementation.md` sub-step 4(a), `execute-tracks/SKILL.md`, and `conventions-execution.md` §2.4 do not restate the single-step-high exception; per D3 they defer to `review-agent-selection.md` §Step-level routing through their pointers and were left unedited. A future session outside this opt-out branch could tighten them. See Episodes §Step 1.

## Decision Log

### D1: Tier = minimal
- Alternatives considered: `full` (a `design.md` plus a multi-track plan) and `lite` (no design, but a multi-track plan), both rejected.
- Rationale: the two tier gates resolve to minimal. Gate 1 (does the change need a narrative design doc?) = no — the fix is a localized correction across three workflow-prose `.md` files, one cohesive logical unit with a fully understood mechanism and no new structure to diagram. Gate 2 (one track or several?) = single track, which follows from that footprint: well under the ~12-file single-track bound. So the branch produces no `design.md` and no `implementation-plan.md`; this track file is the whole change's canonical record. The Phase 0 → 1 adversarial gate ran with the user-added `Workflow machinery` lens (the change edits a load-bearing review-selection and single-step-skip control-flow gate).
- Risks/Caveats: a mid-flight scope balloon — more files than the single-track footprint, or a structural change that wants a diagram — would escalate the tier through the inline-replan ESCALATE path.
- Implemented in: this track.

### D2: Edit live under the §1.7(k) prose-rule opt-out, not §1.7 staging
- Alternatives considered: §1.7 staging (stage the edits in a `staged-workflow/` subtree, promote at Phase 4); the §1.7(k) prose-rule opt-out (edit live).
- Rationale: §1.7 staging keeps a workflow-modifying branch's edits in a parallel `staged-workflow/` subtree that the live workflow ignores until Phase 4 promotes it, so a branch never runs under its own half-finished rules. Editing live departs from that on purpose: it makes the corrected rule active before this branch's own Phase C, so the fix self-applies and the single-step-high self-trap dissolves at the root. The change moves no `_workflow/**` artifact schema — no track-file section, resume-state field, drift-gate format, or stamp format — so the opt-out qualifies under §1.7(k) criteria 1 and 2 (the edited files' in-branch consumer is judgment-layer review-selection and review-protocol prose).
- Risks/Caveats: the opt-out covers judgment-layer workflow prose but not the orchestration loop — the executable Phase-B step loop in `step-implementation.md` — so it forbids editing that file. A mandatory `/migrate-workflow` stamp-advance must run after the last live `.claude/workflow` commit to re-arm the §1.6(b) drift gate (the Plan of Work spells out the stamp mechanics). Live edits to the branch's own rules must be re-validated end-to-end on any rebase that touches the three sections.
- Implemented in: this track.

### D3: Operative widening rule in §Step-level routing; premise corrections in the two skip-gate files; step-implementation.md untouched
- Alternatives considered: qualify `step-implementation.md` sub-step 4(a) inline (rejected — it is the opt-out-excluded orchestration loop, and §Step-level routing is the declared single source of truth that sub-step 4(a) consumes through its existing pointer); put the rule only in the two skip-gate files (rejected — §Step-level routing governs which reviewers fire at a step, so the widening belongs there).
- Rationale: `review-agent-selection.md:104-106` declares §Step-level routing the single source of truth that the step and track dispatch sites consume; sub-step 4(a) only restates the rule and defers, so the pointer carries the widening without any edit to the orchestration loop.
- Risks/Caveats: sub-step 4(a)'s inline `hook-safety, prompt-design` summary stays the default-case narrowing; the single-step-high widening lives only in §Step-level routing, so that paragraph must state the rule prominently enough that every dispatch site applies it.
- Implemented in: this track.

### D4: Widen to the full track-pass-equivalent selection — generalize past workflow reviewers
- Alternatives considered: widen only the four deferred workflow reviewers (the issue's literal framing); widen to the full track-pass-equivalent selection (every deferred reviewer).
- Rationale: the same defer-to-track-pass mechanism drops the three deferred baselines (`review-code-quality`, `review-test-behavior`, `review-test-completeness`) for a single-step-high Java track. "Full track-pass-equivalent selection" covers both classes with one rule and matches both issues' proposed-fix wording.
- Risks/Caveats: none material — the track pass already defines the full selection, so the rule reuses it.
- Implemented in: this track.

## Outcomes & Retrospective
- [x] Technical: PASS at iteration 2 (3 findings, 3 accepted) — T1/T2 (should-fix) and T3 (suggestion), all completeness gaps in the Plan of Work / Context / Interfaces / Validation sections; all applied and gate-verified. T1 added the contradicting `review-agent-selection.md:145-155` "zero-reviewer" paragraph to the edit scope; T2 made the widening an explicit override of both group-narrowing paragraphs; T3 enumerated the four-reviewer step-level roster for a `.claude/workflow/*.md`-only diff.

## Context and Orientation

Three terms set the scene. **Step-level review** is the dimensional review the orchestrator runs on one step's commit in Phase B; **track-level review** (the Phase C track pass) is the cumulative review of the whole track's diff. The two run different reviewer sets. At a high step, step-level review is narrowed, and the **deferred-reviewer set** drops out of it:

- baseline group — `review-code-quality`, `review-test-behavior`, `review-test-completeness`;
- workflow group — `review-workflow-consistency`, `review-workflow-context-budget`, `review-workflow-writing-style`, `review-workflow-instruction-completeness`.

This set does not fire at the step; it defers to the Phase C track pass. The deferral loses no coverage on its own, because that track pass runs the full reviewer selection. The **single-step-high Phase C skip** then skips that very pass when a single-step track's sole step is `risk:high`. The two rules combine so the deferred set runs nowhere.

The three in-scope files at branch start say the following. `review-agent-selection.md` §Step-level vs track-level routing (the declared single source of truth for step/track review timing, at lines 104-106) narrows the step to `review-bugs-concurrency` plus glob-matched workflow reviewers and defers the rest. Its lines 145-155 carry the worst case — a high step editing only `.claude/workflow/*.md` matches neither step-level workflow glob, so today it fires zero step-level workflow reviewers and defers entirely to the track pass. For a single-step track that paragraph's "fully defers" conclusion is the bug, so the fix scopes it to multi-step high tracks (see Plan of Work step 1). `code-review-protocol.md` §Single-step tracks (lines 58-64) and `track-code-review.md` §Single-Step Track (lines 105-113) state the skip on the premise "step-level dimensional review already ran against the identical diff" / "fully reviewed in Phase B."

This branch is itself the bug scenario. It is a workflow-only change, and its single step is unavoidably `risk:high`. `risk-tagging.md` caps a workflow-prose change at `risk:low` only when it changes no review gate and no reviewer-dispatch logic; this edit changes both the single-step skip gate and the step-level reviewer-dispatch rule, so the cap does not apply and the step stays `high`. The branch is held to its own changed rules under the §1.7(k) opt-out (edit-live), so the corrected rule is active before this branch's own Phase C and the fix self-applies — the single-step-high track is reviewed by the very rule it adds.

## Plan of Work

The fix is a set of prose edits across the three in-scope files. The widening rule in `review-agent-selection.md` §Step-level vs track-level routing is the anchor; the two premise corrections reference it, so it lands first.

1. **Widen the step-level selection** in `review-agent-selection.md` §Step-level vs track-level routing. When the high step under review is the sole step of its track, the step-level selection is not narrowed: it runs the full track-pass-equivalent selection — every baseline and every workflow reviewer the Phase C track pass would run — because that Phase C pass is skipped for single-step-high tracks. The section states its timing across three paragraphs that each narrow a slice, so the widening must override all three, not sit beside them:
   - the **baseline-group** paragraph (only `review-bugs-concurrency` at the step; the other three baselines defer);
   - the **workflow-review-group** paragraph (only `review-workflow-hook-safety` and `review-workflow-prompt-design` at the step; the other four workflow reviewers defer); and
   - the **"high step editing only `.claude/workflow/*.md`"** paragraph, which today asserts such a step "draws zero step-level reviewers and fully defers to the track pass" and calls that "correct on its own terms."

   The single-step-high carve-out contradicts all three as written, and the third most directly: its rationale — a multi-file gate change's completeness can only be judged on the cumulative diff — is exactly what fails when the track is one step and the Phase C cumulative pass never runs. So the implementer scopes each group narrowing and the zero-reviewer claim to **multi-step** high tracks (where the Phase C track pass does run, so the deferral loses no coverage) and states the single-step-high full-selection rule as the explicit exception each narrowing defers to — for example, a lead clause in each narrowing paragraph ("unless the high step is the sole step of its track — then the full selection runs at the step; see below") plus one widening paragraph stating the rule. The rule must be positioned and worded so a dispatch reader hitting any group paragraph first sees the single-step-high exception before applying that paragraph's narrowing; otherwise a Phase-B reader reaching the baseline paragraph would still narrow to `review-bugs-concurrency`. `step-implementation.md` sub-step 4(a) consumes this single source of truth through its existing `(see §Step-level vs track-level routing)` pointers and is not edited — the opt-out excludes the orchestration loop.
2. **Correct the false skip premise** in `code-review-protocol.md` §Single-step tracks. The skip is valid only once the full track-pass-equivalent selection has run at the step. Add one clause stating why the skip is then sound: this section is a review-selection rule the orchestrator re-reads at the start of each Phase C, so once the full selection already ran at the step, re-running the track pass would select the same reviewers against the same diff and add nothing.
3. **Correct the same premise** in `track-code-review.md` §Single-Step Track, the gate's other home, with the matching clause.

The approach is live edit under the §1.7(k) opt-out: no staged subtree, no Phase 4 promotion of staged files. `step-implementation.md` sub-step 4(a) is out of scope — the opt-out forbids editing the orchestration loop, and its pointer already carries the widened rule.

**Step sequencing (Phase A).** The whole fix is one coherent `risk:high` step (Step 1 in `## Concrete Steps`): a HIGH-category gate/dispatch change stays a single step so its step-level review sees the entire change at once (`track-review.md` §Step Decomposition, high-risk isolation), which is also what makes the self-application work — the branch's own single high step is reviewed under the rule it adds. The three file edits are ordered within the step: the `review-agent-selection.md` widening rule lands first as the anchor, then the two premise corrections in `code-review-protocol.md` and `track-code-review.md` that reference it.

Two obligations follow the edits. The `.claude/**` section summaries may change, so run `.claude/scripts/workflow-reindex.py --check` before committing — the toc-check CI gate is load-bearing.

The second obligation is the workflow-SHA stamp. Each `_workflow/**` artifact carries a line-1 stamp — the `<!-- workflow-sha: ... -->` comment that opens this very file — recording the workflow-format commit the artifact was created against. The §1.6(b) drift gate compares that recorded SHA against later workflow-format commits and fires when they diverge, prompting a re-read. Committing live `.claude/workflow` edits advances HEAD past every artifact's stamp base, so without intervention the gate would fire on this branch's own authoring every later session. The fix is to advance the stamps: after the branch's last live `.claude/workflow` commit, run `/migrate-workflow` (the stamp-advance command), which rewrites every artifact's line-1 SHA to HEAD and re-arms the gate to catch real develop-side drift instead.

The fix self-applies: this branch's own Phase C must show the single-step-high track drawing the full selection.

## Concrete Steps
1. Widen single-step-high step-level review to the full track-pass-equivalent selection and correct the two skip-gate premises. Edit `review-agent-selection.md` §Step-level vs track-level routing — scope the baseline-group, workflow-review-group, and `.claude/workflow/*.md`-only narrowing paragraphs to multi-step high tracks and add the single-step-high full-selection rule as the override each defers to, worded so a dispatch reader sees the exception before applying any narrowing — then `code-review-protocol.md` §Single-step tracks and `track-code-review.md` §Single-Step Track, correcting the "already covered / fully reviewed in Phase B" premise so the skip is licensed only once the full selection ran at the step. — risk: high (Workflow machinery: changes the single-step-high Phase C skip gate and the step-level reviewer-dispatch rule — a load-bearing control-flow protocol per `risk-tagging.md` §Workflow machinery; the prose-only `low` cap does not apply because the edit changes gate/dispatch logic)  [x] commit: 61d9767e42

## Episodes
<!-- Continuous-log. Empty at Phase 1; Phase B sub-step 7 appends one block per step. -->

### Step 1 — commit 61d9767e42, 2026-06-22T08:00Z [ctx=safe]

**What was done:** Widened the single-step-high step-level review to the full track-pass-equivalent selection and corrected the two skip-gate premises, across three live `.claude/workflow/*.md` files. In `review-agent-selection.md` §Step-level vs track-level routing, added a "Single-step-high override (read first)" paragraph — when the high step is the sole step of its track, the step-level selection runs every baseline and every workflow reviewer the Phase C track pass would run — and scoped the three narrowing paragraphs (baseline group, workflow-review group, and the `.claude/workflow/*.md`-only zero-reviewer paragraph) to multi-step high tracks, each opening with a lead clause that points back to the override so a dispatch reader meets the exception ahead of the narrowing. In `code-review-protocol.md` §Single-step tracks and `track-code-review.md` §Single-Step Track, corrected the skip premise: the Phase C skip is licensed once the full selection has run at the step, because re-running the track pass would then select the same reviewers against the same diff. The step landed across two commits — 935078765e (initial edit) and 61d9767e42 (Review fix for three writing-style findings).

**What was discovered:** The fix self-applied at this step's own review. Under the live corrected rule, this single-step `risk:high` workflow-only track drew the four workflow reviewers the Phase C track pass would run — `review-workflow-consistency`, `review-workflow-context-budget`, `review-workflow-instruction-completeness`, `review-workflow-writing-style` — where the pre-fix rule drew zero step-level reviewers. The writing-style pass flagged negative parallelism in the new override prose (two should-fix, one suggestion); all were fixed and gate-verified at iteration 2. The instruction-completeness pass raised one out-of-scope coherence note: the inline step-level-narrowing summaries at the dispatch sites — `step-implementation.md` sub-step 4(a), `execute-tracks/SKILL.md`, and `conventions-execution.md` §2.4 — still describe the multi-step default-case narrowing without restating the single-step-high exception. D3 decided those summaries stay default-case and defer to §Step-level routing through their existing pointers, so no edit was made; the residual is recorded for a future session outside this opt-out branch, which excludes the `step-implementation.md` orchestration loop.

**Key files:** `.claude/workflow/review-agent-selection.md`, `.claude/workflow/code-review-protocol.md`, `.claude/workflow/track-code-review.md`.

**Critical context:** This is the branch's last live `.claude/workflow` commit set (tip 61d9767e42). The §1.7(k) opt-out obligation is the one-shot `/migrate-workflow` stamp-advance, which runs after this commit to re-arm the §1.6(b) drift gate before the next session.

## Validation and Acceptance

Track-level acceptance (from YTDB-1076 and YTDB-1147):

- `review-agent-selection.md` §Step-level vs track-level routing carries the single-step-high full-selection rule.
- A single-step `risk:high` workflow-only (`.claude/workflow/*.md`) track runs at the step the four workflow reviewers the Phase C track pass would run for that diff — `review-workflow-consistency`, `review-workflow-context-budget`, `review-workflow-instruction-completeness`, and `review-workflow-writing-style` (the four that previously deferred); `review-workflow-prompt-design` and `review-workflow-hook-safety` stay glob-gated off a bare workflow-rule `.md` diff.
- `code-review-protocol.md` §Single-step tracks and `track-code-review.md` §Single-Step Track state that the skip's "already covered / fully reviewed in Phase B" premise holds only once the full selection ran at the step.
- A single-step-high Java track runs the three deferred baselines (`review-code-quality`, `review-test-behavior`, `review-test-completeness`) at the step — the generalization past the issues' workflow-reviewer framing.

Per-step acceptance (Step 1) — verified by reading the edited files, since the change is workflow prose with no test method behind it:

- GIVEN a single-step `risk:high` track whose sole step edits only `.claude/workflow/*.md`, WHEN Phase B reads `review-agent-selection.md` §Step-level vs track-level routing to select step-level reviewers, THEN it runs the full track-pass-equivalent selection — for this diff `review-workflow-consistency`, `review-workflow-context-budget`, `review-workflow-instruction-completeness`, and `review-workflow-writing-style` — not the narrowed subset.
- GIVEN the three narrowing paragraphs in that section, WHEN a dispatch reader reaches any one of them, THEN it sees the single-step-high full-selection exception before applying that paragraph's narrowing (no paragraph reads as still narrowing a single-step-high track).
- GIVEN that the full selection ran at the step, WHEN Phase C reaches the single-step-high skip gate in `code-review-protocol.md` §Single-step tracks and `track-code-review.md` §Single-Step Track, THEN the skip is licensed because each section's corrected premise (full selection already ran at the step) holds.

## Idempotence and Recovery

Step 1 is a set of prose edits to three workflow `.md` files, so it is naturally idempotent: re-applying the same widening rule and premise corrections converges on the same text, and there is no data, migration, or on-disk schema state to reconcile. Recovery from a partial or failed edit is the implementer's standard revert — `git reset --hard HEAD` back to the `## Base commit` — followed by a clean re-attempt; nothing outside the working tree changes. The only post-edit obligations are mechanical and re-runnable: `.claude/scripts/workflow-reindex.py --check` (rerun until clean if a section summary changed) and the one-shot `/migrate-workflow` stamp-advance after the branch's last live `.claude/workflow` commit.

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

In-scope files:

- `.claude/workflow/review-agent-selection.md` — §Step-level vs track-level routing: the baseline-group and workflow-review-group narrowing paragraphs and the "high step editing only `.claude/workflow/*.md`" paragraph (all three scoped to multi-step high tracks, with the single-step-high full-selection rule added as the exception they defer to), plus its TOC summary if the summary changes.
- `.claude/workflow/code-review-protocol.md` — §Single-step tracks (and its TOC summary, if it changes).
- `.claude/workflow/track-code-review.md` — §Single-Step Track (and its TOC summary, if it changes).

Out of scope:

- `.claude/workflow/step-implementation.md` — the orchestration loop the opt-out excludes; sub-step 4(a)'s `(see §Step-level vs track-level routing)` pointer carries the widened rule with no edit.
- `.claude/workflow/workflow.md:348` — mentions the single-step-high skip in the resume-state table but stays accurate, since the fix keeps the skip and only adds a precondition.

No inter-track dependencies: this is the single track. No library or function signatures change — the edit is prose. No track-level Mermaid diagram: the change touches no three-or-more interacting internal components.

## Invariants & Constraints

- After the fix, a single-step `risk:high` track runs the full track-pass-equivalent selection at the step — verified by reading the single-step-high paragraph in `review-agent-selection.md` §Step-level vs track-level routing.
- The single-step-high Phase C skip is licensed only when the full selection ran at the step — verified by the corrected premise in `code-review-protocol.md` §Single-step tracks and `track-code-review.md` §Single-Step Track.
- The branch carries the §1.7(k) opt-out marker and creates no `docs/adr/reviewers-loose/_workflow/staged-workflow/` subtree. The marker lives in the phase ledger — the append-only state file (`_workflow/phase-ledger.md`) the workflow reads to resume — whose `s17` field records the §1.7 staging mode; here `s17` = opt-out. Verified by the ledger value and the absent subtree.
- `.claude/scripts/workflow-reindex.py --check` passes after the edits (TOC and annotation consistency) — verified by the toc-check CI gate.
- `/migrate-workflow` runs after the branch's last live `.claude/workflow` commit — verified by the edited files' line-1 stamps advanced to HEAD.

## Base commit
271fa9fb71072d3828ca105cdb18d033afb53457
