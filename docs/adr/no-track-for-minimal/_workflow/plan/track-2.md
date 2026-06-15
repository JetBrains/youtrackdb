<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Track 2: Rewire the runtime consumers onto the ledger

## Purpose / Big Picture
After this track lands, every runtime consumer reads branch-level facts and
review state from the ledger and the new `plan-review.md` rather than the plan,
the `minimal`→`lite`/`full` escalation materializes the dropped artifacts, and
pause boundaries and the track completion episode land in their new homes.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Re-point every consumer that reads branch-level facts or review state at the new
homes, and update the escalation, handoff, and episode paths. Moves the Phase-2
audit summary into the new `plan-review.md` (review state stays in the ledger),
re-points the tier-line and §1.7(c)/(l) marker readers at the ledger, records
pause boundaries as ledger events, makes the `minimal`→`lite`/`full` escalation
materialize the dropped plan and design, and moves the track completion episode
into the track file. Depends on the ledger format and conventions Track 1
defines.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log

#### D4: Branch-level facts live in the ledger
- **Alternatives considered**: per-tier homes (tier line → ledger; §1.7 marker
  → plan `### Constraints` in `lite`/`full`, track `### Constraints` in
  `minimal` — the issues as filed).
- **Rationale**: "this branch stages" and "the change is tier X" are
  whole-change properties no single track owns in multi-track `lite`/`full`; the
  per-tier split scatters the marker across two locations. One fixed ledger
  location serves the implementer §1.7(c) gate, the §1.7(l) re-point, and the
  tier-line readers. Track 1 defines this ledger home; this track re-points
  every reader at it.
- **Risks/Caveats**: a missed reader silently reads a stale or absent fact, so
  the reader inventory must be exhaustive — `inline-replanning`,
  `track-review`, `create-final-design`, `consistency-review`, the three
  §1.7(l) review prompts (`technical`/`risk`/`adversarial`), the implementer
  §1.7(c) gate (`step-implementation`, `implementer-rules`), and the two
  gate-recheck prompts that carry the same standalone §1.7(b) staged-read block
  (`dimensional-review-gate-check`, `review-gate-verification`).
- **Implemented in**: this track (the readers); Track 1 defines the conventions
  §1.7 marker home.
- **Full design**: design.md §"The phase ledger" (Decisions & invariants, D4).

#### D7: Phase-2 audit summary moves to a new plan-review.md
- **Alternatives considered**: fold the audit into a ledger `review=passed`
  event (zero new artifact).
- **Rationale**: the audit summary is multi-line review prose (consistency +
  structural findings, auto-fixes, escalations), so embedding it in the
  append-only ledger tail would bloat the tail `determine_state` greps. Review
  *state* stays in the ledger (the resume hot path); review *fact and summary*
  go to `plan-review.md`, which exists in every tier so `minimal` has a
  review-fact home without a plan. `/review-plan` re-runs append their verdict
  there; Phase 4 folds the verdict as it does today.
- **Risks/Caveats**: a second review artifact to keep coherent; mitigated by it
  being a cold record rarely read during development.
- **Implemented in**: this track (implementation-review, consistency/structural
  review prompts, create-final-design fold).
- **Full design**: design.md §"The thinned plan and the plan-review document".

#### D8: Pause boundaries recorded as ledger events
- **Alternatives considered**: keep the two plan-anchored secondary `**PAUSED`
  markers where they are.
- **Rationale**: the Phase-2/State-0 and Phase-4 secondary markers sat beneath
  `## Plan Review` and `## Final Artifacts`, both removed from the thinned plan.
  Routing them to a ledger `paused` event is uniform across tiers and strictly
  stronger — a ledger paused event is machine-read by `determine_state` on
  resume, not just a human cue. The handoff file itself is unchanged; only the
  in-plan defense-in-depth marker relocates. A/B/C pauses stay in the track
  `## Progress`; Phase 0/1 and ad-hoc stay "none".
- **Risks/Caveats**: the recovery grep (`grep -rn '^\*\*PAUSED '`) must cover
  the ledger, or the ledger paused event must keep the greppable `**PAUSED`
  prefix.
- **Implemented in**: this track (mid-phase-handoff).
- **Full design**: design.md §"The phase ledger" (D8).

#### D11: Minimal→lite/full escalation materializes the dropped plan and design
- **Alternatives considered**: route only "tier-line readers → ledger" and leave
  the writer side implicit.
- **Rationale**: `inline-replanning` is a tier-line writer, not only a reader:
  an ESCALATE upgrade rewrites the tier line and a `lite`→`full` upgrade writes
  a new design seed. Under D2 the `minimal` tier has no plan or design, so the
  upgrade carrier must write the upgraded tier as a ledger event and materialize
  `implementation-plan.md` (and `design.md` for `full`).
- **Risks/Caveats**: a downgrade is not automatic and a completed review is not
  re-run, matching today's mid-flight upgrade rule.
- **Implemented in**: this track (inline-replanning).
- **Full design**: design.md §"Mid-flight tier upgrade".

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

This track stages under §1.7(b) like Track 1; every edit routes to
`_workflow/staged-workflow/.claude/**` and the live workflow stays at develop.
It depends on the ledger format, the thinned plan shape, and the §1.7 marker
home that Track 1 defines, so it runs after Track 1.

Codebase state at the start of this track — the consumers and what each reads
today:

- **`.claude/workflow/implementation-review.md`**: the Phase-2 consistency +
  structural audit, whose summary overwrites the plan's `## Plan Review`
  section today. Moves to writing `plan-review.md`.
- **`.claude/workflow/prompts/consistency-review.md`**: reads the tier line for
  routing and has a degenerate-case branch for an unreadable tier line; under
  the mirror model it reads the ledger tier line and validates plan-vs-track
  consistency.
- **`.claude/workflow/prompts/structural-review.md`**: the structural pass and
  the plan bloat checks; the pass is dropped entirely in `minimal` (no plan),
  and the bloat checks adapt to the thinned `lite`/`full` plan.
- **`.claude/workflow/prompts/create-final-design.md`**: the Phase-4 prompt
  reading the tier line and folding the review verdict into `adr.md`; in
  `minimal` it folds the verdict into the PR-description summary instead of a
  `docs/adr` entry.
- **`.claude/workflow/prompts/technical-review.md`,
  `prompts/risk-review.md`, `prompts/adversarial-review.md`**: each carries the
  §1.7(l) "workflow-machinery criteria" block that reads the plan's
  `### Constraints` for the canonical §1.7(b)/(k) marker. The marker read
  re-points to the ledger.
- **`.claude/workflow/step-implementation.md`, `implementer-rules.md`**: the
  per-spawn implementer §1.7(c) gate that reads `### Constraints` for the marker
  and routes writes to staged paths. The marker read re-points to the ledger.
- **`.claude/workflow/prompts/dimensional-review-gate-check.md`,
  `prompts/review-gate-verification.md`**: the two gate-recheck prompts (the
  Phase-3B/3C dimensional gate-check and the Phase-3A review-gate re-check) carry
  the same standalone §1.7(b) staged-read block reading the plan's
  `### Constraints` for the marker. The marker read re-points to the ledger.
- **`.claude/workflow/track-review.md`**: the Phase-A review that reads the tier
  line to prime its criteria.
- **`.claude/workflow/inline-replanning.md`**: the ESCALATE/tier-upgrade path —
  a tier-line reader and writer (D11).
- **`.claude/workflow/mid-phase-handoff.md`**: the secondary-marker table whose
  Phase-2/State-0 and Phase-4 rows point beneath removed plan sections (D8).
- **`.claude/workflow/track-code-review.md`**: Phase-C track completion writes
  the completion episode; it moves canonical to the track file (D5 of the plan).

## Plan of Work

Order the edits so the highest-traffic state contract (the §1.7 marker read)
is re-pointed once and the rest follow. Each edit consumes a Track 1 contract.

1. **Phase-2 audit → `plan-review.md` (D7).** `implementation-review.md` writes
   the audit summary to the new document and records review *state* in the
   ledger; `consistency-review.md` reads the ledger tier line and keeps the
   degenerate-unreadable-tier branch; `structural-review.md` drops the `minimal`
   pass and adapts the bloat checks to the thinned plan.
2. **Tier-line + §1.7(c)/(l) marker readers → ledger (D4).** Re-point the marker
   read in the three §1.7(l) review prompts, the implementer §1.7(c) gate
   (`step-implementation`, `implementer-rules`), and the two gate-recheck prompts
   that carry the same standalone §1.7(b) staged-read block
   (`dimensional-review-gate-check`, `review-gate-verification`) from the plan's
   `### Constraints` to the ledger; re-point `track-review` and
   `create-final-design` tier-line reads to the ledger.
3. **Pause boundaries → ledger events (D8).** Route the mid-phase-handoff
   Phase-2/State-0 and Phase-4 secondary markers to a ledger `paused` event,
   keeping the greppable `**PAUSED` prefix (or extending the recovery grep to
   the ledger).
4. **Minimal→lite/full escalation (D11).** `inline-replanning` writes the
   upgraded tier as a ledger event and materializes `implementation-plan.md`
   (and `design.md` for `full`) the source `minimal` tier never had.
5. **Completion episode → track file.** `track-code-review` writes the track
   completion episode canonical to the track file's `## Episodes`; the
   `lite`/`full` Checklist keeps a one-line summary and pointer.

Invariant to preserve: the §1.7(b)/(k) stable-prefix match semantics are
unchanged — only the *location* the consumers read the marker from moves from
the plan to the ledger, so a workflow-modifying branch is still detected
identically.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, an
optional `size:` clause, and a `[ ]` status checkbox. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step. Empty at Phase 1. -->

## Validation and Acceptance

- A Phase-2 review writes its audit summary to `plan-review.md` and its review
  state to the ledger; a `minimal` change (no plan) still has a review-fact home.
- The implementer §1.7(c) gate and the three §1.7(l) review prompts detect a
  workflow-modifying branch from the ledger marker, with the same stable-prefix
  semantics as the old plan `### Constraints` read.
- A paused phase boundary at State 0 or Phase 4 is recorded as a ledger event
  that `determine_state` reads on resume, and the recovery grep still finds it.
- A `minimal`→`lite` upgrade materializes `implementation-plan.md`; a
  `minimal`→`full` upgrade materializes both `implementation-plan.md` and
  `design.md`, and writes the upgraded tier as a ledger event.
- A completed track's completion episode is in the track file's `## Episodes`;
  the `lite`/`full` Checklist carries only a one-line summary and pointer.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Often empty. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/workflow/implementation-review.md`
- `.claude/workflow/prompts/consistency-review.md`
- `.claude/workflow/prompts/structural-review.md`
- `.claude/workflow/prompts/create-final-design.md`
- `.claude/workflow/prompts/technical-review.md`
- `.claude/workflow/prompts/risk-review.md`
- `.claude/workflow/prompts/adversarial-review.md`
- `.claude/workflow/prompts/dimensional-review-gate-check.md`
- `.claude/workflow/prompts/review-gate-verification.md`
- `.claude/workflow/step-implementation.md`
- `.claude/workflow/implementer-rules.md`
- `.claude/workflow/track-review.md`
- `.claude/workflow/inline-replanning.md`
- `.claude/workflow/mid-phase-handoff.md`
- `.claude/workflow/track-code-review.md`

All edits route to the staged mirror under
`docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/**`.

**Out-of-scope (Track 1):** the ledger primitive (`workflow-startup-precheck.sh`
and tests), the convention/planning/workflow format docs, and the `create-plan`
SKILL. This track consumes the contracts Track 1 publishes; it does not define
them.

**Contracts this track consumes (published by Track 1):**
- The ledger event grammar and the `--append-ledger` subcommand signature (read
  by the re-pointed marker/tier consumers; written by the escalation path).
- The thinned `lite`/`full` plan shape and the per-tier artifact set (the
  Phase-2 review and escalation branch on them).
- The §1.7 marker home in the ledger (the §1.7(c)/(l) readers re-point to it).

**Inter-track dependency:** depends on Track 1. No downstream track consumes
this track's output within this plan.
