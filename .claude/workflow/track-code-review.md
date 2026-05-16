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
in parallel. Baseline agents (4) run unless the diff is workflow-only or
`docs-only`+workflow (see the baseline-skip override in
`review-agent-selection.md`); conditional agents and workflow-review
agents are added based on the track description and changed files across
the full diff. See [`code-review-protocol.md`](code-review-protocol.md)
for the two-tier protocol overview and
[`review-iteration.md`](review-iteration.md) for iteration limits,
finding ID prefixes, and gate format.

All selected reviews run against the same diff
(`git diff {base_commit}..HEAD`) and produce independent findings. Launching
them in parallel saves wall-clock time since they examine different aspects
of the changes.

---

## Phase C Startup

1. Read the step file's `## Base commit` section to get the SHA recorded
   at the start of Phase B.
2. **Verify the recorded base is reachable from HEAD.** A rebase
   between Phase B and Phase C rewrites every on-branch commit, so the
   SHA recorded at Phase B startup still resolves (the old object stays
   in the reflog) but is no longer an ancestor of HEAD. `git diff
   <stale-base>..HEAD` then returns commits from earlier tracks —
   either inflating the review diff by orders of magnitude or, after a
   subtle rebase, silently shifting it by a handful of files.

   Run the ancestor check, and on stale, recompute the actual on-branch
   parent from the `Record Phase B base commit for` commit:

   ```bash
   ACTUAL_BASE="$BASE_SHA"
   if ! git merge-base --is-ancestor "$ACTUAL_BASE" HEAD; then
       # Stale — typically a post-Phase-B rebase.
       RECORDING=$(git log -F \
           --grep="Record Phase B base commit for <track>" \
           --format=%H HEAD | head -n 1)
       if [ -z "$RECORDING" ]; then
           # No recording commit on HEAD's path — surface to user;
           # do not invent a base.
           exit 1
       fi
       ACTUAL_BASE=$(git log -1 --format=%P "$RECORDING")
   fi
   # Use $ACTUAL_BASE as {base_commit} for the rest of Phase C.
   ```

   Scope `git log` to `HEAD` (not `--all`) and match the track title
   literally with `-F` (fixed-strings) so titles containing regex
   metacharacters (`.`, `(`, `[`, `*`, …) cannot mis-match and so
   reflog orphans and any other tracks' recording commits are ignored.
   If `git log --grep` returns nothing (no recording commit on HEAD's
   path for this track), the base SHA was never written through the
   workflow path — surface the discrepancy to the user before
   proceeding; do not invent a base.

   On stale, **append** a discrepancy note to the step file's `## Base
   commit` section (do not overwrite the original SHA — keep both for
   the audit trail):

   ```
   Note: recorded base <stale-sha> was stale (likely from a
   post-Phase-B rebase); using actual on-branch parent <actual-sha>
   for this Phase C.
   ```

   Commit the step-file edit as a Workflow update commit before
   continuing (per
   [`commit-conventions.md`](commit-conventions.md) § Commit type
   prefixes — "Workflow update" row) so the draft PR records the
   recompute. Use `$ACTUAL_BASE` as `{base_commit}` for every
   subsequent step in Phase C (review sub-agent spawns, the diff
   staging at step 7, the implementer's `base_commit` field in
   §Implementer Spawns).
3. Read the **implementation plan** (`docs/adr/<dir-name>/_workflow/implementation-plan.md`)
   — this provides strategic context (goals, architecture notes, decision
   records, track episodes from completed tracks).
4. Read the **step file** (`docs/adr/<dir-name>/_workflow/plan/track-N.md`) — this
   provides the step descriptions and episodes.
5. **Generate the slim plan snapshot** at
   `/tmp/claude-code-plan-slim-$PPID.md` by running:

   ```bash
   python3 .claude/scripts/render-slim-plan.py \
       --plan-path docs/adr/<dir-name>/_workflow/implementation-plan.md \
       --out /tmp/claude-code-plan-slim-$PPID.md
   ```

   The script implements the rule from
   [`plan-slim-rendering.md`](plan-slim-rendering.md); do not re-derive
   the transform inline. **Always pass `--out` explicitly** with
   `$PPID` — the orchestrator's PID inside its bash shell. The script
   does not auto-derive this path because, when invoked via the Bash
   tool, the script's parent is the bash shell, not the orchestrator,
   so any default would silently drift to the wrong PID and sub-agents
   would see a missing snapshot. Sub-agents spawned for track-level
   review will read this snapshot by path — this keeps the main
   agent's tool-call history from accumulating a plan copy per
   sub-agent spawn. Regenerate the snapshot if plan corrections are
   applied during the review loop (before the next spawn batch).
6. Use `{base_commit}` when spawning all review sub-agents.
   All sub-agents review `git diff {base_commit}..HEAD`.
7. **Pre-stage the cumulative diff and changed-files list.** Always
   write both to per-track temp files so the canonical context block
   references paths instead of inlining content. Staging the diff
   matches the agents' `## Input` contract (every spec says "a path
   to a temp file containing the full diff", read via the `Read`
   tool); staging the changed-files list is an orchestrator-context
   optimisation across the multi-agent fan-out (see § Sub-agents →
   § "Pre-staged diff and changed-files list" for the multi-spawn
   rationale). Run:

   ```bash
   git diff {base_commit}..HEAD \
       > /tmp/claude-code-track-{N}-diff-$PPID.patch
   git diff {base_commit}..HEAD --name-only \
       > /tmp/claude-code-track-{N}-files-$PPID.txt
   ```

   See § Sub-agents → § "Pre-staged diff and changed-files list"
   below for the rationale, the path convention, and the
   regeneration rule (the staged files become stale after every
   `Review fix:` commit).

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
track episode + the on-disk `**Strategy refresh:**` line only; the
current track and other not-started tracks are shown in full. If the
snapshot is missing, fall back to
docs/adr/{dir-name}/_workflow/implementation-plan.md.

## Track Steps (tactical context)
Read the step file at:
  docs/adr/{dir-name}/_workflow/plan/track-{N}.md
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
The changed-files list is at:
  /tmp/claude-code-track-{N}-files-{PPID}.txt
Read it with the `Read` tool (the file is written at Phase C Startup
step 7 and regenerated after every `Review fix:` commit).

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
The full cumulative track diff is at:
  /tmp/claude-code-track-{N}-diff-{PPID}.patch
Read it with the `Read` tool before forming findings. For diffs over
2000 lines, page through with the `offset` and `limit` parameters.
The file is written at Phase C Startup step 7 and regenerated after
every `Review fix:` commit. See § "Pre-staged diff and changed-files
list" below for staging mechanics and regeneration rules.
```

### Pre-staged diff and changed-files list

The cumulative track diff and the changed-files list are pre-staged
at Phase C Startup step 7, and the canonical context block always
references the staged paths instead of inlining content. Staging the
diff matches the `## Input` contract every dimensional review agent
declares ("a path to a temp file containing the full diff", read via
the `Read` tool) and matches how the `/code-review` skill dispatches
the same agents from the standalone entry point. Staging the
changed-files list is an orchestrator-context optimisation — see
"Why" below.

**Why.** A track-level review fan-out is up to six dimensional
reviewers × three iterations = eighteen sub-agent spawns, all
targeting the same cumulative diff. Inlining a 12K-line diff into
every spawn accumulates ~230K lines of redundant diff content in the
orchestrator's tool-call history, which the prompt cache reads on
every subsequent tool call. Pre-staging puts the diff in the
sub-agent's context only — the orchestrator's tool-call history
carries the file path, not the bytes. Always staging (rather than
only above a size threshold) also removes a contract mismatch with
the agent `## Input` spec, which expects a file path uniformly.

**Path convention.** Use `/tmp/claude-code-track-{N}-diff-$PPID.patch`
and `/tmp/claude-code-track-{N}-files-$PPID.txt`, where `$PPID` is the
orchestrator's PID through its bash shell (the same `$PPID` suffix
convention used by the plan-slim snapshot — see
[`plan-slim-rendering.md`](plan-slim-rendering.md); the `{N}` segment
additionally scopes the file to the current track so resume
diagnostics that list multiple tracks' tmp files stay unambiguous).
The unique-suffix requirement from project-level `CLAUDE.md`
§ Concurrent Agent File Isolation applies: never use bare names like
`/tmp/track-diff.patch`.

**Regenerate before every fan-out after a `Review fix:` commit.**
Each iteration's `Review fix:` commit grows the cumulative diff, so
any previously staged files go stale. Re-run the staging commands
from Phase C Startup step 7 before composing the next fan-out's
sub-agent prompts — this covers both the in-loop gate-check fan-out
(§ Iteration loop) and the post-approval re-review fan-out triggered
by user-requested fixes during § Track Completion (step 3, **Review
mode** path — `FIX_FINDING` items). Within a single iteration, the dimensional reviewers
can share the same staged paths (they all review the same HEAD). If
a `RESULT_MISSING` or `FAILED` iteration leaves HEAD unchanged, the
previously staged files are still current — skip the re-run.

**Why paths, not inline contents.** Inlining the plan, track file,
diff, and changed-files list into every track-level sub-agent spawn
embeds copies in the main agent's tool-call history — up to ~10 agents
× 3 iterations per track. Paths keep the main agent lean; sub-agents
Read the files themselves so the contents land only in sub-agent
context.

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

After all selected sub-agents complete, produce a unified findings list
by running the canonical procedure in
[`finding-synthesis-recipe.md`](finding-synthesis-recipe.md). The recipe
covers:

1. **Deduplication** by pivot order (`file:line` → issue shape →
   suggested fix shape → severity tie-break), with a worked example
   showing a 5-way cross-dimension merge.
2. **Severity assignment** against the standard `blocker` /
   `should-fix` / `suggestion` scale defined in
   [`review-iteration.md`](review-iteration.md) §Severity levels,
   including the rules for downgrading or upgrading singletons whose
   stated impact does not match the agent's assigned severity.
3. **Bucketing** into in-scope this iteration, deferred to next
   iteration, and plan-correction / out-of-track.
4. **Pre-spawn budget** (target 8–12 findings, ≤ 6–8 files per
   iteration — soft pacing under the ~15 / ~10 ceiling enforced in
   §Review loop step 2 below).
5. **Output format** for the synthesised list, including the
   per-finding shape the implementer's `findings:` block consumes.

The recipe also covers gate-check synthesis (mapping `VERIFIED` /
`REJECTED` / `MOOT` / `STILL OPEN` / `REGRESSION` verdicts and folding
any `New findings` into the next iteration's input) — that is the same
procedure routed from [`review-iteration.md`](review-iteration.md)
§Gate-check synthesis routing.

Present the synthesised findings list to proceed to the review loop.

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
2. **Pre-spawn budget check.** If the in-scope subset has ≥ ~15
   findings or spans ≥ ~10 distinct source files, split the work
   across multiple iterations rather than one mega-iteration. Pick
   the highest-severity subset that fits the budget (typical safe
   shape: 8–12 findings touching ≤ 6–8 files, leaving room for
   targeted re-runs of the touched test classes per
   [`implementer-rules.md`](implementer-rules.md) §Pacing
   long-running tasks → "Prefer targeted `-Dtest=…` re-runs"). The
   remaining findings re-surface in iteration 2's gate-check
   fan-out and consume an iteration counter normally — they are not
   lost. This is a soft-pacing rule, not a hard split: when the
   findings are genuinely independent and the diff is small (e.g.,
   20 identical-pattern Javadoc fixes touching 10 files), one large
   iteration is still fine. The Track-18 incident (2026-05-07) — a
   22-finding × 14-file spawn that ran an Opus implementer out of
   message budget mid-iteration — motivated this rule. **The
   thresholds (~15 findings / ~10 files) are heuristics, not
   contract gates** — when the finding-set is borderline, prefer
   splitting; when an iteration count is already tight (2 of 3 used)
   and the remaining findings genuinely cohere, accept the larger
   spawn and document the choice.
3. **If any in-scope findings need fixes:**
   - **Spawn the per-iteration implementer** with `level=track`,
     `mode=FIX_REVIEW_FINDINGS`, `base_commit` (read from the step
     file's `## Base commit`), and the iteration's in-scope findings
     as `findings`. Use the prompt template in
     [`step-implementation.md`](step-implementation.md) §Implementer
     Prompt Template — the same template both phases share. See
     §Implementer Spawns below for the per-iteration variable inputs.
   - **Dispatch on the structured return:**

     ```
     match result:
         result.RESULT == SUCCESS                  -> on_iteration_success(result)
         result.RESULT == DESIGN_DECISION_NEEDED   -> escalate_to_user_then_respawn(result)
         result.RESULT == RISK_UPGRADE_REQUESTED   -> contract violation — surface to user
         result.RESULT == FAILED                   -> handle_iteration_failure(result)
         <no parsable RESULT block>                -> handle_result_missing(result_text)
     ```

     The three normal handlers and the two contract-violation paths
     are spelled out in §Phase C Implementer Handlers below. The
     `RESULT_MISSING` path covers implementers that exited mid-flight
     without emitting the contract block — typically due to
     message-budget exhaustion or a tool-call crash; per
     [`implementer-rules.md`](implementer-rules.md) §Return contract,
     silent exit is forbidden, but the orchestrator must still be
     able to recover when it happens.
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
     `git diff {base_commit}..HEAD` instruction. **Re-run the
     staging logic from Phase C Startup step 7 before composing the
     gate-check prompts** — the new `Review fix:` commit grew the
     cumulative diff, so the previously staged files are stale (see
     § Sub-agents → § "Pre-staged diff and changed-files list").
   - **Use the compact gate-check prompt template, not the dimensional
     review prompt.** Spawn each re-checked agent with the prompt at
     [`prompts/dimensional-review-gate-check.md`](prompts/dimensional-review-gate-check.md),
     substituting:
     - `{dimension}` — the agent's review type (e.g., `Bugs & Concurrency`, `Test behavior`)
     - `{findings_under_recheck}` — open finding IDs and titles for that dimension, copied verbatim from the synthesised list
     - `{diff_path}`, `{files_path}` — the re-staged temp files from Phase C Startup step 7
     - `{plan_slim_path}` — `/tmp/claude-code-plan-slim-$PPID.md`
     - `{step_file_path}` — `docs/adr/<dir-name>/_workflow/plan/track-{N}.md`

     The template enforces the ≤ 60-line budget, the forbidden-section
     list, and the verdict-only output format. See
     [`review-iteration.md`](review-iteration.md) §"Dimensional-review
     gate-check budget" for the YTDB-696 rationale, the verdict-handling
     rules (VERIFIED / REJECTED / MOOT / STILL OPEN / REGRESSION), and
     the §Gate-check synthesis routing. Re-using the full
     dimensional review prompt at gate-check time burns roughly three
     times the budget for no extra signal and is the load-bearing
     cause of mid-Phase-C session pauses.
   - **After collecting all gate-check returns, run them through
     §Synthesis** before composing the next iteration's implementer
     input (per [`review-iteration.md`](review-iteration.md)
     §Gate-check synthesis routing). Treat
     `REGRESSION` verdicts as blocker-severity carry-forwards with
     `revert-or-repair` guidance; treat `REJECTED` and `MOOT`
     verdicts as cleared (identical to `VERIFIED`); carry
     `STILL OPEN` verdicts forward verbatim with the original
     finding ID.
   - **Context consumption check** (mandatory after each iteration,
     except the last): run
     `cat /tmp/claude-code-context-usage-$PPID.txt`. If the level is
     `warning` (≥30%) or `critical` (≥40%), do NOT start the next
     iteration. Save all work (update Progress section with current
     iteration count, commit) and ask the user for a session refresh
     (see workflow.md §Context Consumption Check). If the pause leaves
     Phase C mid-flight (for example, gate-checks already PASSed but
     track-completion approval is still pending, or iteration N has
     committed `Review fix:` but iteration N+1's gate-check sub-agents
     have not run), write a handoff file per
     [`mid-phase-handoff.md`](mid-phase-handoff.md). The handoff
     captures the iteration count, the gate-check outcomes, and the
     verbatim track-completion summary to re-present, so the next
     session does not re-spawn reviewer / gate-check sub-agents whose
     output is already on disk. If the level is `safe`/`info`,
     continue to the next iteration. If the file does not exist or
     the command fails, this is **not an error** — treat as `safe`
     and continue.
4. Max 3 iterations **total across sessions** — on resume, read the
   iteration count from the Progress section to determine how many remain.
   The iteration count is shared across all review dimensions (not
   independent counters). Iterations short-circuited via the
   pre-spawn budget split in step 2 each consume one counter — a
   24-finding set split into a 12+12 sequence consumes 2 of 3
   iterations on the in-scope findings alone, leaving only one
   iteration for any gate-check carry-over from **either** chunk.
   This is tight: if iteration 1's gate-check surfaces new fixable
   findings and iteration 2 is already full at 12 carry-overs, the
   single remaining iteration must absorb gate-check carry from
   both prior iterations and may exhaust the budget. When the
   pre-spawn finding-set is large enough that a 2-chunk split is
   forced, expect blockers-persist exit at the end and surface the
   residual findings at track completion rather than treating the
   third iteration as a guaranteed cleanup slot.
5. If blockers persist after 3 iterations, or if any iteration ended
   in a non-`SUCCESS` return that exited the loop, note the unfixed
   findings — they'll be presented to the user during track completion
   (below).
6. When all reviews pass (or max iterations reached), mark
   `Track-level code review` as `[x]` in the step file's Progress section.

---

## Implementer Spawns

Each Phase C implementer spawn uses `subagent_type: "general-purpose"`
and `model: "opus"` — the same as every Phase B implementer spawn (see
[`risk-tagging.md`](risk-tagging.md) §"Risk levels — quick reference"
for the rationale: Sonnet's reliability on multi-step implementation
work is below the threshold this workflow requires, and the model is
not allocated by risk tag).

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
to the user rather than running a handler, plus a fifth
`RESULT_MISSING` recovery path for the case where the implementer
exited mid-flight without emitting the contract block at all. None
of the five paths roll back prior `Review fix:` commits — see
[`implementer-rules.md`](implementer-rules.md) §"Mode-specific scope
of the local revert" `level=track` row for the rationale (prior
iterations' fixes have already passed their gate check; a failed
iteration does not invalidate them).

### `on_iteration_success(result)`

The implementer's `Review fix:` commit is on disk and pushed to
`origin` per [`implementer-rules.md`](implementer-rules.md) §Return
contract; `result.COMMIT` is its SHA. `result.FIX_NOTES` carries the
implementer's per-iteration notes (which findings were addressed,
which were skipped, what was discovered).

**Defensive push check.** Before stashing notes and proceeding,
assert that the `Review fix:` commit (`result.COMMIT`) is on
`origin` per [`defensive-push-check.md`](defensive-push-check.md).
The check short-circuits when no upstream is set, asserts ancestry
against `@{u}`, and auto-recovers via `git push` if the implementer
skipped the push.

Stash `FIX_NOTES` and `CROSS_TRACK_HINTS` for inclusion in the
eventual track episode (see §Track Completion below). Proceed to the
Progress update + gate-check fan-out per the review loop above.

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

Triggered when `result.RESULT == FAILED`. `result.FAILURE` carries
`what_was_attempted`, `why_it_failed`, `impact_on_remaining_steps`
(at `level=track` this is "impact on remaining findings"),
`recommended_action`, and `failure_class`.

**Pre-step: push-only short-circuit.** If
`result.FAILURE.failure_class == push_only`, the `Review fix:`
commit content is fine — content work succeeded, the commit landed
at HEAD, and only `git push` failed (see
[`implementer-rules.md`](implementer-rules.md) §Return contract).
The clean-tree-at-HEAD assumption below does **not** apply; the
`Review fix:` commit is at HEAD by design.

Skip the failure-recording flow entirely. Instead:

1. Route the push failure per
   [`commit-conventions.md`](commit-conventions.md) § Push failure
   handling — `non-fast-forward` triggers
   [`branch-divergence-check.md`](branch-divergence-check.md) (gated
   to first per-session rejection); other shapes record-and-continue.
2. After the gate (or record-and-continue), run `git push` from the
   orchestrator to publish the existing `Review fix:` commit.
3. If the orchestrator's push succeeds, `result.COMMIT` is now on
   `origin`. Synthesise the success path: route to
   `on_iteration_success(result)` above so notes are stashed and the
   gate-check fan-out runs on the now-pushed commit.
4. If the orchestrator's push still fails after the gate, escalate
   to the user with the `Review fix:` SHA and the `git push` stderr.
   Do **not** fall through to the numbered steps below; the
   `Review fix:` commit at HEAD is good work that should not be
   recorded as a content failure.

The numbered steps below apply only to the default
`failure_class: content` path. In that path the implementer has run
the snapshot-and-diff revert sequence per
[`implementer-rules.md`](implementer-rules.md) §Detection rules, so
the working tree is clean at HEAD and no `Review fix:` commit was
produced this iteration.

Verify `git status` is clean before continuing. A dirty tree at this
point is a contract violation, but per
[`implementer-rules.md`](implementer-rules.md) §Return contract this
violation is **expected** when the implementer hit a budget-pressure
exit and prioritised emitting `RESULT: FAILED` over completing the
revert sequence. **Route a dirty tree at FAILED through the same
recovery flow as `RESULT_MISSING`**: kill background tasks, inspect
the tree, and present the user with commit-as-is / re-spawn /
discard (see §`handle_result_missing` below for the full procedure).
The implementer's `FILES_TOUCHED` and `FAILURE.why_it_failed` are
authoritative inputs to the user's choice — do not discard them. If
`git status` is clean, proceed with the steps below.

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

### `handle_result_missing(result_text)` (contract violation, recovery required)

The implementer's return text contains **no parsable `RESULT:` block**
(or the block is truncated mid-field). This is a contract violation
per [`implementer-rules.md`](implementer-rules.md) §Return contract —
the implementer is required to emit a RESULT block before any exit,
including context/budget exhaustion. The most common causes are:

- the implementer ran out of message budget mid-iteration (the
  Track-18 incident, 2026-05-07: ~213k cache_read peak from
  foreground Maven runs dumping stack traces, implementer truncated
  while applying the 22nd of 22 findings and never reached its own
  return block);
- a runtime crash or timeout in a tool call (Bash 600 s timeout
  fired in foreground, Agent runtime dropped a wake-up
  notification, MCP server became unresponsive mid-call);
- the implementer left a runaway poll loop or background task alive
  that the system terminated externally.

The orchestrator cannot dispatch on missing data and the
implementer's last actions are by definition uncommitted (a
successful commit always emits `RESULT: SUCCESS`). Recovery is
manual and requires user approval — do not auto-respawn.

1. **Kill any background tasks the implementer may have left
   alive.** Run

   ```bash
   ps -o pid,ppid,cmd -e \
     | grep -E 'mvnw|surefire|java.*test|pgrep -f' \
     | grep -v ' grep '
   ```

   and terminate orphaned PIDs with `kill -TERM <pid>`. Wait a few
   seconds for graceful cleanup (releasing file locks, flushing logs),
   then escalate to `kill -KILL <pid>` only if they survive. Two patterns are
   particularly common after a `RESULT_MISSING` exit:
   - a defunct `[java]` zombie from an interrupted Maven fork
     (still listed but consuming no CPU; reaped by killing its
     parent);
   - a runaway `pgrep -f "surefire"` poll loop whose own shell
     command line matches the search pattern, so the loop never
     exits — see [`implementer-rules.md`](implementer-rules.md)
     §Pacing long-running tasks → forbidden self-referential
     pgrep. Untouched, this loop runs forever and consumes a CPU.

2. **Inspect the tree.** Run `git status --short` and
   `git diff --stat`. Three states are possible:

   - **Clean tree** — implementer reverted before exiting (or
     never began applying fixes). Skip to step 3 with the **discard**
     option pre-selected; the iteration is effectively a no-op.
   - **Dirty tree, edits look correct on inspection** — the
     implementer applied fixes but never committed (the most
     common Track-18 shape). The **commit-as-is** option is
     viable if the orchestrator can verify the edits independently.
   - **Dirty tree, edits look incomplete or wrong** — the
     implementer was mid-edit when its budget ran out. Recovery
     is most likely **re-spawn from a clean state**.

3. **Present the situation to the user with three options.**
   Include the inspection output (a `git status --short` excerpt
   and the implementer's last visible message, truncated to a few
   hundred characters) so the user can choose informedly.

   - **Re-spawn finalizer.** Clean the tree on the implementer's
     behalf (run the snapshot-and-diff revert sequence per
     [`implementer-rules.md`](implementer-rules.md) §Detection
     rules — the implementer never reached its own revert path,
     so the orchestrator owns the cleanup this once), then
     re-spawn a fresh implementer with the original findings. **If
     the original findings list was large** (≥ ~15 items or ≥ ~10
     files, the same heuristic as §Review loop step 2's pre-spawn
     budget check), halve the in-scope subset on the respawn so
     the new spawn doesn't repeat the budget exhaustion. The
     halved-off findings re-surface in the next iteration's
     gate-check fan-out.
   - **Commit-as-is.** Only when the dirty tree's edits look
     complete and correct on inspection, and when the orchestrator
     is in a position to verify them itself. The orchestrator
     stages explicit paths (no `git add -A`), runs Spotless +
     targeted tests of the touched test classes (per
     [`implementer-rules.md`](implementer-rules.md) §Pacing
     long-running tasks → "Prefer targeted `-Dtest=…` re-runs")
     + the coverage gate, commits as `Review fix: <subject>` per
     [`commit-conventions.md`](commit-conventions.md), and
     proceeds to the gate-check fan-out as if the iteration had
     returned `SUCCESS`. **Apply the test-additive carve-out** from
     [`implementer-rules.md`](implementer-rules.md) §Pacing
     long-running tasks → "Test-additive spawns skip the
     coverage-profile build entirely": when
     `git diff origin/develop -- '**/src/main/**'` is empty for the
     cumulative branch diff, record the gate as `n/a (test-additive)`
     and skip the coverage profile build — running it on a test-only
     iteration is wasted budget. **If we reached this handler via
     the dirty-tree-at-FAILED redirect from `handle_iteration_failure`
     above** (i.e., `result.RESULT == FAILED` with a populated
     `result.FAILURE` block, not a true `RESULT_MISSING` where no
     RESULT block was parsed), fold the implementer's
     `FAILURE.what_was_attempted` / `FAILURE.why_it_failed` /
     `FAILURE.recommended_action` fields into the `Review fix:`
     commit message body verbatim so the git history preserves the
     budget-pressure context — the implementer's own pre-exit
     diagnosis is the most authoritative record of what happened
     and must not be discarded just because the iteration was
     finalised by the orchestrator. For a true `RESULT_MISSING`
     entry the FAILURE block doesn't exist; record what was visible
     (the implementer's last visible message excerpt from step 3)
     in the commit message body instead. **This is the only Phase C case
     where the orchestrator commits source-file changes directly** —
     note the deviation in the eventual track episode so the audit
     trail is preserved.
   - **Discard.** Run the snapshot-and-diff revert on the
     implementer's behalf and treat the iteration as `FAILED`
     with `recommended_action: escalate`. The remaining findings
     are surfaced at track completion via the existing "blockers
     persist after N iterations" branch.

4. **Iteration counter accounting.** A `RESULT_MISSING` recovery
   consumes one iteration counter regardless of which option the
   user picks — the implementer was spawned, ran, and produced a
   commit's worth of state on disk; that has the same impact on
   the budget as a `FAILED` return. Update the Progress section
   with the new counter and a note (e.g.,
   `- [ ] Track-level code review (1/3 iterations, iteration 1
   recovered from RESULT_MISSING via commit-as-is)`) and commit it
   as a Workflow update commit before the next action.

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

2. **Save plan changes** — update `implementation-plan.md` and, if a
   new track was added or an existing track's
   `**What/How/Constraints/Interactions**` subsections need
   somewhere to live, the corresponding `tracks/track-<M>.md` step
   file. Note the finding IDs that motivated each plan correction.

3. **Commit and push the plan corrections** as a separate Workflow
   update commit (per `commit-conventions.md` § Commit type
   prefixes — "Workflow update" row), distinct from the
   `Mark <track> complete` commit below:

   ```bash
   git add docs/adr/<dir-name>/_workflow/implementation-plan.md \
           docs/adr/<dir-name>/_workflow/plan/track-<M>.md \
           ... (one path per modified or newly created step file)
   git commit -m "Apply plan corrections from <track> review"
   git push
   ```

   Stage explicit paths only. Drop step-file paths from the `git add`
   if no step file was modified or created.

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

   **This step is the single re-compile entry point.** It runs on
   initial Completion entry, on post-Apply re-render after a
   FIX_FINDING round, and on State C session re-entry (per
   `workflow.md` § Startup Protocol State C sub-states row "All
   steps `[x]`, code review `[x]`, track still `[ ]` in plan").
   In all three cases, re-read `git diff {base_commit}..HEAD`
   against current HEAD before compiling, so the episode reflects
   any `Review fix:` commits a prior-session implementer may have
   landed before the orchestrator could re-render. This subsumes
   the mid-Apply-crash + session-restart case (see
   [`review-mode.md`](review-mode.md) § State and resume).

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

3. **Wait for user response.** Use `AskUserQuestion` with three
   one-step options per the approval-panel contract in
   [`review-mode.md`](review-mode.md) § Approval-panel contract:

   - **Approve** — proceed to step 4 with whatever fix-finding
     work the review-mode loop has accumulated (none on the first
     render, or one or more `Review fix:` commits on HEAD if
     earlier rounds applied `FIX_FINDING` items).
   - **Review mode** — enter the conversational refinement loop
     per [`review-mode.md`](review-mode.md) § Flow.
   - **ESCALATE** — trigger inline replanning per
     [`inline-replanning.md`](inline-replanning.md).

   **Review mode side effects.** The user drops observations
   across as many chat turns as they want; the orchestrator
   silently classifies and accumulates them. When the user
   signals completion, one approval panel surfaces the
   accumulated set. On Apply, `FIX_FINDING` items are collected
   into a synthesised findings list (each item: location, issue,
   proposed fix) and a fresh implementer is spawned with
   `level=track`, `mode=FIX_REVIEW_FINDINGS`, using the same prompt
   template and validity matrix as §Implementer Spawns above. The
   implementer's `Review fix:` commit lands on top of HEAD; the
   orchestrator does not touch source files itself. `QUESTION`
   items are answered inline in chat as they came in (no Apply
   side effect). `EDIT_PLAN` / `EDIT_STEP_DESC` / `SKIP_TRACK` /
   `CLARIFY` are not available on Completion (see
   [`review-mode.md`](review-mode.md) § Action types).

   **Completion outcome mapping.** For what each implementer
   return status (`SUCCESS` / `FAILED` / `DESIGN_DECISION` /
   `RESULT_MISSING`) means at Completion — including the
   re-compile, the `**Review-mode fix attempt failed:**` surfacing,
   the design-decision escalation flow, and the
   `commit-as-is / re-spawn / discard` sub-panel for
   `RESULT_MISSING` — see
   [`review-mode.md`](review-mode.md) § Completion FIX_FINDING
   outcome mapping. Completion FIX_FINDING does **not** reuse
   §Phase C Implementer Handlers above; the spec lives at the
   review-mode callsite because the four outcomes feed the
   review-mode three-option re-render, not the iteration loop.

   The three-option panel re-renders after every review-mode Apply
   until the user picks **Approve** or **ESCALATE**.

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
   collapse time; the next session's Track Pre-Flight gate appends it
   when Panel 1 (strategy assessment) clears (see
   [`track-review.md`](track-review.md) § Track Pre-Flight step 6).

   **Always drop**: the `**Scope:**` line and the `**Depends on:**`
   line.

   Pending-track entries in the plan are written in the thin form
   during Phase 1, so there are no
   `**What**: / **How**: / **Constraints**: / **Interactions**:`
   subsections present in the plan-file entry to drop — the detailed
   description has lived in the step file's `## Description` section
   from Phase 1 onward. Phase C does not touch the step file's
   description either; it only writes the track episode + collapse
   into the plan-file entry.

   **Track episode fields:**
   - Strategic summary covering: what was built, key discoveries, plan
     deviations with cross-track impact. Length is proportional to
     cross-track impact — a routine track may need only a couple of
     sentences, while a track with architectural surprises should
     include enough detail for the next session's Track Pre-Flight
     gate (Panel 1 strategy assessment) to assess downstream impact
     without reading the step file.
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
     > **Step file:** `plan/track-N.md` (M steps, K failed)
   ```

   This shrinks completed-track entries from 100+ lines to ~10–15
   lines and keeps the plan file lean as tracks land. The
   `**Strategy refresh:**` line is appended by the next session's
   Track Pre-Flight gate when Panel 1 clears.

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
           docs/adr/<dir-name>/_workflow/plan/track-<N>.md
   git commit -m "Mark <track> complete"
   git push
   ```

   This commit is registered as scaffolding in the resume orphan
   detection (see
   [`step-implementation-recovery.md`](step-implementation-recovery.md)
   §Resume-side commit-pattern reference — entry 5, "Other Workflow
   update commits"). It does **not** contribute to any `[x]` step's
   expected commit set.

   After the push, **remove the staged temp files for this track**
   so they don't linger as later tracks run:

   ```bash
   rm -f /tmp/claude-code-track-{N}-diff-$PPID.patch \
         /tmp/claude-code-track-{N}-files-$PPID.txt
   ```

   If the user re-opens this track later (e.g., post-PR fixes), the
   regeneration rule from § Sub-agents → § "Pre-staged diff and
   changed-files list" stages fresh copies before the next fan-out.

   **Budget rule for re-opened FIX_FINDING.** Any FIX_FINDING
   spawn produced by a re-opened track's Completion gate (the user
   re-enters Review mode and clicks Apply on a FIX_FINDING action
   set) is **budgetless** — same rule as a same-session Completion
   FIX_FINDING per § Track Completion step 3 "Completion outcome
   mapping". The 3-iteration cap exists to bound autonomous
   review-loop spinning; the user-in-the-loop check at each Apply
   is the natural rate limit and replaces the autonomous cap. The
   pre-Completion review loop's counter from the original session
   is not consulted on re-open.

6. **Run self-improvement reflection.** Load
   `.claude/workflow/self-improvement-reflection.md` on-demand and
   follow it. Phase C friction worth recording typically lives in
   the track-level review-iteration loop, the dimensional review
   agent selection, the deferred-finding → plan-correction handoff,
   the implementer-driven review-fix application, or the track
   episode template. The protocol creates approved proposals as
   YouTrack issues under `YTDB` with the `dev-workflow` tag (or
   skips with a notice if the YouTrack MCP server is unreachable);
   reflection produces no commit. Then proceed to Step 7.
7. **Session ends.** The next session's Track Pre-Flight gate runs
   the strategy assessment (Panel 1) against this track's episode.

**Why deferred write:** Writing the track episode and marking `[x]` before
user approval creates a state that cannot be reliably resumed — if the
session ends between marking `[x]` and receiving approval, the next session
detects the track as complete (State A: pre-Phase-A — Track Pre-Flight
runs) and skips user review entirely. By deferring the write, an
interrupted session simply re-enters track completion on resume (all
phases `[x]` in the step file, track still `[ ]` in the plan file).

**Why merge with code review:** Phase C's code review and track completion
have no perspective conflict — unlike Phase B→C (where implementation
context biases code review), here the reviewer mindset naturally feeds into
the track episode. More importantly, Phase C may produce plan corrections
(deferred findings → new or updated tracks), and the track episode must
accurately reflect these. A separate session would lose this context.
