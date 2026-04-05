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

## Semi-Formal Reasoning Protocol

This review requires **structured evidence certificates** for every risk
claim. You must not assert that something is risky without tracing the
actual code path and documenting concrete evidence. This prevents
pattern-matching on keywords ("WAL" → "must be risky") and catches cases
where existing safeguards already mitigate the perceived risk.

### Certificate requirements

**For every critical path exposure assessed**, produce:

```markdown
#### Exposure: <what the track touches on a critical path>
- **Track claim**: <what the track plans to do to this path>
- **Critical path trace**:
  1. Entry: <method(args)> @ <file:line>
  2. <next call> @ <file:line> — <what happens here>
  3. ... (trace until the operation completes or reaches disk/network)
- **Blast radius**: <what breaks if this step has a bug — list affected
  callers, data structures, recovery paths>
- **Existing safeguards**: <locks, WAL coverage, validation, tests that
  already protect this path — with file:line references>
- **Residual risk**: HIGH | MEDIUM | LOW — <what can still go wrong
  despite safeguards>
```

**For every assumption verified**, produce:

```markdown
#### Assumption: <what the track takes for granted>
- **Track claim**: <quote or paraphrase the assumption>
- **Evidence search**: <Grep/Glob query performed>
- **Code evidence**: <file:line showing the assumption holds or doesn't>
- **Verdict**: VALIDATED | UNVALIDATED | CONTRADICTED
- **Detail**: <if not VALIDATED — what the code actually shows>
```

**For every testability concern**, produce:

```markdown
#### Testability: <step description>
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: <what makes this step hard to test>
- **Existing test infrastructure**: <relevant test base classes, fixtures,
  helpers at file:line>
- **Feasibility**: ACHIEVABLE | DIFFICULT | INFEASIBLE
- **Detail**: <if not ACHIEVABLE — what specific coverage gaps are expected>
```

### Rules for certificates

- **Trace critical paths fully.** Do not claim blast radius without tracing
  the callers. A method that looks critical may be called from only one
  isolated test helper.
- **Document existing safeguards.** A risk that already has WAL coverage,
  lock protection, or comprehensive tests is lower severity than the same
  risk without safeguards. Check before flagging.
- **Assumptions require code evidence.** "It should work" is not validation.
  Search for the specific API, interface, or behavior the track assumes.
- **Prior track episodes are evidence.** If a prior track discovered
  something, cite the episode and verify it still holds.

---

## Output Format

### Part 1: Evidence Certificates

Include all certificate entries (Exposure, Assumption, Testability)
grouped by review criterion. This is the evidence base.

### Part 2: Findings

Derived from certificates. Each finding must reference the certificate
entry that produced it.

For each issue found, produce a finding:

### Finding R<N> [blocker|should-fix|suggestion]
**Certificate**: <Exposure/Assumption/Testability entry that produced this>
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
