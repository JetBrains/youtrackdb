<!--MANIFEST
review: workflow-instruction-completeness
track: 1
iteration: 1
level: high
findings: 0
index: []
evidence_base: C1-C6
cert_index: [C1, C2, C3, C4, C5, C6]
flags: []
-->

# Workflow instruction-completeness review — Track 1, iteration 1

## Findings

No procedural-completeness defects survived verification within the delta's scope. The changed Phase-4 cleanup instruction has an unambiguous execution order, preserves the single-cleanup-commit contract, carries an accurate `-f` / `rm -rf` rationale, and introduces no dangling reference, gate without a resume path, or claim contradicted elsewhere in the procedure.

## Evidence base

The delta is a ~15-line prose edit that turns the bare `git rm -r docs/adr/<dir-name>/_workflow/` into `git rm -rf …` plus a follow-up `rm -rf …` at two operative sites (`create-final-design.md` § Step 6, `workflow.md` § Final Artifacts) and reconciles four descriptive mentions. Each completeness check below is a candidate defect that did not survive verification.

#### C1 — Execution order of the new two-command sequence — REFUTED (order unambiguous)
Checked whether the new `git rm -rf` → `rm -rf` sequence has a defined order. In `create-final-design.md` § Step 6 the fenced block reads `git rm -rf …` / `rm -rf …` / `git commit -m "Remove workflow scaffolding"` / `git push` (lines 612-615), a strict top-to-bottom order. `workflow.md` item 3 states "`git rm -rf …` followed by `rm -rf …`" then "Commit … Push" (lines 764-778). `git rm -rf` stages the tracked-file deletions and force-removes modified working-tree copies; the follow-up `rm -rf` clears the untracked remnants that were never in the index. The order is correct and each command's role is stated. No ambiguity.

#### C2 — Single-cleanup-commit contract still holds — REFUTED (no second commit introduced)
Checked whether the added `rm -rf` introduces a second commit or a missing "commit before/after" step. In `create-final-design.md` § Step 6 both deletions precede the single `git commit` in the same fenced block; the untracked files `rm -rf` removes are not in the index, so they add nothing to stage. `workflow.md` item 3 states explicitly "Both deletions run inside this one step, so the cleanup stays a single commit" (lines 776-778) and "This commit runs for **every** change." The contract the track (D1 Risks, Invariants) commits to preserve is intact.

#### C3 — `-f` / `rm -rf` rationale accuracy — REFUTED (matches the empirical repro)
Checked whether the inline rationale correctly attributes which files each command reaches. The comment (`create-final-design.md` lines 609-611; `workflow.md` lines 769-775) states `-f` force-deletes the tracked-but-modified `design-mutations.md` and `rm -rf` clears the untracked cold-read output, per-round params, and `.pyc` remnants `git rm` never reaches. This matches the git repro recorded in the track file's `## Context and Orientation` (line 57): `git rm -r` aborts on the modified tracked file; `git rm -rf` succeeds but leaves the untracked sibling; a follow-up `rm -rf` clears it. Attribution accurate.

#### C4 — "sweeps automatically" prose no longer contradicts the command — REFUTED (prose reconciled)
Checked whether any descriptive site still claims the recursive `git rm` sweeps untracked files after the fix. `create-final-design.md:621-623`, `workflow.md:773-775`, and `conventions-execution.md:372-377` now split the sweep in two: `git rm -rf` sweeps the tracked files, `rm -rf` clears the untracked remnants. No site retains the buggy single-command untracked-sweep claim; no internal contradiction remains (the risk D2 was written to close).

#### C5 — No dangling reference introduced by the edit — REFUTED (forward-pointers stay accurate)
Checked whether the edit strands any cross-reference. `conventions-execution.md:383-385` ("the Phase 4 cleanup is the blanket recursive `rm` above") reads consistently with the reconciled `git rm -rf` + `rm -rf` at lines 372-373 directly above it. `mid-phase-handoff.md:493` still names "Step 6 of `create-final-design.md`", which matches the current Step 6 header. The benign forward-pointers (`workflow.md:757` "the cleanup `git rm` below deletes the log") remain accurate — the log is still removed by the cleanup step. No reference dangles.

#### C6 — Step-6 re-run / idempotency gap is pre-existing, not delta-introduced — REFUTED (out of scope; not worsened)
Checked whether the edit introduces a partial-completion re-run hazard for Step 6 (e.g., re-entering Phase 4 after `_workflow/` was already committed-deleted, where `git rm -rf` exits 1 on a missing pathspec and `git commit` errors on an empty index). Compared against `develop`: the pre-change command was `git rm -r …` + `git commit`, which already errored identically on a missing pathspec, so no re-run guard existed before this change. The delta does not worsen the leading command's re-run behavior and in fact adds one idempotent line (`rm -rf` on a missing path is a silent no-op). The gap is a pre-existing property of the cleanup step, outside this delta's scope, and is not flagged.
