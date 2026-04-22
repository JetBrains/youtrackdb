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
| **Research** | Phase 0 — interactive exploration before planning. The agent answers questions, explores code, and does internet research. Completes only when the user explicitly asks to create the plan. Same session as Phase 1. |
| **Session** | One invocation of `/execute-tracks`. Handles one sub-phase (A, B, or C) of one track. Sessions are separated by context clearing. Episodes bridge context across sessions. The only exception: strategy refresh + Phase A share a single session. |
| **Sub-agent** | A spawned agent for self-contained review tasks (technical/risk/adversarial reviews, dimensional code review agents, test quality review). Sub-agents provide fresh perspective; the main agent retains full context. |

---

## 1.2 Plan File Structure

All workflow phases reference this structure.
`<dir-name>` is the plan directory name — provided explicitly by the user, or
defaulting to the current git branch name.

```
docs/adr/<dir-name>/
  ## Working files (untracked — on disk only, deleted with the branch)
  implementation-plan.md          <- strategic: goals, architecture, tracks,
                                     track-level episodic summaries
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
  > <description: what/how/constraints/interactions>
  > **Scope:** ~N steps covering X, Y, Z
- [ ] Track 2: <title>
  > <description>
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
