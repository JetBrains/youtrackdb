Read and follow the workflow for Phase 4 (Final Design Document).

**Step 1 — Read workflow documents.**

Read these before doing anything else:
1. `.claude/workflow/conventions.md` — shared formats, glossary, plan file structure
2. `.claude/workflow/design-document-rules.md` — design document rules and structure
3. `.claude/workflow/workflow.md` — §Final Design Document (Phase 4) for the
   purpose and process

**Step 2 — Read the implementation plan and original design document.**

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the directory
name. Otherwise, default to the current git branch name
(`git branch --show-current`).

Read:
- `docs/adr/<dir-name>/implementation-plan.md` — the full plan with all track
  episodes (these document what was discovered and what deviated from the plan)
- `docs/adr/<dir-name>/design.md` — the original design document from Phase 1
  (this is the "planned" design — do NOT modify it)
- `docs/adr/<dir-name>/tracks/track-*.md` — all track step files. These contain
  detailed step episodes documenting what was actually implemented, what failed,
  and what design deviations occurred. This is the richest source of context for
  understanding why the final design differs from the planned design.

**Step 3 — Read the implemented code.**

Using the implementation plan's Architecture Notes (Component Map, Decision
Records) and track episodes as a guide, read the actual implemented code:
- All classes, interfaces, and components mentioned in the plan
- Any new classes/interfaces that emerged during execution (mentioned in track
  episodes or step files)
- Key method signatures and relationships between components

Build a complete picture of what was actually built, not what was planned.

**Step 4 — Produce the final design document.**

Write `docs/adr/<dir-name>/design-final.md` reflecting the **actual
implementation**. Follow the same structure as the original `design.md` but
based on the real code:

```
# <Feature Name> — Final Design

## Overview
<Brief summary of what was actually built — the real design as implemented,
not the planned design. Note any high-level deviations from the original plan.>

## Class Design
<Mermaid classDiagram(s) showing the classes/interfaces as they actually exist
in the codebase. Pair each diagram with prose explaining responsibilities and
any deviations from the planned design.>

## Workflow
<Mermaid sequenceDiagram(s) and/or flowchart(s) showing the actual runtime
behavior. Pair each diagram with prose explaining the flow and any differences
from the planned flow.>

## <Complex Topic 1>
<How this complex part was actually implemented, why it differs from the plan
(if it does), gotchas discovered during implementation.>

## <Complex Topic 2>
<How this complex part was actually implemented, why it differs from the plan
(if it does), gotchas discovered during implementation.>
```

Rules:
- **All diagrams must be Mermaid** — `classDiagram`, `sequenceDiagram`,
  `flowchart`, or `stateDiagram` as appropriate.
- **Reflect reality, not the plan** — if the implementation diverged from the
  original design, the final document shows what was built. Use track episodes
  to understand why deviations occurred.
- **Pair every diagram with prose** — explain what the diagram shows and, where
  relevant, how it differs from the original design.
- **Keep diagrams focused** — same sizing rules as the original: class diagrams
  ≤ ~12 classes, sequence diagrams ≤ ~8 participants.
- **Complex parts are mandatory** — same rule as the original design document:
  concurrency, crash recovery, performance-critical paths, non-obvious
  invariants must have dedicated sections.
- **Do NOT modify `design.md`** — the original stays untouched for comparison.

**Step 5 — Commit.**

Commit `design-final.md` with a message explaining this is the
post-implementation design document.
