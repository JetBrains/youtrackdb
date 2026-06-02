<!-- workflow-sha: f97512c02f4dbaaf66c7382397907580fd54391b -->
# Track 3: Phase A criteria addendum (YTDB-1046)

## Purpose / Big Picture
After this track, the Phase A technical, risk, and adversarial reviewers stop raising phantom `NOT FOUND` blockers on a workflow-machinery track and instead verify named references as file paths and `§`-anchors while applying prose-soundness criteria.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

The Phase A technical, risk, and adversarial reviewers apply Java criteria
that misfire on a prose track and raise phantom `NOT FOUND` blockers. A
marker-gated addendum re-points the criteria to prose; the same three
reviewers self-adapt.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review
- [x] Track completion
- [x] 2026-06-02T07:05Z [ctx=safe] Review + decomposition complete
- [x] 2026-06-02T07:17Z [ctx=safe] Step 1 complete (commit 9a671fe)
- [x] 2026-06-02T07:36Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)
- [x] 2026-06-02T07:39Z [ctx=safe] Track complete

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- 2026-06-02T07:17Z Step 1 discovered: the Phase A addendum (md5 d38747dc) and
  Track 2's read caveat now form a contiguous staged-only block in the three
  criteria prompts, with no live counterpart until Phase 4; Track 4/D5
  delta-scoping and Phase C's S3 cross-check should treat both as one
  new-region staged addition. See Episodes §Step 1.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

- Decomposed to one step, not the ~2 the scope indicator estimated: the
  addendum is a single byte-uniform block (S3) added to three Phase A
  criteria prompts in one coherent commit, and S3 verification is intra-step
  validation rather than a separate commit — mirroring Track 2's grouping of
  its byte-uniform caveat across four Phase A prompts into one step. The
  scope indicator is a non-binding estimate.
- Technical review (iteration 1, PASS) folded two suggestions into the step's
  addendum-wording guidance: T1 — the prose criteria supersede (not merely
  append to) the Java criteria when the marker is present; T2 — phrase the
  path/anchor re-point as additive-for-prose so a track mixing prose and code
  keeps the both-lenses behaviour the design edge case promises. Both are
  decomposition-time wording requirements, not plan changes. See Episodes
  once Phase B lands the step.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 1 (3 findings; T1/T2 suggestions folded
  into the decomposed step's addendum-wording guidance, T3 informational, no
  action). Simple track (Technical only, no Risk/Adversarial). Reviewer
  hand-injected per `§1.7(h)`: workflow-machinery prose criteria (verify
  refs as paths/`§`-anchors, not `findClass`) and staged-read precedence
  (read the staged copies of the three criteria prompts, which already carry
  Track 2's caveat). Verified the dependency on Track 2 is satisfied and the
  Plan of Work reproduces the design's five prose criteria faithfully.

## Context and Orientation

`track-review.md §Complexity Assessment` selects which Phase A reviews run by
step count and code cues, with no workflow branch. A workflow-machinery track
therefore gets the Java reviewers unchanged.

On such a track the names in the track file are workflow docs and `§`-anchors,
not Java FQNs:
- The technical reviewer's rule to verify every named class via `findClass`
  has no valid target and raises phantom `NOT FOUND` blockers.
- The WAL, crash, migration, and hot-caller criteria have nothing to bind to.

The three criteria reviewers in scope are `technical-review.md`,
`risk-review.md`, and `adversarial-review.md`. `review-gate-verification.md`
re-checks prior findings rather than generating criteria, so it is
criteria-agnostic and is out of scope here — it takes the Track 2 read caveat
alone, no addendum.

Concrete deliverables: one marker-gated addendum block in each of the three
criteria reviewer prompts.

## Plan of Work

The approach is one addendum block per criteria reviewer, gated on the same
`§1.7(b)` marker the read caveat uses (D4), byte-uniform across the three
(S3).

1. Add the addendum to `technical-review.md`, `risk-review.md`, and
   `adversarial-review.md`, gated on the marker. The addendum re-points the
   criteria:
   - verify named references as file paths and `§`-anchors with grep and Read,
     not as Java FQNs via `findClass`, so a missing target is no longer a
     phantom blocker;
   - replace WAL, crash, migration, and hot-caller concerns with rule
     coherence and non-contradiction, instruction completeness, prompt-design
     soundness, context-budget impact, and breakage of dependent prompts or
     agents.
2. Verify uniformity (S3): the addendum reads the same across the three
   criteria prompts.

Ordering and invariants:
- The same three reviewers still run; they read the marker and switch
  criteria, mirroring how the read caveat self-gates (D4). The
  complexity-assessment dispatch is untouched, so a track mixing prose and
  code gets one reviewer applying both lenses.
- This track lands after Track 2 because the addendum references the read
  caveat in the same three files; the caveat and the addendum cooperate (the
  caveat points the reviewer at the staged copy, the addendum tells it to
  verify that copy's `§`-anchors as paths).

## Concrete Steps

1. Author the marker-gated workflow-machinery criteria addendum block and add it byte-identically to the three Phase A criteria prompts (`technical-review.md`, `risk-review.md`, `adversarial-review.md` under `.claude/workflow/prompts/`, staged copies), gated on the canonical `§1.7(b)` workflow-modifying marker sentence (the same gate clause as the Track 2 read caveat: "carries the canonical `§1.7(b)` workflow-modifying marker sentence"). Per D4 the addendum re-points the criteria when the marker is present: (a) verify named references as workflow file paths and `§`-anchors via grep + Read — a non-resolving workflow path or anchor is the finding, while a non-resolving Java symbol is NOT a blocker on a prose reference, so a track mixing prose and code keeps both lenses (design edge case; finding T2); (b) the five prose criteria (rule coherence and non-contradiction, instruction completeness, prompt-design soundness, context-budget impact, breakage of dependent prompts or agents) supersede — not merely append to — the prompt's Java EDGE-CASES / INTEGRATION-POINTS / BACKWARD-COMPAT / `findClass` NAMED-REFERENCES criteria for that review (finding T1). Leave `review-gate-verification.md` addendum-free (criteria-agnostic; out of scope). Verify S3 byte-uniformity of the addendum across the three prompts. — risk: low (default: additive marker-gated prose addendum mirrored byte-identically across three Phase A criteria prompts; established Track 2 caveat pattern; staged under `_workflow/staged-workflow/`; S3-checkable)  [x] commit: 9a671fe

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit 9a671fe, 2026-06-02T07:17Z [ctx=safe]
**What was done:** Added a marker-gated workflow-machinery criteria addendum
byte-identically (md5 d38747dc) to the three staged Phase A criteria prompts
(`technical-review.md`, `risk-review.md`, `adversarial-review.md` under
`_workflow/staged-workflow/.claude/workflow/prompts/`). The block sits
immediately after Track 2's staged-read caveat in each file and gates on the
same canonical `§1.7(b)` workflow-modifying marker sentence. When the marker
is present it re-points the Phase A criteria: (a) verify named references as
workflow file paths and `§`-anchors via grep and Read rather than as Java FQNs
via `findClass`, where a non-resolving workflow path or anchor is the finding
and a non-resolving Java symbol is not a blocker on a prose reference, so a
track mixing prose and code keeps both lenses (T2); (b) five prose criteria
(rule coherence and non-contradiction, instruction completeness, prompt-design
soundness, context-budget impact, dependent-prompt or agent breakage) supersede
rather than append to the Java EDGE-CASES, INTEGRATION-POINTS, BACKWARD-COMPAT,
and `findClass` NAMED-REFERENCES criteria for the prose part (T1).
`review-gate-verification.md` was left addendum-free, criteria-agnostic and out
of scope. Both pre-commit gates passed (no ephemeral identifiers outside
`_workflow/`; no live `.claude/workflow/` path staged).

**What was discovered:** Track 2's read caveat is already present
byte-identically in all three staged criteria prompts, so the Track 2
dependency is satisfied and `§1.7(e)` copy-then-edit-on-first-touch did not
re-fire. The caveat and the new addendum now form a contiguous staged-only
block in each of the three files, with no live counterpart until the Phase 4
promotion (`§1.7(h)`); Track 4's D5 delta-scoping and Phase C's S3 cross-check
should treat both blocks as one new-region staged addition. The addendum's
normalized md5 is d38747dc, the key for any future S3 re-verification.

**Key files:**
- `docs/adr/ytdb-1038-review-gate-for-workflow/_workflow/staged-workflow/.claude/workflow/prompts/technical-review.md` (modified)
- `docs/adr/ytdb-1038-review-gate-for-workflow/_workflow/staged-workflow/.claude/workflow/prompts/risk-review.md` (modified)
- `docs/adr/ytdb-1038-review-gate-for-workflow/_workflow/staged-workflow/.claude/workflow/prompts/adversarial-review.md` (modified)

## Validation and Acceptance

Track-level behavioral acceptance:
- On a workflow-machinery track (plan carries the `§1.7(b)` marker), each of
  the three criteria reviewers verifies named references as file paths and
  `§`-anchors via grep and Read; a missing Java symbol is not raised as a
  blocker.
- The WAL, crash, migration, and hot-caller criteria are replaced by the
  prose-soundness criteria for such a track.
- The same three reviewers run with no dispatch change; a track mixing prose
  and code gets one reviewer applying both lenses by reading the marker plus
  the track's in-scope files.
- The addendum reads the same across the three prompts (S3).
- `review-gate-verification.md` gets no addendum.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

The single step is one commit of an additive marker-gated prose block to
three staged prompts under `_workflow/staged-workflow/.claude/workflow/prompts/`
(the live tree is untouched until Phase 4). Recovery is the standard Phase B
revert: `git reset --hard HEAD` discards the uncommitted attempt, and
re-running re-applies the same staged addendum, so the step is idempotent on
re-run.

Per-step note:
- **Step 1** edits the three staged criteria prompts in place — Track 2
  already created all three staged copies (with the read caveat) on its own
  first touch, so the `§1.7(e)` copy-then-edit-on-first-touch step does not
  re-fire here. Before inserting the addendum, check for an existing addendum
  block to stay idempotent. The S3 cross-check is a read-only `grep`/`diff`
  over the three staged prompts and has no side effect.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

In-scope files (all under `.claude/workflow/**`):
- `technical-review.md`, `risk-review.md`, `adversarial-review.md` — the three
  Phase A criteria reviewers.

Out-of-scope:
- `review-gate-verification.md` — criteria-agnostic; takes the Track 2 read
  caveat only, no addendum.
- The complexity-assessment dispatch in `track-review.md` — unchanged; the
  same technical/risk/adversarial reviewers run (D4, Non-Goal).
- No new Phase A prompt files (Non-Goal).

Inter-track dependencies:
- **Depends on Track 2.** The addendum references the read caveat in
  `technical-review.md`, `risk-review.md`, and `adversarial-review.md`, so
  Track 2's caveat must land in those three files first.

Staging: per `§1.7`, all three edits route through
`docs/adr/<dir>/_workflow/staged-workflow/.claude/workflow/...`; the live
files stay at develop's state until Phase 4 promotion.

Full design: design.md §"Phase A criteria for workflow-machinery tracks".

## Base commit

bdbdfb29850c562c37add11ff674d42d3adff2fa
