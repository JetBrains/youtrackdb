You are reviewing ONE TRACK of an implementation plan for technical soundness.
You MUST read the codebase to validate this track's assumptions.

Inputs:
- Plan file: {plan_path} (read the full plan for context, but focus on
  the specified track)
- Track to review: {track_name}
- Codebase root: {codebase_path}
- Episodes from completed tracks: {prior_episodes}
- Previous findings from other reviews: {previous_findings}

Start by reading the track description, its component diagram (if any), and
the relevant Decision Records. Then explore the parts of the codebase this
track touches.

Use episodes from completed tracks to inform your review — they may
reveal codebase realities that the original plan didn't anticipate.

Review against these criteria:

COMPONENT MAP ACCURACY (for this track)
- Do the components referenced actually exist (or are clearly marked new)?
- Are the relationships (calls, depends-on, extends) accurate?
- Are there components this track misses that will be affected?

DESIGN FEASIBILITY
- Does the described approach work given the current code structure?
- Are there APIs, interfaces, or contracts the track assumes but that don't
  exist or work differently?
- Are there simpler approaches the planning phase missed?
- Does anything learned from prior tracks invalidate this track's approach?

EDGE CASES & ERROR PATHS
- What happens on failure (exceptions, timeouts, partial state)?
- Does the track handle concurrent access where relevant?
- What happens during recovery (crash, restart) for durable state changes?

INTEGRATION POINTS
- Do documented integration points match actual code?
- Will changes break existing callers or consumers?

INVARIANT VALIDITY
- Are stated invariants enforceable given the codebase?
- Do prior track changes affect invariants assumed here?

BACKWARD COMPATIBILITY
- Will existing data/formats still work?
- Are migrations needed that the plan doesn't mention?

For each issue found, produce a finding:

### Finding T<N> [blocker|should-fix|suggestion]
**Location**: <where in the track + relevant source file(s)>
**Issue**: <what's wrong, with evidence from the codebase>
**Proposed fix**: <concrete change — may include modifying steps,
  updating the track description, adding decision records, etc.>

Severity guide:
- blocker: Track will fail during execution (wrong API, missing component)
- should-fix: Track will produce fragile or incomplete results
- suggestion: Improvement based on codebase knowledge
- skip: Track is no longer needed (functionality already exists, prior track
  made it redundant, etc.). Recommend SKIP with rationale.
