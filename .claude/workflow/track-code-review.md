# Track Execution — Phase C: Code Review + Track Completion

Phase C is a **two-actor phase**, mirroring Phase B's split:

- The **orchestrator** (this document) drives the per-iteration loop:
  spawn the review fan-out sub-agents, synthesise findings, classify
  in-scope vs deferred, spawn the per-iteration **implementer** to
  apply in-scope fixes, dispatch on its return, run the gate-check
  fan-out, manage iteration counts, run the context check, apply
  plan corrections, compile the track episode, present results to
  the user, mark the track `[x]` upon approval.
- The **implementer** (a fresh sub-agent spawned per iteration that
  has fixes to apply, see [`implementer-rules.md`](implementer-rules.md))
  performs sub-steps 1–3 of fix application — read the cumulative
  track diff and the synthesised findings, apply the fixes, run
  tests + Spotless + the coverage gate, stage and commit a
  `Review fix:` commit. The implementer's output is the same
  structured handoff used at `level=step`, with `level=track`
  selecting the variant defined in
  [`implementer-rules.md`](implementer-rules.md) §Inputs and
  §Return contract.

After all steps are committed, review the full track diff using sub-agents.
These are deliberately sub-agents — fresh eyes catch systematic issues that
the main agent, having read every step episode and skimmed the cumulative
diff, can normalise away. Delegating fix application to a per-iteration
implementer keeps the cumulative review sub-agent fan-out, source-file
reads, Spotless and Maven traffic out of the orchestrator's tool-call
history — exactly the rationale that justified the Phase B split (PR
#1021); the same shape applies here.

## Tooling — PSI for cross-step reference accuracy

Track-level review's value is catching cross-step interaction issues —
one step adds a producer, another adds a consumer, and only the
cumulative diff shows whether they actually meet. Those are
reference-accuracy questions and route through mcp-steroid PSI
find-usages rather than grep when the IDE is reachable, per the rule
in [`conventions.md`](conventions.md) §1.4 *Tooling discipline* (run
`steroid_list_projects` once at session start; do not re-probe). The
canonical sub-agent context block below already embeds the PSI
instruction — keep it intact when customising. When mcp-steroid is
unreachable, sub-agents fall back to grep and add reference-accuracy
caveats to any finding that depends on a symbol search.

### Pre-PR semantic pass via the `inspect-and-fix` recipe

Phase C is the last gate before the cumulative track diff lands on
the draft PR. When mcp-steroid is reachable, intersect IntelliJ's
inspection set with the diff to surface semantic issues that Spotless
and the coverage gate can't catch — redundant casts, atomic-on-
volatile, suspicious `equals`/`hashCode`, format-string mismatches,
thread-unsafe statics. Use the **`inspect-and-fix`** recipe (see
[`conventions.md`](conventions.md) §1.4 *Recipes*); intersect the
findings with `git diff {base_commit}..HEAD --name-only` so the
report scopes to the cumulative track diff. **Report findings, never
auto-apply.** Roll any inspection findings into the synthesised
findings list above so they go through the same review-fix iteration
as the dimensional review's output.

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
2. Read the **implementation plan** (`docs/adr/<dir-name>/_workflow/implementation-plan.md`)
   — this provides strategic context (goals, architecture notes, decision
   records, track episodes from completed tracks).
3. Read the **step file** (`docs/adr/<dir-name>/_workflow/tracks/track-N.md`) — this
   provides the step descriptions and episodes.
4. **Generate the slim plan snapshot** at
   `/tmp/claude-code-plan-slim-$PPID.md` by running:

   ```bash
   python3 .claude/scripts/render-slim-plan.py \
       --plan-path docs/adr/<dir-name>/_workflow/implementation-plan.md
   ```

   The script implements the rule from
   [`plan-slim-rendering.md`](plan-slim-rendering.md); do not re-derive
   the transform inline. With no `--out` it writes
   `/tmp/claude-code-plan-slim-<ppid>.md` using its parent (the
   orchestrator) PID, matching the snapshot path convention.
   Sub-agents spawned for track-level review will read this snapshot
   by path — this keeps the main agent's tool-call history from
   accumulating a plan copy per sub-agent spawn. Regenerate the
   snapshot if plan corrections are applied during the review loop
   (before the next spawn batch).
5. Use `{base_commit}` when spawning all review sub-agents.
   All sub-agents review `git diff {base_commit}..HEAD`.

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
back to docs/adr/{dir-name}/_workflow/implementation-plan.md.

## Track Steps (tactical context)
Read the step file at:
  docs/adr/{dir-name}/_workflow/tracks/track-{N}.md
The file begins with a `## Description` section carrying the track's
original description — intro paragraph +
**What/How/Constraints/Interactions** subsections + any track-level
diagram — copied there at Phase A start. Below that, all steps for
this track appear with their episodes. Each step also carries a
`**Risk:**` line tagging it as `low`, `medium`, or `high` — treat
`medium` and `high` step ranges as **focal points** within the diff
(weight your attention toward those changes; the tag identifies where
tests + the workflow's own gating could not easily catch issues, so
this review carries more of the load there).

## Changed Files
{output of git diff {base_commit}..HEAD --name-only — passed inline,
small}

## Skip These Files (generated code)
- core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/*
- Any files under generated-sources/ or generated-test-sources/
- Generated Gremlin DSL classes

## Tooling
Use **mcp-steroid PSI find-usages, not grep**, for any
reference-accuracy question about a Java symbol in the cumulative
track diff or its callers (callers/overrides/usages of a method,
field, class, or annotation; cross-step interaction sites where one
step adds a producer and another adds a consumer; whether a renamed
symbol still has stale references elsewhere; whether a deleted member
is genuinely unused). Grep is acceptable for filename globs, unique
string literals, and orientation reads, but the load-bearing answer
behind a finding must be PSI-backed when mcp-steroid is reachable. If
the SessionStart hook reported `mcp-steroid: NOT reachable`, fall
back to grep and note the reference-accuracy caveat in any finding
that depends on a symbol search.

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

Iterate on the synthesized findings. Each iteration that has fixes to
apply spawns a **fresh per-iteration implementer** (`level=track`,
`mode=FIX_REVIEW_FINDINGS`) per §Implementer Spawns below; the
orchestrator never edits source files itself in Phase C.

1. **Classify findings.** From the synthesised list, separate
   in-scope findings (to apply now) from deferred findings (to push
   to other tracks via plan corrections — see §Plan Corrections from
   Deferred Findings below). The implementer receives only the
   in-scope subset.
2. **If any in-scope findings need fixes:**
   - **Spawn the per-iteration implementer** with `level=track`,
     `mode=FIX_REVIEW_FINDINGS`, `base_commit` (read from the step
     file's `## Base commit`), and the iteration's in-scope findings
     as `findings`. Use the prompt template in
     [`step-implementation.md`](step-implementation.md) §Implementer
     Prompt Template — the same template both phases share. See
     §Implementer Spawns below for the per-iteration variable inputs.
   - **Dispatch on the structured return:**

     ```
     match result.RESULT:
         SUCCESS                  -> on_iteration_success(result)
         DESIGN_DECISION_NEEDED   -> escalate_to_user_then_respawn(result)
         RISK_UPGRADE_REQUESTED   -> contract violation — surface to user
         FAILED                   -> handle_iteration_failure(result)
     ```

     The three normal handlers and the contract-violation path are
     spelled out in §Phase C Implementer Handlers below.
   - On `SUCCESS`: the implementer has already pushed a `Review fix:`
     commit and run tests + Spotless + coverage gate. The orchestrator
     does **not** re-run tests or re-stage files; it proceeds to the
     gate check.
   - **Update the Progress section** on disk to record the completed
     iteration (e.g., `- [ ] Track-level code review (1/3 iterations)`).
     Commit and push the Progress update as a Workflow update commit
     (per [`commit-conventions.md`](commit-conventions.md) § Commit
     type prefixes — "Workflow update" row).
   - Spawn **fresh sub-agents** to verify (gate check) — only re-run the
     review dimension(s) that had open findings. For example, if only
     crash-safety code findings and test-completeness findings remain,
     spawn only `review-crash-safety` and `review-test-completeness`.
     If findings span all dimensions, re-run all originally selected agents.
     The gate-check sub-agents review the new HEAD (after the
     `Review fix:` commit), which they reach via the same
     `git diff {base_commit}..HEAD` instruction.
   - **Context consumption check** (mandatory after each iteration,
     except the last): run
     `cat /tmp/claude-code-context-usage-$PPID.txt`. If the level is
     `warning` (≥30%) or `critical` (≥40%), do NOT start the next
     iteration. Save all work (update Progress section with current
     iteration count, commit) and ask the user for a session refresh
     (see workflow.md §Context Consumption Check). If the level is
     `safe`/`info`, continue to the next iteration. If the file does
     not exist or the command fails, this is **not an error** — treat
     as `safe` and continue.
3. Max 3 iterations **total across sessions** — on resume, read the
   iteration count from the Progress section to determine how many remain.
   The iteration count is shared across all review dimensions (not
   independent counters).
4. If blockers persist after 3 iterations, or if any iteration ended
   in a non-`SUCCESS` return that exited the loop, note the unfixed
   findings — they'll be presented to the user during track completion
   (below).
5. When all reviews pass (or max iterations reached), mark
   `Track-level code review` as `[x]` in the step file's Progress section.

---

## Implementer Spawns

Each Phase C implementer spawn uses `subagent_type: "general-purpose"`
and `model: "opus"`. (Track-level fix application operates on the
cumulative track diff and may surface cross-step interactions; the
risk tag, which would otherwise gate the model choice in Phase B, is
locked per-step at end-of-Phase-B and does not inform the
track-level model. Always spawn Opus.)

Use the **shared Implementer Prompt Template** in
[`step-implementation.md`](step-implementation.md) §Implementer Prompt
Template — the static prefix is identical across both levels. Phase C
populates the variable section as follows:

| Field | Value |
|---|---|
| `level` | `track` |
| `base_commit` | SHA from the step file's `## Base commit` section. |
| `mode` | `FIX_REVIEW_FINDINGS` (or `WITH_GUIDANCE` after a design-decision escalation per §Phase C Implementer Handlers below). |
| `step_index` / `step_description` / `risk_tag` | **Omit** — the level-conditional fields are not populated at `level=track`. |
| `findings` | The iteration's in-scope synthesised findings (only when `mode=FIX_REVIEW_FINDINGS`). |
| `Guidance` / `exploration_notes_echo` | The user's chosen alternative + the prior `exploration_notes` (only when `mode=WITH_GUIDANCE`). |

The implementer's contract — what it reads, what it commits, when it
must early-return, and what fields its return block carries — is the
same rulebook used at `level=step`. The contract differences for
track-level work (cumulative diff target, no `RISK_UPGRADE_REQUESTED`,
`FIX_NOTES` instead of `EPISODE_DRAFT`, no orchestrator-side
rollback on `FAILED`) are documented inline in
[`implementer-rules.md`](implementer-rules.md). The orchestrator does
not need to load that file; the implementer reads it on every spawn.

Each implementer's `Review fix:` commit is pushed by the implementer
itself (per the per-commit push rule in
[`commit-conventions.md`](commit-conventions.md) § Push every commit).
The Ephemeral identifier rule applies to durable content (source code,
tests, comments) but not to branch-only commit messages — the
implementer may cite finding IDs in its `Review fix:` commit subject
or body when it makes the log easier to follow.

---

## Phase C Implementer Handlers

The implementer's structured return drives one of three
orchestrator-side handlers (success / escalate / failure), plus a
fourth `RISK_UPGRADE_REQUESTED` contract-violation path that aborts
to the user rather than running a handler. None of the three
handlers roll back prior `Review fix:` commits — see
[`implementer-rules.md`](implementer-rules.md) §"Mode-specific scope
of the local revert" `level=track` row for the rationale (prior
iterations' fixes have already passed their gate check; a failed
iteration does not invalidate them).

### `on_iteration_success(result)`

The implementer's `Review fix:` commit is on disk and pushed;
`result.COMMIT` is its SHA. `result.FIX_NOTES` carries the
implementer's per-iteration notes (which findings were addressed,
which were skipped, what was discovered). Stash `FIX_NOTES` and
`CROSS_TRACK_HINTS` for inclusion in the eventual track episode (see
§Track Completion below). Proceed to the Progress update + gate-check
fan-out per the review loop above.

### `escalate_to_user_then_respawn(result)`

Triggered when `result.RESULT == DESIGN_DECISION_NEEDED`. The
implementer has run the snapshot-and-diff revert sequence per
[`implementer-rules.md`](implementer-rules.md) §Detection rules, so
the working tree is clean at HEAD (no commit was produced).
`result.DESIGN_DECISION` is populated.

Verify `git status` is clean before continuing — a dirty tree at
this point is a contract violation; surface the discrepancy to the
user instead of proceeding.

1. Present `result.DESIGN_DECISION` to the user via
   [`design-decision-escalation.md`](design-decision-escalation.md).
2. On user response, respawn the implementer with:
   - `level=track`
   - `mode=WITH_GUIDANCE`
   - `Guidance:` set to the user's chosen alternative + any
     additional direction
   - `exploration_notes_echo` set to
     `result.DESIGN_DECISION.exploration_notes`

   The original `findings:` list is **intentionally not** carried
   into the `WITH_GUIDANCE` respawn — `findings:` is populated only
   in `mode=FIX_REVIEW_FINDINGS` per the matrix in
   [`implementer-rules.md`](implementer-rules.md) §Inputs. The
   user's `Guidance:` is decisive about what to do with the
   surfaced design question; the implementer does not need the
   raw findings list to apply the chosen alternative. If the
   guidance leaves part of the original findings unaddressed, the
   gate-check fan-out re-surfaces them in the next iteration.
3. The respawn's result re-enters the dispatch above. No iteration
   count increment for the escalation respawn — the user-decided
   alternative continues the same iteration.

### `handle_iteration_failure(result)`

Triggered when `result.RESULT == FAILED`. The implementer has run
the snapshot-and-diff revert sequence per
[`implementer-rules.md`](implementer-rules.md) §Detection rules, so
the working tree is clean at HEAD; no `Review fix:` commit was
produced this iteration. `result.FAILURE` carries
`what_was_attempted`, `why_it_failed`, `impact_on_remaining_steps`
(at `level=track` this is "impact on remaining findings"), and
`recommended_action`.

Verify `git status` is clean before continuing — a dirty tree at
this point is a contract violation; surface the discrepancy to the
user instead of proceeding.

1. **Record the failure** — update the step file's Progress section
   to mark the track-level code review entry with the failure (e.g.,
   `- [ ] Track-level code review (FAILED at iteration N/3)`) and
   commit the Progress update as a Workflow update commit. Embed the
   `FAILURE` fields (`what_was_attempted`, `why_it_failed`,
   `impact_on_remaining_findings`, `recommended_action`) verbatim in
   the commit message body so the git history preserves the failure
   context for the draft PR — review findings are not persisted to a
   separate file (per [`track-review.md`](track-review.md) §Phase A,
   the durable trace is step-file edits plus the workflow-update
   commit).
2. **Exit the iteration loop.** Do not respawn the implementer for
   the same findings. The remaining findings are now "unfixed" and
   are presented to the user during track completion (the existing
   "If blockers persist after 3 iterations, note them" branch).
3. If `recommended_action: escalate`, present the situation to the
   user and consider entering ESCALATE per
   [`inline-replanning.md`](inline-replanning.md). For `retry`, the
   recommendation reduces to "no further automatic attempts at this
   set of findings"; the user decides whether to re-spawn with
   guidance or accept the unfixed state at track completion.
   `recommended_action: split` is **forbidden at `level=track`** per
   [`implementer-rules.md`](implementer-rules.md) §Fundamental
   failure — if surfaced, treat as a contract violation: present the
   return block verbatim to the user (do not respawn) alongside the
   same options ESCALATE / accept-as-unfixed.

### `RISK_UPGRADE_REQUESTED` (contract violation)

`RESULT: RISK_UPGRADE_REQUESTED` is **forbidden at `level=track`**
per [`implementer-rules.md`](implementer-rules.md) §"Risk upgrade
required (level=step only)". If a Phase C implementer returns this
value, treat it as a contract violation: surface the return block
verbatim to the user, do not respawn automatically, and let the
user decide whether to enter ESCALATE
([`inline-replanning.md`](inline-replanning.md)) or to proceed to
track completion with the iteration's findings unfixed.

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

2. **Save plan changes** — update `implementation-plan.md` (and
   `implementation-backlog.md` if a new track's
   `**What/How/Constraints/Interactions**` subsections need
   somewhere to live) on disk. Note the finding IDs that motivated
   each plan correction.

3. **Commit and push the plan corrections** as a separate Workflow
   update commit (per `commit-conventions.md` § Commit type
   prefixes — "Workflow update" row), distinct from the
   `Mark <track> complete` commit below:

   ```bash
   git add docs/adr/<dir-name>/_workflow/implementation-plan.md \
           docs/adr/<dir-name>/_workflow/implementation-backlog.md
   git commit -m "Apply plan corrections from <track> review"
   git push
   ```

   Stage explicit paths only. Drop `implementation-backlog.md` from
   the `git add` if no new track was added.

If no findings were deferred, skip this section.

---

## Track Completion

After the review loop completes and any plan corrections are committed,
proceed directly to track completion **in the same session**.

1. **Compile the track episode** from all step episodes in the step
   file plus any per-iteration `FIX_NOTES` and `CROSS_TRACK_HINTS`
   stashed by the review loop's `on_iteration_success` handler. The
   track episode is a strategic summary — what was built, key
   discoveries (including any surfaced during the review-fix
   iterations), plan deviations with cross-track impact, and which
   review-fix iterations applied non-trivial changes (cite the
   `Review fix:` commit subjects when they aid the strategic
   narrative; do not embed finding IDs in the durable text per the
   Ephemeral identifier rule). If findings were deferred to other
   tracks, mention the plan corrections and which tracks were
   affected. If any iteration ended in a non-`SUCCESS` return, name
   the unfixed findings and why they were not addressed.

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
   - **Fixes needed** — package the user's specific fixes as a
     synthesised findings list (each item: location, issue, proposed
     fix) and **spawn a fresh implementer** with `level=track`,
     `mode=FIX_REVIEW_FINDINGS`, and the user-provided findings.
     Use the same prompt template, validity matrix, and handler
     dispatch as §Implementer Spawns and §Phase C Implementer
     Handlers above. The implementer's `Review fix:` commit lands
     on top of HEAD; the orchestrator does not touch source files
     itself. Re-run track-level code review if the user's fixes are
     substantial enough that a gate-check run alone won't catch
     potential regressions. Re-compile the track episode if fixes
     changed outcomes. Present updated results and wait again.
   - **Fundamental rework** — trigger ESCALATE (see workflow.md
     §Inline Replanning).

4. **Write the track episode, collapse the description, and mark `[x]`**
   in the plan file on disk (only after user approval).

   The track episode is written **only after user approval**, and at
   the same time the description is **collapsed** to remove
   implementation detail that is now superseded by the committed code
   and step episodes.

   **Always keep** (regardless of plan shape): the **intro paragraph**
   (the first paragraph of the original description, before any
   `**What**:` / `**How**:` / `**Constraints**:` / `**Interactions**:`
   subsection), the `**Track episode:**` block (written at collapse
   time), the `**Step file:**` pointer, and the `**Strategy refresh:**`
   line if present — though that line is never yet on disk at Phase C
   collapse time; the next session's strategy refresh appends it (see
   [`strategy-refresh.md`](strategy-refresh.md)).

   **Always drop**: the `**Scope:**` line and the `**Depends on:**`
   line.

   Pending-track entries in the plan are written in the thin form
   during Phase 1, so there are no
   `**What**: / **How**: / **Constraints**: / **Interactions**:`
   subsections present in the plan-file entry to drop — the detailed
   description was removed from the backlog at Phase A start and
   already lives in the step file's `## Description` section. Phase C
   does not touch the backlog.

   **Track episode fields:**
   - Strategic summary covering: what was built, key discoveries, plan
     deviations with cross-track impact. Length is proportional to
     cross-track impact — a routine track may need only a couple of
     sentences, while a track with architectural surprises should
     include enough detail for the next session's strategy refresh to
     assess downstream impact without reading the step file.
   - Reference to the step file with step count and failure count.
   - This is what future track sessions read from the plan file — the
     step file is available for deeper investigation if needed.

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

   This shrinks completed-track entries from 100+ lines to ~10–15
   lines and keeps the plan file lean as tracks land. The
   strategy-refresh line is appended by the next session.

   **Why collapse:** Completed tracks accumulate in the plan file and
   are re-sent to every sub-agent as strategic context. Keeping the
   full implementation detail for completed tracks inflates every
   code-review sub-agent prompt by tens of thousands of tokens. The
   intro paragraph plus track episode is sufficient strategic context
   for reviewers of later tracks. For how sub-agents render the plan,
   see [`plan-slim-rendering.md`](plan-slim-rendering.md).

5. **Commit and push the track-completion changes** as a single
   Workflow update commit. The plan-file edit (track episode + `[x]`
   + collapsed description) and any final step-file Progress updates
   from Phase C land together so the draft PR shows a clean track
   boundary:

   ```bash
   git add docs/adr/<dir-name>/_workflow/implementation-plan.md \
           docs/adr/<dir-name>/_workflow/tracks/track-<N>.md
   git commit -m "Mark <track> complete"
   git push
   ```

   This commit is registered as scaffolding in the resume orphan
   detection (see
   [`step-implementation-recovery.md`](step-implementation-recovery.md)
   §Resume-side commit-pattern reference — entry 5, "Other Workflow
   update commits"). It does **not** contribute to any `[x]` step's
   expected commit set.

6. **Run self-improvement reflection.** Load
   `.claude/workflow/self-improvement-reflection.md` on-demand and
   follow it. Phase C friction worth recording typically lives in
   the track-level review-iteration loop, the dimensional review
   agent selection, the deferred-finding → plan-correction handoff,
   the implementer-driven review-fix application, or the track
   episode template. If the user approves any proposed issues,
   write the chosen `workflow-issues/*.md` files, commit + push
   per the protocol §Commit format, then proceed to Step 7.
7. **Session ends.** Strategy refresh happens next session.

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
