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

## 2.3 Commit Message Format

Follow the project's commit message conventions (see `CLAUDE.md`). Only
**code changes** are committed — workflow working files are never committed
(see `conventions.md` §1.2). For commit type prefixes used during execution,
see [`commit-conventions.md`](commit-conventions.md).

---

## 2.4 Two-Tier Dimensional Code Review

Code review happens at two levels — step-level and track-level — using
review sub-agents selected based on code characteristics (see
[`review-agent-selection.md`](review-agent-selection.md)). Four baseline
agents always run; up to six conditional agents are added based on the
step/track description and changed files. After all selected agents
complete, findings are deduplicated, severity-assigned (blocker /
should-fix / suggestion), and attributed to source dimension(s). Max 3
iterations per level.

**Single-step tracks skip the code review portion of Phase C** — the
step-level review already covered the identical diff. Phase C still runs
for track completion (episode, user approval). See `track-code-review.md`
§Single-Step Track.

- **Step-level:** see `step-implementation.md` §Per-Step Workflow (sub-step 4)
- **Track-level:** see `track-code-review.md` (includes track completion)

---

## 2.5 Complexity Tiers

### Track complexity and review pipeline

| Track complexity | Review pipeline |
|---|---|
| Simple (1-2 steps) | Technical review only — even if track characteristics suggest Risk or Adversarial, skip them for 1-2 step tracks. |
| Moderate (3-5 steps) | Technical review as baseline. Risk and/or Adversarial reviews are added when track characteristics warrant them (see track-review.md "Which reviews to run"). |
| Complex (6-7 steps, or critical path / high-risk) | Full: Technical + Risk + Adversarial. |

Complexity determines which pre-execution reviews to run, not user
interaction level — all tracks execute autonomously after review.

All tracks get both step-level and track-level code review regardless of
complexity.

---

## 2.6 Checklist Decomposition Rules

These rules apply to step decomposition during Phase A (review + decomposition).

- Each step = one commit
- Each step = fully tested, self-contained change with 85% line / 70%
  branch coverage
- If a step touches more than ~3 files or does unrelated things, split it
- If a step feels trivial (single import, single rename), merge it into a
  neighbor
- Note **cross-cutting concerns** (shared types, refactors) as separate
  steps rather than embedding them inside feature steps

**Parallel step annotation:** During step decomposition, the track
review agent may identify independent steps within the track — steps that
don't depend on each other and don't modify the same files. These are
annotated with `*(parallel with Step N.M)*` in the step file. Must not
modify same files.

---

## 2.7 Note on `~/.claude/plans/` vs `adr/`

There are two plan-related directories — don't confuse them:

| | Global `~/.claude/plans/` | Project `docs/adr/<dir-name>/` |
|---|---|---|
| **Purpose** | Claude Code session artifacts | Working files during execution; final artifacts after Phase 4 |
| **Names** | Auto-generated | Working: `implementation-plan.md`, `tracks/track-N.md`; Final: `design-final.md`, `adr.md` |
| **Version-controlled** | No | Only Phase 4 artifacts (`design-final.md`, `adr.md`) |
| **Survives context clearing** | Exists on disk but not reliably linked | Yes — on disk, referenced by path |
| **After feature is complete** | Can be deleted | Working files deleted with branch; artifacts kept |

Claude may internally use plan mode during execution — that's fine.
But insights must be captured in the **project's track episodes** (plan
file) and **step episodes** (step files), not left only in
`~/.claude/plans/`.
