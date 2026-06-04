<!-- workflow-sha: 786f441e224ba6c8c4240dde5d9368866fb9b405 -->
# Track 2: Review routing

## Purpose / Big Picture
After this track, a high step fires only the reviewers whose findings are localized to that step's diff; the rest defer to the cumulative Phase C track review, and `review-bugs-concurrency` is a mandatory baseline across all three review paths.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Split review-agent dispatch into step-level vs track-level for the first time and promote `review-bugs-concurrency` to a mandatory baseline. Edits `review-agent-selection.md` (baseline carve-out + a new non-mirrored triage note), `step-implementation.md` (sub-step 4a dispatch), `track-code-review.md` (track-level dispatch), the `risk-tagging.md` `high` quick-ref row, and `code-review/SKILL.md` (bugs-concurrency promotion).

## Base commit
31e38d1d501bd394e35efb6c737bbed3830980ff

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-06-04T15:56Z [ctx=info] Review + decomposition complete
- [x] 2026-06-04T16:17Z [ctx=safe] Step 1 complete (commit 0fc2ff0fc1)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

**Phase A decomposition decisions (2026-06-04):**

- **DL1 — Scope expanded by a consistency-sweep step (Step 5).** D4 makes the step-level baseline set `review-bugs-concurrency` only. Technical (T1) and adversarial (A1) review found three files outside the planned five still assert a step-level count of four: `code-review-protocol.md:32` ("For both levels: Baseline agents (4)"), `execute-tracks/SKILL.md:109` ("the step-level dimensional review loop (4 baseline + …)"), and `conventions-execution.md:401` (a two-tier pool summary, ambiguous). The first two are wrong at the step level once D4 lands. Left unstaged, the Phase 4 `cp -r` promotion ships a live workflow whose dispatch points contradict its overviews. Step 5 stages and corrects them in decomposition rather than deferring to a Phase C consistency fix that would stage them anyway. `finding-synthesis-recipe.md:68` and `track-code-review.md:125` were checked and left unchanged: both are track-level, where all four baselines still run.
- **DL2 — Track-level Phase C runs all four baselines; only the step level narrows.** design.md §"review-bugs-concurrency across the three review paths" makes `review-bugs-concurrency` mandatory at the Phase C track pass, the standalone skill, and every high Java step. The track-level set is unchanged (four baselines plus the full trigger-based workflow-reviewer selection). D4/D5 narrow only the step-level set (bugs-concurrency, `hook-safety`, `prompt-design`). "Defer to track" means an agent no longer also runs at the step; its coverage comes from the track pass that already runs it. The Step 3 dispatch edit must not drop `review-bugs-concurrency` from the track pass, which would leave a low/medium-only track with no bug-class coverage.
- **DL3 — All five steps tagged `risk: low` under the live taxonomy (§Self-application limit).** The I6 invariant keeps the live `.claude/**` workflow at develop's state for the branch's lifetime, so step risk tags read from the live `risk-tagging.md`, which has no workflow-machinery category. Workflow-prose edits fall to the LOW default. The staged `### Workflow machinery` taxonomy (Track 1) is the deliverable, not the operative rulebook for this branch. No step reaches step-level dimensional review; Phase C reviews the cumulative staged-vs-live delta.
- **DL4 — Accepted suggestions baked into step bodies, no Decision Record change.** R2: the taxonomy decides whether a step is `high`; the per-agent file-pattern globs, not the risk category, select which workflow reviewers fire, so the new note stays about timing. R3: the sub-step 4a edit leaves the `risk_tag == 'high'` gate condition and the baseline-skip-override reference unchanged, editing only the agent-selection list and the count text, so the `RISK_UPGRADE_REQUESTED` valve still re-enters sub-step 4. A2: the note justifies the zero-step-reviewer case for a `.claude/workflow/*.md`-only high step on its own terms (a gate/protocol change's resume-path defect class needs the cumulative diff), not by the prose-only-cap analogy, which covers a disjoint capped-`low` population. A3: the note states `review-bugs-concurrency` absorbs the buriable error-handling subset, so deferring `review-code-quality` loses only style, DRY, and readability. A4: the note states why `prompt-design`'s localized core outweighs its cross-file references. A5: the note cross-references `§Baseline agents` and `§Workflow-review agents`, reconciles the `## Baseline agents (always run)` heading with the carve-out, and `§Maintenance` gains a one-line pointer that the step/track timing lives in the non-mirrored note. T2/A6: edit the already-staged `risk-tagging.md` copy in place, locating the `high` row by content rather than the live `:65` offset.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 1 (2 findings, both accepted: T1 should-fix, T2 suggestion).
- [x] Risk: PASS at iteration 1 (3 findings, all accepted: R1 should-fix, R2/R3 suggestions).
- [x] Adversarial: PASS at iteration 1 (6 findings, all accepted: A1/A2 should-fix, A3-A6 suggestions). No `skip` recommendation; all three Decision Records (D4, D5, D7) survived. A1 drove a scope expansion (Step 5); the rest baked into the selection-note prose and per-step guardrails.

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

The selection note is the source of truth the two dispatch points consume, so it lands first. Phase A review settled one semantic point that governs every step below (Decision Log DL2): per design.md §"review-bugs-concurrency across the three review paths", `review-bugs-concurrency` is mandatory at the Phase C track pass as well as at high Java steps, so the **track-level set is unchanged** (all four baselines plus the full trigger-based workflow-reviewer selection). D4/D5 narrow only the **step-level** set. "Defer to track" means an agent no longer also runs at the step; its coverage comes from the track pass that already runs it. The track set does not shrink.

The edits:

1. **Selection note** (D4, D5, D7) in `review-agent-selection.md`: at the `## Baseline agents` intro, add the baseline step-vs-track carve-out. At a high step only `review-bugs-concurrency` runs from the baseline group (subordinate to the baseline-skip override); `review-code-quality`, `review-test-behavior`, `review-test-completeness` no longer run at the step and are covered by the track pass. Add a NEW non-mirrored note carrying the workflow-reviewer triage (`hook-safety` + `prompt-design` at the step, selected by their existing file-pattern globs; `consistency`, `context-budget`, `writing-style`, `instruction-completeness` at track) and the rule that `review-bugs-concurrency` is excluded from workflow-machinery changes. Keep this note out of the `§Maintenance`-mirrored sections. The note also: (a) selects the step-level workflow reviewers by file-pattern glob, not by risk category, since the taxonomy only decides whether a step is `high` while the globs decide which workflow reviewers fire (DL4/R2); (b) justifies the zero-step-reviewer outcome for a `.claude/workflow/*.md`-only high step on its own terms, because a gate or control-flow change's resume-path defect class needs the cumulative diff, not by the prose-only-cap analogy (DL4/A2); (c) states that `review-bugs-concurrency` absorbs the buriable error-handling subset, so deferring `review-code-quality` loses only style, DRY, and readability (DL4/A3); (d) states why `prompt-design`'s localized core (frontmatter, `$ARGUMENTS`, one prompt's internal decision rules) outweighs its cross-file references (DL4/A4); (e) cross-references `§Baseline agents` and `§Workflow-review agents` for membership, reconciles the `## Baseline agents (always run)` heading with the carve-out (the "always run" is the track-level reading), and adds a one-line `§Maintenance` pointer that the step/track timing lives in the non-mirrored note (DL4/A5).
2. **Step-level dispatch** (D4, D5) in `step-implementation.md` sub-step 4a, with the `risk-tagging.md` `high` quick-ref cell (edit 4) riding this step: on a `high` step, route the step-level baseline (`review-bugs-concurrency`, subordinate to the baseline-skip override) and the step-level workflow reviewers (`hook-safety`, `prompt-design`) by their file-pattern triggers. Reword the sub-step-4a "Baseline agents (4)" framing to the step-level set (bugs-concurrency only). **Guardrail (R3):** leave the `risk_tag == 'high'` gate condition and the baseline-skip-override reference unchanged, editing only the agent-selection list and the count text, so the `RISK_UPGRADE_REQUESTED` valve still re-enters sub-step 4 for an upgraded grouped step. Rewrite the `risk-tagging.md` `high` quick-ref step-level cell from "4 baseline + conditional" to the post-split membership in the **same step** (R1), editing the **already-staged** `risk-tagging.md` copy in place. Locate the `high` row by its content, not the live `:65` offset, and do not re-copy from live, which would drop Track 1's `### Workflow machinery` taxonomy (§1.7(d); T2/A6).
3. **Track-level dispatch** (D4, D5) in `track-code-review.md` § Agent selection and launching: make the track-level dispatch explicitly cover the step-deferred agents. The track pass runs all four baselines (including `review-bugs-concurrency`, per DL2) and the full trigger-based workflow-reviewer selection, which is where the deferred baselines (`code-quality`, `test-behavior`, `test-completeness`) and the deferred workflow reviewers (`consistency`, `context-budget`, `writing-style`, `instruction-completeness`) get their coverage. Do not reduce the track baseline set to three. `track-code-review.md:125` (§Multi-Step Tracks, "Baseline agents (4)") is track-level and stays correct; leave it.
4. **SKILL promotion** (D7) in `code-review/SKILL.md`: promote `review-bugs-concurrency` to "Always launched (unless `docs-only` or `build-config` is the ONLY category)," matching the test-review baselines' exclusion shape. No `§Maintenance` sync-stamp bump (the table is not in the mirror set).
5. **Consistency sweep** (D4 blast radius; scope added at Phase A, see Decision Log DL1) across three overview/summary files that assert a step-level baseline count D4 invalidates: `code-review-protocol.md:32` ("For both levels: Baseline agents (4)") becomes level-aware (step: `review-bugs-concurrency` only, subordinate to the skip override; track: all four); `execute-tracks/SKILL.md:109` ("the step-level dimensional review loop (4 baseline + …)") is corrected to the step-level set; `conventions-execution.md:401` (two-tier pool summary) is clarified as the cross-tier inventory, not a per-tier count. Leave `finding-synthesis-recipe.md:68` and `track-code-review.md:125` (track-level, all four still run).

Invariants to preserve: the split changes only *which mandatory baselines* run at the step; conditional reviewers keep firing by their existing characteristic triggers, no trigger is widened and no agent is forced on. The `review-bugs-concurrency`-at-step rule is subordinate to the workflow-only/docs-only baseline-skip override. The `RISK_UPGRADE_REQUESTED` mid-implementation valve still re-enters the step-level review path for a grouped step that turns out HIGH. The track-level set is unchanged (DL2).

<!-- Phase A appends a per-step sequencing summary referencing the Concrete Steps roster. -->

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. Per-step episodes do NOT live here; they live
in `## Episodes` below. The roster is immutable after Phase A except
for the status checkbox flip and the optional `commit:` annotation
Phase B appends. -->

Steps 2-5 each consume Step 1's selection note (source of truth) but touch disjoint file sets, so they may run in any order after Step 1.

1. Selection note in `review-agent-selection.md`: baseline step-vs-track carve-out at the `## Baseline agents` intro plus a new non-mirrored workflow-reviewer triage note (D4, D5, D7) — risk: low (default; live taxonomy, §Self-application limit)  [x] commit: 0fc2ff0fc1
2. Step-level dispatch in `step-implementation.md` sub-step 4a, with the `risk-tagging.md` `high` quick-ref cell rewrite riding it (already-staged copy, edit in place) (D4, D5) — risk: low (default)  [ ] *(parallel with Step 3, Step 4, Step 5)*
3. Track-level dispatch in `track-code-review.md` § Agent selection and launching; track set unchanged, documents deferred-agent coverage (D4, D5, DL2) — risk: low (default)  [ ] *(parallel with Step 2, Step 4, Step 5)*
4. SKILL promotion of `review-bugs-concurrency` to a mandatory baseline in `code-review/SKILL.md` (D7) — risk: low (default)  [ ] *(parallel with Step 2, Step 3, Step 5)*
5. Consistency sweep of stale step-level baseline-count text in `code-review-protocol.md`, `execute-tracks/SKILL.md`, and `conventions-execution.md` (D4 blast radius, DL1) — risk: low (default)  [ ] *(parallel with Step 2, Step 3, Step 4)*

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit 0fc2ff0fc1, 2026-06-04T16:17Z [ctx=safe]
**What was done:** Staged `review-agent-selection.md` (copy-then-edit per §1.7(e); live tree byte-unchanged) and added the step-vs-track routing note that Steps 2 and 3 consume as their source of truth. At the `## Baseline agents (always run)` intro, a baseline carve-out: a `high` step runs only `review-bugs-concurrency` from the baseline group (subordinate to the baseline-skip override), and the other three baselines defer to the Phase C track pass. A new non-mirrored `## Step-level vs track-level routing` H2, placed between the conditional-agents `### Examples` and `## Workflow-review agents` (outside the four `§Maintenance`-mirrored sections), carries the workflow-reviewer triage (`hook-safety` + `prompt-design` at the step by their file-pattern globs; `consistency`, `context-budget`, `writing-style`, `instruction-completeness` at the track) and the rule excluding `review-bugs-concurrency` from workflow-machinery changes. The note bakes in the five DL4 points: glob-not-risk-category selection (R2), the zero-step-reviewer justification on its own resume-path-defect-class terms rather than the prose-only-cap analogy (A2), `review-bugs-concurrency` absorbing the buriable error-handling subset so deferring `review-code-quality` loses only style/DRY/readability (A3), `prompt-design`'s localized core outweighing its cross-file references (A4), and the membership cross-references plus the "always run" heading reconciliation and the one-line `§Maintenance` pointer (A5). File grew 298 → 383 lines.

**Key files:**
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/review-agent-selection.md` (new)

## Validation and Acceptance

Track-level behavioral acceptance (Phase A turns these into per-step EARS/Gherkin lines):

- `review-agent-selection.md` carries a non-mirrored note stating the baseline split (`review-bugs-concurrency` at step; the other three baselines at track), the workflow-reviewer split (`hook-safety` + `prompt-design` at step; `consistency`, `context-budget`, `writing-style`, `instruction-completeness` at track), and the `review-bugs-concurrency`-excluded-from-workflow rule. The note is not inside any `§Maintenance`-mirrored section.
- `step-implementation.md` sub-step 4a dispatches `review-bugs-concurrency` (Java high steps, subordinate to the baseline-skip override) and the step-level workflow reviewers (`hook-safety`, `prompt-design` by trigger) on `high` steps only.
- `track-code-review.md` § Agent selection and launching covers all four baselines (including `review-bugs-concurrency`, per DL2) and the deferred workflow reviewers against the cumulative track diff at Phase C; the track baseline set is not reduced to three.
- `risk-tagging.md` `high` quick-ref row's step-level cell reflects the step-vs-track split.
- `code-review/SKILL.md` lists `review-bugs-concurrency` as "Always launched (unless `docs-only` or `build-config` is the ONLY category)"; the `§Maintenance` sync stamp is unchanged.
- Every edit lives under the staged subtree; the live `.claude/**` tree is byte-unchanged from develop.

Per-step acceptance:

- **Step 1.** Given the staged `review-agent-selection.md`, then a non-mirrored note states the baseline split (`review-bugs-concurrency` at step; the other three at track), the workflow-reviewer split (`hook-safety` + `prompt-design` at step; the other four at track), and the bugs-concurrency-excluded-from-workflow rule; the note sits outside every `§Maintenance`-mirrored section; the step-level workflow reviewers are selected by file-pattern glob, not risk category; the `## Baseline agents (always run)` heading is reconciled with the carve-out; and `§Maintenance` carries a one-line pointer to the non-mirrored timing note.
- **Step 2.** Given a `high` Java step, sub-step 4a dispatches `review-bugs-concurrency` (subordinate to the baseline-skip override) plus `hook-safety` and `prompt-design` by trigger, and no other baseline. The `risk_tag == 'high'` gate condition is byte-unchanged from live. The sub-step-4a count no longer reads "Baseline agents (4)". The staged `risk-tagging.md` `high` quick-ref step-level cell no longer reads "4 baseline", and Track 1's `### Workflow machinery` taxonomy is still present in the staged copy.
- **Step 3.** Given Phase C, the `track-code-review.md` dispatch runs all four baselines and the deferred workflow reviewers; `review-bugs-concurrency` still runs at the track pass; `track-code-review.md:125` is unchanged.
- **Step 4.** `code-review/SKILL.md` lists `review-bugs-concurrency` as "Always launched (unless `docs-only` or `build-config` is the ONLY category)"; the `§Maintenance` sync stamp is unchanged.
- **Step 5.** `code-review-protocol.md:32` states a level-aware baseline count (step: bugs-concurrency only; track: four); `execute-tracks/SKILL.md:109` describes the step-level set, not "4 baseline"; `conventions-execution.md:401` reads as a cross-tier inventory; `finding-synthesis-recipe.md:68` and `track-code-review.md:125` are unchanged.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

All five steps are prose edits to staged workflow copies, idempotent under re-application: each edit either matches its target text or is already applied. First touch of a not-yet-staged file (`review-agent-selection.md`, `step-implementation.md`, `track-code-review.md`, `code-review/SKILL.md`, `code-review-protocol.md`, `execute-tracks/SKILL.md`, `conventions-execution.md`) copies the live file verbatim into the staged subtree, then edits. `risk-tagging.md` is already staged: edit in place, never re-copy. Recovery from a failed step is `git reset --hard HEAD` on the uncommitted staged file, then re-run. No data migration, no runtime state, no test fixtures.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In-scope files (staged copies under `_workflow/staged-workflow/.claude/...`):**
- `.claude/workflow/review-agent-selection.md` — the `## Baseline agents` intro (carve-out) and a new non-mirrored note; explicitly not the `§Maintenance`-mirrored sections.
- `.claude/workflow/step-implementation.md` — sub-step 4a (step-level dispatch), including the "Baseline agents (4)" count at `:426`.
- `.claude/workflow/track-code-review.md` — § Agent selection and launching (`:479`, track-level dispatch). The §Multi-Step Tracks "Baseline agents (4)" at `:125` is track-level and stays unchanged.
- `.claude/workflow/risk-tagging.md` — the `high` quick-ref row only; the rest of this file is Track 1. This file is **already staged** (Track 1 created the staged copy with the `### Workflow machinery` taxonomy), so edit the staged copy in place — locate the `high` row by content, not the live `:65` offset, and never re-copy from live (§1.7(d), DL4/T2).
- `.claude/skills/code-review/SKILL.md` — the baseline/conditional table row for `review-bugs-concurrency`.
- `.claude/workflow/code-review-protocol.md` — the `## For both levels` baseline-count framing (`:32`), made level-aware (Phase A scope addition, DL1).
- `.claude/skills/execute-tracks/SKILL.md` — the step-level dimensional-review-loop description (`:109`), corrected to the step-level set (Phase A scope addition, DL1).
- `.claude/workflow/conventions-execution.md` — the two-tier code-review pool summary (`:401`), clarified as a cross-tier inventory (Phase A scope addition, DL1).

**Out-of-scope (owned by Track 1 or deliberately not edited):**
- `track-review.md`, `conventions.md §1.1`, and the `risk-tagging.md` HIGH/MEDIUM/LOW criteria + `~5` MEDIUM clause — all Track 1.
- The `review-agent-selection.md` `§Maintenance`-mirrored sections (`§Workflow-review agents`, `§Workflow-machinery file set`, `§Per-agent file-pattern triggers`, `§Workflow-machinery override`) — must not carry the step/track timing.
- The `step-implementation.md` high-only step-review gate and session-end context gate — load-bearing guardrails cited for context, not edited.

**Dependencies:**
- **Upstream:** depends on Track 1's `### Workflow machinery` risk taxonomy (D6) — the workflow-reviewer triage (D5) has no trigger without it. Track 2 follows Track 1.
- **Cross-track file:** the `risk-tagging.md` `high` quick-ref row (`:65`) is disjoint from Track 1's edits to the same file; the staged copy accumulates both, and each track's Phase C review delta-scopes to its own sections.

**Staging contract:** workflow-modifying marker present in `implementation-plan.md` § Constraints; writes route to the staged subtree; the staged-vs-live delta gets the Phase C `§1.7(h)` review, delta-scoped to the live-vs-staged diff (D5 convention), not the whole-file staged copy.
