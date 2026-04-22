# Conventions — Execution (Phase 3)

Execution-specific formats and rules. Loaded only during Phase 3
(`/execute-tracks`). For core conventions shared across all phases, see
[`conventions.md`](conventions.md).

---

## 2.1 Plan File — Execution Additions

These subsections extend the plan file structure defined in
`conventions.md` §1.2.

### After track completion (user-approved)

The track episode is written to the plan file **only after the user
approves** the track results, and at the same time the description is
**collapsed** to remove implementation detail that is now superseded by
the committed code and step episodes (see workflow.md §Track Completion
Protocol).

**Always keep** (regardless of plan shape): the **intro paragraph** (the
first paragraph of the original description, before any `**What**:` /
`**How**:` / `**Constraints**:` / `**Interactions**:` subsection), the
`**Track episode:**` block (written at collapse time), the `**Step
file:**` pointer, and the `**Strategy refresh:**` line if present — the
next session's strategy refresh appends it per §"After strategy refresh"
below.

**Always drop** (regardless of plan shape): the `**Scope:**` line and
the `**Depends on:**` line.

**Conditional drop** — applied according to the plan shape:

1. **New-format plan** (`implementation-backlog.md` exists on disk):
   pending-track entries in the plan were already written in the thin
   form during Phase 1, so there are no `**What**:` / `**How**:` /
   `**Constraints**:` / `**Interactions**:` subsections present to drop.
   The collapse reduces to the "Always drop" rule above. The detailed
   description was removed from the backlog at Phase A start and already
   lives in the step file's `## Description` section; Phase C does not
   touch the backlog.
2. **Legacy plan** (no `implementation-backlog.md` on disk): pending-
   track entries still carry `**What/How/Constraints/Interactions**`
   subsections — drop those in addition to `**Scope:**` and
   `**Depends on:**` (today's behavior).
3. **Mid-migration plan** (`implementation-backlog.md` exists on disk,
   but this particular entry still carries legacy
   `**What/How/Constraints/Interactions**` subsections — e.g., a
   partial hand-migration): drop those subsections as in the legacy
   case, in addition to `**Scope:**` and `**Depends on:**`. Whether the
   backlog is present does not affect the per-entry decision; the
   presence of the keyword subsections themselves is what triggers the
   drop. This case arises only from manual hand-migration of a legacy
   plan; normal workflow operations never leave a new-format plan
   entry with residual keyword subsections.

Quick reference: always drop `**Scope:**` and `**Depends on:**`; drop
the four keyword subsections only if present.

```markdown
- [x] Track 2: <title>
  > <intro paragraph — first paragraph of the original description>
  >
  > **Track episode:**
  > <strategic summary — length proportional to cross-track impact>
  >
  > **Step file:** `tracks/track-2.md` (4 steps, 0 failed)
```

**Track episode fields:**
- Strategic summary covering: what was built, key discoveries, plan deviations
  with cross-track impact. Length is proportional to cross-track impact — a
  routine track may need only a couple of sentences, while a track with
  architectural surprises should include enough detail for the next session's
  strategy refresh to assess downstream impact without reading the step file.
- Reference to the step file with step count and failure count
- This is what future track sessions read from the plan file — the step
  file is available for deeper investigation if needed

**Why collapse:** Completed tracks accumulate in the plan file and are
re-sent to every sub-agent as strategic context. Keeping the full
implementation detail for completed tracks inflates every code-review
sub-agent prompt by tens of thousands of tokens. The intro paragraph plus
track episode is sufficient strategic context for reviewers of later
tracks. For how sub-agents render the plan (including the same shape
applied in-memory for legacy un-collapsed entries), see
[`plan-slim-rendering.md`](plan-slim-rendering.md).

### After strategy refresh

The strategy refresh result is appended to the same track's block in the
plan file, after the step file reference:

```markdown
- [x] Track 2: <title>
  > <intro paragraph>
  >
  > **Track episode:**
  > <strategic summary>
  >
  > **Step file:** `tracks/track-2.md` (4 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact detected.
```

For ADJUST, include a brief summary of what was adjusted:

```markdown
  > **Strategy refresh:** ADJUST — Track 4 description updated to account
  > for the new `IndexStatistics` API shape discovered during this track.
```

For skipped tracks (`[~]`), the strategy refresh line follows the skip
record. The on-disk form keeps whatever description was in the plan
entry: in new-format plans that is the intro paragraph only (the
`**What/How/Constraints/Interactions**` detail lived in
`implementation-backlog.md` and track-skip removes that section at the
same time it marks `[~]` — see `track-skip.md`); in legacy plans that
is the full inline description. Skipped tracks never go through the
Phase C collapse, so nothing further trims the plan entry:

```markdown
- [~] Track 3: <title>
  > <description>
  >
  > **Skipped:** <reason>
  >
  > **Strategy refresh:** CONTINUE — no downstream impact from skipping.
```

For how sub-agents see this — `plan-slim-rendering.md` strips the
implementation-detail subsections at prompt-assembly time so reviewers
get a compact view without changing what's on disk.

ESCALATE triggers inline replanning (see workflow.md), which restructures
the plan file directly — no strategy refresh line is written.

### Session state detection

Moved to `workflow.md` §Startup Protocol — the only place where state
detection is used.

### Step file content (`tracks/track-N.md`)

````markdown
# Track N: <title>

## Description
<assembled at Phase A start — intro paragraph from the plan entry plus
`**What/How/Constraints/Interactions**` + optional diagram from the
backlog. See the "Description lifecycle" subsection at the end of §2.1
for authoritative-location rules and the legacy-plan fallback.>

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/5 complete)
- [ ] Track-level code review

## Base commit
`abc1234`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps
- [x] Step: <description>
  - [x] Context: safe
  > **What was done:** ...
  > **What was discovered:** ...
  > **Key files:** ...

- [x] Step: <description>
  - [x] Context: info
  > **What was done:** ...
  > **Key files:** ...

- [ ] Step: <description>
- [ ] Step: <description>
- [ ] Step: <description>
````

The **Description** section carries the track's full description —
intro paragraph (sourced from the plan-file entry), `**What**:` /
`**How**:` / `**Constraints**:` / `**Interactions**:` subsections, and
any track-level Mermaid diagram (both sourced from
`implementation-backlog.md`). Phase A orchestration writes the section
once at the start of the track, concatenating the plan-entry intro
with the backlog-section body; it is re-written only if
[`inline-replanning.md`](inline-replanning.md) updates a mid-execution
track's description. Phase B and Phase C sub-agents that already read
the step file see the description here automatically — see the
Description lifecycle subsection below for the authoritative-location
rules across phases. For legacy plans (no `implementation-backlog.md`
on disk), the section is still written at Phase A start but the whole
description is sourced from the plan-file entry's checklist block —
legacy plans keep intro + `**What/How/Constraints/Interactions**` all
inline there (legacy fallback added in Track 2).

The **Progress** section tracks which phase the track is in. The execution
agent updates it at each phase transition:
- Mark `Review + decomposition` as `[x]` when the step file is first written
- Update `Step implementation` count as each step completes (e.g.,
  `(3/5 complete)`); mark `[x]` when all steps are done
- Update `Track-level code review` iteration count as each review
  iteration completes (e.g., `(1/3 iterations)`); mark `[x]` when the
  review passes or max iterations are reached

The **Context check** sub-item under each step records the context window
level measured after step completion (sub-step 7 in step-implementation.md).
The agent marks it `[x]` with the measured level (`safe`, `info`, `warning`,
or `critical`). If measurement failed (file missing or command error), record
`- [x] Context: unavailable`. This sub-item is written as part of the
episode commit. It must be marked before the step is considered complete —
an unmarked context check means the agent skipped the check.

The **Base commit** section records the git SHA of `HEAD` at the start of
Phase B, before the first implementation commit. Phase B writes this once
at session start. Phase C reads it to compute `git diff {base_commit}..HEAD`
for the track-level code review.

The **Reviews completed** section records which pre-execution reviews
finished. If a session is interrupted during Phase A, the next session
can skip completed reviews and only re-run missing ones.

Step files are created during Phase A (review + decomposition) when steps
are decomposed. They do not exist during Phase 1 (planning) or Phase 2
(structural review) — only scope indicators in the plan file exist at
that point.

**The plan is a strategic guide, not a rigid task graph.** Track
descriptions, architecture notes, and inter-track dependencies are the
load-bearing parts. Step-level detail is tactical and should emerge
just-in-time during execution when the agent has maximum codebase
context. Phase A always has freedom to adapt step-level decomposition
without formal replanning — only track-level or decision-level changes
require escalation.

### Description lifecycle

A track's detailed description (the `**What/How/Constraints/Interactions**`
subsections plus any track-level Mermaid diagram) travels between files
as the track moves through the workflow. The table below is the
canonical reference for where that content lives at each phase for
pending, active, and completed tracks. Phase A resume logic (see
[`track-review.md`](track-review.md)) and inline replanning (see
[`inline-replanning.md`](inline-replanning.md)) both read the same
rules from here. Skipped tracks follow a separate retention rule —
see §"After strategy refresh" above for the authoritative statement
(in new-format plans the plan entry keeps only the intro paragraph;
in legacy plans it keeps the full inline description; either way, the
`[~]` entry is never collapsed).

| Phase | Authoritative location | Writer | Reader(s) |
|---|---|---|---|
| Pre-Phase-1 | (none) | — | — |
| Phase 1 write | `implementation-backlog.md` | Phase 1 agent (via `create-plan/SKILL.md`) | Phase 2 consistency + structural reviews |
| Phase A start — before step-file write | `implementation-backlog.md` | (inherited from Phase 1) | Phase A orchestration (reads the track's backlog section) |
| Phase A mid — step file has `## Description`, backlog entry still present | **step file** (Phase A writes the step file first, then removes the backlog entry — the step file takes authority the moment it is written, so an interrupted session always resumes against a single source) | Phase A orchestration (wrote the step file) | Phase A resume logic (idempotent re-entry); Phase A review sub-agents |
| Phase A end | step file | Phase A orchestration (backlog section already removed) | Phase A review sub-agents (Track 3 prompts) |
| Phase B / Phase C | step file | (stable — only inline replan rewrites) | Phase B/C code-review sub-agents |
| Phase C after collapse | step file + plan intro paragraph | Phase C collapse (writes intro + episode) | future-track sessions (as strategic context) |
| Skipped at or before Phase A | plan file entry (retained under `[~]`) + (backlog entry removed; step file never created) | `track-skip` | strategy refresh / future sessions |
| Skipped after Phase A (rare) | plan file entry (retained under `[~]`) + step file (retained so the skip is traceable) | `track-skip` | strategy refresh / future sessions |
| Inline replan | per authoritative-location rule in [`inline-replanning.md`](inline-replanning.md) | inline-replanning orchestration | — |

Track-level Mermaid diagrams follow the same trajectory as the
description (backlog → step file at Phase A; never rendered in the
plan file). The writer and readers at each phase are the same as the
corresponding description row above.

**Legacy plans** (no `implementation-backlog.md` on disk, per D4): the
description remains in the plan-file entry until Phase C collapse drops
the keyword subsections. The step file still gets a `## Description`
section at Phase A start, sourced from the plan-file entry rather than
the backlog (legacy fallback added in Track 2 — `track-review.md`
Phase A).

**Monotonic shrinkage:** The backlog grows only during Phase 1 or inline
replanning, and shrinks as tracks enter Phase A or are skipped. Normal
Phase A / B / C execution never adds entries. The file itself is never
committed to git — it is a working file that persists on disk between
sessions and is cleaned up when the branch is deleted after PR merge
(see `conventions.md` §1.2: "Working files persist on disk between
sessions and are never committed"). The load-bearing-file rule in the
same section requires the file to remain on disk for the lifetime of
the plan, even after the last track section is removed (an empty
header-only file still signals the new-format plan).

### Backlog section body extraction rule

Track N's section body in `implementation-backlog.md` is everything
between the `## Track N: <title>` header line and the next
`## Track M: <title>` header line (or EOF, if Track N is the last
section), excluding the `## Track N:` header line itself. Optional
trailing blank lines between Track N's content and the next
`## Track M:` header are stripped from the extracted body so repeated
extract-then-insert cycles do not accumulate whitespace.

Do **NOT** use line-count deletion to implement the removal — that
approach breaks when track-level `mermaid` diagrams or
multi-paragraph blockquotes change a section's line count between
when the agent originally read it and when the removal runs. Always
search for the header boundary at removal time.

This is the single authoritative definition used wherever the workflow
reads or removes a track's backlog section: Phase A description-move
(see [`track-review.md`](track-review.md) §What You Do sub-step 2e),
`track-skip` backlog cleanup (see [`track-skip.md`](track-skip.md)),
and inline-replanning updates (see
[`inline-replanning.md`](inline-replanning.md)). All three entry points
apply the rule verbatim — keeping the extraction logic identical
across them avoids accidental divergence when section bodies contain
track-level `mermaid` diagrams or multi-paragraph blockquotes.

---

## 2.2 Episode Formats

Three episode types: **step completion** (`[x]`), **step failed** (`[!]`),
and **track episode** (in plan file after user approval).

Step completion fields: **What was done** (always), **What was discovered**
(when applicable — fill whenever anything unexpected is found),
**What changed from the plan** (when applicable — name affected future
steps), **Key files** (always), **Critical context** (rare).

Step failed fields: **What was attempted**, **Why it failed**, **Impact on
remaining steps**, **Key files**.

Episodes are immutable once written. Code is committed first, then the
episode is written to the step file on disk. Episode length is proportional
to cross-track impact.

**Full format, rules, and examples:**
[`episode-format-reference.md`](episode-format-reference.md)

---

## 2.3 Commit messages, code review, complexity, decomposition

These rules are needed only when their specific phase or action runs — not
at session startup. Load on demand:

- **Commit message format** — follow the project's `CLAUDE.md` commit
  conventions. Only code changes are committed; workflow files are never
  committed (see §1.2 in `conventions.md`). For the execution-specific
  prefixes (`Review fix:`) used during session resume, see
  [`commit-conventions.md`](commit-conventions.md).
- **Two-tier dimensional code review** (step-level and track-level
  sub-agent reviews, 4 baseline + up to 6 conditional, max 3 iterations):
  [`code-review-protocol.md`](code-review-protocol.md).
- **Complexity tiers** (which pre-execution reviews to run for Simple /
  Moderate / Complex tracks): covered in
  [`track-review.md`](track-review.md) §Complexity Assessment.
- **Checklist decomposition rules** (step sizing, cross-cutting concerns,
  parallel step annotation): covered in
  [`track-review.md`](track-review.md) §Step Decomposition.
