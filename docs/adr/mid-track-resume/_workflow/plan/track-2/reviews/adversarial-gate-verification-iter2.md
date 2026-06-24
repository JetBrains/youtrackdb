<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: REJECTED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Adversarial gate verification — Track 2 (iteration 2)

Re-check of the five iteration-1 adversarial findings against the updated track
file `plan/track-2.md`. Four ACCEPTED fixes verify clean; the one REJECTED
decision (A5, the sizing cut) holds with no downstream issue. No new finding
surfaced. Overall: PASS.

Staging note: the branch ledger carries `s17=workflow-modifying`, so reads of
`.claude/workflow/**` resolve through §1.7(d). The four resume-protocol docs
Track 2 edits are not yet staged (this is Phase A, pre-implementation), so the
staged copy is absent and the live file is authoritative for the citation
re-checks below. Only `conventions.md` and `conventions-execution.md` exist
under `_workflow/staged-workflow/`.

## Verification certificates

#### Verify A1: boundary 3's `:743` host commit is conditional/per-iteration
- **Original issue**: the track file pinned boundary 3 (`review-done-track-open`)
  to the per-iteration Progress commit at `track-code-review.md:743`, which is
  conditional (fires inside step 3's fix-iteration branch) and absent on a clean
  first-pass review, so it cannot mean "review passed."
- **Fix applied** (Option A — NEW pre-approval step-6 commit gated on
  all-reviews-pass):
  - `## Plan of Work` table row 3 (`:185`) now reads "a NEW pre-approval
    Workflow-update commit at step 6 (gated on all-reviews-pass)" as the ride
    commit, not `:743`.
  - `## Plan of Work` boundary-3 detail (`:229-247`) prescribes a new step-6
    commit, gated on all-reviews-pass, staging the `Track complete` Progress
    flip plus the `--substate review-done-track-open` append; it explicitly
    rules out `:743` ("fires inside step 3's … branch — before that iteration's
    gate-check runs and on every fix iteration — so it cannot mean review
    passed and is not the ride site").
- **Re-check**:
  - Track-file location: table row 3 (`:185`), boundary-3 prose (`:229-247`),
    plus the "Two boundaries need a new commit" correction (`:188-198`) and the
    Surprises-log entry (`:29-38`).
  - Live citation check: `track-code-review.md:826` is step 6 "When all reviews
    pass …" and appends the `Track complete` Progress entry with **no commit at
    that point** — confirmed by Read; the entry stays uncommitted until the
    post-approval track-completion commit (step 5, `:1401`). `:743` is the
    per-iteration "Commit and push the Progress update as a Workflow update
    commit" inside the fix-iteration loop — confirmed it is conditional and
    pre-verdict. So step 6 is genuinely commit-free today and a new commit there
    is the correct fix, not a duplication of an existing commit.
  - Current state: the track file now hosts the `review-done-track-open` append
    on a new, unconditional, all-reviews-pass-gated commit. The original
    "conditional/absent on a clean pass" defect is removed.
  - Criteria met: every `substate` append rides a commit that survives
    `git reset --hard HEAD` (S4); the ride commit is unconditional on the
    happy path.
- **Regression check**: checked three adjacent commit sites for collision —
  the per-iteration `:743` commit, the new step-6 commit, and the post-approval
  track-completion commit (step 5, boundary 4 carrying `decomposition-pending`).
  The track file distinguishes all three (`:229-247`). The single-step-track
  case is handled: a single-step track skips the review loop, reaches no step 6,
  gets no such commit, stays at `steps-done-review-pending`, and is carried past
  review by boundary 4 (`:240-244`, `:283-288`). The "two boundaries need a new
  commit, not one" count is consistent across the Surprises log (`:33-34`), Plan
  of Work (`:188`), and Decision Log D1 (which retains the immutable original
  count and routes the wording delta to a Phase-4 `design-final.md`
  reconciliation, `:76` / `:196-198`). Clean.
- **Verdict**: VERIFIED

#### Verify A2: Phase-B→C commit coverage by the resume commit classifier
- **Original issue**: the new Phase-B→C Workflow-update commit (boundary 2) is a
  scaffolding commit the Phase-B resume must not treat as an orphan; the track
  did not confirm it is covered by `step-implementation-recovery.md`'s
  §Resume-side commit-pattern reference.
- **Fix applied**: confirmed classifier entry 5 ("Other Workflow update
  commits … **not** orphans regardless of position") is a catch-all that already
  covers the new commit functionally; added `step-implementation-recovery.md` to
  the track's `## Interfaces and Dependencies` In-scope list with a one-line
  entry-5 enumeration addition (documentation symmetry — listing the new
  Phase-B-complete commit alongside the already-listed Phase-A decomposition
  commit).
- **Re-check**:
  - Track-file location: `## Interfaces and Dependencies` In-scope (`:359-363`)
    now names `step-implementation-recovery.md` with the entry-5 one-line
    enumeration addition, and states the entry-5 catch-all "already covers the
    new commit functionally (regardless of position)."
  - Live citation check: `step-implementation-recovery.md` §Resume-side
    commit-pattern reference entry 5 (`:315-322`) reads "Other Workflow update
    commits — touch only `_workflow/` but are not episode commits (Phase 1 init,
    Phase A decomposition, … track-completion mark, inline-replanning update,
    Phase C iteration-count Progress updates, …). They are scaffolding and
    **not** orphans regardless of position." Confirmed: (a) it is a genuine
    catch-all keyed on "touches only `_workflow/`, not an episode commit,
    regardless of position", which the new Phase-B-complete commit satisfies
    (it stages the track file `[x]` flip and the ledger append, both under
    `_workflow/`); (b) it enumerates the Phase-A decomposition commit but **not**
    a Phase-B-complete commit today, so the documentation-symmetry addition is
    accurate and non-redundant.
  - Current state: functional coverage confirmed sound (no behavioral gap — the
    new commit never resumes as an orphan); the in-scope addition is present and
    correctly scoped as a one-line enumeration entry, not a behavior change.
  - Criteria met: the new commit cannot be misclassified as an orphan by the
    Phase-B resume; the classifier enumeration names every scaffolding commit
    after the edit.
- **Regression check**: checked the resume classifier's orphan logic (entries
  1–4) — the new commit is not a `Revert step:`, not an episode commit, not a
  `Review fix:`, and touches no code paths outside `_workflow/`, so it falls only
  under entry 5; no entry-4 (implementer code commit) misroute. The addition is
  a prose enumeration entry, so it adds no new conditional needing a complement.
  Clean.
- **Verdict**: VERIFIED

#### Verify A3: a `phase=C` track could carry a stale non-empty substate
- **Original issue**: the S2/D3 closure was stated as "every `phase=C` track
  carries an explicit `substate`" reading as a stronger guarantee (terminal value
  always matches lifecycle position) than the cadence delivers; the accurate
  guarantee is non-emptiness, and a stale-but-non-empty value on a `phase=C`
  track must still route correctly.
- **Fix applied**: S2 closure tightened to non-emptiness in `## Validation and
  Acceptance` and `## Invariants & Constraints`.
- **Re-check**:
  - Track-file location: `## Validation and Acceptance` (`:324-333`) and
    `## Invariants & Constraints` S2 (`:391-400`).
  - Current state: both sections now state the accurate non-emptiness form —
    "every `phase=C` track on a current-scheme ledger carries a **non-empty**
    `substate`, so the Track 1 ledger read never takes the empty-read roster
    fallback for a current plan." The Invariants section explicitly distinguishes
    the guarantee from the stronger claim: "S2 guarantees non-emptiness, not that
    the terminal value always matches lifecycle position beyond what the cadence
    delivers: a single-step track terminates at `steps-done-review-pending`
    (no review loop, no step-6 commit) and routes correctly to completion."
  - Criteria met: the closure invariant is now stated as exactly what the cadence
    guarantees (a non-empty last-value-wins `substate` on every `phase=C` track),
    and the resume routes correctly whether the terminal value is
    `steps-done-review-pending` (single-step) or `review-done-track-open`
    (multi-step) — the resume "checks whether review applies and proceeds to
    completion."
- **Regression check**: cross-checked against the replan-revert framing (A4's
  neighbour) — boundary 5 writes `steps-partial` but the `--phase 0` reset routes
  the resume to State 0, so no `phase=C` substate is read on the replan resume;
  the non-emptiness claim is scoped to "current-scheme ledger" `phase=C` tracks
  and is not weakened by the replan path. The diagram (`:145-160`) shows the
  multi-step terminal edge; the single-step terminal-at-`steps-done-review-pending`
  path is carried in the edge-case prose (`:283-288`) and the note in the diagram
  scopes Track 1's start state — diagram and prose are consistent. Clean.
- **Verdict**: VERIFIED

#### Verify A4: the D1+D3 wiring-pair could leave a `phase=C` track with no substate
- **Original issue**: the D1 A→C `steps-partial` append and the D3 track-advance
  `decomposition-pending` append, if split across steps, could land a half-state
  where the *next* track sits at `phase=C` with no `substate`, silently triggering
  the fallback — the exact failure mode this branch fixes; "in this track" was not
  a strong enough constraint.
- **Fix applied**: `## Invariants & Constraints` now requires the D1+D3 pair to
  land in **one step** (or steps sharing one mergeable commit), not merely "in
  this track."
- **Re-check**:
  - Track-file location: `## Invariants & Constraints` constraints block
    (`:409-418`).
  - Current state: the constraint reads "Both the A→C `steps-partial` append (D1,
    `track-review.md`) and the track-advance `decomposition-pending` append (D3,
    `track-code-review.md`) MUST land in **one step** (or steps sharing one
    mergeable commit), not merely 'in this track.'" It states the failure mode
    explicitly ("a split intermediate commit … leaves the *next* track at
    `phase=C` with no `substate`, silently triggering the fallback") and the
    enforcement hook ("The decomposition in `## Concrete Steps` satisfies this by
    keeping the `track-review.md` and `track-code-review.md` append wiring in the
    same step"). The Decision Log D3 (`:106-110`) carries the matching
    "D1+D3 wiring-pair constraint" framing.
  - Criteria met: the no-half-state invariant is now a single-step / single-commit
    constraint with a named enforcement point at decomposition, not a track-scope
    aspiration. The two append sites live in two files (`track-review.md` and
    `track-code-review.md`), so the "natural per-file decomposition would split
    them" risk is exactly the one the constraint forecloses.
  - Criteria note: `## Concrete Steps` is a Phase-A placeholder at this point
    (`:295-296`), so the constraint is the binding instruction the decomposition
    must honour; verification is that the constraint is present and unambiguous,
    which it is. Decomposition compliance is checked at Phase A decomposition, not
    here.
- **Regression check**: checked that the single-step requirement does not conflict
  with the per-file in-scope split in `## Interfaces` (`:347-363`) — the in-scope
  list names files, not steps, so no contradiction; the constraint binds the
  *step* decomposition, which is orthogonal to the file list. Clean.
- **Verdict**: VERIFIED

#### Verify A5 (REJECTED): the core→consumer sizing cut vs folding into Track 1
- **Rejection reason**: the challenge argued Track 2 (~5 files) should fold into
  Track 1 rather than stand as a separate track. The decision held: the cut sits
  at the core→consumer dependency boundary; Track 2's doc-only append-site wiring
  has no behavior to validate until Track 1's read side exists, and folding it in
  would mix resume-protocol prose with the tested primitive and forfeit Track 1's
  independent landing and validation. No change made.
- **Downstream check**: the sizing justification stands in `## Interfaces and
  Dependencies` (`:373-381`) — ~5 files (the four resume-protocol docs plus the
  one-line `step-implementation-recovery.md` enumeration addition), below the ~12
  fill target, a deliberate merge candidate cut at the core→consumer seam, with a
  written rationale (independent landing + validation; doc-prose vs tested-primitive
  separation; the dependency boundary is the natural seam). Leaving the cut as-is
  introduces no downstream issue: Track 2 `Depends on: Track 1` (`:369-371`) is
  declared and honoured, Track 1 already landed dormant and mergeable (its empty
  `substate` read routes to the fallback), and the A2 in-scope addition raised the
  file count to ~5 without crossing into "too small to stand alone" — the track
  still carries its own validation surface (the append-cadence review plus the
  Track 1 tests it feeds). The sizing justification is the kind a Phase-2
  structural review requires for a below-target track, and it is present and
  sound, so no structural-review escalation is provoked by leaving the cut.
- **Verdict**: REJECTED (no action needed)

## Findings

(No new findings surfaced during verification.)

## Summary

PASS. All four ACCEPTED fixes (A1, A2, A3, A4) verify clean against the updated
track file with no regression; the REJECTED decision (A5) holds with no
downstream issue. No new finding surfaced.
