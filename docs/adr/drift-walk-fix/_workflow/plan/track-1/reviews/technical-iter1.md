<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: T1, sev: should-fix, loc: "track-1.md §Context/§Plan-of-Work; workflow-drift-check.md:207-224", anchor: "### T1 ", cert: "Premise: doc Skip #1 = directory-existence vs script empty-input", basis: "Track relabels the empty-input check (:612) as 'skip #1', but the doc's Skip #1 is a [ -d $PLAN_DIR/_workflow ] directory-existence check the script never implements as such; skip-number alignment unverified across doc and script"}
  - {id: T2, sev: suggestion, loc: "track-1.md §Plan of Work move 1; workflow-startup-precheck.sh:659", anchor: "### T2 ", cert: "Premise: DRIFT_BASE_SHA left at default on skip-#2 return"}
evidence_base: {section: "## Evidence base", certs: 13, matches: 12}
cert_index:
  - {id: P1, verdict: CONFIRMED, anchor: "#### P1 "}
  - {id: P2, verdict: CONFIRMED, anchor: "#### P2 "}
  - {id: P3, verdict: CONFIRMED, anchor: "#### P3 "}
  - {id: P4, verdict: CONFIRMED, anchor: "#### P4 "}
  - {id: P5, verdict: CONFIRMED, anchor: "#### P5 "}
  - {id: P6, verdict: PARTIAL,   anchor: "#### P6 "}
  - {id: E1, verdict: CONFIRMED, anchor: "#### E1 "}
  - {id: E2, verdict: CONFIRMED, anchor: "#### E2 "}
  - {id: E3, verdict: CONFIRMED, anchor: "#### E3 "}
  - {id: E4, verdict: CONFIRMED, anchor: "#### E4 "}
  - {id: I1, verdict: MATCHES,   anchor: "#### I1 "}
  - {id: I2, verdict: MATCHES,   anchor: "#### I2 "}
  - {id: I3, verdict: MATCHES,   anchor: "#### I3 "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: Premise P6 (doc Skip #1 = directory-existence vs script empty-input), Integration I1 (doc↔script skip routing).
**Location**: track-1.md `## Context and Orientation` (numbered list item 1 labels the empty-input return "Skip #1, empty-input no-drift") and `## Plan of Work` move 1 + ordering constraint; against `workflow-drift-check.md:207-224` (the doc's Skip #1/Skip #2 definitions).
**Issue**: The track's internal skip numbering does not line up term-for-term with `workflow-drift-check.md § Skip conditions`, and on a workflow-machinery change whose central risk D2 itself names is doc↔script coherence, that gap deserves an explicit note. The doc's **Skip #1** is "Active plan's `_workflow/` directory doesn't exist" with check `[ -d "$PLAN_DIR/_workflow" ]` (`workflow-drift-check.md:207-210`). The script (`workflow-startup-precheck.sh`) has **no** `[ -d ]` directory-existence guard anywhere in `detect_drift` (grep for `[ -d` returns nothing). Instead the empty-input check at `:612` (`[ -z "$STAMPED_SHAS" ] && [ -z "$UNSTAMPED_FILES" ]`) subsumes both "directory absent" (the `ls 2>/dev/null` walk at `:598`-`:601` finds nothing) and "directory present but only a `handoff-*.md`" — both collapse to `detected=false, kind=null` at `:615`-`:617`. The track calls *that* empty-input return "skip #1" (Context item 1, and the ordering invariant "`:612`-before-`:618`"). So the track's "skip #1" and the doc's "Skip #1" are not the same predicate, even though they converge on the same outcome. This is a **pre-existing** doc↔script framing gap (the script already lacked the `[ -d ]` check before this track), not one the fix introduces — but the fix inserts a block whose correctness argument (D1, D4) is built on the "skip #1 / skip #2 ordering," so the labels should be reconciled or the divergence flagged so the next reader of either artifact is not misled. The load-bearing behavior is unaffected: placing the new block at `:619` keeps it below the empty-input return regardless of how that return is labeled, and the doc's Skip #1→Skip #2 cheapest-first order is preserved.
**Proposed fix**: In the track file, add one sentence in `## Context and Orientation` (near the numbered exit list) noting that the script realizes the doc's Skip #1 (directory-existence) and the empty-input "no stampable artifact" case as a *single* `:612` check (there is no literal `[ -d ]` guard), so the track's "skip #1" label denotes that combined empty-input return. Optionally surface this as a Phase-4 design-final reconciliation note (the doc's `§ Skip conditions` prose could gain a parenthetical that Skip #1's `[ -d ]` and the empty-input no-stampable case share one script exit). No code change is required — the placement and ordering are already correct.

### T2 [suggestion]
**Certificate**: Premise P2 (DRIFT_* defaults), Premise P5 (skip-#2 emitted scalars).
**Location**: track-1.md `## Plan of Work` move 1 and D1 ("leaving `DRIFT_BASE_SHA`/`DRIFT_COMMIT_COUNT` at their `""`→null defaults"); `workflow-startup-precheck.sh:659`, `:352`-`:356`.
**Issue**: D1/move 1 correctly state the skip-#2 block leaves `DRIFT_BASE_SHA` at its `""`→null default because it returns before the fold. Worth making explicit for the implementer: the *only* writer of `DRIFT_BASE_SHA` in `detect_drift` is `:659` (`DRIFT_BASE_SHA="$FOLD_BASE_SHA"`), which sits inside the post-fold range path at `:653`+. Since the new block returns at `:619` — above the fold (`:634`) and above `:659` — `DRIFT_BASE_SHA` is guaranteed to retain its global default (`:354`), so the emitted `base_sha` is `null`. This matches the empty-input return above it and the doc's `detected==false, kind=="stamped"` consumer row (`workflow-drift-check.md:93`-`99`), which the gate routes to "startup continues" identically to `kind==null`. No risk; the suggestion is only to keep the implementer from accidentally also clearing or setting `DRIFT_BASE_SHA` in the new block (it must touch only `DRIFT_DETECTED` and `DRIFT_KIND`, exactly as the empty-input return at `:615`-`:616` does).
**Proposed fix**: When decomposing, pin in the step's acceptance that the new block sets only `DRIFT_DETECTED="false"` and `DRIFT_KIND="stamped"` and calls nothing that mutates `DRIFT_BASE_SHA`/`DRIFT_COMMIT_COUNT`/`DRIFT_FIRST_COMMITS_JSON` — mirroring `:615`-`:617`. The skip-#2 regression test already asserts `base_sha` is null / `commit_count` 0 implicitly via `detected=false`; consider an explicit `assert drift["base_sha"] is None` to lock it.

## Evidence base

#### P1: detect_drift control flow and the :618/:620 insertion point
- **Track claim**: Insert skip #2 "between the empty-input no-drift return (`:618`) and the unstamped short-circuit (`:620`)" (D1, Plan of Work move 1).
- **Search performed**: Read `workflow-startup-precheck.sh:584`-`706` end-to-end (the full `detect_drift` body); grep for the next function boundary after `:706`.
- **Code location**: `workflow-startup-precheck.sh:584`-`706`. `:612`-`:618` = empty-input if-block (`return` at `:617`, closing `}` at `:618`); `:619` blank; `:620`-`:627` = unstamped short-circuit (`if [ -n "$UNSTAMPED_FILES" ]`); `:629`-`:651` = Phase-2 fold; `:653`-`:705` = range `git log`; function closes at `:706`; next thing is the `detect_migrate_range` header comment at `:708`.
- **Actual behavior**: Inserting at `:619` (after the empty-input block's `}`, before the unstamped `if`) is a valid statement boundary. Control reaching `:619` means the empty-input arm did NOT return, i.e. at least one stamped or unstamped artifact is on disk. The new block can `ledger_tail_value phase`, test `D`/`Done`, set the two scalars, and `return` — falling through to `:620` otherwise.
- **Verdict**: CONFIRMED
- **Detail**: Line numbers in the track match the live file exactly.

#### P2: DRIFT_* global defaults
- **Track claim**: The skip-#2 block leaves `DRIFT_BASE_SHA`/`DRIFT_COMMIT_COUNT` at their `""`→null defaults and `DRIFT_FIRST_COMMITS_JSON` at its global `[]` (D1).
- **Search performed**: grep `^(DRIFT_|STAMPED_SHAS|...)` ; Read `:350`-`:383`.
- **Code location**: `workflow-startup-precheck.sh:352`-`:356`, `:361`.
- **Actual behavior**: `DRIFT_DETECTED="false"` (`:352`), `DRIFT_KIND=""` (`:353`), `DRIFT_BASE_SHA=""` (`:354`), `DRIFT_COMMIT_COUNT=""` (`:355`), `DRIFT_FIRST_COMMITS_JSON="[]"` (`:356`), `DRIFT_NORMALIZATION_LANDED="false"` (`:361`). The comment at `:350`-`:351` states the defaults "cover the empty-input no-drift path so emit_json never reads an unset variable." A return at `:619` leaves every fold scalar at default, exactly as the `:615`-`:617` empty-input return does (it sets only `DRIFT_DETECTED`/`DRIFT_KIND`).
- **Verdict**: CONFIRMED

#### P3: ledger_tail_value resets and is local-only; cannot corrupt determine_state
- **Track claim**: `ledger_tail_value <key>` (`:1652`) resets `LEDGER_VALUE` on entry, uses only `local` vars, so calling it inside `detect_drift` won't corrupt the later `determine_state` read of the same key (Context, D1).
- **Search performed**: Read `:1652`-`1684` (`ledger_tail_value`), `:1755`-`1807` (`determine_state_from_ledger`), `:1809`-`1818` (`determine_state`); grep `LEDGER_VALUE` / `ledger_tail_value`.
- **Code location**: `workflow-startup-precheck.sh:1652`-`1684`; readers at `:1766`-`1767`, `:1785`-`1786`.
- **Actual behavior**: `ledger_tail_value` declares `local key ledger line rest val` (`:1653`) and assigns `LEDGER_VALUE=""` on entry (`:1654`) before the `while read` scan (last-value-wins, no early break — `:1680` overwrites each match). `determine_state` → `determine_state_from_ledger` calls `ledger_tail_value "phase"` fresh at `:1766` and reads `phase="$LEDGER_VALUE"` at `:1767`. Because the function resets `LEDGER_VALUE` first, any value the skip-#2 block leaves in it is discarded before `determine_state` reads. No cross-call corruption.
- **Verdict**: CONFIRMED

#### P4: --mode full dispatch runs detect_drift before determine_state
- **Track claim**: `detect_drift` (`:2124`) runs before `determine_state` (`:2126`) under `--mode full` (Context, D1).
- **Search performed**: Read `:2110`-`2137` (the `case "$MODE"` dispatch).
- **Code location**: `workflow-startup-precheck.sh:2121`-`2127`.
- **Actual behavior**: `full)` arm runs `detect_divergence` (`:2123`) → `detect_drift` (`:2124`) → `scan_handoffs` (`:2125`) → `determine_state` (`:2126`). Order confirmed: drift detection precedes state determination, so the `LEDGER_VALUE` reset in P3 is the relevant safeguard.
- **Verdict**: CONFIRMED

#### P5: ledger_path resolves the active plan's ledger from the same branch detect_drift uses
- **Track claim**: `ledger_path()` (`:1501`) resolves `docs/adr/${branch}/_workflow/phase-ledger.md` from the same branch name `detect_drift` uses, so the skip reads the active plan's ledger (Context).
- **Search performed**: Read `:1501`-`1505` (`ledger_path`) and `:590`-`592` (detect_drift branch resolution).
- **Code location**: `workflow-startup-precheck.sh:1501`-`1505`; `:590`-`592`.
- **Actual behavior**: `ledger_path` does `git rev-parse --abbrev-ref HEAD` → `docs/adr/${branch}/_workflow/phase-ledger.md`. `detect_drift` does the same `git rev-parse` at `:591` → `PLAN_DIR="docs/adr/${branch}"`. Same branch source, same plan dir, so `ledger_tail_value` (via `ledger_path`) reads the active plan's ledger.
- **Verdict**: CONFIRMED

#### P6: doc Skip #1 (directory-existence) vs script empty-input check
- **Track claim**: Track Context numbers the empty-input return "Skip #1"; the ordering invariant is "skip #1 before skip #2."
- **Search performed**: Read `workflow-drift-check.md:193`-`238` (Skip conditions); grep `[ -d` over the script; grep skip-return labels in `detect_drift` `:584`-`706`.
- **Code location**: doc Skip #1 at `workflow-drift-check.md:207`-`210`; script empty-input at `workflow-startup-precheck.sh:612`-`618`; no `[ -d ]` guard found.
- **Actual behavior**: The doc's Skip #1 is `[ -d "$PLAN_DIR/_workflow" ]`. The script has no such guard; the empty-input check (both classification sets empty) subsumes both "directory absent" and "only a handoff present," collapsing to `detected=false, kind=null`. The track's "skip #1" label denotes the empty-input return, not the doc's directory-existence predicate.
- **Verdict**: PARTIAL
- **Detail**: Behaviorally equivalent (both yield silent no-drift, and skip #1→#2 cheapest-first order is preserved), but the labels diverge term-for-term. Pre-existing gap; produces finding T1.

#### E1: Empty-input Phase-4 case (handoff-only) returns at skip #1, kind=null
- **Trigger**: Ledger tail `D`/`Done`, `_workflow/` holds only a `handoff-*.md` (no stampable artifact).
- **Code path trace**:
  1. Entry: `detect_drift()` @ `:584`; walk `:598`-`608` finds no `implementation-plan.md`/`design*.md`/`track-*.md` → `STAMPED_SHAS`/`UNSTAMPED_FILES` both empty.
  2. `:612` both-empty test true → `:615` `DRIFT_DETECTED="false"`, `:616` `DRIFT_KIND=""`, `:617` `return` — BEFORE reaching `:619`.
- **Outcome**: `detected=false, kind=null`. Returns at the empty-input arm before the new skip-#2 block.
- **Track coverage**: yes — D1 Risks/Caveats and D4 ordering test pin this exact arm.

#### E2: Stamped Phase-4 case with in-range commits returns at skip #2, kind=stamped
- **Trigger**: Ledger tail `D`, a stamped `plan/track-*.md`, ≥1 in-range workflow commit.
- **Code path trace**:
  1. Walk classifies the stamped artifact into `STAMPED_SHAS` (non-empty), `UNSTAMPED_FILES` empty.
  2. `:612` both-empty test false → fall to `:619` new block; `ledger_tail_value phase` = `D` → set `detected=false, kind="stamped"`, return — BEFORE the fold (`:634`) and range `git log` (`:661`) that would otherwise emit `detected=true` at `:701`.
- **Outcome**: `detected=false, kind="stamped"` (was `detected=true, kind="stamped"` before the fix). The regression the track targets.
- **Track coverage**: yes — D1, D4 skip-#2 test, Validation/Invariants.

#### E3: Unstamped Phase-4 case returns at skip #2 (above the unstamped short-circuit), kind=stamped
- **Trigger**: Ledger tail `D`, an unstamped artifact present.
- **Code path trace**:
  1. Walk puts the artifact in `UNSTAMPED_FILES`; `:612` both-empty test false → fall to `:619` new block.
  2. `ledger_tail_value phase` = `D` → set `detected=false, kind="stamped"`, return — BEFORE the `:620` unstamped short-circuit that would set `detected=true, kind="unstamped"`.
- **Outcome**: `detected=false, kind="stamped"`. Drift not flagged at Phase 4 (load-bearing property holds); kind is "stamped" not "unstamped" — informational-only per the gate's identical `detected==false` routing (I1).
- **Track coverage**: yes — D1 Risks/Caveats explicitly lists the unstamped case under skip #2 with `kind="stamped"`.

#### E4: Merge-base-failed Phase-4 case returns at skip #2 (above the fold), kind=stamped
- **Trigger**: Ledger tail `D`, two stamps with no reachable common ancestor.
- **Code path trace**:
  1. All artifacts stamped → would reach the fold at `:634`; but the new block at `:619` fires first.
  2. `ledger_tail_value phase` = `D` → `detected=false, kind="stamped"`, return — BEFORE `fold_stamps_to_base` (`:634`) and the `:636`-`:650` merge-base-failed short-circuit.
- **Outcome**: `detected=false, kind="stamped"`. Merge-base-failed Phase-4 case never reaches the fold, so no `kind="merge-base-failed"` emit. Drift not flagged. The skip short-circuits before the wasteful fold (D1 rationale).
- **Track coverage**: yes — D1 Risks/Caveats lists merge-base-failed under skip #2 with `kind="stamped"`.

#### I1: detected/kind consumer routing — identical on detected=false regardless of kind
- **Plan claim**: The non-uniform `kind` across Phase-4 cases is informational only; the startup gate routes identically on `detected=false` (D1 Risks/Caveats).
- **Actual entry point**: `workflow-drift-check.md:82`-`109` (the five-outcome routing table) and `:93`-`99` (the `detected==false, kind=="stamped"` row) and `:100`-`103` (the `detected==false, kind=null` row).
- **Caller analysis**: Both `detected==false` rows resolve to "Startup continues to the calling session's next startup step" (`:96`-`97` for stamped, `:103` for null). Line `:97`-`99` explicitly says "Any matched condition in § Skip conditions takes the same silent path inside the script before the walk runs" — so a skip producing `detected==false, kind=="stamped"` WITHOUT running the no-drift normalization is already covered by the doc's language (the "ran normalization … before reporting this state when the stamp set was non-uniform; otherwise it skipped silently" clause at `:93`-`96`). Only `detected==true` rows (`:85`-`92`, `:104`-`109`) run the resolution prompt.
- **Breaking change risk**: none — the skip yields `detected=false`, which the gate routes to "continue," never to the Migrate/Defer/Suppress prompt. The `kind="stamped"` vs `kind=null` difference does not branch the gate.
- **Verdict**: MATCHES

#### I2: §1.6(h) byte-copied walk region and conformance tests unperturbed
- **Plan claim**: The §1.6(h) walk region (`:594`-`:609`) is untouched; conformance tests extract only the walk loop, so the new block past `:609` cannot perturb them (Plan of Work invariant, Out-of-scope).
- **Actual entry point**: `_extract_all_ls_walks()` @ `test_workflow_startup_precheck.py:3067`-`3084`; `_extract_drift_walk()` @ `:3119`-`3129`; conformance tests @ `:3186`, `:3210`, `:3231`.
- **Caller analysis**: `_extract_all_ls_walks` scans from `for f in $(ls ` to the next line whose strip equals `done` (`:3072`-`3080`), capturing only `:598`-`:608`. `_extract_drift_walk` filters by absence of `STAMPED_PAIRS`. The new block at `:619` is past the `done` at `:608`, outside every extraction window. The three drift-walk conformance tests (`glob_set`, `anchored_regex`, `drift_walk_carries_no_stamped_pairs`) compare only the extracted loop text.
- **Breaking change risk**: none — inserting a block at `:619` does not change the bytes between `for f in $(ls` and `done`.
- **Verdict**: MATCHES

#### I3: detect_migrate_range out-of-scope; migration still replays at Phase 4
- **Plan claim**: `detect_migrate_range` (`:709`+) untouched; the skip is in the startup drift gate (`--mode full`) only, not the migrate-range computation (Out-of-scope, D3, Validation).
- **Actual entry point**: `detect_migrate_range` @ `:709`+ (function body documented `:708`-`:760`); dispatched only by `migrate-range)` arm @ `:2131`-`2132`.
- **Caller analysis**: The skip-#2 block lives inside `detect_drift`, invoked only by the `full)` arm (`:2124`). `--mode migrate-range` runs `detect_migrate_range`, which has its own §1.6(h) walk (with the sanctioned `STAMPED_PAIRS` extension) and `MR_*` outputs, and never calls `detect_drift`. So `/migrate-workflow` Step 2's range computation is unaffected; a user who runs migration at Phase 4 still gets the full computed range.
- **Breaking change risk**: none — disjoint code paths and modes.
- **Verdict**: MATCHES
