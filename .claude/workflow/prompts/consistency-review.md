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

## Output Format

For each issue found, produce a finding:

### Finding CR<N> [blocker|should-fix|suggestion]
**Location**: <which document and section, plus code location if applicable>
**Issue**: <what's inconsistent or missing>
**Evidence**: <what you found in the code vs. what the document says>
**Proposed fix**: <concrete change to the plan/design text>

Severity guide:
- **blocker**: A factual error that would cause the execution agent to make
  wrong assumptions (phantom class reference, incorrect call flow, missing
  critical code construct)
- **should-fix**: An inconsistency that could cause confusion but wouldn't
  block execution (slightly outdated method signature, missing cross-reference)
- **suggestion**: An improvement that would strengthen the documents
  (additional diagram, clarifying note about existing code behavior)
