You are reviewing ONE TRACK of an implementation plan for risks and
feasibility. You MUST read the codebase to assess risk realistically.

## Workflow Context

You are a sub-agent spawned during **Phase A (Review + Decomposition)** of
the execution workflow. The overall workflow has five phases: Phase 0
(research), Phase 1 (planning) — together these produced the plan you are
reviewing, Phase 2 (consistency & structural review of the plan — already
passed), Phase 3 (execution — tracks implemented one at a time, each going
through Phase A → Phase B → Phase C), and Phase 4 (final artifacts).

**Key terminology:**
- **Track**: A coherent stream of related work within the plan. Contains
  steps (decomposed later in this Phase A, after your review). Max ~5-7
  steps per track.
- **Step**: A single atomic change = one commit. Fully tested. Step
  decomposition has not happened yet — only scope indicators exist.
- **Episode**: A structured record of what happened during a step or track
  implementation. Track episodes (in the plan file under completed tracks)
  summarize strategic outcomes; step episodes (in step files) contain
  implementation details. Episodes from completed tracks are your evidence
  of what actually happened vs. what was planned.
- **Scope indicator**: A rough sketch of expected work in a track
  (`> **Scope:** ~N steps covering X, Y, Z`). Strategic signal, not a binding contract.
- **Decision Records**: Design choices in the plan's Architecture Notes
  section. Each has alternatives, rationale, risks, and track references.
  Immutable during execution — changes require formal replanning.
- **Component Map**: Mermaid diagram + annotated bullet list showing which
  system components this plan touches and what changes in each.
- **Invariants**: Conditions that must remain true before/after the change.
  Can be ENFORCED (code already guarantees them), ASPIRATIONAL (tracks need
  to implement them), or VIOLATED (current code contradicts them). Each must
  map to a testable assertion.
- **Integration Points**: How new code connects to existing code — entry
  points, SPIs, callbacks, event flows.
- **Non-Goals**: Explicit scope exclusions to prevent scope creep during
  execution.

**Your role:** Assess risks and feasibility of this track before
implementation begins. Your findings may lead to risk mitigation steps,
reordering, or (if severity is `skip`) a recommendation to skip the track.

**Where things live during Phase A:** The track's detailed description
(the `**What/How/Constraints/Interactions**` subsections plus any
track-level component diagram) lives in the step file at
`docs/adr/<dir-name>/tracks/track-N.md` under a `## Description` section —
copied there at Phase A start. The plan file carries strategic context
(Architecture Notes, Decision Records, Component Map) and track-level
status + episodic memory.

---

Inputs:
- Plan file: {plan_path} (strategic context — Architecture Notes,
  Decision Records, Component Map)
- Step file: {step_file_path} (the track's `## Description` section —
  authoritative source for the track's What/How/Constraints/Interactions
  and any track-level diagram.)
- Track to review: {track_name}
- Codebase root: {codebase_path}
- Episodes from completed tracks: {prior_episodes}
- Previous findings: {previous_findings}

Start by reading the track description and any track-level component
diagram from the step file's `## Description` section. Read the
relevant Decision Records from the plan. Then explore the parts of the
codebase this track touches.

**Tooling — PSI is required for symbol audits.** Critical-path
exposure analysis ("who calls this hot method", "which subclasses
override this", "which existing safeguards already protect this
path") is reference-accuracy work. Use mcp-steroid PSI find-usages /
find-implementations / type-hierarchy when the mcp-steroid MCP server
is reachable — grep silently misses polymorphic call sites, generic
dispatch, and Javadoc references, exactly the cases where a "blast
radius is bounded" claim can quietly be wrong. Use grep only for
filename globs, unique string literals, and orientation. If
mcp-steroid is unreachable in this session, fall back to grep and add
an explicit reference-accuracy caveat to any finding that depends on
a symbol search.

**How to invoke:**
- The MCP server is `mcp-steroid`. Its tools are deferred, so load their schemas via ToolSearch first.
- Call `steroid_list_projects` once at session start to confirm the IDE has the right project open and matches the working tree.
- Run PSI queries (find-usages, find-implementations, type-hierarchy) via `steroid_execute_code`, which evaluates a Kotlin snippet against the PSI tree — there is no dedicated `find_usages` tool.
- For Kotlin recipes, fetch the `coding-with-intellij-psi` skill via `steroid_fetch_resource`.

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
- **Evidence search**: <PSI find-usages / find-implementations /
  type-hierarchy query when the IDE is reachable; Grep/Glob query
  otherwise. Record which tool was used so the certificate's
  reference-accuracy is auditable.>
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
  isolated test helper. Use mcp-steroid PSI find-usages when the IDE is
  reachable so polymorphic and generic call sites are not silently
  missed.
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
