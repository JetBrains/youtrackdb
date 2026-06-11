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
  guard (lines 75-81; line 103 only cross-references it from the inputs
  contract). The guard's sentence couples two carriers ("The plan's Decision
  Records and the track file are the authoritative source of truth during
  execution"); the D7 rewording targets that whole sentence, naming the
  track's DRs as the live authority.
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
   appends replan decisions and supersession notes; the lifecycle table rows
   and the section descriptions update accordingly (D7).
2. **Carrier consumption (`plan-slim-rendering.md`,
   `implementer-rules.md`).** Define the slim-track rendering in
   `plan-slim-rendering.md` (new — the live file covers slim plan rendering
   only), keeping the track's inline DR section so D7's consumption model
   holds; the frozen-design guard rewords "plan's DRs" to "track's DRs" and
   keeps naming the live DR authoritative during execution; `design.md` stays
   path-only on-demand context in `full` (D7).
3. **Replan propagation (`inline-replanning.md`).** The cross-track
   propagation duty: a replan revising a duplicated decision updates every
   not-yet-completed track copy in the same replan and appends a supersession
   note to completed tracks' `## Decision Log`; the copy-shape rule (any
   post-seed copy of an ever-revised decision carries the inline-replan
   revision format, seed decision pinned in `**Original decision**`); the
   updatable-section lists gain `## Decision Log`; the completed-track pause
   gains the documentation-only carve-out (D7). The D12 tier upgrade rides
   the existing ESCALATE path: new tier's artifacts and 3A passes from the
   upgrade point onward, no retroactive reviews, no automatic downgrade.
4. **Phase-2 conditionals (`implementation-review.md`,
   `prompts/consistency-review.md`).** Pass selection reads the D18 tier
   line: `minimal` drops the structural pass and lightens consistency to
   track-vs-code; design-presence guards skip the design half when
   `design.md` is absent; the findings-routing rule collapses for no-design
   tiers (every correction plan- or track-scoped; the defer-to-Phase-4 branch
   unreachable) while frozen-seed findings in `full` still defer (D9/D10).
5. **Structural repurpose (`structural-review.md`,
   `prompts/structural-review.md`).** The duplication check becomes the
   seed↔track fidelity verification with Part 5's domain (iterate seed
   records), provenance-only qualifier for revision-format DRs, and
   authoring-time-only restoration; design-destination bloat-fix re-routes
   point at the matching track sections in every tier; the `minimal` stub
   skip (nothing to check) lands here and in step 4's selection (D10).
6. **Phase-3A selection (`track-review.md`).** The tier replaces the
   step-count axis as the change-level panel selector (S4: the per-step risk
   tag stays the 3B gate, triage stays the 3C gate); Risk stays
   track-characteristic-gated in `lite`/`full` and drops in `minimal`; the
   adversarial pass narrows to track realization (scope/sizing, cross-track-
   episode reality, invariant violations) with the episode challenge dropped
   on track 1 only; the 3A adversarial spawn pins the D14 model/effort by
   tier (D9/D14).
7. **Phase 4 (`workflow.md` § Final Artifacts,
   `prompts/create-final-design.md`).** Per-tier durable artifacts (D16):
   `full` = `design-final.md` + `adr.md` with the verdict fold, `lite` =
   `adr.md` with the fold, `minimal` = a two-line gate-verdict summary folded
   into the PR description, no `docs/adr/` entry. The fold reads the log's
   resolved gate records before the `_workflow/` cleanup deletes them; the
   §1.7(f) promotion runs unchanged in every tier, `minimal` included when
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
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, an
optional `size:` clause, and a `[ ]` status checkbox. The `size:`
clause (`— size: ~N files; <reason>`) appears only on an under-filled
`low`/`medium` step (rule in `track-review.md` §Step Decomposition).
Per-step episodes do NOT live here; they live in `## Episodes` below.
The roster is immutable after Phase A except for the status checkbox
flip and the optional `commit:` annotation Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

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
  opens, cites, or routes findings to a design file.
- The repurposed duplication check cannot fire on a mandated track DR: its
  domain iterates `design.md` seed records only, and a track DR with no seed
  counterpart is out of scope by construction.
- The propagation duty is complete: revising a duplicated decision names an
  owner (the orchestrator), a scope (every not-yet-completed carrying track),
  a completed-track mechanism (supersession note), and a copy-shape rule.
- Phase 4 under each tier produces exactly the D16 artifact set, and the
  §1.7(f) promotion text is byte-identical in intent across tiers.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

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

**Dependencies.** Upstream: Track 1 — the tier vocabulary (glossary), the
D18 tier-line shape the Phase-2/3A selectors read, the log's gate-verdict
record shape the Phase-4 fold consumes, and the inline-DR track shape this
track's lifecycle, rendering, and propagation rules govern. Downstream: none
(Phase 4 of this branch consumes the result operationally).

**Signatures and contracts.** The 3A adversarial spawn mirrors Track 1's
gate spawn contract: Agent call with `model` per D14 and the xhigh effort
pin. The D18 tier line is read-only for every consumer in this track; only
`create-plan` writes it.
