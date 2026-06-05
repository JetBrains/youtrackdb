<!-- workflow-sha: 59c7dd338fc472a21ea2bd40876edb7ae96ee13b -->
# Track 1: Two-sided sizing, phase-aware enforcement, design freeze, and design-first authoring

## Purpose / Big Picture
After this track lands, a track is sized as one stacked-diff PR (two-sided
footprint bound + maximize), each size metric is checked at the phase where
it is knowable, `design.md` is frozen after Phase 1, and `/create-plan`
authors the design first in its own reviewed session.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Land all four YTDB-1060 threads as one stacked-diff PR. The threads share
files heavily (the sizing files, the design-doc files, the review prompts),
and applying the maximize directive by hand puts the ~17-file change under the
soft ceiling with no autonomy break, so it is one track, not four. This track
is its own first test case: it is decomposed by the rule it introduces, and
its size is the first calibration data point for the threshold open questions.

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
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

The workflow's only track-sizing rule today is the one-sided "more than
~5-7 steps, split", stated in step count. Mining the 42 committed tracks
under `docs/adr/` shows it never bound anything (min 1 step, median 3, max
5); file footprint is the dimension that goes unbounded. The rule is
duplicated across **12 positions**, five of which are review prompts that
enforce it on reviewers (see the sync-list in §Interfaces and Dependencies).

`design.md` carries a live self-contradiction: `design-document-rules.md`
lists inline replanning as a `design.md` mutation trigger in its §Mutation
discipline enumeration, while Rule 15 in §Rules states `design.md` is "never
modified after planning". No Phase 3A or 3C reviewer
receives `design.md`, and the plan's immutable Decision Records are already
the de-facto source of truth during execution. `create-plan` Step 4 authors
Architecture Notes and the track checklist first and writes `design.md` last
(sub-step 8), so the design back-fills the plan rather than seeding it.

The Phase 2 structural reviewer classifies findings into exactly two classes
— `mechanical` (orchestrator auto-fixes) and `design-decision` (escalate) —
with no advisory tier; track sizing is already a `design-decision`. The
branch is workflow-modifying, so all edits stage under
`_workflow/staged-workflow/.claude/...` and promote at Phase 4.

Concrete deliverables: the retired metric replaced everywhere by the
two-sided cap; the maximize directive in `planning.md`; the file-based floor
and footprint ceiling with the argumentation gate; the Phase B running-diff
early-warning and the Phase C review-burden check; the `design.md` freeze
(mutation paths removed, replan intent rerouted); and the design-first
`create-plan` reorder with the adversarial-then-cold-read `edit-design`
ordering.

## Plan of Work

The edits cluster into four arcs that share files, so they land in dependency
order and later edits read earlier edits' staged copies:

1. **Sizing definition.** Reframe the Track glossary entry and the §1.2
   planning rule in `conventions.md`; write the maximize directive, the
   two-sided bound, the floor, and the argumentation rule into `planning.md`
   §Track descriptions; mirror the decomposition guidance in `track-review.md`
   §Step Decomposition and the `create-plan/SKILL.md` Step 4 sizing rule.
   Retire "~5-7 steps" as the sizing metric (the step *definition* in
   `conventions.md:70` is unchanged).
2. **Enforcement propagation.** Move the five review prompts (`structural`
   ×3 spots, `technical`, `adversarial`, `risk`, `consistency`) to the
   two-sided cap and add a sync-list anchor so the set cannot drift apart.
   Add the Phase B running diff-stat early-warning to the step loop and the
   Phase C review-burden line check to `track-code-review.md`.
3. **Design freeze.** Remove the inline-replanning mutation trigger from
   `design-document-rules.md` and narrow its phase annotations; reroute
   `inline-replanning.md`'s design intent into the Decision Records and the
   track narrative; narrow the §1.8 `edit-design` phase span in
   `conventions.md`; add the implementer's frozen-design guard in
   `implementer-rules.md`; add the divergence-is-expected note to
   `consistency-review.md`.
4. **Design-first reorder.** Reorder `create-plan/SKILL.md` Step 4 to author
   the design first, add the design→plan session boundary and auto-resume
   condition; re-frame `planning.md` §Goal and §Design Document; list the
   design/plan sub-phases in `workflow.md`; add the design-scoped role/phase
   to `adversarial-review.md`; note the cold-read-after-adversarial order in
   `design-review.md`; insert the adversarial step into the `edit-design`
   `phase1-creation` loop.

Ordering constraints: arc 1 establishes the vocabulary the review prompts in
arc 2 cite, so arc 1 precedes arc 2. Arcs 3 and 4 compose (Thread 3 freezes,
Thread 4 moves the freeze point earlier); the design-first reorder must read
the frozen-design rules, so arc 3 precedes arc 4. Invariants to preserve: the
step definition is unchanged; the `§1.7(b)` marker stays in the plan's
Constraints; no whole-file deletions (promotion is additive-only).

(Phase A appends the per-step sequencing summary referencing the Concrete
Steps roster.)

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered roster here:
one entry per step with description, `risk:` tag, an optional `size:` clause,
and a `[ ]` status checkbox. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step,
identified by step number + commit SHA. Empty at Phase 1. -->

## Validation and Acceptance

Track-level behavioral acceptance criteria:

- The string "~5-7 steps" (and the one-sided step ceiling it states) appears
  in zero *enforcement* positions: no planning rule, no glossary sizing
  metric, no review-prompt check. The step *definition* is unchanged.
- `planning.md` states the maximize directive — "extend the track to the
  bound, split only when forced" — before any split clamp, and the sizing
  prose names files and steps, never a line count.
- A track outside the bounds (under-floor, below the maximize target, or
  over the ceiling) that carries no argumentation block is a `design-decision`
  finding at Phase 2; a documented one passes without escalation.
- The floor is file-based (≤~12 files) and flag-only; nothing auto-merges
  tracks.
- The Phase B step loop reads a running `git diff base..HEAD --stat`; the
  Phase C code review checks review burden at >~2,000 / >~4,000 total +/-
  lines (generated excluded, test kept).
- `design-document-rules.md` lists no Phase 3 `design.md` mutation trigger,
  and Rule 15's freeze no longer self-contradicts; an inline replan routes
  design intent into the Decision Records and the track narrative.
- `consistency-review.md` § DESIGN↔PLAN tells the re-run reviewer that a
  revised-DR-vs-frozen-design divergence is expected, not a finding.
- `create-plan/SKILL.md` authors `design.md` before the Architecture Notes
  and track checklist, and auto-resumes plan derivation when `design.md`
  exists and `implementation-plan.md` does not; the `edit-design`
  `phase1-creation` loop runs adversarial before cold-read.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as
test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once
steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't belong
to one specific step. Often empty. -->

## Interfaces and Dependencies

**In-scope files (~17, all under §1.7 staging):**

| File | Threads | Edit |
|---|---|---|
| `conventions.md` | 1, 3 | Track glossary §1.1, §1.2 planning rule, §1.8 edit-design phase span |
| `planning.md` | 1, 2, 4 | maximize + two-sided bound; line-free sizing prose; §Goal + §Design Document re-frame |
| `track-review.md` | 1 | §Step Decomposition track-sizing guidance |
| `create-plan/SKILL.md` | 1, 4 | Step 4 sizing rule; design-first reorder + session boundary + auto-resume |
| `prompts/structural-review.md` | 1 | track-sizing terminology + finding template (3 spots) → two-sided cap |
| `prompts/technical-review.md` | 1 | sizing guidance → two-sided cap |
| `prompts/adversarial-review.md` | 1, 4 | sizing guidance; design-scoped role/phase |
| `prompts/risk-review.md` | 1 | sizing guidance → two-sided cap |
| `prompts/consistency-review.md` | 1, 3 | track-definition sizing; divergence-is-expected note |
| `step-implementation.md` | 2 | Phase B running diff-stat early-warning |
| `track-code-review.md` | 2 | Phase C review-burden line check |
| `design-document-rules.md` | 3, 4 | remove mutation trigger + narrow annotations; pin adversarial-then-cold-read ordering |
| `inline-replanning.md` | 3 | route design intent to DRs + track narrative; drop design-file clause |
| `implementer-rules.md` | 3 | frozen-design guard (escalate `DESIGN_DECISION_NEEDED`) |
| `edit-design/SKILL.md` | 3, 4 | insert adversarial step before cold-read for `phase1-creation` |
| `workflow.md` | 4 | list design/plan sub-phases with the A/B/C session-boundary contract |
| `prompts/design-review.md` | 4 | note cold-read runs after the adversarial pass |

**Sync-list — the 12 "~5-7 steps" occurrences to update (re-verified on this
branch):** `planning.md:424`, `conventions.md:69`, `conventions.md:228`,
`track-review.md:702`, `create-plan/SKILL.md:207`, `structural-review.md:58`,
`:174`, `:370`, `technical-review.md:45`, `adversarial-review.md:46`,
`risk-review.md:45`, `consistency-review.md:70`. Phase A confirms the live
line numbers (develop may have moved them).

**Out of scope:** the semantic-staleness lint for `Full design:` links
(YTDB-1079); re-implementing the design-first reorder in YTDB-975 (corrected
on a separate branch); pinning final threshold values.

**Dependencies:** none — single track. Internal ordering (arc 1 → 2, arc 3 →
4) is handled by staged-first reads within the plan; the design-doc anchors
were verified against current `develop` during Phase 0 and may shift under
rebase before Phase 4 promotion.
