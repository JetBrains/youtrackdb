# Conventions

Shared formats, rules, and glossary used by all agents in the workflow.

---

## 1.1 Glossary

| Term | Definition |
|---|---|
| **Track** | A coherent stream of related work within a plan. Contains steps. Max ~5-7 steps. If larger, split into dependent tracks during planning. |
| **Step** | A single atomic change = one commit. Fully tested. |
| **Episode** | Structured record of what happened during a step or track. |
| **Scope indicator** | Rough sketch of expected work in a track. |
| **Session** | One invocation of `/execute-tracks`. Handles one track (or resumes an incomplete one). Sessions are separated by context clearing. Episodes bridge context across sessions. |
| **Sub-agent** | A spawned agent for self-contained review tasks (technical/risk/adversarial reviews, code-reviewer, track-level code review). Sub-agents provide fresh perspective; the main agent retains full context. |

---

## 1.2 Plan File Structure

All workflow phases reference this structure.
`<dir-name>` is the plan directory name — provided explicitly by the user, or
defaulting to the current git branch name.

```
docs/adr/<dir-name>/
  implementation-plan.md          <- strategic: goals, architecture, tracks,
                                     track-level episodic summaries
  tracks/
    track-1.md                    <- tactical: decomposed steps, step episodes
    track-2.md
    ...
  reviews/
    structural.md
    track-1-technical.md
    ...
```

### Plan file content (`implementation-plan.md`)

```markdown
# <Feature Name>

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
```

**Planning rule:** If a track would need more than ~5-7 steps or internal
phasing, split it into separate dependent tracks. Track sequencing and
episode propagation between dependent tracks is handled by the session
workflow — this gives the same "informed decomposition" benefit without
extra complexity.

### Track status markers

| Marker | Meaning |
|---|---|
| `[ ]` | Not started |
| `[x]` | Completed |
| `[~]` | Skipped (recommended by track review or execution agent) |

### After track completion

The track episode is appended to the plan file under the completed track's
checklist entry:

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

ESCALATE triggers inline replanning (see workflow.md), which restructures
the plan file directly — no strategy refresh line is written.

### Session state detection

On session startup, the plan file and step files together determine
the resume state:

| Plan file state | Step file state | Session state |
|---|---|---|
| Last `[x]` track has no `**Strategy refresh:**` line | — | **State A**: perform strategy refresh first |
| All `[x]` tracks have `**Strategy refresh:**`; next track is `[ ]` | No step file for next track | **State B**: fresh start on next track |
| A track is `[ ]` | Step file exists | **State C**: mid-track resume — read Progress section |

**State C sub-states** (from step file Progress section):

| Progress section | Resume action |
|---|---|
| `Review + decomposition` is `[ ]` | Reviews completed section may show partial progress — re-run only missing reviews, then decompose |
| `Review + decomposition` is `[x]`, steps partially complete | Resume from next `[ ]` step |
| All steps `[x]`, `Track-level code review` is `[ ]` | Run Phase C (track-level code review) |
| All phases `[x]` | Track is done but plan file not yet updated — write track episode, mark `[x]` |

### Step file content (`tracks/track-N.md`)

```markdown
# Track N: <title>

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/5 complete)
- [ ] Track-level code review

## Reviews completed
- [x] Technical
- [x] Risk

## Steps
- [x] Step: <description>
  > **What was done:** ...
  > **What was discovered:** ...
  > **Key files:** ...

- [x] Step: <description>
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
- Mark `Track-level code review` as `[x]` when the review passes

The **Reviews completed** section records which pre-execution reviews
finished. If a session is interrupted during Phase A, the next session
can skip completed reviews and only re-run missing ones.

Step files are created by the review phase during Phase 3 when steps
are decomposed. They do not exist during Phase 1 (planning) or Phase 2
(structural review) — only scope indicators in the plan file exist at
that point.

**The plan is a strategic guide, not a rigid task graph.** Track
descriptions, architecture notes, and inter-track dependencies are the
load-bearing parts. Step-level detail is tactical and should emerge
just-in-time during execution when the agent has maximum codebase
context. The review phase always has freedom to adapt step-level
decomposition without formal replanning — only track-level or
decision-level changes require escalation.

### Scope indicators (required)

Every track must include a **Scope** line in its description block: a rough
sketch of the expected work — approximate step count and a brief list of
what they'd cover. Scope indicators are strategic signals, not tactical
commitments. The review phase always does full step decomposition at
execution time regardless.

Format: `> **Scope:** ~N steps covering X, Y, Z`

Scope indicators serve three purposes:
1. **Structural review** can catch sizing issues (a track claiming ~2 steps
   but describing 8 distinct changes) and ordering problems (scope of
   Track B implies a dependency on Track A's output).
2. **Human reviewers** can quickly gauge relative effort across tracks.
3. **Execution planning** — the review phase uses scope indicators as
   a starting point for just-in-time step decomposition, not as a binding
   contract.

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

## 1.3 Episode Formats

### Step completion episode

Recorded in the step file under the completed step item:

```markdown
- [x] Step: <description>
  > **What was done:** ...
  > **What was discovered:** ... (when applicable)
  > **What changed from the plan:** ... (when applicable)
  > **Key files:** ...
  > **Critical context:** ... (when applicable)
```

#### Episode fields

Episodes are produced by the **execution agent** (step implementation phase)
after it commits the code changes and completes the code review cycle. The
execution agent has full context of what it implemented, what it discovered,
and what deviated from the plan.

| Field | Required | Purpose |
|---|---|---|
| **What was done** | Always | Factual summary of the implementation — files created/modified, approach taken |
| **What was discovered** | When applicable | Unexpected findings about the codebase, APIs, or behavior that weren't anticipated by the plan. This is the most important field — it's the mechanism for adapting to new information |
| **What changed from the plan** | When applicable | Any deviations from the planned approach, and which future steps may be affected. If the deviation is significant, flag specific step IDs |
| **Key files** | Always | Files created or modified, with (new) or (modified) annotations |
| **Critical context** | When applicable | Free-form field for anything essential that doesn't fit the structured fields above — e.g., a fundamental architectural insight, a performance characteristic that changes the approach for the whole feature, or a constraint discovered that affects multiple tracks. Use sparingly; most episodes won't need this |

**Rules:**
- **"What was discovered" must be filled whenever anything unexpected is
  found** — even if it didn't block the current step. Future sessions and
  track reviews depend on this field to adapt.
- **"What changed from the plan" must name affected future steps** when the
  deviation could impact them. The execution agent uses this to
  adapt remaining steps within the track and across tracks.
- Keep each field concise but complete. A reviewer should understand the
  full step outcome from the episode alone, without reading the diff.
- Episodes are immutable once committed. If later work reveals an episode
  was wrong, add a correction note to the later step's episode, don't edit
  the original.

#### Minimal episode (nothing unexpected)

```markdown
- [x] Step: Add histogram header to leaf page structure
  > **What was done:** Extended `LeafPage` with 16-byte histogram header.
  > Added serialization/deserialization in `LeafPageSerializer`.
  >
  > **Key files:** `LeafPage.java` (modified), `LeafPageSerializer.java`
  > (modified), `LeafPageHistogramTest.java` (new)
```

When there are no discoveries and no plan deviations, those fields are
simply omitted — no need for "N/A" placeholders.

### Step failed episode

```markdown
- [!] Step: <description>
  > **What was attempted:** ...
  > **Why it failed:** ...
  > **Impact on remaining steps:** ...
  > **Key files:** ...
```

When a step implementation phase cannot complete its work
(tests won't pass, coverage can't be met, code reviewer finds fundamental
issues, wrong API assumption), it signals failure. The execution agent reverts
uncommitted changes and produces a failed episode explaining what was
attempted, why it failed, and the impact on remaining steps.

The execution agent then decides:
- **Retry** with a different approach
- **Split** the step into smaller pieces that can succeed independently
- **Adjust** upcoming steps to work around the discovered constraint
- **Escalate** if the failure undermines the track's approach

If the same step fails twice, stop and present the situation to the user
(see workflow.md §Failure Handling).

Failed episodes are recorded in the step file with the `[!]` marker so
future sessions and reviews can see what was attempted and why it didn't
work.

### Track episode

Written to the plan file under the completed track's checklist entry.
Contains: what was built, key discoveries, plan deviations with cross-track
impact. Reference to step file with counts.

### Episode length rule

Proportional to cross-track impact. A track that went as planned and
produced no surprises needs 1-2 sentences. A track that discovered
architectural issues, changed assumptions, or deviated from the plan
should include enough detail for the execution agent to assess
impact on remaining tracks without reading the step file. There is no
hard line limit — clarity and completeness for downstream decision-making
is the criterion.

The same principle applies to step episodes: a trivial rename needs one
line; a step that uncovered a concurrency bug needs a full explanation.

### Commit and episode ordering

During Phase 3, the step implementation phase commits its
code changes first (including any code review fix commits). After all code
is committed, the execution agent writes the episode to the step file and
commits it as a **separate episode commit**. This avoids the chicken-and-egg
problem of needing the episode before the commit while needing the
implementation to produce the episode.

### Where episodes live

Step-level episodes are recorded in the **step file**
(`docs/adr/<dir-name>/tracks/track-N.md`), not in the plan file. This keeps
the plan file focused on strategic content.

After a track completes (user review), the execution agent writes a
compressed **track episode** into the plan file under
the track's checklist entry. The track episode is a strategic summary
synthesized from the step episodes — it captures what the track achieved
and what was discovered, without step-level detail.

---

## 1.4 Commit Message Format

Follow the project's commit message conventions (see `CLAUDE.md`). If the
branch name contains a YTDB issue number, the `prepare-commit-msg` hook
auto-prepends the prefix.

```
YTDB-NNN: <imperative summary, under 50 chars>

<detailed explanation of WHY this change was made — motivation, context,
trade-offs. Not a restatement of the diff.>
```

Omit the `YTDB-NNN:` prefix when the branch has no associated issue.

**"Why" over "what"** in commit messages. The diff shows what changed; the
message explains why.

---

## 1.5 Two-Tier Code Review

Code review happens at two levels, catching different classes of issues:

### Step-level code review (within each execution agent step phase)

After implementing and committing, the execution agent runs a code review loop:

1. Delegates review to the **code-reviewer agent** (fresh sub-agent).
2. If findings are returned, fixes them and re-submits for review.
3. Repeats until approved OR **max 3 iterations** reached.
4. Each iteration spawns a fresh code-reviewer sub-agent.
5. If max iterations reached, notes remaining findings in the episode.
   Some findings may be genuinely hard or non-fixable within the step's
   scope — this is an escalation signal, not a "try harder" signal.

**What step-level review catches:** localized code quality — naming, error
handling, edge cases, test coverage gaps, obvious bugs in the diff.

The code review loop runs **within the execution agent's context** — no context
clearing between review iterations. The execution agent retains full knowledge
of why it made each implementation choice, enabling targeted and accurate
fixes.

### Track-level code review (after all steps complete)

After all steps are committed, the execution agent spawns a fresh
**code review sub-agent** that reviews the full track diff
(`git diff <base>..HEAD`):

1. The sub-agent reviews the entire track diff for systematic issues.
2. If findings are returned, the execution agent applies fixes as additional
   commits, then spawns a fresh sub-agent to verify.
3. Repeats until approved OR **max 3 iterations** reached.
4. If max iterations reached, remaining findings are noted and presented
   to the user during track review.

**What track-level review catches:** systematic patterns repeated across
steps, cross-step consistency issues, accumulated technical debt that's
individually acceptable but collectively problematic, integration issues
where steps compile independently but the combined result has subtle
interactions.

---

## 1.6 Complexity Tiers

### Track complexity and review pipeline

| Track complexity | Review pipeline |
|---|---|
| Simple (1-2 steps) | Technical review only — even if track characteristics suggest Risk or Adversarial, skip them for 1-2 step tracks. |
| Moderate (3-5 steps) | Technical review as baseline. Risk and/or Adversarial reviews are added when track characteristics warrant them (see track-execution.md "Which reviews to run"). |
| Complex (6-7 steps, or critical path / high-risk) | Full: Technical + Risk + Adversarial. |

Complexity determines which pre-execution reviews to run, not user
interaction level — all tracks execute autonomously after review.

All tracks get both step-level and track-level code review regardless of
complexity.

---

## 1.7 Checklist Decomposition Rules

These rules apply to step decomposition by the review phase.

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

## 1.8 Review Iteration Protocol

Shared by structural review (planning.md), track pre-execution reviews
(track-execution.md), and track-level code review.

- Max 3 iterations per review type
- Finding IDs are cumulative across iterations:
  - `S1, S2, ...` for structural review
  - `T1, T2, ...` for technical review
  - `R1, R2, ...` for risk review
  - `A1, A2, ...` for adversarial review
  - `C1, C2, ...` for track-level code review
- If blockers persist after 3 iterations, escalate
- Severity levels: **blocker** / **should-fix** / **suggestion** / **skip** (track reviews only — recommends skipping the entire track)

### Iteration flow

```
Iteration 1: Full review -> findings -> decisions -> apply fixes
Iteration 2: Gate check -> verify fixes + catch regressions -> if blockers, loop
Iteration 3: Gate check -> if still blockers, escalate
```

If structural fixes significantly restructure the plan (tracks reordered,
scope indicators changed substantially), re-run the full review instead of
the gate check to catch cascading issues.

### Finding format

```markdown
### Finding <PREFIX><N> [blocker|should-fix|suggestion|skip]
**Location**: <where in the plan or codebase>
**Issue**: <what's wrong>
**Proposed fix**: <concrete change>
```

### Gate verification output

For each previous finding: **VERIFIED**, **STILL OPEN** (with explanation),
or **REJECTED** (no action needed). New findings (if any) with cumulative
numbering. Summary: **PASS** or **FAIL**.

---

## 1.9 Note on `~/.claude/plans/` vs `adr/`

There are two plan-related directories — don't confuse them:

| | Global `~/.claude/plans/` | Project `docs/adr/<dir-name>/` |
|---|---|---|
| **Purpose** | Claude Code session artifacts | Durable project plans (lightweight ADRs) |
| **Names** | Auto-generated (`synthetic-orbiting-gizmo.md`) | `implementation-plan.md` + `tracks/track-N.md` |
| **Version-controlled** | No | Yes |
| **Survives context clearing** | Exists on disk but not reliably linked | Yes — referenced by path in prompts |
| **After feature is complete** | Can be deleted | Keep as decision record |

Claude may internally use plan mode during execution — that's fine.
But insights must be captured in the **project's track episodes** (plan
file) and **step episodes** (step files), not left only in
`~/.claude/plans/`.
