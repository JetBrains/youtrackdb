<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Track 1: The phase ledger, the new artifact model, and the authoring surface

## Purpose / Big Picture
After this track lands, the workflow has an append-only phase ledger that owns
branch-level resume state, convention and planning docs that specify the
derived-mirror plan model, and a `create-plan` skill that produces the new
artifacts.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Build the append-only phase ledger and its `--append-ledger` subcommand in
`workflow-startup-precheck.sh`, then define the model around it:
`determine_state` reading the ledger tail
(two-level resume), the convention and planning docs that specify the new
artifact set (per-tier set, the thinned `lite`/`full` plan, the 15th track
section, the §1.6(f) ledger exclusion, the §1.7 marker home), and the
`create-plan` SKILL that produces the new artifacts (drop the `minimal` stub,
thin `lite`/`full`, add `## Invariants & Constraints`, seed the ledger, route
Step 1c resume on the ledger). This track defines and produces the new model;
Track 2 rewires the runtime consumers onto it.

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

#### D1: Plan is a derived mirror of the tracks
- **Alternatives considered**: keep the plan canonical for Architecture Notes
  (status quo).
- **Rationale**: the status quo leaves two writers of the same fact and the
  drift the §1.6 stamp gate exists to catch. Track files are canonical for
  detailed content; the `lite`/`full` plan summarizes only next-track
  continuation and cross-track impact, and `minimal` drops it (D2).
- **Risks/Caveats**: a thinner plan loses standalone readability; mitigated by
  the Checklist intro paragraphs and the thin Component Map.
- **Implemented in**: this track (conventions §1.2 per-tier set and plan
  structure, planning plan structure, create-plan templates).
- **Full design**: design.md §"The thinned plan and the plan-review document".

#### D2: Minimal drops the plan; lite/full thin it
- **Alternatives considered**: all tiers keep a (one-track) summary plan;
  change `minimal` only and leave `lite`/`full` untouched.
- **Rationale**: a one-track plan mirrors a single track and its cross-track
  view is vacuous, so `minimal` drops `implementation-plan.md`; `lite`/`full`
  keep the thinned plan as the cross-track navigation layer.
- **Risks/Caveats**: a `minimal`→`lite`/`full` escalation must materialize the
  dropped plan (D11, Track 2).
- **Implemented in**: this track (per-tier artifact set, create-plan templates);
  the structural-review drop of the `minimal` pass and the Phase-4 minimal
  PR-summary land in Track 2.
- **Full design**: design.md §"The thinned plan and the plan-review document".

#### D3: Ledger is authoritative for resume state
- **Alternatives considered**: keep a tiny resume artifact the script parses
  (defers the ledger and limits how far `minimal` shrinks).
- **Rationale**: dropping the `minimal` plan removes the artifact
  `determine_state` parses today; resume state needs a non-plan home. The
  startup script derives State 0 / A / C / D / Done from the ledger tail.
  `determine_state` stays a two-level lookup: the ledger owns the top-level
  phase and active track, the track file's `## Progress` owns the within-track
  sub-state.
- **Risks/Caveats**: a torn append must not corrupt state — the atomic
  temp-file-plus-rename append plus the existing interrupted-write
  reconciliation cover it.
- **Implemented in**: this track (precheck.sh `determine_state`, tests).
- **Full design**: design.md §"The phase ledger", §"Resume routing".

#### D5: Old plan sections disposed per section
- **Alternatives considered**: keep `### Goals`, Architecture Notes, and the
  completion episode in the plan.
- **Rationale**: `### Goals` is read only by the structural bloat check and the
  aim lives in the research log plus the PR `## Motivation`; Decision Records are
  track-canonical, so the thinned plan keeps only a thin cross-track Component
  Map; the completion episode is canonical in the track file.
- **Risks/Caveats**: removing `## Plan Review` and `## Final Artifacts` from the
  thinned plan changes what `determine_state` and Phase-4 read — covered by D3
  and (in Track 2) D7.
- **Implemented in**: this track (conventions, planning); the track-code-review
  completion-episode relocation lands in Track 2.
- **Full design**: design.md §"The thinned plan and the plan-review document".

#### D6: Ledger is an append-only event log written by an orchestrator subcommand
- **Alternatives considered**: a current-state file rewritten each boundary
  (needs an in-place atomic rewrite); the script infers boundaries autonomously
  (forces the script to reconstruct orchestrator actions).
- **Rationale**: one append per phase boundary, last-value-wins on read, handles
  a mid-flight tier change by appending a new value. The orchestrator calls
  `--append-ledger` at the same points it flips checkboxes today, so the append
  and format live in one tested place. Each entry carries an ISO timestamp, a
  `[ctx=…]` marker, the phase, an optional active track, and optional field
  updates.
- **Risks/Caveats**: append granularity is per phase boundary, too coarse for
  per-step sub-state — which stays in the track file (D3's two-level rule).
- **Implemented in**: this track (precheck.sh `--append-ledger`, tests).
- **Full design**: design.md §"The phase ledger".

#### D9: Combined `## Invariants & Constraints` track section (the 15th)
- **Alternatives considered**: a separate `## Constraints` section with
  invariants left in `## Validation and Acceptance`; fold both into
  `## Validation and Acceptance`.
- **Rationale**: testable technical/performance/compatibility constraints and
  the Architecture Notes invariants are the same shape ("X must hold, backed by
  a test"), so they share one home. A process-only, non-testable constraint goes
  to `## Context and Orientation` or the Decision Log. Integration Points move to
  the existing `## Interfaces and Dependencies`; Non-Goals move to the research
  log and PR `## Motivation` (and design.md in `full`).
- **Risks/Caveats**: additive — the rest of the 14-section template is unchanged.
- **Implemented in**: this track (conventions-execution §2.1 template, planning
  track descriptions, create-plan track template).
- **Full design**: design.md §"Track-file dispositions".

#### D10: Step 1c routes resume on the ledger, not plan presence
- **Alternatives considered**: keep Step 1c routing on `design.md` /
  `implementation-plan.md` presence.
- **Rationale**: a plan-less `minimal` resume would hit the "neither file exists
  — fresh start" branch and re-run research, tier classification, and the gate.
  Step 1c reads the ledger (present and tier line readable) to route a `minimal`
  resume to its recorded state; the `plan/track-1.md` glob is the secondary
  signal. For `lite`/`full`, plan presence stays the signal.
- **Risks/Caveats**: the narrow window where Step 4's gate cleared but no ledger
  entry was written reads as a fresh start — correct, nothing durable was
  produced.
- **Implemented in**: this track (create-plan Step 1c, ledger seeding).
- **Full design**: design.md §"Resume routing".

#### D13: Ledger is unstamped (research-log precedent)
- **Alternatives considered**: stamp the ledger like the other `_workflow/**`
  artifacts.
- **Rationale**: an append-only log that no §1.6(h) walk enumerates and no phase
  re-derives is replay-immune, so a workflow-SHA stamp would be dead weight and
  would trip the drift gate's unstamped detection. The ledger joins the §1.6(f)
  exclusion list alongside `research-log.md`. `track-1.md` stays the stamped
  drift anchor, so dropping the `minimal` plan does not weaken drift detection.
- **Risks/Caveats**: none material.
- **Implemented in**: this track (conventions §1.6(f), precheck.sh drift fold).
- **Full design**: design.md §"The phase ledger".

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

The branch is workflow-modifying under §1.7(b) (plan D12), so every edit in
this track routes to `_workflow/staged-workflow/.claude/**`; the live workflow
stays at develop state and the branch executes under the develop workflow. The
new `workflow-startup-precheck.sh` and its tests are exercised from the staged
path, not wired into the live machinery.

Codebase state at the start of this track:

- **`.claude/scripts/workflow-startup-precheck.sh`** (~1,744 lines). Today
  `determine_state` (around line 1439) resolves the plan dir from the branch,
  reads `implementation-plan.md`, parses the `## Plan Review`
  first-checkbox token via `section_first_checkbox_token`, then walks
  `## Checklist` for the first `[ ]` track and reads the track file's
  `## Progress` for the within-track sub-state. The CLI surface is a
  `--mode {full,divergence-only,migrate-range}` `case` near line 60 with
  `--bootstrap-sha` and repeatable `--exclude-sha`. `detect_drift` (around line
  474) folds the stamp of `_workflow/**` artifacts.
- **`.claude/scripts/tests/test_workflow_startup_precheck.py`** (the main suite)
  and **`test_workflow_startup_precheck_stub.py`** (the `minimal` stub-plan
  tests, which read the tier line). The stub tests assume a parsed `minimal`
  plan and must be reworked for the no-plan `minimal` resume.
- **`.claude/workflow/conventions.md`**: §1.1 glossary, §1.2 Plan File Structure
  and the Per-tier artifact set, §1.6(f) stamped-artifact exclusions and the
  §1.6(h) Phase-1 walk, §1.7(b)/(k) staging markers.
- **`.claude/workflow/conventions-execution.md`**: §2.1 track-file content (the
  14-section template and the section-lifecycle table).
- **`.claude/workflow/planning.md`**: §Tier classification, §Plan file
  structure, §Architecture Notes format, §Track descriptions.
- **`.claude/workflow/workflow.md`**: §Startup Protocol (the State 0 / A / C / D
  derivation `determine_state` feeds).
- **`.claude/skills/create-plan/SKILL.md`**: Step 1c resume routing, the per-tier
  plan and track templates (the `minimal` shape-complete stub, the full
  aggregator), the track-file template.

Terminology used below without re-definition: the **phase ledger**
(`_workflow/phase-ledger.md`), an append-only event log; the **two-level
resume** (ledger owns top-level phase and active track, track file owns
within-track sub-state); the **15th track section**
(`## Invariants & Constraints`).

## Plan of Work

Order the edits so the ledger format is defined before any doc or skill
references it, and the skill that produces the artifacts is touched last.

1. **Ledger primitive (the script).** Add the `--append-ledger` subcommand to
   the CLI `case` and an atomic temp-file-plus-rename append. Rewrite
   `determine_state` to read the ledger tail (latest value per key) for the
   top-level phase and active track, keeping the track-file `## Progress` read
   for the within-track sub-state. Add the ledger to the drift logic as an
   unstamped artifact (it is not folded into the §1.6(h) stamp walk). Decide and
   pin the event vocabulary and field grammar (phase, track, tier + matched
   categories, §1.7 mode, paused) — this grammar is the contract Track 2's
   readers consume.
2. **Script tests.** Cover the append, last-value-wins reads, the
   interrupted-append reconciliation, and the new `determine_state` ledger path.
   Rework `test_workflow_startup_precheck_stub.py` for the no-plan `minimal`
   resume (ledger present, no `implementation-plan.md`).
3. **Conventions.** §1.1 glossary entries (phase ledger, derived-mirror plan,
   plan-review document, combined Invariants & Constraints section); §1.2
   per-tier artifact set (the ledger row; `minimal` drops the plan; `lite`/`full`
   thin it) and the thinned plan structure; §1.6(f) ledger exclusion (D13);
   §1.7(b)/(k) marker-home note (the §1.7 mode marker now lives in the ledger,
   D4 — this track defines the home; Track 2 re-points the readers); the
   track-file section count (14 → 15).
4. **Conventions-execution §2.1.** Add `## Invariants & Constraints` as the 15th
   section to the track-file template and the section-lifecycle table; note that
   Integration Points fold into `## Interfaces and Dependencies` and the
   completion episode is canonical in the track file (the track-code-review
   relocation is Track 2).
5. **Planning.** §Tier classification, §Plan file structure (the thinned
   `lite`/`full` plan; `minimal` has no plan), §Track descriptions (the 15th
   section), and the disposition of `### Goals` / Architecture Notes per D5.
6. **Workflow.md §Startup Protocol.** State derivation reads the ledger tail
   rather than the plan checkboxes.
7. **create-plan SKILL.** Drop the `minimal` shape-complete stub template; thin
   the `lite`/`full` aggregator template; add `## Invariants & Constraints` to
   the track template; seed the ledger at Phase 1 via `--append-ledger`; rewire
   Step 1c resume routing onto the ledger (D10).

Invariant to preserve across the work: existing in-flight `lite`/`full` plans
that still carry a Checklist must resume without regression — the two-level
lookup keeps the track-file sub-state walk unchanged.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, an
optional `size:` clause, and a `[ ]` status checkbox. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step. Empty at Phase 1. -->

## Validation and Acceptance

- A phase boundary append followed by a fresh `--mode full` read returns the
  appended phase and active track; a second append of a changed tier is read as
  the latest value.
- An interrupted (torn) append leaves the prior ledger tail intact and
  `determine_state` resolves the prior state.
- A `minimal` branch with a ledger and a `plan/track-1.md` but no
  `implementation-plan.md` resumes to its recorded state (not a fresh start).
- An existing `lite`/`full` plan with a Checklist resumes unchanged.
- The drift gate still finds a stamped anchor (`track-1.md`) when the plan is
  absent; the ledger is reported unstamped without tripping the drift gate.
- The `create-plan` `minimal` path produces no `implementation-plan.md`; the
  `lite`/`full` path produces the thinned plan; every tier's track template
  carries `## Invariants & Constraints`.

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
- `.claude/scripts/workflow-startup-precheck.sh`
- `.claude/scripts/tests/test_workflow_startup_precheck.py`
- `.claude/scripts/tests/test_workflow_startup_precheck_stub.py`
- `.claude/workflow/conventions.md`
- `.claude/workflow/conventions-execution.md`
- `.claude/workflow/planning.md`
- `.claude/workflow/workflow.md`
- `.claude/skills/create-plan/SKILL.md`

All edits route to the staged mirror under
`docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/**`.

**Out-of-scope (Track 2):** the runtime consumer prose — `implementation-review.md`,
the `consistency-review` / `structural-review` / `create-final-design` /
`technical-review` / `risk-review` / `adversarial-review` prompts,
`step-implementation.md`, `implementer-rules.md`, `track-review.md`,
`inline-replanning.md`, `mid-phase-handoff.md`, `track-code-review.md`. This
track defines the §1.7 marker home and the Phase-2 review-state contract; it
does not re-point the readers.

**Contracts this track publishes (Track 2 depends on them):**
- The `--append-ledger` subcommand signature (the fields an orchestrator passes:
  phase, optional track, tier + matched categories, §1.7 mode, paused event).
- The ledger event grammar `determine_state` greps (line shape, key set,
  last-value-wins read).
- The thinned `lite`/`full` plan shape and the per-tier artifact set the
  consumers branch on.
- The 15th track section `## Invariants & Constraints`.

**Inter-track dependency:** Track 2 depends on this track; this track depends on
nothing prior. No downstream track in this plan consumes Track 1 output beyond
Track 2.
