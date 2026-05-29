<!-- workflow-sha: 367f5f83f1bce0e98eaeb0679973f9728db64b61 -->
# Track 4: Universal annotation rollout (49 files)

## Purpose / Big Picture

After this track lands, every in-scope workflow doc and skill file carries a TOC region and per-section annotations. The reindex script's `--check` passes cleanly across the full file set.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Author per-section TOC + annotations for every in-scope file: 31 under `.claude/workflow/`, 11 under `.claude/workflow/prompts/`, and 7 workflow-referencing skill files. ~600 annotations, all author-written. Run `workflow-reindex.py --write` to scaffold TOC tables, then hand-correct per-section `roles=`, `phases=`, `summary=`. Land in a single logical batch so the schema becomes universally applicable on one commit (or a small adjacent group; squash-merge collapses anyway).

## Progress
- [x] 2026-05-29T03:51Z [ctx=warning] Review + decomposition complete (Technical + Risk + Adversarial reviews PASS iter-1; 6 steps decomposed; 2 design decisions resolved)
- [ ] Step implementation
- [x] 2026-05-29T04:50Z [ctx=info] Step 1 complete (commit 39d517c5c8) — dim review PASS iter-2 (5 agents; WC1/WI1 blocker fixed + 4 lower findings)
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- 2026-05-29T04:50Z Step 1 discovered: `design.md` still anchors the TOC "directly under the H1" at lines 20, 75, 285, which drifts from the implemented three-shape anchor schema — carry to Phase 4 design-synthesis (`design-final.md`) alongside the Track 3 design.md drift items. See Episodes §Step 1.
- 2026-05-29T04:50Z Step 1 discovered: the gap-scan accepts a non-fenced bootstrap block plus blank lines before the TOC delimiter under all three anchor shapes, so Track 5 can place `## Reading workflow files (TOC protocol)` above the TOC region on H1, after-frontmatter, and top-of-file files alike. See Episodes §Step 1.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

- **(Phase A) Fence-fix scope kept in Track 4.** The Phase A reviews ran `workflow-reindex.py` and found the parser counts fenced `##`/`###` headings and TOC delimiters as real (rules 2/3/4 ignore `compute_fenced_lines`; ~156 fenced headings in 20 files). The approved WB1 plan note ("rule_4 carve-out in `create-final-design.md`") was both mis-scoped (rules 2/3/4, not rule_4; 20 files, not 1) and mis-sequenced (prerequisite for Step 1, since `conventions.md` is annotated first). User chose to keep the widened fix in Track 4 as a front-loaded HIGH-risk step rather than ESCALATE to a Track 2 reopen. The `## 99.1 Demo section` annotate-vs-carve-out question resolves to carve-out, handled by the same parser fix.
- **(Phase A) H1-less skill files: TOC after frontmatter.** 5 of the 7 in-scope skill files (`edit-design`, `migrate-workflow`, `code-review`, `review-workflow-pr`, `review-plan`) have real `##` sections but no document H1, which §1.8(d)'s "directly under H1" rule did not anticipate. User chose to anchor the TOC immediately after the frontmatter block (over adding an H1 title or dropping skills to refs-only). Step 1 amends §1.8(d) + rule_2 accordingly; Track 5 inserts the bootstrap block above the TOC.
- **(Phase A) `--write` does not scaffold TOC regions.** Confirmed by reading `_rebuild_toc_region`: it rebuilds an existing delimiter pair, returning None when none exists. The per-file procedure now hand-places delimiters first, then runs `--write` to fill the table and auto-stamp in-file refs.
- **(Step 1, scope-refinement) Third TOC anchor shape added — top-of-file.** Dimensional review found 10 of the 11 prompts are prose-first (no real H1, no frontmatter), a shape the step's two-anchor plan did not cover and rule_2 silently accepted. The chosen convention extends the Phase A "anchor at the natural structural top" decision: such files anchor the TOC at the top of the file (delimiter as first content). §1.8(d) and rule_2 now enforce all three shapes (under-H1 / after-frontmatter / top-of-file) with stated H1-over-frontmatter-over-top precedence. See Episodes §Step 1.

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 1 (7 findings — 3 blocker, 3 should-fix, 1 suggestion; all accepted and folded into Step 1 + the corrected procedure/decomposition).
- [x] Risk: PASS at iteration 1 (5 findings — 0 blocker, 3 should-fix, 2 suggestion; all accepted, mostly into Idempotence and the per-batch `--check` rule).
- [x] Adversarial: PASS at iteration 1 (5 findings — 1 blocker, 2 should-fix, 2 suggestion; all accepted; file counts 31+11+7=49 verified exact).

All three reviews converged on one root cause: the reindex parser counts fenced headings/delimiters as real, so the track cannot reach a green `--check` by authoring alone. Findings were concrete, script-verified facts (re-confirmed directly against staged `conventions.md` §1.8 and the skill-file H1 reality), so they were accepted wholesale and incorporated into the decomposition rather than re-litigated across iterations; the corrected track file is the durable trace. Two findings needed user decisions (fence-fix scope, H1-less TOC placement) — resolved in the Decision Log above. Carried to Track 5: the H1-adjacency hand-off (Track 5's bootstrap block sits above Track 4's after-frontmatter TOC in H1-less files). Carried to Phase C: annotation semantic accuracy (roles/phases/summary) on Steps 2-6 is the focal-point concern `--check` cannot fully validate.

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

Phase A revised the original six-batch plan after the track reviews ran `workflow-reindex.py` against the staged files and found that its parser treats `##`/`###` headings and `<!--Document index…-->` delimiters **inside fenced code blocks** as real (rules 2/3/4 never consult `compute_fenced_lines`; only rules 6/8 do). About 156 fenced headings span 20 of the 49 in-scope files — including `conventions.md` (annotated first) and 5 skill files — so a green `--check` is unreachable by authoring alone. The fence-exclusion fix in the reindex script, and the §1.8 schema clarifications it rests on, become Step 1 rather than a final cleanup. This reopens Track 2's script and Track 1's schema (the prior "No code paths change" framing no longer holds; see `## Interfaces and Dependencies`).

**Corrected per-file procedure.** `--write` rebuilds an existing TOC region; it does not create one. For each file: (1) hand-place the `<!--Document index start-->` / `<!--Document index end-->` delimiter pair at the file's TOC anchor — directly under H1 for workflow docs and prompts, immediately after the frontmatter block for H1-less skill files per the §1.8(d) after-frontmatter rule Step 1 adds; (2) hand-write each `##`/`###` section's annotation comment; (3) run `workflow-reindex.py --write` to populate the TOC table body and auto-stamp in-file `§X.Y(z)` refs (rule 8); (4) run `workflow-reindex.py --check --files <batch>` for incremental signal. Files with no real (non-fenced) `##` headings carry no TOC region per §1.8(d) — `create-plan` and `execute-tracks` SKILL.md after fence exclusion.

**The `--no-verify` window.** The reindex pre-commit hook stays RED across the un-migrated tree until the rollout completes, so intermediate step commits use `git commit --no-verify`. Because `--no-verify` bypasses every pre-commit gate (not only reindex), each step runs `--check --files <batch>` manually before committing to recover per-batch signal; Step 6 is the single full-set green `--check`.

**Cross-ref ordering.** Rule 6 (cross-file subset) and rule 8 (in-file auto-stamp) both need the target's annotation to exist, so refs from an early batch to a not-yet-annotated target fail mid-rollout and clear only at Step 6's green run. The batches front-load the most-referenced targets (`conventions.md`, `workflow.md`) to keep the dangling-ref window small.

Step sequencing (full roster in `## Concrete Steps`):
- Step 1 (HIGH) is the parser + schema prerequisite and must land before any annotation step.
- Steps 2-3 split the 31 workflow-root files by heading count, not file count (`conventions.md` alone carries ~54 headings), so each batch is a comparable hand-authoring and review unit.
- Step 4 covers the 11 prompts, including the already-staged `create-final-design.md`.
- Step 5 covers the 7 skill files (after-frontmatter TOC for the 5 H1-less ones; no TOC for the 2 with no real headings).
- Step 6 is the full-set `--check` green run plus the house-style sweep on every `summary="..."`.

Each annotated section needs: `roles=` (which agent types load it), `phases=` (which workflow phases pull it), `summary="..."` (≤120 chars, house-style compliant).

## Concrete Steps
1. Fence-exclusion in the reindex parser + §1.8 schema correctness: thread `compute_fenced_lines` into `parse_headings`, `parse_toc_region`, and the rule_2 delimiter count so rules 2/3/4 skip headings and TOC delimiters inside fenced blocks; add §1.8(e) prose stating fenced headings and TOC delimiters are excluded (today only refs are); add the §1.8(d) after-frontmatter TOC-anchor rule for H1-less files plus the matching rule_2 anchor logic; regression tests under `.claude/scripts/tests/`. Reopens `workflow-reindex.py` (live path) and staged `conventions.md` §1.8. — risk: high (architecture: reopens the reindex validation gate + the §1.8 schema that all of Track 4 and every future workflow-modifying branch depend on; a parser bug silently passes wrong annotations through CI)  [x] commit: 39d517c5c8
2. Annotate workflow-root batch 1, heading-count-balanced and anchored by `conventions.md` + the next most-referenced/largest root files. Hand-place TOC delimiters + per-section annotations → `--write` (fills TOC, auto-stamps in-file refs) → `--check --files <batch>` → commit `--no-verify`. — risk: medium (annotation semantic accuracy: roles/phases assignment and summary house-style are judgment calls `--check` cannot fully validate; Phase C focal point)  [ ]
3. Annotate workflow-root batch 2 (remaining root files). Same procedure. — risk: medium (annotation semantic accuracy; Phase C focal point)  [ ]
4. Annotate the 11 prompts under `.claude/workflow/prompts/`, including staged `create-final-design.md` (whose fenced `adr.md`-template headings Step 1's fix now skips). Same procedure. — risk: medium (annotation semantic accuracy; Phase C focal point)  [ ]
5. Annotate the 7 workflow-referencing skill files: after-frontmatter TOC for the 5 H1-less ones (`edit-design`, `migrate-workflow`, `code-review`, `review-workflow-pr`, `review-plan`); no TOC region for the 2 with no real headings (`create-plan`, `execute-tracks`). Same procedure. — risk: medium (annotation semantic accuracy + first exercise of the after-frontmatter TOC anchor; Phase C focal point)  [ ]
6. Full-set `workflow-reindex.py --check` green run across all 49 files plus a house-style sweep on every `summary="..."`; fix residual enum/TOC/cross-ref findings; confirm a final clean `--check`. — risk: medium (cross-cutting verification gate + house-style judgment; Phase C focal point)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit 39d517c5c8, 2026-05-29T04:50Z [ctx=info]
**What was done:** Threaded `compute_fenced_lines` into `parse_headings`, `parse_toc_region`, the rule_2 delimiter count, and `find_first_h1_line`, so rules 2/3/4 and `--write` skip `##`/`###` headings and `<!--Document index ...-->` delimiters inside fenced code blocks. Added the §1.8(d) after-frontmatter TOC anchor for H1-less skill files plus the matching rule_2 anchor logic (`find_first_h1_line` + `find_frontmatter_close_line`). Added §1.8(e) fenced-content-exclusion prose to staged `conventions.md`. The dimensional review (5 workflow-review agents, PASS at iteration 2) drove a `Review fix:` commit `39d517c5c8` that closed the WC1/WI1 blocker and four lower findings. Final state: 94/94 reindex tests pass.

**What was discovered:** The 11 prompt files split three ways by TOC anchor, not the two the step anticipated. Only `design-review.md` carries a real H1; the other 10 are prose-first with no H1 and no YAML frontmatter (including `create-final-design.md`, whose only `# ` is the fenced `adr.md`-template heading the new fence-aware `find_first_h1_line` correctly skips). rule_2's third branch had silently accepted any TOC placement for that shape, so `--check` could not have guarded Step 4's placement of those TOCs. The fix added a third anchor shape — top-of-file — to both §1.8(d) and rule_2, with the under-H1 and top-of-file branches sharing one gap-scan code path (`gap_start_idx = 0` vs the H1 line) so bootstrap-block tolerance and fence skipping apply uniformly. Separately, `design.md` still describes the TOC as anchored "directly under the H1" at three sites (lines 20, 75, 285), which now drifts from the implemented three-shape schema — a Phase 4 design-synthesis reconciliation item.

**What changed from the plan:** Step 1's scope grew by one anchor shape. The step description named only the H1-less-with-frontmatter case (the 5 skill files); the prose-first-no-frontmatter case surfaced during dimensional review and was folded into the same step. This sharpens the anchor placement Steps 4 and 5 must follow: TOC under the H1 for workflow docs and `design-review.md`, after the frontmatter close for the 5 H1-less skill files, and at the top of the file for the other 10 prompts.

**Key files:**
- `.claude/scripts/workflow-reindex.py` (modified)
- `.claude/scripts/tests/test_workflow_reindex.py` (modified)
- `docs/adr/ytdb-1023-workflow-toc/_workflow/staged-workflow/.claude/workflow/conventions.md` (modified, §1.8(d)/(e))

**Critical context:** rule_2 now enforces TOC placement positionally (previously it did no positional check for the third shape), so every annotation step must place each file's TOC at its correct anchor or rule_2 fires. Initial commit `8bcb1792f3`; review-fix commit `39d517c5c8`.

## Validation and Acceptance

After this track lands:

- Every in-scope file with at least one real (non-fenced) `^##` heading carries exactly one TOC region — directly under H1 for files that have one, immediately after the frontmatter block for the H1-less skill files. Files with no real `^##` headings carry no TOC region (§1.8(d)).
- Every real (non-fenced) `^##`/`^###` heading in every in-scope file is followed by an annotation comment; headings inside fenced code blocks are not annotated and not counted.
- `python3 .claude/scripts/workflow-reindex.py --check` exits 0 across the full file set, with no false positives on fenced headings/delimiters (the `## 99.1 Demo section` worked example and the `create-final-design.md` `adr.md`-template headings are skipped, not annotated).
- Step 1's regression tests pass: a fenced `## Heading` (both ``` and `~~~`) yields no rule_2/3/4 finding and no `--write` TOC row, a real heading still does, and an H1-less file with an after-frontmatter TOC validates.
- Annotation summary text passes house-style review (no banned vocabulary, ≤120 chars, plain prose).
- TOC tables match the per-section annotations 1:1 (rebuildable by `--write` with no diff).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

- **Step 1 must precede every annotation step.** Resuming into Step 2+ presupposes the fence-exclusion + H1-less-anchor fix has landed; running an annotation batch's `--check` against the un-patched script produces spurious rule_2/3/4 findings. A resume that finds Step 1 incomplete restarts from Step 1.
- **Each step is one commit and independently revertable.** `git revert <step-commit>` rolls a batch back without disturbing earlier batches; re-running the batch reproduces the same staged content.
- **`--write` is idempotent.** Re-running it on an already-annotated file rebuilds the same TOC table and re-stamps the same in-file refs (no diff on a clean file), so resuming a partially-done batch is safe.
- **Staging isolation bounds partial-completion damage.** Annotation edits land under `_workflow/staged-workflow/`; the live `.claude/workflow/**` paths stay at develop state until Phase 4 promotion, so a half-annotated staged tree cannot break the live workflow machinery the branch itself runs on. The one live-path change is Step 1's `workflow-reindex.py` edit, guarded by its regression tests.
- **Mid-rollout `--check` is expected RED.** Cross-file (rule 6) and in-file (rule 8) refs to not-yet-annotated targets fail until Step 6's full-set run; this is the normal interim state, not a recovery trigger.

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

- **Depends on Track 1, and reopens it.** The schema in `conventions.md §1.8` must exist before authors can write annotations to it; Step 1 additionally amends §1.8(d) (after-frontmatter TOC anchor for H1-less files) and §1.8(e) (fenced headings/delimiters excluded).
- **Depends on Track 2, and reopens it.** The reindex script must exist for `--write` and `--check`; Step 1 fixes its fenced-heading/delimiter parsing (rules 2/3/4) and adds the H1-less anchor logic, with regression tests added to Track 2's suite.
- **Depends on Track 3** in execution order (not structurally). Track 3 lands first so `prompts/create-final-design.md` already carries the telemetry-invocation block when Track 4 annotates it.
- **Unblocks Track 5.** Cross-reference suffixes in the 20 agent files point AT files whose role/phase tags exist; while the suffix is technically forward-resolvable, landing Track 4 first keeps the schema's surface coherent at every commit.

### Library/function signatures touched

Step 1 edits `.claude/scripts/workflow-reindex.py` (a live-path change, not §1.7 staging): it threads `compute_fenced_lines` into `parse_headings`, `parse_toc_region`, and the rule_2 delimiter count so rules 2/3/4 skip fenced headings/delimiters, and it teaches rule_2 the after-frontmatter TOC anchor for H1-less files. This reopens Track 2's script and its test suite under `.claude/scripts/tests/` (regression tests for both behaviors are required per the track reviews). Step 1 also edits staged `conventions.md` §1.8(d)/(e) — Track 1's schema. Steps 2-6 are per-section annotation authoring in Markdown only; no further code paths change.

## Base commit
5aa5917da7d8a57c78bcf2fe530c58686a07c3e0
