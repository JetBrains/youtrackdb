Read and follow the workflow for Phase 4 (Final Artifacts).

**Step 1 — Read workflow documents.**

Read these before doing anything else:
1. `.claude/workflow/conventions.md` — shared formats, plan file structure
2. `.claude/workflow/design-document-rules.md` — design document rules
3. `.claude/workflow/workflow.md` — §Final Artifacts (Phase 4)

**Step 2 — Read all workflow working files and the implemented code.**

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the directory
name. Otherwise, default to the current git branch name
(`git branch --show-current`).

Read:
- `docs/adr/<dir-name>/implementation-plan.md` — full plan with track episodes
- `docs/adr/<dir-name>/design.md` — original design document (do NOT modify)
- `docs/adr/<dir-name>/tracks/track-*.md` — all step files with step episodes

Using the plan's Architecture Notes and track episodes as a guide, read the
actual implemented code: all classes, interfaces, and components mentioned
in the plan, plus any that emerged during execution.

**Step 3 — Produce the two final artifacts.**

### Artifact 1: Final Design Document (`design-final.md`)

Write `docs/adr/<dir-name>/design-final.md` reflecting the **actual
implementation**. Same structure as `design.md` but based on real code:

```
# <Feature Name> — Final Design

## Overview
<What was actually built. Note high-level deviations from the original plan.>

## Class Design
<Mermaid classDiagram(s) of actual classes/interfaces. Pair with prose.>

## Workflow
<Mermaid sequenceDiagram(s)/flowchart(s) of actual runtime behavior.
Pair with prose.>

## <Complex Topic>
<How this was actually implemented, why it differs from the plan (if it does),
gotchas discovered.>
```

Rules:
- All diagrams must be Mermaid. Reflect reality, not the plan.
- Pair every diagram with prose.
- Keep diagrams focused (class ≤ ~12, sequence ≤ ~8 participants).
- Complex parts (concurrency, crash recovery, performance paths) are mandatory.
- Do NOT modify `design.md`.

**Verification protocol:** Before writing each diagram, build a
verification table to ensure the diagram reflects actual code:

For class diagrams:
```
| Diagram Element        | Code Location        | Verified? | Notes           |
|------------------------|----------------------|-----------|-----------------|
| Class X                | file:line            | YES/NO    | actual name/role |
| X extends Y            | file:line            | YES/NO    |                 |
| X.method(args): return | file:line            | YES/NO    | actual signature |
```

For workflow/sequence diagrams:
```
| Step | Diagram Claim                 | Code Location | Actual Behavior | Match? |
|------|-------------------------------|---------------|-----------------|--------|
| 1    | Caller → method(args)         | file:line     | [what happens]  | YES/NO |
| 2    | Method → delegate(args)       | file:line     | [what happens]  | YES/NO |
```

Every element in the diagram must have a corresponding row. Do not
include classes, methods, or flows that you have not verified exist in
the current code. The tables do not appear in the final artifact — they
are working notes that ensure accuracy.

### Artifact 2: ADR (`adr.md`)

Write `docs/adr/<dir-name>/adr.md` — a post-implementation Architecture
Decision Record derived from `implementation-plan.md`, adjusted for actual
outcomes using insights from all episodic memories.

**Episodic memory aggregation:** Scan **all step episodes first** (they
contain ground-truth details — "What was discovered", "What was done",
"What changed from the plan"), then cross-reference with **track episodes**
(which add strategic framing). Both levels must be aggregated — track
episodes are summaries that may omit step-level details important for
future work. Every discovery and plan deviation from either level should
be evaluated for inclusion in the ADR.

```
# <Feature Name> — Architecture Decision Record

## Summary
<What problem it solves, what was built.>

## Goals
<Adjusted for actual outcomes. Note descoped or changed goals.>

## Constraints
<Note relaxed constraints or new ones discovered.>

## Architecture Notes

### Component Map
<Updated Mermaid diagram + bullet list reflecting actual topology.>

### Decision Records
<All decisions from the plan, updated for actual outcomes:
- Implemented as planned → note it
- Modified during execution → update rationale, note what changed and why
- New decisions that emerged → add with rationale
Retain D1, D2, ... numbering; append new decisions at the end.>

### Invariants & Contracts (if applicable)
### Integration Points (if applicable)
### Non-Goals (if applicable)

## Key Discoveries
<Synthesized from both track episodes AND step episodes — important things
learned during implementation that weren't known at planning time. Step
episodes are the primary source (ground truth); track episodes provide
strategic framing. Include discoveries that would affect future work in
the same area, even if they seem minor at the step level.>
```

Rules:
- No track details — captures decisions and outcomes, not execution process.
- Aggregate from both episode levels — do not rely on track episodes alone,
  as they may omit step-level details.
- Retain original decision numbering for traceability.

**Step 4 — Commit and complete.**

Stage and commit both artifacts in a single commit:

```
Add final workflow artifacts

Post-implementation artifacts:
- design-final.md: actual design reflecting implemented code
- adr.md: architecture decision record with actual outcomes
```

Inform the user that Phase 4 is complete and the workflow is done.
