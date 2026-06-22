<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: T1, sev: should-fix, verdict: VERIFIED, anchor: "#### Verify T1 ", cert: "Clarifying sentence present in track §Context item 1; claims accurate vs live script (no [ -d ] guard; :598-:601 ls 2>/dev/null walk; :612 empty-input check); no broken ref or contradiction introduced"}
  - {id: T2, sev: suggestion, verdict: VERIFIED, anchor: "#### Verify T2 ", cert: "Deferred-to-decomposition (accept action for a suggestion, no track-text change); underlying claim confirmed — only DRIFT_BASE_SHA writer in detect_drift is :659, above which the new :619 block returns"}
overall: PASS
-->

## Findings

<!-- No new findings. The T1 edit introduced no regression; T2 required no track-text change. -->

## Verification certificates

Before verifying, re-read the track's `## Purpose / Big Picture`, `## Context and
Orientation` (the numbered exit list carrying the T1 edit), `## Plan of Work`, and
`## Interfaces and Dependencies`, and D1/D4 in the `## Decision Log`. Verified every
claim by grep/Read against the live `workflow-startup-precheck.sh` and
`workflow-drift-check.md` — no Java symbols in this track, no staged subtree yet, so
reads resolve to live files and carry no reference-accuracy caveat.

#### Verify T1: doc↔script skip-#1 framing gap
- **Original issue**: The track labels the empty-input return (`:612`) "skip #1", but
  `workflow-drift-check.md § Skip conditions` defines Skip #1 as a directory-existence
  predicate (`[ -d "$PLAN_DIR/_workflow" ]`) the script never implements as such.
  Skip-number alignment was unverified across doc and script on a change whose central
  risk (D2) is doc↔script coherence.
- **Fix applied**: A clarifying sentence was appended to the track's `## Context and
  Orientation` numbered exit list, item 1 ("Skip #1, empty-input no-drift",
  track-1.md:186-192). It states that the single `:612` check realizes both the doc's
  Skip #1 (`[ -d "$PLAN_DIR/_workflow" ]`, directory absent → the `:598`-`:601`
  `ls 2>/dev/null` walk finds nothing) and the directory-present-but-no-stampable-
  artifact case in one predicate; that `detect_drift` carries no literal `[ -d ]`
  guard; and that the track's "skip #1" label therefore denotes that combined
  empty-input return, not the doc's directory-existence predicate.
- **Re-check**:
  - Track-file location: `track-1.md:183`-`192` (Context item 1, the appended sentence
    at `:186`-`192`).
  - Current state: the sentence is present and reads as the orchestrator described.
    Every load-bearing claim verified against the live files:
    - **(a) sentence present** — yes, `track-1.md:186`-`192`.
    - **(b) claims accurate** —
      - No `[ -d ]` in `detect_drift`: `grep -n '\[ -d'` over the whole script returns
        zero matches. CONFIRMED.
      - `:598`-`:601` is the `ls` walk: lines `598`-`601` are the `for f in $(ls …`
        block listing `implementation-plan.md`/`design.md`/`design-mechanics.md`/
        `plan/track-*.md`, with `2>/dev/null` on the `ls` at `:601`. CONFIRMED.
      - `:612` is the empty-input check: line `612` is
        `if [ -z "$STAMPED_SHAS" ] && [ -z "$UNSTAMPED_FILES" ]; then`; the arm sets
        `DRIFT_DETECTED="false"` (`:615`), `DRIFT_KIND=""` (`:616`), `return` (`:617`),
        closing `}` at `:618`. CONFIRMED.
      - Doc Skip #1 = directory-existence: `workflow-drift-check.md:207`-`208` reads
        "Active plan's `_workflow/` directory doesn't exist. … Check:
        `[ -d "$PLAN_DIR/_workflow" ]`." Doc Skip #2 = phase-ledger tail
        (`:212`-`:216`). CONFIRMED — the sentence's doc characterization is exact.
    - **(c) no broken reference or contradiction** — every line citation in the
      sentence (`:598`-`:601`, `:612`) matches the live script. The sentence is
      consistent with the rest of the track: D1 (`:618`/`:620` insertion boundary),
      Plan of Work move 1 (`:618`/`:620`), the ordering constraint (`:612`-before-`:618`),
      and Interfaces (`:618`/`:620`) all use the same framing without contradiction.
  - Criteria met: the doc↔script skip-#1 framing gap is now explicitly flagged in the
    artifact, so a reader of the track is no longer misled into reading the track's
    "skip #1" as the doc's `[ -d ]` predicate. The pre-existing behavioral equivalence
    (both converge on `detected=false, kind=null`; new block at `:619` sits below) is
    stated correctly.
- **Regression check**: Checked (i) all `:NNN` citations in the track for drift against
  the live script — all match; (ii) internal consistency of the new sentence with D1,
  D4, Plan of Work, ordering constraint, and Interfaces — no contradiction; (iii) the
  sentence's `ls 2>/dev/null` claim against `:601` — present. Clean — no new issue.
- **Verdict**: VERIFIED

#### Verify T2: DRIFT_BASE_SHA left at default on skip-#2 return
- **Original issue**: Suggestion to make explicit for the implementer that the new
  skip-#2 block must mutate only `DRIFT_DETECTED`/`DRIFT_KIND` (mirroring `:615`-`:617`)
  and to add an explicit `assert drift["base_sha"] is None` to the regression test.
- **Fix applied**: ACCEPTED as decomposition guidance, NOT a track-text edit. The
  accept action for a suggestion of this kind is to defer it to the decomposer (pin the
  scalar-set constraint in the step acceptance; add the explicit assert to the test).
  No track-text change was made.
- **Re-check**:
  - The underlying claim that makes the deferred guidance sound: the only writer of
    `DRIFT_BASE_SHA` in `detect_drift` is the post-fold path at `:659`, above which the
    new `:619` block returns. Verified: `grep -n 'DRIFT_BASE_SHA='` over the whole
    script returns exactly two assignments — `:354` (the global default `""`) and
    `:659` (`DRIFT_BASE_SHA="$FOLD_BASE_SHA"`). `detect_drift` spans `:584`-`:770`
    (next function `mr_files_for_sha()` at `:771`), so `:659` is its sole in-function
    writer. It sits after the fold (`:634`) and below the `:619` insertion point, so a
    skip-#2 return at `:619` leaves `DRIFT_BASE_SHA` at its `:354` default → emitted
    `base_sha` is `null`, exactly as the empty-input return at `:615`-`:617` does.
    CONFIRMED.
  - Current state: track text unchanged for T2 (correct for a deferred suggestion). The
    decomposition guidance — pin "sets only `DRIFT_DETECTED`/`DRIFT_KIND`, mirrors
    `:615`-`:617`" in step acceptance; add `assert drift["base_sha"] is None` — rests on
    a true premise and is therefore sound to carry into decomposition.
  - Criteria met: a suggestion's accept action is satisfied by recording it for the
    decomposer; the confirmed `:659`-sole-writer fact guarantees the guidance is
    actionable and correct.
- **Regression check**: No track-text change, so no regression surface. The deferral
  itself introduces nothing; the premise it depends on is verified. Clean.
- **Verdict**: VERIFIED

## Summary

PASS — T1 VERIFIED (fix applied, accurate against live `workflow-startup-precheck.sh`
and `workflow-drift-check.md`, no regression); T2 VERIFIED (deferred-to-decomposition,
underlying `:659`-sole-writer premise confirmed). No new findings.
