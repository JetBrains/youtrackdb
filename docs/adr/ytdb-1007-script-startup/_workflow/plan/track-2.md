<!-- workflow-sha: 0676e2446f373e969da86da6748c91d442135161 -->
# Track 2: State determination

## Purpose / Big Picture
After this track lands, `--mode full` reports the correct resume `state` — phase 0 / A / C / D / Done, plus the five-way State C sub-state and the `section-discrepancy` edge — so the agent routes startup without reading the prose state table.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Track 2 builds the markdown state parser. It reads the plan file's `## Plan Review` / `## Checklist` / `## Final Artifacts` markers and the active track file's `## Progress` continuous log and `## Concrete Steps` roster, then reports `state.phase` and, for State C, the sub-state string. This is the one piece of the script that parses markdown rather than git output, so it is the riskiest surface and is isolated in its own track with the heaviest fixture coverage. It is authored live and extends the `full`-mode JSON that Track 1 stubs.

## Progress
- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete
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

- **State C sub-state sources** (Phase A review, A2/T1): the parser reads `## Progress` (decomposition-pending + code-review phase signal) AND the `## Concrete Steps` roster (steps-partial / `[!]` / all-done) AND the plan-file track checkbox (review-done-track-open), not `## Progress` alone. This refines design.md's TL;DR `## Progress` gloss to match the authoritative live `workflow.md` step 5 row contents and `step-implementation.md` sub-step 7.1 (roster `[x]` = primary step-done marker). design.md reconciliation deferred to Phase 4 (plan §Final Artifacts).

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 2 (6 findings T1-T6, all accepted; iter-1 reshaped the input contract, iter-2 gate VERIFIED all).
- [x] Adversarial: PASS at iteration 2 (7 findings A1-A7, all accepted; A1/A2 were blockers — `## Progress`-is-a-continuous-log + sub-states-read-the-roster; iter-2 gate VERIFIED all).
- Gate-check finding T7 (should-fix, non-blocking) deferred to Phase 4: frozen design.md §State determination glosses the sub-state source as `## Progress`; the track refines it to roster + plan-checkbox per live `workflow.md` step 5 rows + `step-implementation.md` sub-step 7.1. See plan §Final Artifacts.

## Context and Orientation

State determination today lives in `workflow.md § Startup Protocol` step 5 as a prose precedence table the agent reads and applies. This track ports that precedence into the script.

The parser reads three markdown surfaces:

1. **The plan file** — `## Plan Review` (State 0 gate), the `## Checklist` track markers (the first `[ ]` track), and `## Final Artifacts` (State D vs Done).
2. **The active track file's `## Progress`** — an append-only **continuous log** (`conventions-execution.md §2.1`), not a fixed four-checkbox block. Entries accrue per phase event, per step, and per review iteration; resume reads it as "most-recent relevant entry = current phase".
3. **The active track file's `## Concrete Steps`** — the numbered roster whose per-step `[ ]` / `[x]` / `[!]` checkbox is the authoritative step-status source (`step-implementation.md` sub-step 7.1: the roster `[x]` flip is the primary "step done" marker).

The top-level precedence the parser must reproduce:

- **State 0** first: an unchecked or entirely-absent `## Plan Review` entry — or an absent plan file at the resolved `PLAN_DIR` — means plan review has not passed.
- Otherwise walk the `## Checklist` track entries in order. The first `[ ]` track is **State A** (no `plan/track-N.md`) or **State C** (track file present).
- When every track is `[x]` or `[~]`, the `## Final Artifacts` marker chooses **State D** (`[ ]` or `[>]`) or **Done** (`[x]`).

**State A is the rare path.** `/create-plan` writes every `plan/track-N.md` at Phase 1, and the only track-file-deleting action (`SKIP_TRACK`) leaves the track `[~]`, not `[ ]`. So a first `[ ]` track with no track file has no normal producer; the common first-entry shape is State C *decomposition-pending* (track file present, `## Progress` "Review + decomposition" still `[ ]` — the exact shape this track sits in). The parser implements State A for parity and the manual-delete/corruption case, but State C decomposition-pending is the steady-state first entry. Track 4's `workflow.md` rewrite collapses the State A / C prose into routing on `state.phase`, so the live table's standalone State-A row is superseded post-merge — recorded as a cross-track note for Track 4.

The markers the parser reads are the track-roster and Phase-4 markers `conventions.md §1.2 § Status markers` defines (`[ ]` / `[x]` / `[~]` / `[>]`), **plus** the roster/Progress-only `[!]` failed-step marker (`step-implementation-recovery.md`) that §1.2's table omits but State C sub-state 3 depends on. `[!]` is recognized, not malformed.

## Plan of Work

The work proceeds top-down through the precedence, then the State C sub-state map, then the discrepancy edge, then fixtures.

1. **State 0 / A / C / D / Done precedence walk.** Read `## Plan Review` from `implementation-plan.md`; absent section, absent plan file, or unchecked entry → State 0. Otherwise walk the `## Checklist` track entries — anchored to top-level `- [<m>] Track N:` lines (column 0, no leading `>`), bounded to the region between `## Checklist` and the next `## ` heading, so a checkbox token inside a blockquoted episode or fenced template is not miscounted. The first `[ ]` track resolves to State A (no `plan/track-N.md`) or State C (track file present). All tracks `[x]`/`[~]` → read `## Final Artifacts` (heading plus its first top-level checkbox) and resolve State D vs Done.
2. **State C sub-state map.** Map to the five sub-states from their actual sources, mirroring `workflow.md` step 5: (1) *decomposition-pending* — `## Progress` "Review + decomposition" is `[ ]` (short-circuit; do not quantify over the still-empty roster); (2) *steps-partial* — roster has both `[x]` and `[ ]` steps; (3) *failed-step* — roster contains `[!]`; (4) *steps-done-review-pending* — all roster steps `[x]`, code-review Progress entry `[ ]`/partial; (5) *review-done-track-open* — all roster steps `[x]`, code-review Progress `[x]`, and the plan-file track checkbox still `[ ]`. Sub-states 2-4 read the `## Concrete Steps` roster; sub-state 5 also reads the plan-file track checkbox; only sub-state 1 is a pure `## Progress` read.
3. **Section-discrepancy edge.** Join roster step to Progress entry by **step number N**: a roster line `^N.` flipped `[x]` whose `## Progress` log has no word-boundary `Step N` entry (a `[!]` failed-step Progress entry counts as present-for-N) emits the literal `section-discrepancy`, so the agent reconciles from the `## Episodes` block.
4. **State fixtures.** Construct plan-dir fixtures at each phase and each State C sub-state, plus the discrepancy edge, the blockquote-anchoring case, and the parse-error path; assert the emitted `state` object. At least one fixture is cut from a real on-disk track file (the continuous-log `## Progress` + numbered roster of a completed or mid-flight track), not an idealized four-checkbox shape, so coverage tracks reality. Fixtures reuse Track 1's `GitFixture.plan_artifact` with composed multi-section bodies (no new helper) and live under `docs/adr/main/_workflow/`; `GitFixture` is needed only because `PLAN_DIR` is branch-derived, not because state determination touches git.

Ordering constraints and invariants to preserve: the walk reads the markers `conventions.md §1.2 § Status markers` defines plus the `[!]` roster/Progress failed-step marker; an *unrecognized* glyph (e.g. `[X]`, `[ x]`, `[]`) is reported as an explicit parse error (stderr naming the section/line + non-zero exit, before any `state` is emitted), never silently coerced — `[!]` is recognized, so it is not a parse error. State C sub-state detection reads only the **active** track's file (both `## Progress` and `## Concrete Steps`) plus the one already-loaded plan-file track checkbox, not every track file, to keep the parse cheap. An entirely-absent `## Plan Review` is treated identically to an unchecked entry (State 0).

## Concrete Steps

1. Checkbox helper + parse-error guard + State 0/A/C/D/Done top-level walk + `STATE_JSON` wiring — add `determine_state` and a checkbox-reading helper recognizing `[ ]`/`[x]`/`[~]`/`[>]`/`[!]` (an unrecognized glyph → stderr naming the section/line + non-zero exit before any `state` is emitted; `[!]` is recognized, not an error); top-level precedence: `## Plan Review` absent/`[ ]`/absent-plan-file → State 0; otherwise walk `## Checklist` anchored to top-level `- [<m>] Track N:` lines (column 0, blockquote/fence-excluded, bounded between `## Checklist` and the next `## `) for the first `[ ]` track → State A (no `plan/track-N.md`) or State C (track file present, `substate` null at this step); all tracks `[x]`/`[~]` → `## Final Artifacts` (heading + first top-level checkbox) → State D (`[ ]`/`[>]`) or Done (`[x]`); set `STATE_JSON={phase,substate:null}` and wire `determine_state` into the `full` dispatch before `emit_json`; fixtures for State 0 (three shapes incl. absent-plan), State A, State C top-level, State D, Done, blockquote-anchoring, and the malformed-marker parse-error, reusing `GitFixture.plan_artifact` under `docs/adr/main/_workflow/` — risk: medium (new observable behavior — the `state` key — plus a new parse-error path on the precheck component; no HIGH trigger)  [ ]
2. State C sub-state map (five sub-states, joint read) — extend `determine_state` for State C, reading `## Progress` as a continuous log (most-recent relevant entry) and the `## Concrete Steps` roster: (1) decomposition-pending = Progress "Review + decomposition" `[ ]`, short-circuit before quantifying the roster; (2) steps-partial = roster mixes `[x]` and `[ ]`; (3) failed-step = roster contains `[!]`; (4) steps-done-review-pending = all roster steps `[x]` + code-review Progress `[ ]`/partial; (5) review-done-track-open = all roster steps `[x]` + code-review Progress `[x]` + plan-file track checkbox `[ ]`; fixtures for each of the five sub-states, the decomposition-pending empty-roster vacuous-truth guard, and at least one fixture cut from a real on-disk track file (continuous-log Progress + numbered roster) — risk: medium (the trickiest logic; new observable behavior; joint multi-surface read)  [ ]
3. Section-discrepancy edge — extend `determine_state` to join roster step to Progress entry by step number N: a roster line `^N.` flipped `[x]` whose `## Progress` log has no word-boundary `Step N` entry → `substate: "section-discrepancy"` (a `[!]` failed-step Progress entry counts as present-for-N); fixtures for discrepancy-present, a healthy track whose Progress interleaves phase-checkpoint lines between step lines (no false-positive), and the `[!]`-counts-as-present case — risk: medium (subtle join logic; the false-positive/false-negative hazard flagged in Phase A review)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

Track-level behavioral acceptance:

- An absent or `[ ]` `## Plan Review` entry — or an absent plan file at the resolved `PLAN_DIR` — reports `state.phase="0"`, regardless of track checkboxes.
- A passed `## Plan Review` with the first `[ ]` track lacking a `plan/track-N.md` reports `state.phase="A"`; with the track file present it reports `state.phase="C"`. State C is the steady-state case; State A is exercised only by a deleted/absent track file.
- The common first-entry shape — first `[ ]` track present on disk with `## Progress` "Review + decomposition" `[ ]` and an empty/placeholder `## Concrete Steps` — reports `state.phase="C"` with the decomposition-pending sub-state, and does NOT coerce the empty roster to an all-steps-done sub-state.
- All tracks `[x]`/`[~]` with `## Final Artifacts` `[ ]`/`[>]` reports `state.phase="D"`; with `[x]` it reports `state.phase="Done"`.
- Each of the five State C sub-states reports its matching `state.substate` string from its actual source (Progress log for decomposition-pending; `## Concrete Steps` roster for steps-partial / `[!]` / all-done; plan-file track checkbox for review-done-track-open); `substate` is null in every non-C state.
- A roster step `[x]` whose `## Progress` log has no `Step N` entry reports the literal `section-discrepancy`; a healthy track whose Progress log interleaves phase-checkpoint lines between step lines does NOT false-positive.
- A `## Checklist` parse anchors to top-level track lines: a `> - [x]` checkbox inside a blockquoted episode is not counted as a track entry.
- An unrecognized checkbox glyph (`[X]`, `[ x]`, `[]`) produces an explicit parse error (stderr + non-zero exit), not a coerced state; a `[!]` failed-step marker is recognized and drives the failed-step sub-state.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

Track-level note: state determination is a read-only markdown parse with no on-disk effect, so steps are re-runnable and produce deterministic output for a fixed plan-dir state. It does read `$PLAN_DIR`, which Track 1 resolves to `docs/adr/<branch>` from the current branch, so a state fixture must set a git branch (via `GitFixture`) for `PLAN_DIR` to resolve even though no git history is read; an unresolved or empty `PLAN_DIR` finds no plan file and collapses to State 0.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In scope:**
- The state-determination logic added to `.claude/scripts/workflow-startup-precheck.sh`, setting `STATE_JSON` (the `{phase, substate}` object) and wired into the `full`-mode dispatch (`emit_json`'s `full` branch already reads `${STATE_JSON:-null}`).
- State fixtures under `.claude/scripts/tests/` covering every phase, every State C sub-state, the discrepancy edge, the blockquote-anchoring case, and the parse-error path — at least one cut from a real on-disk track file.

**Out of scope (other tracks):**
- The `--mode full` scaffold, jq emit point, divergence, drift, and handoff scan — Track 1.
- The normalization commit and `actions_taken` — Track 3.
- All prose edits — Track 4 (staged).

**Contract:** the parser reproduces the precedence in `workflow.md § Startup Protocol` step 5. Inputs: the plan file's `## Plan Review` / `## Checklist` / `## Final Artifacts` markers, and the active track file's `## Progress` continuous log and `## Concrete Steps` roster (the roster is the authoritative step-status source). It reads the `conventions.md §1.2 § Status markers` set (`[ ]` / `[x]` / `[~]` / `[>]`) plus the roster/Progress `[!]` failed-step marker. `state.phase` is one of `0`, `A`, `C`, `D`, `Done`; `state.substate` is populated only for State C (one of the five sub-state strings) or the literal `section-discrepancy`, and is null otherwise. The enum stays closed: an unrecognized marker glyph is an explicit error on stderr with a non-zero exit before any `state` is emitted, not a sixth `phase` value.

**Dependencies:** depends on Track 1 (the script scaffold, `--mode full` plumbing, jq emit point, and the `GitFixture` test builder). Consumed by Track 4, which cites the `state` shape in the rewritten dispatch rule and collapses the live `workflow.md` State A / C prose into routing on `state.phase` (so the live table's standalone State-A row is superseded post-merge).

## Base commit
fa84fa2598223b328fd5b6561fb85bba9f50a936
