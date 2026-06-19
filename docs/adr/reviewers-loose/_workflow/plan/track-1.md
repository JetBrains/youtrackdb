<!-- workflow-sha: 38eda5dbf538513e1d75ea1f43fc62033ea2b49c -->
# Track 1: Single-step high tracks run the full reviewer selection at the step

## Purpose / Big Picture

After this track, a single-step `risk:high` track runs the full track-pass-equivalent reviewer selection — every reviewer the Phase C track pass would run — at the step, so the reviewers it would otherwise lose to the skipped Phase C track pass actually run. (`risk:high` is the per-step review-intensity tag the workflow assigns from a change's blast radius.)

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands (Phase A). -->

The bug (YTDB-1076, with its duplicate YTDB-1147): a single-step `risk:high` track is reviewed by too few reviewers. At a high step the step-level dimensional review fires only a subset of the reviewer roster — `review-bugs-concurrency` from the baseline group, plus the workflow reviewers whose file-pattern globs match the changed files (`review-workflow-hook-safety`, `review-workflow-prompt-design`). The other three baselines and the other four workflow reviewers — the deferred-reviewer set enumerated under Context and Orientation — defer to the Phase C track pass. But when the track has exactly one step and that step is `risk:high`, `code-review-protocol.md` and `track-code-review.md` skip the Phase C track pass on the premise that step-level review already covered the identical diff. The deferred reviewers then run nowhere. The skip premise is false: step-level ran only a subset.

This is one cohesive workflow-prose fix across three `.claude/workflow/*.md` files, edited live under the §1.7(k) prose-rule self-application opt-out (a branch may edit judgment-layer workflow prose in place instead of staging it, so the corrected rule is active for the branch's own review).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

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
<!-- Continuous-log. Empty at Phase 1. -->

## Context and Orientation

Three terms set the scene. **Step-level review** is the dimensional review the orchestrator runs on one step's commit in Phase B; **track-level review** (the Phase C track pass) is the cumulative review of the whole track's diff. The two run different reviewer sets. At a high step, step-level review is narrowed, and the **deferred-reviewer set** drops out of it:

- baseline group — `review-code-quality`, `review-test-behavior`, `review-test-completeness`;
- workflow group — `review-workflow-consistency`, `review-workflow-context-budget`, `review-workflow-writing-style`, `review-workflow-instruction-completeness`.

This set does not fire at the step; it defers to the Phase C track pass. The deferral loses no coverage on its own, because that track pass runs the full reviewer selection. The **single-step-high Phase C skip** then skips that very pass when a single-step track's sole step is `risk:high`. The two rules combine so the deferred set runs nowhere.

The three in-scope files at branch start say the following. `review-agent-selection.md` §Step-level vs track-level routing (the declared single source of truth for step/track review timing, at lines 104-106) narrows the step to `review-bugs-concurrency` plus glob-matched workflow reviewers and defers the rest. Its lines 145-155 already note the worst case — a high step editing only `.claude/workflow/*.md` matches neither step-level workflow glob and fires zero step-level workflow reviewers, deferring entirely to the track pass. `code-review-protocol.md` §Single-step tracks (lines 58-64) and `track-code-review.md` §Single-Step Track (lines 105-113) state the skip on the premise "step-level dimensional review already ran against the identical diff" / "fully reviewed in Phase B."

This branch is itself the bug scenario. It is a workflow-only change, and its single step is unavoidably `risk:high`. `risk-tagging.md` caps a workflow-prose change at `risk:low` only when it changes no review gate and no reviewer-dispatch logic; this edit changes both the single-step skip gate and the step-level reviewer-dispatch rule, so the cap does not apply and the step stays `high`. The branch is held to its own changed rules under the §1.7(k) opt-out (edit-live), so the corrected rule is active before this branch's own Phase C and the fix self-applies — the single-step-high track is reviewed by the very rule it adds.

## Plan of Work

The fix is three prose edits across the three in-scope files. The widening rule is the anchor; the two premise corrections reference it, so it lands first.

1. **Widen the step-level selection** in `review-agent-selection.md` §Step-level vs track-level routing. When the high step under review is the sole step of its track, the step-level selection is not narrowed: it runs the full track-pass-equivalent selection — every baseline and every workflow reviewer the Phase C track pass would run — because that Phase C pass is skipped for single-step-high tracks. This paragraph must state the rule prominently, because the dispatch sites consume this single source of truth and apply it unchanged. `step-implementation.md` sub-step 4(a) is one such site: it only restates the rule and defers via its existing `(see §Step-level vs track-level routing)` pointer.
2. **Correct the false skip premise** in `code-review-protocol.md` §Single-step tracks. The skip is valid only once the full track-pass-equivalent selection has run at the step. Add one clause stating why the skip is then sound: this section is a review-selection rule the orchestrator re-reads at the start of each Phase C, so once the full selection already ran at the step, re-running the track pass would select the same reviewers against the same diff and add nothing.
3. **Correct the same premise** in `track-code-review.md` §Single-Step Track, the gate's other home, with the matching clause.

The approach is live edit under the §1.7(k) opt-out: no staged subtree, no Phase 4 promotion of staged files. `step-implementation.md` sub-step 4(a) is out of scope — the opt-out forbids editing the orchestration loop, and its pointer already carries the widened rule.

Two obligations follow the edits. The `.claude/**` section summaries may change, so run `.claude/scripts/workflow-reindex.py --check` before committing — the toc-check CI gate is load-bearing.

The second obligation is the workflow-SHA stamp. Each `_workflow/**` artifact carries a line-1 stamp — the `<!-- workflow-sha: ... -->` comment that opens this very file — recording the workflow-format commit the artifact was created against. The §1.6(b) drift gate compares that recorded SHA against later workflow-format commits and fires when they diverge, prompting a re-read. Committing live `.claude/workflow` edits advances HEAD past every artifact's stamp base, so without intervention the gate would fire on this branch's own authoring every later session. The fix is to advance the stamps: after the branch's last live `.claude/workflow` commit, run `/migrate-workflow` (the stamp-advance command), which rewrites every artifact's line-1 SHA to HEAD and re-arms the gate to catch real develop-side drift instead.

The fix self-applies: this branch's own Phase C must show the single-step-high track drawing the full selection.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the thin numbered roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1; Phase B sub-step 7 appends one block per step. -->

## Validation and Acceptance

Track-level acceptance (from YTDB-1076 and YTDB-1147):

- `review-agent-selection.md` §Step-level vs track-level routing carries the single-step-high full-selection rule.
- A single-step `risk:high` workflow-only track runs `review-workflow-consistency` plus the other always-on and glob-triggered workflow reviewers at the step.
- `code-review-protocol.md` §Single-step tracks and `track-code-review.md` §Single-Step Track state that the skip's "already covered / fully reviewed in Phase B" premise holds only once the full selection ran at the step.
- A single-step-high Java track runs the three deferred baselines (`review-code-quality`, `review-test-behavior`, `review-test-completeness`) at the step — the generalization past the issues' workflow-reviewer framing.

<!-- Per-step EARS/Gherkin lines are a Phase A placeholder — decomposition writes them per step. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

In-scope files:

- `.claude/workflow/review-agent-selection.md` — §Step-level vs track-level routing (and its TOC summary, if the summary changes).
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
