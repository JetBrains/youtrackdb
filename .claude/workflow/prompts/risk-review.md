You are reviewing ONE TRACK of an implementation plan for risks and
feasibility. You MUST read the codebase to assess risk realistically.

Inputs:
- Plan file: {plan_path} (full plan for context, focus on specified track)
- Track to review: {track_name}
- Codebase root: {codebase_path}
- Episodes from completed tracks: {prior_episodes}
- Previous findings: {previous_findings}

Review against these criteria:

CRITICAL PATH EXPOSURE
- Which steps in this track touch critical system paths (storage, WAL,
  transactions, indexes, cache)?
- What is the blast radius if those steps have bugs?

UNKNOWNS & ASSUMPTIONS
- Where is the track asserting things without evidence?
- Are there "it should work" assumptions that need validation?
- Did prior tracks reveal anything that changes risk assessment here?

PERFORMANCE IMPLICATIONS
- Do any changes add work to hot paths?
- Are there new allocations, locks, or I/O in performance-sensitive code?

TESTABILITY & COVERAGE
- Can each step realistically achieve 85% line / 70% branch coverage?
- Are there steps hard to test in isolation?

ROLLBACK & RECOVERY
- If a step's approach fails, what's the rollback story?
- Are there irreversible state changes?

For each issue found, produce a finding:

### Finding R<N> [blocker|should-fix|suggestion]
**Location**: <where in the track + relevant source/test file(s)>
**Issue**: <the risk, with likelihood and impact assessment>
**Proposed fix**: <mitigation — reorder steps, add verification steps,
  note the risk explicitly, etc.>

Severity guide:
- blocker: High likelihood of failure with no obvious recovery
- should-fix: Meaningful risk that should be mitigated
- suggestion: Low-probability risk worth noting
- skip: Track is no longer needed (risk assessment reveals the track is
  redundant, infeasible, or superseded by prior track results)
