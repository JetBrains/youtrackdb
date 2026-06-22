<!--
review: consistency
iter: 1
role: reviewer-plan
phase: 2
tier: minimal
verdict: PASS
findings: 2
blockers: 0
should_fix: 1
suggestion: 1
index:
  - id: CR1
    sev: should-fix
    anchor: "### CR1 [should-fix]"
    loc: "track-1.md:101 (D3 heading); cross-checked research-log.md D3/OQ1"
    cert: "Ref: s17 staging-mode token"
    basis: "conventions.md:1340-1341 (§1.7(k)); phase-ledger.md:1"
    class: mechanical
  - id: CR2
    sev: suggestion
    anchor: "### CR2 [suggestion]"
    loc: "track-1.md:213, track-1.md:302"
    cert: "Ref: --mode full dispatch line number"
    basis: "workflow-startup-precheck.sh:2121-2126"
    class: mechanical
evidence_base: "23 reference/invariant certificates against the live workflow-startup-precheck.sh (no staged copy; live reads) and the live test_workflow_startup_precheck.py / _stub.py. All 14 drift-region line/range claims (:584, :594-609, :612, :618, :620, :629-651, :653-705, :664-693, :701-705) MATCH exactly. Helper-function line claims (ledger_path :1501, ledger_tail_value :1652, determine_state :1809, WORKFLOW_PATHSPECS :383, detect_migrate_range :709+) MATCH. WORKFLOW_PATHSPECS contents and the .claude/scripts/ omission (S1/D3) MATCH. ledger_tail_value local-var/LEDGER_VALUE-reset contract and the detect_drift-before-determine_state dispatch order MATCH. Full test fixture surface (write_ledger, plan_artifact, stamped_artifact, workflow_commit, handoff, run_precheck, _drift, GitFixture), the model test, all four conformance tests, and the stub suite MATCH. Two MISMATCHES: the s17 token value (CR1) and the dispatch line number (CR2)."
-->

# Consistency review — Track 1 (drift-walk-fix), iteration 1

Tier `minimal` (ledger `tier=minimal`): no `design.md`, no `implementation-plan.md`. Ran the PLAN ↔ CODE track-reference bullet against the single track file plus live code, the GAPS orphan-codebase-construct bullet, and the tier-presence check. Skipped the DESIGN axes, the DESIGN half of GAPS, and the PLAN-content bullets (no plan file). All reference verification used grep/Read against the live bash/python files (no staged copy exists; Phase B has not run) — authoritative for these file types, no reference-accuracy caveat applies.

## Findings

### CR1 [should-fix]
**Certificate**: Ref: s17 staging-mode token
**Location**: `track-1.md:101` (D3 heading `### D3: §1.7 staging mode = staged (\`s17=staged\`)…`); the same wrong token appears in `research-log.md` D3 heading (:84) and OQ1 (:176, "confirmed `s17=staged`").
**Issue**: The track DR D3 renders the §1.7(b) staging mode as the token `s17=staged`. That is not a canonical `s17` field value. `conventions.md §1.7(k)` (`:1340-1341`) defines the field as carrying exactly two mutually-exclusive values — `s17` = `workflow-modifying` (stage) or `s17` = `opt-out` (edit live). There is no `staged` token.
**Evidence**: `conventions.md:1340-1341` — "a branch stages (`s17` = workflow-modifying) or edits live under the opt-out (`s17` = opt-out), never both." The on-disk phase ledger is **correct** — `phase-ledger.md:1` carries `s17=workflow-modifying`. So the live branch state already uses the canonical token; only the track DR (and research log) heading mis-render it as `staged`. The script's `--append-ledger` does not validate the value set (`reject_bad_ledger_value "s17" … bare` at `:1581` rejects only spaces/quotes), so `staged` would not be caught by the script — the divergence is purely a documentation/token mismatch against the canonical spec.
**Proposed fix**: In `track-1.md:101`, change the D3 heading to `### D3: §1.7 staging mode = stage (\`s17=workflow-modifying\`), not the §1.7(k) opt-out`. The decision (stage, not opt-out) is unchanged; only the token spelling is corrected to the canonical value the ledger already carries. (The descriptive uses of "staged" as an adjective elsewhere in D3 — "staged copy", "run staged" — are correct and need no change.)
**Classification**: mechanical
**Justification**: current-state claim about a canonical token, single unambiguous correct rendering (`workflow-modifying`, the value already on the ledger), fix preserves the decision intent (stage).

### CR2 [suggestion]
**Certificate**: Ref: --mode full dispatch line number
**Location**: `track-1.md:213` ("The `--mode full` dispatch (`:2121`) runs `detect_drift` before `determine_state`") and `track-1.md:302` ("the dispatch already runs `detect_drift` before `determine_state` (`:2121`)").
**Issue**: Line `:2121` is the `case "$MODE" in` head that opens the dispatch; the actual `detect_drift` call is at `:2124` and the `determine_state` call at `:2126`. The cited line points at the dispatch construct, not the two calls whose ordering the claim is about.
**Evidence**: `workflow-startup-precheck.sh:2121` = `case "$MODE" in`; `:2124` = `detect_drift`; `:2126` = `determine_state`. The substantive ordering claim — `detect_drift` runs before `determine_state` — is **true** (2124 < 2126), so this is a precision nit, not a factual error. The execution agent will land on the dispatch block either way.
**Proposed fix**: Optionally point the call-ordering reference at the calls — e.g., "`detect_drift` (`:2124`) before `determine_state` (`:2126`)" — or leave `:2121` as a dispatch-block anchor. Low priority; the ordering claim holds.
**Classification**: mechanical
**Justification**: current-state claim, single unambiguous correct rendering (the call lines `:2124`/`:2126`), fix changes only the line citation, not the (correct) ordering claim.

## Evidence base

Certificates grouped under PLAN ↔ CODE (track-vs-code) and GAPS. All searches via grep/Read on the live files at the repo root (`workflow-startup-precheck.sh`, `tests/test_workflow_startup_precheck.py`, `tests/test_workflow_startup_precheck_stub.py`, `conventions.md`). No `_workflow/staged-workflow/` exists, so every read resolves to the live file (confirmed by directory listing).

#### Ref: detect_drift function start (:584)
- **Document claim**: `detect_drift` begins at `workflow-startup-precheck.sh:584` (Context, D1).
- **Search performed**: `grep -n 'detect_drift()'`.
- **Code location**: `:584` — `detect_drift() {`.
- **Actual signature/role**: function definition at exactly line 584.
- **Verdict**: MATCHES

#### Ref: §1.6(h) byte-copied walk region (:594–:609)
- **Document claim**: the byte-copied artifact walk spans `:594`–`:609` (Context, Plan of Work, Out-of-scope, Invariants).
- **Search performed**: Read of lines 575–715.
- **Code location**: `:594` = `# --- Phase 1 walk, byte-copied from conventions.md § 1.6(h) ---`; `:609` = `# --- end byte-copied walk ---`; the `for f in $(ls …)` loop sits between (:598–:608).
- **Actual signature/role**: the walk loop is bounded exactly by the two `---` marker comments at 594 and 609.
- **Verdict**: MATCHES

#### Ref: empty-input check / return (:612, :618)
- **Document claim**: empty-input no-drift check at `:612`, its `return` at `:618` (D1, Context, Plan of Work, D4 ordering).
- **Search performed**: Read of lines 611–618.
- **Code location**: `:612` = `if [ -z "$STAMPED_SHAS" ] && [ -z "$UNSTAMPED_FILES" ]; then`; `:615-616` set `DRIFT_DETECTED="false"`, `DRIFT_KIND=""`; `:617` = `return`. The block's closing brace/return region runs through 618.
- **Actual signature/role**: empty-input no-drift exit, `kind` stays null; block ends at 618.
- **Verdict**: MATCHES

#### Ref: unstamped short-circuit (:620)
- **Document claim**: the unstamped short-circuit follows the empty-input return, at `:620`; the skip-#2 block must sit between `:618` and `:620` (D1, Plan of Work).
- **Search performed**: Read of lines 619–627.
- **Code location**: `:620` = `if [ -n "$UNSTAMPED_FILES" ]; then`; sets `kind="unstamped"` and returns at 626.
- **Actual signature/role**: unstamped short-circuit begins at 620, immediately after the empty-input block. The insertion gap (after :618, before :620) exists and is the blank line at :619.
- **Verdict**: MATCHES

#### Ref: Phase-2 fold (:629–:651)
- **Document claim**: the stamp fold + merge-base-failed short-circuit spans `:629`–`:651` (Context).
- **Search performed**: Read of lines 629–651.
- **Code location**: `:629` = fold comment block start; `:634` = `fold_stamps_to_base break`; `:636` = `if [ -n "$FOLD_FAILED_PAIRS" ]`; `:649` sets `kind="merge-base-failed"`; `:650` = `return`; `:651` = closing brace of the if.
- **Verdict**: MATCHES

#### Ref: range git log (:653–:705)
- **Document claim**: the range `git log` exit spans `:653`–`:705` (Context).
- **Search performed**: Read of lines 653–706.
- **Code location**: `:653` = "Fold succeeded: BASE_SHA …" comment; `:661` = `git log --reverse …`; the function's last emitting statement is at `:705`; `:706` = closing `}`.
- **Verdict**: MATCHES

#### Ref: empty-range skip (:664–:693)
- **Document claim**: the empty-range no-drift exit spans `:664`–`:693` (Context).
- **Search performed**: Read of lines 664–693.
- **Code location**: `:664` = `if [ -z "$log_lines" ]; then`; `:669-670` set `detected=false, kind="stamped"`; `:693` = `return` of the block.
- **Verdict**: MATCHES

#### Ref: non-empty-range detected point (:701–:705)
- **Document claim**: the non-empty-range drift exit spans `:701`–`:705`, emitting `detected=true, kind="stamped"` (D1 alternative-rejected, Context).
- **Search performed**: Read of lines 701–705.
- **Code location**: `:701` = `DRIFT_DETECTED="true"`; `:702` = `DRIFT_KIND="stamped"`; `:703` = commit count; `:704-705` = first-commits JSON.
- **Verdict**: MATCHES

#### Ref: ledger_path() (:1501)
- **Document claim**: `ledger_path()` at `:1501` resolves `docs/adr/${branch}/_workflow/phase-ledger.md` from the branch name (Context, D1, Out-of-scope; research log S4).
- **Search performed**: `grep -n 'ledger_path()'` + Read of 1501–1505.
- **Code location**: `:1501` = `ledger_path() {`; body `:1503` = `git rev-parse --abbrev-ref HEAD`; `:1504` = `printf '%s' "docs/adr/${branch}/_workflow/phase-ledger.md"`.
- **Verdict**: MATCHES

#### Ref: ledger_tail_value (:1652) — contract
- **Document claim**: `ledger_tail_value <key>` at `:1652` resets `LEDGER_VALUE` and uses only `local` vars, so calling it inside `detect_drift` will not corrupt the later `determine_state` read (Context, Out-of-scope; research log S4).
- **Search performed**: `grep -n 'ledger_tail_value'` + Read of 1652–1684.
- **Code location**: `:1652` = `ledger_tail_value() {`; `:1653` = `local key="$1" ledger line rest val`; `:1654` = `LEDGER_VALUE=""` (reset on entry); the only non-local assignment is `LEDGER_VALUE` (the documented output). No global state besides `LEDGER_VALUE` is written.
- **Actual signature/role**: resets `LEDGER_VALUE`, all working vars `local`; last-value-wins scan. Contract holds — `determine_state` later calls `ledger_tail_value "phase"` again (`:1766`) and gets a fresh reset.
- **Verdict**: MATCHES

#### Ref: determine_state (:1809)
- **Document claim**: `determine_state` at `:1809` (Out-of-scope; read-not-modified).
- **Search performed**: `grep -n 'determine_state'` + Read of 1809–1820.
- **Code location**: `:1809` = `determine_state() {`.
- **Verdict**: MATCHES

#### Ref: WORKFLOW_PATHSPECS (:383) — location and contents
- **Document claim**: `WORKFLOW_PATHSPECS` at `:383` is `.claude/workflow/ .claude/skills/ .claude/agents/` and omits `.claude/scripts/` (S1, D3, Context, Out-of-scope).
- **Search performed**: `grep -n 'WORKFLOW_PATHSPECS='` + Read of 376–384.
- **Code location**: `:383` = `WORKFLOW_PATHSPECS=".claude/workflow/ .claude/skills/ .claude/agents/"`.
- **Actual signature/role**: exactly the three paths claimed; `.claude/scripts/` is absent. The S1/D3 "scripts not drift-watched" claim is correct.
- **Verdict**: MATCHES

#### Ref: --mode full dispatch order (:2121 cited; actual :2124/:2126)
- **Document claim**: the `--mode full` dispatch (`:2121`) runs `detect_drift` **before** `determine_state` (Context, Out-of-scope).
- **Search performed**: `grep -n` + Read of 2113–2137.
- **Code location**: `:2121` = `case "$MODE" in`; `:2122` = `full)`; `:2124` = `detect_drift`; `:2126` = `determine_state`.
- **Actual signature/role**: the ordering claim is true (`detect_drift` at 2124 precedes `determine_state` at 2126), but the cited line `:2121` is the `case` head, not the call lines.
- **Verdict**: PARTIAL — ordering correct, line number imprecise (→ CR2).

#### Ref: detect_migrate_range (:709+)
- **Document claim**: `detect_migrate_range (:709+)` — the migrate-range walk, out of scope (Out-of-scope, Invariants).
- **Search performed**: `grep -n 'detect_migrate_range'` + Read of 707–711, 783.
- **Code location**: `:709` = first content line of the migrate-range section comment header (`# migrate-range detection — …`); the function definition is at `:783`. The `:709+` notation denotes the block from its header onward, which includes the function.
- **Actual signature/role**: the `+` correctly marks a block reference starting at the section header; the function body lives below at 783. Reference is accurate as written.
- **Verdict**: MATCHES

#### Ref: DRIFT_* default scalars ([] / null)
- **Document claim**: the skip-#2 block leaves `DRIFT_BASE_SHA`/`DRIFT_COMMIT_COUNT` at their `""`→null defaults and `DRIFT_FIRST_COMMITS_JSON` at its global `[]` (D1).
- **Search performed**: Read of 350–361.
- **Code location**: `:354` = `DRIFT_BASE_SHA=""`; `:355` = `DRIFT_COMMIT_COUNT=""`; `:356` = `DRIFT_FIRST_COMMITS_JSON="[]"`.
- **Verdict**: MATCHES

#### Ref: GitFixture and fixture-surface methods
- **Document claim**: tests are built from the existing `GitFixture` surface: `write_ledger`, `plan_artifact`/`stamped_artifact`, `workflow_commit`, `handoff`, `run_precheck`, `_drift` (D4, Interfaces, Plan of Work).
- **Search performed**: `grep -nE 'class GitFixture|def (write_ledger|plan_artifact|stamped_artifact|workflow_commit|handoff|run_precheck|_drift)'` on the live test file.
- **Code location**: `class GitFixture` :156; `workflow_commit` :316; `plan_artifact` :373; `handoff` :397; `stamped_artifact` :412; `write_ledger` :462; `run_precheck` :93 (module-level helper); `_drift` :1135 (module-level helper).
- **Actual signature/role**: all named helpers exist. `run_precheck` and `_drift` are module-level functions rather than `GitFixture` methods, but the track lists them as available fixture-surface helpers, which is accurate — no phantom reference.
- **Verdict**: MATCHES

#### Ref: model test test_drift_phase2_detected_reports_range
- **Document claim**: the skip-#2 test models `test_drift_phase2_detected_reports_range` (D4, Plan of Work).
- **Search performed**: `grep -nE 'def test_drift_phase2_detected_reports_range'`.
- **Code location**: `:1261` = `def test_drift_phase2_detected_reports_range() -> None:`.
- **Verdict**: MATCHES

#### Ref: conformance tests (Invariants section)
- **Document claim**: `test_conformance_glob_set_matches_canonical`, `test_conformance_anchored_regex_matches_canonical`, `test_conformance_drift_walk_carries_no_stamped_pairs`, and the migrate-range conformance test exist and stay green (Invariants).
- **Search performed**: `grep -nE 'def test_conformance'`.
- **Code location**: `:3186` glob_set; `:3210` anchored_regex; `:3231` drift_walk_carries_no_stamped_pairs; `:3249` migrate_range_walk_carries_stamped_pairs; `:3272` normalization_recompute_walk.
- **Actual signature/role**: all four named conformance tests exist; the migrate-range conformance test the Invariants section calls `test_conformance_migrate_range_*` resolves to `test_conformance_migrate_range_walk_carries_stamped_pairs`.
- **Verdict**: MATCHES

#### Ref: stub suite test_workflow_startup_precheck_stub.py
- **Document claim**: the stub suite `test_workflow_startup_precheck_stub.py` exists and is run in Move 3 (Plan of Work, Validation).
- **Search performed**: `ls -la` on the path.
- **Code location**: `.claude/scripts/tests/test_workflow_startup_precheck_stub.py` (18520 bytes, present).
- **Verdict**: MATCHES

#### Ref: s17 canonical token (conventions.md §1.7)
- **Document claim**: track D3 heading renders the staging mode as `s17=staged` (track-1.md:101); research log D3/OQ1 say the same.
- **Search performed**: `grep -nE 'workflow-modifying|opt-out|s17'` on conventions.md + Read of 1336–1346; Read of phase-ledger.md.
- **Code location**: `conventions.md:1340-1341` — the field has exactly two values, `workflow-modifying` (stage) and `opt-out`. `phase-ledger.md:1` carries `s17=workflow-modifying` (correct). Script `--append-ledger` at `:1581` validates only grammar (bare token, no spaces/quotes), not the value set.
- **Actual signature/role**: canonical "stage" token is `workflow-modifying`, not `staged`. The on-disk ledger is correct; the track DR heading mis-renders it.
- **Verdict**: MISMATCHES (→ CR1).

#### Invariant: skip-#1 returns before skip-#2 (ordering)
- **Document claim**: the empty-input skip (`:612`–`:618`) must return before any Phase-4 skip, so the `kind=null` empty-input arm wins first (D1, D4, Plan of Work ordering constraint, Invariants).
- **Code evidence**: `:612` empty-input check returns at `:617-618`; the proposed skip-#2 insertion point is between `:618` and the unstamped short-circuit at `:620`. The empty-input return therefore structurally precedes any code inserted at the D1 placement.
- **Mechanism**: sequential function body — the empty-input `return` at `:618` exits before control reaches the `:618`→`:620` gap.
- **Verdict**: ENFORCED (the ordering the track's invariant and ordering test depend on is structurally present in the live function; this is a target-state placement the track will add, but the supporting empty-input return already exists at the right position).

#### Gap check: orphan codebase constructs
- **Document claim (implicit)**: the fix needs `ledger_tail_value`, `ledger_path`, the `DRIFT_*` output vars, and `WORKFLOW_PATHSPECS`; no new helper or signature.
- **Search performed**: reviewed `detect_drift`, the `DRIFT_*` defaults, `ledger_tail_value`, `ledger_path`, and the dispatch.
- **Result**: no orphan construct. The skip reads the ledger via the existing `ledger_tail_value phase`; every construct the fix touches is named in the track. No existing helper the fix should use is omitted.
- **Verdict**: MATCHES (no gap).

#### Tier-presence check
- **Document claim**: tier resolves from the phase ledger `tier` field.
- **Search performed**: Read of phase-ledger.md.
- **Code location**: `phase-ledger.md:1` carries `tier=minimal`.
- **Verdict**: MATCHES — tier resolves; no tier-presence finding.
