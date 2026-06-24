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

## Findings

(No new findings. This is a pure-verdict re-check pass; all five prior risk
findings verified as correctly fixed. Per-finding verdicts are in the manifest
`verdicts` block; the certificates below carry the evidence.)

## Verification certificates

#### Verify R1: D1+D3 wiring-pair can split across steps
- **Original issue** (should-fix): The A→C `steps-partial` append (D1,
  `track-review.md`) and the track-advance `decomposition-pending` append (D3,
  `track-code-review.md`) MUST land together, but the track only asserted the pair
  is "in this track." Decomposition could split the two per-file append sites into
  separate steps; a partial intermediate commit ships track N+1 at `phase=C` with no
  `substate`, silently triggering the roster fallback — the exact failure mode this
  branch fixes. The constraint was stated only in §Invariants (where it reads as
  already-satisfied), not where the decomposer reads it.
- **Fix applied**: `## Invariants & Constraints` now carries a dedicated
  decomposer-facing constraint (track-2.md:409-418): "The D1+D3 wiring pair lands in
  the same step. Both the A→C `steps-partial` append (D1, `track-review.md`) and the
  track-advance `decomposition-pending` append (D3, `track-code-review.md`) MUST land
  in **one step** (or steps sharing one mergeable commit), not merely 'in this
  track.'" It names the doc-only no-failing-test gap, the natural per-file split as
  the hazard, and the precise failure ("leaves the *next* track at `phase=C` with no
  `substate`, silently triggering the fallback"), then directs the decomposition:
  "The decomposition in `## Concrete Steps` satisfies this by keeping the
  `track-review.md` and `track-code-review.md` append wiring in the same step."
- **Re-check**:
  - Track-file location: `## Invariants & Constraints` (track-2.md:409-418); the D3
    "wiring-pair" bullet retained at track-2.md:106-110.
  - Current state: the constraint is now stated in the section the decomposer reads
    when it builds `## Concrete Steps`, with an explicit satisfaction recipe (one
    step covering both files) and the "same step / one mergeable commit" wording the
    fix promised. It is no longer phrased as already-satisfied; it is phrased as a
    decomposition obligation.
  - Criteria met: the risk finding's proposed fix ("State the constraint where the
    decomposer will read it, not only in §Invariants where it reads as
    already-satisfied") is satisfied verbatim. `## Concrete Steps` is the
    Phase-A-written roster (currently a placeholder, track-2.md:295-296), and the
    constraint explicitly binds that roster.
- **Regression check**: Checked D3's wiring-pair bullet (track-2.md:106-110, still
  present and consistent), the `## Concrete Steps` placeholder (track-2.md:295-296,
  unwritten at Phase 1 as expected — the constraint binds it forward), and the
  §Interfaces in-scope list (track-2.md:349-355, both files listed). The two
  statements of the pair (D3 bullet + Invariants constraint) are consistent: D3 says
  the pair is in this track, Invariants tightens it to one step. No contradiction
  introduced.
- **Verdict**: VERIFIED

#### Verify R2: boundary 3's `:743` commit cannot mean "review passed"
- **Original issue** (should-fix): The track planned to add
  `--substate review-done-track-open` "around `:743`." Tracing `track-code-review.md`:
  `:743` is the **per-iteration** Progress commit (`Track-level code review iteration
  N complete`), which fires on every iteration including non-passing ones, before the
  gate-check verdict. A literal wiring would record `review-done-track-open` at the
  end of iteration 1 of a multi-iteration review, marking a still-iterating track as
  review-passed. There is no pre-approval commit that uniquely means "review passed."
- **Fix applied**: Boundary 3 (track-2.md:229-247) adopts Option A — a NEW
  pre-approval Workflow-update commit. It states the `:743` problem precisely:
  "`:743` ... fires inside step 3's 'if any in-scope findings need fixes' branch —
  *before* that iteration's gate-check runs and on *every* fix iteration — so it
  cannot mean 'review passed' and is not the ride site." It prescribes: "Add a **new
  pre-approval Workflow-update commit at step 6**, gated on all-reviews-pass, staging
  the `Track complete` Progress flip plus a `--append-ledger --substate
  review-done-track-open` append." The boundary table row (track-2.md:185) reads "a
  NEW pre-approval Workflow-update commit at step 6 (gated on all-reviews-pass)."
- **Re-check**:
  - Track-file location: `## Plan of Work` boundary 3 (track-2.md:229-247); table row
    (track-2.md:185); D1 count-correction prose (track-2.md:188-198); Surprises log
    (track-2.md:29-38).
  - Live-doc cross-check: `track-code-review.md` §Review loop step 6 ("When all
    reviews pass", `:826-:834`) appends the `Track complete` Progress entry and
    carries NO commit verb (confirmed live); the per-iteration commit verb is at
    `:739` and the post-approval `Mark <track> complete` commit at `:1423`. So a new
    pre-approval commit at step 6 is indeed required, and `:743` is correctly excluded
    as the ride site.
  - Criteria met: the fix matches the finding's proposed-fix shape (a) — append in
    the step-6 all-reviews-pass path with its own committed boundary. Numbered item 3
    explicitly states `:743` "is not the ride site," resolving the under-specification.
- **Regression check**: Checked the gate-A6 by-reference symmetry: boundary 3's new
  commit is described as "symmetric with boundary 2's new commit, and distinct from
  the post-approval track-completion commit (boundary 4), which carries
  `decomposition-pending` for the next track" (track-2.md:238-241) — consistent with
  boundary 4 (track-2.md:248-254) and boundary 2 (track-2.md:206-228). The single-step
  edge case (skips the review loop, no step 6, stays `steps-done-review-pending`,
  carried by boundary 4) is consistent across boundary 3 (track-2.md:241-244), the
  Edge cases (track-2.md:283-288), §Validation (track-2.md:329-333), and S2
  (track-2.md:397-400). No contradiction. The D1 two-vs-one commit-count correction
  is routed to Phase 4 `design-final.md` reconciliation (Surprises track-2.md:29-38),
  leaving the immutable D1 intact — correct handling.
- **Verdict**: VERIFIED

#### Verify R3: new Phase-B→C commit needs precise placement and a completion-path guard
- **Original issue** (should-fix): Boundary 2 adds a new commit to the previously
  commit-free §Phase B Completion. Three risks: ordering vs reflection and end-session
  (a commit after end-session is lost); the early-exit path (context-warning /
  two-failure) reaching §Phase B Completion with steps still `[ ]` must NOT fire the
  `steps-done-review-pending` commit; and the behavior change (resume now reads the
  ledger directly) should cite the Track 1 ledger-path test.
- **Fix applied**: Boundary 2 gains a "Placement and guard (Phase A review, R3)"
  paragraph (track-2.md:216-228): "Insert the commit in §Phase B Completion after step
  1 (the `[x]` flip) and after step 3 (self-improvement reflection, which produces no
  commit and stages nothing), but strictly before step 4 (end the session)." Staging:
  "Stage explicit paths only — the track file (the `[x]` flip) and the phase ledger
  (the append) — never `git add -A`, symmetric with the A→C commit at
  `track-review.md:600-609`." Guard: "fire **only on the normal all-steps-`[x]`/`[~]`
  completion path**, not on a context-warning or two-failure early exit where steps
  remain `[ ]`." Test citation: "Cite Track 1's `steps-done-review-pending`
  ledger-path test as the verification that the slug this commit writes resolves
  correctly on resume."
- **Re-check**:
  - Track-file location: `## Plan of Work` boundary 2 (track-2.md:206-228); table row
    (track-2.md:184); §Validation (track-2.md:319-323); S4 invariant (track-2.md:401-405).
  - Live-doc cross-check: `step-implementation.md` §Phase B Completion (`:1070`) runs
    exactly the four steps the fix references — step 1 `[x]` flip, step 2 inform user,
    step 3 reflection ("produces no commit"), step 4 end session (confirmed live at
    `:1076-1101`). The fix's "after step 1, after step 3, before step 4" placement is
    valid against the live structure; the early-exit-reachability premise holds
    (reflection is mandatory on early exit, the new commit is correctly excluded from
    that path).
  - Criteria met: all three of the finding's proposed-fix sub-requirements (exact
    insertion point, explicit-path staging symmetric with the A→C commit, the
    all-`[x]`/`[~]` guard) and the Track 1 test citation are present.
- **Regression check**: Checked the guard's interaction with the Edge cases: the
  `[~]`-skipped-step edge case (track-2.md:289-291) keys boundary 2 on "every step
  `[x]`/`[~]`", consistent with the guard wording in boundary 2. The "incidentally
  commits the previously-uncommitted `Step implementation [x]` flip" note
  (track-2.md:212-214, 79-81) is preserved and consistent with D1 (track-2.md:78-81).
  No contradiction with the symmetry claim against `track-review.md:600-609` (verified
  the A→C commit there stages explicit paths, not `git add -A`).
- **Verdict**: VERIFIED

#### Verify R4: no enum guard on `--substate`; slug typo has no runtime backstop
- **Original issue** (suggestion): Track 1 landed `--substate` validating only
  bare-token-ness (no enum membership; WI2 deferred and declined for Track 2). Track 2
  is the sole writer of `substate` values, so a typo'd slug passes validation and
  routes as an unknown sub-state with no diagnostic. Mitigation: add a track-level
  acceptance check that each appended slug is byte-identical to the four canonical
  slugs, and note WI2 in Surprises.
- **Fix applied**: `## Validation and Acceptance` adds a slug-byte-identity check
  (track-2.md:311-318): "**Each appended slug is byte-identical to one of the four
  canonical slugs** — `decomposition-pending`, `steps-partial`,
  `steps-done-review-pending`, `review-done-track-open`," with the explanation that
  "`--substate` validates only bare-token-ness, not enum membership (Track 1's WI2,
  deferred and declined for this track), and Track 2 is the sole writer of `substate`
  values, so a typo has no runtime backstop — the append-cadence review is the only
  gate." It directs: "Confirm each of the five append sites writes the slug its `##
  Plan of Work` row names, spelled exactly." The Surprises log records WI2
  (track-2.md:54-59).
- **Re-check**:
  - Track-file location: `## Validation and Acceptance` (track-2.md:311-318);
    Surprises "WI2 enum guard declined" block (track-2.md:54-59); the same WI2 note
    cross-referenced in §Invariants/Validation prose.
  - Current state: the four canonical slugs are enumerated byte-exact; the check is a
    review-gate acceptance criterion, converting the typo from a runtime-only catch to
    a review catch — exactly the finding's proposed mitigation. WI2 is noted in
    Surprises with the "future Track-1-scope follow-up can add the enum guard" hook.
  - Criteria met: both halves of the proposed fix (the byte-identity acceptance check
    + the WI2 Surprises note) are present.
- **Regression check**: Checked the slug set against the design's canonical four and
  the script's grammar comment — the four slugs in track-2.md:312-314 match the four
  transitions in D1 (track-2.md:72-74) and the mermaid state diagram (track-2.md:146-152):
  `decomposition_pending → steps_partial → steps_done_review_pending →
  review_done_track_open`. No fifth slug or misspelling introduced. The "sole writer"
  claim is consistent with the Out-of-scope note that the script/validation is Track 1
  (track-2.md:365-366).
- **Verdict**: VERIFIED

#### Verify R5: boundary 5 framing must not invite dropping the `--phase 0` append
- **Original issue** (suggestion): Boundary 5 appends `--substate steps-partial` on
  the inline-replan commit. But the replan path's `--phase 0` reset is last-value-wins
  and `determine_state_from_ledger` returns `{phase:"0", substate:null}` from its
  `0|A|D|Done` arm before the `phase=C` substate read, so the substate append is never
  read on the replan resume itself — it is forward-hygiene. The wording could mislead
  an implementer into treating substate as the routing signal or dropping the
  load-bearing `--phase 0` append.
- **Fix applied**: Boundary 5 gains a "The `--phase 0` reset is the routing signal;
  the `--substate` append is forward-hygiene" paragraph (track-2.md:264-279). It
  states the `phase=0` arm "returns `{phase:"0", substate:null}` *before* the `phase=C`
  arm that reads `substate`. So the `--substate steps-partial` written on the replan
  commit is **never read on the replan resume itself**." It directs: "do **not**
  describe it as the mechanism that reopens the track, and do **not** drop the
  `--phase 0` append — that reset is what routes the replan resume." The numbered item
  also frames the append as additive: "append `--substate steps-partial` **in addition
  to** the existing `--phase 0` reset" (track-2.md:261-262).
- **Re-check**:
  - Track-file location: `## Plan of Work` boundary 5 (track-2.md:258-279); the
    boundary-5 dormancy Surprises block (track-2.md:40-45).
  - Live-doc cross-check: `inline-replanning.md` §Process step 6 (`:242-:268`) appends
    `--phase 0` (`:249`) and commits via `Inline replan after Track <N>` staging the
    ledger (`:266`) — confirmed live. The `--substate` append on the same commit is
    additive to the `--phase 0` reset, as the fix states.
  - Criteria met: the finding's proposed fix ("state plainly that `--substate
    steps-partial` is appended **in addition to** the existing `--phase 0` reset ...
    and that the `--phase 0` append remains the routing signal") is present verbatim,
    including the "in addition to" phrasing and the explicit "do not drop `--phase 0`"
    directive.
- **Regression check**: Checked that the fix does not over-correct into dropping the
  `--substate` append: the prose keeps it ("Keep the `--substate` append anyway: it is
  cheap, survives `git reset --hard HEAD`...", track-2.md:271-276), so both appends
  remain on the commit — no behavior change beyond clarification, as the finding
  required. The dormancy framing is routed to Phase 4 `design-final.md` reconciliation
  (Surprises track-2.md:40-45, consistent with the frozen `design.md:186-188`
  reconciliation note in boundary 5). No new issue.
- **Verdict**: VERIFIED

## Summary

PASS. All five prior risk findings (R1/R2/R3 should-fix, R4/R5 suggestion) are
correctly fixed in the updated track file, each verified against the live
resume-protocol docs the fixes cite (staged-workflow carries no copy of the four
in-scope Track-2 docs, so they were read live per §1.7(d); the cited line references
— `track-review.md:596`/`:1048`, `track-code-review.md:826`/`:743`/`:1409`,
`step-implementation.md` §Phase B Completion steps 1-4, `inline-replanning.md:249`/`:266`,
`step-implementation-recovery.md` entry-5 — all resolve). No regressions: the fixes are
internally consistent with D1/D3, the mermaid state diagram, the boundary table, the
Edge cases, S2/S4, and the Phase-4 reconciliation items recorded in Surprises. No new
findings surfaced.
