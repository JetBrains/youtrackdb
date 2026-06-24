---
schema: verdict-producer-manifest
review_type: technical
phase: 3A
iteration: 2
track: "Track 2: Wire the `substate` append sites across the resume protocol"
findings: 0
verdicts:
  - id: T1
    severity: should-fix
    verdict: VERIFIED
  - id: T2
    severity: should-fix
    verdict: VERIFIED
  - id: T3
    severity: suggestion
    verdict: VERIFIED
overall: PASS
---

# Technical gate-verification — Track 2, iteration 2

Three iteration-1 findings (all ACCEPTED) re-checked against the updated track
file. All three fixes landed correctly and introduced no regression. No new
findings. Overall: PASS.

Staged-read note: ledger `s17=workflow-modifying`, so `.claude/**` reads
resolve through §1.7(d). The four resume-protocol docs are not staged, so they
were read from the live `.claude/workflow/` tree; the precheck reader is staged,
so `determine_state_from_ledger` was read from
`_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh`.

#### Verify T1: boundary 3 no longer claims a pre-existing pre-approval commit
- **Original issue**: the Plan of Work claimed boundary 3 (`review-done-track-open`)
  "rides the pre-approval code-review-complete commit (already present)", but no
  such commit exists.
- **Fix applied** (user chose Option A): reframed boundary 3 to a NEW pre-approval
  Workflow-update commit at `track-code-review.md` step 6 (`:826`), gated on
  all-reviews-pass. Updated the boundary table (row 3 + the "two boundaries need a
  new commit" note), numbered item 3, `## Validation`, `## Invariants`, and
  `## Surprises` (D1 commit-count → Phase-4 `design-final.md` reconciliation).
- **Re-check**:
  - Track-file location: `## Plan of Work` table row 3 (`:185`), the
    "Two boundaries need a new commit" note (`:188-198`), numbered item 3
    (`:229-247`), `## Validation` (`:319-323`), `## Invariants` S4 (`:401-405`),
    `## Surprises` D1/D3 entry (`:29-38`).
  - Current state: row 3 now reads "a NEW pre-approval Workflow-update commit at
    step 6 (gated on all-reviews-pass)". Line 30 states boundary 3 "has no existing
    committed home". The lone "(already present)" remaining is on boundary 1's A→C
    commit, which is genuinely pre-existing. No phrase claims a pre-existing
    pre-approval commit.
  - Source check: at live `track-code-review.md` step 6 (`:826`) "when all reviews
    pass" appends a `## Progress` `Track complete` entry but carries NO commit; the
    entry stays uncommitted until the post-approval track-completion commit
    (boundary 4, step 5 / `:1409`+). The per-iteration commit at `:743`
    ("Commit and push the Progress update as a Workflow update commit") fires inside
    step 3's fix branch, before the gate-check verdict and on every fix iteration.
    The track file's account of both facts is accurate.
  - Criteria met: the track no longer claims a pre-existing commit; the new-commit
    framing is internally consistent across all six edited locations. The
    single-step graceful-degradation edge case is consistent across numbered item 3
    (`:241-244`: single-step track skips the review loop, stays at
    `steps-done-review-pending`, carried past review by the boundary-4 append), the
    `## Edge cases` block (`:283-288`), and the S2 invariant note (`:398-400`).
  - D1-immutability handling correct: D1 stays as written; the corrected
    two-vs-one count is a Phase-4 `design-final.md` reconciliation item, mirroring
    Track 1's WI3 diagram-reconciliation pattern. Quoted "three boundaries ride
    existing commits" appears only as a reference to D1's original count, always
    framed as corrected.
- **Regression check**: checked the boundary table, numbered items, Validation,
  Invariants, Surprises, and Context/Orientation prose for a contradicting "rides
  an existing commit" claim on boundary 3 — none. Checked that boundary 1's
  legitimate "(already present)" was not mistakenly stripped — intact. Clean.
- **Verdict**: VERIFIED

#### Verify T2: boundary 5 reframed — `--phase 0` is the routing signal, `--substate` is forward-hygiene
- **Original issue**: the boundary-5 `--substate steps-partial` append on the
  inline-replan revert was described as the reopening mechanism, but it is dormant:
  the `--phase 0` reset resolves to State 0 before any substate read.
- **Fix applied**: reframed numbered item 5 — `--phase 0` is the routing signal,
  `--substate` is forward-hygiene appended alongside it on the same
  `Inline replan after Track <N>` commit; added a Surprises Phase-4 note citing
  `design.md:186-188`.
- **Re-check**:
  - Track-file location: numbered item 5 (`:258-279`), Surprises boundary-5 entry
    (`:40-45`).
  - Current state: item 5 now states the replan resolves to State 0 because
    `--phase 0` is last-value-wins and `determine_state_from_ledger` handles
    `phase=0` in its `0 | A | D | Done` arm returning `{phase:"0", substate:null}`
    before the `phase=C` arm reads substate; so the `--substate steps-partial`
    written on the replan commit is "never read on the replan resume itself". Keeps
    the append as cheap forward-hygiene; explicitly does NOT describe it as the
    reopening mechanism and does NOT drop the `--phase 0` append.
  - Source check (staged reader,
    `_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh`):
    `determine_state_from_ledger` (`:1934`) reads `phase` last-value-wins, then
    `case "$phase"` matches `0` in the `0 | A | D | Done)` arm (`:1953`) and returns
    `{phase:"0", substate:null}` — it never reaches the `C)` arm (`:1960`) where
    `ledger_tail_value_for_track "substate"` (`:1981`) runs. The dormancy claim is
    exact.
  - Source check (live `inline-replanning.md` step 6, `:247-266`): the reset is
    `--append-ledger --phase 0`, "append-only and last-value-wins, so a `phase=0`
    appended after the prior `phase=A` makes `0` the resolved phase; `determine_state`
    reads it as State 0", staged on the `Inline replan after Track <N>` commit. The
    track file's `:249`/`:266` site references match.
  - Criteria met: the append is correctly classed as forward-hygiene, the routing
    signal is correctly attributed to `--phase 0`, and the frozen-design mismatch
    (`design.md:186-188` prescribes the append without reconciling against the
    `phase=0` reset) is routed to Phase 4, not silently changed.
- **Regression check**: checked that item 5 still instructs both appends (no drop
  of `--phase 0`), that the Surprises note does not over-claim, and that the
  forward-hygiene rationale (survives `git reset --hard HEAD`; guards against a
  future reader seeing a stale `steps-done-review-pending` at `phase=C`) is
  coherent. Clean.
- **Verdict**: VERIFIED

#### Verify T3: S2 closure wording tightened to non-emptiness
- **Original issue**: the S2 closure wording ("every `phase=C` track carries an
  explicit `substate`") read as a stronger guarantee than the cadence delivers.
- **Fix applied**: tightened to non-emptiness ("empty-read fallback never taken")
  in `## Validation` and `## Invariants`, with the single-step terminal-value note.
- **Re-check**:
  - Track-file location: `## Validation` S2 bullet (`:324-333`), `## Invariants`
    S2 closure bullet (`:391-400`).
  - Current state: both now state every `phase=C` track on a current-scheme ledger
    carries a NON-EMPTY `substate`, so the Track 1 ledger read never takes the
    empty-read roster fallback for a current plan. The Invariants bullet adds the
    explicit scope limit: "S2 guarantees non-emptiness, not that the terminal value
    always matches lifecycle position beyond what the cadence delivers". The
    single-step terminal-value note is present in both: a single-step track skips the
    review loop / step-6 commit and terminates at `steps-done-review-pending`, which
    routes correctly to completion.
  - Criteria met: the guarantee now matches what the cadence delivers — coverage
    (non-emptiness), not a per-state terminal-value guarantee. The single-step
    branch's terminal `steps-done-review-pending` is named as an explicitly
    correct-but-non-canonical terminal, consistent with T1's graceful-degradation
    framing and D3's "empty means pre-this-change ledger" decision.
  - Cross-consistency: the Validation and Invariants statements of S2 agree with
    each other and with numbered item 3 and the Edge cases block on the single-step
    terminal value. No residual "explicit `substate`" over-claim remains in
    Validation/Invariants.
- **Regression check**: checked that the tightened wording did not weaken the real
  guarantee the Track 1 read depends on (a current-scheme `phase=C` track never hits
  the empty-read fallback) — preserved. Checked D3's empty-read semantics still
  align (empty `substate` = pre-this-change ledger = the unambiguous fallback
  trigger) — consistent. Clean.
- **Verdict**: VERIFIED

## Findings

<!-- No new findings surfaced by this verification pass. -->

## Summary

PASS — all three iteration-1 findings (T1, T2 should-fix; T3 suggestion) VERIFIED.
No regressions. No new findings.
