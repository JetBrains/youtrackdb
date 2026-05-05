# Track Execution — Phase B: Step Implementation (Orchestrator)

Phase B is a **two-actor phase**:

- The **orchestrator** (this document) drives the per-step loop:
  spawn the implementer, route on its return, run the dimensional
  review fan-out for `risk: high` steps, run the cross-track impact
  check, run the context check, finalise the step episode, mark the
  step `[x]`, and update the Progress count.
- The **implementer** (a fresh sub-agent spawned per step, see
  [`implementer-rules.md`](implementer-rules.md)) performs sub-steps
  1–3 of step implementation — read the step file, implement the
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

This document covers the **happy path** only: every implementer spawn
returns `RESULT: SUCCESS`, no orphan commits exist at session start,
no step is marked `[!]`. The non-happy-path logic — Phase B Resume,
the three non-`SUCCESS` orchestrator handlers, post-commit rollback
handlers, retry/split formats, the Two-Failure Rule, Track-Level
Failure — lives in
[`step-implementation-recovery.md`](step-implementation-recovery.md)
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

Before spawning the first implementer:

1. **Record the base commit.** Run `git rev-parse HEAD` to get the
   current SHA, then write it to the step file's `## Base commit`
   section (creating the section if it doesn't exist). Skip if
   `## Base commit` already has a SHA (resume case).

   The step file change must be committed before the first
   implementer spawn — the implementer's `git reset --hard HEAD`
   would otherwise discard the `## Base commit` write. Stage and
   commit:

   ```bash
   git add docs/adr/<dir-name>/_workflow/tracks/track-<N>.md
   git commit -m "Record Phase B base commit for <track>"
   git push
   ```

   Skip the commit on resume (when `## Base commit` already had a
   SHA and no write happened).
2. **Generate the slim plan snapshot** at
   `/tmp/claude-code-plan-slim-$PPID.md`. Read the plan, apply the
   rendering rule in [`plan-slim-rendering.md`](plan-slim-rendering.md),
   and write the result. Both the implementer and any dimensional
   review sub-agents read this snapshot by path — it keeps the
   orchestrator's tool-call history from accumulating a plan copy per
   spawn. Regenerate the snapshot only if inline replanning
   (ESCALATE) modifies the plan mid-session. The snapshot lives in
   `/tmp` (not under `_workflow/`) and is not committed.
3. **Detect orphan commits.** Run `git log --oneline
   {base_commit}..HEAD` and inspect the result. If it shows orphan
   implementer commits, orphan `Review fix:` commits, or a
   `Revert step:` commit at the tip, the previous session was
   interrupted mid-step — load
   [`step-implementation-recovery.md`](step-implementation-recovery.md)
   and follow §Phase B Resume there before spawning the first
   implementer. If no orphan commits exist, continue normally.

### Per-step base commit tracking

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
[`step-implementation-recovery.md`](step-implementation-recovery.md)
§Post-Commit Handlers.

The orchestrator captures `step_base_commit` in-memory by running
`git rev-parse HEAD` just before the spawn. On resume, derive it
from git: it is the parent of the first orphan commit for the next
`[ ]` step, or HEAD if no orphans exist.

---

## Step Completion Gate

**A step is not complete until all seven sub-steps of the per-step
workflow are done.** Do NOT spawn the implementer for the next step
until the current step's episode is written to the step file on disk
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

For each `[ ]` step in the step file, run sub-steps 1–8 to
completion before moving to the next step. Sub-steps 1–3 run inside
the implementer; sub-steps 4–7 run on the orchestrator side.

```
result = spawn_implementer(step, mode=INITIAL)
match result.RESULT:
    SUCCESS                  -> on_success(step, result)          # sub-steps 4–7
    DESIGN_DECISION_NEEDED   -> [load recovery] escalate_to_user_then_respawn(step, result)
    RISK_UPGRADE_REQUESTED   -> [load recovery] apply_upgrade_then_decide(step, result)
    FAILED                   -> [load recovery] handle_failure(step, result)
```

Only `on_success` is in this document. The other three handlers are
in
[`step-implementation-recovery.md`](step-implementation-recovery.md)
§Non-`SUCCESS` orchestrator handlers — load it before entering any
of those branches. The implementer prompt template is in
§Implementer Prompt Template below.

---

## Implementer Prompt Template

Each spawn uses `subagent_type: "general-purpose"`. Pick `model` from
the step's risk tag: `risk: low` steps spawn with `model: "sonnet"`;
`risk: medium` and `risk: high` steps spawn with `model: "opus"`. See
[`risk-tagging.md`](risk-tagging.md) §"Risk levels — quick reference"
for the full allocation table. The choice is locked at spawn time and
re-evaluated against the current risk tag on every respawn — a
`low → high` upgrade per
[`risk-tagging.md`](risk-tagging.md) §"Phase B upgrade" automatically
promotes the respawn to Opus, and `WITH_GUIDANCE` / `FIX_REVIEW_FINDINGS`
respawns at the same tag stay on whichever model the tag selects.
Downgrades mid-Phase B are not permitted (see `risk-tagging.md`), so
the model never demotes once a step has run.

The prompt body has a **stable static prefix** followed by the
**per-step variable inputs**. The static block goes first for
predictability and to keep the variable section easy to spot in
transcripts; whether the platform's prompt cache hits across
sub-agent spawns depends on Claude Code's `cache_control` placement
(currently neither documented nor guaranteed for Agent-tool spawns),
so do not rely on caching as a load-bearing optimisation here.

```
## Workflow Context (static — same on every spawn this session)

You are the **per-step implementer** in Phase B of a structured
development workflow. You implement one step (sub-steps 1–3:
implement, test, commit) and return a structured handoff to the
orchestrator. Read the rulebook before starting any work:

  Rulebook: .claude/workflow/implementer-rules.md

The rulebook defines what you do, the three early-return cases
(design decision, risk upgrade, failure), and the return contract
your output must end with. Do not modify the step file, the plan,
the backlog, or any review file — those are the orchestrator's
responsibility.

## Stable inputs (static)

repo_root: {repo_root}
plan_slim_path: /tmp/claude-code-plan-slim-{PPID}.md
step_file_path: docs/adr/{dir-name}/_workflow/tracks/track-{N}.md
design_path: docs/adr/{dir-name}/_workflow/design.md

## Per-step variable inputs

step_index: {step_index}
step_description: {step_description}
risk_tag: {risk_tag}
base_commit: {base_commit}
mode: {INITIAL | WITH_GUIDANCE | FIX_REVIEW_FINDINGS}

# Mode-conditional fields below — only populated when relevant.

Guidance: {populated only when mode == WITH_GUIDANCE}
exploration_notes_echo: {populated only when mode == WITH_GUIDANCE}
findings: {populated only when mode == FIX_REVIEW_FINDINGS}

## Instructions

Read the rulebook at the path above, then perform sub-steps 1–3 for
the step at step_index. Return the structured block defined in the
rulebook's §Return contract. Do not return free-form prose after the
block — the orchestrator parses only the block.
```

The orchestrator substitutes `{repo_root}`, `{PPID}`, `{dir-name}`,
`{N}`, and the per-step variable values when composing each prompt.

**Why the rulebook is referenced by path, not inlined.** Inlining the
rulebook on every spawn would re-embed it in the orchestrator's
tool-call history; passing a path keeps the orchestrator lean. The
implementer reads the rulebook itself, so the contents land only in
sub-agent context.

---

## Orchestrator Handlers

### `on_success(step, result)` — sub-steps 4–7

The implementer's commit is now on disk; `result.COMMIT` is its SHA.
Run sub-steps 4–7 in order.

**Sub-step 4 — Dimensional review loop (only when `step.risk_tag ==
'high'`).** For `medium` and `low` steps, skip directly to sub-step
5. The dimensional review loop follows the protocol unchanged: up to
3 iterations within the orchestrator's context. See
[`code-review-protocol.md`](code-review-protocol.md) for the
two-tier protocol overview and
[`review-iteration.md`](review-iteration.md) for iteration limits,
finding ID prefixes, and gate format.

   a. **Select review agents** based on code characteristics (see
      [`review-agent-selection.md`](review-agent-selection.md)),
      then spawn them in parallel (fresh sub-agents each iteration).
      Baseline agents (4) always run; conditional agents are added
      based on the step description and changed files.

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
      (Component Map). The track steps file provides tactical
      context: what each step does and what was discovered.
      **Episodes** are the blockquoted sections under completed
      steps (starting with `**What was done:**`) — they are
      structured records of implementation outcomes. Use episodes
      to understand intent behind prior steps and check for
      cross-step consistency issues. Severities: **blocker** (must
      fix), **should-fix** (should fix before merge),
      **suggestion** (optional improvement).

      ## Review Target
      Track {N}, Step {M}: {step description}
      Reviewing: commit range {commit}~1..{commit}

      ## Implementation Plan (strategic context)
      Read the slim plan snapshot at:
        /tmp/claude-code-plan-slim-{PPID}.md
      Filtered view of the plan — completed tracks show title +
      intro + track episode + strategy refresh only; the current
      track and other not-started tracks are shown in full. If the
      snapshot is missing, fall back to
      docs/adr/{dir-name}/_workflow/implementation-plan.md.

      ## Track Steps (tactical context)
      Read the step file at:
        docs/adr/{dir-name}/_workflow/tracks/track-{N}.md
      The file begins with a `## Description` section carrying the
      track's original description — intro paragraph +
      **What/How/Constraints/Interactions** subsections + any
      track-level diagram — copied there at Phase A start. Below
      that, all steps for this track appear with their episodes.

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

      ## Diff
      {the step's diff at commit range {commit}~1..{commit} — passed
      inline since it is the review target}
      ```

      **Why paths, not inline contents.** Inlining `{contents}` for
      each of the plan and track file places a copy of those files
      in the orchestrator's tool-call history for every sub-agent
      spawn. Across a Phase B session this dominates orchestrator
      context. Paths keep the orchestrator lean; sub-agents Read the
      files themselves so the contents land only in sub-agent
      context. The diff is the one exception — it is the review
      target, small, and step-specific, so it is passed inline.

      The orchestrator substitutes `{PPID}`, `{dir-name}`, `{N}`,
      `{M}`, and `{commit}` with concrete values when composing each
      prompt.

   b. **Synthesise.** After all selected agents complete,
      deduplicate findings across dimensions and prioritise
      (blocker > should-fix > suggestion). Merge findings that
      multiple agents flagged for the same code location.
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
      ```

      On `SUCCESS`, the implementer's new commit follows
      [`commit-conventions.md`](commit-conventions.md)'s
      `Review fix:` prefix; the new `result.COMMIT` becomes the
      diff target for the next gate check. On any non-`SUCCESS`
      return, load
      [`step-implementation-recovery.md`](step-implementation-recovery.md)
      and hand off to the post-commit handler defined there
      (§Post-Commit Handlers); it rolls the prior commits back and
      re-enters the top-level dispatch with a clean tree at
      `step_base_commit`.
   d. **Re-run only the dimension(s) with open findings** on each
      gate-check iteration. Repeat until approved OR **max 3
      iterations** reached.
   e. If max iterations reached, note remaining findings in the
      `EPISODE_DRAFT` so they appear in the step episode.

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

- Record the result: `safe`, `info`, `warning`, `critical`.
- If the file does not exist or the command fails: this is **not an
  error** — record `unavailable` and treat as `safe`.

**Sub-step 7 — Episode finalisation and step-file write.** Merge
`result.EPISODE_DRAFT` with any cross-track-impact-check
observations from sub-step 5 (those go into the episode's
**What was discovered** field). Write the episode to the step file
under the step item, and write the context-check sub-item with the
measured level:

```markdown
- [x] Step: <description>
  - [x] Context: safe
  > **Risk:** ...
  >
  > **What was done:** ...
```

If sub-step 6's measurement failed, write
`- [x] Context: unavailable`.

Mark the step as `[x]`. Update the **Progress** section's `Step
implementation` count (e.g., `(3/5 complete)`). If this is the last
step, mark `Step implementation` as `[x]`.

**Sub-step 8 — Commit and push the episode.** The step file is
tracked under `_workflow/`, so the episode write produces a dirty
working tree. Commit and push it as a separate workflow-update
commit so the draft PR reflects the new state and so `HEAD` is
clean before the next implementer is spawned (the implementer's
revert path uses `git reset --hard HEAD`, which would otherwise
roll back the unwritten episode):

```bash
git add docs/adr/<dir-name>/_workflow/tracks/track-<N>.md
git commit -m "Record episode for <step description>"
git push
```

See `commit-conventions.md` § Push every commit, and the
"Workflow update" row in § Commit type prefixes for the standard
message form.

**Session-end gate.** After committing the episode: if the context
level was `warning` or `critical`, do NOT spawn the implementer for
the next step. Save all work and ask the user for a session refresh
(see workflow.md §Context Consumption Check).

**→ GATE: Step is now complete.** Do not spawn the next implementer
until sub-steps 1–8 above are done.

---

## Cross-Track Impact Check

After each step (sub-step 5 of the per-step workflow), do a
lightweight assessment against the plan — this is a quick check, not
a full strategy refresh. The orchestrator has the plan context
in-session, so this is a natural self-check, fed by
`result.CROSS_TRACK_HINTS`.

For each completed step, assess:

1. **Assumption validity** — Does this discovery contradict
   assumptions in any upcoming track's description?
2. **Architecture impact** — Does this change affect the Component
   Map or Decision Records in ways that touch other tracks?
3. **Dependency ordering** — Does this invalidate the dependency
   ordering of remaining tracks?

### If impact is detected

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
    see [`inline-replanning.md`](inline-replanning.md)).

### If no impact is detected

Continue to the context check (sub-step 6). No user notification
needed.

---

## Episode Production

Episodes are produced by the orchestrator from the implementer's
`result.EPISODE_DRAFT`, merged with cross-track-impact-check
observations from sub-step 5. The implementer never writes the
episode to disk — that is the orchestrator's responsibility.

The episode includes:

- **What was done** — factual summary from `EPISODE_DRAFT.what_was_done`.
- **What was discovered** — unexpected findings; merge
  `EPISODE_DRAFT.what_was_discovered` with any cross-track impact
  observations.
- **What changed from the plan** — deviations from
  `EPISODE_DRAFT.what_changed_from_plan`, naming affected future
  steps.
- **Key files** — files created/modified from `result.FILES_TOUCHED`.
- **Critical context** — from `EPISODE_DRAFT.critical_context`. Use
  sparingly.

Write the episode to the step file on disk. Detailed format and
examples live in
[`episode-format-reference.md`](episode-format-reference.md).

The failed-episode format (`[!]` entry, `**What was attempted:**` /
`**Why it failed:**` fields), retry/split row insertion, and the
Two-Failure Rule live in
[`step-implementation-recovery.md`](step-implementation-recovery.md)
— load it when handling a `RESULT: FAILED` return.

---

## Phase B Completion

After the last step's episode is written to the step file (and its
code changes committed to git):

1. **Mark `Step implementation` as `[x]`** in the Progress section.
2. **Inform the user** that Phase B is complete:
   - How many steps were implemented (including any failed/retried;
     count from `[!]` and retry rows in the step file if recovery
     was used).
   - Key discoveries from step episodes.
   - Any unresolved code review findings.
   - Instruct: "Clear session and re-run `/execute-tracks` to start
     Phase C (track-level code review)."
3. **End the session.** Do not proceed to Phase C in the same
   session.

**Why.** Phase B accumulates implementation-flavoured context — the
implementer's commits, debugging history, workaround decisions, test
fixtures filtered through the orchestrator's handlers. This context
would bias the track-level code reviewer (who needs fresh eyes) and
is no longer useful. The step episodes carry forward everything the
code reviewer needs.
