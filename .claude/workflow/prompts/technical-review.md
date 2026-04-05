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

## Semi-Formal Reasoning Protocol

This review requires **structured evidence certificates** for every claim
about the codebase. You must not assert that an API exists, a component
works as described, or an approach is feasible without documented evidence
from reading the actual code. This prevents assumptions like "this
interface probably has that method" and catches subtle mismatches.

### Certificate requirements

**For every component/API assumption verified**, produce:

```markdown
#### Premise: <what the track assumes>
- **Track claim**: <quote or paraphrase from the track description>
- **Search performed**: <Grep/Glob query used>
- **Code location**: <file:line, or "NOT FOUND">
- **Actual behavior**: <what the code actually shows — copy relevant
  declaration, method signature, or excerpt>
- **Verdict**: CONFIRMED | WRONG | PARTIAL | NOT FOUND
- **Detail**: <if not CONFIRMED — what specifically differs>
```

**For every edge case / error path analyzed**, produce:

```markdown
#### Edge case: <scenario description>
- **Trigger**: <specific condition — e.g., "null index name", "concurrent
  WAL flush during histogram read">
- **Code path trace**:
  1. Entry: <method(args)> @ <file:line>
  2. <next call> @ <file:line> — <behavior with this input>
  3. ... (trace until outcome)
- **Outcome**: <what happens — exception type, partial state, silent
  corruption, correct handling>
- **Track coverage**: <does the track description address this? yes/no>
```

**For every integration point verified**, produce:

```markdown
#### Integration: <integration point name>
- **Plan claim**: <what the plan says about how new code connects>
- **Actual entry point**: <file:line of the real integration surface>
- **Caller analysis**: <who calls this today — list callers found via Grep>
- **Breaking change risk**: <will the track's changes break existing callers?>
- **Verdict**: MATCHES | MISMATCHES | CALLERS AT RISK
```

### Rules for certificates

- **Every premise requires a search.** Do not confirm an API exists
  because its name is plausible. Search and read the actual code.
- **Follow calls interprocedurally.** When checking feasibility of an
  approach, trace the actual call chain. A method may delegate, throw,
  or behave differently than its name suggests.
- **Trace edge cases to completion.** Do not stop at "an exception is
  thrown." Trace what catches it, whether state is left inconsistent,
  and whether the track accounts for this.
- **Document negative results.** If a component is NOT FOUND or an API
  works differently than assumed, that is a finding.
- **Prior track episodes are evidence.** When a prior track's episode
  reveals a codebase reality (renamed class, changed API), use it as
  a premise and verify it still holds.

---

## Output Format

### Part 1: Evidence Certificates

Include all certificate entries (Premise, Edge case, Integration) in
order of review criteria. This is the evidence base.

### Part 2: Findings

Derived from certificates. Each finding must reference the certificate
entry that produced it.

For each issue found, produce a finding:

### Finding T<N> [blocker|should-fix|suggestion]
**Certificate**: <Premise/Edge case/Integration entry that produced this>
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
