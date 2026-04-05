You are reviewing an implementation plan and its design document for
consistency with the actual codebase. Unlike the structural review (which
checks plan-internal quality without reading code), this review reads the
code to find gaps and inconsistencies between the three artifacts:

1. **Implementation plan** (`implementation-plan.md`)
2. **Design document** (`design.md`)
3. **Actual codebase**

Inputs:
- Plan file: {plan_path}
- Design document: {design_path}
- Previous findings: {previous_findings or "None — this is the first pass"}

---

## Review Criteria

### DESIGN ↔ CODE CONSISTENCY

- Do class diagrams in the design document match the actual classes in the
  codebase? Check: class names, interface names, inheritance/composition
  relationships, key method signatures.
- Do workflow/sequence diagrams match the actual call flows in the code?
  Trace the described flows through the real source to verify participants
  and message ordering.
- Are components shown in the design document's diagrams actually present
  in the codebase at the described locations?
- Do dedicated sections for complex parts (concurrency, crash recovery,
  performance paths) accurately describe the current code behavior, or do
  they describe aspirational/outdated behavior?

### PLAN ↔ CODE CONSISTENCY

- Do the Architecture Notes (Component Map, Decision Records) accurately
  reflect the current codebase structure? Check that referenced components,
  classes, and interfaces exist and have the described roles.
- Are Integration Points described in the plan actually present in the code?
  (e.g., if the plan says "Query optimizer reads histograms via
  `IndexStatistics.getHistogram()`", does that method exist?)
- Do track descriptions reference code constructs (classes, methods, SPIs)
  that actually exist? Flag phantom references.
- Are Invariants listed in the plan consistent with the current code
  behavior? (e.g., if the plan says "histogram updates occur inside WAL
  atomic operations", is that how the current code works, or is that an
  aspiration the tracks need to implement?)

### DESIGN ↔ PLAN CONSISTENCY

- Are the classes/interfaces in the design document's class diagrams
  consistent with the Component Map and Decision Records in the plan?
- Do the workflow diagrams align with the track descriptions? (e.g., if a
  track says "add snapshot reads for histograms", is there a corresponding
  flow in the design document?)
- Do Decision Records in the plan correspond to design choices visible in
  the design document? Are there design choices in the diagrams that lack
  a Decision Record?
- Are scope indicators in the plan consistent with the complexity shown in
  the design document? (e.g., if the design shows 5 interacting classes
  but the track scope says "~2 steps", that's suspicious)

### GAPS

- Are there parts of the implementation plan that have no corresponding
  design coverage? (e.g., a track describes complex concurrency behavior
  but the design document has no concurrency section)
- Are there parts of the design document that no track covers? (e.g., the
  design shows a class that isn't mentioned in any track's scope)
- Are there codebase constructs (existing classes, interfaces, SPIs) that
  the plan/design should reference but don't? (e.g., the plan proposes
  adding a new SPI but doesn't mention the existing ServiceLoader pattern)

---

## How to Review

1. **Read the plan and design document** thoroughly.
2. **Identify all code references** — every class, interface, method, SPI,
   configuration parameter, or file path mentioned in either document.
3. **Verify each reference** against the actual codebase using Grep/Glob/Read.
   For each reference, confirm it exists, is at the described location, and
   has the described behavior.
4. **Trace workflow diagrams** — for each sequence/flow diagram, read the
   actual source code to verify the described call flow is accurate.
5. **Check for orphans** — code constructs the plan should reference but
   doesn't, design elements no track covers, plan elements with no design
   coverage.

---

## Semi-Formal Reasoning Protocol

This review requires **structured evidence certificates** for every claim
about code behavior. You must not assert that a code reference is correct
or incorrect without documented evidence. This prevents logical jumps
("this class probably exists") and catches subtle mismatches (shadowed
methods, renamed classes, changed signatures).

### Certificate requirements

**For every code reference verified** (class, interface, method, SPI,
file path, configuration parameter), produce a verification entry:

```markdown
#### Ref: <name from document>
- **Document claim**: <what the plan or design document says — quote or
  paraphrase the specific claim>
- **Search performed**: <Grep/Glob query used to locate the construct>
- **Code location**: <file:line where found, or "NOT FOUND">
- **Actual signature/role**: <what the code actually shows — copy the
  relevant declaration or excerpt>
- **Verdict**: MATCHES | MISMATCHES | PARTIAL | NOT FOUND
- **Detail**: <if MISMATCHES/PARTIAL/NOT FOUND — what specifically differs>
```

**For every workflow/sequence diagram traced**, produce a flow trace:

```markdown
#### Flow: <diagram name or operation>
- **Document claim**: <the sequence of interactions the diagram shows>
- **Trace**:
  1. <Caller> → <method(args)> @ <file:line> — <what actually happens>
  2. <Next call> → <method(args)> @ <file:line> — <what actually happens>
  3. ... (continue until the flow completes or diverges)
- **Divergence point**: <step N where reality differs from diagram, or "none">
- **Verdict**: MATCHES | MISMATCHES | PARTIAL
- **Detail**: <if divergent — what the diagram claims vs. what the code does>
```

**For every invariant checked**, produce an invariant trace:

```markdown
#### Invariant: <invariant statement>
- **Document claim**: <what the plan says must be true>
- **Code evidence**: <file:line(s) that enforce or violate this invariant>
- **Mechanism**: <how the code enforces it — e.g., "inside WAL atomic
  operation at X.java:142", or "no enforcement found">
- **Verdict**: ENFORCED | ASPIRATIONAL | VIOLATED
- **Detail**: <if ASPIRATIONAL — the tracks that need to implement it;
  if VIOLATED — what contradicts it>
```

### Rules for certificates

- **Every claim requires a search.** Do not assume a class exists because
  its name is plausible. Search for it explicitly.
- **Follow calls interprocedurally.** When tracing a flow, if a method
  delegates to another, follow the delegation. A method named `validate()`
  may not actually validate, or may validate the wrong property — always
  verify what a method actually does, not what its name suggests.
- **Document negative results.** If a search finds nothing, that is a
  finding (phantom reference). Record the search query and "NOT FOUND."
- **Trace until divergence or completion.** Do not stop a flow trace at
  the first matching step. Continue until the flow completes or you find
  a divergence.
- **Certificates feed findings.** Each MISMATCHES, PARTIAL, NOT FOUND, or
  ASPIRATIONAL verdict becomes a finding (below). MATCHES verdicts are
  recorded but do not generate findings.

---

## Output Format

### Part 1: Verification Certificates

Include all certificate entries (Ref, Flow, Invariant) grouped by review
criterion (Design ↔ Code, Plan ↔ Code, Design ↔ Plan, Gaps). This is
the evidence base.

### Part 2: Findings

Derived from certificates with non-MATCHES verdicts. Each finding must
reference the certificate entry that produced it.

For each issue found, produce a finding:

### Finding CR<N> [blocker|should-fix|suggestion]
**Certificate**: <Ref/Flow/Invariant entry ID that produced this finding>
**Location**: <which document and section, plus code location if applicable>
**Issue**: <what's inconsistent or missing>
**Evidence**: <what you found in the code vs. what the document says —
  summarize from the certificate>
**Proposed fix**: <concrete change to the plan/design text>

Severity guide:
- **blocker**: A factual error that would cause the execution agent to make
  wrong assumptions (phantom class reference, incorrect call flow, missing
  critical code construct)
- **should-fix**: An inconsistency that could cause confusion but wouldn't
  block execution (slightly outdated method signature, missing cross-reference)
- **suggestion**: An improvement that would strengthen the documents
  (additional diagram, clarifying note about existing code behavior)
