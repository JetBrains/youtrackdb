# Design Decision Escalation

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §When to pause and ask the user | implementer | 3B | The situations during step implementation that warrant pausing for a user design decision. |
| §How to present the decision | implementer | 3B | Describe the context, list alternatives with trade-offs, recommend, and wait for user guidance. |
| §What is NOT a design decision (handle autonomously) | implementer | 3B | Mechanical changes, convention-following naming, test selection, and plan-prescribed details are handled autonomously. |
| §Per-phase autonomy | orchestrator,implementer | 3A,3B,3C | Everything within a phase session is autonomous except design decisions. |

<!--Document index end-->

During step implementation (Phase B), the agent may encounter situations
where the code requires a **design decision** — a choice between
alternatives that affects architecture, public API shape, data structures,
algorithms, or behavioral semantics beyond what the plan specifies.

> **House style for chat-scale prose.** User-facing prose produced from this file (status updates, escalation prompts, replanning summaries, review-mode loop turns, handoff notes, whichever apply) follows the AI-tell subset of `house-style.md`: `## Banned sentence patterns`, `## Banned analysis patterns`, `## Orientation`, and `## Plain language`. Structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`) do not apply to chat-scale prose. See conventions.md:any:any `§1.5` for the workflow-level anchor and tier mapping.

## When to pause and ask the user
<!-- roles=implementer phases=3B summary="The situations during step implementation that warrant pausing for a user design decision." -->

- The plan does not prescribe the specific approach and multiple valid
  alternatives exist with different trade-offs
- The choice affects public API surface or behavioral contracts
- The decision has implications beyond the current step or track
- The implementation reveals that the planned approach has multiple
  viable interpretations
- A new abstraction, interface, or data structure needs to be introduced
  that wasn't anticipated in the plan

## How to present the decision
<!-- roles=implementer phases=3B summary="Describe the context, list alternatives with trade-offs, recommend, and wait for user guidance." -->

1. Describe the context — what you're implementing and where the decision
   point arose
2. List the alternatives (at least 2) with concrete trade-offs for each
3. State your recommendation with rationale
4. Wait for user guidance before proceeding

## What is NOT a design decision (handle autonomously)
<!-- roles=implementer phases=3B summary="Mechanical changes, convention-following naming, test selection, and plan-prescribed details are handled autonomously." -->

- Mechanical code changes with one obvious approach
- Naming choices that follow existing codebase conventions
- Test structure and test case selection
- Code review fix iterations
- Implementation details fully prescribed by the plan or Decision Records

## Per-phase autonomy
<!-- roles=orchestrator,implementer phases=3A,3B,3C summary="Everything within a phase session is autonomous except design decisions." -->

Everything within a phase session is fully autonomous **except design
decisions**:

- Phase A: track reviews (as sub-agents), step decomposition, per-step
  risk tagging (`low` / `medium` / `high` per `risk-tagging.md`)
- Phase B: step implementation, testing, coverage, step-level code review
  iterations (up to 3 per step — fires only on `risk: high` steps),
  episode production, within-track adaptation
- Phase C: track-level code review (iteration depth keyed to the per-track
  complexity tag, no fixed iteration cap, terminated by no-progress
  detection; treats `medium` and `high` step ranges as focal points)
- Cross-track impact checks (unless impact is detected)
