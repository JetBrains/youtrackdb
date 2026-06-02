<!-- workflow-sha: f97512c02f4dbaaf66c7382397907580fd54391b -->
# Track 4: Review-target delta-scoping for staged copies (YTDB-1038)

## Purpose / Big Picture
After this track, when a track first-creates a staged copy inside a reviewed range, the Phase C track reviewers and the high-risk Phase B step reviewers get a `diff <live> <staged>` delta and scope findings to the real change rather than re-reviewing already-promoted content.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

On a workflow-modifying plan a track's deliverable is a staged copy under
`_workflow/staged-workflow/.claude/...`. When that copy is first created in a
reviewed commit range the cumulative diff shows it as a whole-file add, even
though it is a copy of an already-live, already-reviewed file plus a small
edit. The orchestrator pre-stages the delta against the live counterpart and
the reviewer context block scopes findings to it.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-02T08:00Z [ctx=safe] Review + decomposition complete
- [x] 2026-06-02T08:20Z [ctx=safe] Step 1 complete (commit b99294b83135ae6c9ddad957fa53d23e93926fd9)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- Track 4's own Phase C does not trigger D5 delta pre-staging. Both files it
  edits (`track-code-review.md`, `step-implementation.md`) were first-created
  as staged copies by Track 2, so Track 4's own `base..HEAD` shows ordinary
  edits, not whole-file adds. Phase C self-application still needs staged-path
  normalization, staged-read precedence, and the prose-criteria lens, but not
  delta pre-staging. See Episodes §Step 1.
- S2 baseline for any future edit to the two delta context blocks: each now
  carries `read caveat → delta note` in that order; the delta note is
  byte-identical across both (md5 `b4fb405f`) modulo fence indentation and the
  per-step versus per-track delta-path token. See Episodes §Step 1.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

- Decomposed to one step, not the ~2-3 the scope indicator estimated. The
  scope note must read byte-uniformly across the two parallel context blocks
  (S2), which is guaranteed by authoring it once and placing it in both
  blocks in a single commit, mirroring Track 3's grouping of its byte-uniform
  addendum across three prompts and Track 2's caveat across nine. The two
  delta-staging procedures differ per file (cumulative `base..HEAD` diff in
  the Phase C setup vs per-step `commit~1..commit` diff in Phase B sub-step
  4(a)), but both ride in the same commit. The scope indicator is a
  non-binding estimate.
- Technical review (iteration 1, PASS; Simple track, Technical only) folded
  two suggestions into the step description. T1: pin the sub-step 4(a) edit to
  the step-review reviewer `## Workflow Context` block, not the
  implementer-template `## Workflow Context (static …)` block elsewhere in the
  same file. T2: express each delta-staging step as a concrete `bash` fence
  (`--diff-filter=A` + staged-prefix strip + live-file existence test)
  mirroring the existing staging fences, not prose. Both are decomposition-time
  wording requirements, not plan changes.
- Dependency reveal (affects Phase C self-application). Track 4 edits the
  staged copies of `track-code-review.md` and `step-implementation.md` that
  Track 2 first-created (commit `704d847fa6`), so Track 4's own edits are
  ordinary diffs, not whole-file adds. Track 4's own D5 delta-scoping
  therefore does not fire on its own Phase C review: the cumulative track diff
  already shows only Track 4's delta. The `§1.7(h)` hand-injection for Track
  4's Phase C needs staged-path normalization, staged-read precedence, and the
  prose-criteria lens, but not delta pre-staging.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 1 (2 findings, both suggestions; 0
  plan/track edits — both folded into decomposition guidance). T1: pin the
  sub-step 4(a) `## Workflow Context` edit target unambiguously, since
  `step-implementation.md` also carries an implementer-template
  `## Workflow Context (static …)` block at a different sub-step. T2: draft
  the delta-staging step as a concrete `bash` fence (`--diff-filter=A` +
  staged-prefix strip + live-file existence test) mirroring the existing
  staging blocks, not prose. Self-application carve-out (`§1.7(h)`)
  hand-injected: the reviewer ran against the staged technical-review prompt
  (Track 2 caveat + Track 3 addendum) and read the staged copies of the two
  edited files.

## Context and Orientation

`track-code-review.md §Phase C Startup` step 7 ("Pre-stage the cumulative diff
and changed-files list") writes the cumulative track diff to a temp file, and
`§Context passed to all sub-agents` (plus its backing `§Pre-staged diff and
changed-files list`) points every track reviewer at that diff by path. Neither
gives a freshly-created staged copy any special handling: the reviewer sees a
whole-file add.

`step-implementation.md §on_success(step, result)` sub-step 4(a) is the
high-risk step-review setup. Its "pre-stage the step diff and the
changed-files list" block writes the per-step diff to a temp file, and the
canonical `## Workflow Context` block in the same sub-step points the
step reviewers at it. Same blind spot: a staged copy first created in that
step's commit reads as a whole-file add.

On a workflow-modifying plan the changed file is often a staged copy under
`…/_workflow/staged-workflow/.claude/…`. When the track first creates it, the
whole-file add masks the real target: only the delta against the live
counterpart is the change worth review. Reviewers handed the whole-file add
spend effort on already-promoted content and risk phantom findings or scope
creep into the live machinery.

The two context blocks are parallel copies, not a shared include (S2): the
sub-step 4(a) block in `step-implementation.md` and the
`§Context passed to all sub-agents` block in `track-code-review.md` must carry
the delta note with matching meaning, or a Phase C review behaves differently
from its Phase B counterpart.

Concrete deliverables: a delta-staging step in each of the two orchestrator
setups, plus a matching scope note in each of the two context blocks.

## Plan of Work

The approach is orchestrator pre-staging (D5). In the Phase C diff-staging
step and the high-risk Phase B step-review setup, the orchestrator detects a
changed file that is a freshly-created staged copy (it matches the anchored
`…/_workflow/staged-workflow/.claude/…` prefix, is a new-file add in the
reviewed range, and has a live counterpart) and additionally stages a
`diff <live> <staged>` delta file. The context block points reviewers at that
delta with the note: scope findings to this delta; the rest is verbatim-copied
live content.

1. Add the delta-staging step to `track-code-review.md §Phase C Startup`
   (alongside step 7's cumulative-diff staging) and carry the scope note in
   `§Context passed to all sub-agents`.
2. Add the delta-staging step to `step-implementation.md` sub-step 4(a)
   (alongside its step-diff staging) and carry the scope note in that
   sub-step's `## Workflow Context` block.
3. Verify the scope note reads the same in both context blocks (S2).

Ordering and invariants:
- This track lands after Track 2. Both edit the two context blocks; Track 4
  layers its delta note onto Track 2's read caveat, so sequencing Track 4
  second avoids a staged-copy conflict on the same two blocks.
- The S2 parallel-block invariant, extended by this replan, now covers the
  delta note alongside the read caveat: the note must land in both the
  step-implementation sub-step 4(a) block and the track-code-review block with
  matching meaning.
- The trigger is precise: it fires only on the first creation of a staged copy
  (a new-file add). A later edit to an already-restaged file is an ordinary
  diff, not a whole-file add, so no delta is staged. Like the selection
  normalization, the trigger keys off the staged prefix and needs no marker —
  staged paths exist only on plans that carry the `§1.7(b)` marker anyway.

## Concrete Steps

1. Add the `diff <live> <staged>` delta-staging step and the reviewer scope note to both parallel review setups in one commit. In `track-code-review.md` (staged copy): add the delta-staging step to `§Phase C Startup` alongside step 7's cumulative-diff staging, and add the scope note to the `§Context passed to all sub-agents` block. In `step-implementation.md` (staged copy): add the delta-staging step to sub-step 4(a) alongside its per-step diff staging, and add the scope note to that sub-step's step-review reviewer `## Workflow Context` block, the one inside sub-step 4(a) — NOT the implementer-prompt-template `## Workflow Context (static — same on every spawn this session)` block at a different sub-step (finding T1). Express each delta-staging step as a concrete `bash` fence mirroring the existing staging blocks: enumerate new-file adds under the anchored `…/_workflow/staged-workflow/.claude/…` prefix via `git diff … --diff-filter=A --name-only`, strip the staged prefix to derive each live counterpart, test the live file exists, then write `diff <live> <staged>` to a delta temp file (finding T2). The scope note directs reviewers to scope findings to the delta and treat the rest as verbatim-copied live content; it is byte-uniform across the two context blocks (S2), modulo the deeper code-fence indentation in `step-implementation.md`. The delta rides in the two context blocks only, not the gate-check prompts `dimensional-review-gate-check.md` or `review-gate-verification.md` (Non-Goal). Verify S2 byte-uniformity of the scope note across the two blocks intra-step. — risk: low (default: additive workflow-doc prose to two already-staged copies under `_workflow/staged-workflow/`; no Java, concurrency, durability, public-API, or build trigger; S2-checkable)  [x]  commit: b99294b83135ae6c9ddad957fa53d23e93926fd9

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit b99294b83135ae6c9ddad957fa53d23e93926fd9, 2026-06-02T08:20Z [ctx=safe]
**What was done:** Added a `diff <live> <staged>` delta-staging step plus a
matching reviewer scope note to both parallel review setups in one commit,
editing the staged copies under `_workflow/staged-workflow/`. In
`track-code-review.md` the delta step is a new step 8 in `§Phase C Startup`
beside step 7's cumulative-diff staging (cumulative `{base_commit}..HEAD`
range), and its scope note sits in the `§Context passed to all sub-agents`
block right after Track 2's read caveat. In `step-implementation.md` the delta
step rides in sub-step 4(a) beside the per-step `{commit}~1..{commit}` staging,
and its scope note sits in the sub-step 4(a) step-review `## Workflow Context`
block, not the static implementer-template block (finding T1). Each delta step
is a `bash` fence mirroring the existing staging blocks: `git diff …
--diff-filter=A --name-only` over the anchored
`…/_workflow/staged-workflow/.claude/…` prefix, a prefix-strip to the live
counterpart, a live-file existence test, then `diff` to a `$PPID`-suffixed
delta temp file (finding T2). The note rides in the two context blocks only,
not the gate-check prompts (Non-Goal).

**What was discovered:** Running the new loop against this branch's own
`origin/develop..HEAD` range as a functional test found all twelve staged
whole-file adds, derived each live counterpart, confirmed existence, and
emitted per-file `diff <live> <staged>`. The run reconfirmed the Phase A
dependency reveal: the two files this step edits are whole-file adds across the
full cumulative range but were first-created by Track 2, so within Track 4's
own `base..HEAD` they are ordinary edits. Track 4's own D5 delta-scoping does
not fire on its own Phase C review; Phase C still needs the `§1.7(h)`
hand-injection (staged-path normalization, staged-read precedence,
prose-criteria lens) but not delta pre-staging.

**What changed from the plan:** None. The scope note is byte-uniform across
both blocks (md5 `b4fb405f` after normalizing leading whitespace) modulo two
forced differences the step description named only in part: the deeper
code-fence indentation in `step-implementation.md`, and the per-step
(`claude-code-step-{N}-{M}-delta`) versus per-track
(`claude-code-track-{N}-delta`) delta-path token. The token must differ
because the two delta files are genuinely distinct, matching how the sibling
`## Diff` and `## Changed Files` sub-sections already differ by the same
step/track token.

**Key files:**
- `docs/adr/ytdb-1038-review-gate-for-workflow/_workflow/staged-workflow/.claude/workflow/track-code-review.md` (modified)
- `docs/adr/ytdb-1038-review-gate-for-workflow/_workflow/staged-workflow/.claude/workflow/step-implementation.md` (modified)

## Validation and Acceptance

Track-level behavioral acceptance:
- On a workflow-modifying plan, when a reviewed range first-creates a staged
  copy, the orchestrator stages a `diff <live> <staged>` delta and the reviewer
  context block scopes findings to that delta.
- An ordinary edit to an already-restaged file (not a whole-file add) stages
  no delta; the reviewer sees the ordinary diff unchanged.
- The delta-scoping note reads the same in both context blocks — the
  step-implementation sub-step 4(a) `## Workflow Context` block and the
  track-code-review `§Context passed to all sub-agents` block (S2).
- The delta rides in the two parallel context blocks only, not the gate-check
  prompts (`dimensional-review-gate-check.md`, `review-gate-verification.md`).
- Reviewer-side self-diffing was not chosen: pre-staging is deterministic
  across the fan-out (D5).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

The single step is one commit of additive workflow-doc prose (a delta-staging
`bash` block and a scope note) to two staged copies under
`_workflow/staged-workflow/.claude/workflow/` (the live tree is untouched
until Phase 4). Recovery is the standard Phase B revert: `git reset --hard
HEAD` discards the uncommitted attempt, and re-running re-applies the same
edits, so the step is idempotent on re-run.

Per-step note:
- **Step 1** edits the two staged copies in place — Track 2 already created
  both staged copies (with the read caveat) on its S2 step, so the `§1.7(e)`
  copy-then-edit-on-first-touch step does not re-fire here. Before inserting
  the delta-staging block and scope note, check for an existing block to stay
  idempotent. The S2 byte-uniformity cross-check is a read-only `diff`/`grep`
  over the two context blocks and has no side effect.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

In-scope files (all under `.claude/workflow/**`):
- `track-code-review.md` — the Phase C diff-staging step (`§Phase C Startup`
  step 7) and the `§Context passed to all sub-agents` block.
- `step-implementation.md` — sub-step 4(a), the high-risk Phase B step-review
  setup: its step-diff staging block and `## Workflow Context` context block.

Out-of-scope:
- `dimensional-review-gate-check.md` and `review-gate-verification.md` — the
  gate-checks re-check prior findings rather than scoping a fresh diff, so they
  take the Track 2 read caveat only, no delta note.
- Reviewer-side self-diffing — rejected alternative (D5).

Inter-track dependencies:
- **Depends on Track 2.** Both tracks edit the two context blocks; Track 2's
  read caveat lands first, and Track 4 layers the delta note onto it.

Staging: per `§1.7`, the two edits route through
`docs/adr/<dir>/_workflow/staged-workflow/.claude/workflow/...`; the live files
stay at develop's state until Phase 4 promotion.

Self-application (`§1.7(h)`): Track 4 edits the live review machinery this
branch stages, so its own Phase A and Phase C reviews run against the unfixed
live rules. The orchestrator hand-injects the staging and delta-scoping
guidance during this branch's execution — the same manual step the fix removes
for later plans.

Full design: design.md §"Read-side staging awareness".

## Base commit

33d3fdf7af3116b8b7ba891a13a32134b7691865
