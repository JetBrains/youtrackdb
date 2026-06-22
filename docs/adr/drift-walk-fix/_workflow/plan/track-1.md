<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->
# Track 1: Phase-4-active drift skip (#2) in detect_drift

## Purpose / Big Picture
A session that starts a branch at Phase 4 no longer sees a spurious workflow-drift
prompt — the startup precheck folds a Phase-4-active branch to "no drift" instead
of offering a migration that would derail the final cleanup.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

The startup precheck's `detect_drift` (in
`.claude/scripts/workflow-startup-precheck.sh`) walks the active plan's `_workflow/`
artifacts and decides whether any workflow commits have landed past the stamp those
artifacts carry. `workflow-drift-check.md § Skip conditions` lists three silent-skip
conditions that should short-circuit the walk to `drift.detected=false` before any
prompt fires; skip #2 ("Phase 4 active") is the one `detect_drift` never implemented.
Consider a Phase-4 branch — its phase-ledger tail is `D` or `Done`. Say it carries at
least one workflow commit past the stamp base — the workflow-format commit its
`_workflow/` artifacts are stamped against. With skip #2 missing, the walk runs to
its last exit, the range `git log`, which reports a non-empty commit range. That
exit emits `drift.detected=true, kind=stamped`. `workflow-drift-check.md § Skip
conditions` forbids exactly that pairing alongside `state.phase=D`. The startup
dispatch then routes that to the Migrate/Defer/Suppress prompt, and a user who picks
"Migrate now"
runs a migration against a `_workflow/` subtree the next Phase-4 cleanup commit
deletes. This track adds the missing skip: `detect_drift` reads the phase-ledger
tail and, when it is `D` or `Done`, folds to `drift.detected=false` and returns. Two
regression tests pin the fix and the skip-ordering invariant it depends on.

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
<!-- The track-canonical live decision carrier (D7). Phase 1 seeds the full
inline Decision Records this track owns (full four-bullet form below); the
section then continues as the execution-time continuous log. Seeded from the
research log in `minimal`. One block per decision. -->

### D1: Skip-#2 placement and emitted scalars in `detect_drift`
Place the Phase-4-active skip in `detect_drift` immediately after the empty-input
no-drift return (`workflow-startup-precheck.sh:618`) and before the unstamped
short-circuit (`:620`). The new block calls `ledger_tail_value phase`; when the
resolved tail is `D` or `Done` it sets `DRIFT_DETECTED=false`, `DRIFT_KIND="stamped"`,
and returns, leaving `DRIFT_BASE_SHA`/`DRIFT_COMMIT_COUNT` at their `""`→null
defaults and `DRIFT_FIRST_COMMITS_JSON` at its global `[]`.
- **Alternatives considered**:
  - Place the skip at the non-empty-range detected point (`:701`). Rejected. It
    covers only the stamped case. It misses the unstamped and merge-base-failed
    Phase-4 cases. It still runs the wasteful fold and no-drift normalization before
    short-circuiting.
  - Place it before the empty-input check (`:612`). Rejected. It reorders the doc's
    cheapest-first skip sequence (#1 empty-input < #2 phase-4 < #3 empty-range). It
    reads the ledger even when no stampable artifact is on disk.
- **Rationale**: the chosen point matches the doc's cheapest-first skip order and
  its unqualified "Phase 4 active → fold to `detected=false`". Sitting after the
  empty-input return and before the fold, it covers every Phase-4 case that reaches
  it — stamped, unstamped, and merge-base-failed alike — and short-circuits before
  the no-drift normalization, so no spurious `Normalize workflow-sha stamps` commit
  lands on a subtree the next cleanup deletes. It is also the issue's literal
  proposed fix (set `kind="stamped"`, return).
- **Risks/Caveats**: the load-bearing property — drift is never flagged at Phase 4 —
  holds for every Phase-4 case (all yield `detected=false`), but the emitted `kind`
  is not uniform. Skip #1 handles the empty-input case: a Phase-4 branch with no
  stampable artifact (only a transient `handoff-*.md`) returns there
  (`detected=false, kind=null`) before reaching skip #2. Skip #2 handles the rest: the
  stamped, unstamped, and merge-base-failed Phase-4 cases all return at skip #2
  (`detected=false, kind="stamped"`). Both are silent no-drift, so the startup
  gate routes identically and the `kind` difference is informational only. D4 pins
  both arms.
- **Implemented in**: this track.

### D2: Change tier = `minimal`
Gate 1 (does the change need a `design.md`?) = no; Gate 2 (multi-track?) = single
track. The change is one bash function edit plus one regression test (D4 splits
that into two cheap test methods).
- **Alternatives considered**: `lite` or `full` — rejected: there is no second track
  and no design surface to justify the extra artifacts. Gate 1 stays `no` even
  though the change lives in workflow machinery — `risk-tagging.md § Gate 1` holds
  that touching a HIGH-risk category is not the central involvement that would
  require a design.
- **Rationale**: a localized, well-understood bug fix in one function of one script,
  pinned by a single regression test — no HIGH-risk category is central. Matches the
  issue's own cost-benefit gate (a script-only fix scoped to one function in one
  script). The adversarial lens for an otherwise lens-free Gate-1-no change is
  `Workflow machinery` (rule coherence, instruction completeness, doc↔script
  consistency), because the bug was a script-vs-doc disagreement, so doc↔script
  coherence is this change's central risk.
- **Risks/Caveats**: none beyond the usual minimal-tier trade-off — no frozen
  `design.md` seed, so the track file is the sole durable design record this branch
  produces before Phase 4.
- **Implemented in**: this track.

### D3: §1.7 staging mode = staged (`s17=staged`), not the §1.7(k) opt-out
The change edits `.claude/scripts/**`, which `conventions.md §1.7(a)` (D14) puts in
staging scope. Route the script and test edits through
`_workflow/staged-workflow/.claude/scripts/…` during Phase B, carry the mode in the
phase-ledger `s17` field, and promote at Phase 4.
- **Alternatives considered**: the `§1.7(k)` opt-out (edit the live files directly) —
  rejected. The opt-out *does* list `.claude/scripts/**` as eligible
  (`conventions.md:1327`), but it is foreclosed here by criterion 2
  (`conventions.md:1368`): `workflow-startup-precheck.sh` is an executable-procedure
  file a running phase reads at every session start — the same class §1.7(k) names
  (the implementer rulebook's gate sequence, the step-implementation loop, the
  migrate replay) as files that stay staged even on an otherwise-qualifying plan.
  Criterion 1 reinforces it: the change is to the drift gate itself, which
  §1.7(k):1352 names in its staging-hazard list. See
  `[[no-track-for-minimal-branch]]` for the prose-only opt-out precedent that
  qualifies under §1.7(k) where this change does not.
- **Rationale**: staging is mandatory here, not a preference. The test harness is
  purpose-built to run staged. `_resolve_live_repo_root()` walks up to the live
  `conventions.md` for the §1.6(h) byte-source — the §1.6(h) walk region is the block
  of `detect_drift` that `conventions.md` pins byte-for-byte, so the script and the doc
  cannot drift apart. `SCRIPT_PATH` stays anchored at `REPO_ROOT`, so a staged copy of
  the suite exercises the staged script. Staging therefore carries no extra test cost.
- **Risks/Caveats**: the planning artifacts (this track file, the research log) are
  **not** staged — only the `.claude/scripts/**` code edits are. The drift walk does
  not watch `.claude/scripts/` — `WORKFLOW_PATHSPECS` omits it (see Context). Because
  the walk ignores `.claude/scripts/`, this branch's scripts-only edits never self-flag
  as drift, and its own Phase-4 drift range is empty whether or not the fix lands.
  Staging buys no predictability here, but it stays the documented default.
- **Implemented in**: this track.

### D4: Two regression tests, not one
Pin both arms of the skip ordering in `test_workflow_startup_precheck.py`.
- **Alternatives considered**: one test only (the skip-#2 arm) — rejected: it leaves
  the skip-#1-before-#2 ordering invariant unpinned, so a future edit that reordered
  the skips ahead of the empty-input check could break the `kind=null` empty-input
  arm silently.
- **Rationale**: the second test is cheap and pins the `:612`-before-`:618` ordering
  that D1's coverage-precision note depends on. The two tests are: (1) the headline
  **skip-#2 arm** — a `phase=D` ledger plus a stamped `plan/track-1.md` plus two
  in-range `workflow_commit`s → `detected=false, kind="stamped"` (was `detected=true`
  before the fix); models `test_drift_phase2_detected_reports_range`. (2) the
  **skip-#1-before-#2 ordering arm** — a `phase=D` ledger plus only a
  `handoff-*.md` (no stampable artifact) → `detected=false, kind=null` via the
  empty-input path, which must return *before* skip #2.
- **Risks/Caveats**: none — both tests use existing fixture surface (`write_ledger`,
  `plan_artifact`/`stamped_artifact`, `workflow_commit`, `handoff`), so they add no
  new harness.
- **Implemented in**: this track.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

**Domain terms.** A *stamp* is the line-1 HTML comment `<!-- workflow-sha: <40-hex> -->`
every workflow planning artifact carries; it records the `develop`-HEAD SHA the
artifact was authored against. *Drift* is the condition where workflow files
(`.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**`) changed on the
branch after the stamp base — the planning artifacts may be reasoning against a
stale snapshot of the workflow machinery. The *phase ledger*
(`<plan_dir>/_workflow/phase-ledger.md`) is an append-only event log whose `phase`
tail (last value wins) records the branch's current workflow phase
(`0`/`A`/`C`/`D`/`Done`); `D` means Phase 4 (final artifacts) is pending and `Done`
means it has completed. The drift walk emits one of five `kind` outcomes:

- `null` — no stampable artifact; silent no-drift.
- `unstamped` — an artifact missing its stamp; drift.
- `merge-base-failed` — two stamps with no reachable common ancestor; drift.
- `stamped` with `detected=false` — empty range; no drift.
- `stamped` with `detected=true` — non-empty range; drift.

(`stamped` carries two opposite senses, split by the `detected` flag.)

**`detect_drift` at the start of this track**
(`.claude/scripts/workflow-startup-precheck.sh:584`). The function resolves the
active plan dir from the branch name (`PLAN_DIR="docs/adr/${branch}"`), walks the
`_workflow/` plan artifacts (`implementation-plan.md`, `design.md`,
`design-mechanics.md`, `plan/track-*.md`) — the region byte-copied from
`conventions.md §1.6(h)` at `:594`–`:609` — and sorts each into `STAMPED_SHAS` or
`UNSTAMPED_FILES` by reading its line-1 stamp. It then runs four exits in order:

1. **Skip #1, empty-input no-drift** (`:612`–`:618`): when both the stamped and
   unstamped sets are empty (a `_workflow/` holding only a transient `handoff-*.md`),
   set `DRIFT_DETECTED=false`, `DRIFT_KIND=""` (→ JSON `null`), and return.
2. **Unstamped short-circuit** (`:620`–`:627`): any unstamped artifact → `detected=true,
   kind="unstamped"`, no fold, no `git log`.
3. **Phase 2 fold** (`:629`–`:651`): every artifact is stamped, so
   `fold_stamps_to_base break` folds the stamp set pairwise through `git merge-base`
   to derive `BASE_SHA`; a merge-base failure short-circuits to `detected=true,
   kind="merge-base-failed"`.
4. **Range `git log`** (`:653`–`:705`): `git log --reverse BASE_SHA..HEAD --
   $WORKFLOW_PATHSPECS`. **Skip #3, empty range** (`:664`–`:693`) → `detected=false,
   kind="stamped"`, `commit_count=0` (and an optional no-drift normalization commit
   when the stamps are non-uniform). A **non-empty range** (`:701`–`:705`) →
   `detected=true, kind="stamped"`, with `commit_count` and the first ten subject
   lines.

Skip #2 from `workflow-drift-check.md § Skip conditions` — "Phase 4 active … reads
the phase ledger … fires when the ledger exists and its resolved `phase` is `D` or
`Done`" — has no implementation between exits 1 and 2. A Phase-4 branch with a
stamped artifact and in-range workflow commits therefore reaches exit 4's non-empty
path and emits `detected=true, kind="stamped"`.

**Supporting machinery.** `ledger_tail_value <key>` (`:1652`) sets the script-scoped
`LEDGER_VALUE` to the last value of `<key>` across the ledger lines, resetting it on
entry and using only `local` vars, so calling it inside `detect_drift` will not
corrupt the later `determine_state` read of the same key. `ledger_path()` (`:1501`)
resolves `docs/adr/${branch}/_workflow/phase-ledger.md` from the same branch name
`detect_drift` uses, so the ledger the skip reads is the active plan's.
`WORKFLOW_PATHSPECS` (`:383`) is `.claude/workflow/ .claude/skills/ .claude/agents/` —
note it omits `.claude/scripts/`, so a scripts-only branch's edits are not
drift-watched (see D3 Risks/Caveats). The `--mode full` dispatch (`:2121`) runs
`detect_drift` **before** `determine_state`, which is why `detect_drift` can read
the ledger without the later state read seeing a corrupted value.

**Deliverables this track produces**: the skip-#2 block in `detect_drift`, and two
regression tests in `test_workflow_startup_precheck.py`.

## Plan of Work
Three moves, in order:

1. **Insert the skip-#2 block** in `detect_drift`, between the empty-input no-drift
   return (`:618`) and the unstamped short-circuit (`:620`). The block calls
   `ledger_tail_value phase`, and when `LEDGER_VALUE` is `D` or `Done` it sets
   `DRIFT_DETECTED=false`, `DRIFT_KIND="stamped"`, and returns. It leaves the fold
   scalars at their null defaults (it never folds), matching the empty-input return
   above it. Per D3 this edit lands in the staged copy at
   `_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh`.
2. **Add the two regression tests** to `test_workflow_startup_precheck.py` (its
   staged copy, per D3): the skip-#2 arm and the skip-#1-before-#2 ordering arm (D4).
   The skip-#2 test models the existing `test_drift_phase2_detected_reports_range` —
   same `workflow_commit` + stamped-artifact fixture shape — but adds a `phase=D`
   ledger and asserts `detected=false`. The ordering test pairs a `phase=D` ledger
   with only a `handoff()` artifact and asserts `detected=false, kind=null`.
3. **Run both suites** — `test_workflow_startup_precheck.py` and
   `test_workflow_startup_precheck_stub.py` — to confirm the fix passes the new
   tests, breaks no existing test, and leaves the §1.6(h) conformance tests green.

**Ordering constraint**: the skip-#2 block must stay *below* the empty-input check
(`:612`–`:618`) so the empty-input arm keeps returning `kind=null` first; the
ordering test pins this. **Invariant to preserve**: the §1.6(h) byte-copied walk
region (`:594`–`:609`) is untouched — the new block sits past it, so the
conformance tests (which extract only the `for f in $(ls …)` walk loop) are
unperturbed.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here. The roster is immutable after Phase A except for the status
checkbox flip and the optional `commit:` annotation Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance
- `detect_drift` reads the phase-ledger tail and folds a tail of `D` or `Done` to
  `drift.detected=false`.
- A `--mode full` run with ledger tail `D` and in-range workflow commits returns
  `drift.detected=false` (was `true` before the fix), so the startup gate no longer
  prompts for migration at Phase 4.
- A regression test pins the Phase-4 skip: ledger tail `D` plus a stamped artifact
  plus a non-empty workflow-commit range → `detected=false, kind="stamped"`.
- A second regression test pins the skip ordering: ledger tail `D` plus only a
  `handoff-*.md` (no stampable artifact) → `detected=false, kind=null`, confirming
  the empty-input skip still returns before skip #2.
- Both test suites (`test_workflow_startup_precheck.py` and the stub suite) pass; no
  existing test changes outcome.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Often empty. -->

## Interfaces and Dependencies
**In-scope**:
- `.claude/scripts/workflow-startup-precheck.sh` — `detect_drift` only (the skip-#2
  block between `:618` and `:620`). The function already reads `ledger_tail_value`'s
  `LEDGER_VALUE` contract and the `DRIFT_*` script-scoped output variables; no new
  helper or signature is added.
- `.claude/scripts/tests/test_workflow_startup_precheck.py` — two new test methods,
  built from the existing `GitFixture` surface (`write_ledger`, `plan_artifact` /
  `stamped_artifact`, `workflow_commit`, `handoff`, `run_precheck`, `_drift`).

**Out-of-scope** (must remain unchanged):
- `detect_migrate_range` (`:709`+) — the `/migrate-workflow` Step-2 walk. Migration
  must still replay at Phase 4 against the staged subtree; the skip is in the
  startup drift gate only, not the migrate-range computation.
- The §1.6(h) byte-copied walk region in `detect_drift` (`:594`–`:609`) — the
  conformance tests pin it byte-for-byte.
- `WORKFLOW_PATHSPECS` (`:383`) — the watched-paths list is not changed by this fix.
- `determine_state` (`:1809`) and `ledger_tail_value` (`:1652`) — read, not modified;
  the dispatch already runs `detect_drift` before `determine_state` (`:2121`).

**Inter-track dependencies**: none — this is a single-track `minimal` branch.

## Invariants & Constraints
<!-- Plan-at-start, combined section (D9). Phase 1 writes both the
per-track testable constraints and the testable invariants. Each invariant
becomes a test assertion in the relevant step. -->
- A `phase=D` (or `Done`) ledger plus a stamped artifact plus in-range workflow
  commits ⇒ `drift.detected=false, kind="stamped"` — verified by the new skip-#2
  regression test.
- The empty-input Phase-4 case (ledger tail `D`, only a `handoff-*.md`, no stampable
  artifact) stays `detected=false, kind=null` via skip #1, which returns before skip
  #2 — verified by the new skip-#1-before-#2 ordering test.
- `detect_migrate_range` still computes its walk and range unchanged (migration
  replays at Phase 4) — verified by the existing `test_conformance_migrate_range_*`
  and migrate-range tests staying green.
- The §1.6(h) byte-copied drift walk region is byte-for-byte unchanged — verified by
  `test_conformance_glob_set_matches_canonical`,
  `test_conformance_anchored_regex_matches_canonical`, and
  `test_conformance_drift_walk_carries_no_stamped_pairs` staying green.
- No existing `--mode full` drift test changes outcome (no prior test combines a
  `D`/`Done` ledger with a stamped artifact and in-range commits) — verified by the
  full `test_workflow_startup_precheck.py` suite passing.
