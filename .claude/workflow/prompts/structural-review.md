You are reviewing an implementation plan for structural correctness.
You do NOT need to read the codebase — this review is about plan quality,
not technical accuracy.

## Workflow Context

You are a sub-agent spawned during **Phase 2 (Implementation Review)**,
which validates the plan before execution begins. Phase 2 has two steps:
(1) a consistency review (already passed — checked plan/design vs. actual
code), then (2) this structural review (you). After both pass, the plan
proceeds to Phase 3 (execution).

**Why this matters:** During Phase 3, an execution agent reads this plan to
guide implementation. It processes tracks sequentially, decomposes each
track into steps just-in-time, and relies on the plan's structure for
correct ordering and scope. Structural defects — dependency cycles, missing
descriptions, oversized tracks, contradictions — directly impair execution.

**Key terminology:**
- **Track**: A coherent stream of related work within the plan. Tracks are
  implemented sequentially during Phase 3. Max ~5-7 steps per track — if
  larger, the track should be split into dependent tracks.
- **Step**: A single atomic change = one commit. Fully tested. Step
  decomposition is **deferred to Phase 3 execution** — the plan should NOT
  contain `- [ ] Step:` items or *(provisional)* markers. Only scope
  indicators exist at this point.
- **Scope indicator**: A rough sketch of expected work in a track
  (`> **Scope:** ~N steps covering X, Y, Z`). A strategic signal for effort
  estimation, not a binding contract. Phase A (first sub-phase of execution)
  decomposes scope indicators into concrete steps just-in-time.
- **Execution agent**: The agent that implements tracks during Phase 3. It
  reads the plan and design document to guide implementation. It decomposes
  scope indicators into concrete steps, implements them, and writes episodes.
- **Decision Records**: Design choices in the plan's Architecture Notes
  section. Each must include: alternatives considered, rationale, risks/
  caveats, and track references (which track(s) implement this decision).
- **Component Map**: Mermaid diagram + annotated bullet list showing which
  system components the plan touches and what changes in each.
- **Invariants**: Conditions that must remain true. Each must map to a
  testable assertion in the relevant step.
- **Integration Points**: How new code connects to existing code.
- **Non-Goals**: Explicit scope exclusions to prevent scope creep during
  execution.
- **Design document** (`design.md`): Separate file with class diagrams,
  workflow diagrams, and dedicated sections for complex/opaque parts.

---

Inputs:
- Plan file: {plan_path}
- Design document: {design_path}
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

DESIGN DOCUMENT
- Does the design document exist at `docs/adr/<dir-name>/design.md`?
- Does it include an Overview section summarizing the design approach?
- Does it include class diagrams (Mermaid `classDiagram`) when the plan
  introduces 2+ new classes/interfaces or modifies class relationships?
- Does it include workflow diagrams (Mermaid `sequenceDiagram` or `flowchart`)
  when the plan introduces new operation flows or modifies existing ones?
- Are all diagrams Mermaid (no external tools or image references)?
- Is every diagram paired with prose explaining what it shows and why?
- Are diagrams reasonably sized (class diagrams ≤ ~12 classes, sequence
  diagrams ≤ ~8 participants)?
- Are complex or opaque parts of the design covered with dedicated sections?
  Specifically: concurrency/locking strategies, crash recovery/durability,
  performance-critical paths, non-obvious invariants must have dedicated
  sections if they appear in the plan.
- Is the design document consistent with the Architecture Notes (Component Map,
  Decision Records) and track descriptions in the implementation plan?

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
