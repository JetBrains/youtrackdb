# Research Log — mid-track-resume

## Initial request

Fix YTDB-1134: `workflow-startup-precheck.sh` mis-routes a mid-track resume
because `roster_scan` only reads the column-0 `N. ` line of each
`## Concrete Steps` entry. When a step description hard-wraps, its
`— risk: <tag>  [<glyph>]` tail lands on an indented continuation line, the
column-0 line carries no `risk:` and is skipped, `ROSTER_STEP_COUNT` stays 0,
and `determine_c_substate` falls through to `steps-partial`. A track whose
steps are all `[x]` with `## Progress` code-review still `[ ]` then resolves to
`steps-partial` instead of `steps-done-review-pending`, routing the resume back
into Phase B for a `[ ]` step that does not exist.

**Chosen direction (user, 2026-06-23):** rather than the issue's narrow
`roster_scan`-hardening fix, re-route the State-C sub-state source to the
**phase ledger** so resume stops parsing the track-file `## Concrete Steps`
roster entirely — "re-route source to the ledger and leave track file in
peace." This removes the fragile-roster-parse failure class rather than
hardening one parser.

## Decision Log

#### D1: Source the State-C sub-state from a track-scoped `substate` ledger key
- **Why:** The bug class is "the fine-grained resume signal lives in a
  fragile-to-parse place (the `## Concrete Steps` roster) when a durable one
  (the phase ledger) already records the coarse signal." Adding one
  `substate=<slug>` key to the ledger grammar maps 1:1 to the sub-state slugs
  `workflow.md` step-5 already routes on, leaves the phase enum `{0,A,C,D,Done}`
  untouched, and lets the precheck resolve the sub-state without reading the
  track file. (User-chosen direction, 2026-06-23; shape A confirmed.)
- **Alternatives rejected:** (B) a real `phase=B` token plus flags — bigger
  blast radius (the phase enum and every consumer change) and still cannot
  express `failed-step` / `review-done-track-open` without an extra field
  anyway. (Issue's narrow fix) harden `roster_scan` only — leaves the
  fragile-parse class in place as the routing source.
- **Scoping rule:** the ledger is last-value-wins across the whole file, so the
  `substate` read MUST be **track-scoped** (the last `substate` on a line whose
  `track=` equals the active track), else track N's terminal sub-state leaks
  into track N+1's resume. A new track-scoped reader is needed; the existing
  `ledger_tail_value` is global.
- **Append cadence — every `substate` append rides an already-committed
  boundary** (revised after adversarial iter-1 A1/A2). The ledger only records
  sub-states that survive a `git reset --hard HEAD`, so the four ledger
  sub-states each ride an existing commit:
  | Milestone | `substate` appended | Rides commit |
  |---|---|---|
  | Phase A decomposition complete | `steps-partial` | A→C commit (`track-review.md:600-608`) |
  | All steps complete (Phase B→C) | `steps-done-review-pending` | the Phase B→C boundary commit |
  | Code review passed (pre-approval) | `review-done-track-open` | the **pre-approval** code-review-complete Workflow-update commit (`track-code-review.md:743`) — NOT the post-approval completion commit (A2: else a crash during the approval wait re-runs the code-review fan-out) |
  | Track complete → next track | `decomposition-pending` (for track N+1) | track-completion commit (`track-code-review.md:1419-1424`) |
  `failed-step` is **NOT** a ledger sub-state (A1): the failure writes (`[!]`
  roster flip, FAILED episode, retry rows) are uncommitted in-session and
  reverted by the next `git reset --hard HEAD`; the `failed-step` resume is
  reachable only via crash, which the Phase B resume Detection already
  reconciles from working-tree artifacts (`step-implementation-recovery.md:166-203`,
  reconstructing a missing `[!]`). On the ledger path a crashed-during-failure
  session resumes as `steps-partial` and that same Detection finds the `[!]`/
  retry rows. `failed-step` stays a fallback-path / working-tree signal only.
- **Risks/Caveats:** per-step appends are NOT needed — the milestone flips carry
  the enum, and the step pointer for a `steps-partial` resume is resolved later
  by the agent reading the track file as prose. The ledger path emits four
  sub-states {`decomposition-pending`, `steps-partial`, `steps-done-review-pending`,
  `review-done-track-open`}; `failed-step` and `section-discrepancy` are
  fallback-path-only (see D2).

#### D2: Drop `section-discrepancy` from routing; keep + fix `roster_scan` as the fallback
- **Why:** `section-discrepancy` is a torn-write cross-check that exists only
  because today's resume reads two track-file sources (roster + `## Progress`)
  that can disagree. With the ledger as the single routing source there is
  nothing to cross-check at routing time, and the ledger line commits atomically
  with the track-file change, so a crashed boundary cannot leave them
  inconsistent. The non-ledger `determine_state` walk and pre-this-change
  ledgers still need a sub-state source, so `determine_c_substate` falls back to
  the existing roster+Progress read (which keeps `section-discrepancy`) when the
  track-scoped `substate` read is empty. Applying the issue's wrap-tolerant fix
  to `roster_scan` makes that fallback correct and satisfies YTDB-1134's literal
  acceptance criteria (count a wrapped step, regression test) for free.
- **Alternatives rejected:** fully retire `roster_scan` and the joint read —
  breaks resume for any branch mid-flight at merge time and for pre-ledger
  in-flight `lite`/`full` plans; the wrap fix is cheap, so keeping it costs
  little. Keep `section-discrepancy` on the ledger path — no second source to
  disagree with, so it would be dead code there.
- **Risks/Caveats:** two sub-state computations now coexist — ledger-authoritative
  primary (emits the four committed sub-states) and the roster+Progress fallback
  (which alone emits `failed-step` from a working-tree `[!]` and
  `section-discrepancy` from the roster-vs-Progress cross-check). They must stay
  behaviorally aligned on the four shared sub-states. **Mandate (A3): a dual-path
  parity test** in `test_workflow_startup_precheck.py` — for a fixture whose
  ledger carries `substate=<slug>` and whose track file's roster/Progress imply
  the same `<slug>`, assert the ledger path and the (ledger-stripped) fallback
  path resolve to the identical sub-state — so the two readers cannot silently
  diverge.

#### D3: Track-advance append sets `substate=decomposition-pending` explicitly
- **Why:** On a current-scheme ledger every `phase=C` track then always carries
  an explicit `substate`, so "empty `substate` on a `phase=C` track" means
  exactly one thing — a pre-this-change ledger — which is the unambiguous
  trigger to fall back to `roster_scan`. Matches the script's loud/explicit
  posture (conventions.md §1.6(e): an absent value is an explicit decision
  point, never a silent default); the bug being fixed is itself a silent-default
  mis-route.
- **Alternatives rejected:** default an empty sub-state read to
  `decomposition-pending` — conflates "genuinely not decomposed" with "the
  append was lost / this is an old ledger," re-introducing the silent-default
  failure mode. Track 1 never hits this state (it is `phase=A` until its A→C
  append sets `substate=steps-partial`); only an advanced track N+1 sits at
  `phase=C` undecomposed, and the advance append is where the explicit value
  lands.
- **Closure invariant (A4):** on a current-scheme ledger every `phase=C` track
  carries an explicit `substate` — the A→C append sets `steps-partial`, the
  track-advance append sets `decomposition-pending` for the next track, and the
  two Phase-C milestones set the rest (D1) — so an empty track-scoped `substate`
  read on a `phase=C` track means exactly "pre-this-change ledger → fall back to
  `roster_scan`," nothing else. Both wiring halves (the A→C site in D1 and the
  advance site in D3) must land together; a half-implementation leaves a
  `phase=C` track with no `substate` and silently triggers the fallback when it
  should not.
- **Risks/Caveats:** one extra field on the track-advance append; `phase==A`
  (no track file yet) still routes by top-level phase and reads no sub-state.

## Surprises & Discoveries

- [2026-06-23T16:24Z] [ctx=safe] Complete ledger append-site inventory (7 sites):
  create-plan Phase-1 seed (`--phase 0 --tier --categories [--s17]`);
  `implementation-review.md:646` / `review-plan/SKILL.md:103` plan-review pass
  (`--phase A`); `track-review.md:596,1048` Phase-A complete
  (`--phase C --track N`); `track-code-review.md:1409` track-advance
  (`--track N+1`, phase carried=C); `track-code-review.md:1411` last-track
  (`--phase D`); `inline-replanning.md:169,249` (`--tier`, ESCALATE `--phase 0`);
  `mid-phase-handoff.md:186-187` (`--paused state0|phase4`). No `phase=B` exists.
- [2026-06-23T16:24Z] [ctx=safe] The ledger and the track file are staged and
  committed together (`track-review.md:600-608`; `track-code-review.md:1419-1424`)
  and the implementer revert path is `git reset --hard HEAD`, so a crash between
  a `substate` append and the commit reverts both atomically — the ledger and
  track file cannot diverge at a committed boundary. This strengthens the case
  for dropping `section-discrepancy` from the routing path (its torn-write guard
  loses its reason to exist once the ledger is the single routing source).

- [2026-06-23T16:21Z] [ctx=safe] The phase ledger today records only coarse
  phase boundaries; the phase enum is `{0, A, C, D, Done}` with **no `B`**.
  `phase=C track=N` is appended at the *end of Phase A* (decomposition
  complete; `track-review.md:596`) and means "track active, executing B or C".
  The within-track sub-state (decomposition done? steps done? review done?
  failed? interrupted write?) is read entirely from the **track file** by
  `determine_c_substate` → `roster_scan` + `progress_step_numbers`. The ledger
  carries none of it.
- [2026-06-23T16:21Z] [ctx=safe] Ledger append sites today: create-plan Phase 1
  seed (`phase=0` + tier/categories/s17); `track-review.md` A→C boundary
  (`phase=C track=N`); `track-code-review.md` track completion
  (`--track N+1`, phase stays C) or last-track (`--phase D`). So the ledger
  transitions `0 → C(t1) → C(t2) → … → D → Done` and never records B/C
  progress within a track.
- [2026-06-23T16:21Z] [ctx=safe] The precheck's `determine_c_substate` returns
  only the sub-state slug. The *step pointer* for a `steps-partial` /
  `failed-step` resume ("which `[ ]` step to resume from") is resolved later by
  the agent following `step-implementation-recovery.md`, reading the track file
  as prose — not by the precheck. So moving the sub-state *decision* to the
  ledger does not require the ledger to record per-step pointers; the precheck
  needs only the sub-state enum.
- [2026-06-23T16:21Z] [ctx=safe] `section-discrepancy` is not a progress marker
  — it is an integrity cross-check (`determine_c_substate:1731-1738`) that
  detects a torn write between sub-step 7.1 (roster `[x]` flip) and 7.2
  (`## Progress` `Step N` append). It exists *because* the resume reads both the
  roster and Progress and they can disagree. If the ledger becomes authoritative
  and the roster is no longer the routing source, this check changes nature.

- [2026-06-23T16:30Z] [ctx=safe] Verified the adversarial iter-1 A1/A2 code
  claims before revising decisions: (A1) the failed-step path's four writes
  (`[!]` flip, FAILED episode, Progress entry, retry rows) are uncommitted
  in-session and reconciled from working-tree artifacts by the Phase B resume
  Detection (`step-implementation-recovery.md:166-203`, which reconstructs a
  missing `[!]` from the revert body) — so a `substate=failed-step` append has
  no committed boundary to ride; confirmed. (A2) the code-review-iteration
  Progress `[x]` is committed pre-approval as a Workflow-update commit
  (`track-code-review.md:743`) while track completion is deliberately deferred
  to post-approval (`:1471-1487` "Why deferred write") — so the
  `review-done-track-open` append must ride the pre-approval commit; confirmed.

## Open Questions

- [2026-06-23T16:21Z] [ctx=safe] RESOLVED by D1 — shape A (track-scoped
  `substate` ledger key), confirmed by the user.
- [2026-06-23T16:21Z] [ctx=safe] RESOLVED by D2 — drop `section-discrepancy`
  from routing; keep + fix `roster_scan` as the fallback for the non-ledger
  walk and pre-change ledgers.
- [2026-06-23T16:21Z] [ctx=safe] RESOLVED by D2/D3 — back-compat is the
  empty-substate fallback to a wrap-fixed `roster_scan`; the explicit
  decomposition-pending advance append makes empty-substate an unambiguous
  pre-change-ledger signal.
- [2026-06-23T16:24Z] [ctx=safe] RESOLVED into D1 (after adversarial iter-1
  A1/A2) — the append cadence with committed boundaries is now decided, not a
  deferred detail: `track-review.md` A→C gains `substate=steps-partial`; the
  Phase B→C boundary gains `substate=steps-done-review-pending`;
  `track-code-review.md:743` pre-approval code-review-complete commit gains
  `substate=review-done-track-open`; `track-code-review.md` track-advance gains
  `substate=decomposition-pending`. NO `step-failure` append (A1). What remains
  for Phase 1 is the mechanical implementation: the `--substate` flag +
  `LEDGER_SUBSTATE` accumulator + bare-token validation + the track-scoped
  reader in `workflow-startup-precheck.sh`; the grammar in the script header,
  the `conventions.md` Phase-ledger glossary, and `conventions-execution.md §2.1`;
  the test surface in `test_workflow_startup_precheck.py` (ledger path, fallback
  path, the dual-path parity test from D2, plus the wrapped-roster regression
  test from the issue).
- [2026-06-23T16:24Z] [ctx=safe] Edge cases for Phase 1 to cover:
  single-step tracks skip code review (who appends `review-done-track-open`
  when review is skipped — likely the track-completion path); `[~]` skipped
  steps count toward all-steps-complete; an inline-replan that adds steps to a
  review-pending track must append `substate=steps-partial` to revert.
- [2026-06-23T16:24Z] [ctx=safe] §1.7 staging: this is a workflow-modifying
  branch touching `.claude/scripts/**` (a code file, not pure prose), so it is
  NOT a §1.7(k) prose-rule opt-out candidate — the script edits must be staged
  under `_workflow/staged-workflow/.claude/...` and promoted in Phase 4. Tier
  classification (Step 4) confirms.

## Baseline and re-validation

Workflow-modifying branch (touches `.claude/scripts/workflow-startup-precheck.sh`,
its test suite, and `.claude/workflow/**` resume-protocol docs), so it carries a
rebase-drift baseline.

- Branch fork point / HEAD at branch start: `6dba771b5cf142c026b5cddcbaa8e4c211e93d34`
  (branch has not diverged from `origin/develop` yet).
- Workflow-format HEAD at branch start: `04d2cf165f77a5e8384a4b7a64cd119510930473`.
- Re-validate against `develop` before the Phase 4 promotion (the staged-vs-live
  reconciliation a workflow-modifying branch needs); confirm no conflicting
  resume-protocol or ledger-grammar change landed on `develop` in the interim.

## Adversarial gate record

### Adversarial review of this log (2026-06-23T16:30Z) — NEEDS REVISION: 2 should-fix, 2 suggestions
Iteration 1 (model: opus — `fable` unavailable in this environment, documented
env fallback per D14, not a downgrade). 0 blockers. A1 (should-fix): drop
`step-failure` from the ledger append sites (uncommitted, working-tree-
reconciled). A2 (should-fix): pin the `review-done-track-open` append to the
pre-approval code-review-complete commit. A3/A4 (suggestions): dual-path parity
test; empty-substate closure invariant. All four absorbed into D1/D2/D3.
Review file: `_workflow/reviews/research-log-adversarial-iter1.md`.
