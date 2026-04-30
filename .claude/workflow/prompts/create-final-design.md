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
- `docs/adr/<dir-name>/tracks/track-*.md` — all step files with step
  episodes. Each step file begins with a `## Description` section
  carrying the track's original description (copied there at Phase A
  start from the backlog), so "what each track was supposed to do"
  lives in the step file rather than in `implementation-backlog.md`.
  By Phase 4 the backlog is header-only: every track has either
  completed or been skipped, and both paths removed their backlog
  entries.

Using the plan's Architecture Notes and track episodes as a guide, read the
actual implemented code: all classes, interfaces, and components mentioned
in the plan, plus any that emerged during execution.

**Tooling — PSI is required for symbol verification.** The two final
artifacts are committed to git, so the class diagrams, workflow
diagrams, and Decision Record "Implemented in" details must reflect
the *actual* code precisely. Use mcp-steroid PSI find-usages /
find-implementations / type-hierarchy when the mcp-steroid MCP server
is reachable to verify class hierarchies, method signatures, callers
of integration points, and override sets. Grep silently misses
polymorphic call sites, generic dispatch, and identifiers inside
Javadoc/comments — exactly the kinds of mistakes that would mislead
future readers of `design-final.md` and `adr.md`. Fall back to grep
only when mcp-steroid is unreachable, and note any reference-accuracy
caveats inline.

**How to invoke:**
- The MCP server is `mcp-steroid`. Its tools are deferred, so load their schemas via ToolSearch first.
- Call `steroid_list_projects` once at session start to confirm the IDE has the right project open and matches the working tree.
- Run PSI queries (find-usages, find-implementations, type-hierarchy) via `steroid_execute_code`, which evaluates a Kotlin snippet against the PSI tree — there is no dedicated `find_usages` tool.
- For Kotlin recipes, fetch the `coding-with-intellij-psi` skill via `steroid_fetch_resource`.

**Step 3 — Produce the two final artifacts.**

### Ephemeral identifier rule (applies to BOTH artifacts)

`design-final.md` and `adr.md` are the **only** workflow files committed
to git. Every other workflow file —  `implementation-plan.md`,
`implementation-backlog.md`, `tracks/track-N.md`, `reviews/**` — is
untracked and deleted when the branch is deleted after PR merge. Anything
these final artifacts say must survive that deletion.

**The authoritative rule, forbidden/allowed lists, and rewrite examples
live in [`../conventions-execution.md`](../conventions-execution.md)
§2.3.** Read that section and apply it to both artifacts.

Phase-4-specific reminders that follow from the shared rule:

- The `adr.md` Decision Records section is where the final IDs `D1`,
  `D2`, … live. Retain the numbering from the plan for traceability,
  **but only for IDs you are restating in `adr.md` itself**. Do not
  cite a plan-file-only `D<N>` that has no corresponding entry here.
- Do not write `Implemented in: Track X` or `Track X Step Y` lines in
  Decision Records — replace with a prose description, a file/class
  reference, or a commit SHA.
- Key Discoveries is the section most prone to leaks — it is typically
  synthesized from step episodes and easily carries track / step /
  finding labels along with the substance. Rewrite each discovery to
  stand on its own: strip any specific track or step numbers (`Track
  3`, `Step 2 of Track 4`, etc.) and any review-finding IDs, and keep
  only the substance, plus a file/class reference or commit SHA if
  "where" still matters.

Re-scan both artifacts before Step 4 (commit) with the grep in §2.3.

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
- Apply the Ephemeral identifier rule from the top of Step 3 (and
  `../conventions-execution.md` §2.3) to the whole file — especially to
  Decision Records ("Implemented in: …" lines) and Key Discoveries,
  which are the two most frequent leak sites.

**Step 4 — Commit and complete.**

Stage and commit both artifacts in a single commit:

```
Add final workflow artifacts

Post-implementation artifacts:
- design-final.md: actual design reflecting implemented code
- adr.md: architecture decision record with actual outcomes
```

Inform the user that Phase 4 is complete and the workflow is done.
