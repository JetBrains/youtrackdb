# Design Document Rules

The plan must be accompanied by a separate **design document** at
`docs/adr/<dir-name>/design.md` that explains **what will be implemented at a
design level** — not code, but the structural and behavioral design of the
solution.

## Purpose

- Bridge the gap between high-level architecture (Component Map, Decision Records)
  and track-level execution details
- Make complex or non-obvious parts of the implementation explicit so the execution
  agent and reviewers can verify intent without reverse-engineering code
- Provide a single place where the overall design can be understood as a coherent
  whole, not just as a collection of tracks
- Hold the **long-form** material that supports plan-level decisions
  (worked examples, layered diagrams, multi-paragraph rationale,
  crash-scenario walk-throughs) so the implementation plan can stay
  thin and strategic — see "Boundary with the implementation plan"
  below.

## Boundary with the implementation plan

The plan corpus is split across three files with a strict content
boundary. Putting prose in the wrong file inflates the
`/execute-tracks` startup load (the plan file is read at every
session) and routinely produces duplication between the plan and the
design document.

| File | What it carries |
|---|---|
| `implementation-plan.md` | Goals, constraints, the **decisions themselves** (alternatives / rationale / risks / where-implemented / link-to-design), the Component Map (topology + short intent bullets), short invariant statements, short integration-point bullets, the track checklist. **Strategic, scannable, loaded every session.** |
| `design.md` | Class diagrams, sequence/flow diagrams, the **long-form rationale** that supports a decision (when one exists), worked examples, layered designs, complex-topic walk-throughs (concurrency, crash recovery, performance paths), full workflow descriptions. **Long-form, loaded only when referenced.** |
| `implementation-backlog.md` | Per-track concrete deliverables — files, classes, methods, edit lists, ordering constraints, track-level diagrams. **Per-track edit detail, loaded only in Phase A of one track per session.** |

> **The rule, succinctly:** if you find yourself writing a worked
> example, a multi-paragraph derivation, a code-change inventory, or
> a "here is how all the pieces fit together" walk-through inside a
> decision record, an invariant, or an integration-point bullet,
> **stop and move it to `design.md`** (or, if it is per-track edit
> detail, to `implementation-backlog.md`). Replace the original
> location with a one-line link.

The reciprocal pointer is the `**Full design**: design.md §<section>`
line in the Decision Record template (see `planning.md` § Decision
Records). When a DR has long-form support, the DR itself stays at the
four-bullet form and the long-form material lives in `design.md` under
a section the DR links to.

**What this looks like in practice:**

- A decision whose rationale is "we picked B over A because A doesn't
  satisfy invariant X" — that's a 1-line rationale, no `design.md`
  section needed.
- A decision whose rationale needs a worked example (e.g., walking
  through what happens to a transaction when the rollback log is
  evicted mid-commit) — keep the four-bullet rationale at one
  sentence, then add a `design.md` section titled "Rollback log
  eviction during commit" that walks the example, and link to it from
  the DR's `**Full design**` line.
- An invariant like "WAL atomic operation boundaries enclose the
  histogram update" — one bullet, no `design.md` section needed.
- An invariant whose semantics need a multi-paragraph derivation
  (e.g., why the read path is safe under concurrent eviction) — keep
  the invariant entry at one bullet stating the rule, and add a
  `design.md` complex-topic section that derives it.

## Required content

**1. Class diagrams (Mermaid `classDiagram`)** — Show the key classes, interfaces,
and their relationships that this plan introduces or modifies. Focus on:
- New classes/interfaces and their responsibilities
- Inheritance and composition relationships
- Key method signatures that define the contracts between components
- Only include classes relevant to this plan — do not diagram the entire codebase

Include class diagrams when the plan introduces 2+ new classes/interfaces or
modifies relationships between existing classes.

Example:

````markdown
```mermaid
classDiagram
    class IndexStatistics {
        <<interface>>
        +getHistogram(indexName) Histogram
        +hasHistogram(indexName) boolean
    }
    class BTreeHistogramProvider {
        -pageCache: PageCache
        +getHistogram(indexName) Histogram
        +hasHistogram(indexName) boolean
    }
    class Histogram {
        -buckets: long[]
        -boundaries: Comparable[]
        +estimateSelectivity(min, max) double
    }
    IndexStatistics <|.. BTreeHistogramProvider
    BTreeHistogramProvider --> Histogram
    BTreeHistogramProvider --> PageCache
```
````

**2. Workflow/sequence diagrams (Mermaid `sequenceDiagram` or `flowchart`)** — Show
the runtime behavior of key operations. Use sequence diagrams for interactions
between components over time; use flowcharts for decision logic or state transitions.

Include workflow diagrams when the plan introduces a new operation flow or
significantly modifies an existing one.

Example:

````markdown
```mermaid
sequenceDiagram
    participant QO as QueryOptimizer
    participant SF as StatisticsFacade
    participant HR as HistogramReader
    participant PC as PageCache

    QO->>SF: estimateSelectivity(index, min, max)
    SF->>SF: hasHistogram(index)?
    alt histogram exists
        SF->>HR: readHistogram(index)
        HR->>PC: snapshot read (no lock)
        PC-->>HR: page data
        HR-->>SF: Histogram
        SF->>SF: histogram.estimateSelectivity(min, max)
        SF-->>QO: estimated selectivity
    else no histogram
        SF-->>QO: default 0.1
    end
```
````

**3. Dedicated paragraphs for complex or opaque parts** — Any part of the design
that is non-obvious, involves subtle trade-offs, or could be misunderstood must
have its own section with:
- **What** the complex part is
- **Why** it is designed this way (not just what it does, but the reasoning)
- **Gotchas** — subtle behaviors, edge cases, or invariants that are easy to miss

Mark these with a `## <Topic>` heading. Examples of things that warrant dedicated
sections:
- Concurrency or locking strategies
- Crash recovery or durability guarantees
- Performance-sensitive paths with specific algorithmic choices
- Backward compatibility shims or migration logic
- Interactions with external systems or SPIs

## Rules

1. **Separate file** — the design document lives at `docs/adr/<dir-name>/design.md`,
   not inside the implementation plan.
2. **All diagrams must be Mermaid** — use `classDiagram`, `sequenceDiagram`,
   `flowchart`, or `stateDiagram` as appropriate. No external tools or image files.
3. **Design level, not code level** — describe classes, interfaces, relationships,
   and flows. Do not include implementation details like variable names, loop
   constructs, or error handling minutiae.
4. **Pair every diagram with prose** — a diagram without explanation is ambiguous.
   Always follow a diagram with a brief description of what it shows and why the
   design was chosen.
5. **Keep diagrams focused** — cap class diagrams at ~10-12 classes, sequence
   diagrams at ~6-8 participants. Split into multiple diagrams if larger.
6. **Complex parts are mandatory** — if any part of the design involves concurrency,
   crash recovery, performance-critical paths, or non-obvious invariants, it MUST
   have a dedicated section. Omitting these is a structural review finding.
7. **Frozen after Phase 1** — the original `design.md` is never modified after
   planning. Phase 4 produces `design-final.md` (actual design) and `adr.md`
   (architecture decisions with actual outcomes) — the only git-tracked
   workflow artifacts.

## Structure

```markdown
# <Feature Name> — Design

## Overview
<Brief summary of the design approach — what the solution looks like at a
structural level, which major components are involved, and how they interact.>

## Class Design
<Mermaid classDiagram(s) + prose explaining responsibilities and relationships>

## Workflow
<Mermaid sequenceDiagram(s) and/or flowchart(s) + prose explaining runtime flows>

## <Complex Topic 1>
<Dedicated paragraph: what, why, gotchas>

## <Complex Topic 2>
<Dedicated paragraph: what, why, gotchas>
```
