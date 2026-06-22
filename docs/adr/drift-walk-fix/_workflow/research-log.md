# Research Log — drift-walk-fix (YTDB-1150)

## Initial request

Fix YTDB-1150: "Startup precheck drift walk omits Phase-4-active skip, reports
drift at phase D."

`detect_drift` in `.claude/scripts/workflow-startup-precheck.sh` never reads the
phase ledger, so **skip #2 (Phase-4-active)** from `workflow-drift-check.md
§Skip conditions` is unimplemented. At Phase-4 startup (phase-ledger tail `D` or
`Done`) with at least one workflow commit past the stamp base, `--mode full`
emits `state.phase=D` together with `drift.detected=true, kind=stamped` in the
same JSON blob — a pair the doc says must be impossible. The startup dispatch
routes `drift.detected=true, kind=stamped` to the Migrate/Defer/Suppress gate,
and a user who picks "Migrate now" derails Phase 4 against a `_workflow/`
subtree the next cleanup commit deletes.

Proposed fix (from the issue): in `detect_drift`, after the artifact walk
resolves the plan dir, resolve the phase-ledger tail (`ledger_tail_value
phase`); when it is `D` or `Done`, set `DRIFT_DETECTED=false`,
`DRIFT_KIND="stamped"`, and return — the fold the doc already specifies. Place
it beside the existing empty-input no-drift path. Skip #1 (no `_workflow/` dir)
and skip #3 (empty range) are already covered; only skip #2 is missing.

Acceptance criteria:
- `detect_drift` reads the phase-ledger tail and folds tail `D`/`Done` to
  `drift.detected=false`.
- A `--mode full` run with ledger tail `D` and in-range workflow commits returns
  `drift.detected=false`.
- A regression test in `test_workflow_startup_precheck.py` pins the Phase-4 skip
  (ledger tail `D` plus non-empty range → not detected).

## Decision Log

### D1: Skip-#2 placement and emitted scalars in `detect_drift`
[2026-06-22T00:00Z] [ctx=safe]
Place the Phase-4-active skip immediately after the empty-input no-drift return
(`workflow-startup-precheck.sh:618`) and before the unstamped short-circuit
(`:620`): call `ledger_tail_value phase`, and when the resolved value is `D` or
`Done` set `DRIFT_DETECTED=false`, `DRIFT_KIND="stamped"`, and return. Leave
`DRIFT_BASE_SHA`/`DRIFT_COMMIT_COUNT` at their `""`→null defaults and
`DRIFT_FIRST_COMMITS_JSON` at its global `[]`.
- **Why:** matches the doc's cheapest-first skip order (#1 empty-input <
  #2 phase-4 < #3 empty-range) and the doc's unqualified "Phase 4 active → fold
  to `detected=false`". Placed after the empty-input return (`:618`) and before
  the fold, it covers every Phase-4 case that passes the empty-input check —
  stamped, unstamped, and merge-base-failed alike — and short-circuits the
  no-drift normalization so no spurious `Normalize workflow-sha stamps` commit
  lands on a subtree the next cleanup deletes. It also matches the issue's
  literal proposed fix (set `kind="stamped"`, return).
- **Precision on coverage (A1):** the load-bearing property — drift is never
  flagged at Phase 4 — holds for *every* Phase-4 case (all yield
  `detected=false`), but the emitted `kind` is not uniform. A Phase-4 branch
  with no stampable artifact (only a `handoff-*.md`) returns at skip #1
  (`detected=false, kind=null`) before reaching skip #2; the stamped /
  unstamped / merge-base-failed Phase-4 cases return at skip #2
  (`detected=false, kind="stamped"`). Both are silent no-drift, so the gate
  routes identically; the `kind` difference is informational. Test coverage
  pins both arms (see A4 below).
- **Alternatives rejected:** (a) place at the non-empty-range detected point
  (`:701`) — only covers the stamped case, misses unstamped/merge-base-failed
  Phase-4, and still runs the wasteful normalization; (b) place before the
  empty-input check — reorders #1/#2 and reads the ledger even when no artifact
  is on disk.

### D2: Change tier = `minimal`
[2026-06-22T00:00Z] [ctx=safe]
Gate 1 (needs a `design.md`?) = no; Gate 2 (multi-track?) = single. One bash
function edit plus one regression test.
- **Why:** a localized, well-understood bug fix in one function of one script,
  with a single pinning test — no HIGH-risk category is central. Matches the
  issue's own cost-benefit gate ("on-demand — script-only fix … zero
  always-loaded carry"; scope-match: one function in one script).
- **Alternatives rejected:** `lite`/`full` — no second track and no design
  surface to justify the extra artifacts. Gate 1 stays `no` even though the
  change lives in workflow machinery: it is a localized, mechanical fix, and
  risk-tagging.md §Gate 1 reuse holds that touching a HIGH category is not
  central involvement that needs a design.
- **Adversarial lens:** a Gate-1-no change is otherwise lens-free, but prime
  the gate with the `Workflow machinery` prose-scrutiny lens (rule coherence,
  instruction completeness, doc↔script consistency). The bug was a script-vs-doc
  disagreement, so doc↔script coherence is the change's central risk.

### D3: §1.7 staging mode = staged (`s17=staged`), not the §1.7(k) opt-out
[2026-06-22T00:00Z] [ctx=safe]
The change edits `.claude/scripts/**`, which §1.7(a) puts in staging scope
(D14). Route edits through `_workflow/staged-workflow/.claude/scripts/…`; carry
the mode in the phase-ledger `s17` field; promote at Phase 4.
- **Why (corrected per A3):** the §1.7(k) opt-out *does* list
  `.claude/scripts/**` as eligible (`conventions.md:1327`), so the earlier
  "opt-out is prose-only" reasoning was wrong. The opt-out is foreclosed here by
  **criterion 2** (`conventions.md:1368`): `workflow-startup-precheck.sh` is an
  executable-procedure file a running phase reads at every session start — the
  same class §1.7(k) names (the implementer rulebook's gate sequence, the
  step-implementation loop, the migrate replay) as files that "stay staged even
  on an otherwise-qualifying plan." Criterion 1 reinforces it: the change is to
  the drift gate itself, which §1.7(k):1352 names in its staging-hazard list.
  Conclusion is unchanged — stage — but staging is **mandatory**, not a
  preference. The test harness is purpose-built to run staged
  (`_resolve_live_repo_root()` walks up to live `conventions.md` for the
  §1.6(h) byte-source while `SCRIPT_PATH` stays anchored at `REPO_ROOT` so a
  staged copy of the suite exercises the staged script), so staging carries no
  extra test cost.
- **Alternatives rejected:** the §1.7(k) prose-rule opt-out (edit live) — the
  sanctioned live path, but foreclosed by criterion 2 (executable-procedure
  consumer) and criterion 1 (drift-gate change) above. See
  [[no-track-for-minimal-branch]] for the prose-only opt-out precedent that
  qualifies under §1.7(k) where this change does not.

### D4: two regression tests, not one (A4 accepted)
[2026-06-22T00:00Z] [ctx=safe]
Pin both arms of the skip ordering:
1. **Skip #2 arm** (the headline fix): `phase=D` ledger + a stamped
   `plan/track-1.md` + two in-range `workflow_commit`s → `detected=false,
   kind="stamped"` (was `detected=true` before the fix). Models
   `test_drift_phase2_detected_reports_range`.
2. **Skip #1-before-#2 ordering arm** (A4): `phase=D` ledger + only a
   `handoff-*.md` (no stampable artifact) → `detected=false, kind=null` via the
   empty-input path, which must return *before* skip #2. Guards against a future
   edit that reorders skip #2 ahead of the empty-input check.
- **Why:** the second test is cheap and pins the `:612`-before-`:618` ordering
  D1's precision note (A1) depends on; without it, a reorder that broke the
  `kind=null` empty-input arm would pass silently.
- **Alternatives rejected:** one test only — leaves the ordering invariant
  unpinned.

## Surprises & Discoveries

### S1: the drift walk does not watch `.claude/scripts/`
[2026-06-22T00:00Z] [ctx=safe]
`WORKFLOW_PATHSPECS=".claude/workflow/ .claude/skills/ .claude/agents/"`
(`:383`) omits `.claude/scripts/`, even though §1.7(a) D14 puts scripts in
staging scope. So a scripts-only branch's own edits never self-flag as drift,
and this branch's own Phase-4 drift range is empty regardless of the fix — the
"keep the branch's machinery predictable" rationale for staging is moot for this
specific branch. Staging is still the documented default (D3).

### S2: the fix lands outside the §1.6(h) byte-copied walk region
[2026-06-22T00:00Z] [ctx=safe]
The skip-#2 block sits after the empty-input return (`:618`), past the
byte-copied artifact walk (`:594`–`:609`). Confirmed (A5): the conformance tests
(`test_conformance_*`) extract walk regions through `_extract_drift_walk()` →
`_extract_script_walks()`, which select only the `for f in $(ls …)` loop blocks.
The skip-#2 block is an `if`/`case` calling `ledger_tail_value`, outside any
walk, so `test_conformance_glob_set_matches_canonical`,
`_anchored_regex_matches_canonical`, and `_drift_walk_carries_no_stamped_pairs`
are all unperturbed. Deferral closed.

### S3: no existing test exercises the bug's exact state
[2026-06-22T00:00Z] [ctx=safe]
No `--mode full` test combines a `phase=D`/`Done` ledger with a stamped artifact
and in-range workflow commits. `test_ledger_path_state_D`/`_done` and the stub
`phase=Done` test author no stamped artifact (empty-input path, reached before
skip #2); the stub drift-clean test uses `phase=C`. The fix breaks no existing
test; the regression test fills a real gap.

### S4: both `detect_drift` and `ledger_tail_value` resolve from the branch
[2026-06-22T00:00Z] [ctx=safe]
`ledger_path()` and `detect_drift`'s local `PLAN_DIR` both derive from
`git rev-parse --abbrev-ref HEAD`, so the ledger the skip reads is the active
plan's. `ledger_tail_value` resets `LEDGER_VALUE` and uses only `local` vars, so
calling it inside `detect_drift` does not corrupt the later `determine_state`
read.

## Open Questions

### OQ1: staging vs live — RESOLVED (folded into D3, per A2)
[2026-06-22T00:00Z] [ctx=safe]
There is no genuine staging-vs-live choice. The §1.7(k) opt-out is foreclosed
by criterion 2 (`workflow-startup-precheck.sh` is executable procedure a running
phase reads) and criterion 1 (the change is to the drift gate), so staging is
mandatory. The S1 pragmatic counter (scripts aren't drift-watched) does **not**
reopen the opt-out: §1.7(k)'s criteria key on the edited file's *consumer class*
(executable procedure vs judgment-layer prose), not on whether the drift walk
watches the path. Resolved into D3; no open user choice remains. The user
confirmed `s17=staged` at the Step-4 tier/mode confirmation.

## Baseline and re-validation

Workflow-modifying branch (edits `.claude/scripts/workflow-startup-precheck.sh`
and `.claude/scripts/tests/test_workflow_startup_precheck.py`). Baseline at fork:
`detect_drift` implements skip #1 (empty-input) and skip #3 (empty-range) but not
skip #2 (Phase-4-active); both test suites
(`test_workflow_startup_precheck.py`, `test_workflow_startup_precheck_stub.py`)
pass against the current script. Re-validate this baseline after any rebase onto
`develop`.

## Adversarial gate record

### Adversarial review of this log (2026-06-22T00:00Z) — PASS
Iteration 1 (`reviewer-adversarial`, opus, `Workflow machinery` lens):
`_workflow/reviews/research-log-adversarial-iter1.md`. 0 blockers, 3 should-fix,
2 suggestions. All three core decisions (D1 placement, D2 `minimal`, D3 staging)
survived on the merits. The three should-fix items were rationale-precision
corrections, now resolved into the log: A1 → D1 coverage-precision note (kind is
not uniform across Phase-4 cases, though `detected=false` is); A2 → OQ1 resolved
(no staging-vs-live choice — §1.7(k) opt-out foreclosed by criterion 2/1);
A3 → D3 rationale corrected (§1.7(k) lists `.claude/scripts/**`; the disqualifier
is criterion 2, not "prose-only"). The two suggestions are recorded: A4 → D4
(second regression test for the empty-input ordering arm); A5 → S2 deferral
closed (conformance tests are walk-region-scoped). Gate clear.
