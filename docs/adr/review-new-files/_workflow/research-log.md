# Research Log — Fix YTDB-1179

## Initial request

Fix YTDB-1179: "Phase C delta-staging omits genuinely-new staged files from
review scope" (Bug, medium, phase-c, tag `dev-workflow`).

On a workflow-modifying (§1.7-staged) track, `track-code-review.md` Phase C
Startup step 8 builds the review-target delta with `diff <live> <staged>` per
freshly-staged file, inside `if [ -f "$live" ]` with no else branch. A
genuinely-new staged file (no live counterpart — a new agent/skill/prompt/doc)
produces no delta entry, and the "Review-target delta for freshly-created
staged copies" context block then tells reviewers the rest of each whole-file
add is verbatim-copied, already-reviewed, out of scope. Read literally, a
brand-new staged file gets marked out-of-scope and ships unreviewed while the
machinery reports a clean pass.

Issue's proposed fix (two-part):
1. Add an `else` branch to step 8's loop: a staged file with no live
   counterpart appends a `=== NEW staged file (no live counterpart): <path> ===`
   marker to the delta file.
2. Add one sentence to the context block: a file under a NEW marker has no
   already-reviewed live baseline and must be reviewed in full, distinct from
   the delta-scoped (copy-of-live) case.

Acceptance criteria:
- step 8 loop emits a NEW-file marker for staged files with no live counterpart;
- the context block distinguishes NEW (review-in-full) from delta-scoped
  (copy-of-live) staged files;
- a workflow-modifying track that adds a new `.claude/**` file no longer relies
  on the orchestrator hand-listing it for reviewers.

## Decision Log

### D1: Fix both files, all four defect locations per file — 2026-07-01T15:05Z; revised 15:19Z per iter1 gate A1/A2 [ctx=safe]
The issue names `track-code-review.md` Phase C step 8. Research found a
byte-identical copy of the same defect in `step-implementation.md` (Phase B
step-level review, fires on `high` steps). Fix **both files**. The iter1
adversarial gate (A1, blocker) established the defect logic is not confined to
the bash loop and the context block: the "only when a live counterpart exists"
scoping is stated in **four** locations per file, all of which must move
together or the prose contradicts the code:
1. **Preamble prose** — "…and when the live file exists write `diff <live>
   <staged>`" (track-code-review.md ~271, step-implementation.md ~486). Must
   also state the no-live-counterpart case emits a NEW-file marker.
2. **The bash loop** — add an `else` branch to `if [ -f "$live" ]` emitting
   `=== NEW staged file (no live counterpart): <staged> ===`
   (track-code-review.md ~283-289, step-implementation.md ~498-504).
3. **Post-loop narration** — "…it fires only on a new-file add … that has a
   live counterpart" (track-code-review.md ~293, step-implementation.md ~508).
   The "that has a live counterpart" restriction is wrong post-fix: the loop
   now records every add under the staged prefix, as a delta when a live
   counterpart exists and as a NEW marker when it does not.
4. **The context block** — see the A2 rewrite requirement folded below.
That is eight edit points total (four per file), one logical fix.

**A2 (should-fix) folded in — the context block is a REWRITE, not an append.**
The current block says "When that file is non-empty, scope your findings to
the delta: the rest of each whole-file add is verbatim-copied … out of scope."
A NEW-file marker makes the delta file non-empty, so an appended note would
still sit under that blanket "out of scope" sentence and a NEW file would read
as out-of-scope. Rewrite the block so the delta-scoped and NEW cases are a
**per-entry, mutually exclusive** distinction: a file under a `=== delta: … ===`
marker is scoped to its delta (rest of the whole-file add is verbatim copy,
out of scope); a file under a `=== NEW staged file … ===` marker has no
already-reviewed live baseline and must be reviewed in full.
- **Why:** The defect is one logical bug (missing no-live-counterpart handling
  plus the "out of scope" instruction), duplicated near-verbatim across the
  two files and, within each file, spread across preamble + loop + post-loop +
  context block. Any subset edit leaves a prose/code contradiction a
  consistency review flags, or leaves the Phase B copy silently under-covering.
- **Alternatives rejected:** (a) Fix only the loop + context block as D1
  originally scoped, or only `track-code-review.md` as the issue literally
  scopes — rejected: leaves contradicting preamble/post-loop prose and the
  defective Phase B copy (iter1 A1). (b) De-duplicate the loop and context
  block into a shared include — rejected: workflow docs are standalone
  Markdown read independently per phase; there is no include mechanism, and
  introducing one is a far larger refactor than this bug warrants. Mirror the
  existing intentional duplication instead.
- **Scope confirmed closed:** research (grep over `.claude/**` for the delta
  temp path and the "freshly-created staged" prose) found the loop/context
  block in exactly these two files; `conventions.md` §1.7(k) only references
  the concept (A3). No third copy to miss.

### D2: The NEW-file marker emits the staged path — 2026-07-01T15:05Z [ctx=safe]
The else-branch marker is `=== NEW staged file (no live counterpart): <staged> ===`,
naming the staged path (`docs/adr/<dir>/_workflow/staged-workflow/.claude/…`).
- **Why:** The reviewer locates the file in the cumulative/step diff, which
  shows it under its staged path (a whole-file add). The staged path is the
  diff locator the reviewer needs; the derived live path is that path minus a
  fixed prefix, so it adds no locating power.
- **Alternatives rejected:** emit the derived live path (semantic identity but
  not where it appears in the diff); emit both (redundant — the live path is a
  deterministic prefix-strip of the staged path).

### D3 (leaning, confirmed at Step 4): §1.7(k) prose-rule opt-out — 2026-07-01T15:05Z [ctx=safe]
Take the §1.7(k) judgment-layer opt-out; edit the two workflow files live
rather than staging. Single-track, design_gate=no, minimal.
- **Why:** The change is confined to judgment-layer workflow prose (an else
  branch in a bash snippet plus one clarifying sentence in a context block).
  This branch adds no new `.claude/**` files, so the bug it fixes cannot even
  trigger on its own review; the opt-out disables the staged-delta prep that
  the fix touches, so there is no self-referential hazard. Same shape as the
  prior prose-fix branches that used minimal + opt-out.
- **Alternatives rejected:** full §1.7 staging — rejected: staging machinery
  overhead is unwarranted for a two-file prose fix with no new-file adds and
  no self-application risk.

## Surprises & Discoveries

- 2026-07-01T15:05Z [ctx=safe] — The same defect (missing else + "out of
  scope" instruction) exists in **two** files, not one: `track-code-review.md`
  Phase C step 8 loop (283-289) + context block (454-465), and
  `step-implementation.md` Phase B step-level review loop (498-504) + context
  block (610-621). The loops and blocks are near-verbatim copies of each other
  (the "canonical context block"). The issue only names Phase C.
- 2026-07-01T15:05Z [ctx=safe] — `conventions.md` §1.7(k) (line 1346)
  references "the Phase C Startup staged-delta prep in track-code-review.md
  step 8" as one of the things the opt-out disables. It is a pointer, not a
  third copy of the loop. No fix needed there; but the fix's step-8 wording
  should stay consistent with how §1.7(k) names it.
- 2026-07-01T15:05Z [ctx=safe] — The loop already enumerates the new file (it
  is an `--diff-filter=A` add under the staged prefix); the file simply falls
  through the `if [ -f "$live" ]` with no else. So the else branch is the
  complete loop-side fix — no change to the enumeration is needed.

## Open Questions

- (resolved) Marker path form → D2 (staged path).
- (for the Step 4 gate) Confirm §1.7(k) opt-out and design_gate=no with the
  user → D3.

## Baseline and re-validation

Branch `review-new-files` is freshly cut from `develop` (`develop` is an
ancestor of HEAD; last workflow commit touching `track-code-review.md` is
`03eac656fa`). Both defect sites are present in the current on-disk state,
matching the issue's description. No rebase performed yet. Under the D3
§1.7(k) opt-out the fix edits live workflow files, so re-validation against
develop-drift is the standard drift gate at each session start (already clean
this session).

## Adversarial gate record

### Adversarial review of this log (2026-07-01T15:14Z) — NEEDS REVISION: 1 blocker, 1 should-fix
See `_workflow/reviews/research-log-adversarial-iter1.md`. A1 (blocker): D1's
fix scope was too narrow — the "only when a live counterpart exists" logic is
also in the preamble prose and post-loop narration in each file; D1 revised to
enumerate all four locations per file. A2 (should-fix): the context block must
be rewritten to a per-entry delta-vs-NEW distinction, not an appended note;
folded into D1. A3/A4 recorded as suggestions (D2/D3 hold): A3 — the §1.7(k)
opt-out disables the step-8 staged-delta prep on this branch, so the fix ships
without in-workflow self-validation (manual coherence trace stands in); A4 —
D2's staged-path marker survives (it is the diff locator).

### Adversarial review of this log (2026-07-01T15:22Z) — PASS
See `_workflow/reviews/research-log-adversarial-iter2.md`. Verdict-producer
re-review: A1 and A2 addressed by the D1 rewrite (all four per-file locations
enumerated; context block specified as a per-entry rewrite). A3/A4 remain
recorded non-gating suggestions. No new findings. Gate clears; ready for
Phase-1 artifact derivation.
