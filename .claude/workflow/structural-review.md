# Structural Review (Step 2 of Implementation Review)

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Goal | reviewer-plan,orchestrator | 2 | Validate plan structure and completeness (cycles, missing descriptions, contradictions, bloat) without reading code. |
| §Structural review prompt | reviewer-plan | 2 | Pointer to the structural-review sub-agent prompt file. |
| §Bloat checks | reviewer-plan | 2 | The per-section bloat budgets (DR/invariant/integration/component length, superseded DRs, seed↔track fidelity) and fixes; bloat-fix destinations re-route to track sections. |
| §Gate verification | reviewer-plan | 2 | After fixes, the structural review re-runs via the gate-verification prompt to confirm. |
| §Review iteration | orchestrator,reviewer-plan | 2 | Up to 3 iterations: auto-apply mechanical fixes, escalate design-decision findings, gate-check until clean. |
| §Review output | orchestrator,reviewer-plan | 2 | The review is not persisted; mechanical fixes apply to the plan, the durable trace is the gate-PASS audit entry. |
| §Replanning | orchestrator | 3A,3C | Replanning is not a separate phase; it rides Phase 3's ESCALATE flow with a structural-review preview. |

<!--Document index end-->

## Goal
<!-- roles=reviewer-plan,orchestrator phases=2 summary="Validate plan structure and completeness (cycles, missing descriptions, contradictions, bloat) without reading code." -->

Validate the plan's internal structure and completeness across the plan
file and the track files. This is a lightweight check that does NOT read
the codebase — it catches plan-level defects (dependency cycles, missing
descriptions, contradictions, **bloat**) cheaply. Pending-track detail
lives in each track's `plan/track-N.md` across the four track-level
sections (`## Purpose / Big Picture`, `## Context and Orientation`,
`## Plan of Work`, `## Interfaces and Dependencies`); the review reads
the plan file plus every pending track's track file.

The review also enforces the per-section budgets defined in
`planning.md` § Architecture Notes format. Plan-file bloat is paid by
every Phase A/B/C session for the rest of the plan's life — bloat
findings are first-class structural defects, not stylistic suggestions.

This runs **automatically** as step 2 of the implementation review
(Phase 2), after the consistency review passes. See
implementation-review.md:orchestrator,reviewer-plan:2 for the full
Phase 2 orchestration.

Technical, risk, and adversarial reviews happen later, adaptively per-track
during Phase 3, when the execution agent has maximum context about the
codebase and can benefit from what was learned executing earlier tracks.

## Structural review prompt
<!-- roles=reviewer-plan phases=2 summary="Pointer to the structural-review sub-agent prompt file." -->

**Prompt file:** prompts/structural-review.md:reviewer-plan:2

## Bloat checks
<!-- roles=reviewer-plan phases=2 summary="The per-section bloat budgets (DR/invariant/integration/component length, superseded DRs, duplication) and fixes." -->

The structural review enforces the per-section budgets in
`planning.md` § Architecture Notes format (which carries both the
table-form summary and the per-section rationale). Detection is
mechanical — line-count and pattern-match on the plan file, no
codebase read required — and the findings carry the severities below.

**All bloat findings are classified `mechanical`** by the autonomous
Phase 2 orchestrator (see
prompts/structural-review.md:reviewer-plan:2 §
Classification rules). The fix follows the rule mechanically — trim
to the four-bullet form, move long-form material to its matching track
section, delete the superseded DR — and the orchestrator applies it
without asking the user. Findings escalate as `design-decision` only
when the structural issue is ordering / sizing / contradiction /
decision-traceability, not bloat.

**Bloat-fix destination — track sections in every tier (D10).** The
length-bloat fixes below move long-form material **to the matching track
section**, not to `design.md`, in every tier including `full`. Under the
carrier flip (D7) the track is the live decision carrier and `design.md`
is at most a frozen, non-canonical provenance seed; routing live material
into the frozen seed is wrong-direction (the seed cannot be edited after
Step 4a freeze) and unmaintainable after the first replan. A finding whose
natural destination is the design file therefore re-routes to the track's
`## Decision Log` (decision records), `## Interfaces and Dependencies`
(integration points / invariants), or `## Context and Orientation`
(component-intent prose). In `full` the `**Full design**` pointer in a
trimmed DR still names the frozen seed's mechanism section as on-demand
provenance, but the live record stays in the track.

| Category | Severity | Trigger | Fix |
|---|---|---|---|
| **DR-length** | should-fix | Decision Record body exceeds ~30 lines | Trim DR back to the four-bullet form; move long-form material to the matching track's `## Decision Log` record. In `full`, link the frozen-seed mechanism from `**Full design**` as on-demand provenance. |
| **Invariant-length** | should-fix | Invariant entry exceeds ~5 lines | State the rule in one short paragraph; move multi-paragraph derivations to the matching track's `## Interfaces and Dependencies` (or `## Decision Log` when the derivation is decision rationale). |
| **Integration-point-length** | should-fix | Integration-point bullet exceeds ~3 lines | Name the connection point in one short bullet; move workflow walk-throughs to the matching track's `## Interfaces and Dependencies` / `## Plan of Work`. |
| **Component-intent-length** | should-fix | A component's intent bullet (under the Component Map) exceeds ~5 lines | Keep the intent to one short paragraph; move design-level descriptions of that component's behavioral change to the matching track's `## Context and Orientation`. |
| **Superseded-DR retained** | blocker | A DR is explicitly marked `(SUPERSEDED ...)` or "see DN" but still occupies a `#### D<N>` block | Delete the superseded DR entirely; document the supersession in the replacing DR's rationale ("This replaces an earlier approach where..."). |
| **Seed↔track fidelity** (`full` only, authoring-time only) | should-fix | At Step 4b authoring, a `design.md` **seed D-record** has a matching track `## Decision Log` DR whose content is **not** substantively equivalent to the seed | Restore the track DR to substantive equivalence with its seed record (authoring-time only — see notes). Provenance-only for revision-format DRs: a track DR in the inline-replan revision format is checked only against the seed pinned in its `**Original decision**` field, never against its revised text. |
| **Plan-file budget exceeded** | should-fix | Plan file exceeds ~1,500 lines / ~30K tokens | Identify which sections are over their per-section budget and apply the per-section fixes above. |

**Detection notes:**
- Line counts include the section heading and bullet body but exclude
  trailing blank lines between sections.
- The former **plan/design duplication** check is **repurposed** into the
  **Seed↔track fidelity** check above (D10). The old check fired on a DR
  body over 50 lines that had a title-matching `design.md` section — which
  is now exactly the **mandated** shape of a seed-derived track DR (D7), so
  the old check would fire backwards against compliant track DRs. The
  inverted check iterates the **`design.md` seed records only** and, for
  each, verifies its matching track DR is a faithful copy; a track DR with
  no seed counterpart is out of scope by construction (it cannot fire on a
  mandated track DR that has no seed). The fuzzy title-match heuristic (any
  section whose heading shares 2+ significant words after lowercasing and
  dropping stop-words) still identifies the seed↔track pairing; borderline
  matches are flagged for human review, not auto-resolved.
- The fidelity check is **`full`-tier only** (it needs a `design.md` seed)
  and **authoring-time only**: it runs while the Step 4b author still holds
  context, when the track DR is freshly copied from the seed. After
  authoring, post-replan divergence is owned by the cross-track propagation
  duty (inline-replanning.md:orchestrator:3A,3C), not this check — so the check never fires
  during execution against a track DR the replan legitimately revised.
- A single oversized section is enough to fire the per-section finding;
  the plan-file-budget finding is a roll-up that fires when *cumulative*
  bloat across many sections puts the whole file over budget even
  though no individual section is dramatically oversized.

## Gate verification
<!-- roles=reviewer-plan phases=2 summary="After fixes, the structural review re-runs via the gate-verification prompt to confirm." -->

After fixes are applied, the structural review re-runs to verify.

**Prompt file:** prompts/structural-gate-verification.md:reviewer-plan:2

## Review iteration
<!-- roles=orchestrator,reviewer-plan phases=2 summary="Up to 3 iterations: auto-apply mechanical fixes, escalate design-decision findings, gate-check until clean." -->

The structural review iterates until clean. Each finding carries a
`Classification` field (`mechanical | design-decision`) emitted by the
sub-agent — the orchestrator auto-applies `mechanical` fixes and
batches `design-decision` findings for a single user-resolution pass
per iteration:

```
Iteration 1: Full review → classify findings →
             auto-apply mechanical fixes (Edit / edit-design) →
             escalate design-decision findings to user once →
             apply user resolutions
Iteration 2: Gate check → verify fixes + catch regressions →
             if new findings, classify and re-route as in iteration 1
Iteration 3: Gate check → if still blockers, escalate to user
```

Max 3 iterations. Finding IDs are cumulative (S1, S2, ... S6, S7).
Classification rules live in
prompts/structural-review.md:reviewer-plan:2
§ Classification rules (bloat findings → `mechanical` by construction;
ordering, sizing, contradiction, decision-traceability findings →
`design-decision`).

If blockers persist after 3 iterations, escalate to the user and return to
Phase 1 (Planning) to rework the plan before re-entering structural review.

If structural fixes significantly restructure the plan (tracks reordered,
tracks added/removed, scope indicators changed substantially), re-run
the full structural review instead of the gate check to catch cascading
issues.

## Review output
<!-- roles=orchestrator,reviewer-plan phases=2 summary="The review is not persisted; mechanical fixes apply to the plan, the durable trace is the gate-PASS audit entry." -->

The structural review is not persisted as a review report. Mechanical
fixes are
applied autonomously to `implementation-plan.md` (and the relevant
`plan/track-N.md` files when track descriptions need updates);
design-decision findings ride in the orchestrator's conversation
context until escalated and resolved. The durable trace splits in two
under D7: the multi-line audit summary is written to `plan-review.md`
(present in every tier), and the review *state* is recorded in the phase
ledger as a `phase=A` boundary (the machine signal the startup protocol's
State-0 detection reads — there is no plan `## Plan Review` checkbox to
flip). See implementation-review.md:orchestrator,reviewer-plan:2 §"The
`plan-review.md` document and the ledger review state" for the exact write.
A typical iteration looks like:

```
Iteration 1
  Finding S1 [blocker, mechanical]      → AUTO-FIX → delete superseded DR D2 (replaced by D5)
  Finding S2 [should-fix, mechanical]   → AUTO-FIX → trim D3 from 42 lines to 18; move worked example to the track's ## Decision Log
  Finding S3 [should-fix, design-decision] → ESCALATE → user resolves Track 2 vs. Track 4 contradiction by reordering

Iteration 2 (Gate Verification)
  S1: VERIFIED
  S2: VERIFIED
  S3: VERIFIED
  No new findings → PASS
```

When the structural review passes, proceed to Phase 3 execution
(`/execute-tracks`).

---

## Replanning
<!-- roles=orchestrator phases=3A,3C summary="Replanning is not a separate phase; it rides Phase 3's ESCALATE flow with a structural-review preview." -->

**Not a separate phase.** Replanning is handled within Phase 3
by the execution agent's ESCALATE flow (see "Inline Replanning
(ESCALATE)" in `workflow.md`).

**Why:** The execution agent reads all track episodes from the plan file
and can read/write it directly. It has the context to revise the plan
within the session. A separate phase would add unnecessary context loss.

**What happens on ESCALATE:**
1. Execution agent stops starting new steps.
2. Presents full situation to user (all episodes, what broke, what assumptions
   failed).
3. Proposes revised plan (new/modified tracks, reordering, removed tracks).
4. Spawns a structural review sub-agent to validate the revised plan.
5. On review PASS — updates the plan file with the revised plan and ends
   the session. The next `/execute-tracks` session picks up the revised plan
   and continues.
6. On review FAIL with persistent blockers — advises user to restart
   from Phase 1 (`/create-plan`) with accumulated episodes as input.

The only case that exits to Phase 1 is when the plan is so fundamentally
broken that incremental revision cannot fix it.
