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
| **Backlog** | `implementation-backlog.md` — the companion file to `implementation-plan.md` that holds the detailed `**What/How/Constraints/Interactions**` subsections and any track-level Mermaid diagrams for pending tracks. Written during Phase 1 alongside the plan (and extended by inline replanning); shrinks monotonically as tracks enter Phase A or are skipped; never committed to git. Its presence on disk is the signal that tells consumers the plan is in the new split-file format (see §1.2). |

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
                                     monotonically; presence on disk signals
                                     new-format plan (see subsection below)
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
Mermaid diagrams for **pending** tracks — the content that used to live
inside each checklist entry in the plan file. Splitting this detail out of
the plan keeps `implementation-plan.md` thin so `/execute-tracks` sessions
read only strategic context at startup; the backlog is read only in Phase A
of one track per session.

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

…
````

**File shape requirements:**
- `# <Feature Name> — Track Details` — required canonical header for
  newly-created backlogs. The D4 detection rule (below) checks only file
  existence, not header content, but Phase 1 must still write the header
  for human readers and for structural consistency across plans.
- The `<!-- DO NOT DELETE … -->` HTML comment immediately after the header
  is **required** in newly-created backlogs as the self-documenting marker
  of the load-bearing-file rule below. It is informational for human
  readers and agents; detection does not parse it.
- One `## Track N: <title>` section per pending track, with bold-label
  blockquote subsections and an optional fenced `mermaid` block for any
  track-level diagram.

**Lifecycle (overview):** Sections are removed from the backlog as their
tracks enter Phase A or are skipped, so the backlog shrinks monotonically
during normal execution. Entries are added only during Phase 1 or inline
replanning. Full per-phase detail (writers, readers, authoritative
location) lives in the description-lifecycle table in
`conventions-execution.md` §2.1.

**Load-bearing-file rule:** The backlog file's **presence on disk** — not
its content — is the signal that tells consumers the plan is in the new
split-file format. The file must remain on disk throughout execution even
after the last track section is removed (an empty header-only file still
signals new-format). The `<!-- DO NOT DELETE -->` HTML comment is the
self-documenting marker of this rule; natural cleanup happens when the
branch is deleted after PR merge, like every other working file.

**D4 — Legacy-compat detection rule:** If the file
`docs/adr/<dir-name>/implementation-backlog.md` exists, the plan is
new-format and consumers read pending-track detail from the backlog;
otherwise the plan is legacy and consumers fall back to reading
`**What/How/Constraints/Interactions**` from the plan file's checklist
entries as before. Use "file exists" wording in derived documents and
prompts — the canonical rule is language-neutral; individual orchestrators
choose whatever tool is convenient (Bash `test`, Glob, Read).

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
