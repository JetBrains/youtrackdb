# Conventions

Shared formats, rules, and glossary used by all phases of the workflow.

For execution-specific conventions (episodes, commit format, code review,
complexity tiers, decomposition rules), see
[`conventions-execution.md`](conventions-execution.md) — loaded only
during Phase 3 execution.

---

## 1.1 Glossary

| Term | Definition |
|---|---|
| **Track** | A coherent stream of related work within a plan. Contains steps. Max ~5-7 steps. If larger, split into dependent tracks during planning. |
| **Step** | A single atomic change = one commit. Fully tested. |
| **Episode** | Structured record of what happened during a step or track. |
| **Scope indicator** | Rough sketch of expected work in a track. |
| **Risk tag** | Per-step `low` / `medium` / `high` label assigned by the Phase A decomposer. Gates whether Phase B runs step-level dimensional review (`high` only) and signals focal points to Phase C track-level review (`medium` and `high`). Locked once the step is implemented. Criteria, override rules, and lifecycle live in `risk-tagging.md`; sub-step gating reads only the tag value, not the criteria. |
| **Research** | Phase 0 — interactive exploration before planning. The agent answers questions, explores code, and does internet research. Completes only when the user explicitly asks to create the plan. Same session as Phase 1. |
| **Session** | One invocation of `/execute-tracks`. Handles one sub-phase (A, B, or C) of one track. Sessions are separated by context clearing. Episodes bridge context across sessions. The only exception: strategy refresh + Phase A share a single session. |
| **Sub-agent** | A spawned agent for self-contained review tasks (technical/risk/adversarial reviews, dimensional code review agents, test quality review). Sub-agents provide fresh perspective; the main agent retains full context. |
| **Backlog** | `implementation-backlog.md` — the companion file to `implementation-plan.md` that holds the detailed `**What/How/Constraints/Interactions**` subsections and any track-level Mermaid diagrams for pending tracks. Written during Phase 1 alongside the plan (and extended by inline replanning); shrinks monotonically as tracks enter Phase A or are skipped; never committed to git. |

---

## 1.2 Plan File Structure

All workflow phases reference this structure.
`<dir-name>` is the plan directory name — provided explicitly by the user, or
defaulting to the current git branch name.

```
docs/adr/<dir-name>/
  ## Working files (untracked — on disk only, deleted with the branch)
  implementation-plan.md          <- strategic: goals, architecture, tracks,
                                     track-level episodic summaries (thin
                                     checklist — description detail lives in
                                     implementation-backlog.md)
  implementation-backlog.md       <- pending-track details (what/how/
                                     constraints/interactions + any
                                     track-level diagrams); shrinks
                                     monotonically
  design.md                       <- design-level: class diagrams, workflow
                                     diagrams, complex/opaque part explanations
                                     (created in Phase 1, never modified after)
  tracks/
    track-1.md                    <- tactical: decomposed steps, step episodes
    track-2.md
    ...
  reviews/
    structural.md
    track-1-technical.md
    ...

  ## Final artifacts (committed in Phase 4 — the only tracked files)
  design-final.md                 <- post-implementation design reflecting
                                     what was actually built
  adr.md                          <- architecture decision record with actual
                                     outcomes, aggregated from all episodes
```

Working files persist on disk between sessions and are never committed.
The user deletes them alongside the branch after the PR is merged.
Only the two Phase 4 artifacts are committed to git.

### Plan file content (`implementation-plan.md`)

```markdown
# <Feature Name>

## Design Document
[design.md](design.md)

## High-level plan

### Goals
<what this feature achieves and why>

### Constraints
<technical, performance, compatibility, or process constraints>

### Architecture Notes
<follow the Architecture Notes rules — see planning.md>

## Checklist
- [ ] Track 1: <title>
  > <intro paragraph — high-level context; detailed description in implementation-backlog.md>
  > **Scope:** ~N steps covering X, Y, Z
- [ ] Track 2: <title>
  > <intro paragraph — high-level context; detailed description in implementation-backlog.md>
  > **Scope:** ~N steps covering X, Y, Z
  > **Depends on:** Track 1 (when applicable)

## Final Artifacts
- [ ] Phase 4: Final artifacts (`design-final.md`, `adr.md`)
```

**Planning rule:** If a track would need more than ~5-7 steps or internal
phasing, split it into separate dependent tracks. Track sequencing and
episode propagation between dependent tracks is handled by the session
workflow — this gives the same "informed decomposition" benefit without
extra complexity.

### Section budgets

`implementation-plan.md` is loaded at every `/execute-tracks` startup,
so each section of the plan file obeys a length budget. Targets:
plan-file total ~1,500 lines / ~30K tokens; DR ≤ ~30 lines; invariant
≤ ~5; integration-point bullet ≤ ~3; component intent bullet ≤ ~5.
See [`planning.md`](planning.md) § Architecture Notes format for the
per-section budgets and rationale, and
[`structural-review.md`](structural-review.md) § Bloat checks for how
the structural review enforces them.

### Backlog file content (`implementation-backlog.md`)

Companion file to `implementation-plan.md`. It holds the detailed
`**What/How/Constraints/Interactions**` subsections and any track-level
Mermaid diagrams for **pending** tracks. Keeping this detail out of the
plan keeps `implementation-plan.md` thin so `/execute-tracks` sessions
read only strategic context at startup; the backlog is read only in
Phase A of one track per session.

````markdown
# <Feature Name> — Track Details

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

…
````

**File shape requirements:**
- `# <Feature Name> — Track Details` — required canonical header.
- One `## Track N: <title>` section per pending track, with bold-label
  blockquote subsections and an optional fenced `mermaid` block for any
  track-level diagram.

**Lifecycle (overview):** Sections are removed from the backlog as their
tracks enter Phase A or are skipped, so the backlog shrinks monotonically
during normal execution. Entries are added only during Phase 1 or inline
replanning. Full per-phase detail (writers, readers, authoritative
location) lives in the description-lifecycle table in
`conventions-execution.md` §2.1.

### Status markers

| Marker | Meaning | Used in |
|---|---|---|
| `[ ]` | Not started | Tracks, Phase 4 |
| `[>]` | In progress | Phase 4 |
| `[x]` | Completed | Tracks, Phase 4 |
| `[~]` | Skipped | Tracks only (recommended by track review or execution agent) |

### Scope indicators (required)

Every track must include a **Scope** line in its description block: a rough
sketch of the expected work — approximate step count and a brief list of
what they'd cover. Scope indicators are strategic signals, not tactical
commitments. Phase A always does full step decomposition at execution
time regardless.

Format: `> **Scope:** ~N steps covering X, Y, Z`

Scope indicators serve three purposes:
1. **Structural review** can catch sizing issues (a track claiming ~2 steps
   but describing 8 distinct changes) and ordering problems (scope of
   Track B implies a dependency on Track A's output).
2. **Human reviewers** can quickly gauge relative effort across tracks.
3. **Execution planning** — Phase A uses scope indicators as a starting
   point for just-in-time step decomposition, not as a binding contract.

**Rules:**
- The planner should focus energy on track descriptions, architecture notes,
  and inter-track dependencies — not premature step decomposition.
- Scope indicators are estimates. "~3-5 steps" is fine; exact counts are
  not required.
- The brief list (covering X, Y, Z) names the major pieces of work, not
  individual commits. Think "what" not "how."
- Do NOT include full step descriptions, `- [ ] Step:` items, or
  *(provisional)* markers. Steps are decomposed during Phase 3 execution.

---

## 1.3 Review Iteration Protocol

Shared by structural review, track pre-execution reviews, and track-level
code review. Severity levels: **blocker** / **should-fix** / **suggestion**
/ **skip** (track reviews only — recommends skipping the entire track).

**Full protocol** (iteration limits, finding ID prefixes, finding format,
gate verification output): [`review-iteration.md`](review-iteration.md) —
load when running a review loop.

---

## 1.4 Tooling discipline — prefer mcp-steroid PSI for Java symbol audits

The user's global rules in `~/.claude/CLAUDE.md` (sections "MCP Steroid"
and "Grep vs PSI — when to switch") are the single authoritative source
for when to route through the IntelliJ IDE via mcp-steroid versus when
to fall back to `grep`/`rg`/Bash. This subsection is a project-level
reminder that those rules apply to **every phase of the workflow** —
research, planning, Phase A review, Phase B implementation, Phase C
code review, Phase 4 final artifacts — and to **every sub-agent** the
workflow spawns.

### Session-start preflight

The SessionStart hook prints an `mcp-steroid: …` status line. Trust it
as the canonical IDE state for the session, do not re-probe, and act on
its three outcomes:

- **`mcp-steroid: reachable`** — call `steroid_list_projects` once
  before the first symbol-related action to confirm the open project
  matches the working tree, then route every reference-accuracy
  question about Java symbols through PSI for the rest of the session.
- **`mcp-steroid: NOT reachable`** — IDE control is unavailable. Symbol
  audits must use `grep`/`rg` and every audit conclusion that depends
  on a symbol search must explicitly note the reference-accuracy caveat
  ("grep-based; may miss polymorphic call sites / Javadoc / generics").
- **cwd mismatch** (`steroid_list_projects` reports a different open
  project than the working tree) — pause and ask the user to switch
  the open project before running any load-bearing symbol audit. Do
  not silently fall back to grep.

### When PSI is required (not optional)

When mcp-steroid is reachable, **load-bearing audits MUST use PSI** —
grep is not acceptable for the search that drives the action. A search
is load-bearing if a missed or spurious reference would corrupt the
result: deletions, renames, signature changes, "no production callers"
claims, "field is referenced only inside its declaring class" claims,
"this slot has no consumer" claims, etc. The cost of a missed
polymorphic call site is "tests pass at the deletion commit but
production breaks at runtime" — exactly the failure mode PSI exists to
prevent.

The bullet list above is **illustrative, not exhaustive**. The
operative test is the criterion ("would a missed or spurious reference
corrupt the result?"), not the example set. When a case isn't listed,
apply the criterion; when in doubt, treat the audit as load-bearing
and route through PSI. `~/.claude/CLAUDE.md` (sections "MCP Steroid"
and "Grep vs PSI — when to switch") is the last authoritative source
for edge cases.

This rule applies to design and research sessions too. Design
conclusions often hinge on reference-accuracy facts that grep can
silently miss — so research, Phase 1 planning, and Phase A track
reviews are not exempt.

### Sub-agent delegation

Delegating a symbol-usage question to a sub-agent (Explore, Phase A
review prompts, code review prompts, Phase 4 final-artifact prompts)
**does not bypass the PSI requirement.** Sub-agents default to
Bash/grep, so an unannotated delegation routes through grep regardless
of the question's shape. When passing a reference-accuracy question
to any sub-agent, the prompt MUST explicitly say *"use mcp-steroid PSI
find-usages, not grep, for these reference-accuracy questions"*. The
canonical review prompts under `prompts/` already embed this
instruction; custom delegations and on-the-fly prompts must do the
same.

### Other mcp-steroid routes (Maven, refactoring)

The user-global rules also cover when to route Maven runs and Java
refactors through the IDE. Two project-relevant defaults:

- **Single-test reruns** during step implementation (e.g. `-Dtest=Foo#bar`
  after a focused fix) and **compile-fix loops** benefit from
  `steroid_execute_code` — the IDE returns parsed test results and
  filtered compiler output. Full-suite runs, coverage profiles, JMH
  benchmarks, and integration-test suites stay on Bash `./mvnw` per the
  Maven-routing rule in `~/.claude/CLAUDE.md`.
- **Renames, moves, signature changes, extract-method, pull-up/push-down,
  and any refactor that touches more than one reference site** route
  through the IDE refactoring engine via mcp-steroid, not raw `Edit`.
  Pure single-file edits and changes that don't move references stay on
  `Edit`. After an IDE refactor, run Spotless on the affected modules
  and re-run the relevant tests — the engine doesn't enforce project
  formatting.

The two examples above are **illustrative, not exhaustive** — they
name the project-relevant defaults, not the full set of cases where
mcp-steroid is the right route. The full Maven and refactoring
routing tables live in `~/.claude/CLAUDE.md` (sections "Maven — when
to route through mcp-steroid" and "Refactoring — IDE refactor vs raw
Edit"); when a situation isn't covered here, that file is the last
authoritative source.

Both routes require the same `steroid_list_projects` preflight as PSI
audits.
