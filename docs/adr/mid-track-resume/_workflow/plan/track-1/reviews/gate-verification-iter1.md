<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index:
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: A1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: T4, verdict: VERIFIED}
  - {id: R1, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Phase A combined gate verification — Track 1, iteration 1

All eleven accepted Phase-A findings (technical, risk, adversarial) re-check
clean against the edited track file. The branch is `§1.7(b)`-staged
(`s17=workflow-modifying`) with nothing yet under `_workflow/staged-workflow/`,
so every verification was performed against the LIVE
`.claude/scripts/workflow-startup-precheck.sh`, `.claude/workflow/conventions.md`,
`.claude/workflow/conventions-execution.md`, and
`.claude/scripts/tests/test_workflow_startup_precheck.py`. No new findings.

## Findings

(none)

## Evidence base

#### Verify T1: wrap-fix join terminator glob

- **Original issue**: The track stated the join terminator as `[0-9]*". "`
  (no trailing `*`), which a `case` glob anchors at both ends, so it matches
  only a string ending exactly at `. ` and never fires on a real next-step line.
- **Fix applied**: `## Plan of Work` item 6 sub-bullet 3 and its explanatory
  paragraph now use `[0-9]*". "*` (trailing `*`), with the rationale that a
  `case` glob is anchored both ends so the trailing `*` is load-bearing, and a
  citation of the live `roster_scan` column-0 guard precedent.
- **Re-check**:
  - Track-file location: lines 202, 211, 217, 286 — every load-bearing use is
    the `[0-9]*". "*` form. Line 217 cites the live guard as `[0-9]*". "*)`.
  - Live-script location: `workflow-startup-precheck.sh:1338` — the column-0
    guard in `roster_scan` is `[0-9]*". "*)` (the `case` opens at line 1337).
    The track's "~line 1337" reference matches within rounding.
  - Current state: the track's cited precedent (`[0-9]*". "*)`) is byte-exact to
    the live guard. The broken form `[0-9]*". "` appears only at lines 214 and
    287, both naming it as the form that "would fail" / "never fires" — never
    asserted as correct.
  - Criteria met: the load-bearing trailing `*` is present; the precedent
    citation is accurate; the broken form is correctly characterized as wrong.
- **Regression check**: Checked the `## Validation and Acceptance` wrapped-roster
  group — it gained a two-adjacent-wrapped-steps case (lines 281-287) asserting
  the terminator stops at the next column-0 step line so the two never merge.
  Consistent with the join logic in item 6. Clean.
- **Verdict**: VERIFIED

#### Verify A1: wrap-fix join terminator (adversarial, same fix as T1)

- **Original issue**: Same terminator-glob defect as T1, raised on the
  adversarial axis (a long enough description could break the join).
- **Fix applied**: Same `[0-9]*". "*` correction plus the two-adjacent-wrapped
  regression case.
- **Re-check**: Identical evidence to T1 above. The adversarial concern (two
  adjacent wrapped steps merging) is the exact scenario the new second regression
  case (lines 281-287) and invariant S5 (lines 348-350) now pin.
- **Regression check**: No new contradiction; S5 and the regression case align
  with item 6's terminator. Clean.
- **Verdict**: VERIFIED

#### Verify T2: conventions.md glossary key set

- **Original issue**: Ambiguity over whether the `{ phase, track, tier,
  categories, s17, paused }` brace key-set lives in the conventions.md
  Phase-ledger glossary row (where adding `substate` would drift it) or in the
  script-header grammar comment.
- **Fix applied**: `## Plan of Work` item 8 now states the conventions.md
  Phase-ledger glossary names the key set in prose, while the brace set lives in
  the script-header grammar comment (item 7's target).
- **Re-check**:
  - conventions.md location: line 89 (Phase-ledger glossary row). It names keys
    in prose — "the resume phase, the active track, the change tier and its
    matched categories, the §1.7 staging mode, and pause events" — with NO
    literal brace key-set. Matches item 8's claim.
  - Script-header location: `workflow-startup-precheck.sh:56` — "The key set is
    exactly { phase, track, tier, categories, s17, paused }." The brace set does
    live here, as item 8 states.
  - Current state: item 8 correctly routes the prose `substate` extension to the
    glossary and the brace-set edit to item 7's script-header target.
  - Criteria met: no glossary brace-set drift; the two homes are correctly
    distinguished.
- **Regression check**: Checked item 7 (script-header grammar comment) — it
  targets adding `substate` to the key set and the validated bare-token list,
  consistent with the line-56 brace set being its home. No double-edit and no
  contradiction. Clean.
- **Verdict**: VERIFIED

#### Verify R2: dual-path parity non-vacuity

- **Original issue**: A dual-path parity test could be vacuous if both arms read
  the ledger (the fallback arm never exercising the roster path).
- **Fix applied**: The dual-path parity Validation bullet now describes one
  track-file fixture run through two `write_ledger` ledger variants (one with
  `substate`, one without), names non-vacuity as a fixture property, states
  `determine_c_substate` reads no ledger, and says no new strip helper is needed.
- **Re-check**:
  - Track-file location: `## Validation and Acceptance` dual-path parity bullet
    (lines 272-280) and invariant S3 (lines 346-347).
  - Live-code location: `determine_c_substate` at
    `workflow-startup-precheck.sh:1713-1768` takes only `track_file` and reads
    `progress_entry_token`, `roster_scan`, `progress_step_numbers`,
    `step_num_in_progress` — all track-file readers. It never reads the ledger.
  - Current state: the bullet's reasoning matches the source. Non-vacuity holds
    because the fallback fixture's ledger omits `substate`, forcing the
    no-ledger `determine_c_substate` path; the "strip" is building the fixture
    ledger without the token, needing no new harness helper.
  - Criteria met: internally consistent; matches the live `determine_c_substate`
    (reads no ledger).
- **Regression check**: Checked the helper-attribution intro (lines 245-256) —
  it mandates `write_ledger` for ledger-path/dual-path groups, aligning with the
  parity bullet. Clean.
- **Verdict**: VERIFIED

#### Verify A2: dual-path parity non-vacuity (adversarial, same fix as R2)

- **Original issue**: Adversarial framing of the same vacuity risk — an attacker
  fixture leaving `substate` on both arms.
- **Fix applied**: Same dual-path parity rewrite naming non-vacuity as a fixture
  property and the token-omission strip.
- **Re-check**: Identical evidence to R2. The track explicitly states "a fallback
  fixture that left `substate` on its ledger would make both arms read the ledger
  and the assertion vacuous" (lines 278-280) — the exact adversarial case, now
  defended by construction.
- **Regression check**: No new issue; S3 invariant text matches. Clean.
- **Verdict**: VERIFIED

#### Verify A3: S1 decoy test (categories-embedded decoy)

- **Original issue**: The ledger-path tests did not prove the pre-`categories`
  read wins against a decoy `track=`/`substate=` smuggled inside a quoted
  `categories="…"` span.
- **Fix applied**: The ledger-path Validation group gained a `categories="…"`-
  embedded `track=`/`substate=` decoy case asserting the pre-`categories` read
  wins (lines 264-268).
- **Re-check**:
  - Track-file location: ledger-path bullet, S1-pinning case (lines 264-268) and
    item 3 emit-order rationale (lines 166-168).
  - Live-code location: emit order at `workflow-startup-precheck.sh:1622-1627` —
    `phase`/`track`/`tier` (bare) are appended BEFORE the single quoted
    `categories="…"` (line 1625); `s17`/`paused` follow. The reader comment
    (lines 1682-1685) confirms a decoy inside the quoted span is avoided only
    because the emitter writes bare tokens first and the reader takes the first
    ` key=` token.
  - Current state: item 3 places the new `substate` token in the pre-`categories`
    block alongside `phase`/`track`, so a bare `substate=` always precedes the
    one quoted field. The decoy test asserts the real bare tokens win — exactly
    the emit-order invariant the script relies on.
  - Criteria met: consistent with the emit-order invariant; the decoy case is
    well-formed against it.
- **Regression check**: Checked invariant S1 (lines 339-341) and the
  track-scoping ledger-path case (lines 261-263) — both align with the
  first-token / track-scoped read. Clean.
- **Verdict**: VERIFIED

#### Verify T3: helper attribution in the Validation intro

- **Original issue**: The test groups did not attribute which helper drives which
  path, risking a "ledger-path" test built without a `write_ledger` ledger that
  never exercises the new read.
- **Fix applied**: The Validation intro now mandates `write_ledger` for
  ledger-path/dual-path groups and notes `_substate` drives the legacy
  non-ledger walk.
- **Re-check**:
  - Track-file location: `## Validation and Acceptance` intro (lines 245-256).
    "the ledger-path and dual-path groups MUST drive the ledger through
    `write_ledger` … because `_substate` alone composes the track file and drives
    the legacy non-ledger walk."
  - Live-test location: `write_ledger` helper exists at
    `test_workflow_startup_precheck.py:462` (writes and commits a verbatim
    ledger). The attribution is sound.
  - Current state: the intro correctly partitions helper usage by path.
  - Criteria met: helper-per-group rule stated; load-bearing rationale given.
- **Regression check**: Checked against the dual-path parity bullet (uses
  `write_ledger` for both variants) — consistent. Clean.
- **Verdict**: VERIFIED

#### Verify R3: TESTS registry registration note

- **Original issue**: New `def test_*` functions could silently never run because
  the suite is not auto-collected.
- **Fix applied**: The Validation intro now notes new `def test_*` functions must
  be registered in the hand-maintained `TESTS` list.
- **Re-check**:
  - Track-file location: Validation intro (lines 253-256): "New test functions
    must be added to the hand-maintained `TESTS` registry … the suite is not
    auto-collected, so an unregistered `def test_*` silently never runs."
  - Live-test location: `test_workflow_startup_precheck.py:4101` declares
    `TESTS: List[Tuple[str, Callable[[], None]]] = [ … ]`, iterated at line 4217
    (`for name, fn in TESTS:`) and counted at lines 4228/4230. This is a
    hand-maintained registry; there is no auto-collection.
  - Current state: the note is accurate — the `TESTS` registry exists and is the
    sole run gate.
  - Criteria met: registration requirement correctly stated.
- **Regression check**: No new issue. Clean.
- **Verdict**: VERIFIED

#### Verify T4: STATE_JSON emit reuse and absent-track-file precedence

- **Original issue**: The ledger-first read needed to reuse the existing
  `jq -nc --arg` STATE_JSON emit, and the absent-track-file ledger precedence was
  undocumented.
- **Fix applied**: Item 5 now reuses the `jq -nc --arg s … '{phase:"C",
  substate:$s}'` construction and documents that a non-empty ledger `substate`
  resolves even when the track file is absent or unreadable.
- **Re-check**:
  - Track-file location: item 5 (lines 178-190).
  - Live-code location: `determine_state_from_ledger` `phase=C` arm at
    `workflow-startup-precheck.sh:1804-1821`. The fallback emit is
    `STATE_JSON="$(jq -nc --arg s "$C_SUBSTATE" '{phase:"C", substate:$s}')"`
    (line 1814) — the exact construction item 5 reuses. The absent-track-file
    branch (lines 1815-1820) currently emits State A; item 5's new ledger-first
    read runs BEFORE that `if [ -f "$track_file" ]` check, so a non-empty ledger
    `substate` resolves without touching the roster even when the track file is
    absent — the documented new precedence.
  - Current state: emit construction matches; the precedence is the intended new
    behavior and is internally consistent with the ledger-authoritative design
    (D1, item 5).
  - Criteria met: emit reuse exact; absent-track-file precedence documented.
- **Regression check**: Checked that the ledger-only read does not disturb the
  empty-`substate` fallback (which still depends on the track file, per item 5
  lines 188-190 and D2). Consistent. Clean.
- **Verdict**: VERIFIED

#### Verify R1 (addressed-by-process): hot-loop exposure of the wrap-fix

- **Original issue**: The `roster_scan` wrap fix runs on the resume hot path, so
  a defect would expose every resume; flagged as risk with no direct track-text
  fix beyond process mitigation.
- **Fix applied**: No track-text fix. Mitigation is risk-tagging the wrap-fix step
  `high` plus the new two-adjacent-wrapped regression test.
- **Re-check**:
  - Track-file location: item 6 (the wrap fix) is the step the decomposition will
    risk-tag `high`; the two-adjacent regression case (lines 281-287) and
    invariant S5 (lines 348-350) pin the join behavior on the hot path.
  - Current state: the mitigation is present as process (high risk tag + dedicated
    regression coverage). No track contradiction introduced.
  - Criteria met: risk acknowledged and covered by a regression assertion;
    the high tag is the appropriate decomposition input.
- **Regression check**: The new regression case does not conflict with the
  single-wrapped case or S5. Clean.
- **Verdict**: VERIFIED (addressed-by-process)

#### Verify R4 (already-covered): empty-substate assumption

- **Original issue**: The design assumes an empty `substate` unambiguously signals
  a ledger written before this change; needs to be documented so a future reader
  does not treat empty as a routing slug.
- **Fix applied**: Already documented in D2 and item 5; no new track-text edit
  required.
- **Re-check**:
  - Track-file location: D2 (lines 78-81) — "The fallback covers two cases the
    ledger cannot: an in-flight plan created before this change (no `substate`
    key), and the non-ledger `determine_state` walk." Item 5 (lines 187-188) —
    "The empty case is the unambiguous signal of a ledger written before this
    change (D3, owned by Track 2)." Invariant S2 (lines 343-344) pins the
    empty-`substate` → roster-fallback behavior.
  - Current state: the empty-`substate` semantics are documented in two places
    and tested by the fallback-path group; an empty value routes to the fallback,
    never treated as a slug.
  - Criteria met: assumption explicitly documented and test-pinned.
- **Regression check**: D2 and item 5 are mutually consistent on the
  empty-`substate` semantics; no drift. Clean.
- **Verdict**: VERIFIED (already-covered)
