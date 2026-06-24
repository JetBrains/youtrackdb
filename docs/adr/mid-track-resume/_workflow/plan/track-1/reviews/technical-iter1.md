<!--MANIFEST
role: reviewer-technical
phase: 3A
track: "Track 1: Ledger substate primitive, dual-path resolution, wrap-fix, tests, grammar"
iteration: 1
verdict: changes-requested
findings: 4
blockers: 0
index:
  - id: T1
    sev: should-fix
    anchor: "T1"
    loc: ".claude/scripts/workflow-startup-precheck.sh roster_scan; track-1.md Plan of Work item 6 sub-bullet 3"
    cert: "Edge case: wrap-fix join terminator never fires"
    basis: bash-case-glob-eval
  - id: T2
    sev: should-fix
    anchor: "T2"
    loc: "track-1.md Plan of Work item 8; .claude/workflow/conventions.md Phase-ledger glossary row (line 89)"
    cert: "Premise: conventions.md carries a literal { phase, track, tier, categories, s17, paused } key set"
    basis: grep-read
  - id: T3
    sev: suggestion
    anchor: "T3"
    loc: "track-1.md Validation and Acceptance / ledger-path test group; test_workflow_startup_precheck.py _substate helper (line 2600)"
    cert: "Premise: the _substate helper drives the ledger read path"
    basis: read
  - id: T4
    sev: suggestion
    anchor: "T4"
    loc: "track-1.md Plan of Work item 5; determine_state_from_ledger phase=C arm (line 1804)"
    cert: "Integration: ledger-first substate read in determine_state_from_ledger"
    basis: read
evidence_base:
  premises: 6
  edge_cases: 2
  integrations: 2
  confirmed: 8
-->

# Technical review — Track 1, iteration 1

## Findings

### T1 [should-fix]
**Certificate**: Edge case — "wrap-fix join terminator never fires"
**Location**: `track-1.md` `## Plan of Work` item 6, sub-bullets 3 and the closing "The terminator `[0-9]*". "` is correct bash" sentence; target code `roster_scan` in `.claude/scripts/workflow-startup-precheck.sh:1306`.
**Issue**: The track names the join terminator as the bash pattern `[0-9]*". "` and asserts it "matches a digit followed by anything, then the literal `. ` — i.e. the start of the next numbered step." That is wrong for a `case` glob. A `case` pattern with no trailing `*` is anchored to the END of the string, so `[0-9]*". "` matches only a line that terminates exactly at `. ` (a bare `2. ` with nothing after). A real next-step line — `2. Second step — risk: medium  [ ]` or `12. Twelfth step` — does NOT match it (verified by direct `case` evaluation: `[0-9]*". "` gives NO match for both, while the existing column-0 guard `[0-9]*". "*` at `:1338` matches both). If item 6 is implemented with the pattern exactly as written, the "stop the join at the next column-0 step line" rule never triggers, so two adjacent wrapped steps DO merge — the precise failure sub-bullet 3 promises to prevent — and the joined buffer reads only the second step's `risk:`/status, undercounting `ROSTER_STEP_COUNT`. The wrapped-roster regression test (S5) would still pass on a single wrapped step, so the defect ships uncaught unless a two-adjacent-wrapped-steps case is added.
**Proposed fix**: Correct the track text to the terminator the existing scanner already uses for column-0 detection: `[0-9]*". "*` (trailing `*`), and update the "correct bash" justification sentence to match ("a digit run, then `. `, then anything"). Add a test fixture with two adjacent wrapped steps to S5 so the no-merge boundary is pinned, not just the single-wrapped-step count.

### T2 [should-fix]
**Certificate**: Premise — "conventions.md carries a literal `{ phase, track, tier, categories, s17, paused }` key set"
**Location**: `track-1.md` `## Plan of Work` item 8 and `## Invariants & Constraints` ("the existing six ledger keys … `substate` is added as the seventh key"); target `.claude/workflow/conventions.md` Phase-ledger glossary row (`:89`).
**Issue**: Item 8 says to "Add `substate` to the `{ phase, track, tier, categories, s17, paused }` key set, making it the seventh key." That literal brace-delimited six-key set does NOT exist in `conventions.md` (grep for `phase, track, tier, categories, s17, paused` returns zero hits). The conventions.md Phase-ledger glossary row enumerates the keys in PROSE — "the resume phase, the active track, the change tier and its matched categories, the §1.7 staging mode, and pause events" — not as a typeset key set. The literal six-key brace set DOES exist, but in the SCRIPT header (`workflow-startup-precheck.sh:56`: "The key set is exactly { phase, track, tier, categories, s17, paused }"). So item 8's edit target is mis-located: an implementer following item 8 verbatim will search conventions.md for a string that is not there. Item 7 (the script-header grammar comment) is the edit that actually touches the brace set.
**Proposed fix**: Reword item 8 to match the actual conventions.md surface: add `substate` to the prose key enumeration in the Phase-ledger glossary row (`:89`), naming it the within-track resume signal the precheck reads ledger-first. Keep the brace-set edit ("seventh key") under item 7, which targets the script header where the `{ phase, track, tier, categories, s17, paused }` literal lives. Update the `## Invariants & Constraints` "existing six ledger keys … seventh key" sentence to point at the script header, not conventions.md, for the brace-set claim.

### T3 [suggestion]
**Certificate**: Premise — "the `_substate` helper drives the ledger read path"
**Location**: `track-1.md` `## Validation and Acceptance` ledger-path test group ("a fixture whose ledger tail carries `phase=C track=2 substate=<slug>`") and the reuse claim ("The tests reuse the existing `write_ledger` … and `_substate` / `_track_doc` helpers"); target `test_workflow_startup_precheck.py:2600` (`_substate`).
**Issue**: The `_substate(track_body)` helper composes a State-C plan with `implementation-plan.md` + `plan/track-2.md` but writes NO phase ledger; `_PLAN_FIRST_TODO_TRACK_2` is a plan body, not a ledger. So a fixture built through `_substate` alone resolves via the LEGACY plan-checkbox walk (`determine_state_from_ledger` returns 1 on `[ -f "$ledger" ] ||` at `:1783`), never through the new ledger-`substate` read. The ledger-path test group, which asserts a `phase=C track=2 substate=<slug>` ledger drives resolution, MUST use `write_ledger` (the helper that writes and commits a verbatim ledger, `:462`) — and likely a `write_track_only` track file so the `phase=C` arm finds a track at `plan/track-2.md`. The track's prose lists `write_ledger` and `_substate`/`_track_doc` as the reused helpers without distinguishing which group uses which; an implementer who reaches for `_substate` for the ledger-path group would test the fallback path by accident and the ledger-path assertion would be vacuous (it would pass via the roster, not the ledger key).
**Proposed fix**: In `## Validation and Acceptance`, split the helper attribution per group: the ledger-path and dual-path-parity groups build their fixture with `write_ledger` (+ a `write_track_only`/`plan_artifact` track file the `phase=C track=N` arm resolves); the fallback-path and wrapped-roster groups use `_substate`/`_track_doc` (no ledger, so the legacy walk runs). Note explicitly that the ledger-path group's fixture needs the track file present at `plan/track-<N>.md` for the `phase=C` arm to call `determine_c_substate`'s replacement, since `determine_state_from_ledger`'s `C` arm falls to `STATE_JSON='{"phase":"A",...}'` when the track file is absent (`:1815`).

### T4 [suggestion]
**Certificate**: Integration — "ledger-first substate read in `determine_state_from_ledger`"
**Location**: `track-1.md` `## Plan of Work` item 5; target `determine_state_from_ledger` `phase=C` arm, `:1804`.
**Issue**: Item 5 says to call `ledger_tail_value_for_track substate <active-track>` "before the `determine_c_substate` call" and "emit it directly as the `substate` in `STATE_JSON`." The integration is clean — the `C` arm already resolves `track` (defaulting to `1`, `:1810`) before reaching `determine_c_substate` (`:1813`), so the new reader has the active track in hand at the right point. One under-specified edge: item 5 emits the ledger slug "directly" but does not say it must reuse the SAME `jq -nc --arg s … '{phase:"C", substate:$s}'` emit at `:1814` rather than string-interpolating the slug into `STATE_JSON` by hand. The four committed slugs carry no jq metacharacters, so a hand-built string would not corrupt today, but the existing arm routes every substate through `jq --arg` for escape-by-construction; a hand-built emit would diverge from that contract for no benefit. Also unaddressed: when the track file is ABSENT but the ledger carries `substate=<slug>`, item 5 does not say whether the ledger slug still wins or the `:1815` no-track-file `{"phase":"A"}` branch takes precedence. By construction Track 2 only appends `substate` once a track file exists, so this is not reachable today, but the ordering should be stated.
**Proposed fix**: Add to item 5 that the ledger-`substate` emit reuses the existing `jq -nc --arg s '$C_SUBSTATE'`-style call (substituting the ledger value for `$C_SUBSTATE`) so escaping stays contract-uniform with `:1814`. State the precedence for the absent-track-file-with-substate case explicitly (recommend: the no-track-file `{"phase":"A"}` branch still wins, since a `substate` with no track file on disk is the same pre-decomposition shape the legacy arm treats as State A) — or note it as out of reach by Track 2's append contract and therefore not a tested path.

## Evidence base

#### Premise: the four committed sub-state slugs are byte-identical to workflow.md step 5's routing slugs
- **Track claim**: D1 — "one of the four committed sub-state slugs (`decomposition-pending`, `steps-partial`, `steps-done-review-pending`, `review-done-track-open`), which map 1:1 to the slugs `workflow.md` step 5 already routes on"; Interfaces "Out-of-scope: … `workflow.md` step 5 routing, which is unchanged because the four committed slugs are byte-identical."
- **Search performed**: grep `decomposition-pending|steps-partial|steps-done-review-pending|review-done-track-open|failed-step|section-discrepancy|substate` over `.claude/workflow/workflow.md`.
- **Code location**: `.claude/workflow/workflow.md:344,347,348,349` (the four), `:345,346` (the two fallback-only slugs `section-discrepancy`/`failed-step`), `:340-342` ("Route on `state.substate`" table).
- **Actual behavior**: All four slugs appear verbatim as `state.substate` routing-table rows. `section-discrepancy` and `failed-step` also appear, confirming they are routing-recognized but are the fallback-only pair the ledger path drops (D2). The `{ "phase": …, "substate": <slug>|null }` JSON shape is documented at `:292`.
- **Verdict**: CONFIRMED
- **Detail**: The byte-identity claim holds; no `workflow.md` edit is needed.

#### Premise: the phase enum is `{0, A, C, D, Done}` with no `B`
- **Track claim**: `## Context and Orientation` "the enum `{0, A, C, D, Done}`, with no `B`"; D1 Rejected (a) "widens the phase enum `{0, A, C, D, Done}`."
- **Search performed**: Read `determine_state_from_ledger` case arms.
- **Code location**: `workflow-startup-precheck.sh:1796-1828` — `case "$phase" in 0 | A | D | Done) … ;; C) … ;; *) parse_error …`.
- **Actual behavior**: The accepted phase tokens are exactly `0`, `A`, `C`, `D`, `Done`; any other token hits `parse_error` (exit 3). No `B` arm. The `phase=C` arm spans Phase B execution and Phase C review, matching the track's "phase `C` therefore spans both."
- **Verdict**: CONFIRMED

#### Premise: `roster_scan` reads status from the column-0 line and skips wrapped continuation lines today
- **Track claim**: item 6 "Today the scan reads the status checkbox only from the column-0 `N. ` line; when a long description wraps, the `— risk: <tag>  [<glyph>]` tail is on an indented continuation line, the column-0 line carries no `risk:`, and the `*) continue` arm skips the entry without counting it."
- **Search performed**: Read `roster_scan`.
- **Code location**: `workflow-startup-precheck.sh:1306-1391`. Column-0 guard at `:1337-1340` (`[0-9]*". "*) ;; *) continue`); `risk:`-presence guard at `:1345-1352` (`*"risk:"*) tail=… ;; *) continue`).
- **Actual behavior**: A column-0 line with no `risk:` token falls to the `*) continue` arm at `:1350` and is skipped WITHOUT incrementing `ROSTER_STEP_COUNT` (`:1379`). A wrapped continuation line (leading space) fails the column-0 guard at `:1338` and is `continue`d at `:1339`. So a wrapped step is dropped from the count exactly as the track describes — confirming the YTDB-1134 defect and the wrap-fix premise.
- **Verdict**: CONFIRMED

#### Premise: `conventions.md` carries a literal `{ phase, track, tier, categories, s17, paused }` key set (item 8 edit target)
- **Track claim**: item 8 "Add `substate` to the `{ phase, track, tier, categories, s17, paused }` key set" in `conventions.md`.
- **Search performed**: grep `\{ ?phase, track, tier, categories, s17, paused ?\}` and `phase, track, tier, categories, s17, paused` over `conventions.md`; grep over `workflow-startup-precheck.sh`.
- **Code location**: `conventions.md` — NOT FOUND (zero hits for the literal). The brace set lives at `workflow-startup-precheck.sh:56`. The conventions.md Phase-ledger row (`:89`) enumerates keys in prose only.
- **Verdict**: WRONG
- **Detail**: Produces T2. The brace set is in the script header (item 7's target), not conventions.md (item 8's target).

#### Premise: the test helpers `write_ledger`, `_substate`, `_track_doc` exist
- **Track claim**: `## Validation and Acceptance` "The tests reuse the existing `write_ledger` … and `_substate` / `_track_doc` helpers."
- **Search performed**: grep `def (write_ledger|_substate|_track_doc)` over the test file.
- **Code location**: `test_workflow_startup_precheck.py:462` (`write_ledger`), `:2585` (`_track_doc`), `:2600` (`_substate`).
- **Actual behavior**: `write_ledger(body, *, commit=True)` writes and commits a verbatim ledger; `_track_doc(progress, concrete_steps="")` composes a track body; `_substate(track_body)` runs `--mode full` against a State-C Track 2 plan with NO ledger. Existing bare-token/newline/quote append-rejection tests at `:3862,:3886,:3909` confirm the append-validation pattern item 10 mirrors.
- **Verdict**: CONFIRMED
- **Detail**: All three helpers exist. The caveat that `_substate` does not exercise the ledger read path is T3, not a non-existence finding.

#### Premise: `ledger_tail_value` reads the first ` key=` token and the emit-order invariant holds
- **Track claim**: items 3 and 4 — "the reader takes the first ` substate=` token on the line and stops, so a bare `substate` written before the one quoted `categories` field can never lose to a decoy `substate=` inside a quoted `categories="…"` span."
- **Search performed**: Read `ledger_tail_value` and `append_ledger`'s line builder.
- **Code location**: reader `:1675-1707` (`case " $line" in *" $key="*) rest="${line#*" $key="}" … LEDGER_VALUE="$val"`), builder `:1621-1627`.
- **Actual behavior**: The reader anchors `" $key="` at a leading-space token boundary, takes the FIRST match (`${line#*" $key="}` strips up to and including the first occurrence), and never loops. The builder appends `phase`/`track`/`tier` as bare tokens, THEN `categories="…"`, THEN `s17`/`paused` (`:1622-1627`). So the proposed `substate` token, placed in the pre-`categories` block per items 2-3, is emitted before the one quoted field and is the first match. The invariant the track relies on holds. NOTE: `append_ledger` currently appends `s17` and `paused` AFTER `categories` (`:1626-1627`) — those two bare keys sit after the quoted span. They are reader-consumed (`s17` by the staged-read precedence; `phase` reads `s17`? no — `ledger_tail_value phase`/`track` only). `substate` being placed BEFORE `categories` keeps it safe; the track's instruction to put it in the pre-`categories` block is correct and necessary.
- **Verdict**: CONFIRMED

#### Edge case: wrap-fix join terminator never fires
- **Trigger**: two adjacent wrapped roster steps, where step N's `risk:` tail wrapped onto a continuation line and step N+1 begins at column 0.
- **Code path trace**:
  1. Proposed `roster_scan` buffers the column-0 `N. ` line (no `risk:`) per item 6 sub-bullet 1.
  2. It appends following non-column-0 lines per sub-bullet 2.
  3. It is told to STOP at "the next column-0 step line (matched by `[0-9]*". "`)" per sub-bullet 3 — but `[0-9]*". "` as a `case` glob matches only a string ending at `. ` (verified: `2. Second step…` and `12. Twelfth step` both NO-match; bare `2. ` matches).
  4. So the stop condition never sees the real next-step line; the join runs past step N+1's column-0 line and merges the two steps.
- **Outcome**: step N+1 is folded into step N's buffer; `ROSTER_STEP_COUNT` undercounts by one and step N+1's status is lost. The single-wrapped-step S5 test still passes, so the defect ships uncaught.
- **Track coverage**: NO — the track asserts the pattern is "correct bash" and tests only a single wrapped step.

#### Edge case: empty `substate` read on a `phase=C` track (the dormant-primitive path)
- **Trigger**: a `phase=C` ledger with no `substate` key (the steady state until Track 2 wires the append).
- **Code path trace**:
  1. `determine_state_from_ledger` resolves `phase=C` (`:1804`), `track` (`:1808-1810`).
  2. Item 5's new `ledger_tail_value_for_track substate <track>` returns empty (no `substate` line for the track).
  3. Item 5 falls through to `determine_c_substate "$track_file" "todo"` (`:1813`) — the wrap-fixed roster path.
- **Outcome**: resolves the roster-derived slug, which is the pre-this-change behavior plus the wrap fix. Matches D1's "lands dormant" / S2 invariant.
- **Track coverage**: YES — the fallback-path test group and S2 pin it.

#### Integration: ledger-first substate read in `determine_state_from_ledger`
- **Plan claim**: item 5 — read `ledger_tail_value_for_track substate <active-track>` before `determine_c_substate` in the `phase=C` arm and emit the non-empty value directly.
- **Actual entry point**: `determine_state_from_ledger` `C` arm, `:1804-1822`. The arm resolves `track` (`:1808`) before the `determine_c_substate` call (`:1813`), and emits via `jq -nc --arg s '{phase:"C", substate:$s}'` (`:1814`).
- **Caller analysis**: `determine_state` (`:1839`) is the sole caller; it calls `determine_state_from_ledger` first and falls to the legacy walk on return 1. `emit_json` consumes `STATE_JSON`. No other consumer of the `C` arm.
- **Breaking change risk**: low — the new read is inserted before an existing call and leaves the fallback intact; the dormant-primitive property (empty `substate` → existing behavior) means no existing test regresses. The under-specified emit-method and absent-track-file precedence are T4 (suggestion), not breakage.
- **Verdict**: MATCHES

#### Integration: `--substate` append flag and validation
- **Plan claim**: items 1-2 — add a `--substate)` arg case filling `LEDGER_SUBSTATE`, and `reject_bad_ledger_value "substate" "$LEDGER_SUBSTATE" bare` in the validation block.
- **Actual entry point**: arg parser `:124-178` (the `--phase`/`--track` cases at `:149-156` are the template), accumulators `:108-114`, validation block `:1599-1606`.
- **Caller analysis**: `reject_bad_ledger_value … bare` (`:1562`) rejects space (`:1573-1579`) and newline (`:1567-1572`) with exit 3; the existing `phase`/`track`/`tier`/`s17`/`paused` lines (`:1601-1605`) are the exact mirror. A `--substate` case and a `reject_bad_ledger_value "substate" … bare` line slot in with zero structural change; `LEDGER_SUBSTATE=""` must be added to the accumulator block (`:108-114`) and the line builder (`:1622-1627`, in the pre-`categories` block).
- **Breaking change risk**: none — additive flag, empty default omits the token (the `[ -n "$LEDGER_…" ] &&` guard pattern).
- **Verdict**: MATCHES
