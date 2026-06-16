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
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-16T06:28Z [ctx=info] Review + decomposition complete

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. -->

- **Phase-A review widened the consumer inventory.** The iteration-1 technical,
  risk, and adversarial reviews found five consumers of the removed plan
  sections (`## Plan Review` / `## Final Artifacts`) outside the original
  16-file scope: `workflow-drift-check.md` (Phase-4 migration-skip reads
  `## Final Artifacts`), `skills/execute-tracks/SKILL.md` and
  `skills/review-plan/SKILL.md` (State-0 end-session read and the D7 write
  side), `workflow/structural-review.md` (the orchestration doc, distinct from
  the in-scope `prompts/structural-review.md`), and `plan-slim-rendering.md`
  ("Keep `## Final Artifacts` verbatim"). All five are now in scope.
- **Cross-track seam: `conventions.md` §1.7(c) read-side.** §1.7(c) is the
  normative spec the re-pointed `implementer-rules` / `step-implementation`
  consumers cite, and it still says "the implementer reads `### Constraints`."
  Leaving it stale while the consumers read the ledger makes the spec and its
  consumers contradict. `conventions.md` is a Track-1 file, but the §1.7(c)
  read-side amendment is carved into this track's scope (narrowly) so the spec
  stays coherent with the consumers Track 2 re-points.

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
  the reader inventory must be exhaustive. The §1.7-marker and tier-line readers
  are `inline-replanning`, `track-review`, `create-final-design`,
  `consistency-review`, the three §1.7(l) review prompts
  (`technical`/`risk`/`adversarial`), the implementer §1.7(c) gate
  (`step-implementation`, `implementer-rules`), the two gate-recheck prompts
  that carry the same standalone §1.7(b) staged-read block
  (`dimensional-review-gate-check`, `review-gate-verification`),
  `track-code-review` (the same standalone §1.7(b) marker-read block), and
  `implementation-review` (the Phase-2 pass-matrix tier read). The Phase-A
  review added the consumers of the *removed plan sections* the original
  inventory missed: `workflow-drift-check` (Phase-4 skip on `## Final
  Artifacts`), the `execute-tracks` / `review-plan` SKILLs (State-0 read, D7
  write side), `structural-review` (orchestration doc), and
  `plan-slim-rendering` (`## Final Artifacts` render rule). The re-pointed
  marker read is ledger-first with a plan-`### Constraints`-scan fallback, so an
  in-flight pre-ledger workflow-modifying branch is still detected (it carries
  the marker but no ledger).
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
- **Risks/Caveats**: of D8's two mitigation arms, the "keep the greppable
  `**PAUSED` prefix" arm is infeasible as-built. Track 1's ledger `paused` field
  is a bare space-rejecting token, so it cannot hold the literal `**PAUSED ` the
  `^\*\*PAUSED `-anchored recovery grep matches, and a ledger line starts with
  `[<ISO>]`. So this track takes the other arm: extend the recovery grep in
  `mid-phase-handoff` to scan the ledger for `paused=` events. Track 1 writes
  but never reads `paused` (`determine_state` reads only `phase`/`track`), so
  the "machine-read by `determine_state`" property the rationale above claims is
  not delivered by this track — `determine_state` is Track 1's precheck, out of
  scope. Recoverability comes from the extended grep plus the unchanged handoff
  file (found by the `handoffs` glob), not from `determine_state` reading
  `paused`. The ledger is append-only with no unpause op, so the recovery scan
  must treat a `paused=` event as resolved once its handoff file is gone (the
  deletion is the clear) or once a later forward-phase append supersedes it;
  otherwise a resolved pause re-discovers as a recurring false Abort.
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

- [x] Technical: PASS at iteration 3 (6 findings, 6 accepted). T1-T4 (iter 1)
  and T5-T6 (iter 2) were the same inventory / spec-completeness class; all
  fixed. The iter-3 gate-check re-greped the consumer space and confirmed the
  22-file inventory exhaustive (0 misses).
- [x] Risk: PASS at iteration 2 (9 findings, 9 accepted). Two iter-1 blockers
  (R1 incomplete reader inventory, R2 missing in-flight fallback) drove the
  scope widening and the ledger-first-with-`### Constraints`-fallback clause;
  R8/R9 (iter 2) folded into the same fixes.
- [x] Adversarial: PASS at iteration 2 (7 findings, 7 accepted). A1 (blocker,
  `plan-slim-rendering` missed consumer) joined the inventory fix; A7 (iter 2)
  added the D8 pause clear-on-resolution path. Scope/sizing, cross-track-episode
  reality, and invariant challenges all survived on the revised track.

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
- **`.claude/workflow/workflow.md`**: Track 1 migrated the Startup Protocol,
  Session Lifecycle, and Final Artifacts prose to the ledger model but left
  references keyed on the removed plan `## Plan Review` / `## Final Artifacts`
  sections, marked "Track 2 re-points this". Track 2 finishes the re-point onto
  the ledger (the spots are pinned by line in Plan-of-Work step 6).
- **`.claude/workflow/workflow-drift-check.md`**: the Phase-4 migration-skip
  (`:216`) reads the plan's `## Final Artifacts [>]/[x]`; under D5/D7 that
  section is gone in every tier, so the skip can never fire. Re-base it on the
  ledger `phase == "D"`/`"Done"` tail (Track 1's contract).
- **`.claude/skills/execute-tracks/SKILL.md`**: the State-0 end-session bullet
  (`:89`) keys on `## Plan Review` being `[x]`; re-point to the ledger review
  state. (The parallel copy in `workflow.md` is step 6.)
- **`.claude/skills/review-plan/SKILL.md`**: step 5 (`:97`) overwrites the
  plan's `## Plan Review` section — the *write side* of D7. Re-point to write
  `plan-review.md` and the ledger review state.
- **`.claude/workflow/structural-review.md`**: the orchestration doc (distinct
  from the in-scope `prompts/structural-review.md`) names the plan `## Plan
  Review` section as the durable audit trace (`:167`); re-point to
  `plan-review.md`.
- **`.claude/workflow/plan-slim-rendering.md`**: the render rule "Keep the
  `## Final Artifacts` section verbatim" (`:162`) and its pre-Checklist
  Goals/Non-Goals list (`:138-140`) name plan content D5 relocates; adapt to the
  thinned plan.
- **`.claude/workflow/conventions.md` (§1.7(c) read-side only)**: a Track-1
  file, carved into scope narrowly. §1.7(c) is the normative spec the re-pointed
  implementer consumers cite; it still says "the implementer reads
  `### Constraints`." Amend the read-side to ledger-first with the
  plan-`### Constraints` fallback so the spec matches the re-pointed consumers.

## Plan of Work

Order the edits so the highest-traffic state contract (the §1.7 marker read)
is re-pointed once and the rest follow. Each edit consumes a Track 1 contract.

1. **Phase-2 audit → `plan-review.md` (D7).** `implementation-review.md` writes
   the audit summary to the new document and records review *state* in the
   ledger; `consistency-review.md` reads the ledger tier line and keeps the
   degenerate-unreadable-tier branch; `prompts/structural-review.md` drops the
   `minimal` pass and adapts the bloat checks to the thinned plan. Re-point the
   two other audit-trace writers the Phase-A review surfaced: the
   `workflow/structural-review.md` orchestration doc (`:167`, distinct from the
   prompt) and `skills/review-plan/SKILL.md` (`:97`, the D7 write side) both name
   the plan `## Plan Review` section — move them to `plan-review.md` and the
   ledger review state. Re-point `skills/execute-tracks/SKILL.md` (`:89`) State-0
   end-session read off the `## Plan Review` checkbox onto the ledger.
2. **Tier-line + §1.7(c)/(l) marker readers → ledger (D4).** Re-point the marker
   read in the three §1.7(l) review prompts, the implementer §1.7(c) gate
   (`step-implementation`, `implementer-rules`), the two gate-recheck prompts
   that carry the same standalone §1.7(b) staged-read block
   (`dimensional-review-gate-check`, `review-gate-verification`), and
   `track-code-review.md` — which carries that same standalone §1.7(b)
   marker-read block (it is in scope below for the completion episode, but its
   marker read must re-point too, or on a `minimal` workflow-modifying branch the
   block goes inert and the Phase-C reviewer reads live instead of staged). Move
   all of these off the plan's `### Constraints` to the ledger. Re-point the
   tier-line reads to the ledger: `track-review`, `create-final-design`,
   `inline-replanning`'s descriptive tier-line *read* prose, and
   `implementation-review.md`'s Phase-2 pass-matrix tier read (`:184-193`, a
   distinct orchestrator-level read from the `consistency-review` one). Each
   re-pointed marker read is **ledger-first with a plan-`### Constraints`-scan
   fallback**: read `s17` from the ledger; if no ledger (an in-flight pre-ledger
   workflow-modifying branch), fall back to the develop-era `### Constraints`
   stable-prefix scan, mirroring `determine_state`'s own two-level pattern. Amend
   `conventions.md` §1.7(c) read-side (narrow Track-1-file carve-out) to describe
   this ledger-first read, and re-frame its first signal label ("Constraints
   declaration") to the ledger-`s17`-first read, so the normative spec matches
   its consumers. Also realize D14's Track-2 half (Track 1 adds
   `.claude/scripts/**` to the §1.7 staged prefix set; Track 2 makes the runtime
   enforce and promote it): (i) extend the implementer-rules §1.7(e) pre-commit
   gate to refuse a live `.claude/scripts/**` edit on a workflow-modifying
   branch, outside the Phase-4 promotion commit (matching the existing
   three-prefix exception); (ii) extend the `create-final-design.md` Phase-4
   promotion so its pre-promotion divergence check and its `git add` both include
   `.claude/scripts` — the `cp -r .claude/.` already copies the staged scripts
   onto live, but without the wider `git add` they are copied yet never
   committed, so they never reach develop.
3. **Pause boundaries → ledger events (D8).** Route the mid-phase-handoff
   Phase-2/State-0 and Phase-4 secondary markers to a ledger `paused` event.
   Take D8's second mitigation arm: extend the recovery grep in
   `mid-phase-handoff` to scan the ledger for `paused=` events (the "keep the
   `**PAUSED` prefix" arm is infeasible — the bare-token `paused` field cannot
   hold `**PAUSED `; see the D8 Risks/Caveats note). The handoff file the
   recovery path needs is unchanged and still found by the `handoffs` glob; the
   ledger `paused` event triggers re-checking it. Specify the **clear-on-
   resolution** path: the ledger is append-only, so a resolved pause's event is
   never deleted. The recovery scan treats a `paused=` event as live only while
   its handoff file is still present — handoff-file deletion (the existing
   resolved-pause signal) is the clear — so a resolved pause is not re-discovered
   as a recurring false Abort next session. Equivalently, ignore a `paused=`
   event followed by a later forward-phase append.
4. **Minimal→lite/full escalation (D11).** `inline-replanning` writes the
   upgraded tier as a ledger event (an `--append-ledger` call), in
   materialize-then-write order: first materialize `implementation-plan.md` (and
   `design.md` for `full`) the source `minimal` tier never had, then append the
   upgraded tier. Re-point `inline-replanning`'s step-6 `## Plan Review` reset
   (`:153`) onto the ledger review state / `plan-review.md`, since D7 moves
   review state off the plan.
5. **Completion episode → track file.** `track-code-review` writes the track
   completion episode canonical to the track file's `## Episodes`; the
   `lite`/`full` Checklist keeps a one-line summary and pointer. Re-point its
   deferred-write reconciliation resume signal (the "approved vs not" check,
   `~:1434-1440`) off the plan-file track `[x]` onto the ledger `phase` tail for
   the `minimal` (no-plan) case.
6. **Finish the `workflow.md` and `plan-slim-rendering.md` re-points.** In
   `workflow.md`, re-point the spots Track 1 left keyed on the removed plan
   sections — pinned by line so the implementer works the enumeration, not the
   "Track 2 re-points this" flag grep (the staged file carries only 2 flags for
   more spots): the Phase-4 start/resume signal and track-completion-episode
   writer (`:350`, `:743`), the State-0 routing reference to `## Plan Review`
   (`:310`), the When-to-end-a-session State-0 bullet (`:417`), and the
   implementation-review loader note (`:768`). Much of this is deleting stale
   forward-pointers and stale-checkbox references, not re-routing — Track 1
   already moved the model. Re-base `workflow-drift-check.md`'s Phase-4
   migration-skip (`:216`) on the ledger `phase == "D"`/`"Done"` tail. Adapt
   `plan-slim-rendering.md`'s "Keep `## Final Artifacts` verbatim" rule (`:162`)
   and its Goals/Non-Goals pre-Checklist list (`:138-140`) to the thinned plan.

Invariant to preserve (stated as the outcome): a workflow-modifying branch is
detected **identically** — the true/false verdict is unchanged. Only the read
*location* and *mechanism* move, from a stable-prefix substring match of the
`### Constraints` marker sentence to a presence/equality test of the ledger
`s17` token, with the `### Constraints` scan kept as the pre-ledger fallback.
The bare-token `s17` form drops the develop-era forward-compat path-prefix-list
growth, which the token does not need (it carries no path list).

## Concrete Steps

1. §1.7 marker + tier control-state reads → ledger (ledger-first,
   `### Constraints` fallback) plus the D14 script-gate/promotion half — re-point
   the §1.7(b)/(c)/(l) marker read in `prompts/technical-review.md`,
   `prompts/risk-review.md`, `prompts/adversarial-review.md`,
   `prompts/dimensional-review-gate-check.md`,
   `prompts/review-gate-verification.md`, and the implementer gate
   (`step-implementation.md`, `implementer-rules.md`) off the plan
   `### Constraints` onto the ledger `s17` field, ledger-first with the
   `### Constraints` stable-prefix fallback for in-flight pre-ledger branches;
   re-point the tier reads in `track-review.md`, `prompts/consistency-review.md`,
   and `prompts/create-final-design.md` the same way; amend `conventions.md`
   §1.7(c) read-side and its "Constraints declaration" signal label to the
   ledger-first read; and realize D14 (extend the `implementer-rules` §1.7(e)
   pre-commit gate and the `create-final-design` Phase-4 divergence check +
   `git add` to cover `.claude/scripts/**`). Eleven files. — risk: high (workflow
   machinery: edits the §1.7 staging convention, a load-bearing control-flow
   protocol)  [ ]
2. Auto-resume / review-state / escalation / completion control reads → ledger —
   `implementation-review.md` writes the audit to `plan-review.md` and the review
   *state* to the ledger and reads the tier from the ledger; `inline-replanning.md`
   reads/writes the tier on the ledger, materializes the dropped plan/design on a
   `minimal` upgrade (materialize-then-write order), and re-points its
   `## Plan Review` reset; `track-code-review.md` re-points its standalone §1.7(b)
   marker block, writes the completion episode to the track file, and reads the
   ledger `phase` tail for the deferred-write resume signal; `mid-phase-handoff.md`
   records pauses as ledger `paused` events, extends the recovery grep to the
   ledger, and adds the clear-on-resolution rule (a `paused=` event is live only
   while its handoff file is present, or until a later forward-phase append
   supersedes it); `workflow.md` finishes the Phase-4 start/resume signal +
   State-0 routing + loader-note re-points (`:310`, `:350`, `:417`, `:743`,
   `:768`); `workflow-drift-check.md` re-bases its Phase-4 migration-skip on the
   ledger `phase == "D"`/`"Done"` tail. Six files. — risk: high (workflow
   machinery: edits the auto-resume state machine and the drift gate)  [ ]
3. Phase-2 audit-summary relocation + review-dispatch / render prose adaptation —
   `prompts/structural-review.md` drops the `minimal` pass and adapts the bloat
   checks to the thinned plan; `workflow/structural-review.md` (the orchestration
   doc) re-points the durable audit trace to `plan-review.md`;
   `skills/review-plan/SKILL.md` writes the audit to `plan-review.md` (the D7
   write side) and the review state to the ledger; `skills/execute-tracks/SKILL.md`
   re-points its State-0 end-session read off the `## Plan Review` checkbox onto
   the ledger; `plan-slim-rendering.md` adapts the "Keep `## Final Artifacts`
   verbatim" render rule and the Goals/Non-Goals pre-Checklist list to the thinned
   plan. The resume/gate logic these describe lives in Steps 1-2 and the Track-1
   precheck, so these are bounded dispatch/render-prose re-points. — risk: medium
   (workflow machinery, behavioral but bounded: review-dispatch and render-rule
   prose) — size: ~5 files; no mergeable low/medium work fits (the rest of the
   track is high)  [ ]

Steps are sequential: Step 1 establishes the ledger-first read pattern and the
`conventions.md` §1.7(c) spec the implementer gate cites; Step 2 applies the same
pattern to the auto-resume / escalation / completion reads and writes the
`plan-review.md` review state; Step 3 relocates the cold audit summary into
`plan-review.md` and adapts the dependent dispatch / render prose. No parallel
steps — all three share the ledger-first read contract Step 1 fixes.

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step. Empty at Phase 1. -->

## Validation and Acceptance

- A Phase-2 review writes its audit summary to `plan-review.md` and its review
  state to the ledger; a `minimal` change (no plan) still has a review-fact home.
- The implementer §1.7(c) gate and the three §1.7(l) review prompts detect a
  workflow-modifying branch from the ledger marker, with the same stable-prefix
  semantics as the old plan `### Constraints` read.
- The implementer-rules §1.7(e) pre-commit gate refuses a live
  `.claude/scripts/**` edit on a workflow-modifying branch, outside the Phase-4
  promotion commit (D14).
- A Phase-4 promotion of a workflow-modifying branch commits the staged
  `.claude/scripts/**` (the `git add` and divergence check include it), so a
  promoted precheck script reaches develop (D14).
- A paused phase boundary at State 0 or Phase 4 is recorded as a ledger
  `paused` event, and the recovery grep extended to the ledger still finds it
  (the handoff file is unchanged and found by the `handoffs` glob).
- A `minimal`→`lite` upgrade materializes `implementation-plan.md`; a
  `minimal`→`full` upgrade materializes both `implementation-plan.md` and
  `design.md`, and writes the upgraded tier as a ledger event.
- A completed track's completion episode is in the track file's `## Episodes`;
  the `lite`/`full` Checklist carries only a one-line summary and pointer.
- An in-flight pre-ledger workflow-modifying branch (plan `### Constraints`
  marker present, no `phase-ledger.md`) is still detected as workflow-modifying
  by every re-pointed marker reader, via the `### Constraints` fallback.
- `conventions.md` §1.7(c) describes the ledger-first read, so the normative
  spec and the re-pointed `implementer-rules` / `step-implementation` consumers
  agree.
- `workflow-drift-check.md`'s Phase-4 migration-skip fires on the ledger
  `phase == "D"`/`"Done"` tail, not the removed plan `## Final Artifacts`.
- `inline-replanning`'s `## Plan Review` reset and `track-code-review`'s
  deferred-write resume signal read the ledger review state / `phase` tail, so a
  `minimal` (no-plan) escalation and completion both resolve.
- `track-code-review`'s standalone §1.7(b) marker-read block and
  `implementation-review`'s Phase-2 pass-matrix tier read both read the ledger
  (with the `### Constraints` fallback), so a `minimal` workflow-modifying branch
  is still detected and the Phase-2 pass matrix is still selected without a plan.
- A resolved pause is not re-discovered: the recovery scan ignores a ledger
  `paused=` event whose handoff file is gone (or which a later forward-phase
  append supersedes), so it does not recur as a false Abort.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

Each step edits staged `.claude/**` copies (copy-then-edit on first touch per
§1.7(e)) and lands one commit. Recovery is the standard Phase-B path: an
interrupted step is reverted with `git reset --hard HEAD` and re-run from its
roster line. The re-points are idempotent — re-applying a marker/tier/signal
re-point to an already-edited file yields the same staged text, and the
ledger-first-with-`### Constraints`-fallback read resolves the same whether or
not a prior partial edit landed. No step mutates shared external state (no DB,
no live workflow — the I6 invariant holds: live `.claude/**` stays at develop
until Phase-4 promotion), so re-running a step has no side effect beyond the
staged file and its commit.

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
- `.claude/workflow/workflow.md`
- `.claude/workflow/workflow-drift-check.md`
- `.claude/skills/execute-tracks/SKILL.md`
- `.claude/skills/review-plan/SKILL.md`
- `.claude/workflow/structural-review.md` (the orchestration doc, distinct from
  the in-scope `prompts/structural-review.md`)
- `.claude/workflow/plan-slim-rendering.md`
- `.claude/workflow/conventions.md` — **§1.7(c) read-side amendment only**, a
  narrow carve-out from the Track-1 ownership below (the normative spec the
  re-pointed implementer consumers cite must agree with them).

All edits route to the staged mirror under
`docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/**`.

**Out-of-scope (Track 1):** the ledger primitive (`workflow-startup-precheck.sh`
and tests), the convention/planning/workflow format docs (**except the narrow
`conventions.md` §1.7(c) read-side amendment carved into this track above**),
and the `create-plan` SKILL. This track consumes the contracts Track 1
publishes; it does not define them.

**Contracts this track consumes (published by Track 1):**
- The ledger event grammar and the `--append-ledger` subcommand signature (read
  by the re-pointed marker/tier consumers; written by the escalation path).
- The thinned `lite`/`full` plan shape and the per-tier artifact set (the
  Phase-2 review and escalation branch on them).
- The §1.7 marker home in the ledger (the §1.7(c)/(l) readers re-point to it).

**Inter-track dependency:** depends on Track 1. No downstream track consumes
this track's output within this plan.

## Base commit
01b13bfd642b48c498c85007cfd7014c370fd2d4
