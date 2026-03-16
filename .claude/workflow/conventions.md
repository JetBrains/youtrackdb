# Conventions

Shared formats, rules, and glossary used by all agents in the workflow.

---

## 1.1 Glossary

| Term | Definition |
|---|---|
| **Track** | A coherent stream of related work within a plan. Contains steps. Max ~5-7 steps. If larger, split into dependent tracks during planning. |
| **Step** | A single atomic change = one commit. Fully tested. |
| **Episode** | Structured record of what happened during a step or track. |
| **Execution log** | Raw notable-events trace from a step executor. |
| **Scope indicator** | Rough sketch of expected work in a track. |
| **Execution orchestrator** | Team lead agent. Cross-track coordination. All user interaction flows through it. |
| **Track orchestrator** | Teammate agent. Owns one track's lifecycle. Internal to the execution orchestrator — user does not interact with it directly. |
| **Step executor** | Sub-agent. Implements one step. |

---

## 1.2 Plan File Structure

All three agents reference this structure. `<dir-name>` is the plan directory
name — provided explicitly by the user, or defaulting to the current git
branch name.

```
adr/<dir-name>/
  implementation-plan.md          <- strategic: goals, architecture, tracks,
                                     track-level episodic summaries
  tracks/
    track-1.md                    <- tactical: decomposed steps, step episodes
    track-2.md
    ...
  logs/
    step-1.1.md                   <- execution logs: notable events from each
    step-1.2.md                      step's implementation (permanent record)
    step-2.1.md
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
episode propagation between dependent tracks is handled by the execution
orchestrator — this gives the same "informed decomposition" benefit without
extra complexity.

### Track status markers

| Marker | Meaning |
|---|---|
| `[ ]` | Not started |
| `[x]` | Completed |
| `[~]` | Skipped (recommended by track review or execution orchestrator) |

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
- 2-4 sentences covering: what was built, key discoveries, plan deviations
  with cross-track impact
- Reference to the step file with step count and failure count
- This is what future track sessions read from the plan file — the step
  file is available for deeper investigation if needed

### Step file content (`tracks/track-N.md`)

```markdown
# Track N: <title>

## Steps
- [x] Step: <description>
  > **What was done:** ...
  > **What was discovered:** ...
  > **Key files:** ...

- [x] Step: <description>
  > **What was done:** ...
  > **Key files:** ...
```

Step files are created by the track orchestrator during Phase 3 when steps
are decomposed. They do not exist during Phase 1 (planning) or Phase 2
(structural review) — only scope indicators in the plan file exist at
that point.

**The plan is a strategic guide, not a rigid task graph.** Track
descriptions, architecture notes, and inter-track dependencies are the
load-bearing parts. Step-level detail is tactical and should emerge
just-in-time during execution when the executor has maximum codebase
context. The track orchestrator always has freedom to adapt step-level
decomposition without formal replanning — only track-level or
decision-level changes require escalation.

### Scope indicators (required)

Every track must include a **Scope** line in its description block: a rough
sketch of the expected work — approximate step count and a brief list of
what they'd cover. Scope indicators are strategic signals, not tactical
commitments. The track orchestrator always does full step decomposition at
execution time regardless.

Format: `> **Scope:** ~N steps covering X, Y, Z`

Scope indicators serve three purposes:
1. **Structural review** can catch sizing issues (a track claiming ~2 steps
   but describing 8 distinct changes) and ordering problems (scope of
   Track B implies a dependency on Track A's output).
2. **Human reviewers** can quickly gauge relative effort across tracks.
3. **Execution planning** — the track orchestrator uses scope indicators as
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

| Field | Required | Purpose |
|---|---|---|
| **What was done** | Always | Factual summary of the implementation — files created/modified, approach taken |
| **What was discovered** | When applicable | Unexpected findings about the codebase, APIs, or behavior that weren't anticipated by the plan. This is the most important field — it's the mechanism for adapting to new information |
| **What changed from the plan** | When applicable | Any deviations from the planned approach, and which future steps may be affected. If the deviation is significant, flag specific step IDs |
| **Key files** | Always | Files created or modified, with (new) or (modified) annotations |
| **Critical context** | When applicable | Free-form field for anything essential that doesn't fit the structured fields above — e.g., a fundamental architectural insight, a performance characteristic that changes the approach for the whole feature, or a constraint discovered that affects multiple tracks. Use sparingly; most episodes won't need this |

**Rules:**
- **"What was discovered" must be filled whenever the step executor
  encounters anything unexpected** — even if it didn't block the current
  step. The track orchestrator and future track reviews depend on this
  field to adapt.
- **"What changed from the plan" must name affected future steps** when the
  deviation could impact them. The track orchestrator uses this to adapt
  remaining steps within the track; the execution orchestrator uses it to
  adapt across tracks.
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

When a step executor cannot complete its work (tests won't pass, coverage
can't be met, code reviewer finds fundamental issues, wrong API assumption),
it appends failure details to the execution log and signals failure. Failed
steps always use the episode synthesis agent, regardless of the step's
complexity — the full trail of what was attempted is essential for recovery
decisions.

The track orchestrator receives the failed episode and decides:
- **Retry** with a different approach (new sub-agent)
- **Split** the step into smaller pieces that can succeed independently
- **Adjust** upcoming steps to work around the discovered constraint
- **Escalate** to the execution orchestrator if the failure undermines
  the track's approach

Failed episodes are recorded in the step file with the `[!]` marker so
the track orchestrator and future sessions can see what was attempted and
why it didn't work.

### Track episode

Written to the plan file under the completed track's checklist entry.
Contains: what was built, key discoveries, plan deviations with cross-track
impact. Reference to step file with counts.

### Episode length rule

Proportional to cross-track impact. A track that went as planned and
produced no surprises needs 1-2 sentences. A track that discovered
architectural issues, changed assumptions, or deviated from the plan
should include enough detail for the execution orchestrator to assess
impact on remaining tracks without reading the step file. There is no
hard line limit — clarity and completeness for downstream decision-making
is the criterion.

The same principle applies to step episodes: a trivial rename needs one
line; a step that uncovered a concurrency bug needs a full explanation.

### Commit and episode ordering

During Phase 3, the step executor commits its code changes first. The track
orchestrator then produces the episode (using `git diff HEAD~1..HEAD` for
the code diff) and commits it as a **separate episode commit**. This avoids
the chicken-and-egg problem of needing the episode before the commit while
needing the diff to produce the episode. The code commit hash can be
referenced in the episode since it is already known.

### Where episodes live

Step-level episodes are recorded in the **step file**
(`adr/<dir-name>/tracks/track-N.md`), not in the plan file. This keeps
the plan file focused on strategic content.

After a track completes (user review + strategy refresh), the execution
orchestrator writes a compressed **track episode** into the plan file under
the track's checklist entry. The track episode is a strategic summary
synthesized from the step episodes — it captures what the track achieved
and what was discovered, without step-level detail.

---

## 1.4 Execution Log Format

Every step executor maintains a structured execution log file at
`adr/<dir-name>/logs/step-<N.M>.md`, regardless of the step's
complexity. The log captures **notable events only** — moments where
expectations are violated or non-obvious choices are made. The git diff
already shows what changed mechanically; the log captures what the diff
cannot.

Execution logs are **permanent and version-controlled**. They serve as
the raw data behind episodes — the uncompressed tactical trace that can
be re-examined when:
- A bug surfaces weeks later and you need the full reasoning trail behind
  a design decision that the episode only summarized
- Replanning needs to understand exactly what was tried and why it failed,
  beyond what the failed episode captured
- An episode turns out to be incomplete and needs re-synthesis from the
  original log

### Format

```markdown
# Execution Log: <step description>

## Event: <short title>
<what happened, what was expected, what was found instead, what was decided>

## Event: <short title>
<...>
```

### What to log (notable events)

- Design decisions made and why (e.g., "chose adapter pattern over
  inheritance because X interface is final")
- Failed attempts and what was learned (e.g., "tried lock-free approach,
  discovered PageCache holds exclusive lock during eviction")
- Surprising API behaviors or codebase findings (e.g., "validateSchema
  middleware silently swallows errors — not documented anywhere")
- Code review findings that required rethinking the approach (not minor
  fixes)
- Assumptions from the plan that turned out to be wrong

### What NOT to log

- Routine actions (read file, ran test, edited line) — visible in the diff
- Minor code review fixes (formatting, naming) — not strategically relevant
- Test results unless they reveal something unexpected

---

## 1.5 Commit Message Format

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

## 1.6 Episode Synthesis Rules

For moderate, complex, and failed steps, after the step executor commits
(or signals failure), the track orchestrator spawns an **episode synthesis
agent** to produce the episode. This separates tactical execution from strategic compression —
the step executor focuses on implementation, the synthesizer focuses on
extracting what matters for the plan.

The episode synthesis agent receives:
- The execution log file
- The git diff of the step's committed changes (`git diff HEAD~1..HEAD`)
- The step description
- The track description and relevant architecture notes
- Curated episodes from prior steps (same set the step executor received)

The synthesis agent produces:
- A structured episode (all fields: What was done / What was discovered /
  What changed from the plan / Key files / Critical context)
- For failed steps: a structured failed episode (What was attempted /
  Why it failed / Impact on remaining steps / Key files)

**Rules:**
- The git diff is the authoritative source for "What was done" and
  "Key files" — the synthesizer reconstructs these from the diff, not
  from the execution log.
- The execution log is the primary source for "What was discovered" and
  "What changed from the plan" — these are the notable events that the
  diff cannot show.
- "Critical context" is synthesized from execution log events that have
  implications beyond the current step or track.
- The synthesizer should flag any execution log events that reference
  future steps or other tracks — these are candidates for cross-track
  impact assessment.

---

## 1.7 Complexity Tiers

### Step complexity and episode production

| Step complexity | Episode production | Rationale |
|---|---|---|
| Simple + successful | Self-reported by step executor | Log kept on disk |
| Moderate + successful | Execution log + synthesizer | Notable events likely |
| Complex + successful | Execution log + synthesizer | High risk of underreporting |
| Failed (any) | Execution log + synthesizer | Full trail essential |

The complexity of a step is assessed by the track orchestrator during step
decomposition, informed by the track's overall complexity assessment.

### Track complexity and review pipeline

| Track complexity | Review pipeline |
|---|---|
| Simple (1-2 steps) | Technical review only — even if track characteristics suggest Risk or Adversarial, skip them for 1-2 step tracks. |
| Moderate (3-5 steps) | Technical review as baseline. Risk and/or Adversarial reviews are added when track characteristics warrant them (see track-orchestrator.md "Which reviews to run"). |
| Complex (6-7 steps, or critical path / high-risk) | Full: Technical + Risk + Adversarial. |

Complexity determines which reviews to run, not user interaction level —
all tracks execute autonomously after review.

---

## 1.8 Checklist Decomposition Rules

These rules apply to step decomposition by the track orchestrator.

- Each step = one commit
- Each step = fully tested, self-contained change with 85% line / 70%
  branch coverage
- If a step touches more than ~3 files or does unrelated things, split it
- If a step feels trivial (single import, single rename), merge it into a
  neighbor
- Note **cross-cutting concerns** (shared types, refactors) as separate
  steps rather than embedding them inside feature steps

**Parallel step annotation:** During step decomposition, the track
orchestrator may identify independent steps within the track — steps that
don't depend on each other and don't modify the same files. These are
annotated with `*(parallel with Step N.M)*` in the step file. Must not
modify same files.

---

## 1.9 Review Iteration Protocol

Shared by structural review (planning.md) and track reviews
(track-orchestrator.md).

- Max 3 iterations per review type
- Finding IDs are cumulative across iterations:
  - `S1, S2, ...` for structural review
  - `T1, T2, ...` for technical review
  - `R1, R2, ...` for risk review
  - `A1, A2, ...` for adversarial review
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

## 1.10 Note on `~/.claude/plans/` vs `adr/`

There are two plan-related directories — don't confuse them:

| | Global `~/.claude/plans/` | Project `adr/<dir-name>/` |
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
