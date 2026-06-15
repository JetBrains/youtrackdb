<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
  - {id: R5, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Risk gate verification — Track 1 (iteration 2)

Verdict: PASS. All five iteration-1 risk findings (R1–R3 should-fix, R4–R5
suggestion) are VERIFIED. Each ACCEPTED fix landed in the updated `track-1.md`
and introduced no regression; R5 (accepted-as-already-covered) is confirmed
captured by Plan-of-Work step 2. No new finding surfaced.

The branch carries the canonical `§1.7(b)` workflow-modifying marker in the
plan's `### Constraints`, so reads of `.claude/**` resolve through `§1.7(d)`.
No `_workflow/staged-workflow/` subtree exists yet (implementation has not
started — this is Phase A plan review), so per `§1.7(d)` the staged-otherwise-
live fallback resolves every `.claude/**` re-check to the live file. Code-fact
re-checks used grep over the live bash script and live workflow prose (the
correct lens — these are shell/Markdown, not Java symbols).

## Semi-Formal Verification Protocol

#### Verify R1: minimal no-plan A/C/D/Done re-derivation underspecified
- **Original issue**: the track named the ledger-tail / two-level read primitive
  but did not pin how a no-plan `minimal` resume re-derives the active track
  (workflow.md step 5 reads it from `## Checklist`), how D-vs-Done is told apart
  without `## Final Artifacts`, or whether the plan-checkbox walk is retained for
  `lite`/`full`. Shared root with technical T2.
- **Fix applied**: Plan-of-Work step 6 and the `minimal` Validation bullet now
  state the no-plan active track is `track-1` by construction; the Checklist-walk
  re-derivation is gated to `lite`/`full`.
- **Re-check**:
  - Track-file location: `track-1.md` Plan of Work step 6 (L276–281) and
    Validation bullet (L307–309).
  - Current state: step 6 reads "For the no-plan `minimal` resume the active
    track is `track-1` by construction (single-track tier; D10's
    `plan/track-1.md` secondary signal), so the agent-side active-track
    re-derivation that walks the plan `## Checklist` (workflow.md §Startup
    Protocol step 5) is gated to `lite`/`full` — `minimal` has no Checklist to
    walk." The Validation bullet now adds "with the active track resolved as
    `track-1` (no Checklist walk)."
  - Code corroboration: `determine_state` (precheck.sh) returns State 0
    unconditionally when `implementation-plan.md` is absent (L1452–1455);
    active-track re-derivation walks `## Checklist` (workflow.md step 5
    L275–276). A `minimal` plan has exactly one track (D2), so `track-1` by
    construction is a correct non-Checklist signal. The fix premise is sound.
  - Criteria met: the resume state machine's critical no-plan path now has a
    pinned active-track rule; the `lite`/`full` Checklist walk is explicitly
    retained (no wholesale replacement), preserving the backward-compat
    invariant.
- **Regression check**: checked the `lite`/`full` resume path — gating the
  Checklist walk to those tiers (rather than deleting it) leaves their
  active-track re-derivation and the existing State A/C/D/Done test battery
  intact. The `## Plan of Work` backward-compat invariant (L287–289) is
  unchanged. Clean.
- **Verdict**: VERIFIED

#### Verify R2: D3 mis-credited the interrupted-write reconciliation for a torn append
- **Original issue**: D3 cited "the existing interrupted-write reconciliation
  covers a torn ledger append," but that machinery reads track-file
  roster-vs-`## Progress` and does not apply to the ledger; the real guard is the
  atomic temp-file-plus-rename.
- **Fix applied**: D3 `Risks/Caveats` reworded to credit the atomic rename (a
  partial write never becomes visible) and note the roster-vs-Progress
  reconciliation is a separate track-file mechanism that does not apply.
- **Re-check**:
  - Track-file location: `track-1.md` D3 Risks/Caveats (L72–76).
  - Current state: "the atomic temp-file-plus-rename append covers it: a partial
    write lands in the temp file and the rename publishes the new tail
    atomically, so a crash mid-append leaves the prior ledger intact and
    `determine_state` resolves the prior state. (The existing
    roster-vs-`## Progress` interrupted-write reconciliation is a separate
    track-file mechanism and does not apply to the ledger.)"
  - Code corroboration: the only atomic-write precedent is the temp+rename at
    `workflow-startup-precheck.sh:407–408` (`{ ...; } > "$f.tmp" && mv "$f.tmp"
    "$f"` inside `no_drift_normalization`); the "interrupted-write" / roster /
    `section-discrepancy` references (L882–923, L1140+) are the track-file
    mechanism, confirming the iter1 finding and the corrected attribution.
  - Criteria met: the durability claim now credits only the real, in-script
    precedented mechanism; the misleading "reconciliation routine exists" reading
    is removed.
- **Regression check**: checked the durability story end-to-end — atomic
  temp+rename plus last-value-wins reads is sound and self-consistent with D6
  (append-only, last-value-wins) and the Validation torn-append bullet (L305–306).
  No stale contradicting prose left in D3. Clean.
- **Verdict**: VERIFIED

#### Verify R3: ledger event grammar deferred, Track 2 depends on it unspecified
- **Original issue**: the published inter-track contract (ledger event grammar)
  was "decide and pin" work with no example line or key spelling on disk, so
  Track 2's ~9 re-pointed readers could not be planned against a fixed shape.
- **Fix applied**: the Contracts block in `## Interfaces and Dependencies` now
  carries an illustrative example ledger line plus the key set; the exact spelling
  is pinned in Plan-of-Work step 1 and read as-built by Track 2's Phase A.
- **Re-check**:
  - Track-file location: `track-1.md` Contracts-published block (L371–378).
  - Current state: the block now gives an illustrative line
    `[2026-06-15T16:42Z] [ctx=safe] phase=A track=1 tier=full
    categories="Workflow machinery,Architecture" s17=b` and the key set
    `phase` / `track` / `tier` / `categories` / `s17` / `paused`, with the
    explicit qualifier "exact token spelling is pinned in Plan-of-Work step 1;
    that as-built grammar is what Track 2's Phase A reviews against."
  - Criteria met: a concrete shape now exists for Track 2 to plan against; the
    line shape (one entry per line, `[<ISO>] [ctx=<level>] key=value …`,
    last-value-wins per key) is explicit, and the deferred-spelling-with-as-built-
    review path resolves the producer/consumer-drift risk D4 names.
- **Regression check**: checked field-name consistency across the track. Step 1
  (L252), the §1.7(b)/(k) note (L262), and the Contracts signature (L369) name
  the §1.7-mode field semantically as "§1.7 mode"; the example line shows one
  token spelling (`s17=b`). This is not a contradiction — the prose names the
  semantic field, the example is explicitly illustrative, and the track defers
  the exact token spelling to step 1 with Track 2 reviewing the as-built. The
  example introduces a concrete key name (`s17`) with no independent anchor, but
  the "illustrative / pinned-in-step-1" framing makes that intentional, not a
  drift. Clean.
- **Verdict**: VERIFIED

#### Verify R4: §1.6(f) exclusion may not be the only drift-walk edit
- **Original issue**: the step-1 phrase "add the ledger to the drift logic" read
  as an inclusion (add to the walk), which would force the triple-site walk-glob
  plus conformance-fixture edit that §1.6(f) flags as S1-forbidden on a staged
  branch.
- **Fix applied**: step 1 and D13 reworded — `detect_drift` uses a hardcoded
  artifact list, so the ledger is excluded by omission; the only change is the
  §1.6(f) doc entry (no walk-glob or fixture edit).
- **Re-check**:
  - Track-file location: `track-1.md` Plan of Work step 1 (L246–249) and D13
    (L147–151).
  - Current state: step 1 reads "no `detect_drift` code change is needed because
    its walk enumerates a hardcoded artifact list that does not include the ledger
    filename (T1/A4 — the implementer confirms the omission holds)"; D13 reads
    "its walk enumerates a hardcoded artifact list (`implementation-plan.md`,
    `design.md`, `design-mechanics.md`, `plan/track-*.md`), so the ledger is
    excluded by omission."
  - Code corroboration: the drift walk uses a hardcoded `ls` list at three sites
    (L391–394, L488–491, L689–692), each enumerating exactly those four artifact
    types; `phase-ledger` / `append-ledger` appears nowhere in the script. The
    exclusion-by-omission claim is exact.
  - Criteria met: the inclusion-misread risk is removed; the zero-edit-on-the-
    walk intent is now unmissable, and the S1-forbidden triple-site+fixture edit
    is explicitly avoided.
- **Regression check**: checked the three walk-glob sites and the conformance
  fixture — the fix asserts no edit to any of them, which matches the code (no
  glob change required). No new coupling introduced. Clean.
- **Verdict**: VERIFIED

#### Verify R5: stub-test load-bearing premise inverted under the no-plan model
- **Original issue**: `test_workflow_startup_precheck_stub.py`'s documented
  premise ("no script change," "must stay byte-identical," a stub-plan
  synthesizer mirroring a `create-plan` template this track deletes) is inverted
  by Track 1, which changes the script and drops the `minimal` stub.
- **Fix applied**: none beyond what already exists — accepted-as-already-covered.
  Plan-of-Work step 2 already directs reworking the stub test for the no-plan
  `minimal` resume.
- **Re-check**:
  - Track-file location: `track-1.md` Plan of Work step 2 (L254–257).
  - Current state: step 2 reads "Rework `test_workflow_startup_precheck_stub.py`
    for the no-plan `minimal` resume (ledger present, no
    `implementation-plan.md`)." This directs the rework the finding asks for.
  - Code corroboration: the live stub test does carry the inverted premise —
    "without any script change" (L9), "must stay byte-identical (no script/test
    edits is the load-bearing invariant…)" (L13–14), and the "load-bearing
    premise — the unchanged precheck reads the stub's…" docstring (L302) —
    confirming the iter1 finding and that step 2's rework target is correct.
  - Criteria met: the disposition (suggestion, accepted-as-already-covered) holds;
    step 2 adequately captures the rework. The vacuous-pass sub-risk (staged tests
    must invoke the staged script path) was raised in the iter1 cert as a Phase-B
    decomposition note and remains a decomposition-time concern, not an unfixed
    plan defect.
- **Regression check**: no edit was made for R5, so no regression possible from a
  fix. Confirmed step 2's rework instruction does not contradict any other
  Plan-of-Work or Validation line. Clean.
- **Verdict**: VERIFIED

## Findings

(none — pure-verdict pass, no new finding surfaced)

## Summary

PASS — all five prior risk findings VERIFIED, zero regressions, zero new
findings.
