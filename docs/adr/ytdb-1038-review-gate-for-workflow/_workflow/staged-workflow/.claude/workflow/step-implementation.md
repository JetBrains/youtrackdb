# Track Execution — Phase B: Step Implementation (Orchestrator)

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Loading discipline — happy path only | orchestrator | 3B | This file is the Phase B orchestrator happy-path spec; recovery and resume handlers live in the recovery file. |
| §Phase B Startup | orchestrator | 3B | Phase B startup: verify the base commit, render the slim plan, and prepare the per-step orchestration loop. |
| §Per-step base commit tracking | orchestrator | 3B | Record and verify the per-step base commit so a step revert returns the tree to its pre-implementation state. |
| §Step Completion Gate | orchestrator | 3B | The gate that decides whether a step is done and the loop advances to the next step. |
| §Per-Step Orchestration Loop | orchestrator | 3B | The seven sub-steps per step: spawn implementer, review (risk:high only), cross-track check, episode, advance. |
| §Implementer Prompt Template | orchestrator | 3B | The prompt template the orchestrator composes for each per-step implementer spawn. |
| §Orchestrator Handlers | orchestrator | 3B | The handlers that process each implementer RESULT (success, design decision, risk upgrade, failure). |
| §`on_success(step, result)` — sub-steps 4–7 | orchestrator | 3B | On SUCCESS: dimensional review for risk:high, cross-track impact check, episode write, and loop advance. |
| §Cross-Track Impact Check | orchestrator | 3B | After each step, check whether the change affects other tracks and route any impact to inline replanning. |
| §If impact is detected | orchestrator | 3B | When cross-track impact is found, surface it and route to inline replanning or a plan correction. |
| §If no impact is detected | orchestrator | 3B | When no cross-track impact is found, record the check and continue the loop. |
| §Episode Production | orchestrator | 3B | The orchestrator finalises the implementer EPISODE_DRAFT and writes the Episodes block, Progress entry, and promotions. |
| §Phase B Completion | orchestrator | 3B | Phase B completes when every step is [x]; record the base commit and hand off to Phase C. |

<!--Document index end-->

Phase B is a **two-actor phase**:

- The **orchestrator** (this document) drives the per-step loop:
  spawn the implementer, route on its return, run the dimensional
  review fan-out for `risk: high` steps, run the cross-track impact
  check, run the context check, finalise the step episode, mark the
  step `[x]`, and update the Progress count.
- The **implementer** (a fresh sub-agent spawned per step, see
  implementer-rules.md:implementer:3B,3C) performs sub-steps
  1–3 of step implementation — read the track file, implement the
  change with tests, stage and commit. The implementer's output is a
  structured handoff parsed by the orchestrator.

The split exists because Phase B's main-agent context is dominated by
Maven output, source-file reads, and IDE traffic. Delegating sub-steps
1–3 to a per-step sub-agent absorbs that traffic outside the
orchestrator's context. The orchestrator sees only a small structured
return per step.

The implementer rulebook is the authoritative reference for sub-steps
1–3. This document stays focused on the orchestrator side.

## Loading discipline — happy path only
<!-- roles=orchestrator phases=3B summary="This file is the Phase B orchestrator happy-path spec; recovery and resume handlers live in the recovery file." -->

This document covers the **happy path** only: every implementer spawn
returns `RESULT: SUCCESS`, no orphan commits exist at session start,
no step is marked `[!]`. The non-happy-path logic — Phase B Resume,
the three non-`SUCCESS` orchestrator handlers, post-commit rollback
handlers, retry/split formats, the Two-Failure Rule, Track-Level
Failure — lives in
step-implementation-recovery.md:orchestrator:3B
and is loaded on demand.

**Read the recovery file when either trigger fires:**

1. **At Phase B Startup**, after `git log {base_commit}..HEAD`: if
   the log shows orphan implementer commits, orphan `Review fix:`
   commits, or a `Revert step:` commit at the tip — see §Phase B
   Startup item 3 below.
2. **At result dispatch** (Per-Step Orchestration Loop, on_success
   sub-step 4c): any value other than `SUCCESS` from an implementer
   spawn — `DESIGN_DECISION_NEEDED`, `RISK_UPGRADE_REQUESTED`,
   `FAILED`.

The recovery file is self-contained — once loaded, it carries the
full procedure for the matching case.

---

## Phase B Startup
<!-- roles=orchestrator phases=3B summary="Phase B startup: verify the base commit, render the slim plan, and prepare the per-step orchestration loop." -->

Before spawning the first implementer:

1. **Record the base commit.** Run `git rev-parse HEAD` to get the
   current SHA, then write it to the track file's `## Base commit`
   section (creating the section if it doesn't exist). Skip if
   `## Base commit` already has a SHA (resume case).

   The track file change must be committed before the first
   implementer spawn — the implementer's `git reset --hard HEAD`
   would otherwise discard the `## Base commit` write. Stage and
   commit:

   ```bash
   git add docs/adr/<dir-name>/_workflow/plan/track-<N>.md
   git commit -m "Record Phase B base commit for <track>"
   git push
   ```

   Skip the commit on resume (when `## Base commit` already had a
   SHA and no write happened).

   **On resume, verify the recorded base is reachable from HEAD
   before using it for orphan detection (step 3 below) or any other
   downstream computation.** A rebase between the prior session and
   this resume rewrites every on-branch commit; the recorded SHA
   still resolves (the old object stays in the reflog) but is no
   longer an ancestor of HEAD, and `git log {base_commit}..HEAD`
   would then enumerate commits from earlier tracks instead of just
   the orphans for this track.

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
   # Use $ACTUAL_BASE as {base_commit} for the rest of Phase B.
   ```

   Scope `git log` to `HEAD` (not `--all`) and match the track title
   literally with `-F` (fixed-strings) so titles containing regex
   metacharacters (`.`, `(`, `[`, `*`, …) cannot mis-match and so
   reflog orphans and other tracks' recording commits are ignored.
   If `git log --grep` returns nothing, surface the discrepancy to
   the user before proceeding; do not invent a base.

   On stale, **append** a discrepancy note to the track file's
   `## Base commit` section (do not overwrite the original SHA —
   keep both for the audit trail):

   ```
   Note: recorded base <stale-sha> was stale (likely from a
   rebase since Phase B); using actual on-branch parent <actual-sha>
   for resume.
   ```

   Commit the track-file edit as a Workflow update commit before
   spawning the first implementer (per
   commit-conventions.md:orchestrator,implementer:3A,3B,3C § Commit type
   prefixes — "Workflow update" row) so the draft PR records the
   recompute. Use `$ACTUAL_BASE` as `{base_commit}` for the orphan
   detection in step 3 and every subsequent reference in Phase B
   (the implementer's `base_commit` field in §Implementer Prompt
   Template, the recovery file's `git log {base_commit}..HEAD`
   patterns).
2. **Generate the slim plan snapshot** at
   `/tmp/claude-code-plan-slim-$PPID.md` by running:

   ```bash
   python3 .claude/scripts/render-slim-plan.py \
       --plan-path docs/adr/<dir-name>/_workflow/implementation-plan.md \
       --out /tmp/claude-code-plan-slim-$PPID.md
   ```

   The script implements the rule from
   plan-slim-rendering.md:orchestrator:3B,3C; do not re-derive
   the transform inline. **Always pass `--out` explicitly** with
   `$PPID` — the orchestrator's PID inside its bash shell. The script
   does not auto-derive this path because, when invoked via the Bash
   tool, the script's parent is the bash shell, not the orchestrator,
   so any default would silently drift to the wrong PID and sub-agents
   would see a missing snapshot. Both the implementer and any
   dimensional review sub-agents read this snapshot by path — it keeps
   the orchestrator's tool-call history from accumulating a plan copy
   per spawn. Regenerate the snapshot only if inline replanning
   (ESCALATE) modifies the plan mid-session. The snapshot lives in
   `/tmp` (not under `_workflow/`) and is not committed.
3. **Detect orphan commits.** Run `git log --oneline
   {base_commit}..HEAD` and inspect the result. If it shows orphan
   implementer commits, orphan `Review fix:` commits, or a
   `Revert step:` commit at the tip, the previous session was
   interrupted mid-step — load
   step-implementation-recovery.md:orchestrator:3B
   and follow §Phase B Resume there before spawning the first
   implementer. If no orphan commits exist, continue normally.

### Per-step base commit tracking
<!-- roles=orchestrator phases=3B summary="Record and verify the per-step base commit so a step revert returns the tree to its pre-implementation state." -->

The orchestrator tracks a **per-step base commit** —
`step_base_commit` — distinct from the Phase B `base_commit`:

- `base_commit` is the SHA at Phase B startup. It does not change
  across steps.
- `step_base_commit` is the SHA at HEAD **immediately before the
  implementer is first spawned for the current step** (`mode=INITIAL`
  or the first respawn after a `WITH_GUIDANCE` escalation /
  `apply_upgrade_then_decide` upgrade). It advances every time a step
  reaches `[x]`.

`step_base_commit` is the rollback target if a `FIX_REVIEW_FINDINGS`
respawn returns a non-`SUCCESS` result — `git revert
{step_base_commit}..HEAD` produces a single revert commit covering
the original implementer commit plus any prior `Review fix:` commits
in the same dim-review loop. See
step-implementation-recovery.md:orchestrator:3B
§Post-Commit Handlers.

The orchestrator captures `step_base_commit` in-memory by running
`git rev-parse HEAD` just before the spawn. On resume, derive it
from git: it is the parent of the first orphan commit for the next
`[ ]` step, or HEAD if no orphans exist.

---

## Step Completion Gate
<!-- roles=orchestrator phases=3B summary="The gate that decides whether a step is done and the loop advances to the next step." -->

**A step is not complete until all seven sub-steps of the per-step
workflow are done.** Do NOT spawn the implementer for the next step
until the current step's episode is written to the track file on disk
and cross-track impact is assessed. This is a hard gate, not a
guideline.

**Why this matters.** Skipping the gate — even for steps that "feel
mechanical" — defeats the workflow's purpose. The dimensional review
catches issues early (before they propagate to later steps). Episodes
record discoveries that inform remaining steps. Cross-track checks
catch assumption failures before they compound. Batching these
activities across steps loses all three benefits.

**Prohibited patterns:**
- Spawning Step N+1's implementer before Step N's episode is written.
- Batching dimensional reviews across multiple steps.
- Batching episodes across multiple steps ("retroactive" episodes).
- Treating the implementer's `SUCCESS` return as the end of the step.

---

## Per-Step Orchestration Loop
<!-- roles=orchestrator phases=3B summary="The seven sub-steps per step: spawn implementer, review (risk:high only), cross-track check, episode, advance." -->

For each `[ ]` step in the track file, run sub-steps 1–8 to
completion before moving to the next step. Sub-steps 1–3 run inside
the implementer; sub-steps 4–7 run on the orchestrator side.

```
result = spawn_implementer(step, mode=INITIAL)
match result.RESULT:
    SUCCESS                  -> on_success(step, result)          # sub-steps 4–7
    DESIGN_DECISION_NEEDED   -> [load recovery] escalate_to_user_then_respawn(step, result)
    RISK_UPGRADE_REQUESTED   -> [load recovery] apply_upgrade_then_decide(step, result)
    FAILED                   -> [load recovery] handle_failure(step, result)
    <no parsable RESULT block> -> [load recovery] handle_result_missing(step, result_text)
```

Only `on_success` is in this document. The other four handlers are
in
step-implementation-recovery.md:orchestrator:3B
§Non-`SUCCESS` orchestrator handlers — load it before entering any
of those branches. The `handle_result_missing` path covers the
contract-violation case where the implementer exited without emitting
a `RESULT` block (typically due to message-budget exhaustion or a
tool-call crash); per
implementer-rules.md:implementer:3B,3C §Return contract,
silent exit is forbidden, but the orchestrator must still be able to
recover when it happens. The implementer prompt template is in
§Implementer Prompt Template below.

---

## Implementer Prompt Template
<!-- roles=orchestrator phases=3B summary="The prompt template the orchestrator composes for each per-step implementer spawn." -->

Each spawn uses `subagent_type: "general-purpose"` and
`model: "opus"` regardless of the step's risk tag. Sonnet's
reliability on multi-step implementation work is below the threshold
required for this workflow — implementer steps that complete cleanly
on Opus surface intermittent execution errors on Sonnet even at
`risk: low` (skipped sub-steps, incorrect test invocations, malformed
return blocks). See risk-tagging.md:decomposer,orchestrator,implementer:3A,3B,3C §"Risk
levels — quick reference" for the rationale and for the risk-tag
effects that DO still apply (sub-step 4 dimensional review, track-level
focal-point treatment).

Because the model is the same across all risk tags, `INITIAL`,
`WITH_GUIDANCE`, and `FIX_REVIEW_FINDINGS` respawns all use Opus, and
a `low → high` upgrade per
risk-tagging.md:decomposer,orchestrator,implementer:3A,3B,3C §"Phase B upgrade" does not
change the model — it only changes what review pressure runs after
the implementer returns.

The same template is used by Phase C with `level=track` (see
track-code-review.md:orchestrator,reviewer-dim-track:3C §Implementer Spawns).

The prompt body has a **stable static prefix** followed by the
**per-spawn variable inputs**. The static block goes first for
predictability and to keep the variable section easy to spot in
transcripts; whether the platform's prompt cache hits across
sub-agent spawns depends on Claude Code's `cache_control` placement
(currently neither documented nor guaranteed for Agent-tool spawns),
so do not rely on caching as a load-bearing optimisation here.

```
## Workflow Context (static — same on every spawn this session)

You are the **implementer** in a structured development workflow.
You apply a code change (sub-steps 1–3: implement/fix, test, commit)
and return a structured handoff to the orchestrator. Read the
rulebook before starting any work:

  Rulebook: .claude/workflow/implementer-rules.md

The rulebook defines what you do, the three early-return cases
(design decision, risk upgrade, failure), and the return contract
your output must end with. Do not modify the track file or the plan —
those are the orchestrator's responsibility.

When the active plan's `### Constraints` section carries the canonical
workflow-modifying marker sentence defined in `conventions.md` §1.7(b),
the rulebook adds two staging-specific routes you apply on every write
to `.claude/workflow/**` or `.claude/skills/**`. Both routes live in the
rulebook already — the **Path mapping for workflow-modifying plans**
bullet under §"What the implementer does (sub-steps 1–3, expanded)"
Sub-step 1 routes writes to
`docs/adr/<plan-dir>/_workflow/staged-workflow/.claude/...`, and the
**Pre-commit gate, live-workflow-path check** alongside the
ephemeral-identifier gate refuses live-path commits outside the Phase 4
promotion commit. Reads of `.claude/workflow/**` or `.claude/skills/**`
follow the precedence rule in `conventions.md` §1.7(d): the staged copy
is authoritative when present, otherwise read the live file. No extra
spawn input carries the marker — you detect it by reading the
`### Constraints` section of the active plan, which you already load
for strategic context. On plans without the marker, the staging rule
does not apply and you write to live paths normally.

## Stable inputs (static)

repo_root: {repo_root}
plan_slim_path: /tmp/claude-code-plan-slim-{PPID}.md
step_file_path: docs/adr/{dir-name}/_workflow/plan/track-{N}.md
design_path: docs/adr/{dir-name}/_workflow/design.md

## Per-spawn variable inputs

level: {step | track}
base_commit: {base_commit}
mode: {INITIAL | WITH_GUIDANCE | FIX_REVIEW_FINDINGS}

# Level-conditional fields — only populated when level == step.

step_index: {step_index}
step_description: {step_description}
risk_tag: {risk_tag}

# Mode-conditional fields — only populated when relevant.

Guidance: {populated only when mode == WITH_GUIDANCE}
exploration_notes_echo: {populated only when mode == WITH_GUIDANCE}
findings: {populated only when mode == FIX_REVIEW_FINDINGS}

## Instructions

Read the rulebook at the path above, then perform sub-steps 1–3 for
the spawn (the step at step_index when level=step; the cumulative
track diff base_commit..HEAD when level=track). Return the
structured block defined in the rulebook's §Return contract. Do not
return free-form prose after the block — the orchestrator parses
only the block.

**Mandatory RESULT block on every exit.** Emit the structured
RESULT block before exiting for any reason — successful completion,
detection-rule early return, fundamental failure, context-window
exhaustion, message-budget pressure, tool-call-budget pressure, or
runtime error in any tool call. A return with no parsable RESULT
block (or a truncated one) is a contract violation that prevents
the orchestrator from recovering. When an exit is forced before
sub-steps 1–3 complete, emit RESULT: FAILED with the cause stated
in FAILURE.why_it_failed, every touched path listed in
FILES_TOUCHED (even if not yet reverted), and recommended_action:
retry. The orchestrator can recover from honest partial-failure but
not from silence. See the rulebook's §Return contract for the full
clause.
```

The orchestrator substitutes `{repo_root}`, `{PPID}`, `{dir-name}`,
`{N}`, and the per-spawn variable values when composing each prompt.
Phase B always passes `level: step` and populates `step_index`,
`step_description`, and `risk_tag`; Phase C always passes
`level: track` and leaves the three step-conditional fields out
(see the validity matrix in
implementer-rules.md:implementer:3B,3C §Inputs).

**Why the rulebook is referenced by path, not inlined.** Inlining the
rulebook on every spawn would re-embed it in the orchestrator's
tool-call history; passing a path keeps the orchestrator lean. The
implementer reads the rulebook itself, so the contents land only in
sub-agent context.

---

## Orchestrator Handlers
<!-- roles=orchestrator phases=3B summary="The handlers that process each implementer RESULT (success, design decision, risk upgrade, failure)." -->

### `on_success(step, result)` — sub-steps 4–7
<!-- roles=orchestrator phases=3B summary="On SUCCESS: dimensional review for risk:high, cross-track impact check, episode write, and loop advance." -->

The implementer's commit is now on disk; `result.COMMIT` is its SHA.
Per implementer-rules.md:implementer:3B,3C §Return contract,
`RESULT: SUCCESS` implies the commit has been pushed to `origin`.

**Defensive push check.** Before running sub-steps 4–7, assert that
the implementer's step commit (`result.COMMIT`) is on `origin` per
defensive-push-check.md:orchestrator:3B,3C. The check
short-circuits when no upstream is set, asserts ancestry against
`@{u}`, and auto-recovers via `git push` if the implementer skipped
the push.

Run sub-steps 4–7 in order.

**Sub-step 4 — Dimensional review loop (only when `step.risk_tag ==
'high'`).** For `medium` and `low` steps, skip directly to sub-step
5. The dimensional review loop follows the protocol unchanged: up to
3 iterations within the orchestrator's context. See
code-review-protocol.md:orchestrator:3B,3C for the
two-tier protocol overview and
review-iteration.md:orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track:2,3A,3B,3C for iteration limits,
finding ID prefixes, and gate format.

   a. **Select review agents** based on code characteristics (see
      review-agent-selection.md:orchestrator:3A,3B,3C),
      then spawn them in parallel (fresh sub-agents each iteration).
      Baseline agents (4) run unless the diff is workflow-only (see
      the baseline-skip override in `review-agent-selection.md`);
      conditional agents and workflow-review agents are added based
      on the step description and changed files.

      Before composing prompts, **pre-stage the step diff and the
      changed-files list** so the canonical context block references
      paths instead of inlining content. Staging the diff matches the
      agents' `## Input` contract (each spec says "a path to a temp
      file containing the full diff", read via the `Read` tool);
      staging the changed-files list is an orchestrator-context
      optimisation that keeps the file list out of the tool-call
      history across the multi-agent fan-out:

      ```bash
      git diff {commit}~1..{commit} \
          > /tmp/claude-code-step-{N}-{M}-diff-$PPID.patch
      git diff {commit}~1..{commit} --name-only \
          > /tmp/claude-code-step-{N}-{M}-files-$PPID.txt
      ```

      Regenerate both files before every gate-check fan-out — each
      `Review fix:` respawn produces a new commit, so `{commit}`
      advances and the previously staged files go stale.

      Each agent receives the same context. The diff target is the
      implementer's commit (`{result.COMMIT}~1..{result.COMMIT}`),
      not uncommitted changes:

      ```
      ## Workflow Context
      This is a **step-level code review** within a structured
      development workflow. A **track** is a coherent stream of
      related work within an implementation plan; a **step** is a
      single atomic change (one commit, fully tested). You are
      reviewing one step's diff. The implementation plan below
      provides strategic context: goals, architecture decisions
      (Decision Records), constraints, and component topology
      (Component Map). The track file provides tactical
      context: what each step does and what was discovered.
      **Episodes** are the blocks in the track file's `## Episodes`
      section — one `### Step N — commit <SHA>, <ISO> [ctx=<level>]`
      block per completed step, carrying `**What was done:**`,
      `**What was discovered:**`, `**What changed from the plan:**`,
      `**Key files:**`, and `**Critical context:**` fields. Use
      episodes to understand intent behind prior steps and check for
      cross-step consistency issues.
      Severities: **blocker** (must fix), **should-fix** (should fix
      before merge), **suggestion** (optional improvement).

      ## Review Target
      Track {N}, Step {M}: {step description}
      Reviewing: commit range {commit}~1..{commit}

      ## Implementation Plan (strategic context)
      Read the slim plan snapshot at:
        /tmp/claude-code-plan-slim-{PPID}.md
      Filtered view of the plan — completed tracks show title +
      intro + track episode + the on-disk `**Strategy refresh:**`
      line only; the current track and other not-started tracks are
      shown in full. If the snapshot is missing, fall back to
      `docs/adr/{dir-name}/_workflow/implementation-plan.md`.

      ## Track File (tactical context)
      Read the track file at:
        docs/adr/{dir-name}/_workflow/plan/track-{N}.md
      The file follows the 14-section per-track ExecPlan shape. Four
      Phase 1 track-level sections carry the track's intent and any
      track-level diagram: `## Purpose / Big Picture` (BLUF +
      ADDED/MODIFIED/REMOVED triad), `## Context and Orientation`
      (current-state framing), `## Plan of Work` (strategy +
      step-references appended at Phase A), and `## Interfaces and
      Dependencies` (in-scope / out-of-scope, inter-track
      dependencies). `## Concrete Steps` carries the per-step roster:
      one `N. <description> — risk: <tag>  [x|!| ]` line per step
      (`[x]` = complete with episode block; `[!]` = failed-and-retried
      with episode block; `[ ]` = pending, no episode block yet),
      optionally with `commit: <SHA>` appended once the step lands.
      `## Episodes` carries one block per completed step, headed
      `### Step N — commit <SHA>, <ISO> [ctx=<level>]`; join roster
      to episode by step number, using the roster's optional
      `commit: <SHA>` as a disambiguator.

      ## Skip These Files (generated code)
      - core/.../sql/parser/*, generated-sources/*, Gremlin DSL

      ## Tooling
      Use **mcp-steroid PSI find-usages, not grep**, for any
      reference-accuracy question about a Java symbol in the diff
      or its callers (callers/overrides/usages of a method, field,
      class, or annotation; whether a slot has any consumer;
      whether a reference is confined to one component). Grep is
      acceptable for filename globs, unique string literals, and
      orientation reads, but the load-bearing answer behind a
      finding must be PSI-backed when mcp-steroid is reachable. If
      the SessionStart hook reported `mcp-steroid: NOT reachable`,
      fall back to grep and note the reference-accuracy caveat in
      any finding that depends on a symbol search.

      ## Staged-read precedence (workflow-modifying plans)
      When the plan's `### Constraints` carries the canonical
      `§1.7(b)` workflow-modifying marker sentence, resolve every
      read of a `.claude/workflow/**` or
      `.claude/skills/**` file through `§1.7(d)`, taking the staged
      copy under `_workflow/staged-workflow/` when present and the
      live file otherwise. Without the marker this caveat is inert:
      read the live path. Reading the live file when a staged copy
      exists would compare a change against `develop`'s version of a
      rule the branch already rewrote and report a phantom mismatch.

      ## Changed Files
      The changed-files list is at:
        /tmp/claude-code-step-{N}-{M}-files-{PPID}.txt
      Read it with the `Read` tool.

      ## Diff
      The step's diff (commit range {commit}~1..{commit}) is at:
        /tmp/claude-code-step-{N}-{M}-diff-{PPID}.patch
      Read it with the `Read` tool before forming findings. For
      diffs over 2000 lines, page through with the `offset` and
      `limit` parameters.
      ```

      **Why paths, not inline contents.** Inlining `{contents}` for
      the plan, track file, diff, or changed-files list places a copy
      in the orchestrator's tool-call history for every sub-agent
      spawn. Across a Phase B session this dominates orchestrator
      context. Paths keep the orchestrator lean; sub-agents Read the
      files themselves so the contents land only in sub-agent
      context. Routing the diff via a path also matches the agents'
      `## Input` spec uniformly across step-level and track-level
      reviews and across the `/code-review` standalone entry point.

      The orchestrator substitutes `{PPID}`, `{dir-name}`, `{N}`,
      `{M}`, and `{commit}` with concrete values when composing each
      prompt.

   b. **Synthesise.** After all selected agents complete, run the
      canonical procedure in
      finding-synthesis-recipe.md:orchestrator:3B,3C:
      deduplicate across dimensions by `file:line` → issue shape →
      suggested fix shape, prioritise on the
      `blocker` / `should-fix` / `suggestion` scale (per
      review-iteration.md:orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track:2,3A,3B,3C §Severity
      levels), and emit the merged list in the format the
      implementer's `findings:` block consumes. Step-level review
      operates on a single step's diff, so the deferred /
      plan-correction buckets in the recipe are typically empty
      here — most surfaced findings are in-scope for the next
      `FIX_REVIEW_FINDINGS` respawn. Items the orchestrator judges
      out of scope for the step are recorded in the `EPISODE_DRAFT`
      instead, not routed via §Plan Corrections (that flow is
      track-level only). The orchestrator stages these notes
      alongside the sub-step 5 cross-track-impact observations;
      sub-step 7's episode merge folds both into
      `EPISODE_DRAFT.what_was_discovered`.
   c. **If findings need fixes**, respawn the implementer with
      `mode=FIX_REVIEW_FINDINGS` and the synthesised findings as
      input. The implementer applies the fixes on top of the
      existing commit. The respawn's return is then **dispatched on
      `RESULT`** — non-`SUCCESS` returns leave a prior commit on
      disk and route to a post-commit handler:

      ```
      match fix_result.RESULT:
          SUCCESS                  -> continue iteration loop with
                                      the new Review fix: commit as
                                      the diff target
          FAILED                   -> [load recovery] rollback_and_handle_failure(
                                          step, fix_result,
                                          step_base_commit)
                                      # exits the dim-review loop
          DESIGN_DECISION_NEEDED   -> [load recovery] rollback_and_escalate(
                                          step, fix_result,
                                          step_base_commit)
                                      # exits the dim-review loop
          RISK_UPGRADE_REQUESTED   -> [load recovery] rollback_and_upgrade(
                                          step, fix_result,
                                          step_base_commit)
                                      # exits the dim-review loop
          <no parsable RESULT block> -> [load recovery] handle_result_missing(
                                          step, fix_result_text)
                                      # exits the dim-review loop
      ```

      On `SUCCESS`, the implementer's new commit follows
      commit-conventions.md:orchestrator,implementer:3A,3B,3C's
      `Review fix:` prefix; the new `result.COMMIT` becomes the
      diff target for the next gate check. On any non-`SUCCESS`
      return, load
      step-implementation-recovery.md:orchestrator:3B
      and hand off to the post-commit handler defined there
      (§Post-Commit Handlers); it rolls the prior commits back and
      re-enters the top-level dispatch with a clean tree at
      `step_base_commit`.
   d. **Re-run only the dimension(s) with open findings** on each
      gate-check iteration. Repeat until approved OR **max 3
      iterations** reached. **Spawn the gate-check sub-agents with
      the compact prompt template at
      prompts/dimensional-review-gate-check.md:reviewer-dim-step,reviewer-dim-track:3B,3C,
      not the dimensional review prompt from sub-step (a).**
      Substitute:
      - `{dimension}` — the agent's review type
      - `{findings_under_recheck}` — open finding IDs and titles for that dimension, copied verbatim from the synthesised list
      - `{diff_path}`, `{files_path}` — the re-staged step diff and changed-files list (regenerated per the staging block in sub-step (a) because each `Review fix:` respawn advanced `{commit}`)
      - `{plan_slim_path}` — `/tmp/claude-code-plan-slim-$PPID.md`
      - `{step_file_path}` — `docs/adr/<dir-name>/_workflow/plan/track-{N}.md`

      The template enforces a ≤ 60-line budget, a forbidden-sections
      list, and a verdict-only output format. See
      review-iteration.md:orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track:2,3A,3B,3C §"Dimensional-review
      gate-check budget" for the YTDB-696 rationale, the verdict-
      handling rules (VERIFIED / REJECTED / MOOT / STILL OPEN /
      REGRESSION), and the §Gate-check synthesis routing.
      Step-level review only fires for `risk: high` steps, so this
      path is rarer than Phase C track-level review, but the burn
      rate is identical when it does fire.

      After collecting all gate-check returns, re-run sub-step 4(b)
      **Synthesise** on the aggregated `New findings` blocks before
      composing the next implementer input. Treat `REGRESSION`
      verdicts as blocker-severity carry-forwards with
      `revert-or-repair` guidance; treat `REJECTED` and `MOOT`
      verdicts as cleared (identical to `VERIFIED`); carry
      `STILL OPEN` verdicts forward verbatim with the original
      finding ID.
   e. If max iterations reached, note remaining findings in the
      `EPISODE_DRAFT` so they appear in the step episode.
   f. **Remove the staged temp files** for this step so they don't
      accumulate as the orchestrator advances to later steps:

      ```bash
      rm -f /tmp/claude-code-step-{N}-{M}-diff-$PPID.patch \
            /tmp/claude-code-step-{N}-{M}-files-$PPID.txt
      ```

      Skip this on a non-`SUCCESS` exit from the dim-review loop —
      the post-commit handler rolls back and the files are already
      orphaned by the next step's regeneration.

**Sub-step 5 — Cross-track impact check.** Quick self-assessment
against the plan, fed by `result.CROSS_TRACK_HINTS`. See §Cross-Track
Impact Check below for the full protocol. If minor impact is detected
(recommendation: **Continue**), record the affected tracks and
weakened assumptions for sub-step 7's episode merge. If **Pause and
ADJUST** or **ESCALATE** is needed, alert the user immediately.

**Sub-step 6 — Context consumption check** (mandatory, including
after the last step). Always run it:

```bash
cat /tmp/claude-code-context-usage-$PPID.txt
```

Record the result: one of `safe`, `info`, `warning`, `critical`, or
`unknown` (the fallback sentinel when the statusline file is missing
or unparseable, mirroring D12 and every other Progress writer).

The recorded value has two downstream uses, which are deliberately
kept separate:

- **Verbatim into `[ctx=<level>]`.** Sub-step 7's writes inline the
  recorded value literally into every `[ctx=<level>]` field. If the
  recorded value is `unknown`, the field MUST be written as
  `[ctx=unknown]` — do NOT silently rewrite it to `[ctx=safe]` or
  any other level. The audit trail depends on the on-disk record
  reflecting what the orchestrator actually observed.
- **Continue-versus-pause gate.** For the session-end gate at the
  end of this sub-step block (and for any continue-versus-pause
  decision in this sub-step alone), treat `unknown` as `safe` — a
  missing statusline file is not in itself an error condition that
  forces a pause.

**Sub-step 7 — Episode finalisation and track-file write.** Merge
`result.EPISODE_DRAFT` with cross-track-impact observations from
sub-step 5 (into **What was discovered**), then run the four-sub-
step writer below — the per-step write shape per
episode-format-reference.md:orchestrator:3A,3B,3C, which
documents the heading template, field-omission rule, promotion
heuristic, back-reference shape, and `[ctx=unknown]` fallback.

**Sub-step 7.0 — Read the statusline and capture the wall-clock
timestamp.** Reuse the level recorded by sub-step 6 (`safe` / `info`
/ `warning` / `critical`, or `unknown` on missing-file). Do not skip
the write. Also capture the current UTC time as `<ISO>` (format
`YYYY-MM-DDTHH:MMZ`) by running:

```bash
date -u +%Y-%m-%dT%H:%MZ
```

Use this same `<ISO>` for both the Episodes block header in sub-step
7.1 and the Progress entry in sub-step 7.2 — both writes refer to
the same logical "episode written" moment.

**Sub-step 7.1 — Append Episodes block + flip roster (always).**
Append `### Step N — commit <SHA>, <ISO> [ctx=<level>]` to
`## Episodes` with the four episode fields, and flip the matching
`## Concrete Steps` roster line from `[ ]` to `[x]` (optionally
appending `commit: <SHA>`) in the same edit.

**Invariant.** The `[ ]`→`[x]` roster checkbox flip in this sub-step
is the **primary marker for "episode written"**. Sub-steps 7.2–7.4
write across additional sections (Progress, Surprises, Decision Log)
and may be interrupted by a crash between 7.1 and the next write; if
that happens, the roster `[x]` plus the Episodes block on disk are
sufficient to drive resume-side reconciliation. The Phase B Resume
detection in
step-implementation-recovery.md:orchestrator:3B
§Phase B Resume runs that reconciliation before the next implementer
is spawned — Progress entries missing for a roster `[x]` row are
derived from the Episodes block; Surprises and Decision Log
promotions are conditional anyway and do not require reconciliation.

**Sub-step 7.2 — Append Progress entry (always).** Append
`- [x] <ISO> [ctx=<level>] Step N complete (commit <SHA>)` to
`## Progress`.

**Sub-step 7.3 — Promote to Surprises & Discoveries (conditional).**
Fires when **What was discovered** (a) mentions a track number other
than the current track, (b) names a class/file outside the track's
in-scope list, or (c) identifies a fact future sessions need without
reading the full episode. Back-reference shape: `See Episodes §Step N`.
Full criteria:
episode-format-reference.md:orchestrator:3A,3B,3C
§Minimum-write contract.

**Sub-step 7.4 — Promote to Decision Log (conditional).** Fires
when **What changed from the plan** names an inline-replan / scope-
down / dependency-reveal / gate-override decision. Same back-
reference shape.

**Sub-step 8 — Commit and push the episode.** The track file is
tracked under `_workflow/`, so the episode write produces a dirty
working tree. Commit and push it as a separate workflow-update
commit so the draft PR reflects the new state and so `HEAD` is
clean before the next implementer is spawned (the implementer's
revert path uses `git reset --hard HEAD`, which would otherwise
roll back the unwritten episode):

```bash
git add docs/adr/<dir-name>/_workflow/plan/track-<N>.md
git commit -m "Record episode for <step description>"
git push
```

See `commit-conventions.md` § Push every commit, and the
"Workflow update" row in § Commit type prefixes for the standard
message form.

**Push failure handling.** Inspect the `git push` exit code and
stderr. If the rejection is `non-fast-forward` (branch divergence)
and this is the first such rejection in the session, load
branch-divergence-check.md:orchestrator:2,3A,3B,3C and
apply the user's chosen resolution; do not silently keep pushing
per-step. For any other push failure (network, auth, pre-receive
hook), record the failure and continue — the session-end summary
reports the unpushed-commit count. The full rule lives in
`commit-conventions.md` § Push failure handling.

**Session-end gate.** After committing the episode: if the context
level was `warning` or `critical`, do NOT spawn the implementer for
the next step. Save all work and ask the user for a session refresh
(see `workflow.md` §Context Consumption Check). The default Phase B
case (just finished a step, next session resumes from the next `[ ]`
step) does **not** require a handoff file — the track file's Progress
section is sufficient. Write a handoff per
mid-phase-handoff.md:orchestrator,planner:0,1,2,3A,3B,3C,4 only if the pause
captures state the next session cannot re-derive from the track file
(for example, a partial cross-track-impact finding that needs to be
re-presented before the next step starts).

**Phase B can only pause at the episode-commit gate.** Mid-step
pauses are not supported: the orchestrator MUST NOT pause while the
implementer sub-agent is running, and MUST NOT pause between
sub-steps 4–7 of step implementation. If a context-pressure signal
arrives mid-step, wait for the implementer to return and complete
sub-steps 4–8, then handle the pause here. The implementer's
revert-on-failure logic and the orphan-commit recovery in
`step-implementation-recovery.md` assume an episode boundary; pausing
inside the step would leave both invariants undefined.

**→ GATE: Step is now complete.** Do not spawn the next implementer
until sub-steps 1–8 above are done.

---

## Cross-Track Impact Check
<!-- roles=orchestrator phases=3B summary="After each step, check whether the change affects other tracks and route any impact to inline replanning." -->

After each step (sub-step 5 of the per-step workflow), do a
lightweight assessment against the plan — this is a quick check, not
a full Track Pre-Flight Panel 1 strategy assessment. The orchestrator
has the plan context in-session, so this is a natural self-check,
fed by `result.CROSS_TRACK_HINTS`.

For each completed step, assess:

1. **Assumption validity** — Does this discovery contradict
   assumptions in any upcoming track's description?
2. **Architecture impact** — Does this change affect the Component
   Map or Decision Records in ways that touch other tracks?
3. **Dependency ordering** — Does this invalidate the dependency
   ordering of remaining tracks?

### If impact is detected
<!-- roles=orchestrator phases=3B summary="When cross-track impact is found, surface it and route to inline replanning or a plan correction." -->

Alert the user immediately with:

- Which upcoming track(s) are affected.
- What assumption is weakened or invalidated.
- What the step discovered that triggered this alert.
- Recommended action:
  - **Continue** (minor impact — record in the step episode's
    **What was discovered** field via sub-step 7's merge so strategy
    refresh and future track reviews can see it; no user
    notification needed).
  - **Pause and ADJUST** (remaining steps in current track need
    revision).
  - **ESCALATE** (the discovery fundamentally changes the plan —
    see inline-replanning.md:orchestrator:3A,3C).

### If no impact is detected
<!-- roles=orchestrator phases=3B summary="When no cross-track impact is found, record the check and continue the loop." -->

Continue to the context check (sub-step 6). No user notification
needed.

---

## Episode Production
<!-- roles=orchestrator phases=3B summary="The orchestrator finalises the implementer EPISODE_DRAFT and writes the Episodes block, Progress entry, and promotions." -->

Episodes are produced by the orchestrator from the implementer's
`result.EPISODE_DRAFT`, merged with cross-track-impact-check
observations from sub-step 5. The implementer never writes the
episode to disk — that is the orchestrator's responsibility.

The episode includes:

- **What was done** — factual summary from `EPISODE_DRAFT.what_was_done`.
- **What was discovered** — unexpected findings; merge
  `EPISODE_DRAFT.what_was_discovered` with any cross-track impact
  observations from sub-step 5 and any out-of-step items the
  orchestrator deferred during sub-step 4(b) review synthesis.
- **What changed from the plan** — deviations from
  `EPISODE_DRAFT.what_changed_from_plan`, naming affected future
  steps.
- **Key files** — files created/modified from `result.FILES_TOUCHED`.
- **Critical context** — from `EPISODE_DRAFT.critical_context`. Use
  sparingly.

Prose produced by this file follows the project house-style at
`.claude/output-styles/house-style.md`. Tier A (full house-style:
BLUF lead, banned vocabulary, em-dash discipline, soft section
length cap with template-bound exemptions, structural rules)
applies to the episode prose this protocol writes — the four
`EPISODE_DRAFT`-sourced fields above
(`What was done`, `What was discovered`, `What changed from the
plan`, `Critical context`), merged with cross-track-impact
observations, plus the Progress / Surprises & Discoveries /
Decision Log entries the orchestrator emits during sub-step 7.
The four banned-section heading slugs to apply are
`## Banned vocabulary`, `## Banned sentence patterns`,
`## Banned analysis patterns`, and `### Em-dash discipline`.
See conventions.md:any:any for the workflow-level pointer.

Write the episode to the track file on disk. Detailed format and
examples live in
episode-format-reference.md:orchestrator:3A,3B,3C.

The failed-episode format (`[!]` entry, `**What was attempted:**` /
`**Why it failed:**` fields), retry/split row insertion, and the
Two-Failure Rule live in
step-implementation-recovery.md:orchestrator:3B
— load it when handling a `RESULT: FAILED` return.

---

## Phase B Completion
<!-- roles=orchestrator phases=3B summary="Phase B completes when every step is [x]; record the base commit and hand off to Phase C." -->

After the last step's episode is written to the track file (and its
code changes committed to git):

1. **Mark `Step implementation` as `[x]`** in the Progress section.
2. **Inform the user** that Phase B is complete:
   - How many steps were implemented (including any failed/retried;
     count from `[!]` and retry rows in the track file if recovery
     was used).
   - Key discoveries from step episodes.
   - Any unresolved code review findings.
   - Instruct: "Clear session and re-run `/execute-tracks` to start
     Phase C (track-level code review)."
3. **Run self-improvement reflection.** Load
   `.claude/workflow/self-improvement-reflection.md` on-demand and
   follow it. Phase B friction worth recording typically lives in
   the implementer rulebook, cross-track impact handling, the
   step-level review-agent selection, recovery from non-`SUCCESS`
   returns, or risk-tagging edge cases that surfaced during
   execution. Reflection is **mandatory** even if Phase B ended on
   a context-window warning, the two-failure rule, or any other
   early-exit path — those exits are themselves the kind of
   friction reflection is meant to capture. The protocol creates
   approved proposals as YouTrack issues under `YTDB` with the
   `dev-workflow` tag (or skips with a notice if the YouTrack MCP
   server is unreachable); reflection produces no commit. Then
   proceed to Step 4.
4. **End the session.** Do not proceed to Phase C in the same
   session.

**Why.** Phase B accumulates implementation-flavoured context — the
implementer's commits, debugging history, workaround decisions, test
fixtures filtered through the orchestrator's handlers. This context
would bias the track-level code reviewer (who needs fresh eyes) and
is no longer useful. The step episodes carry forward everything the
code reviewer needs.
