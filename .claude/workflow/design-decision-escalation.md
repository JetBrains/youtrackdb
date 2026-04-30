# Design Decision Escalation

During step implementation (Phase B), the agent may encounter situations
where the code requires a **design decision** — a choice between
alternatives that affects architecture, public API shape, data structures,
algorithms, or behavioral semantics beyond what the plan specifies.

## When to pause and ask the user

- The plan does not prescribe the specific approach and multiple valid
  alternatives exist with different trade-offs
- The choice affects public API surface or behavioral contracts
- The decision has implications beyond the current step or track
- The implementation reveals that the planned approach has multiple
  viable interpretations
- A new abstraction, interface, or data structure needs to be introduced
  that wasn't anticipated in the plan

## How to present the decision

1. Describe the context — what you're implementing and where the decision
   point arose
2. List the alternatives (at least 2) with concrete trade-offs for each
3. State your recommendation with rationale
4. Wait for user guidance before proceeding

## What is NOT a design decision (handle autonomously)

- Mechanical code changes with one obvious approach
- Naming choices that follow existing codebase conventions
- Test structure and test case selection
- Code review fix iterations
- Implementation details fully prescribed by the plan or Decision Records

## Per-phase autonomy

Everything within a phase session is fully autonomous **except design
decisions**:

- Phase A: track reviews (as sub-agents), step decomposition, per-step
  risk tagging (`low` / `medium` / `high` per `risk-tagging.md`)
- Phase B: step implementation, testing, coverage, step-level code review
  iterations (up to 3 per step — fires only on `risk: high` steps),
  episode production, within-track adaptation
- Phase C: track-level code review (up to 3 iterations; treats `medium`
  and `high` step ranges as focal points)
- Cross-track impact checks (unless impact is detected)
