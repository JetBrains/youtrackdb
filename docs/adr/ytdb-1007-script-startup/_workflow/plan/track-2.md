<!-- workflow-sha: 0676e2446f373e969da86da6748c91d442135161 -->
# Track 2: State determination

## Purpose / Big Picture
After this track lands, `--mode full` reports the correct resume `state` — phase 0 / A / C / D / Done, plus the five-way State C sub-state and the `section-discrepancy` edge — so the agent routes startup without reading the prose state table.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Track 2 builds the markdown state parser. It reads the plan file's `## Plan Review` / `## Checklist` / `## Final Artifacts` markers and the active track file's `## Progress` continuous log and `## Concrete Steps` roster, then reports `state.phase` and, for State C, the sub-state string. This is the one piece of the script that parses markdown rather than git output, so it is the riskiest surface and is isolated in its own track with the heaviest fixture coverage. It is authored live and extends the `full`-mode JSON that Track 1 stubs.

## Progress
- [x] 2026-06-03T04:03Z [ctx=info] Review + decomposition complete
- [x] 2026-06-03T04:29Z [ctx=safe] Step 1 complete (commit 1dd90397d6)
- [x] 2026-06-03T04:44Z [ctx=safe] Step 2 complete (commit d94ca3f4f2)
- [x] 2026-06-03T04:54Z [ctx=safe] Step 3 complete (commit 4f7370ea88)
- [x] 2026-06-03T04:54Z [ctx=safe] Step implementation
- [x] 2026-06-03T05:32Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)
- [x] 2026-06-03T06:42Z [ctx=info] Track complete

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- **`track` field omitted from the `state` object** (Step 1): design.md's frozen state-object example (line 146) shows `{phase, track, substate}`, but the shipped object is `{phase, substate}` per the track's Interfaces contract. The active track number is computed internally for the `plan/track-N.md` probe but not emitted, so Track 4's dispatch rule must re-derive the active track from the checklist unless a later track adds the field. Phase 4 design-final reconciliation candidate, alongside the existing T7 contract restatement (plan §Final Artifacts). See Episodes §Step 1.
- **Roster reads must run inline, not in a subshell** (Step 1): `parse_error` calls `exit`, so a helper that reads `## Concrete Steps` / `## Progress` markers through `$(...)` swallows the exit in a subshell and emits a state instead of failing. Step 2's sub-state reads and Step 3's discrepancy join run inline in the main shell, reusing the established `classify_marker` helper (which already maps `[!]` to "fail"). See Episodes §Step 1.
- **Step 3 needs per-step `(N, status)` pairs from the roster** (Step 2): `roster_scan` aggregates has-fail / has-todo / step-count flags, not per-step number→status. Step 3's section-discrepancy join (a roster step flipped `[x]` whose `## Progress` log lacks a `Step N` entry) needs per-step pairs, so it extends `roster_scan` or adds a sibling scan. Two idioms established here carry directly into Step 3: the roster status checkbox anchors to the post-`risk:` tail (not the first `[...]`, which may be inline description prose), and marker reads run inline, never in a `$(...)`. See Episodes §Step 2.
- **Sub-state values are slug strings, not the verbose row gloss** (Step 2): the shipped `state.substate` values are the slugs `decomposition-pending` / `steps-partial` / `failed-step` / `steps-done-review-pending` / `review-done-track-open` (plus the literal `section-discrepancy` from Step 3). design.md's frozen state-object example (line 146) glosses these with `workflow.md`'s longer row text ("Steps [x], code review [x], track still [ ] in plan"). Track 4's dispatch rule consumes the slug form. This is part of the T7 Phase 4 design-final reconciliation (plan §Final Artifacts), now spanning the substate-string form as well as its sources. See Episodes §Step 2.

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
- [x] Phase C track-level code review: PASS at iteration 1/3. 4 workflow reviewers (consistency, hook-safety, context-budget, writing-style); baseline skipped on the workflow-only diff; 0 blockers, 0 deferred findings. Six findings applied in one Review fix (`c3e4508f26`), all gate-check VERIFIED: WH1+WH2 (should-fix behavior) made the closed-enum parse-error contract total over the bounded `## Checklist` region and the section-heading match trailing-whitespace-tolerant at all five sites, each with a load-bearing regression test (harness 62 → 65); WC1 (should-fix consistency) extended §Final Artifacts to enumerate all three design.md state-object reconciliations (sub-state sources + omitted `track` field + slug-string substate form); WS1 (should-fix style) fixed a Step 3 episode em-dash; WC2+WH3 (suggestions) corrected the lockstep-vs-split-write comment framing and documented the roster parens-only annotation assumption.

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

1. Checkbox helper + parse-error guard + State 0/A/C/D/Done top-level walk + `STATE_JSON` wiring — add `determine_state` and a checkbox-reading helper recognizing `[ ]`/`[x]`/`[~]`/`[>]`/`[!]` (an unrecognized glyph → stderr naming the section/line + non-zero exit before any `state` is emitted; `[!]` is recognized, not an error); top-level precedence: `## Plan Review` absent/`[ ]`/absent-plan-file → State 0; otherwise walk `## Checklist` anchored to top-level `- [<m>] Track N:` lines (column 0, blockquote/fence-excluded, bounded between `## Checklist` and the next `## `) for the first `[ ]` track → State A (no `plan/track-N.md`) or State C (track file present, `substate` null at this step); all tracks `[x]`/`[~]` → `## Final Artifacts` (heading + first top-level checkbox) → State D (`[ ]`/`[>]`) or Done (`[x]`); set `STATE_JSON={phase,substate:null}` and wire `determine_state` into the `full` dispatch before `emit_json`; fixtures for State 0 (three shapes incl. absent-plan), State A, State C top-level, State D, Done, blockquote-anchoring, and the malformed-marker parse-error, reusing `GitFixture.plan_artifact` under `docs/adr/main/_workflow/` — risk: medium (new observable behavior — the `state` key — plus a new parse-error path on the precheck component; no HIGH trigger)  [x] commit: 1dd90397d6
2. State C sub-state map (five sub-states, joint read) — extend `determine_state` for State C, reading `## Progress` as a continuous log (most-recent relevant entry) and the `## Concrete Steps` roster: (1) decomposition-pending = Progress "Review + decomposition" `[ ]`, short-circuit before quantifying the roster; (2) steps-partial = roster mixes `[x]` and `[ ]`; (3) failed-step = roster contains `[!]`; (4) steps-done-review-pending = all roster steps `[x]` + code-review Progress `[ ]`/partial; (5) review-done-track-open = all roster steps `[x]` + code-review Progress `[x]` + plan-file track checkbox `[ ]`; fixtures for each of the five sub-states, the decomposition-pending empty-roster vacuous-truth guard, and at least one fixture cut from a real on-disk track file (continuous-log Progress + numbered roster) — risk: medium (the trickiest logic; new observable behavior; joint multi-surface read)  [x] commit: d94ca3f4f2
3. Section-discrepancy edge — extend `determine_state` to join roster step to Progress entry by step number N: a roster line `^N.` flipped `[x]` whose `## Progress` log has no word-boundary `Step N` entry → `substate: "section-discrepancy"` (a `[!]` failed-step Progress entry counts as present-for-N); fixtures for discrepancy-present, a healthy track whose Progress interleaves phase-checkpoint lines between step lines (no false-positive), and the `[!]`-counts-as-present case — risk: medium (subtle join logic; the false-positive/false-negative hazard flagged in Phase A review)  [x] commit: 4f7370ea88

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit 1dd90397d6, 2026-06-03T04:29Z [ctx=safe]
**What was done:** The `full`-mode `state` field now carries the top-level
resume-state walk that Track 1 stubbed as `null`. `determine_state`
reproduces the `workflow.md § Startup Protocol` step 5 precedence: State 0
(absent plan file, absent `## Plan Review`, or unchecked first Plan-Review
checkbox), then the first `[ ]` track in `## Checklist` resolving to State A
(no `plan/track-N.md`) or State C (track file present), and an all-`[x]`/`[~]`
checklist routing to State D or Done on the `## Final Artifacts` marker. A
checkbox-classifying helper recognizes `[ ]`/`[x]`/`[~]`/`[>]`/`[!]`; an
unrecognized glyph triggers `parse_error`, which names the section and line on
stderr and exits non-zero before any JSON. The `## Checklist` walk anchors to
column-0 `- [<m>] Track N:` lines, excludes blockquoted and fenced regions, and
bounds itself between `## Checklist` and the next `## `. `STATE_JSON={phase,
substate:null}` wires into the `full` dispatch ahead of `emit_json`. 13 net-new
fixtures cover State 0 (three shapes including absent-plan), State A, State C
(three), State D (two), Done, blockquote/fence anchoring, the malformed-marker
parse error across all three reading sites, the recognized `[!]` case, and a
real on-disk track-file shape. Suite: 44/44.

**What was discovered:** Two hazards were caught and fixed before commit.
First, wrapping the section-checkbox helper in a `$(...)` command substitution
ran `parse_error`'s `exit` in a subshell, so a malformed `## Plan Review` /
`## Final Artifacts` glyph printed the diagnostic yet the script still exited 0
with State 0; the helper was refactored to set a script-scoped `SECTION_TOKEN`
with no command substitution, so the exit terminates the whole script. Second,
the initial checkbox glob `"- ["?"] "` matched exactly one bracket character
and silently dropped empty (`[]`) and multi-char (`[ x]`) bodies into State 0;
both match sites were broadened to `"- ["*"] "` so `[]`/`[ x]`/`[X]` route to
`parse_error` per the Validation acceptance lines. Cross-track: design.md's
frozen state-object example (line 146) carries a `track` field that the shipped
`{phase, substate}` object omits per the authoritative track contract — the
active track number is computed internally for the `plan/track-N.md` probe but
not emitted. Track 4 consumes the `state` shape, so Phase 4 design-final must
reconcile the contract. See Surprises & Discoveries.

**What changed from the plan:** none. The implementation follows the track's
Interfaces contract (`{phase, substate}`) exactly. The design.md `track`-field
divergence is a frozen-doc reconciliation deferred to Phase 4, not a plan
deviation introduced here.

**Key files:**
- `.claude/scripts/workflow-startup-precheck.sh` (modified)
- `.claude/scripts/tests/test_workflow_startup_precheck.py` (modified)

**Critical context:** none

### Step 2 — commit d94ca3f4f2, 2026-06-03T04:44Z [ctx=safe]
**What was done:** State C's arm in `determine_state` now computes the five
sub-state slugs through three inline helpers: `progress_entry_token` (the
most-recent matching `## Progress` continuous-log entry's checkbox),
`roster_scan` (classifies `## Concrete Steps` roster status checkboxes into
has-fail / has-todo / step-count flags), and `determine_c_substate` (the
joint-read precedence). The map mirrors `workflow.md § Startup Protocol`
step 5: decomposition-pending short-circuits on the Progress "Review +
decomposition" entry before quantifying the roster; failed-step (any `[!]`)
precedes steps-partial (any remaining `[ ]`); an all-done roster splits on the
most-recent code-review Progress entry into steps-done-review-pending versus
review-done-track-open (the latter also requiring the plan-file track checkbox
`[ ]`). State C's `STATE_JSON` is assembled with jq, holding the
one-contract-home discipline. 12 net-new fixtures cover each sub-state, the
decomposition-pending empty-roster vacuous-truth guard, and a real on-disk
track-file shape; the five pre-existing State C top-level tests that asserted
`substate:null` were updated. Suite: 56/56.

**What was discovered:** The roster status checkbox cannot be read as the
first `[...]` on the line. A roster description can carry inline
backtick-bracketed tokens (this branch's own track-1/track-2 roster lines embed
`[ ]`/`[x]` in their prose), so the status checkbox anchors to the post-`risk:`
tail instead, matching the canonical immutable-roster grammar
(`conventions-execution.md §2.1`). A live-branch smoke run (`--mode full`
against this branch's real plan and track files) reported
`{phase:C, substate:steps-partial}`, confirming behavior parity (S1) against
real artifacts rather than synthetic fixtures alone.

**What changed from the plan:** none. The implementation follows Plan of Work
step 2 and the Interfaces contract (slug-string sub-states). One implicit point
was made explicit and documented inline: the steps-partial predicate is "any
`[ ]` step remains" (covering both the mixed shape the step text names and the
all-`[ ]` just-decomposed shape), and failed-step is evaluated before
steps-partial so a both-failed-and-partial roster routes to the failed resume,
matching `workflow.md` step 5's `[!]` row.

**Key files:**
- `.claude/scripts/workflow-startup-precheck.sh` (modified)
- `.claude/scripts/tests/test_workflow_startup_precheck.py` (modified)

**Critical context:** none

### Step 3 — commit 4f7370ea88, 2026-06-03T04:54Z [ctx=safe]
**What was done:** `determine_state`'s State C arm gained the
section-discrepancy edge: a roster step flipped `[x]` whose number N has no
word-boundary `Step N` entry in `## Progress` now reports the literal
`section-discrepancy`. `roster_scan` gained a per-step `<N>:<token>` pairs
output (`ROSTER_PAIRS`); a new `progress_step_numbers` scan collects the step
numbers named by `Step N` Progress entries (any glyph, so a `[!]` failed entry
counts as present-for-N); a `step_num_in_progress` helper does the exact
numeric-equality membership test. The discrepancy check sits before the
failed/partial/done split, so the inconsistency signal overrides normal
routing. Six net-new fixtures cover discrepancy-present, the
interleaved-phase-checkpoint false-positive guard, the `[!]`-counts-as-present
case, the exact-number join (`Step 12` is not step 1), the skip-step exclusion,
and a real track-file shape. Suite: 62/62.

**What was discovered:** Five State C sub-state fixtures added in Step 2
abbreviated their `## Progress` logs and omitted the per-step `Step N complete`
entries that a real track carries in lockstep with each roster `[x]` flip.
Under the new join those abbreviated shapes were genuine discrepancy cases, so
they failed. The fix made them faithful to the real lockstep shape (adding the
matching `Step N complete` entries) rather than weakening the edge, so each
fixture still tests its intended sub-state. A live-branch smoke run
(`--mode full` against this branch's real plan and track-2.md) reported
`{phase:C, substate:steps-partial}` (steps 1+2 done with their Progress
entries, step 3 `[ ]`), confirming behavior parity (S1) with no false-positive
discrepancy on real artifacts.

**What changed from the plan:** none. The implementation follows Plan of Work
step 3 and the Interfaces contract (the literal `section-discrepancy`
substate). Extending `roster_scan` for the per-step pairs, rather than adding a
sibling scan, was the option the track's Surprises note already sanctioned.

**Key files:**
- `.claude/scripts/workflow-startup-precheck.sh` (modified)
- `.claude/scripts/tests/test_workflow_startup_precheck.py` (modified)

**Critical context:** none

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
