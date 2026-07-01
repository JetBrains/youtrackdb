# Research Log — workflow-scaffolding-fix

## Initial request

Fix YTDB-1180 as the aim of this session.

> YTDB-1180 — Phase-4 cleanup `git rm -r _workflow/` fails on uncommitted
> Phase-4 scaffolding. The documented cleanup command in
> `prompts/create-final-design.md` § Step 6 (`git rm -r docs/adr/<dir>/_workflow/`)
> fails on a full-tier (`design_gate=yes`) Phase 4: the `edit-design`
> `phase4-creation` loop leaves `_workflow/design-mutations.md` locally
> modified (Step-7 review-log append) and leaves per-round params files
> untracked under `_workflow/reviews/` and `_workflow/plan/`. `git rm -r`
> refuses to remove the modified tracked file and silently ignores the
> untracked files, so the cleanup commit cannot run as documented.
> Proposed fix: `git rm -rf` + `rm -rf`, then commit.

Additional aim: find duplicates of YTDB-1180 among issues tagged
`dev-workflow` and close all such duplicate issues (after user sign-off —
closing issues is outward-facing).

## Decision Log

### 2026-07-01 — Bug is live on develop; no partial fix; proceed [ctx=safe] (resolves the open question)
- **Decision:** Proceed with the fix — the bug is present unchanged on this
  branch's HEAD; no partial fix exists on develop.
- **Why:** Verified live this session against `prompts/create-final-design.md:609`
  and `workflow.md:764` (bare `git rm -r`, no `-f`, at both operative sites) and
  the "sweeps automatically" prose at `create-final-design.md:617` /
  `workflow.md:769`. An empirical git repro (modified tracked file + untracked
  sibling under a dir) confirmed the three-part diagnosis: `git rm -r` exits 1
  on the modified file; `git rm -rf` succeeds but leaves the untracked file, so
  a follow-up `rm -rf` is needed.
- **Alternatives rejected:** assume develop already carries a partial fix —
  refuted by direct read of both live sites.

### 2026-07-01 — Keep YTDB-1180 as the fix tracker; close four older duplicates [ctx=safe]
- **Decision:** Treat YTDB-1180 as the canonical issue for this fix; close
  YTDB-868, YTDB-902, YTDB-1055, YTDB-1135 as duplicates of it.
- **Why:** User named YTDB-1180 as the aim, so the PR references it. All five
  describe the identical Phase-4 cleanup failure and the identical fix
  location.
- **Alternatives rejected:** Keep the oldest (YTDB-868) as canonical and close
  the rest — rejected because the user explicitly designated YTDB-1180 as the
  work item; the PR title will carry `[YTDB-1180]`.

### 2026-07-01 — Design gate = no; minimal shape [ctx=safe] (user-confirmed)
- **Decision:** `design_gate=no`, single track, no plan, no design.md (minimal).
  Predicted complexity tag `low`.
- **Why:** Only `Workflow machinery` is touched, and it fires no HIGH
  sub-trigger centrally (no auto-run hook/script/settings, no load-bearing
  gate/control-flow/schema change, not always-loaded). Prose/procedure fix
  with no structural design to work out.
- **Alternatives rejected:** design_gate=yes — overkill for a `git rm` flag
  fix; no design substance to capture.

### 2026-07-01 — §1.7 mode = stage [ctx=safe] (user-confirmed)
- **Decision:** Workflow-modifying, **staged** (not the §1.7(k) opt-out).
  Edits accumulate under `_workflow/staged-workflow/.claude/workflow/**`,
  promoted to live in Phase 4.
- **Why:** `create-final-design.md` §Step 6 and `workflow.md` §Final Artifacts
  are execution procedure a running Phase 4 reads; §1.7(k) criterion 2 keeps
  such files staged even on an otherwise-qualifying plan.
- **Alternatives rejected:** prose-rule opt-out (edit live) — would self-apply
  to this branch, but the files are procedure, not judgment-layer prose, so
  criterion 2 is not met.
- **Self-application gap is absent *for this branch specifically* (the invariant
  the "minor" rests on):** a minimal (`design_gate=no`) branch has **no plan
  file** (so no `[>]`→`[x]` plan-marker flip) and runs **no** `edit-design
  phase4-creation` (so no `design-mutations.md` append and no per-round params
  files), so its own Phase 4 leaves nothing under `_workflow/` for the live
  (buggy) `git rm -r` to choke on. The general staged-mode self-application gap
  still exists for `design_gate=yes` staged branches — a pre-existing property,
  not a regression this fix introduces.

## Surprises & Discoveries

### 2026-07-01 — Duplicate cluster is five issues, not one [ctx=safe]
Five unresolved `dev-workflow` issues report the same bug (found via
`summary: {git rm}` + `summary: cleanup` over the tag — both returned the whole
set with no next page):
- YTDB-868 (2026-05-17, Bug) — narrowest: `design-mutations.md` modification only.
- YTDB-902 (2026-05-20, Feature) — same `design-mutations.md` modification.
- YTDB-1055 (2026-06-02, Bug) — comment adds the untracked cold-read
  `output_path` dimension and names YTDB-868/902 as related duplicates.
- YTDB-1135 (2026-06-16, Bug) — covers `git rm -rf` + follow-up `rm -rf` for
  untracked cold-read output and `.pyc` caches under `staged-workflow/`.
- YTDB-1180 (2026-06-30, Bug) — keeper: modified tracked file + untracked
  params files under `_workflow/reviews/` and `_workflow/plan/`.

### 2026-07-01 — The real fix is wider than YTDB-1180 states alone [ctx=safe]
The union of the five issues defines the complete fix:
1. The cleanup command lives in **two** places, both must change:
   `prompts/create-final-design.md` § Step 6 **and** `workflow.md`
   § Final Artifacts (the mirror). YTDB-868/902 named only the prompt;
   YTDB-1055/1135 name both.
2. `git rm -r` → `git rm -rf` handles **locally-modified tracked** files
   (`design-mutations.md` Step-7 append; `implementation-plan.md` plan-marker
   `[>]`→`[x]` flip).
3. A follow-up `rm -rf docs/adr/<dir>/_workflow/` is needed for **untracked**
   remnants `git rm` cannot reach: the phase4-creation cold-read `output_path`
   file, per-round params files under `_workflow/reviews/` and `_workflow/plan/`,
   and `.pyc` caches under `staged-workflow/`.
Open question: verify against live `create-final-design.md` § Step 6 and
`workflow.md` § Final Artifacts whether the current text already partially
fixes this (some later branch may have edited it).

### 2026-07-01 — Bug confirmed live; full site enumeration [ctx=safe]
Bug is present unchanged on this branch's HEAD. `grep -rn "git rm" .claude/`
gives two **operative** command sites and several **descriptive** mentions:
- **Operative (the command the orchestrator runs):**
  - `prompts/create-final-design.md:609` — `git rm -r docs/adr/<dir-name>/_workflow/`
  - `workflow.md:764` (§ Final Artifacts) — the mirror command.
- **Descriptive / reconcile-for-consistency:**
  - `create-final-design.md:617` and `workflow.md:769` — both assert "the
    recursive `git rm` sweeps the review-file directories automatically."
    True only for **tracked** (committed) review files; false for the
    untracked phase4-creation cold-read output and params files. These
    claims must be corrected or they will contradict the fix.
  - `commit-conventions.md:153` (Phase-4 cleanup table row).
  - `conventions-execution.md:372` and `:747` (blanket-`git rm -r` prose).
  - `mid-phase-handoff.md:493` (descriptive `git rm -r`s mention).
- **Verified-negative (out of scope):** a sixth `git rm -r` match exists at
  `.claude/scripts/tests/fixtures/review-file-valid-strategic.md:33` (my initial
  grep scanned only `.claude/workflow/` and `.claude/skills/`, missing
  `.claude/scripts/`). It is illustrative finding-body text inside a test fixture
  that exercises the count-validation regex, **not** a live cleanup instruction
  or a descriptive claim about the real command — deliberately out of scope, so
  the fix must not touch it. A later Phase-C workflow-consistency reviewer that
  greps `git rm` should not re-flag it.
- Root confirmed: the final-artifacts commit (Step 5) stages **only**
  top-level artifacts and explicitly does NOT stage anything under
  `_workflow/`, so the edit-design Step-7 `design-mutations.md` append (and
  the plan `[>]`→`[x]` marker flip) are tracked-but-modified when Step 6's
  bare `git rm -r` runs → abort. Untracked cold-read/params/`.pyc` files
  then survive the `git rm`.

### 2026-07-01 — §1.7 staging vs opt-out is a real tension here [ctx=safe]
This branch edits `.claude/workflow/**` (workflow-modifying). The prose-only
opt-out (§1.7(k)) needs every edited file's consumer to be judgment-layer;
`create-final-design.md` § Step 6 and `workflow.md` § Final Artifacts are
**executable procedure** a running Phase 4 reads, so by the literal criterion
they stay staged → the branch would NOT qualify for the opt-out. But staging
means this branch's OWN Phase 4 runs the live (buggy) `git rm -r`, so the fix
can't self-apply. This is a classification decision for Step 4 (staging vs
opt-out), to raise with the user — not resolved in research.

## Open Questions

### 2026-07-01 — Does current develop already carry a partial fix? [ctx=safe] — RESOLVED
**Resolved** into the Decision Log ("Bug is live on develop; no partial fix;
proceed"): verified live against both operative sites and the "sweeps
automatically" prose — bug present unchanged, no partial fix on develop.

## Baseline and re-validation

This is a workflow-modifying branch (staged mode, §1.7): the fix edits
`.claude/workflow/prompts/create-final-design.md` and `.claude/workflow/workflow.md`
(operative command sites) plus descriptive mentions in `commit-conventions.md`,
`conventions-execution.md`, and `mid-phase-handoff.md`. Baseline = develop at
branch HEAD; live `.claude/workflow/**` stays at develop state through Phase B/C
while edits accumulate under `_workflow/staged-workflow/`, promoted in Phase 4.
Re-validate the five edit sites against develop after any rebase before promotion.

## Adversarial gate record

### Adversarial review of this log (2026-07-01) — NEEDS REVISION: 0 blocker, 1 should-fix, 2 suggestion
See `reviews/research-log-adversarial-iter1.md`. A1 (should-fix, open question
vs. surprise contradiction) and A2/A3 (suggestions) addressed in the Decision
Log, the §1.7 rationale, and the enumeration surprise; re-challenging at iter2.
