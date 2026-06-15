<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: T1, sev: should-fix, loc: ".claude/scripts/workflow-startup-precheck.sh:474 (detect_drift); track-1.md D13 / Plan-of-Work step 1", anchor: "### T1 ", cert: "Premise: ledger excluded from drift walk", basis: "D13 implements-in names a precheck.sh drift-fold edit, but the ledger is excluded by absence from the hardcoded walk glob; no detect_drift change is needed, risking a no-op script edit at decomposition"}
  - {id: T2, sev: should-fix, loc: ".claude/workflow/workflow.md:275 (Startup Protocol step 5); .claude/scripts/workflow-startup-precheck.sh:1439 (determine_state STATE_JSON)", anchor: "### T2 ", cert: "Integration: agent re-derives active track by walking Checklist", basis: "Script emits {phase,substate} with no track field; agent re-derives the active track by walking the plan Checklist, which a no-plan minimal resume lacks; track step 6 names only the script feed, not the agent-side re-derivation"}
  - {id: T3, sev: suggestion, loc: "track-1.md Plan-of-Work step 1; Contracts block", anchor: "### T3 ", cert: "Premise: --append-ledger absent today", basis: "The event grammar Track 2 consumes is pinned inside step 1 but not surfaced as a frozen example in the track file; an example line would harden the cross-track contract"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 9}
cert_index:
  - {id: P1, verdict: CONFIRMED, anchor: "#### P1 "}
  - {id: P2, verdict: CONFIRMED, anchor: "#### P2 "}
  - {id: P3, verdict: CONFIRMED, anchor: "#### P3 "}
  - {id: P4, verdict: CONFIRMED, anchor: "#### P4 "}
  - {id: P5, verdict: CONFIRMED, anchor: "#### P5 "}
  - {id: P6, verdict: CONFIRMED, anchor: "#### P6 "}
  - {id: P7, verdict: CONFIRMED, anchor: "#### P7 "}
  - {id: P8, verdict: PARTIAL, anchor: "#### P8 "}
  - {id: P9, verdict: CONFIRMED, anchor: "#### P9 "}
  - {id: I1, verdict: MISMATCHES, anchor: "#### I1 "}
  - {id: I2, verdict: MATCHES, anchor: "#### I2 "}
  - {id: E1, verdict: CONFIRMED, anchor: "#### E1 "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: Premise P8 (ledger exclusion from the drift walk) + Premise P7 (the §1.6(f) exclusion prose).
**Location**: track-1.md D13 ("Implemented in: this track (conventions §1.6(f), precheck.sh drift fold)") and Plan-of-Work step 1 ("Add the ledger to the drift logic as an unstamped artifact (it is not folded into the §1.6(h) stamp walk)"); against `.claude/scripts/workflow-startup-precheck.sh:474` `detect_drift`.
**Issue**: `detect_drift` enumerates a **hardcoded** four-path glob (`implementation-plan.md`, `design.md`, `design-mechanics.md`, `plan/track-*.md`) at lines 488-491. The same enumeration recurs at all three script walks (drift walk, migrate-range walk, no-drift normalization recompute) — the §1.6(f) `research-log.md` exclusion prose says exactly this. `phase-ledger.md` is not in that glob, so it is excluded from the walk **by absence**: no `detect_drift` edit makes it unstamped, and no edit is needed to keep it from tripping the unstamped short-circuit (lines 510-516, which fires only for files the walk actually enumerated). D13's "precheck.sh drift fold" implements-in and step 1's "Add the ledger to the drift logic" phrasing both imply a script change to `detect_drift` that does not exist and, if attempted, would be a no-op or a regression (adding the ledger to the glob would make it trip the unstamped short-circuit — the opposite of D13's intent). The genuine D13 work is conventions-prose only: extend the §1.6(f) exclusion list to name the ledger and confirm the "three walks / four-type enumeration" rationale prose stays coherent with one more excluded-by-absence artifact.
**Proposed fix**: Reword D13's "Implemented in" to "this track (conventions §1.6(f) exclusion-list entry)" and drop "precheck.sh drift fold"; reword Plan-of-Work step 1's drift clause to "Document the ledger in the §1.6(f) exclusion list as an unstamped artifact excluded by absence from the §1.6(h) walk glob — no `detect_drift` edit is required." If decomposition still wants a script-level guarantee, add a test (not a code edit) asserting the ledger does not appear in `detect_drift`'s walk output and does not set `kind=unstamped`.

### T2 [should-fix]
**Certificate**: Integration I1 (agent re-derives the active track by walking the plan Checklist) + Premise P9 (`determine_state` STATE_JSON shape).
**Location**: `.claude/workflow/workflow.md:275-276` (Startup Protocol step 5: "There is **no `state.track` field** — re-derive the active track by walking the plan's `## Checklist` for the first `[ ]` track") and `.claude/scripts/workflow-startup-precheck.sh:1439` `determine_state` (STATE_JSON = `{phase, substate}`, no track field, line 1453 onward); against track-1.md Plan-of-Work step 6 and the Contracts block.
**Issue**: The active track is resolved in **two** places today, both Checklist-driven: (1) the script's `determine_state` walks `## Checklist` internally to find `track_num` so it can locate the track FILE for the State-C sub-state (lines 1476-1527); (2) the agent re-derives the active track AGAIN by walking the same `## Checklist` (workflow.md step 5, because the script emits no `track` field). Under D2 the `minimal` tier has no plan and therefore no `## Checklist`, so BOTH derivations lose their source. D3 states "the ledger owns the top-level phase and active track," and the Contracts block lists "the ledger event grammar `determine_state` greps … active track." But the track's Plan-of-Work step 6 ("State derivation reads the ledger tail rather than the plan checkboxes") frames only the **script-feed** half. It does not name the agent-side re-derivation in workflow.md step 5, which must either (a) be changed to read the active track from the ledger, or (b) be made conditional (Checklist for `lite`/`full`, ledger for `minimal`). Equivalently, `determine_state` could begin emitting the active track in STATE_JSON so the agent stops re-deriving — but that is a STATE_JSON contract change the track does not currently call out, and a `state.track` field would invalidate the workflow.md "no `state.track` field" prose and any test that pins the JSON shape. Left unaddressed, a `minimal` resume reaches step 5 with no Checklist to walk and no documented fallback.
**Proposed fix**: Add to Plan-of-Work step 6 (and the workflow.md edit it describes) an explicit decision for the **agent-side** active-track resolution: either (a) re-point workflow.md step 5's "re-derive by walking the `## Checklist`" to "read the active track from the ledger tail (for `minimal`); walk the `## Checklist` (for `lite`/`full`)", or (b) have `determine_state` emit the active track in STATE_JSON and update the workflow.md "no `state.track` field" prose plus the script's contract suite in the same step. Pick one and pin it in the Contracts block so Track 2's readers branch on a settled shape. Add a Validation line covering "a `minimal` resume resolves its active track with no `## Checklist` present."

### T3 [suggestion]
**Certificate**: Premise P6 (`--append-ledger` / `phase-ledger` absent in the script today).
**Location**: track-1.md Plan-of-Work step 1 ("Decide and pin the event vocabulary and field grammar … this grammar is the contract Track 2's readers consume") and the Contracts block.
**Issue**: The ledger event grammar is the load-bearing cross-track contract (Track 2 depends on it), yet the track file describes it only as a list of fields to "decide and pin" inside step 1 — there is no frozen example line in the track file showing the actual on-disk shape (ISO timestamp + `[ctx=…]` + phase + optional active track + optional field updates, per design.md and plan D6). Track 2's reader inventory (D4) will branch on the exact `grep`-able line shape and key set; a concrete example in Track 1's `## Interfaces and Dependencies` Contracts block would let Track 2's Phase-A review verify reader greps against a fixed string rather than a prose description, reducing the chance the two tracks pin slightly different grammars.
**Proposed fix**: Add one frozen example ledger line to the Contracts block (or `## Context and Orientation`), e.g. a representative `phase=` append with a tier line and the `[ctx=…]` marker, annotated as the canonical line shape Track 2's readers grep. This is a documentation hardening, not a scope change; the grammar is still decided in step 1.

## Evidence base

#### P1 Premise: the script is ~1,744 lines
- **Track claim**: "`.claude/scripts/workflow-startup-precheck.sh` (~1,744 lines)."
- **Search performed**: `wc -l .claude/scripts/workflow-startup-precheck.sh` (grep/Bash; workflow-machinery path check, not findClass).
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh` — 1744 lines.
- **Actual behavior**: Exactly 1744 lines.
- **Verdict**: CONFIRMED
- **Detail**: Exact match.

#### P2 Premise: determine_state around line 1439
- **Track claim**: "`determine_state` (around line 1439) resolves the plan dir from the branch, reads `implementation-plan.md`, parses the `## Plan Review` first-checkbox token via `section_first_checkbox_token`, then walks `## Checklist` … and reads the track file's `## Progress`."
- **Search performed**: `grep -n 'determine_state'` + Read of lines 1439-1565.
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:1439` (`determine_state() {`).
- **Actual behavior**: Resolves `plan_dir="docs/adr/${branch}"`, `plan_file=.../implementation-plan.md`; absent plan → State 0; `section_first_checkbox_token "$plan_file" "Plan Review"` (line 1456); walks `## Checklist` for the first `[ ]` track (lines 1476-1527); on a found track with a track file calls `determine_c_substate` which reads `## Progress` AND `## Concrete Steps` (joint read).
- **Verdict**: CONFIRMED
- **Detail**: Line number exact. The within-track read is over `## Progress` + `## Concrete Steps` jointly; the track's shorthand ("`## Progress` owns the within-track sub-state") matches the plan's Non-Goal which names both, so the shorthand is not a defect.

#### P3 Premise: detect_drift around line 474, folds _workflow/** stamps
- **Track claim**: "`detect_drift` (around line 474) folds the stamp of `_workflow/**` artifacts."
- **Search performed**: `grep -n 'detect_drift'` + Read of lines 474-600.
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:474` (`detect_drift() {`).
- **Actual behavior**: Byte-copied §1.6(h) walk over `implementation-plan.md`, `design.md`, `design-mechanics.md`, `plan/track-*.md` (lines 488-491); stamped SHAs folded via `fold_stamps_to_base`; any unstamped artifact short-circuits to `kind=unstamped`.
- **Verdict**: CONFIRMED
- **Detail**: Line number exact.

#### P4 Premise: --mode CLI case near line 60
- **Track claim**: "The CLI surface is a `--mode {full,divergence-only,migrate-range}` `case` near line 60 with `--bootstrap-sha` and repeatable `--exclude-sha`."
- **Search performed**: Read of lines 40-130.
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:60-93` (the `while`/`case "$1"` arg loop + the `case "$MODE"` validation).
- **Actual behavior**: `--mode`, `--bootstrap-sha`, repeatable `--exclude-sha` accumulator; mode validated against `full | divergence-only | migrate-range`.
- **Verdict**: CONFIRMED
- **Detail**: Matches exactly; the validation `case` is at line 86, the arg-loop `case` begins at line 61.

#### P5 Premise: the two named test files exist
- **Track claim**: "`test_workflow_startup_precheck.py` (the main suite) and `test_workflow_startup_precheck_stub.py` (the `minimal` stub-plan tests …). The stub tests assume a parsed `minimal` plan and must be reworked for the no-plan `minimal` resume."
- **Search performed**: `ls` of both files + Read of the stub file's docstring and test-function names.
- **Code location**: `.claude/scripts/tests/test_workflow_startup_precheck.py` (176 KB) and `.claude/scripts/tests/test_workflow_startup_precheck_stub.py` (21 KB).
- **Actual behavior**: The stub file's docstring states its premise: "the existing precheck script must read the stub's three state-bearing sections and resolve a valid resume state without any script change." A `StubPlanFixture` synthesizes a stub plan (tier line + `## Checklist` + `## Plan Review` + `## Final Artifacts`); five `test_minimal_stub_*` functions all assume a parsed `minimal` plan.
- **Verdict**: CONFIRMED
- **Detail**: The track's "must be reworked for the no-plan `minimal` resume" is accurate — the entire file premise (a synthesized stub plan) is invalidated by D2/D3 (minimal drops the plan; resume reads the ledger).

#### P6 Premise: --append-ledger / phase-ledger do not exist today
- **Track claim**: "Build the append-only phase ledger and its `--append-ledger` subcommand in `workflow-startup-precheck.sh`."
- **Search performed**: `grep -n 'append-ledger\|phase-ledger\|ledger' .claude/scripts/workflow-startup-precheck.sh`.
- **Code location**: NOT FOUND (no match).
- **Actual behavior**: No `ledger` token anywhere in the script. The subcommand is net-new.
- **Verdict**: CONFIRMED
- **Detail**: Planned by this track; no collision with an existing subcommand.

#### P7 Premise: §1.6(f) exclusion list + glossary terms are net-new
- **Track claim**: "§1.6(f) ledger exclusion (D13)"; "§1.1 glossary entries (phase ledger, derived-mirror plan, plan-review document, combined Invariants & Constraints section)."
- **Search performed**: `grep -n '1.6(f)'` + Read of §1.6(f) (lines 735-770); `grep -n 'phase ledger|derived-mirror|plan-review document|Invariants & Constraints'`.
- **Code location**: `.claude/workflow/conventions.md:735` (§(f) heading); §1.1 at line 67.
- **Actual behavior**: §1.6(f) positively enumerates four stamped types and excludes `design-final.md`/`adr.md`, `design-mutations.md`, `research-log.md`. The `research-log.md` exclusion prose explicitly states the four-type enumeration "recurs at all three `workflow-startup-precheck.sh` walks." None of the four new glossary terms exist yet.
- **Verdict**: CONFIRMED
- **Detail**: Net-new additions; no naming collision. The "three walks / four-type enumeration" prose is the load-bearing context for T1.

#### P8 Premise: the ledger is excluded from the §1.6(h) walk by absence (no script edit)
- **Track claim**: D13 "Implemented in: this track (conventions §1.6(f), precheck.sh drift fold)"; Plan-of-Work step 1 "Add the ledger to the drift logic as an unstamped artifact (it is not folded into the §1.6(h) stamp walk)."
- **Search performed**: Read of `detect_drift` (lines 474-600) — the walk glob and the unstamped short-circuit.
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:488-491` (hardcoded glob) and 510-516 (unstamped short-circuit).
- **Actual behavior**: The walk enumerates only the four hardcoded paths; `phase-ledger.md` is not among them, so the walk never sees it and the unstamped short-circuit (which fires only for enumerated files) never fires for it. Excluding the ledger requires NO `detect_drift` edit; adding it to the glob would, conversely, trip the unstamped short-circuit (the opposite of D13's intent).
- **Verdict**: PARTIAL
- **Detail**: D13's intent (ledger unstamped, no drift trip) is correct and feasible, but its "precheck.sh drift fold" implements-in and step 1's "Add the ledger to the drift logic" phrasing wrongly imply a `detect_drift` code edit. The real work is conventions-prose only. → T1.

#### P9 Premise: determine_state's STATE_JSON has no track field
- **Track claim**: "the ledger owns the top-level phase and active track" (D3); Contracts: "the ledger event grammar `determine_state` greps (line shape, key set, last-value-wins read)."
- **Search performed**: `grep -n 'STATE_JSON\|"track"\|state.track'` + Read of the STATE_JSON assignments (lines 1453-1556).
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:1453` onward.
- **Actual behavior**: Every `STATE_JSON` assignment is `{phase, substate}` only — no `track` field. The active track is re-derived agent-side (workflow.md step 5, line 275: "no `state.track` field — re-derive … by walking the plan's `## Checklist`").
- **Verdict**: CONFIRMED
- **Detail**: Establishes that "active track" lives in TWO Checklist-driven derivations, both of which lose their source when `minimal` drops the plan. → T2.

#### I1 Integration: agent re-derives the active track by walking the plan Checklist
- **Plan claim**: Plan-of-Work step 6 — "Workflow.md §Startup Protocol. State derivation reads the ledger tail rather than the plan checkboxes." D3 — "the ledger owns the top-level phase and active track."
- **Actual entry point**: `.claude/workflow/workflow.md:275-276` (Startup Protocol step 5) — the agent re-derives the active track by walking `## Checklist`; `.claude/scripts/workflow-startup-precheck.sh:1476-1527` — the script also walks `## Checklist` to locate the track file.
- **Caller analysis**: workflow.md §Startup Protocol step 5 is the orchestrator-side resume router (`roles=orchestrator`, all execution phases). It is in-scope for this track (workflow.md is in the in-scope list and step 6 names §Startup Protocol). The Checklist walk is the only documented active-track source today.
- **Breaking change risk**: For `minimal` (no plan, no Checklist) both the script's internal walk and the agent's step-5 re-derivation lose their source. The track's step 6 names only the script-feed reword, not the agent-side step-5 re-derivation, leaving a `minimal` resume with no documented active-track fallback.
- **Verdict**: MISMATCHES
- **Detail**: → T2. The script-feed framing is necessary but not sufficient; the agent-side re-derivation (or a new `state.track` emission) must be settled in the same edit.

#### I2 Integration: staged edits are excluded from the live drift gate
- **Plan claim**: plan `### Constraints` — "No live test runs against the staged script … exercised from the staged path, not wired into the live machinery"; track `## Context and Orientation` — "the live workflow stays at develop state."
- **Actual entry point**: `.claude/scripts/workflow-startup-precheck.sh:273` `WORKFLOW_PATHSPECS=".claude/workflow/ .claude/skills/ .claude/agents/"` and the drift `git log … -- $WORKFLOW_PATHSPECS` (line ~556).
- **Caller analysis**: The drift walk and the `git log` range both operate on live `.claude/**` paths with trailing slashes; the staged subtree under `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/**` is a different prefix and is excluded. The `staged-workflow/` directory does not exist yet (Phase B has not run), confirming this is iteration-1 against the live files.
- **Breaking change risk**: None — staged edits cannot trip the live drift gate during this branch.
- **Verdict**: MATCHES
- **Detail**: Confirms the staging constraint is enforceable against the current script.

#### E1 Edge case: torn (interrupted) ledger append
- **Trigger**: An `--append-ledger` write is interrupted between writing the temp file and the rename (crash, `/clear`, kill).
- **Code path trace**:
  1. Planned entry: `--append-ledger` writes a `.tmp` then `mv`s it (D3/D6 atomic temp-file-plus-rename).
  2. Precedent: `.claude/scripts/workflow-startup-precheck.sh:402-408` already uses `{ … ; } > "$f.tmp" && mv "$f.tmp" "$f"` in the stamp-rewrite path — a torn write leaves the original intact and at worst an orphan `.tmp`.
  3. On the next `--mode full`, `determine_state` reads the ledger tail (last-value-wins); a torn append leaves the prior tail intact.
- **Outcome**: Correct handling — the prior ledger tail survives and resolves the prior state. The track's Validation line ("An interrupted (torn) append leaves the prior ledger tail intact and `determine_state` resolves the prior state") matches.
- **Track coverage**: yes (D3 Risks/Caveats + the Validation criterion). The script already carries the atomic-write idiom this depends on, so the approach is feasible.
