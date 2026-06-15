<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: T4, sev: suggestion, loc: "implementation-plan.md:280 (D13 Implemented-in)", anchor: "### T4 ", cert: "Mirror drift: plan-side D13 retains the imprecise phrasing T1 fixed in the track file", basis: "The track-file D13 (canonical) was corrected, but the plan's derived-mirror copy of D13 still reads 'precheck.sh drift fold' — the exact wording T1 flagged; cosmetic mirror lag, not a regression"}
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

## Findings

### T4 [suggestion]
**Certificate**: Derived-mirror consistency (D1 — the plan mirrors the canonical track Decision Records).
**Location**: `docs/adr/no-track-for-minimal/_workflow/implementation-plan.md:280` — D13 "Implemented in: Track 1 (conventions §1.6(f), precheck.sh drift fold)".
**Issue**: The T1 fix corrected the **canonical** D13 in `track-1.md` (lines 147-151: "this track (conventions §1.6(f) exclusion entry). No `detect_drift` code change is needed … excluded by omission") and the Plan-of-Work step 1 drift clause. The plan's derived-mirror copy of D13 at line 280 still carries the pre-fix phrasing "precheck.sh drift fold" — the exact wording T1 named as misleading (it implies a `detect_drift` code edit that does not exist). Under D1 the plan is a derived mirror and the track file is canonical, so the implementer reads the corrected track-file D13, not the plan copy; the stale plan line cannot misroute the work. This is mirror lag, not a regression introduced by the fix — the plan-side copy was already imprecise at iteration 1 and the T1 finding scoped its Location and Proposed-fix to the track file only. Surfaced here because a verification pass that re-reads the canonical fix also notices its un-synced mirror.
**Proposed fix**: When the plan is next regenerated from the tracks (or in a one-line touch), reword the plan's D13 line 280 "Implemented in" to match the track file: "Track 1 (conventions §1.6(f) exclusion entry; no `detect_drift` code change — the ledger is excluded by omission from the hardcoded walk glob)". Optional and non-blocking; the canonical fix is complete.

## Evidence base

#### Verify T1: "add the ledger to the drift logic" imprecision
- **Original issue**: D13 "Implemented in: this track (conventions §1.6(f), precheck.sh drift fold)" and Plan-of-Work step 1 "Add the ledger to the drift logic as an unstamped artifact" both implied a `detect_drift` code edit. The script's walk (`workflow-startup-precheck.sh:488-491`) enumerates a hardcoded four-path glob (`implementation-plan.md`, `design.md`, `design-mechanics.md`, `plan/track-*.md`); `phase-ledger.md` is excluded by absence. Adding it to the glob would trip the unstamped short-circuit (lines 510-516) — the opposite of D13's intent.
- **Fix applied**: Track-file D13 "Implemented in" reworded to "this track (conventions §1.6(f) exclusion entry). No `detect_drift` code change is needed: its walk enumerates a hardcoded artifact list … so the ledger is excluded by omission — the implementer confirms the ledger filename is not added to that list." Plan-of-Work step 1 reworded to "Record the ledger as an unstamped artifact in the §1.6(f) exclusion list (doc-only); no `detect_drift` code change is needed because its walk enumerates a hardcoded artifact list that does not include the ledger filename (T1/A4 — the implementer confirms the omission holds)."
- **Re-check**:
  - Track-file location: `track-1.md` D13 lines 147-151; Plan-of-Work step 1 lines 246-249.
  - Current state: both now state "doc-only" / "No `detect_drift` code change" and name the §1.6(f) exclusion list as the implements-in, with "excluded by omission" rationale. The misleading "drift fold" / "Add the ledger to the drift logic" phrasing is gone from the track file.
  - Criteria met: the implementer can no longer read this as a no-op script edit; the work is correctly scoped to conventions §1.6(f) prose. Verified against the live script: the hardcoded glob at `workflow-startup-precheck.sh:488-491` is exactly the four paths the fix cites, and the unstamped short-circuit at 510-516 fires only for enumerated files — confirming the "excluded by omission" claim is accurate.
- **Regression check**: Checked D13's Risks/Caveats ("none material"), the Validation line ("the ledger is reported unstamped without tripping the drift gate", line 312), and the §1.6(f) exclusion-list home referenced in Plan-of-Work step 3 (line 261). All coherent with the no-code-change framing. The Validation line "reported unstamped without tripping the drift gate" is now consistent with the corrected mechanism (excluded by omission, so the walk never sees it). Clean.
- **Verdict**: VERIFIED

#### Verify T2: no-plan `minimal` resume re-derives the active track from a missing Checklist
- **Original issue**: The active track is resolved in two Checklist-driven places — the script's `determine_state` (walks `## Checklist` to locate the track file) and the agent-side re-derivation at workflow.md §Startup Protocol step 5 ("no `state.track` field — re-derive … by walking the plan's `## Checklist`"). Under D2 the `minimal` tier has no plan and therefore no Checklist, so both lose their source. Plan-of-Work step 6 framed only the script-feed half, leaving a `minimal` resume with no documented agent-side fallback.
- **Fix applied**: Plan-of-Work step 6 (lines 276-281) now states: "For the no-plan `minimal` resume the active track is `track-1` by construction (single-track tier; D10's `plan/track-1.md` secondary signal), so the agent-side active-track re-derivation that walks the plan `## Checklist` (workflow.md §Startup Protocol step 5) is gated to `lite`/`full` — `minimal` has no Checklist to walk." The `minimal`-resume Validation bullet (lines 307-309) adds "with the active track resolved as `track-1` (no Checklist walk)."
- **Re-check**:
  - Track-file location: Plan-of-Work step 6 lines 276-281; Validation lines 307-309.
  - Current state: the fix picks resolution path (a) from the proposed fix's menu — gate the Checklist walk to `lite`/`full`, resolve `minimal` to `track-1` by construction — rather than (b) emitting a `state.track` field. This is the cheaper option: it leaves `determine_state`'s STATE_JSON shape `{phase, substate}` untouched and does not invalidate workflow.md's "no `state.track` field" prose.
  - Criteria met: the `minimal`-resume active-track gap is closed with an explicit, settled rule; the Contracts block no longer needs a STATE_JSON shape change for Track 2 to branch on.
- **Regression check** (the prompt's named regression risk — "step 6 doesn't contradict workflow.md §Startup Protocol step 5"): Read the live workflow.md step 5 (lines 272-278). It reads "There is **no `state.track` field** — re-derive the active track by walking the plan's `## Checklist` for the first `[ ]` track, the same walk the script used." The fix's option (a) does NOT contradict this — it *gates* the existing walk to `lite`/`full` and adds a `minimal` branch (`track-1` by construction). Because step 6's edit-target IS workflow.md step 5 (the track lists workflow.md in-scope and step 6 names §Startup Protocol), the develop-state step-5 prose is what the implementer will edit to add the tier gate; the track description and the live prose are consistent in that the develop prose is the unconditional starting point the fix conditionalizes. No contradiction: the fix extends, not opposes, step 5. Cross-checked D10 (plan line 235, "the `plan/track-1.md` glob is the secondary signal") — the "by construction" derivation rests on D10's documented secondary signal, so it is grounded. Clean.
- **Verdict**: VERIFIED

#### Verify T3: ledger event grammar had no frozen example
- **Original issue**: The cross-track contract (the ledger event grammar Track 2's readers grep) was described only as a list of fields to "decide and pin" inside Plan-of-Work step 1; no frozen example line showed the on-disk shape, so Track 2's Phase-A review would verify reader greps against prose, not a fixed string.
- **Fix applied**: The `## Interfaces and Dependencies` Contracts block (lines 370-375) now carries an illustrative example line plus the key set:
  `[2026-06-15T16:42Z] [ctx=safe] phase=A track=1 tier=full categories="Workflow machinery,Architecture" s17=b`
  — annotated "key set `phase` / `track` / `tier` / `categories` / `s17` / `paused`", with the caveat "exact token spelling is pinned in Plan-of-Work step 1; that as-built grammar is what Track 2's Phase A reviews against."
- **Re-check**:
  - Track-file location: Contracts block, lines 367-378.
  - Current state: a concrete grep-able line now exists in the track file, with an explicit "illustrative / exact spelling pinned in step 1" disclaimer so it does not over-freeze the grammar that step 1 still owns.
  - Criteria met: Track 2's Phase-A review now has a fixed example string to verify reader greps against, hardening the cross-track contract (T3's stated goal).
  - **Internal-consistency check of the new example** (the prompt's named regression risk): the example line's shape matches the grammar described elsewhere in the same block — "one entry per line, `[<ISO>] [ctx=<level>] key=value …`, last-value-wins per key on read." The example has: `[<ISO>]` = `[2026-06-15T16:42Z]` (matches the D6 "ISO timestamp"); `[ctx=<level>]` = `[ctx=safe]` (matches D6's "`[ctx=…]` marker"); `key=value …` = `phase=A track=1 tier=full categories="…" s17=b`. Every key in the annotated key set (`phase`/`track`/`tier`/`categories`/`s17`/`paused`) is either present in the example (`phase`, `track`, `tier`, `categories`, `s17`) or correctly noted as optional/event-only (`paused` is the optional paused event per the `--append-ledger` signature bullet just above, line 369). The `s17=b` token aligns with Plan-of-Work step 1's vocabulary "§1.7 mode" and D14's `§1.7(b)` marker. Cross-checked against D6 (track-file lines 94-107) "Each entry carries an ISO timestamp, a `[ctx=…]` marker, the phase, an optional active track, and optional field updates" — the example is a faithful instance. Internally consistent.
- **Regression check**: Checked the `--append-ledger` signature bullet (line 368, "phase, optional track, tier + matched categories, §1.7 mode, paused event") against the example's key set — they correspond field-for-field (`tier + matched categories` → `tier=` + `categories="…"`; `§1.7 mode` → `s17=`; `paused event` → the optional `paused` key). No drift between the subcommand-signature contract and the grammar-example contract. Clean.
- **Verdict**: VERIFIED

#### New T4: plan-side D13 mirror drift (detail)
- **Trigger**: Re-reading the canonical T1 fix in `track-1.md` D13, the verification cross-checked the plan's mirror copy of D13.
- **Code path trace**: `implementation-plan.md:280` D13 "Implemented in: Track 1 (conventions §1.6(f), precheck.sh drift fold)" vs. the corrected `track-1.md` D13 "this track (conventions §1.6(f) exclusion entry). No `detect_drift` code change is needed …". The plan copy retains "precheck.sh drift fold", the exact phrasing T1 flagged.
- **Outcome**: Under D1 the track file is canonical and the plan is a derived mirror; the implementer reads the track-file D13, so the stale plan line cannot misroute the work. Pre-existing at iteration 1 (T1 scoped to the track file only), so not a regression. Suggestion-severity cosmetic mirror lag. → T4.
- **Track coverage**: the canonical fix in the track file is complete; the plan copy is out of T1's stated scope.
