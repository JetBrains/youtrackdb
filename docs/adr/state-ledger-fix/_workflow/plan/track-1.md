<!-- workflow-sha: 5de2481272b55f8ccb712214ec70564093f2baa1 -->
# Track 1: Append the A→C phase-ledger boundary at Phase A completion

## Purpose / Big Picture

After this track lands, a fresh `/execute-tracks` started once a track's
Phase A has completed resumes into Phase B/C, because the phase ledger now
reads `phase=C`, instead of re-running the whole Phase A.

The phase ledger is the append-only event log (one line per phase boundary)
that the startup precheck reads to decide where a resumed session re-enters
the workflow. Today the Phase A completion protocol writes the decomposed
track file and commits it, but never appends the `phase=C track=N` line that
marks "Phase A done, Phase B next." The ledger therefore stays at the last
recorded value, `phase=A`. On the next `/execute-tracks` the precheck reads
`phase=A`, the startup router treats that as "this track has no track file
yet, run Phase A from the top," and the session re-runs Track Pre-Flight plus
the three Phase A review sub-agents plus decomposition — work already done,
and a re-decomposition can overwrite the step roster the prior session wrote.
This is YTDB-1140 (Bug / Major / dev-workflow).

The fix is doc-only. The precheck script and its tests already accept and
exercise `--append-ledger --phase C --track N`; what is missing is the
orchestrator-facing instruction to *call* it. This track adds that call to
the Phase A completion protocol in `track-review.md`, adds a verification
clause so a skipped or corrupted append is caught and recovered, and adds a
regression guard so the instruction cannot be silently dropped again.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-18T14:36Z [ctx=safe] Review + decomposition complete
- [x] 2026-06-18T15:12Z [ctx=safe] Step 1 complete (commit 53d4595915)

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Track-canonical live carrier (D7). Full four-bullet DRs D1/D2/D3 below. -->

### D1: Bundle the A→C ledger append into the existing Phase A commit

Place the `workflow-startup-precheck.sh --append-ledger --ctx <level>
--phase C --track <N>` call in `track-review.md` §What You Do **step 6**,
immediately before that step's `git commit`, and add `phase-ledger.md` to
that step's `git add`. Reuse the `<level>` value step 5 already read for its
`## Progress` write to feed `--ctx` (`unknown` when step 5's statusline read
missed — a valid bare `--ctx` token, distinct from the script's `safe` default
for an omitted flag). Add a ledger-tail check (the tail
must read `phase=C track=<N>`) to §Phase A Completion step 2's verification,
**with its own recovery branch**: when the Phase A commit is present but the
ledger tail is not `phase=C track=<N>` (a corrupted or hand-edited
append-only ledger), re-run the step-6 append, make a dedicated
`git add phase-ledger.md && git commit && git push`, then re-verify. This
mirrors step 2's existing missing-commit recovery ("run step 6 now") and
keeps the check a true gate rather than a passive assertion.

- **Why:** the append must be committed and pushed before Phase B spawns the
  first implementer (the implementer's revert path is `git reset --hard
  HEAD`, which discards anything uncommitted). Bundling the append into step 6
  gives one atomic Workflow-update commit (the decomposition and the phase
  advance land together). That tree stays clean, so §Phase A Completion step 2's
  existing clean-tree check still holds. The bundle also reuses the
  context-level read step 6 already performs, so there is no second statusline
  read and no second commit. The transition stays a prose-instructed
  orchestrator append rather than a new script-driven auto-append subcommand,
  for parity with the four sibling append sites (`implementation-review.md:646`,
  `track-code-review.md:1403/1405`, inline-replan, mid-phase-handoff): one
  ledger-writer surface, no new script behavior to test. The recovery branch
  closes the robustness gap a prose step otherwise carries. Without it, a
  skipped or garbled append would leave the next session re-running Phase A,
  the exact bug this fix removes.
- **Alternatives rejected:** (a) the issue's literal placement (the append in
  §Phase A Completion after step 2) dirties a tree step 2 has just verified
  clean, and leaves the ledger line uncommitted and unpushed unless a second
  dedicated commit+push is added, breaking the one-commit-per-Phase-A pattern.
  (b) a script-driven `--complete-phase-a` auto-append that removes the
  orchestrator-in-the-loop step entirely. It is more robust against a skipped
  step, but it diverges from the established prose-instructed sibling pattern,
  adds a new script subcommand with its own tests, and is broader than this bug
  warrants. The recovery branch gives most of that robustness at the cost of
  one verification clause.

### D2: Add a doc-presence regression guard matching the full invocation

Add an automated guard that asserts the A→C append **call** is present at its
site in `track-review.md`. The guard reproduces the bug, the call's absence,
and is **arg-order- and whitespace-tolerant**: it asserts the §What You Do
step-6 line carries all three of `--append-ledger`, `--phase C`, and
`--track`, in any order and across any whitespace, rather than matching a
fixed contiguous string. The preferred form is a Python doc-presence test in
the existing test module (`test_workflow_startup_precheck.py`), which already
resolves `.claude/workflow/` paths from the repo root and so can read
`track-review.md` directly. The guard MUST resolve `track-review.md` relative
to the module's `REPO_ROOT` anchor (`Path(__file__).resolve().parents[3]`, the
same anchor `SCRIPT_PATH` uses at `:53`) — **not** `LIVE_REPO_ROOT` (`:80`, the
anchor `CONVENTIONS_PATH` copies at `:3005`). Under §1.7(b) staging the test
file and the fixed `track-review.md` both live in the staged subtree, so
co-located `REPO_ROOT` (`parents[3]`) reads the staged *fixed* copy carrying
the append while `LIVE_REPO_ROOT` walks up to the develop-state *unfixed* live
copy. Anchoring on `LIVE_REPO_ROOT` would false-fail the guard during this
branch's own Phase B — the fix self-applies only at the Phase 4 promotion (D3)
— whereas `REPO_ROOT` keeps it green during the branch and after promotion
(post-promotion the two anchors coincide on the fixed file), mirroring §1.7(d)
"staged copy authoritative when present."

- **Why:** CLAUDE.md requires a regression test for every bug fix. The script
  behavior (the `--phase C` append and the resulting `phase=C` read) is
  already covered by `test_workflow_startup_precheck.py:3426/3447`. The gap the
  bug exposed is the missing **doc instruction**, so the guard checks the doc,
  not the script. The guard must not be a bare `grep -- '--phase C'`: D1's own
  step-2 verification prose now contains the literal `phase=C track=<N>`, so a
  bare substring match would self-satisfy from the verification text even if
  the real append call in step 6 were deleted, guarding the appearance of a
  string, not the instruction. The guard must also not require a contiguous
  `--append-ledger --phase C --track` match: the canonical argument order
  (script Usage line 120) puts `--ctx` before `--phase`, so the real call reads
  `--append-ledger --ctx <level> --phase C --track <N>`, and a contiguous-string
  guard would be a false-negative against the correct code. Matching the three
  flags order-independently pins both the call site and its executable shape:
  the guard fails when the actual append is dropped and passes only when the
  real call is present.
- **Alternatives rejected:** (a) a bare `grep -- '--phase C'` presence check,
  a substring match the verification prose alone satisfies, so it guards a
  string's appearance, not the instruction's presence. (b) rely only on the
  existing script tests plus a one-time manual grep at acceptance, which leaves
  no standing guard against the instruction being dropped again on a future edit.

### D3: minimal tier, §1.7(b) staging (not the §1.7(k) opt-out)

Tier = `minimal` — a single track, no design document, no plan; this track
file is the change's whole canonical record. Stage the `.claude/**` edits
under `_workflow/staged-workflow/.claude/` per §1.7(b); seed the phase
ledger's `s17` field with the workflow-modifying token; promote the staged
subtree to its live paths at Phase 4. No matched HIGH-risk category is
central, so the adversarial gate runs lens-free.

- **Why:** the change edits `track-review.md`, which is orchestrator procedure
  a running Phase A **executes**: step 6 carries a bash `--append-ledger`
  command the orchestrator runs. §1.7(k)'s opt-out criterion (2) keys on the
  edited file's in-branch consumer class and excludes files a running phase
  reads as executable procedure; an execution-procedure file fails that
  criterion and must stage. Staging keeps the live workflow at develop state
  while this branch runs its own Phase A, so the fix does not alter this
  branch's own execution mid-flight.
- **Alternatives rejected:** the §1.7(k) prose-rule self-application opt-out
  (edit the live files directly, no staging). The edited file is executed
  machinery, not judgment-layer prose, so it fails the opt-out's consumer-class
  test; opting out would also change this branch's own Phase A completion
  behavior mid-branch.
- **Accepted self-application wrinkle:** because the edits are staged, the
  orchestrator running *this* branch's own Phase A reads the **live**
  (develop-state) `track-review.md`, which lacks the fix — so this branch
  self-inflicts the bug. Expect a stale `phase=A` on resume of this branch and
  append `phase=C track=1` by hand to route the precheck to Phase B/C, exactly
  as the originating branch `understandable-design` did. The fix self-applies
  only after the Phase 4 promotion. This is the accepted price of staging.

### D4: Extend the ledger-tail check to both Phase A completion paths (review-driven)

Step-level instruction-completeness review (WI1) found that gating only
§Phase A Completion step 2 on the ledger tail left §Phase A Resume row 3
able to declare "steady state, route to Phase B" from the track-file commit
alone. On a committed-roster-but-stale-ledger state that path would re-run
Phase A, the YTDB-1140 trace on the resume path. The row-3 steady-state exit
now gates on the ledger tail reading `phase=C track=<N>` and delegates
recovery to step 2. The resume-path `--ctx <level>` unbound-token edge (WI2)
is closed with a fresh-read / `unknown` fallback that preserves D1's
no-second-statusline-read on the normal path. See Episodes §Step 1.

- **Why:** D1's Plan of Work scoped the change to the §What You Do step-6
  append plus the §Phase A Completion step-2 verification. Phase A has two
  completion paths, so the fix has to close the bug on both or a resumed
  session can still re-run Phase A. WI3 (a per-call append exit-code check)
  stays unapplied: D1 keeps the append prose-instructed for parity with the
  four sibling sites, and the two-path ledger-tail verification is the
  backstop that catches a failed or skipped append.

## Outcomes & Retrospective
<!-- Continuous-log. Phase A review entries prefixed by review type. -->
- [x] Technical: PASS at iteration 2 (3 findings, 3 accepted — T1 should-fix
  guard-anchor pin to `REPO_ROOT`; T2/T3 prose-precision suggestions). Risk and
  Adversarial dropped — `minimal` tier runs Technical only.

## Context and Orientation

The codebase touched here is the workflow machinery under `.claude/`, not
product code. Three pieces interact, and the bug lives in the seam between
the first two.

**The phase ledger** (`_workflow/phase-ledger.md`) is an append-only event
log — one line per phase boundary, written by the orchestrator through
`workflow-startup-precheck.sh --append-ledger`. Each line carries
`key=value` fields (`phase`, `track`, `tier`, `s17`, a `[ctx=<level>]`
marker, and others). A reader keeps the **last value of each key**
(last-value-wins), so advancing a phase appends a new line rather than
rewriting the old one. The ledger owns the branch-level resume state: which
phase a fresh session re-enters, and on which track.

**The Phase A completion protocol** lives in `track-review.md`. Phase A
reviews and decomposes the upcoming track, then ends the session at a
mandatory boundary so Phase B starts fresh. Two sub-sections matter:

- §What You Do **step 6** is the single point where Phase A's on-disk work
  is committed. It stages `track-<N>.md`, commits "Phase A review and
  decomposition for <track>", and pushes. Just before this, step 5 already
  performs a statusline context-level read: it reads the context level from
  `/tmp/claude-code-context-usage-$PPID.txt`, falls back to `unknown` on a
  miss, and writes a `## Progress` entry tagged `[ctx=<level>]`. That same
  `<level>` is available to feed the ledger append's `--ctx`, with no second
  read.
- §Phase A Completion **step 2** is the session-boundary gate. It runs
  `git status --porcelain` to confirm a clean tree and `git log -1
  --oneline` to confirm the tip is the Phase A commit, with a recovery branch
  that re-runs step 6 if the commit is missing (an interrupted session
  between step 5 and step 6).

**The startup router** lives in `workflow.md` §Startup Protocol. On each
`/execute-tracks`, `workflow-startup-precheck.sh --mode full` reads the
ledger and emits a `state.phase`. The router then branches on it (step 5):
`phase == "A"` is read as "the active track has no track file yet,
pre-Phase-A" and routes to Track Pre-Flight plus Phase A; `phase == "C"` is
the mid-track resume; it routes on `state.substate` and skips the Track
Pre-Flight gate. A `decomposition-pending` substate enters Phase A Resume
(a near no-op, since the track file already exists); any other substate
enters Phase B/C. For a *completed* Phase A — `## Progress` "Review +
decomposition" `[x]` and a populated `[ ]` step roster — `determine_c_substate`
resolves the C-case to `steps-partial`, which routes to Phase B;
`decomposition-pending` is the distinct interrupted-before-decomposition case.
So the boundary this fix records sends a resumed session to Phase B, the
intended outcome. The script supports both: its arg parser accepts `--phase`
(line 149), and its `determine_state_from_ledger` has a `C)` case (line
1781) that reads the active track from the ledger, finds the track file, and
emits `{phase:"C", substate:<track-driven>}`.

**The bug** is the absence of any A→C append site. The documented append
sites are State 0→A (`implementation-review.md:646`, `--phase A`) and track
completion (`track-code-review.md:1403/1405`, `--track N+1` / `--phase D`).
Nothing appends the A→C boundary, so after Phase A the ledger tail is still
`phase=A`, and the router re-runs Phase A. The trace the fix prevents:

1. Phase A completes and commits the roster.
2. The user clears context and re-runs `/execute-tracks`.
3. The precheck reads the stale `phase=A`.
4. The router re-enters Track Pre-Flight, the three review sub-agents, and
   decomposition.
5. The second decomposition overwrites the roster the first session wrote.

## Plan of Work

The edit sequence:

1. **Stage `track-review.md`** under `_workflow/staged-workflow/.claude/`.
   In §What You Do step 6, add the
   `workflow-startup-precheck.sh --append-ledger --ctx <level> --phase C
   --track <N>` call immediately before the `git commit`, reusing the
   `<level>` step 5/6 already read for its `## Progress` write, and add
   `phase-ledger.md` to step 6's explicit `git add` path list.
2. **Add the §Phase A Completion step-2 ledger-tail verification.** Extend
   step 2's checks so it confirms the ledger tail reads `phase=C track=<N>`,
   with a recovery branch for "commit present but tail wrong" that re-runs
   the step-6 append, makes a dedicated `git add phase-ledger.md && git
   commit && git push`, and re-verifies — mirroring the existing
   missing-commit recovery.
3. **Add the regression guard** (D2): a doc-presence test asserting the
   step-6 append line carries all of `--append-ledger`, `--phase C`, and
   `--track`, order- and whitespace-tolerant, in the existing test module,
   resolving `track-review.md` via the module's `REPO_ROOT` anchor (the staged
   copy under staging), not `LIVE_REPO_ROOT` (see D2).
4. **Verify:** the doc-presence guard passes; the existing script tests at
   `test_workflow_startup_precheck.py:3426/3447` still pass; and
   `grep -- '--phase C --track' .claude/workflow/track-review.md` is
   non-empty (the issue's acceptance criterion #3, asserting a real A→C
   append site exists outside the test suite).

**Ordering constraints:** the step-6 append must land in the **same commit**
as the decomposition (the atomicity D1 depends on). The step-2 verification
depends on the step-6 append already existing — it gates what step 6 wrote,
so it cannot precede it. The regression guard depends only on the step-6 edit
being present.

## Concrete Steps

1. Implement the YTDB-1140 fix per §Plan of Work, routing every `.claude/**`
   edit to the staged subtree under `_workflow/staged-workflow/.claude/`
   (§1.7(b)): (a) add the `workflow-startup-precheck.sh --append-ledger --ctx
   <level> --phase C --track <N>` call to `track-review.md` §What You Do step 6
   immediately before its `git commit`, and add `phase-ledger.md` to that
   step's `git add`; (b) add the §Phase A Completion step-2 ledger-tail
   verification (tail reads `phase=C track=<N>`) with its
   commit-present-but-tail-wrong recovery branch; (c) add the order- and
   whitespace-tolerant doc-presence regression guard to
   `test_workflow_startup_precheck.py`, resolving `track-review.md` via the
   module's `REPO_ROOT` anchor, not `LIVE_REPO_ROOT` (D2). Verify per §Validation
   and Acceptance. — risk: high (Workflow machinery: drives the auto-resume
   state machine — adds the A→C ledger boundary that resume routing reads)  [x]  commit: 53d4595915

<!-- One coherent HIGH step (HIGH-risk isolation): the append instruction, its
     completion-gate verification, and its regression guard are one logically
     continuous change, kept together so Phase B step-level dimensional review
     sees the whole fix at once. No size clause — HIGH steps are sized by the
     change, not the ~12 fill target. -->


## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

### Step 1 — commit 53d4595915, 2026-06-18T15:12Z [ctx=safe]
**What was done:** Implemented the YTDB-1140 fix as staged `.claude/**`
edits under `_workflow/staged-workflow/` (§1.7(b)). `track-review.md`
§What You Do step 6 now appends the A→C phase-ledger boundary
(`workflow-startup-precheck.sh --append-ledger --ctx <level> --phase C
--track <N>`) before its commit, with `phase-ledger.md` added to the
`git add`. §Phase A Completion step 2 now verifies the ledger tail reads
`phase=C track=<N>` and carries a recovery branch for a
committed-roster-but-unadvanced-ledger state. A `REPO_ROOT`-anchored,
region-sliced, order-independent doc-presence guard
(`test_track_review_step6_carries_ac_ledger_append`) was added to
`test_workflow_startup_precheck.py` and registered in its `TESTS` list.
The step-level review loop converged at iteration 2 (writing-style
WS1/WS2 and instruction-completeness WI1/WI2 all VERIFIED).

**What was discovered:** The review surfaced that the ledger-tail check
had to cover both Phase A completion paths, not just §Phase A Completion
step 2. §Phase A Resume row 3 had declared "steady state, route to
Phase B" from the track-file commit alone, so a
committed-roster-but-stale-ledger state on that path would re-run Phase A,
the same YTDB-1140 trace. The fix now gates that exit on the ledger tail
and delegates recovery to step 2. A second resume-path edge (the append's
`--ctx <level>` is unbound when sub-step 5 did not run this session) was
closed with a fresh-read / `unknown` fallback that preserves D1's
no-second-read on the normal path.

**What changed from the plan:** The implemented fix touches three
`track-review.md` sites, not the two D1's Plan of Work named: §What You Do
step 6, §Phase A Completion step 2, and §Phase A Resume row 3 plus the
resume-path `<level>` fallback, the last two added during review. See
Decision Log D4. WI3 (a per-call append exit-code check at step 6) was
deliberately not applied: D1 keeps the append prose-instructed for parity
with the four sibling sites, and the ledger-tail verification now on both
completion paths is the backstop.

**Key files:**
- `…/staged-workflow/.claude/workflow/track-review.md` (modified, staged)
- `…/staged-workflow/.claude/scripts/tests/test_workflow_startup_precheck.py` (modified, staged)

**Critical context:** During this branch the fix lives only in the staged
subtree; the live `.claude/**` stays at develop state and self-applies at
the Phase 4 promotion. Verification runs against the staged copies: the
guard passes on the staged (fixed) `track-review.md` and fails on the live
(unfixed) copy, and the acceptance grep `--phase C --track` targets the
staged path while the live path stays empty until promotion.

## Validation and Acceptance

The track is accepted when all three of the issue's acceptance criteria hold:

1. **The A→C boundary is recorded.** After Phase A completes, the phase
   ledger tail reads `phase=C track=<N>`, and a fresh `workflow-startup-precheck.sh
   --mode full` returns `state.phase=C` (with a track-file-driven substate),
   not `state.phase=A`. Verified by the doc-presence guard (D2) plus the
   existing script behavior tests at `test_workflow_startup_precheck.py:3426`
   (the append leaves a `phase=C` line) and `:3447` (the read resolves to
   `phase=C` / `track=1`).
2. **Resume routes to Phase B/C, not a Phase A re-run.** Because the router
   (`workflow.md` §Startup Protocol step 5) already maps `phase == "C"` to the
   mid-track resume with Track Pre-Flight skipped, the recorded boundary alone
   produces the correct routing — no router change is needed. Verified by
   inspection against the live router prose and the script's `C)` case.
3. **A real A→C append site exists outside the test suite.**
   `grep -- '--phase C' .claude/workflow/` is non-empty after the fix, and
   specifically `grep -- '--phase C --track' .claude/workflow/track-review.md`
   is non-empty (the append call in step 6, distinct from D1's verification
   prose).

<!-- Reserved for Move 3 — EARS/Gherkin acceptance lines. Empty until Move 3. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

**In-scope files** (live-path names; the implementer routes each edit to its
staged counterpart under `_workflow/staged-workflow/.claude/` per §1.7(b)):

- `.claude/workflow/track-review.md`: (a) §What You Do **step 6**: the
  `--append-ledger --ctx <level> --phase C --track <N>` call before the
  commit, `phase-ledger.md` added to the `git add`, reusing step 5/6's ctx
  read; (b) §Phase A Completion **step 2**: the ledger-tail verification
  (`phase=C track=<N>`) with its commit-present-but-tail-wrong recovery
  branch.
- `.claude/scripts/tests/test_workflow_startup_precheck.py` (or the nearest
  existing test module): the D2 doc-presence regression guard. The guard
  resolves `track-review.md` via the module's `REPO_ROOT` anchor (the staged
  copy under staging), not `LIVE_REPO_ROOT` — see D2.

**Out-of-scope** (stated so a later reader does not reopen them):

- `workflow-startup-precheck.sh` itself: already supports `--phase C`
  (arg parser line 149, the `C)` resume case line 1781); no script change.
- `workflow.md` §Startup Protocol routing: already correct (`phase == "C"`
  routes to the mid-track Phase B/C resume); no router change.
- The two sibling append sites (`implementation-review.md:646`,
  `track-code-review.md:1403/1405`) and their missing `--ctx`. The new append
  carries an accurate `--ctx <level>` (the context-level read step 5/6 already
  performs) while the siblings keep the script's `safe` default. That
  divergence is **intentional**: do not
  "normalize" the new append back to the no-`--ctx` sibling form, which would
  silently regress the accurate read. Aligning the siblings is a separate
  future workflow-prose pass.
- Other ledger boundaries (State 0→A, track completion, Phase D, Done): not
  touched.

**Inter-track dependencies:** none. This is the single track of a minimal-tier
change; there is no other track to depend on or block.

**Staging path mapping (§1.7(b)):** every live `.claude/**` path above maps to
`docs/adr/state-ledger-fix/_workflow/staged-workflow/.claude/<same relative
path>` during the branch's lifetime; the live files stay at develop state
until the Phase 4 promotion commit copies the staged subtree onto its live
paths.

## Invariants & Constraints

- **The A→C boundary is recorded and reads back as `phase=C`.** After Phase A
  completes, the ledger tail reads `phase=C track=<N>` and a fresh
  `workflow-startup-precheck.sh --mode full` returns `state.phase=C`
  (track-file-driven substate), not `state.phase=A`. Verified by the D2
  doc-presence guard plus the existing script tests at
  `test_workflow_startup_precheck.py:3426/3447`.
- **The append stays inside the single Phase A commit.** The step-6 append is
  committed together with the decomposition, so §Phase A Completion step 2's
  clean-tree check (`git status --porcelain` empty) still holds after step 6
  with no second commit on the normal path. Verified by inspection against
  step 2's existing clean-tree assertion.
- **A real append site exists outside the test suite.**
  `grep -- '--phase C' .claude/workflow/` is non-empty after the fix (the
  issue's acceptance criterion #3); the match is the step-6 append call, not
  the D2 verification prose. Verified by the acceptance grep.
- **Staged under §1.7(b).** Live `.claude/**` stays at develop state until the
  Phase 4 promotion commit; the ledger `s17` field carries the
  workflow-modifying token throughout the branch. Verified by inspection of
  the ledger `s17` tail and the staged-subtree layout.

## Base commit

ae1cd3a7710c77ac02f6b5de505d0bb6aee8d70e
