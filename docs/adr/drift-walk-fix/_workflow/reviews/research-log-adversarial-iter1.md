<!-- MANIFEST
verdict: PASS
findings: 5   severity: {blocker: 0, should-fix: 3, suggestion: 2}
index:
  - {id: A1, sev: should-fix, loc: "research-log.md:35 (D1)", anchor: "### A1 ", cert: C1, basis: "D1 rationale claims uniform Phase-4 coverage; placement after :618 makes coverage of unstamped/empty-input cases ordering-dependent, not uniform"}
  - {id: A2, sev: should-fix, loc: "research-log.md:126 (OQ1)", anchor: "### A2 ", cert: C2, basis: "OQ1 offers a staging-vs-live user choice §1.7(k) criterion 1 forecloses (this edits the drift-gate format); load-bearing OQ on D3 must resolve into the Decision Log or be waived"}
  - {id: A3, sev: should-fix, loc: "research-log.md:74 (D3)", anchor: "### A3 ", cert: C3, basis: "D3 rationale 'opt-out is prose-rule-only, does not cover a script' is imprecise — §1.7(k):1327 lists .claude/scripts/** as opt-out-eligible; the real disqualifier is criterion 1 (drift-gate format)"}
  - {id: A4, sev: suggestion, loc: "research-log.md:108 (S3/D1)", anchor: "### A4 ", cert: C4, basis: "Cheap addition: a second regression test pinning the empty-input Phase-4 case (phase=D ledger, no stampable artifact) guards the :612-before-skip ordering D1 depends on"}
  - {id: A5, sev: suggestion, loc: "research-log.md:101 (S2)", anchor: "### A5 ", cert: C5, basis: "S2 leaves the §1.6(h) conformance-test scope 'confirm at plan time' — verified here as walk-region-scoped, so the deferral is closeable now"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 5}
cert_index:
  - {id: C1, verdict: WEAK,  anchor: "#### C1 "}
  - {id: C2, verdict: WRONG, anchor: "#### C2 "}
  - {id: C3, verdict: WEAK,  anchor: "#### C3 "}
  - {id: C4, verdict: MATCHES, anchor: "#### C4 "}
  - {id: C5, verdict: MATCHES, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

### A1 [should-fix]
**Certificate**: C1 (Challenge: Decision D1 — skip-#2 placement and emitted scalars)
**Target**: Decision D1
**Challenge**: D1's rationale says placing the skip after the empty-input return "covers the stamped, unstamped, and merge-base-failed Phase-4 cases uniformly." The word *uniformly* overclaims and obscures the placement's real load-bearing property. With the skip at line 619 (after the empty-input return at `:617`/`:618`, before the unstamped short-circuit at `:620`), the four Phase-4 sub-cases are covered by two different mechanisms, not one uniform fold:
- The **empty-input Phase-4 case** (a `phase=D` branch whose only `_workflow/` artifact is the unstamped ledger, or a `minimal` tier with no stampable artifact) never reaches the new skip — the empty-input return at `:612`-`:617` fires first and folds to `detected=false, kind=""` (null). That is the correct outcome, but it comes from the pre-existing path, not from skip #2. So skip #2 does not "cover" it.
- The **unstamped** and **merge-base-failed** Phase-4 cases are covered *only because* the skip sits before the `:620` unstamped short-circuit and before the fold. That ordering is load-bearing: move the skip one block later (after `:620`) and an unstamped Phase-4 artifact would route to `detected=true, kind="unstamped"` and derail Phase 4 — the very failure the issue describes. D1's prose treats the ordering as incidental ("placed before the fold, it covers ... uniformly") when it is in fact the decision's safety-critical hinge.
**Evidence**: `workflow-startup-precheck.sh:612-618` (empty-input return, folds to `kind=""`), `:620-627` (unstamped short-circuit → `detected=true`), `:634` (fold), `:664-693` (empty-range skip #3). The doc (`workflow-drift-check.md:212-224`) states skip #2 "fires when ... resolved `phase` is `D` ... or `Done`" with no stamp-state qualifier, so routing an unstamped Phase-4 artifact to detected is a real doc violation a later placement would reintroduce.
**Proposed fix**: Rewrite D1's `**Why:**` to state the ordering dependency explicitly: the skip must sit *before* the unstamped short-circuit (`:620`) so the unstamped and merge-base-failed Phase-4 cases fold to `detected=false`; the empty-input Phase-4 case is handled by the pre-existing `:612` return and is intentionally out of skip #2's reach. Drop the "uniformly" framing. The decision (the placement line) is correct as written — only the rationale needs strengthening to name the hinge a future edit must not break. Decision survives (WEAK → strengthen rationale).

### A2 [should-fix]
**Certificate**: C2 (Assumption test: OQ1 — staging vs live is a user choice)
**Target**: Open Question OQ1 (bearing on Decision D3)
**Challenge**: OQ1 frames staging-vs-live as a trade-off to "surface ... and let the user pick at the Step-4 tier/mode confirmation." But the convention forecloses the choice: this branch edits `.claude/scripts/workflow-startup-precheck.sh`, which *is* the drift-gate format. `conventions.md §1.7(k):1365-1367` lists the opt-out's criterion 1 as "It changes **no `_workflow/**` artifact schema** — no track-file section, resume-state field, **drift-gate format**, or stamp format moves," and criterion 2 (`:1368-1372`) excludes "files a running phase reads as executable procedure." A fix to the drift walk's skip logic moves the drift-gate behavior and is executable procedure, so it fails *both* opt-out criteria by rule. There is no sanctioned live-edit path for the user to pick; offering one as an open trade-off invites a non-conforming answer. Per the research-log gate contract, an Open Question that bears on a load-bearing decision (D3 = staging mode) is a not-yet-made decision and must be resolved into the Decision Log or explicitly waived before the gate clears.
**Evidence**: `conventions.md §1.7(k):1325-1376` (opt-out criteria 1 and 2); `research-log.md:74-88` (D3 already decides staging); `research-log.md:126-132` (OQ1 leaves the trade-off open for the user).
**Proposed fix**: Resolve OQ1 into the Decision Log: the convention leaves no opt-out for a drift-gate-format edit, so D3 (staging) is forced, not discretionary. Either fold OQ1 into D3 as "no user choice exists — §1.7(k) criterion 1 disqualifies opt-out for a drift-gate-format edit," or keep the Step-4 confirmation as a *mode announcement* (staging, by rule) rather than a *pick*. If the user is still to be consulted, scope the consultation to tier confirmation, not staging-vs-live.

### A3 [should-fix]
**Certificate**: C3 (Challenge: Decision D3 — §1.7 staging mode = staged)
**Target**: Decision D3
**Challenge**: D3's `**Why:**` reads "the §1.7(k) opt-out is prose-rule-only (judgment-layer markdown), so it does not cover a script." That premise is inaccurate as stated: `conventions.md §1.7(k):1326-1327` explicitly lists `.claude/scripts/**` among the paths the opt-out *can* edit live ("may opt out of the staging machinery and edit `.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**`, and `.claude/scripts/**` live"). The opt-out is gated on the *consumer class* of the edited file, not its extension — a script whose only consumer is judgment-layer prose could in principle qualify. D3 reaches the right conclusion (staging) but via a false generalization; the correct, unassailable disqualifier is the one A2 cites: criterion 1 ("the drift-gate format") and criterion 2 (executable procedure). Grounding D3 on "scripts are never opt-out-eligible" would mislead a future scripts-only prose-adjacent branch.
**Evidence**: `conventions.md §1.7(k):1326-1327` (scripts are in the opt-out's editable set), `:1362-1376` (consumer-class criteria, the real gate); contrast `research-log.md:80-82` (D3's "prose-rule-only ... does not cover a script").
**Proposed fix**: Replace D3's "the opt-out is prose-rule-only, so it does not cover a script" with the precise disqualifier: "this branch edits the drift-gate format (`§1.7(k)` criterion 1) and an executable-procedure file (criterion 2), so the opt-out is unavailable; staging is the only sanctioned mode." Decision survives (WEAK → fix the rationale, keep the choice).

### A4 [suggestion]
**Certificate**: C4 (Assumption test: S3 — no existing test exercises the bug's exact state, and the regression test fills the gap)
**Target**: Surprise S3 / Decision D1 (regression-test model)
**Challenge**: The acceptance criteria and S3 name one regression test: ledger tail `D` + a stamped artifact + in-range workflow commits → `detected=false`. That pins the *non-empty-range* Phase-4 case. But A1 shows the empty-input Phase-4 case (ledger `D`/`Done` with no stampable artifact) is handled by a *different* code path (`:612` empty-input return) that fires *before* skip #2. The existing `test_minimal_no_plan_ledger_phase_done_is_done` (stub, `:322-336`) covers a `phase=Done` ledger with no artifact but asserts only `state`, not `drift`, and pre-dates the skip. No test asserts that the empty-input Phase-4 case still folds to `detected=false, kind=null` *and* does not accidentally fall into skip #2. A one-line cheap addition — a second regression test pinning that case — guards the `:612`-before-`:619` ordering A1 identifies as load-bearing, at near-zero cost.
**Evidence**: `workflow-startup-precheck.sh:612-618`; existing tests `test_workflow_startup_precheck.py:2125-2181` (State D/Done, stamp == HEAD, assert `state` only), `test_workflow_startup_precheck_stub.py:322-336` (phase=Done, no artifact, asserts `state`). Decision survives; the addition is value-adding, not corrective (MATCHES).
**Proposed fix**: Note in the plan (or D1) that the regression-test set should include the empty-input Phase-4 variant (ledger `D`/`Done`, no stampable artifact → `detected=false, kind=null`) alongside the in-range variant, so the ordering invariant A1 names is pinned. Optional, not gating.

### A5 [suggestion]
**Certificate**: C5 (Assumption test: S2 — the fix lands outside the §1.6(h) byte-copied walk region)
**Target**: Surprise S2
**Challenge**: S2 ends "(Confirm the conformance test's exact scope at plan time.)" — leaving a verification deferred. The deferral is closeable now: the §1.6(h) conformance test extracts only the `for f in $(ls ...)` walk loops (`_extract_all_ls_walks` / `_extract_script_walks`, `test_workflow_startup_precheck.py:3048-3142`) and the canonical glob set + anchored regex (`test_conformance_glob_set_matches_canonical:3186`, `test_conformance_anchored_regex_matches_canonical:3210`). The skip-#2 block D1 adds (a `ledger_tail_value` call + a `phase` test at line 619) sits between the walk's closing `done` (`:608`) and the unstamped check (`:620`) — outside every extracted region. The conformance fixture keys on the `ls`-walk bytes and the regex literal, neither of which the fix touches. S2's claim holds; the deferral can be marked resolved rather than carried into planning.
**Evidence**: `test_workflow_startup_precheck.py:3048-3084` (walk extraction scoped to `ls` loops through `done`), `:3186-3231` (glob-set and regex conformance assertions); `workflow-startup-precheck.sh:594-609` (the byte-copied walk region), `:619` (where the fix lands, outside it). Assumption HOLDS (MATCHES).
**Proposed fix**: Resolve S2's parenthetical now: state that the conformance test scopes to the `ls`-walk loops and the glob/regex literals, so a skip block placed at `:619` is provably outside its assertion surface. No code change; closes a dangling verification.

## Evidence base

#### C1 Challenge: Decision D1 — skip-#2 placement and emitted scalars in detect_drift
- **Chosen approach**: Place the Phase-4-active skip immediately after the empty-input no-drift return (`:618`) and before the unstamped short-circuit (`:620`); call `ledger_tail_value phase`, fold `D`/`Done` to `DRIFT_DETECTED=false, DRIFT_KIND="stamped"`, return. Rationale claims it "covers the stamped, unstamped, and merge-base-failed Phase-4 cases uniformly."
- **Best rejected alternative**: D1's own rejected alt (a) — place at the non-empty-range detected point (`:701`). D1 correctly rejects it (covers only the stamped case, misses unstamped/merge-base-failed, runs wasteful normalization). The stronger challenge is not against the placement but against the *rationale*'s "uniformly."
- **Counterargument trace**:
  1. The empty-input Phase-4 case (ledger `D`, no stampable artifact) hits the empty-input return at `:612` and folds to `kind=""` (null) — `workflow-startup-precheck.sh:615-617`. It never reaches skip #2.
  2. The unstamped/merge-base-failed Phase-4 cases reach skip #2 only because it precedes `:620`. The coverage is ordering-dependent, not a property of the skip itself.
  3. So "uniformly" is false: two cases are covered by the pre-existing `:612` path and the ordering relative to `:620`, not by one uniform fold. The decision is correct; the rationale misdescribes why.
- **Codebase evidence**: `workflow-startup-precheck.sh:612-627` (the two paths that bracket the skip site); `workflow-drift-check.md:212-224` (skip #2 doc, no stamp-state qualifier → unstamped Phase-4 must fold to detected=false, which only the before-`:620` placement achieves).
- **Survival test**: WEAK — the placement survives; the rationale needs strengthening to name the before-`:620` ordering as the safety hinge and to stop claiming uniform coverage.

#### C2 Assumption test: OQ1 — staging vs live is a user choice
- **Claim**: D3 recommends staging "by the book," and OQ1 holds that a live edit is "harmless in practice," to be surfaced to the user at the Step-4 confirmation as a pick.
- **Stress scenario**: The user, told the live edit is harmless, picks "edit live." The branch then edits `.claude/scripts/workflow-startup-precheck.sh` — the drift-gate format — live, with no staging marker.
- **Code evidence**: `conventions.md §1.7(k):1365-1367` — opt-out criterion 1 fails on any change to "the drift-gate format"; `:1368-1372` — criterion 2 fails on executable-procedure files. `§1.7(b):1038-1048` frames omitting the staging marker on a workflow-modifying path as forfeiting the mechanism (a failure mode), not a sanctioned choice. There is no rule under which "edit live" is conformant here.
- **Verdict**: BREAKS — OQ1 frames a choice the convention forecloses. As a load-bearing OQ on D3, it must resolve into the Decision Log (no choice exists) or be explicitly waived, not be left as a user pick.

#### C3 Challenge: Decision D3 — §1.7 staging mode = staged
- **Chosen approach**: Stage the script edit under `_workflow/staged-workflow/.claude/scripts/…`, carry `s17=staged`, promote at Phase 4.
- **Best rejected alternative**: The §1.7(k) prose-rule opt-out (edit live). D3 rejects it on the grounds "the opt-out is prose-rule-only ... so it does not cover a script."
- **Counterargument trace**:
  1. `conventions.md §1.7(k):1326-1327` explicitly lists `.claude/scripts/**` in the opt-out's editable set, so "does not cover a script" is false as a general statement.
  2. The opt-out gates on consumer class (`:1362-1376`), not file extension; a judgment-layer-only script could qualify.
  3. The real reason this script is ineligible is criterion 1 (drift-gate format) + criterion 2 (executable procedure) — a narrower, correct disqualifier. D3's broad premise would misguide a future scripts-only-but-prose-adjacent branch.
- **Codebase evidence**: `conventions.md §1.7(k):1326-1327` (scripts editable under opt-out), `:1365-1372` (the actual criteria).
- **Survival test**: WEAK — the decision (staging) is right; the rationale rests on a false generalization and should be re-grounded on criterion 1.

#### C4 Assumption test: S3 — no existing test exercises the bug's exact state, the regression test fills the gap
- **Claim**: One regression test (ledger `D` + stamped artifact + in-range commits → not detected) fills the gap, and the fix breaks no existing test.
- **Stress scenario**: A future edit moves the skip block below `:618` or accidentally makes a `phase=D` empty-input branch fall into skip #2 instead of the `:612` return. The single in-range regression test would not catch the empty-input ordering regression.
- **Code evidence**: `workflow-startup-precheck.sh:612-618` (empty-input path, reached before the skip); existing `test_workflow_startup_precheck.py:2125-2181` and `test_workflow_startup_precheck_stub.py:322-336` assert `state` only on `D`/`Done`, not `drift`, and pre-date the skip — so the "fix breaks no existing test" claim verifies (these stamp at HEAD → empty range, and the empty-input/Done cases hit `:612` before the skip).
- **Verdict**: HOLDS — S3 is correct that the fix breaks nothing. The cheap addition (a second regression test for the empty-input Phase-4 variant) is value-adding, not a defect; hence suggestion, not should-fix.

#### C5 Assumption test: S2 — the fix lands outside the §1.6(h) byte-copied walk region
- **Claim**: The skip block sits past the byte-copied walk (`:594`-`:609`), so the §1.6(h) source-conformance test is not perturbed; "confirm the conformance test's exact scope at plan time."
- **Stress scenario**: The conformance test compares whole regions of the script against conventions.md; a new statement near the walk could shift line offsets the test keys on.
- **Code evidence**: `test_workflow_startup_precheck.py:3048-3084` extracts only `for f in $(ls ...)` loops through their closing `done`; `:3186` and `:3210` assert the glob set and the anchored regex literal. None keys on absolute line offsets; the skip block at `:619` is outside every extracted `ls`-loop region.
- **Verdict**: HOLDS — S2's claim is correct and its deferred confirmation is resolvable now (the test is walk-region-scoped, not offset-scoped).
