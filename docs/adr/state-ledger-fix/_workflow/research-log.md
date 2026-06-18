# Research Log — state-ledger-fix

## Initial request

The user directed: "Please read YTDB-1140."

The aim is to fix YTDB-1140 — **"Phase A completion omits the phase=C ledger
append; resume re-runs Phase A."** (Bug / Major / dev-workflow, filed
2026-06-17 against branch `understandable-design` commit `8362111b96`.)

Symptom: on a fresh `/execute-tracks` after a track's Phase A completed,
`workflow-startup-precheck.sh --mode full` reports `state.phase=A` because the
Phase A completion protocol never appended the `phase=C track=N` boundary to
the phase ledger. `workflow.md` §Startup Protocol step 5 then routes to the
pre-Phase-A path (Track Pre-Flight + Phase A review/decomposition) — a full
Phase A re-run, even though ground truth (the track file) shows Phase A done
and Phase B next.

Proposed fix in the issue: add a ledger-advance step to `track-review.md`
§"Phase A Completion — MANDATORY SESSION BOUNDARY" that runs
`workflow-startup-precheck.sh --append-ledger --phase C --track <N>` after the
Phase A commit is verified. The script and its tests already support the
append; only the orchestrator-facing doc instruction is missing.

## Decision Log

### D1: Bundle the A→C ledger append into the existing Phase A commit
[2026-06-18T12:50Z] [ctx=safe] Place the
`workflow-startup-precheck.sh --append-ledger --phase C --track <N>` call in
`track-review.md` §What You Do **step 6**, immediately before that step's
`git commit`, and add `phase-ledger.md` to its `git add`. Reuse the
`<level>` step 6 already read for its D12 `## Progress` write to feed
`--ctx`. Add a ledger-tail check (`phase=C track=<N>`) to §Phase A
Completion step 2's verification, **with its own recovery branch**: when
the Phase A commit is present but the ledger tail is not `phase=C
track=<N>` (a corrupted or hand-edited append-only ledger), re-run the
step-6 append, make a dedicated `git add phase-ledger.md && git commit &&
git push`, then re-verify. This mirrors step 2's existing
missing-commit recovery ("run step 6 now") and keeps the check a true gate.
- **Why:** the append must be committed and pushed. Bundling into step 6
  keeps one atomic Workflow-update commit (decomposition + phase advance
  land together), leaves the tree clean so §Phase A Completion step 2's
  clean-tree check still holds, and reuses the ctx read already present —
  no second statusline read, no second commit. The transition stays a
  prose-instructed orchestrator append (not a new script-driven
  auto-append subcommand) for parity with the four sibling append sites
  (`implementation-review.md:646`, `track-code-review.md:1403/1405`,
  inline-replan, mid-phase-handoff), a single ledger-writer surface, and no
  new script behavior to test; the recovery branch above closes the
  skipped-step robustness gap that a prose step otherwise carries.
- **Alternatives rejected:** (a) the issue's literal placement in §Phase A
  Completion after step 2 — dirties a tree step 2 just verified clean and
  leaves the ledger line uncommitted/unpushed unless a second dedicated
  commit+push is added, breaking the one-commit-per-Phase-A pattern. (b) a
  script-driven `--complete-phase-a` auto-append that removes the
  orchestrator-in-the-loop step entirely — more robust against a skipped
  step, but it diverges from the established prose-instructed sibling
  pattern, adds a new script subcommand and tests, and is broader than this
  bug warrants; the recovery branch gives most of the robustness at the
  cost of one verification clause.

### D2: Add a doc-presence regression guard matching the full invocation
[2026-06-18T12:50Z] [ctx=safe] Add an automated guard asserting the A→C
append **call** is present at its site in `track-review.md`, reproducing the
bug — the absence of the instruction. The guard matches the append **call**
at its site, not a bare `--phase C` substring, and it is **arg-order- and
whitespace-tolerant** — the canonical arg order puts `--ctx` before `--phase`
(script Usage line 120), so the real call reads `--append-ledger --ctx
<level> --phase C --track <N>` and a contiguous
`--append-ledger --phase C --track` match would be a false-negative against
the correct code. Two acceptable forms:
- Preferred — a Python doc-presence test in the existing test module
  asserting the §What You Do step-6 append line carries all of
  `--append-ledger`, `--phase C`, and `--track` (order- and
  whitespace-robust).
- Or a grep anchored on the contiguous `--phase C --track` flag pair, which
  stays adjacent in the canonical single-line call (`--ctx` precedes
  `--phase`, `--track` immediately follows `--phase C`):
  `grep -- '--phase C --track' .claude/workflow/track-review.md` non-empty.
  This requires the step-6 append be written **single-line** so the pair
  stays on one line; co-design the call shape and the guard in Phase B.
- **Why:** CLAUDE.md requires a regression test for every bug fix. The
  script behavior (`--phase C` append + the resulting `phase=C` read) is
  already covered by `test_workflow_startup_precheck.py:3426/3447`; the gap
  the bug exposed is the missing **doc instruction**, so the guard checks
  the doc, not the script. A bare `grep -- '--phase C'` would self-satisfy
  from the wrong location — D1's own step-2 verification prose now contains
  the literal `phase=C track=<N>`, so the bare grep would pass even if the
  real append call in step 6 were deleted. Matching the full
  `--append-ledger --phase C --track` invocation pins the call site and the
  executable form, so the guard fails when the actual append is dropped.
- **Alternatives rejected:** (a) a bare `grep -- '--phase C'` presence check
  — a substring match that the verification prose alone satisfies, so it
  guards the appearance of a string, not the instruction. (b) rely only on
  the existing script tests plus a one-time manual grep at acceptance —
  leaves no standing guard against the instruction being dropped again.

### D3: minimal tier, §1.7(b) staging (not the §1.7(k) opt-out)
[2026-06-18T12:50Z] [ctx=safe] Tier = `minimal` (single track, no design,
no plan). Stage the `.claude/**` edits under
`_workflow/staged-workflow/.claude/` per §1.7(b); seed the ledger `s17`
field with the workflow-modifying token; promote at Phase 4. No matched
HIGH-risk category is central, so the adversarial gate runs lens-free.
- **Why:** the change edits `track-review.md` — orchestrator procedure a
  running phase **executes** (a bash `--append-ledger` step). §1.7(k)
  criterion (2) excludes execution-procedure files from the opt-out, so
  staging applies. Staging keeps live workflow at develop state while this
  branch runs its own Phase A, so the fix does not alter the branch's own
  execution mid-flight.
- **Alternatives rejected:** the §1.7(k) prose-rule opt-out (edit live).
  The edited file is executed machinery, not judgment-layer prose, so it
  fails the opt-out's consumer-class test; opting out would also change
  this branch's own Phase A completion behavior mid-branch.
- **Accepted self-application wrinkle:** staging means the orchestrator
  running *this* branch's own Phase A reads the **live** (develop-state)
  `track-review.md`, which lacks the fix — so this branch self-inflicts the
  bug. Expect a stale `phase=A` on resume of this branch and append
  `phase=C track=1` by hand to route the precheck to Phase B/C, exactly as
  the originating branch `understandable-design` did (per the MEMORY note).
  The fix only self-applies after the Phase 4 promotion. This is the price
  of staging and is accepted.

## Surprises & Discoveries

- [2026-06-18T12:37Z] [ctx=safe] Issue's central claim verified on this branch.
  `grep -rn -- '--phase C' .claude/workflow/ .claude/scripts/` returns nothing:
  no workflow doc ever appends the A→C boundary. The documented append sites are
  State 0→A (`implementation-review.md:646`, `--phase A`) and track completion
  (`track-code-review.md:1403/1405`, `--track N+1` / `--phase D`). The A→C
  transition has no append site, so the ledger stays at `phase=A` after Phase A.
- [2026-06-18T12:37Z] [ctx=safe] Script + tests already support the append. The
  arg parser accepts `--phase` (line 149); `determine_state_from_ledger` has a
  `C)` case (line 1781) emitting `{phase:"C", substate:<track-driven>}`. Tests
  exercise `--append-ledger --phase C --track 1` and the resulting read
  (`test_workflow_startup_precheck.py:3426, 3447`). So the fix is doc-only.
- [2026-06-18T12:37Z] [ctx=safe] `phase=A` routes to a Phase A RE-RUN.
  `workflow.md` §Startup Protocol step 5: `phase == "A"` → Track Pre-Flight +
  Phase A (it reads `phase=A` as "track has no track file yet, pre-Phase-A");
  `phase == "C"` → mid-track Phase B/C, Pre-Flight skipped. So the stale
  `phase=A` sends the next session through Pre-Flight panels + 3 review
  sub-agents + decomposition again, possibly re-overwriting the roster.
- [2026-06-18T12:37Z] [ctx=safe] Commit/dirty-tree wrinkle the issue understates.
  The Phase A commit is created in `track-review.md` §What You Do step 6
  (stages only `track-N.md`, commits "Phase A review and decomposition for
  <track>", pushes). §Phase A Completion step 2 then verifies that commit landed
  and the tree is clean. An append placed in §Phase A Completion AFTER step 2
  (the issue's literal proposal) would dirty the just-verified-clean tree and
  leave the ledger line uncommitted/unpushed — breaking the per-session
  commit-and-push invariant.
- [2026-06-18T12:37Z] [ctx=safe] §What You Do step 6 already does a D12
  statusline read-then-write for the `## Progress` entry (reads `level=`, falls
  back to `unknown`, captures ISO). That same `<level>` can feed the ledger
  `--ctx` with no second read — a synergy favoring placement inside step 6.
- [2026-06-18T12:37Z] [ctx=safe] `--ctx` defaults to `safe` when omitted (script
  lines 105-106, 1576: `ctx="${LEDGER_CTX:-safe}"`). The two sibling append
  snippets in the docs (State 0→A, track completion) omit `--ctx`, relying on
  that default — inconsistent with D12's mandated accurate read, but pre-existing
  and out of this fix's scope. Consequence of D1: the new step-6 append carries
  `--ctx <level>` (D12-accurate) while the two siblings keep the `safe` default.
  This divergence is intentional — do NOT "normalize" the new append back to the
  no-`--ctx` sibling form, which would silently regress the accurate read. The
  siblings are a candidate cleanup for a future workflow-prose pass (out of scope
  here).

## Open Questions

- [2026-06-18T12:50Z] [ctx=safe] All three Phase-0 open questions (placement,
  regression guard, tier + staging) were resolved by user confirmation and
  promoted to Decision Log D1, D2, D3 respectively. None remain open.

## Baseline and re-validation

This is a workflow-modifying branch (it edits `.claude/workflow/track-review.md`,
live-executed orchestrator procedure). Fork point: branch sits at develop tip
`5de2481272` with zero commits ahead (clean fork, drift check clean, no
upstream set yet). Baseline = `5de2481272`; re-validate the A→C routing claim
against any develop rebase before Phase 4.

## Adversarial gate record

### Adversarial review of this log (2026-06-18T12:51Z) — NEEDS REVISION: 0 blocker, 3 should-fix, 2 suggestion
See `reviews/research-log-adversarial-iter1.md`. A1 (D1 step-2 recovery
branch), A2 (D2 full-invocation guard), A3 (D3 self-application wrinkle) all
should-fix; A4/A5 suggestions. All addressed in D1/D2/D3 and Surprises entry 6.

### Adversarial review of this log (2026-06-18T12:53Z) — NEEDS REVISION: 0 blocker, 1 should-fix
See `reviews/research-log-adversarial-iter2.md` (verdict-producer). A1–A5 all
VERIFIED. New A6 (should-fix): D2's tightened guard and the D1 `--ctx`
addition interact — canonical arg order is `--ctx` before `--phase`, so a
contiguous `--append-ledger --phase C --track` match is a false-negative.
Addressed in D2 (order-/whitespace-tolerant guard; preferred Python
doc-presence test).

### Adversarial review of this log (2026-06-18T12:58Z) — PASS
A6 addressed by inspection (mechanical guard-form fix verified against script
Usage line 120; guard finalized in Phase B). No blocker across any iteration;
all should-fix (A1–A3, A6) resolved, suggestions (A4–A5) folded. Gate clears.
