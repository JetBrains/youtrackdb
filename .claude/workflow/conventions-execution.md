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
approves** the track results (see workflow.md §Track Completion Protocol):

```markdown
- [x] Track 2: <title>
  > <description>
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

### After strategy refresh

The strategy refresh result is appended to the same track's block in the
plan file, after the step file reference:

```markdown
- [x] Track 2: <title>
  > <description>
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
record:

```markdown
- [~] Track 3: <title>
  > <description>
  >
  > **Skipped:** <reason>
  >
  > **Strategy refresh:** CONTINUE — no downstream impact from skipping.
```

ESCALATE triggers inline replanning (see workflow.md), which restructures
the plan file directly — no strategy refresh line is written.

### Session state detection

Moved to `workflow.md` §Startup Protocol — the only place where state
detection is used.

### Step file content (`tracks/track-N.md`)

```markdown
# Track N: <title>

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
```

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
