# Structural Review (Step 2 of Implementation Review)

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Goal | reviewer-plan,orchestrator | 2 | Validate plan structure and completeness (cycles, missing descriptions, contradictions, bloat) without reading code. |
| §Structural review prompt | reviewer-plan | 2 | Pointer to the structural-review sub-agent prompt file. |
| §Bloat checks | reviewer-plan | 2 | The per-section bloat budgets (DR/invariant/integration/component length, superseded DRs, duplication) and fixes. |
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
to the four-bullet form, move long-form material to `design.md`,
replace the duplicated body with a one-line link, delete the
superseded DR — and the orchestrator applies it without asking the
user. Findings escalate as `design-decision` only when the structural
issue is ordering / sizing / contradiction / decision-traceability,
not bloat.

| Category | Severity | Trigger | Fix |
|---|---|---|---|
| **DR-length** | should-fix | Decision Record body exceeds ~30 lines | Trim DR back to the four-bullet form; move long-form material to a `design.md` section and link from `**Full design**`. |
| **Invariant-length** | should-fix | Invariant entry exceeds ~5 lines | State the rule in one short paragraph; move multi-paragraph derivations to a `design.md` complex-topic section. |
| **Integration-point-length** | should-fix | Integration-point bullet exceeds ~3 lines | Name the connection point in one short bullet; move workflow walk-throughs to a `design.md` Workflow section. |
| **Component-intent-length** | should-fix | A component's intent bullet (under the Component Map) exceeds ~5 lines | Keep the intent to one short paragraph; move design-level descriptions of that component's behavioral change to `design.md`. |
| **Superseded-DR retained** | blocker | A DR is explicitly marked `(SUPERSEDED ...)` or "see DN" but still occupies a `#### D<N>` block | Delete the superseded DR entirely; document the supersession in the replacing DR's rationale ("This replaces an earlier approach where..."). |
| **Plan/design duplication** | should-fix | A DR body or Architecture Notes subsection is >50 lines **and** `design.md` has a section whose title matches the DR's topic | Replace the duplicated body in the plan with a one-line link to the matching `design.md` section. |
| **Plan-file budget exceeded** | should-fix | Plan file exceeds ~1,500 lines / ~30K tokens | Identify which sections are over their per-section budget and apply the per-section fixes above. |

**Detection notes:**
- Line counts include the section heading and bullet body but exclude
  trailing blank lines between sections.
- For the **plan/design duplication** heuristic, the title-match check
  is fuzzy: any section in `design.md` whose heading shares 2+
  significant words with the DR title (after lowercasing and dropping
  stop-words) is a hit. Borderline matches should be flagged for human
  review, not auto-resolved.
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

The structural review is not persisted to disk. Mechanical fixes are
applied autonomously to `implementation-plan.md` (and the relevant
`plan/track-N.md` files when track descriptions need updates);
design-decision findings ride in the orchestrator's conversation
context until escalated and resolved. The durable trace is the
gate-PASS state, the resulting commit, and the audit-summary entry in
the plan file's `## Plan Review` section (see
implementation-review.md:orchestrator,reviewer-plan:2 §Audit trail).
A typical iteration looks like:

```
Iteration 1
  Finding S1 [blocker, mechanical]      → AUTO-FIX → delete superseded DR D2 (replaced by D5)
  Finding S2 [should-fix, mechanical]   → AUTO-FIX → trim D3 from 42 lines to 18; move worked example to design.md §Histogram Build
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
