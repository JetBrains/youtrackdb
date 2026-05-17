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

**Resolve `<dir-name>`.** All subsequent steps reference
`docs/adr/<dir-name>/_workflow/`; resolve the placeholder once before
running any command that uses it. If `"$ARGUMENTS"` is non-empty, use
it. Otherwise, default to `$(git branch --show-current)`.

**Step 1a — Handoff check (mandatory, before any other on-disk work).**
Run:
```bash
ls -t docs/adr/<dir-name>/_workflow/handoff-*.md 2>/dev/null
```
If any files exist, load
[`.claude/workflow/mid-phase-handoff.md`](../../workflow/mid-phase-handoff.md)
and follow its §Resume protocol BEFORE Step 1b. A previous
`/create-plan` session paused mid-research or mid-planning and left a
handoff to be re-presented. Do NOT ask for the aim, start fresh
research, or write plan files until the handoff is resolved.

**Step 1b — Create the workflow directory.**

As the first durable action of `/create-plan`, ensure the workflow
directory exists so research handoff files have a home if context
fills up before Step 4:
```bash
mkdir -p docs/adr/<dir-name>/_workflow/plan
```
This is idempotent — safe to re-run on resume. The directory carries
the plan, design, track files, and handoff files; the Phase 4 cleanup
commit removes it before merge (see
`.claude/workflow/conventions.md` §1.2).

**Step 2 — Ask the user for the aim.**

After you have finished reading the workflow documents, ask the user to describe the aim and goal for this session. Do NOT proceed until the user provides the aim. Wait for the user's response before starting any research or planning work.

The plan will be saved to:
`docs/adr/<dir-name>/_workflow/implementation-plan.md`
(the `_workflow/` subdir holds every ephemeral working file — plan,
design, track files, reviews — and is removed in the Phase 4
cleanup commit before merge; see `conventions.md` §1.2 and
`workflow.md` § Final Artifacts).
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
     `plan/track-N.md` track file whose `## Purpose / Big Picture`
     section carries a one-line BLUF followed by the same intro
     paragraph. The track's detailed content spreads across three
     other plan-at-start homes (no length cap on any of them):
     `## Context and Orientation` carries the codebase state at the
     start of the track and the concrete deliverables it produces;
     `## Plan of Work` carries the prose sequence of edits and
     additions plus ordering constraints and invariants to preserve;
     `## Interfaces and Dependencies` carries in-scope/out-of-scope
     file boundaries, inter-track dependencies, and library/function
     signatures. See `conventions-execution.md` §2.1 for the
     canonical section list and lifecycle.
   - Include a track-level Mermaid component diagram inside the
     track file's `## Context and Orientation` section when the
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
   `docs/adr/<dir-name>/_workflow/design.md`. The design document must include:
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

Write the implementation plan to
`docs/adr/<dir-name>/_workflow/implementation-plan.md` AND one track
file per planned track at
`docs/adr/<dir-name>/_workflow/plan/track-N.md` using the two
structures below. The plan carries strategic context (Goals,
Constraints, Architecture Notes, Decision Records) plus a thin
checklist; each track file carries that track's detail spread
across the four 14-section homes: `## Purpose / Big Picture`
(intro paragraph), `## Context and Orientation` (codebase state and
any track-level Mermaid diagram), `## Plan of Work` (prose sequence
of edits and additions), and `## Interfaces and Dependencies`
(in-scope/out-of-scope file boundaries, inter-track dependencies,
library/function signatures). Keeping per-track detail out of the
plan keeps `/execute-tracks` startup context small (see
`.claude/workflow/conventions.md` §1.2 for the directory layout
under `_workflow/` and `conventions-execution.md` §2.1 for the
track-file shape and section lifecycle).

```
# <Feature Name>

## Design Document
[design.md](design.md)

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
  > <intro paragraph — high-level context; detailed description in plan/track-1.md>
  > **Scope:** ~N steps covering X, Y, Z

- [ ] Track 2: <title>
  > <intro paragraph — high-level context; detailed description in plan/track-2.md>
  > **Scope:** ~N steps covering A, B
  > **Depends on:** Track 1

## Plan Review
- [ ] Plan review (consistency + structural) — autonomous; runs as the first phase of `/execute-tracks`

## Final Artifacts
- [ ] Phase 4: Final artifacts (`design-final.md`, `adr.md`)
```

Each track file (`plan/track-N.md`) is created with the four
plan-at-start homes (`## Purpose / Big Picture`,
`## Context and Orientation`, `## Plan of Work`,
`## Interfaces and Dependencies`) populated and the track-level
prose in `## Validation and Acceptance` populated (per-step
EARS/Gherkin lines are Phase A placeholders), the continuous-log
sections empty, and the Phase-A-populated sections
(`## Concrete Steps`, `## Idempotence and Recovery`) left as Phase A
placeholders that decomposition will fill. The canonical section list and lifecycle
table — which writer touches which section in which phase — live in
`conventions-execution.md` §2.1; the verbatim ready-to-paste
template body is reproduced below so this SKILL stays
self-sufficient (the lifecycle source is durable; the design-doc
copy is ephemeral and removed in the Phase 4 cleanup commit, so it
cannot be a durable pointer target).

````markdown
# Track N: <title>

## Purpose / Big Picture
<One-line BLUF stating the user-visible behavior gained after this track lands.>

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

<Intro paragraph from the plan checklist entry, restated here so the file
is self-sufficient — Phase B/C sub-agents that don't read the root plan
see it.>

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation
<What state the codebase is in at the start of this track — files,
modules, non-obvious terminology, concrete deliverables this track
produces. Place any optional track-level Mermaid component diagram
(≤10 nodes) inside this section when the track has 3+ internal
components with non-trivial interactions.>

## Plan of Work
<Prose sequence of edits and additions — the approach, ordering
constraints, invariants to preserve, references to the Concrete
Steps roster below. Phase 1 writes the approach prose; Phase A
appends a per-step sequencing summary that references the Concrete
Steps roster.>

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. Per-step episodes do NOT live here; they live
in `## Episodes` below. The roster is immutable after Phase A except
for the status checkbox flip and the optional `commit:` annotation
Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance
<Track-level behavioral acceptance criteria.>

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies
<In-scope and out-of-scope file boundaries, compatibility
requirements, inter-track dependencies (which other tracks supply
prerequisites; which downstream tracks consume this one's output),
and library/function signatures relevant to this track.>
````

The `## Base commit` section is added by Phase B at session start
and is omitted from the Phase 1 skeleton. Full lifecycle for every
section above is tabulated in `conventions-execution.md` §2.1.

Write the design document to
`docs/adr/<dir-name>/_workflow/design.md` using this structure:

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

**Step 5 — Commit, push, and open the draft PR.**

Once the user confirms the plan and design files look right, persist
the work to GitHub so it survives local-disk loss and is visible to
teammates as a draft PR:

1. Stage and commit the `_workflow/` files in a single commit:
   ```bash
   git add docs/adr/<dir-name>/_workflow/
   git commit -m "Add initial implementation plan and design"
   ```
2. Push the branch:
   ```bash
   git push -u origin <branch>
   ```
   (Use `git push` on subsequent pushes once upstream is set.)
3. Ask the user **once**, before opening the PR:
   *"Provide an issue prefix for the PR title (e.g. `YTDB-123`)?
   Leave blank to skip."*
   Branch names in this project often do not encode the issue
   prefix; the user tracks it in the PR title instead.
4. Compose the PR title:
   - With a prefix `<P>`: `[<P>] <feature title>` — e.g.
     `[YTDB-123] Index histogram for selective range scans`
   - Without a prefix: `<feature title>`
5. Compose the PR body from the plan: `## Motivation` (the plan's
   Goals + Constraints, distilled into prose — apply the Ephemeral
   identifier rule from `conventions-execution.md` §2.3 to the body
   since PR titles and descriptions are durable), `## Plan` (one
   line per track from the checklist, no internal IDs), and a
   `## Status` line stating *"Draft — workflow scaffolding under
   `docs/adr/<dir-name>/_workflow/` will be removed in the Phase 4
   cleanup commit before merge."*
6. Open the PR in **draft** mode using `gh`:
   ```bash
   gh pr create --draft --base develop \
       --title "<title built above>" \
       --body "$(cat <<'EOF'
   ...
   EOF
   )"
   ```
   Print the resulting PR URL so the user can share it.

CI does not run on draft PRs, so the per-commit pushes through the
rest of the workflow carry no CI cost. The user manually flips the
PR from draft to "ready for review" at the end of Phase 4 — Claude
never runs `gh pr ready` automatically.

When I'm satisfied, I'll run `/execute-tracks` to start track execution.
The autonomous plan review (Phase 2 — consistency + structural) runs as
its first phase and ends the session before track work begins. I can
also run `/review-plan` manually at any time to re-validate the plan
(useful after inline replanning produces a revised plan).
