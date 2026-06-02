<!-- workflow-sha: 0676e2446f373e969da86da6748c91d442135161 -->
# Track 2: State determination

## Purpose / Big Picture
After this track lands, `--mode full` reports the correct resume `state` — phase 0 / A / C / D / Done, plus the five-way State C sub-state and the `section-discrepancy` edge — so the agent routes startup without reading the prose state table.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Track 2 builds the markdown state parser that reads `## Plan Review`, the track checklist, and the active track file's `## Progress` section, then reports `state.phase` and, for State C, the sub-state string. This is the one piece of the script that parses markdown rather than git output, so it is the riskiest surface and is isolated in its own track with the heaviest fixture coverage. It is authored live and extends the `full`-mode JSON that Track 1 stubs.

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

State determination today lives in `workflow.md § Startup Protocol` step 5 as a prose precedence table the agent reads and applies. This track ports that precedence into the script.

The precedence the parser must reproduce:

- **State 0** is checked first: an unchecked or entirely-absent `## Plan Review` entry means plan review has not passed.
- Otherwise the parser walks the track checklist in order. The first `[ ]` track decides between **State A** (no track file → pre-Phase-A) and **State C** (track file exists → mid-track resume).
- When every track is `[x]` or `[~]`, the `## Final Artifacts` marker chooses **State D** (`[ ]` or `[>]`) or **Done** (`[x]`).

For State C, the parser reads the active track file's `## Progress` checkboxes and maps them to the five sub-states `workflow.md` defines: decomposition pending, steps partial, a failed step, steps done with review pending, and review done with the track still open. The section-discrepancy edge — a roster step flipped `[x]` without its matching Progress entry — is reported as the literal `section-discrepancy` so the agent runs resume-side reconciliation from the Episodes block.

This track extends the script Track 1 scaffolds; it relies on Track 1's `--mode full` plumbing and jq emit point being in place. The markers it reads (`[ ]` / `[x]` / `[~]` / `[>]`) are the ones `conventions.md §1.2 § Status markers` defines.

## Plan of Work

The work proceeds top-down through the precedence, then the State C sub-state map, then the discrepancy edge, then fixtures.

1. **State 0 / A / C / D / Done precedence walk.** Read `## Plan Review` from `implementation-plan.md`; absent or unchecked → State 0. Otherwise walk the `## Checklist` track entries in order; the first `[ ]` track resolves to State A (no `plan/track-N.md`) or State C (track file present). All tracks `[x]`/`[~]` → read `## Final Artifacts` and resolve State D vs Done.
2. **State C sub-state map.** For the active track's `## Progress`, map the four-checkbox combination to one of the five sub-state strings.
3. **Section-discrepancy edge.** Detect a `## Concrete Steps` roster entry flipped `[x]` whose matching `## Progress` line is missing; emit the literal `section-discrepancy` sub-state.
4. **State fixtures.** Construct plan-dir fixtures at each phase and each State C sub-state, plus the discrepancy edge, and assert the emitted `state` object.

Ordering constraints and invariants to preserve: the walk uses the same `[ ]` / `[x]` / `[~]` / `[>]` markers as `conventions.md §1.2 § Status markers`; a malformed marker is reported as an explicit parse error, not silently coerced. State C sub-state detection reads only the active track's file, not every track file, to keep the parse cheap. An entirely-absent `## Plan Review` is treated identically to an unchecked entry (State 0).

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. Per-step episodes do NOT live here; they live
in `## Episodes` below. The roster is immutable after Phase A except
for the status checkbox flip and the optional `commit:` annotation
Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

Track-level behavioral acceptance:

- An absent or `[ ]` `## Plan Review` reports `state.phase="0"`, regardless of track checkboxes.
- A passed `## Plan Review` with the first `[ ]` track lacking a `plan/track-N.md` reports `state.phase="A"`; with the track file present it reports `state.phase="C"`.
- All tracks `[x]`/`[~]` with `## Final Artifacts` `[ ]`/`[>]` reports `state.phase="D"`; with `[x]` it reports `state.phase="Done"`.
- Each of the five State C Progress shapes reports its matching `state.substate` string; `substate` is null in every non-C state.
- A roster step `[x]` with no matching Progress entry reports the literal `section-discrepancy`.
- A malformed checkbox marker produces an explicit parse error, not a coerced state.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

Track-level note: state determination is a read-only markdown parse with no on-disk effect, so steps are re-runnable and produce deterministic output for a fixed plan-dir state.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In scope:**
- The `state_determination` logic added to `.claude/scripts/workflow-startup-precheck.sh` (fills the `state` key that Track 1 stubs).
- State fixtures under `.claude/scripts/tests/` covering every phase, every State C sub-state, and the discrepancy edge.

**Out of scope (other tracks):**
- The `--mode full` scaffold, jq emit point, divergence, drift, and handoff scan — Track 1.
- The normalization commit and `actions_taken` — Track 3.
- All prose edits — Track 4 (staged).

**Contract:** the parser reproduces the precedence in `workflow.md § Startup Protocol` step 5 and the marker set in `conventions.md §1.2 § Status markers`. `state.phase` is one of `0`, `A`, `C`, `D`, `Done`; `state.substate` is populated only for State C (or the literal `section-discrepancy`) and is null otherwise.

**Dependencies:** depends on Track 1 (the script scaffold, `--mode full` plumbing, and jq emit point). Consumed by Track 4, which cites the `state` shape in the rewritten dispatch rule.
