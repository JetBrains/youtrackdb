<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Track 2: Execution-side tier consumption — carrier lifecycle, review selection, design-presence conditionals, Phase 4 audit trail

## Purpose / Big Picture
After this track lands (staged), `/execute-tracks` selects its Phase 2 and
Phase 3A passes from the confirmed tier, treats track files as the live
decision carrier through replans and reviews, and folds the research log's
adversarial verdicts into the tier's durable artifact at Phase 4.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Teach the execution side to consume what Track 1 produces. Track files become
the live decision carrier through Phase 3 (replan propagation duty,
implementer guard rewording, slim rendering, §2.1 lifecycle); Phase 2 and 3A
review selection keys off the tier with design-presence conditionals and the
repurposed duplication check; Phase 4 folds the log's adversarial verdicts
into the per-tier durable carrier.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review
- [x] Track completion

- [x] 2026-06-11T13:54Z [ctx=info] Review + decomposition complete
- [x] 2026-06-11T14:34Z [ctx=safe] Step 1 complete (commit 7db9da10d0)
- [x] 2026-06-11T14:46Z [ctx=safe] Step 2 complete (commit 83e003509c)
- [x] 2026-06-11T14:46Z [ctx=safe] Step implementation complete (2/2 steps)
- [x] 2026-06-11T15:20Z [ctx=info] Track-level code review iteration 1 complete (1/3 iterations)
- [x] 2026-06-11T15:23Z [ctx=info] Track-level code review complete (PASS at iteration 1: 5 reviewers, 16 findings, 0 blockers; all VERIFIED, 0 regressions)
- [x] 2026-06-11T15:35Z [ctx=info] Track complete

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- 2026-06-11T14:34Z Step 1 discovered: renaming `track-review.md`
  §"Complexity Assessment" → "Tier-driven review selection" needs
  promotion-time reconciliation of any develop-side inbound reference. The
  one in-branch reference (staged `conventions-execution.md` §2.5) is
  already repaired; Phase 4 must re-check after the §1.7(f) rebase. See
  Episodes §Step 1.
- 2026-06-11T14:34Z Step 1 deferral: the new slim-track rendering rule
  (`plan-slim-rendering.md`) defines the rendering but rewires no consumer —
  the Phase-3A/3B spawn prompts still pass the full on-disk track file.
  Rewiring those spawn prompts is a recorded follow-up, flagged in the doc
  so a reviewer does not read the rendering as already in effect. See
  Episodes §Step 1.
- 2026-06-11T14:46Z Step 2 discovered: the propagation duty's `## Decision
  Log` addition lives in two enumerations (`inline-replanning.md` cases 2-3
  and `conventions-execution.md` §2.1's mid-execution-rewrite line). Phase 4
  promotion must carry both forward in sync. See Episodes §Step 2.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 2 (5 findings, 5 accepted; 0 blockers, 2 should-fix, 3 suggestion)
- [x] Risk: PASS at iteration 2 (5 findings, 5 accepted; 0 blockers, 2 should-fix, 3 suggestion)
- [x] Adversarial: PASS at iteration 2 (7 findings, 7 accepted; 0 blockers, 4 should-fix, 3 suggestion)

Phase A reviews (Complex track → full Technical + Risk + Adversarial pipeline)
found 17 findings, 0 blockers, all accepted and applied as track-file
refinements. The gate verification ran once at iteration 2 as a single
consolidated pass over all three review types (the findings overlapped
heavily: T2≈R2, T5≈R5≈A2, T3≈R4, and were all 0-blocker plan-of-work
enumeration gaps), VERIFYING every finding with 0 regressions. Highest-value
catches: R2/T2 (the propagation duty's primary `[ ]`-track write path needed
`## Decision Log` in inline-replanning cases 2-3, not only the completed-track
carve-out; left unfixed it would silently desync duplicated decisions); A6
(`minimal` consistency drops the plan half too, not only the design half); A1
(the "xhigh effort pin" overstated the harness, reconciled to D14's
session-default degradation caveat). Review files under `reviews/`.

Phase C (track-level code review) ran five workflow reviewers on the
workflow-only cumulative diff with the baseline group skipped: 16 findings,
0 blockers (1 consistency, 4 prompt-design, 3 instruction-completeness, 0
context-budget, 8 writing-style), all resolved in one fix iteration. The
gate-check re-run VERIFIED every finding across the four dimensions that
carried findings, with 0 regressions. Load-bearing catch WI2: a mid-flight
tier upgrade re-enters the tier-keyed Phase-2/3A selectors but nothing
rewrote the D18 tier line on the upgrade path, so a re-entered selector would
read the stale tier; the staged `inline-replanning.md` now names that writer
and this file's Interfaces note carries the matching read-only carve-out. WP1:
`create-final-design.md`'s `design.md` read is now guarded on design-presence
so a `lite`/`minimal` final-designer does not hit a missing-file Read. The
implementer applied the 14 staged-file findings; the orchestrator applied WS3,
WS8 (track-file em-dashes) and the WI2 carve-out. Phase C review files under
`reviews/` (`{consistency,prompt-design,instruction-completeness,context-budget,writing-style}-iter1.md`).
## Context and Orientation

Current state of each mechanism this track retargets (live paths; all writes
go to the staged mirror per §1.7):

- **`track-review.md`** selects the Phase-3A panel by the step-count
  complexity axis (Simple / Moderate / Complex); the adversarial pass
  re-challenges track decisions without a log-vetted notion. In today's
  upgrade rows, critical paths or performance constraints add the Risk pass,
  while major architectural decisions or non-obvious scope add the
  Adversarial pass. The design's target Risk gate deliberately widens to
  include architectural decisions, so step 6 implements the design's Part-6
  enumeration rather than today's mapping.
- **`implementation-review.md`** runs the Phase-2 consistency and structural
  passes unconditionally with a design half (design ↔ code ↔ plan); the
  design-frozen findings-routing rule defers frozen-design findings to
  Phase 4.
- **`structural-review.md`** (workflow doc) and
  **`prompts/structural-review.md`** (sub-agent prompt) carry the bloat
  checks whose fix destinations point at `design.md`, and the duplication
  check that compares a long plan DR against its matching design section —
  a check that would fire backwards against the now-mandated full track DRs.
- **`prompts/consistency-review.md`** reads design + plan + code with no
  design-absent branch.
- **`inline-replanning.md`** owns the updatable-section lists (no
  `## Decision Log` entry today), the completed-track pause (no carve-out for
  documentation-only appends), and the ESCALATE path D12's tier upgrade rides.
- **`implementer-rules.md`** §Loading discipline carries the frozen-design
  guard: the coupled-carriers sentence naming "The plan's Decision Records and
  the track file are the authoritative source of truth during execution", with
  a separate cross-reference riding the `design_path` inputs bullet. The D7
  rewording targets that whole sentence, naming the track's DRs as the live
  authority. (Anchored on the section name and the target sentence, not a line
  range, which drifts on the next edit above it.)
- **`plan-slim-rendering.md`** defines the slim plan rendering sub-agents
  receive; track files are passed whole today (no track-side rendering
  exists anywhere in the live machinery). D7's consumption model needs a
  new slim-track rendering that keeps the inline DR section (slim plan +
  slim track with full DRs inline, `design.md` path-only).
- **`conventions-execution.md` §2.1** tabulates the track-file section
  lifecycle; `## Decision Log` is execution-time-only there today and becomes
  a plan-at-start home written from Phase 1 (full inline DRs) that execution
  appends to.
- **`workflow.md` § Final Artifacts** and
  **`prompts/create-final-design.md`** produce `design-final.md` + `adr.md`
  unconditionally; the §1.7(f) promotion machinery lives in the same Phase-4
  prompt and is unchanged by this track.

## Plan of Work

Carrier lifecycle first (it is what the reviews then check), then review
selection, then Phase 4. The approach in order:

1. **§2.1 lifecycle (`conventions-execution.md`).** `## Decision Log` becomes
   plan-at-start + continuous: Phase 1 writes the full inline DRs, execution
   appends replan decisions and supersession notes. The edit inventory is the
   lifecycle table row, the numbered section description (bullet 4), and that
   bullet's Move-1 reserved-slot note, which flips from "reserved for Move 1
   (empty placeholder until that Move lands)" to the now-active plan-at-start
   inline-DR home (the §2.1 track-side analog of the introduce-once Move-1
   resolution Track 1 applied to the `design.md` seed). `conventions-execution.md`
   is already staged by Track 1 (§2.5): edit the staged copy in place per
   §1.7(e); do not re-copy from develop, or Track 1's §2.5 edits are lost (D7).
2. **Carrier consumption (`plan-slim-rendering.md`,
   `implementer-rules.md`).** Define the slim-track rendering in
   `plan-slim-rendering.md` (new; the live file covers slim plan rendering
   only) as a **doc-only orchestrator prose rule**: the orchestrator renders
   the slim track inline from the track file before passing it to sub-agents,
   with no `render-slim-plan.py` change. Track files are small enough to render
   inline, so the script-backed slim-plan rule stays untouched and S1 holds; a
   proven script need is an ESCALATE, not a silent script edit. Keep the
   track's inline DR section so D7's consumption model holds (slim plan + slim
   track with full DRs inline, `design.md` path-only in `full`). Name the
   consumer the rule feeds: `implementer-rules.md` controls the implementer's
   `step_file_path`, and the Phase-3A/3B spawns that receive the whole track
   today are the switch point; if no consumer is rewired in this track, record
   it as a deliberate deferral so a reviewer does not read the new rendering as
   already in effect. The frozen-design guard rewords "plan's DRs" to "track's
   DRs", keeping the live DR authoritative during execution (D7).
3. **Replan propagation (`inline-replanning.md`, `conventions-execution.md`
   §2.1).** The cross-track propagation duty: a replan revising a duplicated
   decision updates every not-yet-completed track copy in the same replan and
   appends a supersession note to completed tracks' `## Decision Log`. Add
   `## Decision Log` to the updatable-section lists of **cases 2 and 3**
   (not-yet-started, mid-execution) so the duty's primary write path (every
   `[ ]` track) is open, and give **case 4** (completed-track) the
   documentation-only carve-out that relaxes its existing user-pause for the
   supersession-note append. Mirror the same addition in the
   `conventions-execution.md` §2.1 mid-execution-rewrite line, or the two
   enumerations contradict after the edit. The copy-shape rule (any post-seed
   copy of an ever-revised decision carries the inline-replan revision format,
   seed decision pinned in `**Original decision**`) is decision-state-based,
   not replan-event-based; carry that phrasing verbatim so the marker applies
   to every post-seed copy, not only those the current replan touched (D7). The
   D12 tier upgrade rides the existing ESCALATE path: new tier's artifacts and
   3A passes from the upgrade point onward, no retroactive reviews, no
   automatic downgrade.
4. **Phase-2 conditionals (`implementation-review.md`,
   `prompts/consistency-review.md`).** Pass selection reads the D18 tier line.
   Split the consistency shape by tier: `full` is plan + tracks + design;
   `lite` drops the design half (plan + tracks + code); `minimal` drops both
   the design half and the plan-content cross-check (track + code only, since
   the ~10-line stub plan has no content to cross-check). `minimal` also drops
   the structural pass. Design-presence guards skip the design half when
   `design.md` is absent; the findings-routing rule collapses for no-design
   tiers (every correction plan- or track-scoped; the defer-to-Phase-4 branch
   unreachable) while frozen-seed findings in `full` still defer (D9/D10).
5. **Structural repurpose (`structural-review.md`,
   `prompts/structural-review.md`).** The duplication check becomes the
   seed↔track fidelity verification: invert the live trigger (today it fires on
   a DR body over 50 lines plus a title-matching `design.md` section via a
   fuzzy 2+-shared-word match, exactly what a seed-derived track DR satisfies)
   into a check whose domain iterates seed records only, with a provenance-only
   qualifier for revision-format DRs and authoring-time-only restoration. Guard
   the `DESIGN DOCUMENT` check block (the design-existence and diagram bullets)
   behind a design-presence conditional: skipped under `lite` and `minimal`
   (design absent) and run unchanged under `full`, not only the `minimal`
   stub-skip, since structural still runs under `lite`. Design-destination
   bloat-fix re-routes point at the matching track sections in every tier; the
   `minimal` stub skip (nothing to check) lands here and in step 4's selection
   (D10).
6. **Phase-3A selection (`track-review.md`).** The tier replaces the
   step-count axis as the change-level panel selector (S4: the per-step risk
   tag stays the 3B gate, triage stays the 3C gate). When porting the panel
   table to the tier axis, excise the live "or critical path / high-risk"
   clause on the Complex row (`track-review.md` lines 611/621) so the
   tier-keyed selector reads no per-step risk signal. Risk stays
   track-characteristic-gated in `lite`/`full` and drops in `minimal`. The
   adversarial pass narrows to track realization (scope/sizing,
   cross-track-episode reality, invariant violations); only the
   cross-track-episode challenge drops on track 1, while the scope/sizing and
   invariant-violation challenges still run on track 1 (the foundational track
   most constrains the downstream ones). The 3A adversarial spawn pins the D14
   model/effort by tier (D9/D14).
7. **Phase 4 (`workflow.md` § Final Artifacts,
   `prompts/create-final-design.md`).** Per-tier durable artifacts (D16):
   `full` = `design-final.md` + `adr.md` with the verdict fold, `lite` =
   `adr.md` with the fold, `minimal` = a two-line gate-verdict summary folded
   into the PR description, no `docs/adr/` entry. The fold reads `research.md`'s
   `## Adversarial gate record` resolved entries (Track 1's canonical verdict
   carrier, matched by the latest dated heading per `research.md`'s gate-record
   cadence; this is a cross-track read of a Track 1 file, verdict/status-only
   per S2). Wire the fold into the final-artifacts (`adr.md`) commit so it runs
   before `create-final-design.md`'s cleanup `git rm -r _workflow/` deletes the
   log. The §1.7(f) promotion machinery has two homes (the `workflow.md`
   § Final Artifacts narrative and the operative `create-final-design.md` step)
   and is unchanged: the fold inserts around the existing promotion/cleanup
   ordering, not into it. Promotion runs in every tier, `minimal` included when
   workflow-modifying (D10/D16).

Ordering constraints: step 1 precedes steps 2-3 (they cite the new lifecycle);
steps 4-6 are order-flexible once steps 1-3 define the carrier they review;
step 7 is last (it consumes the verdict records and the per-tier artifact
matrix). Invariants to preserve: S4 (tier and risk tag never stack — verify
no staged rule combines them), S2 (the Phase-2 consistency cross-check stays
the log's only execution-side decision-content read; the Phase-4 fold reads
resolved gate verdicts only, the sanctioned non-decision read), I6 (all
edits staged).

## Concrete Steps

1. Execution-side tier consumption — carrier lifecycle, consumption, review selection, and Phase-4 audit (Plan-of-Work items 1, 2, 4, 5, 6, 7). Stage: the §2.1 `## Decision Log` plan-at-start lifecycle (`conventions-execution.md`); the doc-only slim-track rendering plus the frozen-design-guard reword (`plan-slim-rendering.md`, `implementer-rules.md`); the tier-keyed Phase-2 pass selection with design-presence guards (`implementation-review.md`, `prompts/consistency-review.md`); the structural duplication-check repurpose plus the design-presence guard (`structural-review.md`, `prompts/structural-review.md`); the Phase-3A tier panel selector with the S4 "or high-risk" excision (`track-review.md`); and the Phase-4 per-tier artifacts plus the verdict fold (`workflow.md` § Final Artifacts, `prompts/create-final-design.md`). ~10 staged files; intra-step order: the §2.1 lifecycle (item 1) lands first, the rest are independent doc edits. — risk: medium (behavioral-but-bounded workflow machinery; over 5 files of one-phase dispatch logic, no load-bearing gate, auto-running script, or shared schema)  [x] commit: 7db9da10d0
2. Cross-track replan propagation duty (`inline-replanning.md`, `conventions-execution.md` §2.1) (Plan-of-Work item 3). Add `## Decision Log` to the cases 2-3 updatable-section lists and the matching §2.1 mid-execution-rewrite line; give case 4 the documentation-only carve-out that relaxes its user-pause; carry the decision-state-based copy-shape rule (seed pinned in `**Original decision**`) verbatim. Depends on Step 1: the §2.1 lifecycle must define `## Decision Log` as a plan-at-start section first. — risk: high (workflow machinery: edits the inline-replan control-flow protocol; a propagation defect silently desyncs duplicated decisions across many sessions before a human notices)  [x] commit: 83e003509c

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit 7db9da10d0, 2026-06-11T14:34Z [ctx=safe]
**What was done:** Staged Plan-of-Work items 1, 2, 4, 5, 6, 7 across ten
workflow docs under the §1.7 mirror — all behavioral edits, no live-path
writes (I6 holds). The `conventions-execution.md` §2.1 `## Decision Log`
became plan-at-start plus continuous: Phase 1 seeds full inline Decision
Records as the live carrier, execution appends. `plan-slim-rendering.md`
gained a doc-only slim-track rendering rule that keeps the inline Decision
Log in full and needs no `render-slim-plan.py` change (S1 holds);
`implementer-rules.md`'s frozen-design guard now names the track's Decision
Records as the live authority during execution (D7). Phase-2 pass selection
keys off the D18 tier line with per-tier consistency shape and
design-presence guards (`implementation-review.md`,
`prompts/consistency-review.md`). The structural duplication check inverts
into the seed↔track fidelity verification, and the `DESIGN DOCUMENT` block
is design-presence-guarded so it skips under `lite`/`minimal`
(`structural-review.md` plus its prompt). The Phase-3A panel selector keys
off the tier instead of the step-count axis, with the "or critical path /
high-risk" clause excised so no per-step risk signal leaks into panel
selection (S4); the adversarial pass narrows to track realization and pins
the D14 model by tier (`track-review.md`). Phase 4 produces per-tier durable
artifacts with the adversarial-verdict fold reading `research.md`'s
`## Adversarial gate record` before the cleanup `git rm` (`workflow.md`,
`prompts/create-final-design.md`).

**What was discovered:** Renaming `track-review.md`'s §"Complexity
Assessment" to "Tier-driven review selection" broke an inbound
cross-reference from the Track-1-staged `conventions-execution.md` §2.5
anchor; repaired in place, the only staged file carrying it.
`workflow-reindex.py --check` runs against the staged mirror and caught 11
§1.8 annotation/TOC/cross-ref drifts while editing (over-long summaries,
missing `:roles:phases` suffixes, a missing section annotation, and a
backtick span wrapping across a line boundary that mimicked an `adr.md`
reference). All fixed; `--check` now exits 0.

**Key files:**
- `staged-workflow/.claude/workflow/conventions-execution.md` (modified — §2.1)
- `staged-workflow/.claude/workflow/plan-slim-rendering.md` (new)
- `staged-workflow/.claude/workflow/implementer-rules.md` (new)
- `staged-workflow/.claude/workflow/implementation-review.md` (new)
- `staged-workflow/.claude/workflow/prompts/consistency-review.md` (new)
- `staged-workflow/.claude/workflow/structural-review.md` (new)
- `staged-workflow/.claude/workflow/prompts/structural-review.md` (new)
- `staged-workflow/.claude/workflow/track-review.md` (new)
- `staged-workflow/.claude/workflow/workflow.md` (new)
- `staged-workflow/.claude/workflow/prompts/create-final-design.md` (new)

**Critical context:** Step 2's only dependency, the §2.1 `## Decision Log`
plan-at-start lifecycle, is now staged, so Step 2 (cross-track replan
propagation) can append `## Decision Log` to not-yet-completed tracks. Item
3 was held for Step 2 by design.

### Step 2 — commit 83e003509c, 2026-06-11T14:46Z [ctx=safe]
**What was done:** Staged the cross-track replan propagation duty
(Plan-of-Work item 3) across two §1.7-mirror files. `inline-replanning.md`
gained a "Cross-track propagation duty" block in §Process step 3: the
orchestrator owns it, scope is every not-yet-completed track copy revised in
the same replan, and completed tracks get a supersession note appended to
their `## Decision Log`. The decision-state-based copy-shape rule is carried
verbatim (any post-seed copy of an ever-revised decision keeps the
inline-replan revision format with the seed pinned in `**Original
decision**`, routing it to the fidelity check's provenance-only path), as is
the D12 tier-upgrade-rides-ESCALATE note. `## Decision Log` was added to the
cases 2-3 updatable-section lists, opening the duty's primary `[ ]`-track
write path with no ESCALATE pause, and case 4 gained a documentation-only
carve-out for the supersession-note append. The same `## Decision Log`
addition is mirrored in the staged `conventions-execution.md` §2.1
mid-execution-rewrite line so the two enumerations stay consistent.

**What was discovered:** Sub-step 4 fired zero step-level reviewers. A
`risk: high` step editing only `.claude/workflow/*.md` matches neither
`hook-safety` nor `prompt-design` globs, so by `review-agent-selection.md`
§Step-level vs track-level routing it fully defers to the Phase C track
pass, which judges the multi-file gate change against the cumulative diff.
Orchestrator verification confirmed both enumerations (inline-replanning
cases 2-3 and §2.1) list `## Decision Log` and that reindex `--check`
passes. The implementer found the copy-shape rule's provenance-only path
already realized in the Step-1-staged `prompts/design-review.md` fidelity
criterion, so the revision-format marker and the supersession-note mechanism
interlock with no further reconciliation.

**Key files:**
- `staged-workflow/.claude/workflow/inline-replanning.md` (new)
- `staged-workflow/.claude/workflow/conventions-execution.md` (modified, §2.1)

**Critical context:** Phase 4 promotion overwrites the live tree with the
staged mirror, so both edited enumerations (the inline-replanning cases 2-3
lists and the §2.1 mid-execution-rewrite line) carry forward together. They
must stay in sync if either is later edited on develop before promotion.

## Validation and Acceptance

Track-level acceptance:

- The D9 review matrix is realized exactly: for each (pass, tier) cell, the
  staged docs run, narrow, or drop the pass as the design's Part-6 table
  states, and no other pass gains or loses a condition.
- S4 holds: no staged sentence combines the tier and the per-step risk tag
  into one selection signal; Phase 3B/3C text is unchanged apart from the D7
  carrier rewording.
- No-design branches never dereference `design.md`: a dry-run read of the
  staged Phase-2 flow under `lite` and `minimal` reaches no instruction that
  opens, cites, or routes findings to a design file, and the structural
  `DESIGN DOCUMENT` check block is design-presence-guarded (skipped under both
  `lite` and `minimal`, run under `full`).
- The `minimal` consistency pass cross-checks track-vs-code only: under
  `minimal` the staged flow performs no plan-content cross-check against the
  stub plan (the plan half is dropped, not only the design half).
- The repurposed duplication check cannot fire on a mandated track DR: its
  domain iterates `design.md` seed records only, and a track DR with no seed
  counterpart is out of scope by construction.
- The propagation duty is complete: revising a duplicated decision names an
  owner (the orchestrator), a scope (every not-yet-completed carrying track),
  a completed-track mechanism (supersession note), and a copy-shape rule. The
  primary write path is open: a replan revising a duplicated DR can write the
  `## Decision Log` of a not-yet-completed (`[ ]`) track without an ESCALATE
  pause, because `## Decision Log` appears in the cases 2-3 updatable-section
  lists (and the matching `conventions-execution.md` §2.1 line).
- Phase 4 under each tier produces exactly the D16 artifact set, and the
  §1.7(f) promotion text is byte-identical in intent across tiers.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

Both steps are pure staged-prose edits, so each is idempotent by construction:
re-applying a step's edits to the staged mirror produces the same file bytes,
and a failed step reverts with `git reset --hard HEAD` to its base commit (no
build or test state to unwind). First-touch of a staged file follows §1.7(e) —
copy the live file verbatim only when no staged copy exists; `conventions-execution.md`
already has a Track-1 staged copy, so both steps edit that copy in place per
§1.7(d) and never re-copy from develop.

- **Step 1** recovery: regenerate the affected staged copies (`conventions-execution.md`
  edited in place; `plan-slim-rendering.md`, `implementer-rules.md`,
  `implementation-review.md`, `prompts/consistency-review.md`,
  `structural-review.md`, `prompts/structural-review.md`, `track-review.md`,
  `workflow.md`, `prompts/create-final-design.md` first-touch-copied from
  develop) and re-apply the Plan-of-Work item 1/2/4/5/6/7 edits.
- **Step 2** recovery: re-apply the propagation-duty edits to the
  already-staged `inline-replanning.md` (first-touch-copied from develop) and
  `conventions-execution.md` §2.1 (edited in place). Step 2's correctness
  depends on Step 1's §2.1 lifecycle already being committed.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In-scope files** (writes route to
`docs/adr/plan-slimization/_workflow/staged-workflow/.claude/...` per §1.7):

1. `.claude/workflow/track-review.md`
2. `.claude/workflow/implementation-review.md`
3. `.claude/workflow/structural-review.md`
4. `.claude/workflow/prompts/structural-review.md`
5. `.claude/workflow/prompts/consistency-review.md`
6. `.claude/workflow/inline-replanning.md`
7. `.claude/workflow/implementer-rules.md`
8. `.claude/workflow/plan-slim-rendering.md`
9. `.claude/workflow/conventions-execution.md` (§2.1 only — §2.5 belongs to Track 1)
10. `.claude/workflow/workflow.md` (§ Final Artifacts and adjacent session-end text)
11. `.claude/workflow/prompts/create-final-design.md`

**Out of scope**: every Track 1 file; `workflow-startup-precheck.sh` and all
scripts/tests (S1 — this track edits no script); `.claude/agents/**`;
`track-code-review.md` and `review-agent-selection.md` (Phase 3C is unchanged
per S4); `risk-tagging.md` (Track 1 carries its note); the `execute-tracks`
SKILL (state routing is tier-agnostic; pass selection lives inside the docs
this track edits).

**Sizing justification (argumentation gate).** Eleven in-scope files — at the
merge floor, and foldable into Track 1 under the ceiling by raw count
(13 + 11 = 24). Not folded: the combined track would put the entire
seventeen-decision rewrite through a single Phase C review pass, spreading
reviewer attention across both the authoring and the execution halves of the
workflow at once. The seam is the natural dependency boundary (Phase-0/1
authoring docs vs Phase-2/3/4 consumption docs); the tracks are adjacent,
and the single shared file (`conventions-execution.md`) is split on disjoint
sections with the reason recorded in both track files.

**Dependencies.** Upstream: Track 1 supplies the tier vocabulary (glossary), the
D18 tier-line shape the Phase-2/3A selectors read, the inline-DR track shape
this track's lifecycle, rendering, and propagation rules govern, and the
`research.md` `## Adversarial gate record` section the Phase-4 fold reads
(a cross-track read of a Track 1 file: the fold dereferences a section Track 1
defined, so step 7's correctness depends on that section having landed — it
has, in Track 1's staged `research.md`). Downstream: none (Phase 4 of this
branch consumes the result operationally).

**Deferred carryover (out of Track 2's file scope).** Track 1 handed two open
§2.1 reconciliation items whose target files Track 2 cannot edit: the stale
"four sections" framing in `prompts/adversarial-review.md` (a Track 1 file)
and the third-scope review-file home, a `conventions-execution.md` §2.5
question (§2.5 belongs to Track 1). Both are recorded here as known-deferred
to a Track 1 follow-up or the Phase-4 promotion reconciliation, so step 1's
§2.1 edit is not misread as covering them.

**Signatures and contracts.** The 3A adversarial spawn mirrors Track 1's
gate spawn contract: an Agent call pinning `model` by tier on the Agent
`model` field (`full` → Fable 5, `lite` → Opus 4.x; `minimal` drops the 3A
adversarial pass). The xhigh-effort half of the D14 pin rides the session
default, because the Agent surface exposes no per-spawn effort field and there
is no adversarial-reviewer agent file to carry it in frontmatter. That is
D14's documented degradation caveat, and neither outcome reopens the decision.
The D18 tier line is read-only for every consumer in this track; `create-plan`
writes it at confirmation. The one execution-time exception is a mid-flight
tier upgrade on the inline-replan ESCALATE path, which rewrites the line as the
first artifact it lands (the re-entered Phase-2/3A selectors then read the
upgraded tier); `inline-replanning.md` carries that writer.

## Base commit

f34bc56f066a1ec90cf45ca1c4bb6ee4c26002ee
