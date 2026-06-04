<!-- workflow-sha: 786f441e224ba6c8c4240dde5d9368866fb9b405 -->
# Track 2: Review routing

## Purpose / Big Picture
After this track, a high step fires only the reviewers whose findings are localized to that step's diff; the rest defer to the cumulative Phase C track review, and `review-bugs-concurrency` is a mandatory baseline across all three review paths.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Split review-agent dispatch into step-level vs track-level for the first time and promote `review-bugs-concurrency` to a mandatory baseline. Edits `review-agent-selection.md` (baseline carve-out + a new non-mirrored triage note), `step-implementation.md` (sub-step 4a dispatch), `track-code-review.md` (track-level dispatch), the `risk-tagging.md` `high` quick-ref row, and `code-review/SKILL.md` (bugs-concurrency promotion).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

All edit targets are workflow machinery, so every edit routes to a staged copy under `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/...` per `conventions.md §1.7` (copy-then-edit on first touch; the live tree stays at develop's state). Sites verified current as of `cb5eec65`:

- **`review-agent-selection.md`** — `## Baseline agents (always run)` (`:32`) lists the four baselines, `review-bugs-concurrency` at `:40`. `## Workflow-review agents` (`:80`), `### Per-agent file-pattern triggers` (`:117`), and `### Workflow-machinery override (baseline-skip)` (`:140`) are the workflow-review machinery. `### Maintenance` (`:286`) names the mirror set: §Workflow-review agents, §Workflow-machinery file set, §Per-agent file-pattern triggers, and §Workflow-machinery override mirror `code-review/SKILL.md` verbatim (`:289-291`).
- **`step-implementation.md`** — sub-step 4 is the step-level review fan-out, gated to `risk: high` only (the high-only gate around `:415`, with the baseline-skip override referenced at `:427`). This is the step-level dispatch point.
- **`track-code-review.md`** — `### Agent selection and launching` (`:479`) selects agents per `review-agent-selection.md` and dispatches them against the cumulative track diff at Phase C. This is the track-level dispatch point.
- **`risk-tagging.md`** — the `high` quick-ref row (`:65`) summarizes what a `high` tag triggers; its step-level cell needs to reflect the new step-vs-track split. (Track 1 owns the rest of this file; this row is disjoint.)
- **`code-review/SKILL.md`** — the baseline/conditional table lists `review-code-quality` as "Always launched (unless `docs-only` is the ONLY category)" (`:190`), `review-bugs-concurrency` as conditional on a category list (`:191`), and the two test-review baselines as "Always launched (unless `docs-only` or `build-config` are the ONLY categories)" (`:200-201`). The promotion target wording matches the test-review baselines' exclusion shape.

The **load-bearing wiring constraint**: the step-vs-track timing cannot live in the `§Maintenance`-mirrored sections, because those mirror `SKILL.md` verbatim and `SKILL.md` has no step/track notion. It goes in a new, non-mirrored note. The `SKILL.md` bugs-concurrency promotion touches the baseline/conditional table, which is *not* in the mirror set, so it needs no sync-stamp bump.

Concrete deliverables: a baseline step-vs-track carve-out and a new non-mirrored triage note in `review-agent-selection.md`; a step-level dispatch in `step-implementation.md`; a track-level dispatch in `track-code-review.md`; an updated `risk-tagging.md` `high` quick-ref step cell; and a promoted `review-bugs-concurrency` baseline in `code-review/SKILL.md`.

## Plan of Work

The selection note is the source of truth the two dispatch points consume, so it lands first. The edits:

1. **Selection note** (D4, D5, D7) in `review-agent-selection.md`: at the `## Baseline agents` intro, add the baseline step-vs-track carve-out (`review-bugs-concurrency` at the step; `review-code-quality`, `review-test-behavior`, `review-test-completeness` at track). Add a NEW non-mirrored note carrying the workflow-reviewer triage (`hook-safety` + `prompt-design` at the step; `consistency`, `context-budget`, `writing-style`, `instruction-completeness` at track) and the rule that `review-bugs-concurrency` is excluded from workflow-machinery changes. Keep this note out of the `§Maintenance`-mirrored sections.
2. **Step-level dispatch** (D4, D5) in `step-implementation.md` sub-step 4a: on a `high` step, route the step-level baseline (`review-bugs-concurrency`, subordinate to the existing baseline-skip override) and the step-level workflow reviewers (`hook-safety`, `prompt-design`) by their file-pattern triggers. The `risk-tagging.md` `high` quick-ref step cell (edit 4) rides with this step.
3. **Track-level dispatch** (D4, D5) in `track-code-review.md`: at Phase C, dispatch the deferred baselines (`code-quality`, `test-behavior`, `test-completeness`) and the deferred workflow reviewers (`consistency`, `context-budget`, `writing-style`, `instruction-completeness`) against the cumulative track diff.
4. **SKILL promotion** (D7) in `code-review/SKILL.md`: promote `review-bugs-concurrency` to "Always launched (unless `docs-only` or `build-config` is the ONLY category)," matching the test-review baselines' exclusion shape. No `§Maintenance` sync-stamp bump (the table is not in the mirror set).

Invariants to preserve: the split changes only *which mandatory baselines* run at the step; conditional reviewers keep firing by their existing characteristic triggers, no trigger is widened and no agent is forced on. The `review-bugs-concurrency`-at-step rule is subordinate to the workflow-only/docs-only baseline-skip override. The `RISK_UPGRADE_REQUESTED` mid-implementation valve still re-enters the step-level review path for a grouped step that turns out HIGH.

<!-- Phase A appends a per-step sequencing summary referencing the Concrete Steps roster. -->

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. Per-step episodes do NOT live here; they live
in `## Episodes` below. The roster is immutable after Phase A except
for the status checkbox flip and the optional `commit:` annotation
Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

Track-level behavioral acceptance (Phase A turns these into per-step EARS/Gherkin lines):

- `review-agent-selection.md` carries a non-mirrored note stating the baseline split (`review-bugs-concurrency` at step; the other three baselines at track), the workflow-reviewer split (`hook-safety` + `prompt-design` at step; `consistency`, `context-budget`, `writing-style`, `instruction-completeness` at track), and the `review-bugs-concurrency`-excluded-from-workflow rule. The note is not inside any `§Maintenance`-mirrored section.
- `step-implementation.md` sub-step 4a dispatches `review-bugs-concurrency` (Java high steps, subordinate to the baseline-skip override) and the step-level workflow reviewers (`hook-safety`, `prompt-design` by trigger) on `high` steps only.
- `track-code-review.md` dispatches the deferred baselines and the four deferred workflow reviewers against the cumulative track diff at Phase C.
- `risk-tagging.md` `high` quick-ref row's step-level cell reflects the step-vs-track split.
- `code-review/SKILL.md` lists `review-bugs-concurrency` as "Always launched (unless `docs-only` or `build-config` is the ONLY category)"; the `§Maintenance` sync stamp is unchanged.
- Every edit lives under the staged subtree; the live `.claude/**` tree is byte-unchanged from develop.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In-scope files (staged copies under `_workflow/staged-workflow/.claude/...`):**
- `.claude/workflow/review-agent-selection.md` — the `## Baseline agents` intro (carve-out) and a new non-mirrored note; explicitly not the `§Maintenance`-mirrored sections.
- `.claude/workflow/step-implementation.md` — sub-step 4a (step-level dispatch).
- `.claude/workflow/track-code-review.md` — § Agent selection and launching (track-level dispatch).
- `.claude/workflow/risk-tagging.md` — the `high` quick-ref row (`:65`) only; the rest of this file is Track 1.
- `.claude/skills/code-review/SKILL.md` — the baseline/conditional table row for `review-bugs-concurrency`.

**Out-of-scope (owned by Track 1 or deliberately not edited):**
- `track-review.md`, `conventions.md §1.1`, and the `risk-tagging.md` HIGH/MEDIUM/LOW criteria + `~5` MEDIUM clause — all Track 1.
- The `review-agent-selection.md` `§Maintenance`-mirrored sections (`§Workflow-review agents`, `§Workflow-machinery file set`, `§Per-agent file-pattern triggers`, `§Workflow-machinery override`) — must not carry the step/track timing.
- The `step-implementation.md` high-only step-review gate and session-end context gate — load-bearing guardrails cited for context, not edited.

**Dependencies:**
- **Upstream:** depends on Track 1's `### Workflow machinery` risk taxonomy (D6) — the workflow-reviewer triage (D5) has no trigger without it. Track 2 follows Track 1.
- **Cross-track file:** the `risk-tagging.md` `high` quick-ref row (`:65`) is disjoint from Track 1's edits to the same file; the staged copy accumulates both, and each track's Phase C review delta-scopes to its own sections.

**Staging contract:** workflow-modifying marker present in `implementation-plan.md` § Constraints; writes route to the staged subtree; the staged-vs-live delta gets the Phase C `§1.7(h)` review, delta-scoped to the live-vs-staged diff (D5 convention), not the whole-file staged copy.
