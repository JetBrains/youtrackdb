Read and follow the workflow for Phase 1 (Planning).

**Step 1 — Read workflow documents.**

Read these in order before doing anything else (do NOT ask the user anything yet):
1. `.claude/workflow/conventions.md` — shared formats,
   glossary, plan file structure, scope indicators, review iteration protocol
2. `.claude/workflow/planning.md` — Phase 1 instructions:
   goal, plan file structure, architecture notes format, track descriptions,
   scope indicators, checklist decomposition rules

**Step 2 — Ask the user for the aim.**

After you have finished reading the workflow documents, ask the user to describe the aim and goal for this planning session. Do NOT proceed until the user provides the aim. Wait for the user's response before starting any research or planning work.

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the directory
name. Otherwise, default to the current git branch name
(`git branch --show-current`).

The plan will be saved to: docs/adr/<dir-name>/implementation-plan.md
The codebase is at the current working directory.

Once the user provides the aim, help them develop the plan:
1. Understand the relevant parts of the codebase — explore the modules,
   packages, and classes relevant to the goal. Build a mental model before
   proposing anything.
2. Identify key decisions and constraints — technical, performance,
   compatibility, and process constraints that will shape the plan.
3. Produce Architecture Notes following the workflow rules:
   - Component Map (required): Mermaid diagram if 3+ components with
     non-trivial relationships, always paired with annotated bullet list.
   - Decision Records (required): one per non-obvious design choice, with
     alternatives, rationale, risks, and track references.
   - Invariants & Contracts (if applicable): must map to testable assertions.
   - Integration Points (if applicable): how new code connects to existing code.
   - Non-Goals (if applicable): explicit scope boundaries.
4. Decompose the work into tracks with full descriptions following the
   workflow rules:
   - Every track gets a mandatory description block (no length cap) covering
     what/how/constraints/interactions with other tracks.
   - Include track-level Mermaid component diagrams when the track has 3+
     internal components with non-trivial interactions.
   - Track sizing rule: if a track would need more than ~5-7 steps, split
     it into separate dependent tracks. The execution agent handles
     sequencing and episode propagation between dependent tracks.
5. For each track, include a **Scope indicator**:
   - Format: `> **Scope:** ~N steps covering X, Y, Z`
   - Approximate step count + brief list of major work pieces
   - These are strategic signals, not tactical commitments — step
     decomposition happens during Phase 3 execution.
   - Do NOT include full `- [ ] Step:` items or *(provisional)* markers.
   - Focus energy on track descriptions and architecture, not premature
     step decomposition.
6. Order the tracks so dependencies are respected — earlier tracks don't
   depend on later ones. Annotate dependencies with
   `> **Depends on:** Track N`.
7. Identify key test scenarios and invariants that must be covered — this
   is strategic (what to test and why), not tactical (how to implement tests).
8. Produce a **Design Document** (separate file) following the workflow rules
   in `planning.md` §Design Document. Write it to
   `docs/adr/<dir-name>/design.md`. The design document must include:
   - **Class diagrams** (Mermaid `classDiagram`) showing new/modified classes,
     interfaces, and their relationships
   - **Workflow diagrams** (Mermaid `sequenceDiagram` or `flowchart`) showing
     runtime behavior of key operations
   - **Dedicated sections for complex or opaque parts** — concurrency,
     crash recovery, performance-critical paths, non-obvious invariants, etc.
   - All diagrams must be Mermaid. Every diagram must be paired with prose.
   - Design level, not code level — describe structure and behavior, not
     implementation details.

Do NOT implement anything. Only research and plan.

Write the implementation plan to `docs/adr/<dir-name>/implementation-plan.md`
using this structure:

```
# <Feature Name>

## High-level plan

### Goals
<what this feature achieves and why>

### Constraints
<technical, performance, compatibility, or process constraints>

### Architecture Notes

#### Component Map
<Mermaid diagram + annotated bullet list>

#### D1: <Decision title>
- **Alternatives considered**: <what else was on the table>
- **Rationale**: <why this option won — trade-offs, constraints>
- **Risks/Caveats**: <known downsides or things to watch>
- **Implemented in**: Track X (step references added during execution)

#### Invariants
<if applicable>

#### Integration Points
<if applicable>

#### Non-Goals
<if applicable>

## Checklist
- [ ] Track 1: <title>
  > <track description: what/how/constraints/interactions>
  > <track-level component diagram if needed>
  > **Scope:** ~N steps covering X, Y, Z

- [ ] Track 2: <title>
  > <track description: what/how/constraints/interactions>
  > **Scope:** ~N steps covering A, B
  > **Depends on:** Track 1
```

Write the design document to `docs/adr/<dir-name>/design.md` using this
structure:

```
# <Feature Name> — Design

## Overview
<Brief summary of the design approach — what the solution looks like at a
structural level, which major components are involved, and how they interact.>

## Class Design
<Mermaid classDiagram(s) showing new/modified classes, interfaces, relationships.
Pair each diagram with prose explaining responsibilities and design choices.>

## Workflow
<Mermaid sequenceDiagram(s) and/or flowchart(s) showing runtime behavior of key
operations. Pair each diagram with prose explaining the flow.>

## <Complex Topic 1>
<What the complex part is, why it is designed this way, gotchas/edge cases.>

## <Complex Topic 2>
<What the complex part is, why it is designed this way, gotchas/edge cases.>
```

When I'm satisfied, I'll run `/review-plan` to review the plan, then
`/execute-tracks` to execute track by track.
