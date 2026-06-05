## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (a file whose only `## ` heading is this bootstrap block carries none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains any of your roles (or your role is `any`, or the row's Roles is `any`) AND Phases contains any of your phases (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections; if no row matches your role/phase, the file holds nothing for you — do not read further.

Your role: reviewer-plan.
Your phase: 2.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: a ref matches when any of your roles is in its roles and any of your phases is in its phases, your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Workflow Context | reviewer-plan | 2 | Phase 2 consistency review: read code to catch plan/design vs codebase mismatches before execution. |
| §Intent-axis pre-screen (run BEFORE generating findings) | reviewer-plan | 2 | Classify each claim current-state vs target-state before emitting; a [ ] track's target-state mismatch is not a finding. |
| §Review Criteria | reviewer-plan | 2 | The four consistency axes checked between plan, design document, and code, plus gap detection. |
| §DESIGN ↔ CODE CONSISTENCY | reviewer-plan | 2 | Do the design document's class and workflow diagrams match the actual classes and call flows in the code? |
| §PLAN ↔ CODE CONSISTENCY | reviewer-plan | 2 | Do Architecture Notes, integration points, track references, and invariants in the plan reflect the real codebase? |
| §DESIGN ↔ PLAN CONSISTENCY | reviewer-plan | 2 | Do the design document's diagrams, decisions, and complexity align with the plan's Component Map, DRs, and scope? |
| §GAPS | reviewer-plan | 2 | Plan parts with no design coverage, design parts no track covers, and codebase constructs the documents skip. |
| §How to Review | reviewer-plan | 2 | Read the documents, identify every code reference, verify each via PSI when reachable, trace flows, check for orphans. |
| §Semi-Formal Reasoning Protocol | reviewer-plan | 2 | Every claim about code behavior needs a documented certificate; no logical jumps from a plausible name. |
| §Certificate requirements | reviewer-plan | 2 | Ref, flow, and invariant certificate templates each code reference, diagram, and invariant is verified against. |
| §Rules for certificates | reviewer-plan | 2 | Search every claim, follow calls interprocedurally, document negatives, trace flows; certificates feed findings. |
| §Output Format | reviewer-plan | 2 | Two-part output: the verification certificates first, then findings derived from non-matching verdicts. |
| §Part 1: Verification Certificates | reviewer-plan | 2 | The evidence base: all ref, flow, and invariant certificates grouped by consistency axis. |
| §Part 2: Findings | reviewer-plan | 2 | Findings derived from non-matching certificate verdicts; each cites its certificate and carries a classification. |
| §Classification rules | reviewer-plan | 2 | Each finding is mechanical (orchestrator auto-applies) or design-decision (escalate to user); orthogonal to severity. |
| §`mechanical` — orchestrator applies the fix without asking | reviewer-plan | 2 | Current-state claim, one unambiguous correct rendering, fix preserves plan intent; the orchestrator applies it directly. |
| §`design-decision` — orchestrator escalates to the user | reviewer-plan | 2 | Missing DR, track contradiction, unimplemented or violated invariant, unreachable target, or ambiguous fix. |

<!--Document index end-->

You are reviewing an implementation plan and its design document for
consistency with the actual codebase. Unlike the structural review (which
checks plan-internal quality without reading code), this review reads the
code to find gaps and inconsistencies between the four artifacts:

1. **Implementation plan** (`implementation-plan.md`)
2. **Track files** (`plan/track-N.md`, one per pending track) — each
   track file holds that track's what/how/constraints/interactions
   detail and any track-level Mermaid diagram across its
   `## Purpose / Big Picture`, `## Context and Orientation`,
   `## Plan of Work`, and `## Interfaces and Dependencies` sections.
   Written by `create-plan` at Phase 1.
3. **Design document** (`design.md`)
4. **Actual codebase**

Prose produced by this file follows the project house-style at `.claude/output-styles/house-style.md`. See `.claude/workflow/conventions.md §1.5 Writing style for Markdown and prose artifacts` for the canonical workflow-level anchor and tier mapping; the four banned-section heading slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`.

## Workflow Context
<!-- roles=reviewer-plan phases=2 summary="Phase 2 consistency review: read code to catch plan/design vs codebase mismatches before execution." -->

You are a sub-agent spawned during **Phase 2 (Implementation Review)**,
which validates the plan before execution begins. Phase 2 has two steps:
(1) this consistency review (you), then (2) a structural review. After both
pass, the plan proceeds to Phase 3 (execution).

**Why this matters:** During Phase 3, an execution agent reads the plan and
design document to guide implementation. Every inconsistency you miss — a
phantom class reference, an incorrect call flow, a mismatched interface —
will cause the execution agent to make wrong assumptions and produce
incorrect code.

**Key terminology:**
- **Track**: One PR in a stacked-diff series; it builds on the tracks
  before it and stands alone as an independently reviewable and mergeable
  unit. Sized by its planned in-scope file footprint, not its step count:
  the planner maximizes (packs work up to a soft footprint ceiling, related
  or not) and clamps with a two-sided bound — a merge candidate at ≤~12
  in-scope files that folds into a neighbor, a split candidate over ~20-25.
  Both bounds are soft; an out-of-bounds track passes planning when its
  track file carries a written justification (full rule in `planning.md`
  §Track descriptions). Step-level decomposition does not exist yet — only
  scope indicators.
- **Step**: A single atomic change = one commit. Fully tested. Step
  decomposition is **deferred to Phase 3 execution** — the plan should NOT
  contain `- [ ] Step:` items or *(provisional)* markers. Only scope
  indicators exist at this point.
- **Execution agent**: The agent that implements tracks during Phase 3. It
  reads the plan and design document to guide implementation. It decomposes
  scope indicators into concrete steps, implements them, and writes episodes.
- **Scope indicator**: A rough sketch of expected work in a track
  (`> **Scope:** ~N files covering X, Y, Z`). The `~N files` figure is the
  planned file footprint, knowable at plan time, not a step count. A
  strategic signal for effort estimation, not a binding contract.
- **Decision Records**: Design choices in the plan's Architecture Notes
  section. Each has alternatives, rationale, risks, and track references.
  Immutable during execution — changes require formal replanning.
- **Component Map**: Mermaid diagram + annotated bullet list showing which
  system components the plan touches and what changes in each.
- **Invariants**: Conditions that must remain true. Can be ENFORCED (code
  already guarantees them), ASPIRATIONAL (tracks need to implement them),
  or VIOLATED (current code contradicts them). Each must map to a testable
  assertion.
- **Integration Points**: How new code connects to existing code — entry
  points, SPIs, callbacks, event flows.
- **Non-Goals**: Explicit scope exclusions to prevent scope creep during
  execution.
- **Design document** (`design.md`): Separate file explaining the structural
  and behavioral design — class diagrams, workflow diagrams, dedicated
  sections for complex parts (concurrency, crash recovery, performance).
  Frozen after planning — never modified during execution.

---

Inputs:
- Plan file: {plan_path}
- Track files directory: {plan_dir} — every `plan/track-N.md` whose
  matching plan-file entry is `[ ]` (pending). Read each pending track's
  `## Purpose / Big Picture`, `## Context and Orientation`,
  `## Plan of Work`, and `## Interfaces and Dependencies` sections for
  that track's what/how/constraints/interactions detail and any
  track-level Mermaid diagram. Skip track files for `[x]`/`[~]` tracks
  — those tracks' final descriptions live in the plan-file entry
  instead.
- Design document: {design_path}
- Previous findings: {previous_findings or "None — this is the first pass"}

**Staged-read precedence (workflow-modifying plans):** When the plan's `### Constraints` carries the canonical `§1.7(b)` workflow-modifying marker sentence, resolve every read of a `.claude/workflow/**` or `.claude/skills/**` file through `§1.7(d)`, taking the staged copy under `_workflow/staged-workflow/` when present and the live file otherwise.

---

## Intent-axis pre-screen (run BEFORE generating findings)
<!-- roles=reviewer-plan phases=2 summary="Classify each claim current-state vs target-state before emitting; a [ ] track's target-state mismatch is not a finding." -->

Phase 2 runs autonomously: the orchestrator applies your `mechanical`
findings without asking the user, and only escalates `design-decision`
findings. A finding fired against a target-state claim (a `[ ]` track is
meant to create what the design claims; the current code naturally
won't match yet) would either be auto-fixed wrong (silently rewriting
the plan toward current code) or escalated as a non-issue. Both are
broken outcomes — the pre-screen is what prevents them.

**Pre-screen rule.** Before emitting any finding, classify each plan or
design claim along the **intent axis**:

- **Current-state claim** — the plan/design says something about code
  that should already exist at the time of writing (Component Map
  describing a current module's role, `design.md` class diagram showing
  pre-existing classes, Architecture Notes referencing today's SPI, an
  invariant tagged `ENFORCED`). Discrepancies with these become
  findings — emit normally.
- **Target-state claim** — the plan/design says something about code a
  `[ ]` track will create (target-state prose in the track file's
  `## Purpose / Big Picture` + `## Plan of Work` +
  `## Interfaces and Dependencies` sections, forward-looking
  Decision Records describing the post-implementation shape,
  `design.md` sections describing the post-implementation state,
  invariants tagged `ASPIRATIONAL`). The current code naturally
  won't match — **do NOT emit a finding** unless the target is
  unreachable from the current code (in which case emit a
  `design-decision` finding so the user can resolve the gap).
  `## Context and Orientation` is the carve-out exception — see the
  per-section rules below.

The same rule already applies to invariants via the
`ENFORCED / ASPIRATIONAL / VIOLATED` tagging. The pre-screen extends
it to all `design.md` / Component Map / track-description claims.

**How to determine intent for each claim:**
- If the claim is inside a track file's `## Purpose / Big Picture`,
  `## Plan of Work`, or `## Interfaces and Dependencies` section for a
  `[ ]` track → target-state. `## Context and Orientation` is the
  exception: it describes the codebase state at the start of the
  track (pre-existing code, not the post-implementation shape), so
  claims inside it are always current-state regardless of track
  status — emit findings normally.
- If the claim is in a Decision Record's "Implemented in: Track N" line
  for a `[ ]` track → target-state.
- If the claim is in `design.md` and a `[ ]` track's description names
  the same component, class, or flow as something to be created or
  modified → target-state for that aspect; current-state for any
  pre-existing surrounding context.
- Otherwise (Component Map of pre-existing modules, references to
  today's SPI, infrastructure mentioned without a track creating it) →
  current-state.

When in doubt, treat as current-state — the orchestrator will see the
finding and the classification rules below will route it correctly.

---

## Review Criteria
<!-- roles=reviewer-plan phases=2 summary="The four consistency axes checked between plan, design document, and code, plus gap detection." -->

### DESIGN ↔ CODE CONSISTENCY
<!-- roles=reviewer-plan phases=2 summary="Do the design document's class and workflow diagrams match the actual classes and call flows in the code?" -->

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
<!-- roles=reviewer-plan phases=2 summary="Do Architecture Notes, integration points, track references, and invariants in the plan reflect the real codebase?" -->

- Do the Architecture Notes (Component Map, Decision Records) accurately
  reflect the current codebase structure? Check that referenced components,
  classes, and interfaces exist and have the described roles.
- Are Integration Points described in the plan actually present in the code?
  (e.g., if the plan says "Query optimizer reads histograms via
  `IndexStatistics.getHistogram()`", does that method exist?)
- Do track descriptions reference code constructs (classes, methods, SPIs)
  that actually exist? Flag phantom references. *(Applies to the
  track file's `## Purpose / Big Picture` + `## Context and Orientation`
  + `## Plan of Work` + `## Interfaces and Dependencies` sections for
  pending tracks; to the plan-file entry for completed/skipped tracks.
  Architecture Notes, Component Map, Decision Records, Invariants, and
  Integration Points bullets in this section remain plan-only per
  `conventions.md` `§1.2`.)*
- Are Invariants listed in the plan consistent with the current code
  behavior? (e.g., if the plan says "histogram updates occur inside WAL
  atomic operations", is that how the current code works, or is that an
  aspiration the tracks need to implement?)

### DESIGN ↔ PLAN CONSISTENCY
<!-- roles=reviewer-plan phases=2 summary="Do the design document's diagrams, decisions, and complexity align with the plan's Component Map, DRs, and scope?" -->

- Are the classes/interfaces in the design document's class diagrams
  consistent with the Component Map and Decision Records in the plan?
- Do the workflow diagrams align with the track descriptions? (e.g., if a
  track says "add snapshot reads for histograms", is there a corresponding
  flow in the design document?) *(For pending tracks, read track
  descriptions from the track file's `## Purpose / Big Picture` +
  `## Context and Orientation` + `## Plan of Work` + `## Interfaces
  and Dependencies` sections; for completed/skipped tracks, read from
  the plan-file entry. The Component
  Map/Decision Records bullet above and the Decision Records and
  scope-indicators bullets below remain plan-only.)*
- Do Decision Records in the plan correspond to design choices visible in
  the design document? Are there design choices in the diagrams that lack
  a Decision Record?
- Are scope indicators in the plan consistent with the complexity shown in
  the design document? (e.g., if the design shows 5 interacting classes
  but the track scope says "~2 files", that's suspicious)

**A revised Decision Record diverging from the frozen design is
expected, not a finding.** `design.md` is frozen after Phase 1 and is
never mutated during execution; inline replanning records its new
design intent in the plan's Decision Records and the track narrative
instead (see inline-replanning.md:orchestrator:3A,3C § Process). So on
a re-run after an inline replan, a Decision Record whose `**Revised
decision**` no longer matches the frozen design is the expected state —
do NOT emit a finding for it. This covers two divergence shapes, not
one: a DR whose `**Full design**` link points at a now-superseded
design section, *and* a DR whose revised decision diverges from the
mechanism a frozen design section still describes (the design says the
histogram is per-page, the revised DR makes it per-cluster). Both are
the frozen design lagging the plan by design; the Phase 4
`design-final.md` reconciles the as-built design. Treat as a finding
only a divergence between a DR and the design that the replan did *not*
intend — e.g., a DR that was never revised but still contradicts the
design, which signals a genuine plan/design inconsistency.

### GAPS
<!-- roles=reviewer-plan phases=2 summary="Plan parts with no design coverage, design parts no track covers, and codebase constructs the documents skip." -->

- Are there parts of the implementation plan that have no corresponding
  design coverage? (e.g., a track describes complex concurrency behavior
  but the design document has no concurrency section) *(For pending
  tracks, the "track describes …" text lives in the track file's
  `## Purpose / Big Picture` + `## Context and Orientation` +
  `## Plan of Work` + `## Interfaces and Dependencies` sections; for
  completed/skipped tracks, in the plan-file entry. The
  orphan-scope and orphan-codebase-construct bullets below remain
  plan-only.)*
- Are there parts of the design document that no track covers? (e.g., the
  design shows a class that isn't mentioned in any track's scope)
- Are there codebase constructs (existing classes, interfaces, SPIs) that
  the plan/design should reference but don't? (e.g., the plan proposes
  adding a new SPI but doesn't mention the existing ServiceLoader pattern)

---

## How to Review
<!-- roles=reviewer-plan phases=2 summary="Read the documents, identify every code reference, verify each via PSI when reachable, trace flows, check for orphans." -->

**Tooling — PSI is required for symbol verification.** Every claim
in this review is a reference-accuracy fact about Java code (a class
exists, a method has these callers, a flow has these participants).
Use mcp-steroid PSI find-usages / find-implementations / type-
hierarchy when the mcp-steroid MCP server is reachable — grep
silently misses polymorphic call sites, generic dispatch, identifiers
inside Javadoc/comments/string literals, and recently-renamed
symbols, exactly the cases where a "phantom reference" finding can be
spurious or a real mismatch can hide. Use grep only for filename
globs, unique string literals, and orientation reads. If mcp-steroid
is unreachable in this session, fall back to grep and add an explicit
reference-accuracy caveat to any finding that depends on a symbol
search.

The grep-miss cases listed above are **illustrative, not exhaustive**.
The operative criterion is reference accuracy — would a missed or
spurious match make a finding wrong (phantom reference reported, or
real mismatch hidden)? When in doubt, route through PSI.
`CLAUDE.md` § MCP Steroid → "Grep vs PSI — when to switch" is the last authoritative source for edge cases.

**How to invoke:**
- The MCP server is `mcp-steroid`. Its tools are deferred, so load their schemas via ToolSearch first.
- Call `steroid_list_projects` once at session start to confirm the IDE has the right project open and matches the working tree.
- Run PSI queries (find-usages, find-implementations, type-hierarchy) via `steroid_execute_code`, which evaluates a Kotlin snippet against the PSI tree — there is no dedicated `find_usages` tool.
- For Kotlin recipes, fetch the `coding-with-intellij-psi` skill via `steroid_fetch_resource`.

1. **Read the plan, every pending track's track file, and the design
   document** thoroughly.
2. **Identify all code references** — every class, interface, method,
   SPI, configuration parameter, or file path mentioned in the plan,
   the track files, or the design document.

   **Where track-description code references live:** For each
   **pending** track (`[ ]`), read the track's detailed description
   (what/how/constraints/interactions detail and any track-level
   Mermaid diagram) from `plan/track-N.md`'s `## Purpose / Big Picture`,
   `## Context and Orientation`, `## Plan of Work`, `## Decision Log`,
   and `## Interfaces and Dependencies` sections (`## Decision Log`
   may carry `**Full design**` references per the `edit-design` skill,
   especially once Move 1 inlines per-track Decision Records).
   For **completed** tracks (`[x]`) and **skipped** tracks (`[~]`),
   the plan-file entry already holds the track's final form (intro
   paragraph + track episode for completed; intro + `**Skipped:**`
   reason for skipped) — there is no live track-file description to
   consult, so read code references directly from the plan-file entry.
   Phantom references in a track file's description have the same
   severity as phantom references in the plan file (see the severity
   guide below).
3. **Verify each reference** against the actual codebase. For Java
   symbols, prefer mcp-steroid PSI find-usages / find-implementations /
   type-hierarchy when the IDE is reachable; use Grep/Glob/Read for
   filename globs, unique string literals, file content reads, or when
   mcp-steroid is unreachable. For each reference, confirm it exists,
   is at the described location, and has the described behavior.
4. **Trace workflow diagrams** — for each sequence/flow diagram, read the
   actual source code to verify the described call flow is accurate.
5. **Check for orphans** — code constructs the plan should reference but
   doesn't, design elements no track covers, plan elements with no design
   coverage.

---

## Semi-Formal Reasoning Protocol
<!-- roles=reviewer-plan phases=2 summary="Every claim about code behavior needs a documented certificate; no logical jumps from a plausible name." -->

This review requires **structured evidence certificates** for every claim
about code behavior. You must not assert that a code reference is correct
or incorrect without documented evidence. This prevents logical jumps
("this class probably exists") and catches subtle mismatches (shadowed
methods, renamed classes, changed signatures).

### Certificate requirements
<!-- roles=reviewer-plan phases=2 summary="Ref, flow, and invariant certificate templates each code reference, diagram, and invariant is verified against." -->

**For every code reference verified** (class, interface, method, SPI,
file path, configuration parameter), produce a verification entry:

```markdown
#### Ref: <name from document>
- **Document claim**: <what the plan or design document says — quote or
  paraphrase the specific claim>
- **Search performed**: <PSI find-usages / find-implementations /
  type-hierarchy query when the IDE is reachable; Grep/Glob query
  otherwise. Record which tool was used so the certificate's
  reference-accuracy is auditable.>
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
<!-- roles=reviewer-plan phases=2 summary="Search every claim, follow calls interprocedurally, document negatives, trace flows; certificates feed findings." -->

- **Every claim requires a search.** Do not assume a class exists because
  its name is plausible. Search for it explicitly using mcp-steroid PSI
  when the IDE is reachable; otherwise use grep and note the caveat.
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
<!-- roles=reviewer-plan phases=2 summary="Two-part output: the verification certificates first, then findings derived from non-matching verdicts." -->

### Part 1: Verification Certificates
<!-- roles=reviewer-plan phases=2 summary="The evidence base: all ref, flow, and invariant certificates grouped by consistency axis." -->

Include all certificate entries (Ref, Flow, Invariant) grouped by review
criterion (Design ↔ Code, Plan ↔ Code, Design ↔ Plan, Gaps). This is
the evidence base.

### Part 2: Findings
<!-- roles=reviewer-plan phases=2 summary="Findings derived from non-matching certificate verdicts; each cites its certificate and carries a classification." -->

Derived from certificates with non-MATCHES verdicts. Each finding must
reference the certificate entry that produced it.

For each issue found, produce a finding:

```markdown
### Finding CR<N> [blocker|should-fix|suggestion]
**Certificate**: <Ref/Flow/Invariant entry ID that produced this finding>
**Location**: <which document and section, plus code location if applicable>
**Issue**: <what's inconsistent or missing>
**Evidence**: <what you found in the code vs. what the document says —
  summarize from the certificate>
**Proposed fix**: <concrete change to the plan/design text>
**Classification**: mechanical | design-decision
**Justification**: <one-line citation of the rule from §Classification rules
  below — e.g., "current-state claim, single unambiguous correct rendering"
  or "missing DR for non-obvious choice; user has the rationale">
```

Severity guide:
- **blocker**: A factual error that would cause the execution agent to make
  wrong assumptions (phantom class reference, incorrect call flow, missing
  critical code construct)
- **should-fix**: An inconsistency that could cause confusion but wouldn't
  block execution (slightly outdated method signature, missing cross-reference)
- **suggestion**: An improvement that would strengthen the documents
  (additional diagram, clarifying note about existing code behavior)

---

## Classification rules
<!-- roles=reviewer-plan phases=2 summary="Each finding is mechanical (orchestrator auto-applies) or design-decision (escalate to user); orthogonal to severity." -->

Severity (`blocker | should-fix | suggestion`) tells the user how
urgent the finding is. **Classification** (`mechanical |
design-decision`) tells the orchestrator who decides — itself or the
user. The two axes are orthogonal: a blocker can be mechanical
(phantom reference) and a suggestion can be design-decision (consider
extracting a separate track).

### `mechanical` — orchestrator applies the fix without asking
<!-- roles=reviewer-plan phases=2 summary="Current-state claim, one unambiguous correct rendering, fix preserves plan intent; the orchestrator applies it directly." -->

ALL of these must hold:

1. The plan/design claim is about **current state** (the intent-axis
   pre-screen passed it through as a current-state claim, not a
   target-state claim).
2. There is exactly **one unambiguous correct rendering** of the truth —
   rename to the actual class, update to the real signature, fix the
   participant name in a sequenceDiagram, drop a phantom reference,
   replace a renamed identifier with its current name.
3. Applying the fix **doesn't change what the plan is trying to
   achieve**. Only the description is updated; the plan's goals,
   scope, and architecture are unchanged.

Typical mechanical cases:
- Phantom reference to a class/method/field that should already exist —
  fix by updating to the actual identifier or dropping the reference.
- Outdated method signature on existing code — fix by matching the
  current signature.
- Workflow diagram showing an existing call flow with a renamed
  participant — fix by renaming the participant in the diagram.
- Component Map listing a module that's been split or merged — fix by
  matching the actual module structure.

### `design-decision` — orchestrator escalates to the user
<!-- roles=reviewer-plan phases=2 summary="Missing DR, track contradiction, unimplemented or violated invariant, unreachable target, or ambiguous fix." -->

ANY of these triggers `design-decision`:

- The discrepancy reveals a **missing Decision Record** for a
  non-obvious choice. The user has the rationale, alternatives, and
  trade-offs; the orchestrator does not.
- The discrepancy is a **contradiction between two tracks** (Track 1
  assumes X, Track 3 assumes not-X). Which one is right is a design
  call.
- An **`ASPIRATIONAL` invariant has no implementing track**. Do we add
  a track or change the invariant? Both are plausible.
- A **`VIOLATED` invariant** exists. Do we fix the code (track scope
  expansion) or restate the invariant (design retreat)?
- **Design ↔ code mismatch where the plan describes a target state**
  AND the target is unreachable from the current code (the
  intent-axis pre-screen surfaced it as needing escalation). The
  user must pick: keep the target shape, change it, or restructure
  the track that delivers it.
- **Multiple plausible fix renderings** exist. Even if the claim is
  about current state, if the orchestrator can't pick a single
  correct fix without making a design choice, escalate.

When in doubt between the two classifications, choose
`design-decision` — over-escalating costs one user round-trip, under-
escalating risks silently rewriting the plan.
