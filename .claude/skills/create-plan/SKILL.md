---
name: create-plan
description: "Research the codebase and create an implementation plan with architecture notes, design document, and track decomposition. Use when starting a new feature or large change."
argument-hint: "[plan-directory-name]"
user-invocable: true
---

Read and follow the workflow for Phase 0 (Research) and Phase 1 (Planning).

**Step 1 — Read workflow documents.**

Read these in order before doing anything else (do NOT ask the user anything yet):
1. `.claude/workflow/conventions.md` — shared formats,
   glossary, plan file structure, scope indicators, review iteration protocol
2. `.claude/workflow/research.md` — Phase 0 instructions:
   interactive research, code exploration, internet research, transition rules

Do **NOT** read `.claude/workflow/planning.md` or
`.claude/workflow/design-document-rules.md` yet — they are only needed when
the user asks to create the plan (Step 4). Load them on demand at that point.

**Step 2 — Ask the user for the aim.**

After you have finished reading the workflow documents, ask the user to describe the aim and goal for this session. Do NOT proceed until the user provides the aim. Wait for the user's response before starting any research or planning work.

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the directory
name. Otherwise, default to the current git branch name
(`git branch --show-current`).

The plan will be saved to: docs/adr/<dir-name>/implementation-plan.md
The codebase is at the current working directory.

**Step 3 — Research phase (Phase 0).**

Once the user provides the aim, enter **research mode**. In this mode:
- Answer user questions about the codebase, architecture, and design
- Explore code (read files, search for patterns, trace call chains)
- Perform internet research when asked (web search, fetch documentation)
- Present findings and intermediate conclusions
- Help the user evaluate trade-offs and alternatives
- Do **NOT** produce plan files, design documents, or track decompositions

Stay in research mode until the user explicitly asks to create the plan
(e.g., "create the plan", "let's plan this", "proceed to planning").

**Step 4 — Transition to planning (Phase 1).**

When the user asks to create the plan:

First, read the planning workflow documents (deferred from Step 1):
1. `.claude/workflow/planning.md` — Phase 1 instructions:
   goal, plan file structure, architecture notes format, track descriptions,
   scope indicators, checklist decomposition rules
2. `.claude/workflow/design-document-rules.md` — design document rules,
   structure, and examples

Then summarize the key research findings and decisions from the conversation,
and proceed to planning.

The plan and design document **must** incorporate findings and decisions
from the research phase:
- Decision Records should reflect alternatives explored during research
- Architecture Notes should build on codebase exploration findings
- Track descriptions should incorporate constraints discovered during research
- The design document should reflect design choices discussed with the user

Help the user develop the plan:
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
   - Every track gets an **intro paragraph** in the plan checklist
     entry (a short paragraph of high-level context) and a matching
     `## Track N: <title>` section in `implementation-backlog.md`
     carrying the detailed `**What**:` / `**How**:` /
     `**Constraints**:` / `**Interactions**:` subsections (no length
     cap on the detail). See `planning.md` §Track descriptions for
     what each subsection should cover.
   - Include a track-level Mermaid component diagram in the backlog
     (immediately after the `**Interactions**:` blockquote) when the
     track has 3+ internal components with non-trivial interactions.
     Track-level diagrams are **never rendered in the plan file**.
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

Write both the implementation plan to
`docs/adr/<dir-name>/implementation-plan.md` AND the track-details
backlog to `docs/adr/<dir-name>/implementation-backlog.md` using the
two structures below. The plan carries strategic context (Goals,
Constraints, Architecture Notes, Decision Records) plus a thin
checklist; the backlog carries each track's detailed
`**What/How/Constraints/Interactions**` subsections and any
track-level Mermaid diagrams. Splitting keeps `/execute-tracks`
startup context small — see `.claude/workflow/conventions.md` §1.2 for
the full rules (backlog file shape, D4 legacy-compat detection,
load-bearing-file rule).

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
  > <intro paragraph — high-level context; detailed description in implementation-backlog.md>
  > **Scope:** ~N steps covering X, Y, Z

- [ ] Track 2: <title>
  > <intro paragraph — high-level context; detailed description in implementation-backlog.md>
  > **Scope:** ~N steps covering A, B
  > **Depends on:** Track 1
```

````markdown
# <Feature Name> — Track Details

<!-- DO NOT DELETE THIS FILE. Its presence on disk signals the new
split-file plan format (see .claude/workflow/conventions.md §1.2).
Deleting it flips subsequent workflow operations into legacy mode.
Natural cleanup happens when the branch is deleted after PR merge. -->

## Track 1: <title>

> **What**:
> - <bullet list of concrete deliverables>
>
> **How**:
> - <approach notes, ordering constraints, invariants to preserve>
>
> **Constraints**:
> - <in-scope/out-of-scope files, compatibility requirements>
>
> **Interactions**:
> - <how this track depends on or enables other tracks>

```mermaid
<optional track-level component diagram (≤10 nodes); see planning.md>
```

## Track 2: <title>

> **What**: …
> **How**: …
> **Constraints**: …
> **Interactions**: …

<!-- The Track 2 above uses a condensed one-line-per-label style as a
layout exemplar; expand each track section to carry the full
description content per planning.md §Track descriptions. -->
````

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
