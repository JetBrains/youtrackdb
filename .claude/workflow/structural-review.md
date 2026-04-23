# Structural Review (Step 2 of Implementation Review)

## Goal

Validate the plan's internal structure and completeness across the plan
file and backlog (when present). This is a lightweight check that does
NOT read the codebase — it catches plan-level defects (dependency
cycles, missing descriptions, contradictions) cheaply. For new-format
plans, pending-track `**What/How/Constraints/Interactions**` detail
lives in `implementation-backlog.md`; the review reads both files. For
legacy plans (no backlog file on disk), that detail is inline in the
plan-file entry and the review falls back to it there.

This runs **automatically** as step 2 of the implementation review
(Phase 2), after the consistency review passes. See
[`implementation-review.md`](implementation-review.md) for the full
Phase 2 orchestration.

Technical, risk, and adversarial reviews happen later, adaptively per-track
during Phase 3, when the execution agent has maximum context about the
codebase and can benefit from what was learned executing earlier tracks.

## Structural review prompt

**Prompt file:** [`prompts/structural-review.md`](prompts/structural-review.md)

## Gate verification

After fixes are applied, the structural review re-runs to verify.

**Prompt file:** [`prompts/structural-gate-verification.md`](prompts/structural-gate-verification.md)

## Review iteration

The structural review iterates until clean:

```
Iteration 1: Full review → findings → user decisions → apply fixes
Iteration 2: Gate check → verify fixes + catch regressions → if blockers, loop
Iteration 3: Gate check → if still blockers, escalate to user
```

Max 3 iterations. Finding IDs are cumulative (S1, S2, ... S6, S7).

If blockers persist after 3 iterations, escalate to the user and return to
Phase 1 (Planning) to rework the plan before re-entering structural review.

If structural fixes significantly restructure the plan (tracks reordered,
tracks added/removed, scope indicators changed substantially), re-run
the full structural review instead of the gate check to catch cascading
issues.

## Review output

The structural review document is saved to
`docs/adr/<dir-name>/reviews/structural.md`:

```markdown
# Structural Review

## Iteration 1

### Finding S1 [blocker] → FIXED
**Location**: Track 2 scope indicator
**Issue**: Track 2's scope mentions "wiring IndexStatistics" but
IndexStatistics is introduced in Track 3 — ordering violation.
**Proposed fix**: Reorder Track 3 before Track 2, or extract the
IndexStatistics interface into Track 1's scope.
**Resolution**: Accepted — moved IndexStatistics to Track 1's scope.

### Finding S2 [should-fix] → REJECTED
**Location**: Track 1 description
**Issue**: Missing interaction note with Track 3.
**Proposed fix**: Add interaction note.
**Resolution**: Rejected — Tracks 1 and 3 are independent.

## Iteration 2 (Gate Verification)

- S1: VERIFIED
- S2: REJECTED (no action needed)
- No new findings.
- **Summary: PASS**
```

When the structural review passes, proceed to Phase 3 execution
(`/execute-tracks`).

---

## Replanning

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
5. On review PASS — resumes track execution with the revised plan.
6. On review FAIL with persistent blockers — advises user to restart
   from Phase 1 (`/create-plan`) with accumulated episodes as input.

The only case that exits to Phase 1 is when the plan is so fundamentally
broken that incremental revision cannot fix it.
