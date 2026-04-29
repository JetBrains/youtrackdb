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
  > <intro paragraph>
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
  > **Risk:** high — concurrency (introduces lock acquisition)
  >
  > **What was done:** ...
  > **What was discovered:** ...
  > **Key files:** ...

- [x] Step: <description>
  - [x] Context: info
  > **Risk:** low — default (pure refactoring)
  >
  > **What was done:** ...
  > **Key files:** ...

- [ ] Step: <description>
  > **Risk:** medium — multi-file logic in core (no HIGH triggers)
- [ ] Step: <description>
  > **Risk:** low — default (extract helper)
- [ ] Step: <description>
  > **Risk:** high — public API change
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
inline there (legacy fallback — see `track-review.md` §Phase A).

The **Progress** section tracks which phase the track is in. The execution
agent updates it at each phase transition:
- Mark `Review + decomposition` as `[x]` when the step file is first written
- Update `Step implementation` count as each step completes (e.g.,
  `(3/5 complete)`); mark `[x]` when all steps are done
- Update `Track-level code review` iteration count as each review
  iteration completes (e.g., `(1/3 iterations)`); mark `[x]` when the
  review passes or max iterations are reached

The **Context check** sub-item under each step records the context window
level measured at sub-step 6 of step-implementation.md and recorded when
the episode is written in sub-step 7. The agent marks it `[x]` with the
measured level (`safe`, `info`, `warning`, or `critical`). If measurement
failed (file missing or command error), record `- [x] Context: unavailable`.
This sub-item is written to the step file on disk alongside the episode;
like episodes, it is not committed to git. It must be marked before the
step is considered complete — an unmarked context check means the agent
skipped the check.

The **Risk:** line in each step's description blockquote names the step's
risk level (`low`, `medium`, or `high`) and the triggering category (or
`default` / `override: <reason>`). It is written by the Phase A decomposer
and reviewed by the user before the step file is approved. Phase B reads
it to gate sub-step 4 — the dimensional review loop runs only when the
tag is `high`; for `medium` and `low` steps Phase B skips directly to
sub-step 5. If implementation reveals that a step is more invasive than
tagged, Phase B may upgrade the tag in place (recording the upgrade in
the risk note) but never downgrade. After the step is committed and the
episode written, the risk tag is locked. Phase C reads the locked tags
from the step file and treats `medium` and `high` step ranges as focal
points when reviewing the cumulative track diff. Full criteria, override
rules, and lifecycle live in [`risk-tagging.md`](risk-tagging.md), which
is loaded only by Phase A (and rarely by Phase B on the upgrade path);
sub-step 4's gating decision in normal Phase B execution reads the tag
value from the step file alone.

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
the backlog — see the legacy branch of `track-review.md` §Phase A.

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
(see [`track-review.md`](track-review.md) §What You Do item 2,
sub-step (e)), `track-skip` backlog cleanup (see
[`track-skip.md`](track-skip.md)), and inline-replanning updates (see
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

## 2.3 Ephemeral identifier rule — don't leak workflow IDs into committed content

**Authoritative statement.** This is the single source of truth for the
rule; every phase-specific prompt that touches committed content points
here.

`implementation-plan.md`, `implementation-backlog.md`,
`tracks/track-N.md`, and `reviews/**` are working files — untracked, and
deleted together with the branch after the PR is merged. Any identifier
that lives only in those files becomes a dangling reference the moment
the branch is gone. The same applies to review-loop counters and named
invariants that live only in the plan. Therefore, **anything that goes
into git must not cite those identifiers**.

### Where the rule applies

- **Source code comments and Javadoc** — the most common leak. Comments
  like `// added per Track 2 Step 1`, `// fixes CQ33`, or `// see
  Single-authority invariant` tie the code to files that will not exist
  on `main`.
- **Commit messages** — even though per-commit messages are squashed on
  merge (see CLAUDE.md §Git Conventions), the squashed commit message
  is assembled from the PR title/description and inherits this content.
  Individual commit messages are also visible in code review and PR
  history before squashing.
- **PR titles and descriptions** — stored in GitHub, keyed off by the
  squashed commit message.
- **`design-final.md`** and **`adr.md`** — the only workflow files
  committed to git; see
  [`prompts/create-final-design.md`](prompts/create-final-design.md)
  for Phase-4-specific examples.
- **Tests, test names, and test descriptions** — committed alongside
  the code.

It does **not** apply to the working files themselves: step files
(`tracks/track-N.md`), review files, the plan, and the backlog all
cite tracks, steps, findings, and iterations freely — that's exactly
what those identifiers are for inside the workflow.

### Forbidden

- Track labels: `Track 1`, `Track N`, `Track N: <title>`
- Step labels: `Step 1`, `Step N`, `Step M of Track N`
- Compound labels: `Track 2 Step 1`, `Track 4 iteration 1`
- Review finding IDs and prefixes: `CQ33`, `F-12`, `R-4`, `A-7`,
  `S-2`, or any other `<PREFIX><number>` finding label from
  `reviews/**`
- Review-loop iteration counters: `iteration 1`, `round 2`, when they
  refer to ephemeral review loops
- Named invariants or rule names cited **by label only** —
  "Single-authority invariant", "Load-bearing-file rule",
  "Byte-identity discipline", etc. If the rule matters for a future
  reader of committed content, either restate it in prose or cross-
  reference the stable `.claude/workflow/` location that defines it
  (e.g. `conventions.md` §1.2, `conventions-execution.md` §2.1).
- Plan-file Decision Record IDs (`D1`, `D2`, …) that are **not**
  restated in `adr.md`. IDs that ARE restated in `adr.md` are stable
  (the ADR owns them post-implementation) and may be cited.

### Allowed (these survive in git or are self-contained)

- File paths under the project — source files, workflow docs in
  `.claude/workflow/`, committed artifacts
- Class, interface, method, and field names
- Commit SHAs — the stable way to point at "where this was implemented"
- `adr.md`-defined Decision Record IDs, when `adr.md` itself is the
  reader's reachable context
- Issue tracker IDs (e.g. `YTDB-123`) — these survive in the tracker

### How to rewrite a forbidden reference

Replace the ephemeral label with (a) a prose description of what was
done, (b) the file/class that was modified, or (c) the commit SHA that
implemented it.

Examples:

- ❌ `// added during Track 2 Step 1 to unblock Phase A resume`
- ✅ `// part of the atomic step-file-write + backlog-section-remove
      ordering that keeps Phase A resume idempotent`

- ❌ `Review fix: address CQ72 (anchor drift in legacy fallback)`
- ✅ `Review fix: restore byte-identical phrasing at the two legacy-
      fallback cross-reference sites in plan-slim-rendering.md`

- ❌ `// see Track 4 iteration 1 for context`
- ✅ `// see commit abc1234 for the follow-up that restored these
      sites; the first pass missed two of them`

When in doubt: if a reader on `main` (without the branch, without the
plan) couldn't resolve the reference, the reference is forbidden.

### Self-check before commit

Run a quick grep on staged files before committing — applies to code,
tests, and the two Phase 4 artifacts:

```bash
git diff --cached | grep -nE '\b(Track|Step)[ ]?[0-9]+|\b[A-Z]{1,3}[0-9]+\b'
```

Anything this catches is either a genuine leak to rewrite or an
allowed exception (e.g. issue tracker IDs, class names that happen to
match the pattern) — inspect, then proceed.

---

## 2.4 Commit messages, code review, complexity, decomposition

These rules are needed only when their specific phase or action runs — not
at session startup. Load on demand:

- **Commit message format** — follow the project's `CLAUDE.md` commit
  conventions. Only code changes are committed; workflow files are never
  committed (see §1.2 in `conventions.md`). For the execution-specific
  prefixes (`Review fix:`) used during session resume, see
  [`commit-conventions.md`](commit-conventions.md). Apply the Ephemeral
  identifier rule (§2.3 above) to every commit message.
- **Two-tier dimensional code review** (step-level and track-level
  sub-agent reviews, 4 baseline + up to 6 conditional, max 3 iterations):
  [`code-review-protocol.md`](code-review-protocol.md).
- **Complexity tiers** (which pre-execution reviews to run for Simple /
  Moderate / Complex tracks): covered in
  [`track-review.md`](track-review.md) §Complexity Assessment.
- **Checklist decomposition rules** (step sizing, cross-cutting concerns,
  parallel step annotation): covered in
  [`track-review.md`](track-review.md) §Step Decomposition.
