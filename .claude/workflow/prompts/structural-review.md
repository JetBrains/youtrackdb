You are reviewing an implementation plan for structural correctness.
You do NOT need to read the codebase — this review is about plan quality,
not technical accuracy.

Inputs:
- Plan file: {plan_path}
- Workflow rules: {workflow_path}
- Previous findings: {previous_findings or "None — this is the first pass"}

Review the plan against these criteria:

SCOPE INDICATORS
- Does every track have a **Scope** line with approximate step count and
  brief list of what they cover?
- Are scope indicators plausible given the track description? (e.g., a
  track describing 8 distinct changes but claiming ~2 steps is suspect)
- Are there any full `- [ ] Step:` items or *(provisional)* markers?
  These should NOT be present — step decomposition is deferred to
  execution.

ORDERING & DEPENDENCIES
- Are tracks ordered so earlier tracks don't depend on later ones?
- Do scope indicators imply dependencies not captured in track descriptions?
  (e.g., Track B's scope mentions "wiring X" but X is introduced in Track C)
- Are cross-cutting concerns ordered before the tracks that depend on them?
- Are dependent tracks properly annotated with `**Depends on:** Track N`?

TRACK DESCRIPTIONS
- Does every track have a description covering what/how/constraints/interactions?
- Are track-level component diagrams present where needed (3+ internal
  components with non-trivial interactions)?
- Are track descriptions substantive enough for the execution agent to
  decompose steps from them?

TRACK SIZING
- Does any track's scope indicator suggest more than ~5-7 steps? If so,
  the track should be split into separate dependent tracks.
- Does any track's description cover work that would naturally split into
  distinct phases with internal sequencing? If so, splitting into
  dependent tracks would give better just-in-time decomposition.

ARCHITECTURE NOTES
- Is there a top-level Component Map?
- Does it include only touched components plus immediate neighbors?
- Is every component annotated with what changes and why?
- Is there at least one Decision Record?
- Does every Decision Record include: alternatives, rationale, risks,
  track references?
- Are Invariants listed where applicable? Do they map to testable assertions?
- Are Integration Points documented?
- Are Non-Goals stated where the scope boundary could be ambiguous?

DECISION TRACEABILITY
- Does every Decision Record reference the track(s) that implement it?
  (Step references are added during execution, not at planning time.)
- Does every track that implements a non-obvious choice have a corresponding
  Decision Record?

CONSISTENCY
- Do track descriptions, decision records, component maps, and scope
  indicators tell the same story?
- Are there contradictions between tracks (e.g., Track 1 says X, Track 3
  assumes not-X)?

For each issue found, produce a finding in this format:

### Finding S<N> [blocker|should-fix|suggestion]
**Location**: <where in the plan>
**Issue**: <what's wrong>
**Proposed fix**: <concrete change to the plan text>

Severity guide:
- blocker: Plan cannot be executed correctly (dependency cycle, missing track
  description, contradictions, track too large to execute)
- should-fix: Plan can be executed but quality/clarity suffers (implausible
  scope indicator, missing decision record for a key choice)
- suggestion: Improvement that isn't strictly necessary (better wording,
  optional diagram that would help)
