---
severity: medium
phase: state-0
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# State 0 orchestrator has no rule for `recommend`-language structural fixes

## Symptom

During the autonomous plan review (State 0), the structural-review
sub-agent emitted finding S1 (plan-file total length 2034 lines vs the
~1500-line soft budget). Per the prompt's Bloat → "Plan-file total
length" rule, the **Fix** is phrased as: *"recommend a global trim
pass against the per-section budgets."* The finding was tagged
`mechanical` per the rule's blanket "all bloat findings are
mechanical by construction" classification.

The orchestration loop in `implementation-review.md` § Autonomous
orchestration loop says *"Apply ALL mechanical fixes immediately."*
Read literally, this means the orchestrator should auto-trim 6+
user-approved completed-track episodes (Tracks 7–21) on its own —
hundreds of lines of cross-track prose, with no per-section budget
to anchor the trim depth, and a real risk of removing strategic
context future tracks need.

The orchestrator had to invent a disposition mid-session: I marked
S1 as **acknowledged-but-deferred** in the audit summary (recorded
the recommendation, did not auto-apply), reasoning that
`recommend`-language fixes don't have a single unambiguous correct
rendering and so don't satisfy the mechanical-classifier's third
criterion (`Applying the fix doesn't change what the plan is trying
to achieve`). But the doc never authorizes that disposition
explicitly, and a future orchestrator could just as plausibly
auto-apply or skip silently.

## Reproduction context

- Phase: state-0
- Workflow doc(s) involved:
  - `.claude/workflow/prompts/structural-review.md` § Bloat →
    "Plan-file total length" (the rule with `recommend` language)
  - `.claude/workflow/implementation-review.md` § Autonomous
    orchestration loop (the "apply ALL mechanical fixes" rule)
  - `.claude/workflow/conventions.md` §1.2 (defines per-section
    budgets but not for episode prose)
- Tool / sub-agent involved: structural-review sub-agent
  (`prompts/structural-review.md`)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any State 0 (or `/review-plan`) run where the
  plan exceeds the ~1500-line soft cap **and** the cumulative-bloat
  branch of the rule fires (no single section dramatically
  oversized, but many sections collectively over budget). This
  condition fires on any long-lived plan with many completed tracks.

## Why it's a problem

The same plan-review run will produce different outcomes depending
on which interpretation the orchestrator picks:
- **Auto-apply**: trim user-approved episodes, risk losing context,
  large unauthorized cross-track edit, possibly destabilizing future
  tracks that depend on the absorbed material.
- **Defer**: file an acknowledgment in the audit summary, end the
  session — what I did, but the doc gives no green light.
- **Skip silently**: drop the finding, plan stays over budget, no
  audit trail.

For this plan the residual overage (1938 → still 28% over after
S2/S3/S4 application) lives in completed-track episodes — a class
of content with no per-section budget defined anywhere in
`conventions.md` §1.2. The current rule is recursive: "trim against
the per-section budgets" presupposes per-section budgets that don't
exist for the dominant content class.

## Proposed fix

Edit `.claude/workflow/prompts/structural-review.md` § Bloat →
"Plan-file total length" rule, plus
`.claude/workflow/implementation-review.md` § Mechanical vs.
design-decision classifier, to:

1. **Distinguish `recommend`-class fixes from `apply`-class fixes**
   in the bloat rules. `recommend` means: emit a `should-fix`
   finding with `Classification: design-decision` so the user
   resolves it (or explicitly authorizes a global trim pass). Or
   keep it `mechanical` but state explicitly that the orchestrator
   records the recommendation in the audit summary and does NOT
   auto-apply, when no per-section budget governs the dominant
   content class.

2. **Add a per-section budget for completed-track episode prose**
   in `conventions.md` §1.2 (e.g., `~80 lines per track-episode
   blockquote, including step-episode aggregation`). With a
   concrete budget, the cumulative-bloat rule's "trim against the
   per-section budgets" phrase becomes actionable. Without one, the
   rule is unanchored on long plans.

3. **Update the State 0 orchestration loop** to spell out the three
   dispositions for `mechanical` findings (`apply` / `record-only` /
   `escalate-to-user`) and the criteria that route between them —
   so future orchestrators don't have to invent the routing on the
   fly.

Option (1) + (2) is the cleaner pairing; option (3) is a minimum
fix if the budget question stays unanswered.

## Acceptance criteria

- `.claude/workflow/prompts/structural-review.md` distinguishes
  `recommend` vs `apply` in the Plan-file total length rule (or
  reclassifies it as `design-decision`).
- `.claude/workflow/conventions.md` §1.2 either defines a per-
  section budget for completed-track episode prose, or explicitly
  states that no per-section budget applies to that content class
  and that the cumulative-bloat rule defers to the user when the
  overage sits in that class.
- `implementation-review.md` § Autonomous orchestration loop has an
  explicit fork for `mechanical` findings whose proposed fix uses
  `recommend`/`audit`/`note` language (record-only) versus
  `replace`/`delete`/`update` language (auto-apply).
- A regression check: run the same State 0 against this plan after
  the rule update; the orchestrator's disposition for the residual
  overage is now an unambiguous outcome, not a judgment call.
