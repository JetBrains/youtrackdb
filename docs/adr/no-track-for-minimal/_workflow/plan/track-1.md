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
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-15T15:30Z [ctx=info] Review + decomposition complete
- [x] 2026-06-15T16:09Z [ctx=safe] Step 1 complete (commit e7d18b9bb6)
- [x] 2026-06-15T16:30Z [ctx=safe] Step 2 complete (commit 16ac0731c9)
- [x] 2026-06-15T16:42Z [ctx=safe] Step 3 complete (commit c710c0796a)
- [x] 2026-06-15T18:24Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->
- **§1.7 staging did not cover `.claude/scripts/**` (Phase A adversarial A1,
  blocker).** The frozen design (D12) and plan assumed "every `.claude/**` edit
  stages," but §1.7(a)/(d)/(e) name only `workflow`/`skills`/`agents`, and the
  Phase-4 promotion `git add` + divergence check omit `.claude/scripts`.
  Resolved by adding **D14** (extend §1.7 to `.claude/scripts/**`).
  **Cross-track impact on Track 2:** Track 2 must (i) extend the
  implementer-rules §1.7(e) gate to refuse live `.claude/scripts/**` outside the
  promotion commit, and (ii) widen the `create-final-design.md` Phase-4
  promotion `git add` + divergence check to include `.claude/scripts`. Captured
  in Track 2's Plan-of-Work step 2, Validation, and D14 `Implemented in`.
- 2026-06-15T16:09Z Step 1 hardened the ledger append into a published
  contract for Track 2: it loud-rejects malformed field values (exit 3)
  and loud-fails on a `mkdir` / write / `mv` error, where the
  signature and event grammar stay unchanged. Track 2's orchestrator
  callers must check the append exit status rather than assume success.
  See Episodes §Step 1.
- 2026-06-15T16:30Z Step 2 pinned the §1.7 staging-mode marker home to
  the ledger `s17` field and documented the full Track 2 re-point
  surface (the §1.7 marker readers, the `.claude/scripts/**` gate
  widening, and the Phase-4 / track-completion signal). The consistency
  sweep also reconciled stale `workflow.md` plan-checkbox text and two
  glossary entries to the ledger model. See Episodes §Step 2.
- 2026-06-15T16:42Z Step 3 left the `s17` staging-mode token spelling
  unpinned (the script validates only that it is a bare token); Track 2
  pins the literal when it re-points the §1.7 marker readers. The
  `create-plan` Phase-1 seeding now writes `phase=0` and the `tier`
  field, so the tier is read from the ledger rather than a plan line.
  See Episodes §Step 3.

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
  temp-file-plus-rename append covers it: a partial write lands in the temp
  file and the rename publishes the new tail atomically, so a crash mid-append
  leaves the prior ledger intact and `determine_state` resolves the prior
  state. (The existing roster-vs-`## Progress` interrupted-write reconciliation
  is a separate track-file mechanism and does not apply to the ledger.)
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
- **Implemented in**: this track (conventions §1.6(f) exclusion entry). No
  `detect_drift` code change is needed: its walk enumerates a hardcoded artifact
  list (`implementation-plan.md`, `design.md`, `design-mechanics.md`,
  `plan/track-*.md`), so the ledger is excluded by omission — the implementer
  confirms the ledger filename is not added to that list.
- **Full design**: design.md §"The phase ledger".

#### D14: §1.7 staging covers `.claude/scripts/**`
- **Alternatives considered**: leave §1.7 covering only `.claude/workflow/**`,
  `.claude/skills/**`, `.claude/agents/**` and handle this branch's script edits
  by branch-local manual staging alone (Phase 4 `cp -r .claude/.` promotes a
  staged `scripts/` subtree regardless).
- **Rationale**: this branch's in-scope set includes
  `.claude/scripts/workflow-startup-precheck.sh` and its two test files, and
  design D12 asserts "every `.claude/**` edit stages." The shipped §1.7 must back
  that assertion, so §1.7(a) path layout, (b) marker enumeration, (d)
  reads-precedence, and (e) write-routing all add `.claude/scripts/**`, and the
  implementer-rules pre-commit gate (Track 2) refuses live `.claude/scripts/**`
  edits. Future workflow-modifying branches that touch the scripts then get the
  same auto-routing and gate protection the workflow/skills/agents prefixes
  already have. Raised by the Phase-A adversarial review (A1, blocker;
  user-ratified resolution "extend §1.7 to scripts").
- **Risks/Caveats**: the extension takes effect only after Phase 4 promotion, so
  THIS branch runs the develop-era §1.7 that does not route scripts — its script
  and tests are authored manually at the staged path (see `## Context and
  Orientation`), and the develop gate does not guard a live-script slip.
- **Implemented in**: this track (conventions §1.7(a)/(b)/(d)/(e)); Track 2
  (implementer-rules §1.7(e) gate refuses live `.claude/scripts/**` outside the
  promotion commit; `create-final-design.md` Phase-4 promotion divergence check
  and `git add` both include `.claude/scripts`).
- **Full design**: design.md records the as-built in Phase 4 (the frozen design
  predates this Phase-A review finding).

- 2026-06-15T16:09Z (dependency-reveal) Step 1 pinned the ledger `phase`
  vocabulary to the state-machine values {0, A, C, D, Done} (not the
  design diagram's illustrative `phase=3C`) and made the append
  loud-reject / loud-fail. Track 2's callers must emit these exact phase
  tokens and check the append exit status. See Episodes §Step 1.
- 2026-06-15T16:30Z (dependency-reveal) Step 2 fixed the §1.7
  staging-mode marker home as the ledger `s17` field and left the marker
  readers, the `.claude/scripts/**` enforcement gate, and the Phase-4 /
  track-completion signal on their develop-era plan-checkbox sources for
  Track 2 to re-point. See Episodes §Step 2.
- 2026-06-15T16:42Z (dependency-reveal) Step 3 moved the change tier off
  the plan `**Change tier:**` line onto the ledger `tier` field and left
  the `s17` token literal unpinned for Track 2's marker-reader re-point.
  See Episodes §Step 3.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 2 (3 findings T1-T3, all accepted and fixed;
  gate iter2 surfaced one suggestion T4 — plan-mirror D13 lag — fixed).
- [x] Risk: PASS at iteration 2 (5 findings R1-R5, all accepted/verified; zero
  regressions, zero new findings).
- [x] Adversarial: PASS at iteration 3 (4 findings A1-A4 incl. 1 blocker, plus
  A5 surfaced at gate iter2; all resolved). A1 (§1.7 omitted
  `.claude/scripts/**`) resolved via the user-ratified D14 §1.7-scripts
  extension; A1/A5 needed two gate iterations to fully close the Phase-4
  promotion `git add`/divergence-check gap.

## Context and Orientation

The branch is workflow-modifying under §1.7(b) (plan D12), so every edit in
this track routes to `_workflow/staged-workflow/.claude/**`; the live workflow
stays at develop state and the branch executes under the develop workflow. The
new `workflow-startup-precheck.sh` and its tests are exercised from the staged
path, not wired into the live machinery.

**Branch-local script staging (D14).** This track adds `.claude/scripts/**` to
the §1.7 staging convention (D14), but that coverage only takes effect after
Phase 4 promotion — the branch itself runs the develop-era §1.7, which routes
only `.claude/workflow/**`, `.claude/skills/**`, and `.claude/agents/**`. So the
implementer authors the new script and its tests by **manual** copy-then-edit at
the staged path: on first touch, copy the live
`.claude/scripts/workflow-startup-precheck.sh` (and the two test files) into
`_workflow/staged-workflow/.claude/scripts/`, then edit only the staged copies.
The live `.claude/scripts/**` files are never touched. The develop-era
pre-commit gate does not guard `.claude/scripts/**`, so this discipline is
manual, not gate-enforced; Phase 4's `cp -r .claude/.` promotes the staged
`scripts/` subtree alongside the rest (Track 2 widens the Phase-4 promotion
`git add` and divergence check to include `.claude/scripts`, so the copied
files are committed and reach develop — D14).

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
   for the within-track sub-state. Record the ledger as an unstamped artifact in
   the §1.6(f) exclusion list (doc-only); no `detect_drift` code change is needed
   because its walk enumerates a hardcoded artifact list that does not include
   the ledger filename (T1/A4 — the implementer confirms the omission holds).
   Decide and
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
   D4 — this track defines the home; Track 2 re-points the readers); §1.7(a)
   path layout, (b) marker enumeration, (d) reads-precedence, and (e)
   write-routing all add `.claude/scripts/**` to the staged prefix set (D14;
   Track 2 extends the implementer-rules gate to enforce it); the
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
   rather than the plan checkboxes. For the no-plan `minimal` resume the active
   track is `track-1` by construction (single-track tier; D10's `plan/track-1.md`
   secondary signal), so the agent-side active-track re-derivation that walks the
   plan `## Checklist` (workflow.md §Startup Protocol step 5) is gated to
   `lite`/`full` — `minimal` has no Checklist to walk.
7. **create-plan SKILL.** Drop the `minimal` shape-complete stub template; thin
   the `lite`/`full` aggregator template; add `## Invariants & Constraints` to
   the track template; seed the ledger at Phase 1 via `--append-ledger`; rewire
   Step 1c resume routing onto the ledger (D10).

Invariant to preserve across the work: existing in-flight `lite`/`full` plans
that still carry a Checklist must resume without regression — the two-level
lookup keeps the track-file sub-state walk unchanged.

## Concrete Steps

1. Ledger primitive in `workflow-startup-precheck.sh` + its tests — add the
   `--append-ledger` subcommand and an atomic temp-file-plus-rename append;
   rewrite `determine_state` to read the ledger tail (top-level phase + active
   track), keeping the track-file `## Progress` within-track read; confirm the
   ledger stays excluded by omission from `detect_drift`'s hardcoded artifact
   list; pin the event grammar and key set (the Track-2 contract); cover append,
   last-value-wins, interrupted (torn) append, the new `determine_state` ledger
   path, and the no-plan `minimal` resume (`track-1` active track, no Checklist),
   and rework `test_workflow_startup_precheck_stub.py` for the no-plan `minimal`
   case. Authored by manual copy-then-edit at the staged path; the live script is
   never touched. — risk: high (workflow machinery: a script that runs
   automatically, and the auto-resume state machine)  [x] commit: e7d18b9bb6
2. Artifact-model specification across the convention/planning/workflow docs —
   conventions.md §1.1 glossary (phase ledger, derived-mirror plan, plan-review
   document, combined Invariants & Constraints), §1.2 per-tier artifact set +
   thinned `lite`/`full` plan structure + 14→15 section count, §1.6(f) ledger
   exclusion (D13), §1.7(b)/(k) marker-home note (D4 home; Track 2 re-points the
   readers), §1.7(a)/(b)/(d)/(e) `.claude/scripts/**` coverage (D14);
   conventions-execution.md §2.1 `## Invariants & Constraints` 15th section
   template + lifecycle; planning.md tier classification + thinned plan structure
   + 15th section + `### Goals`/Architecture-Notes disposition (D5); workflow.md
   §Startup Protocol state derivation reads the ledger tail + the no-plan
   `minimal` active-track-is-`track-1` note. — risk: high (workflow machinery:
   edits the §1.7 staging convention, the §1.6 stamp scheme, the auto-resume
   state-machine spec, and the shared plan/track schema)  [x] commit: 16ac0731c9
3. create-plan authoring surface (`create-plan/SKILL.md`) — drop the `minimal`
   shape-complete stub template; thin the `lite`/`full` aggregator template; add
   `## Invariants & Constraints` to the track template; seed the ledger at
   Phase 1 via `--append-ledger`; rewire Step 1c resume routing onto the ledger
   (D10). — risk: medium (workflow machinery, behavioral but bounded: one skill's
   decision/dispatch logic) — size: ~1 file; no mergeable low/medium work fits
   (the rest of the track is high)  [x] commit: c710c0796a

Steps are sequential: Step 1 pins the ledger grammar (the contract), Step 2
documents the model that references it, and Step 3 consumes both (seeds the
ledger via the subcommand, uses the templates the docs define). No parallel
steps.

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step. Empty at Phase 1. -->

### Step 1 — commit e7d18b9bb6, 2026-06-15T16:09Z [ctx=safe]
**What was done:** Added the `--append-ledger` subcommand to the staged
`workflow-startup-precheck.sh` with an atomic temp-file-plus-rename
append, and pinned the event grammar `[<ISO>] [ctx=<level>] phase=…
track=… tier=… categories="…" s17=… paused=…` (key set {phase, track,
tier, categories, s17, paused}, `categories` the one quoted field,
last-value-wins per key on read). Rewrote `determine_state` as a
two-level resume: a new `determine_state_from_ledger` reads the ledger
tail for the top-level phase ({0, A, C, D, Done}) and active track,
computes the State C sub-state from the track file's `## Progress`, and
defaults the active track to `track-1` for the plan-less `minimal` case;
with no ledger it falls back to the unchanged plan-checkbox walk.
Confirmed without a code change that the ledger stays excluded by
omission from `detect_drift`'s hardcoded artifact list. The step-level
hook-safety review then hardened the append path: a value validator
(newline in any field, space in a bare-token field, double-quote in
`categories` each exit 3), loud-fail guards on `mkdir` / write / `mv`
with `exit $?` at the call site, and a `trap … RETURN` that reaps the
PID-suffixed temp without breaking atomicity. 105/105 staged tests pass.

**What was discovered:** A latent newline-detection bug surfaced and was
fixed in the same step. `*"$(printf '\n')"*` degenerates to `*""*`
because command substitution strips the trailing newline, so it matched
every value; the fix uses a `$'\n'` ANSI-C variable in a `case` glob.
Cross-track for Track 2: the hook-safety hardening refined the published
contract so the append now loud-rejects malformed field values and
loud-fails on a write error instead of silently corrupting or losing the
tail. The `--append-ledger` signature and the event grammar stay
unchanged, so Track 2's reader contract holds. See Surprises §Step 1.

**What changed from the plan:** The ledger `phase` token was pinned to
the state-machine values {0, A, C, D, Done} rather than the design
diagram's illustrative `phase=3C`; the within-track sub-phase stays in
the track file per the two-level rule. This is a dependency-reveal for
Track 2: its orchestrator callers must emit these exact phase tokens and
check the append exit status. See Decision Log §Step 1.

**Key files:**
- `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh` (new)
- `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/scripts/tests/test_workflow_startup_precheck.py` (new)
- `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/scripts/tests/test_workflow_startup_precheck_stub.py` (new)

**Critical context:** The ledger `phase` vocabulary {0, A, C, D, Done}
plus the active-track number is now part of the published contract; an
unrecognized phase token is a loud parse error (exit 3). Track 2's
readers and Step 2's docs must use these exact tokens.

### Step 2 — commit 16ac0731c9, 2026-06-15T16:30Z [ctx=safe]
**What was done:** Specified the derived-mirror artifact model across
the four staged workflow docs, written against Step 1's as-built ledger
contract. `conventions.md` gained four `§1.1` glossary terms (phase
ledger, derived-mirror plan, plan-review document, combined
`## Invariants & Constraints`), a `§1.2` per-tier artifact set with the
ledger and plan-review rows (plan is `lite`/`full`-only, `minimal` drops
it) plus the thinned `lite`/`full` plan structure, the `§1.6(f)` ledger
and plan-review unstamped exclusions (D13, excluded by omission), the
`§1.7(b)/(k)` marker-home note (the staging mode lives in the ledger
`s17` field per D4), the `§1.7(a)/(b)/(d)/(e)` plus `(f)` divergence-check
`.claude/scripts/**` extension (D14), and the 14 → 15 track-section
count. `conventions-execution.md` `§2.1` added `## Invariants &
Constraints` as the 15th section across the template, sections-in-order,
and the lifecycle table. `planning.md` rewired tier classification onto
the ledger, thinned the plan, dropped the `minimal` plan, added the 15th
section to track descriptions, and recorded the D5 disposition of
`### Goals` and Architecture Notes. `workflow.md` `§Startup Protocol`
now derives state from the ledger tail and gates the agent-side
active-track Checklist walk to `lite`/`full`. `workflow-reindex --check`
passes on all four staged docs.

**What was discovered:** The internal-consistency sweep found stale
old-model text that would have contradicted the new model and fixed it
in the same step: two `§1.1` glossary entries (Change tier, Aggregator
plan) and several `workflow.md` `§Startup Protocol` plan-checkbox
references (the `phase == "0"`, `phase == "D"`/`"Done"` bullets and the
`review-done-track-open` substate row). The §1.7 staging-mode marker
home is now the ledger `s17` field, which Track 2 re-points its readers
onto. See Surprises §Step 2.

**What changed from the plan:** none material. Beyond the named
sections, the consistency sweep touched a few co-located plan-checkbox
phrasings to keep each document internally coherent, which the step's
"no dangling reference to a removed section" validation mandates. The
`§1.7(c)` detection rule and the marker-reader consumers were left on
the develop-era in-plan `### Constraints` scan on purpose: re-pointing
them onto `s17` is Track 2's work, and the `(b)/(k)` marker-home notes
say so. See Decision Log §Step 2.

**Key files:**
- `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/conventions.md` (modified)
- `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/conventions-execution.md` (modified)
- `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/planning.md` (modified)
- `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/workflow.md` (modified)

**Critical context:** Track 2's re-point surface is now pinned by the
`conventions.md` source of truth. (1) Re-point the §1.7 marker readers
from the plan `### Constraints` scan onto the ledger `s17` field: the
implementer per-spawn gate, the three Phase-3A `§1.7(l)` criteria-switch
blocks, the Phase-2 review staged-read blocks, plus
`dimensional-review-gate-check.md` and `review-gate-verification.md`
(plan CR1). (2) Widen the implementer-rules pre-commit live-path gate
and the `create-final-design.md` Phase-4 `git add` plus divergence check
to include `.claude/scripts` (D14). (3) Re-point the Phase-4 start/resume
signal and the track-completion-episode writer off the plan
`## Final Artifacts` and track checkbox onto the ledger.

### Step 3 — commit c710c0796a, 2026-06-15T16:42Z [ctx=safe]
**What was done:** Rewrote the staged `create-plan/SKILL.md` to produce
the derived-mirror artifact set. Dropped the `minimal` shape-complete
stub plan template (D2 — `minimal` has no `implementation-plan.md`).
Thinned the `lite`/`full` aggregator template to `## Design Document`
(full only) plus a thin cross-track `## Component Map` plus
`## Checklist`, dropping Goals, Constraints, Architecture Notes, Plan
Review, and Final Artifacts (D1/D5), matching the `conventions.md §1.2`
thinned-plan schematic. Added `## Invariants & Constraints` as the 15th
track-file section after `## Interfaces and Dependencies` (D9). Added a
Phase-1 `--append-ledger` seeding step (`phase=0`, tier, categories,
conditional `s17`) with the exact flags and grammar from the staged
script (D6/D10). Rewired Step 1c resume routing onto the ledger (D10):
`minimal` routes on ledger presence plus `tier=minimal` plus the
`plan/track-1.md` glob, while `lite`/`full` keep plan presence as the
signal and read the tier from the ledger.

**What was discovered:** The `s17` staging-mode token spelling is not
pinned to a literal anywhere in the staged docs or script; the docs
describe the values descriptively and the script validates only that
`s17` is a bare metacharacter-free token. The seeding call passes
`--s17 <staging-mode token>` conditionally and points at
`conventions.md §1.7(b)/(k)` as the home, leaving the exact literal to
Track 2's marker-reader re-point (Track 1 defines the home; pinning a
literal here would pre-empt Track 2). See Surprises §Step 3.

**What changed from the plan:** none material. The Step 1c rewire split
the old "neither file exists" branch into a truly-fresh start versus a
plan-less `minimal` resume and added the ledger-tier parse idiom, both
within the step's stated intent.

**Key files:**
- `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (new)

**Critical context:** The plan no longer carries a `**Change tier:**`
line; the tier moved to the ledger `tier` field (D4), so Step 1c and
every fresh `/execute-tracks` session read the tier from the ledger, not
a plan line. This is the consumer-facing contract Track 2's runtime
re-pointing builds on, alongside the ledger phase vocabulary
{0, A, C, D, Done} from Step 1.

## Validation and Acceptance

- A phase boundary append followed by a fresh `--mode full` read returns the
  appended phase and active track; a second append of a changed tier is read as
  the latest value.
- An interrupted (torn) append leaves the prior ledger tail intact and
  `determine_state` resolves the prior state.
- A `minimal` branch with a ledger and a `plan/track-1.md` but no
  `implementation-plan.md` resumes to its recorded state (not a fresh start),
  with the active track resolved as `track-1` (no Checklist walk).
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
`docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/**` (including
`.claude/scripts/**` once D14 lands; for this branch's own execution the script
and tests are staged by manual copy-then-edit — see `## Context and
Orientation`).

**Sub-floor sizing justification (~8 in-scope files, below the ~12 merge
floor).** Track 1 is not merged into Track 2 for two reasons: (1) it is the
foundation Track 2 depends on — the ledger primitive, the artifact model, and
the contracts Track 2 consumes — so merging would produce one ~21-file track
that mixes definition and consumption across a dependency boundary; (2) its
files are large and dense (the ~1,744-line `workflow-startup-precheck.sh`,
`conventions.md`, `conventions-execution.md`, `planning.md`, `workflow.md`,
`create-plan/SKILL.md`), so a combined track would breach practical review
capacity well below the ~20-25 split ceiling. The define/consume seam is the
natural track boundary.

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
- The ledger event grammar `determine_state` greps — one entry per line,
  `[<ISO>] [ctx=<level>] key=value …`, last-value-wins per key on read.
  Illustrative shape (exact token spelling is pinned in Plan-of-Work step 1;
  that as-built grammar is what Track 2's Phase A reviews against):
  `[2026-06-15T16:42Z] [ctx=safe] phase=A track=1 tier=full categories="Workflow machinery,Architecture" s17=b`
  — key set `phase` / `track` / `tier` / `categories` / `s17` / `paused`.
- The thinned `lite`/`full` plan shape and the per-tier artifact set the
  consumers branch on.
- The 15th track section `## Invariants & Constraints`.

**Inter-track dependency:** Track 2 depends on this track; this track depends on
nothing prior. No downstream track in this plan consumes Track 1 output beyond
Track 2.

## Base commit
6c2e0b5f68b12599aacbcce8b608f5c1489a3159
