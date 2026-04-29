# Track Execution — Phase C: Code Review + Track Completion

After all steps are committed, review the full track diff using sub-agents.
These are deliberately sub-agents — fresh eyes catch systematic issues that
you (as the implementer) are blind to.

After the review loop completes and any deferred findings are processed,
this phase continues directly into track completion: compiling the track
episode, presenting results to the user, and marking the track `[x]` upon
approval. Merging code review and track completion into a single session
ensures the agent has full context of which findings were fixed, which were
deferred (and where), and what plan corrections were made — all of which
feed into an accurate track episode.

---

## Single-Step Track: Skip Code Review, Proceed to Track Completion

If the track has exactly **1 step** AND that step is tagged `risk: high`
in the step file, the code review portion of Phase C is skipped — the
step-level review in Phase B already covered the identical diff and
there is no cross-step interaction to catch.

1. Mark `Track-level code review` as `[x]` in the step file's Progress
   section with a note: `(skipped — single-step track, fully reviewed
   in Phase B)`.
2. Skip directly to **Track Completion** (below) in the same session.

If the single step is `risk: medium` or `risk: low`, step-level review
was skipped per [`risk-tagging.md`](risk-tagging.md), so track-level
review must run. Proceed to **Multi-Step Tracks** below; the single
step is treated as the entire diff.

---

## Multi-Step Tracks

Select review agents based on code characteristics (see
[`review-agent-selection.md`](review-agent-selection.md)), then spawn them
in parallel. Baseline agents (4) always run; conditional agents are added
based on the track description and changed files across the full diff.
See [`code-review-protocol.md`](code-review-protocol.md) for the two-tier
protocol overview and [`review-iteration.md`](review-iteration.md) for
iteration limits, finding ID prefixes, and gate format.

All selected reviews run against the same diff
(`git diff {base_commit}..HEAD`) and produce independent findings. Launching
them in parallel saves wall-clock time since they examine different aspects
of the changes.

---

## Phase C Startup

1. Read the step file's `## Base commit` section to get the SHA recorded
   at the start of Phase B.
2. Read the **implementation plan** (`docs/adr/<dir-name>/implementation-plan.md`)
   — this provides strategic context (goals, architecture notes, decision
   records, track episodes from completed tracks).
3. Read the **step file** (`docs/adr/<dir-name>/tracks/track-N.md`) — this
   provides the step descriptions and episodes.
4. **Generate the slim plan snapshot** at
   `/tmp/claude-code-plan-slim-$PPID.md`. Apply the rendering rule in
   [`plan-slim-rendering.md`](plan-slim-rendering.md) to the plan read in
   step 2, then write the snapshot. Sub-agents spawned for track-level
   review will read this snapshot by path — this keeps the main agent's
   tool-call history from accumulating a plan copy per sub-agent spawn.
   Regenerate the snapshot if plan corrections are applied during the
   review loop (before the next spawn batch).
5. Use `{base_commit}` when spawning all review sub-agents.
   All sub-agents review `git diff {base_commit}..HEAD`.

If `## Base commit` is missing (e.g., older step file format), fall back to
finding the parent of the first step's commit via git log.

---

## Sub-agents

### Context passed to all sub-agents

Every sub-agent receives the same context block. Assemble it once and
include it in each agent's prompt:

```
## Workflow Context
This is a **track-level code review** within a structured development
workflow. A **track** is a coherent stream of related work containing
multiple steps (each step = one commit, fully tested). You are reviewing
the **full track diff** — all steps combined — to catch cross-step
interaction issues that step-level reviews could not see (e.g.,
inconsistent error handling across steps, missing integration between
components introduced in different steps, architectural drift from the
plan). The implementation plan below provides strategic context: goals,
architecture decisions (Decision Records), constraints, and component
topology (Component Map). The track steps file provides tactical context:
what each step does and what was discovered. **Episodes** are the
blockquoted sections under completed steps (starting with
`**What was done:**`) — structured records of implementation outcomes.
Use episodes to understand intent and check whether the combined result
matches the plan's goals. Severities: **blocker** (must fix),
**should-fix** (should fix before merge), **suggestion** (optional improvement).

## Review Target
Track {N}: {track title}
Reviewing commit range: {base_commit}..HEAD

## Implementation Plan (strategic context)
Read the slim plan snapshot at:
  /tmp/claude-code-plan-slim-{PPID}.md
Filtered view of the plan — completed tracks show title + intro +
track episode + strategy refresh only; the current track and other
not-started tracks are shown in full. If the snapshot is missing, fall
back to docs/adr/{dir-name}/implementation-plan.md.

## Track Steps (tactical context)
Read the step file at:
  docs/adr/{dir-name}/tracks/track-{N}.md
The file begins with a `## Description` section carrying the track's
original description — intro paragraph +
**What/How/Constraints/Interactions** subsections + any track-level
diagram — copied there at Phase A start. Below that, all steps for
this track appear with their episodes. Each step also carries a
`**Risk:**` line tagging it as `low`, `medium`, or `high` — treat
`medium` and `high` step ranges as **focal points** within the diff
(weight your attention toward those changes; the tag identifies where
tests + the workflow's own gating could not easily catch issues, so
this review carries more of the load there). If the file does not
contain a `## Description` section (legacy step file created before
Phase A's description-move was added), read the track description from
the plan file's checklist entry instead.

## Changed Files
{output of git diff {base_commit}..HEAD --name-only — passed inline,
small}

## Skip These Files (generated code)
- core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/*
- Any files under generated-sources/ or generated-test-sources/
- Generated Gremlin DSL classes

## Diff
{output of git diff {base_commit}..HEAD — passed inline since it is the
review target}
```

**Why paths, not inline contents.** Inlining the plan and track file
into every track-level sub-agent spawn embeds copies in the main
agent's tool-call history — up to ~10 agents × 3 iterations per track.
Paths keep the main agent lean; sub-agents Read the files themselves.
The diff stays inline because it is the review target.

The main agent substitutes `{PPID}`, `{dir-name}`, `{N}`, and
`{base_commit}` when composing each prompt.

### Agent selection and launching

Select agents per [`review-agent-selection.md`](review-agent-selection.md).
Use the track description and `git diff {base_commit}..HEAD --name-only` to
determine which conditional agents to include alongside the baseline.

Each agent's prompt is:

```
Review the following code changes from your specialized perspective.

{context block from above}
```

**Launch all selected sub-agents in a single message** (parallel tool calls)
to maximize efficiency. Wait for all to complete before proceeding to
synthesis.

---

## Synthesis

After all selected sub-agents complete, produce a unified findings list:

1. **Deduplicate**: If multiple agents flagged the same issue (e.g., a
   missing crash-recovery test flagged by both `review-test-crash-safety`
   and `review-crash-safety`), merge into one finding and note which
   dimensions identified it.

2. **Prioritize**: Assign severity using the standard review severity
   levels:
   - **blocker** — must fix before merge (bugs, security vulns, crash
     safety, data corruption, tests giving false confidence)
   - **should-fix** — should fix before merge (likely bugs, performance
     issues, concurrency risks, missing critical test coverage)
   - **suggestion** — recommended improvements (minor style, optional
     optimizations, additional test scenarios)

3. **Attribute**: For each finding, indicate which review dimension(s)
   identified it and use the appropriate finding prefix.

Present the synthesized findings list to proceed to the review loop.

---

## Review loop

Iterate on the synthesized findings:

1. If any findings need fixes:
   - Apply fixes as **additional commits** (never amend prior commits).
     Commit messages and any new code comments must follow the Ephemeral
     identifier rule in
     [`conventions-execution.md`](conventions-execution.md) §2.3 — no
     `Track N`, `Step N`, finding IDs, or review iteration counters
     in the message or the diff.
   - Run tests to verify fixes don't break anything
   - **Update the Progress section** on disk to record the completed
     iteration (e.g., `- [ ] Track-level code review (1/3 iterations)`).
   - Spawn **fresh sub-agents** to verify (gate check) — only re-run the
     review dimension(s) that had open findings. For example, if only
     crash-safety code findings and test-completeness findings remain,
     spawn only `review-crash-safety` and `review-test-completeness`.
     If findings span all dimensions, re-run all originally selected agents.
   - **Context consumption check** (mandatory after each iteration,
     except the last): run
     `cat /tmp/claude-code-context-usage-$PPID.txt`. If the level is
     `warning` (≥25%) or `critical` (≥40%), do NOT start the next
     iteration. Save all work (update Progress section with current
     iteration count, commit) and ask the user for a session refresh
     (see workflow.md §Context Consumption Check). If the level is
     `safe`/`info`, continue to the next iteration. If the file does
     not exist or the command fails, this is **not an error** — treat
     as `safe` and continue.
2. Max 3 iterations **total across sessions** — on resume, read the
   iteration count from the Progress section to determine how many remain.
   The iteration count is shared across all review dimensions (not
   independent counters).
3. If blockers persist after 3 iterations, note them — they'll be presented
   to the user during track completion (below).
4. When all reviews pass (or max iterations reached), mark
   `Track-level code review` as `[x]` in the step file's Progress section.

---

## Plan Corrections from Deferred Findings

During synthesis and the review loop, some findings may be **out of scope
for the current track** — the issue is real but fixing it here would expand
the track beyond its goals. After all in-scope fixes are applied and the
review loop completes, process any deferred findings by updating the
implementation plan:

1. **Categorize** — for each finding you chose not to fix in the current
   track, decide where the work belongs:
   - An **existing future track** — if it fits that track's purpose, add
     the item to that track's description. Update the scope indicator if
     the addition meaningfully changes the expected step count.
   - A **new separate track** — if no existing track covers the work, add
     a new track to the plan's checklist with a description, scope
     indicator, and dependency notation (typically depends on the current
     track). Follow the same format as other tracks in the plan.

2. **Save plan changes** — update `implementation-plan.md` on disk.
   Note the finding IDs that motivated each plan correction.

If no findings were deferred, skip this section.

---

## Track Completion

After the review loop completes and any plan corrections are committed,
proceed directly to track completion **in the same session**.

1. **Compile the track episode** from all step episodes in the step file.
   The track episode is a strategic summary — what was built, key
   discoveries, plan deviations with cross-track impact. If findings were
   deferred to other tracks, mention the plan corrections and which
   tracks were affected.

2. **Present track results to the user** (do NOT write to plan file yet):
   - Track episode (compiled but not yet persisted)
   - All step episodes from the step file
   - Git log of track commits
   - Any unresolved track-level code review findings
   - Plan corrections made (if any) — which findings were deferred and
     where
   - Note that on approval the track description will be collapsed to its
     intro paragraph (see step 4) — the detailed implementation
     subsections are now superseded by the committed code and step
     episodes

3. **Wait for user response:**
   - **Approved** — proceed to step 4.
   - **Fixes needed** — apply the user's specific fixes as additional
     commits. Re-run track-level code review if fixes are substantial.
     Re-compile the track episode if fixes changed outcomes.
     Present updated results and wait again.
   - **Fundamental rework** — trigger ESCALATE (see workflow.md
     §Inline Replanning).

4. **Write the track episode, collapse the description, and mark `[x]`**
   in the plan file on disk (only after user approval):

   Apply the three-clause collapse rule from
   [`conventions-execution.md`](conventions-execution.md) §2.1
   "After track completion (user-approved)". Quick form: always keep
   the intro paragraph + `**Track episode:**` block + `**Step file:**`
   pointer; always drop `**Scope:**` and `**Depends on:**`; drop the
   four `**What/How/Constraints/Interactions**` subsections only if
   present — §2.1's three-clause form covers the conditional-drop
   behaviour across new-format, legacy, and mid-migration plan shapes.
   (The `**Strategy refresh:**` line belongs to §2.1's "Always keep"
   clause but is never yet on disk at Phase C collapse time; the next
   session's strategy refresh appends it.) The collapse does not touch
   `implementation-backlog.md` — if a backlog exists, Phase A already
   removed Track N's section at the start of this track; if not,
   there is nothing to touch.

   Final on-disk form:

   ```markdown
   - [x] Track N: <title>
     > <intro paragraph — first paragraph of the original description>
     >
     > **Track episode:**
     > <strategic summary — length proportional to cross-track impact>
     >
     > **Step file:** `tracks/track-N.md` (M steps, K failed)
   ```

   This shrinks completed-track entries from 100+ lines to ~10–15 lines
   and keeps the plan file lean as tracks land. The strategy-refresh
   line is appended by the next session.

5. **Session ends.** Strategy refresh happens next session.

**Why deferred write:** Writing the track episode and marking `[x]` before
user approval creates a state that cannot be reliably resumed — if the
session ends between marking `[x]` and receiving approval, the next session
detects the track as complete (State A: strategy refresh needed) and skips
user review entirely. By deferring the write, an interrupted session simply
re-enters track completion on resume (all phases `[x]` in the step file,
track still `[ ]` in the plan file).

**Why merge with code review:** Phase C's code review and track completion
have no perspective conflict — unlike Phase B→C (where implementation
context biases code review), here the reviewer mindset naturally feeds into
the track episode. More importantly, Phase C may produce plan corrections
(deferred findings → new or updated tracks), and the track episode must
accurately reflect these. A separate session would lose this context.
