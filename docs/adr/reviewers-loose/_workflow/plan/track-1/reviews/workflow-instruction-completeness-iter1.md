<!--
MANIFEST
producer: review-workflow-instruction-completeness
iter: 1
findings: 1
evidence_base: 1
cert_index: 1
flags: []
index:
  - id: WI1
    sev: Minor
    anchor: "#wi1-minor-state-marker-transition--dispatch-site-coherence"
    loc: ".claude/workflow/step-implementation.md:433-438; .claude/skills/execute-tracks/SKILL.md:109-112; .claude/workflow/conventions-execution.md:520-522"
    cert: "#c1"
    basis: judgment
-->

## Findings

### WI1 [Minor] state-marker / dispatch-site coherence

- **File**: `.claude/workflow/step-implementation.md` (line 433-438), `.claude/skills/execute-tracks/SKILL.md` (line 109-112), `.claude/workflow/conventions-execution.md` (line 520-522)
- **Axis**: state marker transition (dispatch-site coherence with the single-source-of-truth rule)
- **Cost**: a Phase-B reader who selects step-level reviewers from a consuming site's inline summary, without following its `(see §Step-level vs track-level routing)` pointer, narrows a single-step-high track to the old subset — the exact bug this branch fixes — because none of the three consuming sites inline-signals the single-step-high exception.
- **Issue**: the operative widening rule lives only in `review-agent-selection.md` §Step-level vs track-level routing (correct per D3 — that section is the declared single source of truth and the opt-out forbids editing the orchestration loop). But the three sites that consume or summarise that rule still state the narrowed default as unconditional fact and defer to the section only via a pointer: `step-implementation.md` sub-step 4(a) ("The step-level baseline narrows to `review-bugs-concurrency` only"), `execute-tracks/SKILL.md:109` ("the step-level baseline `review-bugs-concurrency`, subject to the baseline-skip override"), and `conventions-execution.md:521` ("the step tier launches a subset (`review-bugs-concurrency` only)"). The fix is sound for any reader who follows the pointer — the override is the first paragraph in the section and says "Apply this override before any group narrowing below" — so this is a residual, not a gap in the in-scope edit. The override paragraph already discharges the dispatch contract by being read-first within the single source of truth.
- **Suggestion**: out of scope for this step under the §1.7(k) opt-out (`step-implementation.md` and `conventions-execution.md` are the orchestration/execution layer; `execute-tracks/SKILL.md` is the skill entry). No edit required here. If a later, non-opt-out-bound change touches those sites, add a four-word inline caveat to each — e.g. "(narrowed only for multi-step tracks; single-step-high runs the full selection — see §Step-level vs track-level routing)" — so the dispatch surface signals the exception without requiring the reader to chase the pointer. The override's "(read first)" placement makes this optional, not load-bearing.

## Evidence base

#### C1 — dispatch-site inline summaries restate the narrowed default without the single-step-high caveat (judgment)

Traced every site that states the step-level narrowed reviewer set. Three found beyond the edited section: `step-implementation.md:433-438` (sub-step 4(a), the step dispatch), `execute-tracks/SKILL.md:109-112` (skill-entry summary), `conventions-execution.md:520-522` (two-tier review summary). Each asserts the narrowed subset (`review-bugs-concurrency` only) as the unconditional default and points to `review-agent-selection.md` §Step-level vs track-level routing for the full rule; none carries an inline single-step-high caveat. Confirmed the override paragraph in the edited section is positioned first ("read first") and instructs "Apply this override before any group narrowing below," so a reader who follows any of the three pointers reaches the override before the narrowing — the single source of truth resolves deterministically. The residual affects only a reader who selects from an inline summary without following the pointer. D3 in the track file documents the deliberate scope choice (rule lives in the single source of truth; opt-out excludes the orchestration loop), and `step-implementation.md` / `conventions-execution.md` / `execute-tracks/SKILL.md` are out of scope. Survives as Minor: real adversarial-reader path, fully mitigated by the read-first override and correctly outside the in-scope edit.

Cross-check of the in-scope edit itself, all PASS (compressed):
- Conditional branch coverage — each of the three narrowing paragraphs (baseline-group, workflow-review-group, `.claude/workflow/*.md`-only) now opens with an explicit "Unless the high step is the sole step of its track..." lead clause deferring to the override; the multi-step complement is stated in each. The override paragraph states the single-step-high case. Complete.
- Deterministic reviewer-set resolution — "full track-pass-equivalent selection" = "every baseline and every workflow reviewer the Phase C track pass would run for that diff," delegating to the unchanged track-pass machinery (§Baseline agents, §Workflow-machinery override, §Per-agent file-pattern triggers). Verified the workflow-only case resolves to exactly four reviewers (consistency + context-budget always-launched; writing-style via `.claude/**/*.md`; instruction-completeness via `.claude/workflow/*.md`; prompt-design and hook-safety glob-gated off), matching the override's parenthetical and the track file's enumeration. Java case adds the three deferred baselines per D4. Both deterministic.
- Skip-gate license — `code-review-protocol.md` §Single-step tracks and `track-code-review.md` §Single-Step Track both now condition the skip on "the full track-pass-equivalent selection already ran at the step"; `track-code-review.md` adds the explicit guard "(A narrowed step-level review would not license the skip — the deferred reviewers would then run nowhere.)". No gap where a narrowed step-level review can still trigger the skip.
- Resume-path coherence — `workflow.md:348` (`steps-done-review-pending`) still reads "single-step tracks skip code review but still run track completion"; the fix keeps the skip and only adds a precondition, so the resume table stays accurate. Correctly noted out of scope in the track file.
