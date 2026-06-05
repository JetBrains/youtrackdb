---
name: create-plan
description: "Research the codebase and create an implementation plan with architecture notes, design document, and track decomposition. Use when starting a new feature or large change."
argument-hint: "[plan-directory-name]"
user-invocable: true
---

## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (a file whose only `## ` heading is this bootstrap block carries none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains any of your roles (or your role is `any`, or the row's Roles is `any`) AND Phases contains any of your phases (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections; if no row matches your role/phase, the file holds nothing for you — do not read further.

Your role: planner.
Your phase: determined by the auto-resume State in `workflow.md` § Startup Protocol.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: a ref matches when any of your roles is in its roles and any of your phases is in its phases, your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

Read and follow the workflow for Phase 0 (Research) and Phase 1 (Planning).

> **House style for chat-scale prose.** User-facing prose produced from this file (status updates, escalation prompts, replanning summaries, review-mode loop turns, handoff notes, whichever apply) follows the AI-tell subset of `house-style.md`: `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`. Structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`) do not apply to chat-scale prose. See conventions.md:planner:0,1 `§1.5` for the workflow-level anchor and tier mapping.

> **Stamp discipline.** Every `_workflow/**` artifact this SKILL creates carries a line-1 `<!-- workflow-sha: <40-char SHA> -->` stamp written at creation. Direct-mutation kinds applied later by `edit-design` (`content-edit`, `section-add`, `section-remove`, `section-rename`, `section-move`, `structural-rewrite`, `mechanics-edit`, `design-sync`) leave the stamp untouched and preserve its line-1 position; only artifact creation, migration replay, and no-drift normalization write the stamp. The format definition, parser idioms, and the paired SHA-computation idiom this SKILL copies into its planning-transition step are anchored in conventions.md:planner:1 `§1.6`. Read that section for the single source of truth.

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

**Step 1.5 — Workflow drift check (mandatory, before any other on-disk work).**

**Ordering:** this step depends on the `<dir-name>` resolver above being complete and Step 1b's `mkdir` not yet having run — see the trailing paragraph below for the gate's Skip-#1 rationale.

Invoke the drift gate defined in
workflow-drift-check.md:planner:1.
The gate is shared with `/execute-tracks`; its intro names both callers
and its body is caller-symmetric, so this step is a thin orchestration
handoff that defers to the gate rather than restating its detection. Run
the gate's § Detection against the resolved `<dir-name>` from the
previous block. Detection now runs the two-phase drift walk inside
`.claude/scripts/workflow-startup-precheck.sh` under `--mode full` and
reads the resulting `drift` JSON object; the script resolves the plan
dir from the active branch, so no inline `PLAN_DIR=` bash line runs
here. Follow its § Skip conditions, § No-drift normalization, and
§ Resolutions flow verbatim.

The three-resolution prompt fires only when drift surfaces and no
skip condition matched. The user picks one:

- **Migrate now** — print `Run /migrate-workflow from this worktree,
  then re-invoke /create-plan afterward.` (the single instruction
  line per `workflow-drift-check.md` § Migrate now, with the
  `/create-plan` re-invocation hint appended), then end the session.
  Exit immediately; no on-disk work has run yet (Step 1b's `mkdir`,
  Step 2's aim prompt, and Step 5's commit and push are all
  downstream of Step 1.5).
- **Defer** — continue this session. Record the deferred-drift count
  via the TaskCreate todo described in `workflow-drift-check.md`
  `§ Defer`; Step 5's deferred-drift recital reads that todo and prints
  the same line shape `workflow.md § What to do before ending a session` uses for `/execute-tracks`. If TaskCreate is unavailable
  in this session, hold the `<count>` and `<short-stamp-base-SHA>`
  (or the unstamped variant flag) in in-context memory instead,
  matching the gate file's § Defer paragraph.
- **Suppress** — continue this session with no recital at session
  end.

No-drift (with or without the gate's normalization commit), Defer,
and Suppress all proceed to Step 1a without further user prompt.
Ordering: Step 1.5 runs after the `<dir-name>` resolver (so the
resolved name is available when the script's `--mode full` walk
resolves the plan dir from the active branch) and before Step 1b's
`mkdir` (so the script's internal Skip-#1 directory check reads the
pre-creation `_workflow/` state on fresh `/create-plan` invocations).
The skip check is now internal to the script, not an inline gate-bash
`[ -d … ]`.

**Interaction with Step 1a's handoff scan.** Step 1.5 fires before
Step 1a. On a `/create-plan` resume where `handoff-*.md` exists in
`docs/adr/<dir-name>/_workflow/`, the drift gate fires before the
handoff loader notices. No failure mode loses the handoff: on Migrate now the
handoff file persists on disk (it is already committed) and the next
`/create-plan` invocation's Step 1a picks it up after the drift gate
clears; on Defer or Suppress, Step 1a's handoff resume runs after
Step 1.5 in the same session. Per-session TaskCreate todos do not
survive `/clear`, so a paused Session A's Defer state is not carried
into Session B — Session B's Step 1.5 re-evaluates drift independently.

**Step 1a — Handoff check (mandatory, before any other on-disk work).**
Run:
```bash
ls -t docs/adr/<dir-name>/_workflow/handoff-*.md 2>/dev/null
```
If any files exist, load
mid-phase-handoff.md:planner:1
and follow its `§Resume protocol` BEFORE Step 1b. A previous
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
`.claude/workflow/conventions.md` `§1.2`).

**Step 2 — Ask the user for the aim.**

After you have finished reading the workflow documents, ask the user to describe the aim and goal for this session. Do NOT proceed until the user provides the aim. Wait for the user's response before starting any research or planning work.

The plan will be saved to:
`docs/adr/<dir-name>/_workflow/implementation-plan.md`
(the `_workflow/` subdir holds every ephemeral working file — plan,
design, track files, reviews — and is removed in the Phase 4
cleanup commit before merge; see `conventions.md` `§1.2` and
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
     signatures. See `conventions-execution.md` `§2.1` for the
     canonical section list and lifecycle.
   - Include a track-level Mermaid component diagram inside the
     track file's `## Context and Orientation` section when the
     track has 3+ internal components with non-trivial interactions.
     Track-level diagrams are **never rendered in the plan file**.
   - Track sizing rule: size each track by its in-scope file footprint, not
     its step count. *Maximize* — pack autonomous units in up to the soft
     footprint ceiling (related or not), opening a new track only when the
     next unit breaches the ceiling or breaks independent mergeability. A
     track ≤~12 in-scope files that folds into a neighbor is a merge candidate
     (flag-only); a track over ~20-25 in-scope files is a split candidate.
     Both bounds are soft: an out-of-bounds track passes when its track file
     carries a written justification. The full rule lives in `planning.md`
     §Track descriptions. The execution agent handles sequencing and episode
     propagation between dependent tracks.
5. For each track, include a **Scope indicator**:
   - Format: `> **Scope:** ~N files covering X, Y, Z`
   - Approximate file footprint + brief list of major work pieces. The
     footprint is a per-track soft heuristic, not the per-step `~12` split
     cap; the in-scope file set already lives in the track file's §Interfaces.
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

**Compute the workflow-SHA stamp once before writing the templates.**
Run the paired test-and-fallback idiom from
conventions.md:planner:1 `§1.6(b)` verbatim;
every artifact created in this `/create-plan` session reuses the
single `$WORKFLOW_SHA` value, so artifacts seeded together share a
stamp by construction:

```bash
WORKFLOW_SHA="$(git log -1 --format=%H HEAD -- .claude/workflow .claude/skills)"
[ -z "$WORKFLOW_SHA" ] && WORKFLOW_SHA="$(git rev-parse HEAD)"
```

Substitute the **resolved** value (not the literal `$WORKFLOW_SHA`
token) into the line-1 stamp comment of each of the three fenced
templates that follow. `Write` does not perform shell expansion. If
you emit `$WORKFLOW_SHA` verbatim, the artifact's stamp is malformed
and the drift check will route to migration on the next gate run.
The fallback to `git rev-parse HEAD` covers fresh repos and repos
where workflow paths have been moved; in every other case the
path-scoped log already returns a usable SHA.

The dual-seed `design-mechanics.md` case (when the planner seeds
both `design.md` and `design-mechanics.md` together) does NOT get a
fourth fenced template in this Step. The dual-seed write routes
through `edit-design phase1-creation` with `target=both`, which
carries an idempotency-guarded stamp directive that stamps the file
when it has not already been stamped. Keeping the dual-seed write on
the existing `edit-design` route avoids duplicating a near-identical
template here; the idempotency guard covers both the
`/create-plan`-driven dual seed and a direct `edit-design
phase1-creation` invocation outside `/create-plan`.

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
`.claude/workflow/conventions.md` `§1.2` for the directory layout
under `_workflow/` and `conventions-execution.md` `§2.1` for the
track-file shape and section lifecycle).

Before writing this template, substitute the resolved 40-character
SHA into the `$WORKFLOW_SHA` placeholder on line 1.

```
<!-- workflow-sha: $WORKFLOW_SHA -->
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
  > **Scope:** ~N files covering X, Y, Z

- [ ] Track 2: <title>
  > <intro paragraph — high-level context; detailed description in plan/track-2.md>
  > **Scope:** ~N files covering A, B
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
`conventions-execution.md` `§2.1`; the verbatim ready-to-paste
template body is reproduced below so this SKILL stays
self-sufficient (the lifecycle source is durable; the design-doc
copy is ephemeral and removed in the Phase 4 cleanup commit, so it
cannot be a durable pointer target).

Before writing this template, substitute the resolved 40-character
SHA into the `$WORKFLOW_SHA` placeholder on line 1.

````markdown
<!-- workflow-sha: $WORKFLOW_SHA -->
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
roster here: one entry per step with description, `risk:` tag, an
optional `size:` clause, and a `[ ]` status checkbox. The `size:`
clause (`— size: ~N files; <reason>`) appears only on an under-filled
`low`/`medium` step (rule in `track-review.md` §Step Decomposition).
Per-step episodes do NOT live here; they live in `## Episodes` below.
The roster is immutable after Phase A except for the status checkbox
flip and the optional `commit:` annotation Phase B appends. -->

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
section above is tabulated in `conventions-execution.md` `§2.1`.

Write the design document to
`docs/adr/<dir-name>/_workflow/design.md` using this structure.
Before writing this template, substitute the resolved 40-character
SHA into the `$WORKFLOW_SHA` placeholder on line 1.

```
<!-- workflow-sha: $WORKFLOW_SHA -->
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
3. **Deferred-drift recital (silent no-op when nothing was deferred).**
   If Step 1.5's Defer resolution created the TaskCreate todo titled
   `Deferred workflow drift: <count> commits since <short-stamp-base-SHA>`
   (or the unstamped variant `Deferred workflow drift: unstamped
   artifacts in active plan, see /migrate-workflow`) earlier in this
   session, read the todo title and recite it verbatim, followed by
   an instruction to run `/migrate-workflow` from this worktree to
   pick up the deferred work. Scan session TaskCreate todos for any
   title matching the prefix `Deferred workflow drift:` — there is at
   most one per session because Step 1.5 fires at most once. If
   TaskCreate was unavailable at Step 1.5 and the two fields are held
   in in-context memory instead, recite the same line shape from
   memory. If no TaskCreate todo can be located and no
   in-context-memory fallback was recorded at Step 1.5 (i.e., no
   Defer resolution fired this session), skip this sub-step silently
   rather than fabricate a recital. The recital fires before the
   draft PR is opened so the user sees the residue in the same
   session; it mirrors the recital `workflow.md § What to do before ending a session` runs for `/execute-tracks`.
4. Ask the user **once**, before opening the PR:
   *"Provide an issue prefix for the PR title (e.g. `YTDB-123`)?
   Leave blank to skip."*
   Branch names in this project often do not encode the issue
   prefix; the user tracks it in the PR title instead.
5. Compose the PR title:
   - With a prefix `<P>`: `[<P>] <feature title>` — e.g.
     `[YTDB-123] Index histogram for selective range scans`
   - Without a prefix: `<feature title>`
6. Compose the PR body from the plan: `## Motivation` (the plan's
   Goals + Constraints, distilled into prose — apply the Ephemeral
   identifier rule from `conventions-execution.md` `§2.3` to the body
   since PR titles and descriptions are durable), `## Plan` (one
   line per track from the checklist, no internal IDs), and a
   `## Status` line stating *"Draft — workflow scaffolding under
   `docs/adr/<dir-name>/_workflow/` will be removed in the Phase 4
   cleanup commit before merge."*
7. Open the PR in **draft** mode using `gh`:
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
