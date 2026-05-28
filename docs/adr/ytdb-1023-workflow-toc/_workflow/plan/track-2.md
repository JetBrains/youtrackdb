<!-- workflow-sha: 367f5f83f1bce0e98eaeb0679973f9728db64b61 -->
# Track 2: Reindex script + CI gate + audit agent updates

## Purpose / Big Picture

After this track lands, `workflow-reindex.py` mechanically validates schema compliance at pre-commit and CI time, and the `review-workflow-context-budget` agent absorbs the qualitative audit at PR review.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Build `.claude/scripts/workflow-reindex.py` (mechanical Python; `--check` and `--write` modes; stdlib only). Wire a pre-commit hook and a GitHub Actions step. Update `.claude/agents/review-workflow-context-budget.md` to absorb the audit. Tests under `.claude/scripts/tests/`.

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

`.claude/scripts/` carries two existing Python tools:

- `design-mechanical-checks.py` — design.md / design-mechanics.md mechanical checks invoked by `edit-design`.
- `render-slim-plan.py` — plan-file slim-rendering for `/execute-tracks` startup.
- `session-stats.py` — statusline cost/token aggregation.

`workflow-reindex.py` joins this set. Same style: stdlib-only Python 3, callable as `python3 .claude/scripts/<script>.py [args]`. Tests live under `.claude/scripts/tests/`.

CI today runs no workflow-doc validation (`find .github -type f | xargs grep -l workflow` returns empty for workflow-doc references). The CI integration in this track is greenfield: either a new GitHub Actions workflow file or a new step in an existing workflow.

Pre-commit hooks: `.githooks/prepare-commit-msg` exists for issue-prefix auto-prepending. `.githooks/pre-commit` already exists as a Spotless runner for staged Java files (gated on the JetBrains/youtrackdb origin). No workflow-file gating runs from it today. This track extends the existing `.githooks/pre-commit` by appending a second block that runs the reindex script on staged workflow files; the Spotless block is preserved.

The `review-workflow-context-budget` agent at `.claude/agents/review-workflow-context-budget.md` is the audit absorber per YTDB-1023. Current agent definition reviews "always-loaded surface, load-on-demand discipline, and instant per-operation consumption". This track extends the agent's responsibilities to include:

- Running the reindex script on workflow-machinery diffs.
- Surfacing script findings as `WB1, WB2, ...` items.

### Files in scope

- `.claude/scripts/workflow-reindex.py` — new file, live path (scripts are not staged per §1.7).
- `.claude/scripts/tests/test_workflow_reindex.py` — new test file, live path.
- `.githooks/pre-commit` — extended (existing Spotless block preserved, workflow-reindex check appended), live path.
- `.github/workflows/<existing or new>.yml` — CI step added, live path.
- `.claude/agents/review-workflow-context-budget.md` — updated, live path (agents are not staged per §1.7).

### Files out of scope

- Schema definition (`conventions.md §1.8`) — Track 1 owns it; this track reads from it.
- Per-section annotation rollout — Track 4 territory.
- Telemetry script — Track 3 territory.

## Plan of Work

The track lands in five steps:

1. **Script core: parsing and discovery.** Implement file enumeration (fixed globs), heading + annotation parsing (regex on `^##`, `^###`, and the line after), TOC region detection (between the delimiter comments). No validation yet; just structured representation of every in-scope file's annotations and TOC.
2. **Validation rules and `--check` mode.** Implement every check from `design.md §"Reindex script" > Validation rules`: stamp present, TOC present, TOC matches annotations (every `^##` and `^###` heading has a TOC row; bootstrap-block heading exempt), annotation present after every `##` and `###` heading (same bootstrap exemption), field well-formedness, enum-token validation (script reads enums from `conventions.md §1.8`), cross-file reference suffix on SKILL.md startup read-lists and agent file refs (hand-written; presence + subset against target annotation per D10 — sub-section refs resolve to that section's annotation, file-level refs resolve to the union of all section annotations), bootstrap-block presence on the 38 in-scope system prompts, in-file `§X.Y(z)` reference suffix validation (auto-stamped; check for unstamped, drifted, and unresolved refs). Exit codes per design.
3. **`--write` mode.** TOC region rebuild from current annotations. Auto-stamp every in-file `§X.Y(z)` reference with the `:roles:phases` suffix derived from the target heading's annotation. Idempotent (running `--write` twice produces no diff after the first run). Does not modify per-section annotations or cross-file `name.md:roles:phases` suffixes. Skips refs inside fenced code blocks. Halts on unresolved in-file refs rather than auto-stamping the wrong heading.
4. **Pre-commit hook + CI wiring.** Pre-commit hook in `.githooks/pre-commit` (callable when user sets `core.hooksPath`). GitHub Actions step calling `python3 .claude/scripts/workflow-reindex.py --check`. Both surface findings with file:line:category format.
5. **Audit agent updates + tests.** Update `.claude/agents/review-workflow-context-budget.md` to invoke the reindex script during workflow-machinery review. Write tests under `.claude/scripts/tests/test_workflow_reindex.py` covering: valid file passes, missing TOC fails, out-of-enum token fails, cross-file ref without suffix fails, cross-file ref with role not in target's annotation fails (subset violation), cross-file ref with phase not in target's annotation fails (subset violation), cross-file file-level ref subset-validates against union of all section annotations, cross-file sub-section ref subset-validates against the specific section's annotation, in-file ref unstamped fails, in-file ref stale-suffix fails (target annotation changed), in-file ref unresolved fails (typo), bootstrap-heading exemption from density rules, `--write` idempotence, `--write` auto-stamps in-file refs from target annotations, `--write` does NOT touch cross-file refs even on subset violation.

The script self-bootstraps from `conventions.md §1.8` for enum values. This means Track 2 cannot complete its first run until Track 1 lands; the script's tests use a fixture conventions.md with the enums to be hermetic.

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

- `python3 .claude/scripts/workflow-reindex.py --check` runs against the schema-only state (Track 1 landed, Track 4 not yet) and exits 0 or 1 deterministically. Exit 1 surfaces specific files-lines-categories.
- Cross-file ref subset violations (citer's roles/phases not in target's current annotation per D10) surface as `cross-file-ref:` category findings with both sides named in the error message; the author hand-edits either the citer or the target to resolve.
- `python3 .claude/scripts/workflow-reindex.py --write` rebuilds TOCs idempotently.
- Pre-commit hook fires on staged workflow files and blocks commits with findings.
- GitHub Actions step fires on PRs touching in-scope files and fails the PR with the same findings.
- `.claude/agents/review-workflow-context-budget.md` carries instructions to invoke the script during workflow-machinery review; the agent emits a per-finding `WB<N>` numeric prefix on script-surfaced items, introduced by this track (D11) under the existing `Critical / Recommended / Minor` severity labels.
- Tests under `.claude/scripts/tests/test_workflow_reindex.py` cover the validation matrix and pass cleanly.

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

- `.claude/scripts/workflow-reindex.py` (new)
- `.claude/scripts/tests/test_workflow_reindex.py` (new)
- `.githooks/pre-commit` (extended)
- `.github/workflows/<existing or new>.yml` (modified)
- `.claude/agents/review-workflow-context-budget.md` (modified)

### Out-of-scope

- `conventions.md §1.8` — Track 1.
- Per-section annotation rollout — Track 4.
- Telemetry script — Track 3.

### Inter-track dependencies

- **Depends on Track 1.** Script reads enum tokens from `conventions.md §1.8`. Tests use a fixture, but the production self-bootstrap path needs §1.8 to exist.
- **Unblocks Track 4.** Annotation rollout uses `--write` to scaffold TOCs and `--check` to validate.
- **Unblocks Track 5.** Cross-reference suffix CI enforcement lives in the script.

### Library/function signatures touched

- `workflow-reindex.py` exports CLI: `--check` (exit 0/1/2), `--write` (in-place TOC rebuild), `--files <space-separated>` (scope to listed files; used by pre-commit hook). No public Python API; all interaction is CLI.
- `review-workflow-context-budget.md` agent system prompt: section added/extended that invokes the script. The agent's output gains a per-finding `WB<N>` prefix introduced by this track (D11), emitted alongside the existing `Critical / Recommended / Minor` severity labels.
