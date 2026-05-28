<!-- workflow-sha: 367f5f83f1bce0e98eaeb0679973f9728db64b61 -->
# Track 4: Universal annotation rollout (49 files)

## Purpose / Big Picture

After this track lands, every in-scope workflow doc and skill file carries a TOC region and per-section annotations. The reindex script's `--check` passes cleanly across the full file set.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Author per-section TOC + annotations for every in-scope file: 31 under `.claude/workflow/`, 11 under `.claude/workflow/prompts/`, and 7 workflow-referencing skill files. ~600 annotations, all author-written. Run `workflow-reindex.py --write` to scaffold TOC tables, then hand-correct per-section `roles=`, `phases=`, `summary=`. Land in a single logical batch so the schema becomes universally applicable on one commit (or a small adjacent group; squash-merge collapses anyway).

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

The file set as enumerated during Phase 0 research:

**`.claude/workflow/` root (31 files):**
branch-divergence-check.md, code-review-protocol.md, commit-conventions.md, conventions-execution.md, conventions.md, defensive-push-check.md, design-decision-escalation.md, design-document-rules.md, ephemeral-identifier-rule.md, episode-format-reference.md, finding-synthesis-recipe.md, implementation-review.md, implementer-rules.md, inline-replanning.md, mid-phase-handoff.md, planning.md, plan-slim-rendering.md, research.md, review-agent-selection.md, review-iteration.md, review-mode.md, risk-tagging.md, self-improvement-reflection.md, step-implementation.md, step-implementation-recovery.md, structural-review.md, track-code-review.md, track-review.md, track-skip.md, workflow-drift-check.md, workflow.md.

**`.claude/workflow/prompts/` (11 files):**
adversarial-review.md, consistency-gate-verification.md, consistency-review.md, create-final-design.md, design-review.md, dimensional-review-gate-check.md, review-gate-verification.md, risk-review.md, structural-gate-verification.md, structural-review.md, technical-review.md.

**Workflow-referencing skills (7 files):**
- `.claude/skills/create-plan/SKILL.md` (17 workflow-doc refs)
- `.claude/skills/execute-tracks/SKILL.md` (17 refs)
- `.claude/skills/edit-design/SKILL.md` (14 refs)
- `.claude/skills/migrate-workflow/SKILL.md` (18 refs)
- `.claude/skills/review-workflow-pr/SKILL.md` (8 refs)
- `.claude/skills/review-plan/SKILL.md` (7 refs)
- `.claude/skills/code-review/SKILL.md` (3 refs; borderline)

All 49 files route through §1.7 staging because they're under `.claude/workflow/**` or `.claude/skills/**`.

Section counts vary widely. `conventions.md` has ~10 H2 sections plus many H3s; smaller files like `track-skip.md` may have 2–3 H2s. Every `##` and every `###` heading carries an annotation per the locked density rule (no author-judged granularity; the bootstrap-block heading is the sole literal-heading exception). Annotation count estimate (~600) is the upper bound from per-file heading inventories.

### Files in scope

All 49 enumerated above. Staged copies under `_workflow/staged-workflow/.claude/workflow/**` and `_workflow/staged-workflow/.claude/skills/**`.

### Files out of scope

- `.claude/agents/**` — refs-only sweep, no per-section annotations. Track 5 territory.
- `CLAUDE.md` — general-purpose project guide, not workflow-specific. Out of scope for this plan; see Non-Goals.
- `.claude/scripts/**` — the scripts themselves are not in-scope for annotations.

## Plan of Work

The track lands in six steps grouped by file batch:

1. **Workflow root, first half (~15 files).** Annotate the largest / most-referenced files first: `conventions.md`, `workflow.md`, `step-implementation.md`, `track-code-review.md`, `conventions-execution.md`, `track-review.md`, `implementer-rules.md`, `design-document-rules.md`, `planning.md`, `review-iteration.md`, `self-improvement-reflection.md`, `implementation-review.md`, `workflow-drift-check.md`, `structural-review.md`, `plan-slim-rendering.md`. For each: run `workflow-reindex.py --write` to scaffold the TOC, then author per-section `roles=`, `phases=`, `summary=` decisions.
2. **Workflow root, second half (~15 files).** Annotate the remaining 15 root files. Same procedure.
3. **Prompts (11 files).** Annotate every prompt under `.claude/workflow/prompts/`. Prompts are typically shorter than rules; faster batch.
4. **Skills (7 files).** Annotate the 7 workflow-referencing skill files. Skill files have a specific shape (frontmatter + skill prose); the annotation idiom applies after the frontmatter block.
5. **Validation pass.** Run `python3 .claude/scripts/workflow-reindex.py --check` against the full file set. Fix any findings (mostly enum-token typos, TOC drift). Iterate until clean.
6. **House-style sweep.** Run a final pass against the annotation summary text — every `summary="..."` must follow house style (no banned vocabulary). Re-run the reindex check; close the track.

The annotation work is repetitive but author-driven. Each section needs:
- `roles=` — which agent types load this section.
- `phases=` — which workflow phases pull this section.
- `summary="..."` — one-line description, ≤120 chars, house-style compliant.

Phase A may split this track if step count grows past 7 — splitting at the natural boundary (root vs. prompts vs. skills) makes the most sense.

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

After this track lands:

- Every in-scope file (49 total) carries a TOC region directly under H1.
- Every `^##` heading in every in-scope file is followed by an annotation comment.
- `python3 .claude/scripts/workflow-reindex.py --check` exits 0 across the full file set.
- Annotation summary text passes house-style review (no banned vocabulary, ≤120 chars, plain prose).
- TOC tables match the per-section annotations 1:1 (rebuildable by `--write` with no diff).

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

### In-scope file set

All 49 files enumerated above. Staged copies under `_workflow/staged-workflow/`.

### Out-of-scope

- `.claude/agents/**` — Track 5.
- `CLAUDE.md` — out of scope (general-purpose, not workflow-specific).
- `.claude/scripts/**` — not annotated.

### Inter-track dependencies

- **Depends on Track 1.** The schema in `conventions.md §1.8` must exist before authors can write annotations to it.
- **Depends on Track 2.** The reindex script must exist for `--write` scaffolding and `--check` validation.
- **Depends on Track 3** in execution order (not structurally). Track 3 lands first so `prompts/create-final-design.md` already carries the telemetry-invocation block when Track 4 annotates it.
- **Unblocks Track 5.** Cross-reference suffixes in the 20 agent files point AT files whose role/phase tags exist; while the suffix is technically forward-resolvable, landing Track 4 first keeps the schema's surface coherent at every commit.

### Library/function signatures touched

None — this track is per-section annotation authoring in Markdown files. No code paths change.
