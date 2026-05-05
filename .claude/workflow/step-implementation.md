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

---

## Phase B Startup

Before spawning the first implementer:

1. **Record the base commit.** Run `git rev-parse HEAD` to get the
   current SHA, then write it to the step file's `## Base commit`
   section (creating the section if it doesn't exist). Skip if
   `## Base commit` already has a SHA (resume case).
2. **Generate the slim plan snapshot** at
   `/tmp/claude-code-plan-slim-$PPID.md`. Read the plan, apply the
   rendering rule in [`plan-slim-rendering.md`](plan-slim-rendering.md),
   and write the result. Both the implementer and any dimensional
   review sub-agents read this snapshot by path — it keeps the
   orchestrator's tool-call history from accumulating a plan copy per
   spawn. Regenerate the snapshot only if inline replanning
   (ESCALATE) modifies the plan mid-session.
3. **Detect orphan commits** per the §Phase B Resume protocol below
   before spawning the first implementer.

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
in the same dim-review loop. See §Post-Commit Handlers.

The orchestrator captures `step_base_commit` in-memory by running
`git rev-parse HEAD` just before the spawn. On resume, derive it
from git: it is the parent of the first orphan commit for the next
`[ ]` step, or HEAD if no orphans exist.

---

## Phase B Resume — Incomplete Step Recovery

When resuming Phase B (session restart), the next `[ ]` step may have
been **partially completed** in the previous session — code committed
but episode not yet written. This happens when a session ends between
the implementer's commit and the orchestrator's episode write, e.g.,
because of a context-level session-end gate or unexpected session
termination.

**Detection.** After identifying the next `[ ]` step, check for orphan
commits — code commits that exist but have no corresponding episode in
the step file on disk:

1. Count the `[x]` steps in the step file. Scan
   `git log --oneline {base_commit}..HEAD` for code commits.
   Each completed step is expected to contribute exactly **one**
   implementer commit plus **zero or more** `Review fix:` commits.
   `Revert step:` commits and the implementer + `Review fix:`
   commits they cancel form a self-contained "rolled back" group
   that does not count toward any `[x]` step's expected commits;
   anything else beyond the expected total is orphaned.
2. If a `Revert step:` commit is present at the tip (or near the
   tip with no implementer commits after it), the previous session
   rolled the next `[ ]` step back via §Post-Commit Handlers but may
   not have finished the orchestrator-side bookkeeping (episode,
   retry/split rows, risk-line rewrite, or user escalation).

   **Read the rollback reason from the commit body.** The first
   non-empty body line is `reason: <slug>` per §Post-Commit Handlers
   "Body format". Branch on the slug:

   **`reason: failed-review-fix`** — the review-fix respawn returned
   `FAILED`.
   - If the step file already has an `[!]` entry for this step,
     the rollback was fully recorded; respawn the implementer for
     the next `[ ]` row from `mode=INITIAL` with `step_base_commit
     = HEAD`.
   - If no `[!]` entry exists, write it now (reconstruct the
     `FAILURE` fields from the prose explanation in the revert body
     plus the git log of the reverted commits), insert retry/split
     rows per the implied `recommended_action` (default to `retry`
     when the revert body does not name one), update Progress, then
     respawn from the retry/split row with `mode=INITIAL`.

   **`reason: late-design-decision`** — the review-fix respawn
   returned `DESIGN_DECISION_NEEDED` and the user had not yet chosen
   an alternative when the session ended.
   - The original `DESIGN_DECISION` payload (alternatives,
     recommendation, exploration_notes) is **not recoverable** from
     the revert body — it was held only in the prior session's
     orchestrator state.
   - The step file is still `[ ]` (no `[!]` is written for this
     case). Respawn the implementer with `mode=INITIAL` and
     `step_base_commit = HEAD`. The new implementer either lands on
     the same design decision (and re-derives the alternatives via
     a fresh `DESIGN_DECISION_NEEDED` return) or finds a different
     path. Either is acceptable — re-derivation is the cost of the
     mid-escalation crash.

   **`reason: late-risk-upgrade`** — the review-fix respawn returned
   `RISK_UPGRADE_REQUESTED`.
   - Read the step's `**Risk:**` line. If it already names the
     upgraded level (the prior session wrote it before dying), no
     further bookkeeping is needed.
   - If the line still names the original level, apply the upgrade
     now: rewrite to the new level (auto-applied for `medium → high`,
     paused for user confirmation on `low → high`) and append an
     override note (`override: upgraded mid-Phase-B during dim review
     (<reason from revert body>)`).
   - Respawn the implementer from `mode=INITIAL` with
     `step_base_commit = HEAD`.

   **Unrecognised slug or missing `reason:` line.** Treat as a
   contract violation: present the revert commit and the step state
   to the user and ask how to proceed. Do not invent a slug.
3. If the commit count exceeds the expected total without a
   `Revert step:` at the tip (one implementer commit + any
   `Review fix:` commits per `[x]` step):
   - The previous session committed code for this step but didn't
     write the episode.
   - **Resume from the appropriate orchestrator handler** by checking
     commit messages (see [`commit-conventions.md`](commit-conventions.md)
     for the patterns):
     - If any orphan commit message contains `Review fix:` → the
       dimensional review loop already ran. Skip directly to
       `on_success` from the cross-track impact check (sub-step 5)
       onward; reconstruct an `EPISODE_DRAFT` from the diff and the
       commit messages.
     - If no `Review fix:` commits exist → the dimensional review
       loop has not yet run for this step. Re-enter `on_success`
       starting at the dimensional review (the implementer's commit
       is already on disk; treat it as the implementer's `SUCCESS`
       return).
   - Then proceed to the next `[ ]` step normally.
4. If no orphan commits exist, spawn the implementer for the next
   `[ ]` step from `mode=INITIAL` with `step_base_commit = HEAD`.

**Why this matters.** Without this check, the orchestrator would
spawn an implementer that re-derives changes already committed,
potentially creating duplicate or conflicting commits.

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
    DESIGN_DECISION_NEEDED   -> escalate_to_user_then_respawn(step, result)
    RISK_UPGRADE_REQUESTED   -> apply_upgrade_then_decide(step, result)
    FAILED                   -> handle_failure(step, result)
```

The four handlers are defined in §Orchestrator Handlers below. The
implementer prompt template is in §Implementer Prompt Template.

---

## Implementer Prompt Template

Each spawn uses `subagent_type: "general-purpose"` with
`model: "opus"`. The prompt has a **stable static prefix** followed
by the **per-step variable inputs**. The static block goes first for
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
          FAILED                   -> rollback_and_handle_failure(
                                          step, fix_result,
                                          step_base_commit)
                                      # exits the dim-review loop
          DESIGN_DECISION_NEEDED   -> rollback_and_escalate(
                                          step, fix_result,
                                          step_base_commit)
                                      # exits the dim-review loop
          RISK_UPGRADE_REQUESTED   -> rollback_and_upgrade(
                                          step, fix_result,
                                          step_base_commit)
                                      # exits the dim-review loop
      ```

      On `SUCCESS`, the implementer's new commit follows
      [`commit-conventions.md`](commit-conventions.md)'s
      `Review fix:` prefix; the new `result.COMMIT` becomes the
      diff target for the next gate check. On any non-`SUCCESS`
      return, the orchestrator hands off to the post-commit handler
      below, which rolls the prior commits back and re-enters the
      top-level dispatch with a clean tree at `step_base_commit`.
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

See `commit-conventions.md` § Push every commit and § Workflow
update for the standard message form.

**Session-end gate.** After committing the episode: if the context
level was `warning` or `critical`, do NOT spawn the implementer for
the next step. Save all work and ask the user for a session refresh
(see workflow.md §Context Consumption Check).

**→ GATE: Step is now complete.** Do not spawn the next implementer
until sub-steps 1–8 above are done.

### `escalate_to_user_then_respawn(step, result)`

Triggered when `result.RESULT == DESIGN_DECISION_NEEDED`. The
implementer has run the snapshot-and-diff revert sequence per
[`implementer-rules.md`](implementer-rules.md) §Detection rules, so
the working tree is clean at `step_base_commit` (no commit was
produced, and no untracked files were left behind).
`result.DESIGN_DECISION` is populated with `context`, `alternatives`,
`recommendation`, and `exploration_notes`.

Verify `git status` is clean before continuing — a dirty tree at
this point is a contract violation; surface the discrepancy to the
user instead of proceeding.

1. Present `result.DESIGN_DECISION` (alternatives + trade-offs +
   recommendation) to the user via the existing escalation protocol
   in [`design-decision-escalation.md`](design-decision-escalation.md).
2. On user response, respawn the implementer with:
   - `mode=WITH_GUIDANCE`
   - `Guidance:` set to the user's chosen alternative + any
     additional direction
   - `exploration_notes_echo` set to
     `result.DESIGN_DECISION.exploration_notes` so the new
     implementer skips re-derivation
3. The respawn's result re-enters the main loop at the
   `match result.RESULT` dispatch.

### `apply_upgrade_then_decide(step, result)`

Triggered when `result.RESULT == RISK_UPGRADE_REQUESTED`. The
implementer has flagged that the step is more invasive than its
tagged risk and has run the snapshot-and-diff revert sequence per
[`implementer-rules.md`](implementer-rules.md) §Detection rules, so
the working tree is clean at `step_base_commit` (no commit was
produced, and no untracked files were left behind).
`result.RISK_UPGRADE` carries `from`, `to`, `category`, and
`evidence`.

Verify `git status` is clean before continuing — a dirty tree at
this point is a contract violation; surface the discrepancy to the
user instead of proceeding.

1. **Apply the upgrade in place** in the step file: rewrite the
   `**Risk:**` line to the new level and append an override note
   in the form `override: upgraded mid-Phase-B (<short reason from
   result.RISK_UPGRADE.evidence>)`. The decomposer-time category
   stays in the line for traceability. Downgrades are not permitted
   — see [`risk-tagging.md`](risk-tagging.md) §Override rules.
2. **Approval flow:**
   - `medium → high`: auto-apply (no user prompt). Note the
     auto-apply in the next step-file write.
   - `low → high`: pause and confirm with the user before
     respawning. The bigger jump is more likely a planning miss.
3. After application/confirmation, respawn the implementer with
   `mode=INITIAL`. The next implementer's run is identical except
   downstream `on_success` will now run the dimensional review.
   No `exploration_notes_echo` is carried across — risk upgrades
   typically surface early, before substantial implementation
   exploration, so re-derivation is cheap and `mode=INITIAL` keeps
   the respawn contract simple. If the prior implementer's
   `RISK_UPGRADE.evidence` already names what was discovered, that
   text plus the rewritten `**Risk:**` line is enough context for
   the next implementer.

### `handle_failure(step, result)`

Triggered when `result.RESULT == FAILED` from `mode=INITIAL` or
`mode=WITH_GUIDANCE` (pre-commit failure). For `FAILED` returns from
`mode=FIX_REVIEW_FINDINGS`, the dim-review loop dispatches to
`rollback_and_handle_failure` instead — see §Post-Commit Handlers.

The implementer has already reverted any uncommitted changes and
removed any untracked artefacts
(the snapshot-and-diff revert sequence) before returning, so the
working tree is clean at `step_base_commit`. `result.FAILURE` carries
`what_was_attempted`, `why_it_failed`, `impact_on_remaining_steps`,
and `recommended_action`.

1. **Write the failed episode** to the step file from
   `result.FAILURE` (mark the step `[!]`).
2. **Insert `[ ]` retry/split rows** per the existing protocol — see
   §Step Failure below for the retry/split formats.
3. **Update the Progress section's step count** to reflect any
   inserted rows.
4. **Two-failure rule.** If the new `[!]` makes two consecutive
   `[!]` entries for the same logical step, stop and present both
   failed episodes to the user — see §Two-Failure Rule below.

The implementer never escalates directly; it returns `FAILED` with
`recommended_action: escalate`, and the orchestrator decides whether
to enter ESCALATE per [`inline-replanning.md`](inline-replanning.md).

---

## Post-Commit Handlers

When the dim-review loop's `mode=FIX_REVIEW_FINDINGS` respawn returns
a non-`SUCCESS` result, the prior step commits are still on disk.
The implementer's local revert
(the snapshot-and-diff revert sequence) only undoes its
in-progress fix attempt; rolling back the prior commits is the
orchestrator's responsibility.

The post-commit handlers all share a common rollback step:

```bash
# step_base_commit was captured at spawn time
git revert -n {step_base_commit}..HEAD
git commit -m "$(cat <<'EOF'
Revert step: <step description>

reason: <slug>

<one-sentence prose explanation, drawn from
fix_result.{FAILURE|DESIGN_DECISION|RISK_UPGRADE}>
EOF
)"
git push
```

The HEREDOC form is required for any commit message containing a blank
line — `git commit -m "…"` with literal embedded newlines is fragile
across shells. This matches the project commit-message convention in
the repo's user-global `CLAUDE.md` ("Committing changes with git"). The
`git push` after the commit follows the per-commit push rule in
`commit-conventions.md` § Push every commit (the revert is part of
the visible branch history on the draft PR).

**Body format (load-bearing for Phase B Resume).** The first
non-empty line of the body MUST be `reason: <slug>` where `<slug>`
is exactly one of three values, used by Phase B Resume to dispatch:

| Slug | Source handler | Resume dispatch |
|---|---|---|
| `failed-review-fix` | `rollback_and_handle_failure` | Write `[!]` if missing, insert retry/split rows, respawn `INITIAL` |
| `late-design-decision` | `rollback_and_escalate` | Respawn `INITIAL`; new implementer re-derives — if it returns `DESIGN_DECISION_NEEDED` again, escalate then |
| `late-risk-upgrade` | `rollback_and_upgrade` | Verify Risk line; apply upgrade if not yet rewritten; respawn `INITIAL` |

A blank line separates the slug line from the prose explanation.
Resume parses the body by reading the first body line and matching
against the slug table — do not vary spelling, capitalisation, or
position.

`git revert -n` stages the reversal of every commit in
`{step_base_commit}..HEAD` (the original implementer commit plus any
prior `Review fix:` commits in the same dim-review loop) without
auto-committing each revert. The orchestrator then commits once with
the `Revert step:` prefix per
[`commit-conventions.md`](commit-conventions.md). After the revert
commit, HEAD's tree state matches `step_base_commit` — the new HEAD
becomes the next step's `step_base_commit` for any respawn that
follows.

The rollback preserves history (the original attempt, any review
fixes, and the revert all stay in `git log`), so future investigators
can see what was tried. This is the conservative choice for a
critical-systems project; squash-merge collapses the noise at the PR
boundary.

**Pre-revert assertion.** Before running `git revert -n`, verify
`git status` is clean. The implementer's snapshot-and-diff revert
sequence should have left it clean;
if not, that is a contract violation and the orchestrator surfaces
the discrepancy to the user instead of proceeding (a dirty tree at
this point usually means the implementer exited mid-write or the
user has manual edits in flight).

### `rollback_and_handle_failure(step, fix_result, step_base_commit)`

Triggered when a `FIX_REVIEW_FINDINGS` respawn returns
`RESULT: FAILED` — the implementer cannot apply the review findings,
typically because the findings reveal a deeper issue than a
mechanical fix can address. `fix_result.FAILURE` carries
`what_was_attempted`, `why_it_failed`, `impact_on_remaining_steps`,
and `recommended_action`.

1. **Run the rollback** (revert + `Revert step:` commit) per the
   common procedure above. The revert lands **before** any step-file
   write so that the only crash-recoverable state is "Revert at tip
   with step still `[ ]`" — Phase B Resume's Detection step 2
   handles that exact state via the `failed-review-fix` slug branch
   (which already covers "if no `[!]` entry exists, write it now").
   Reversing this order — writing `[!]` first — would leave a
   window where prior commits sit unreverted at HEAD with the step
   already marked `[!]`; Detection step 3 would then misattribute
   those orphan commits to the next `[ ]` step.
2. **Write the failed episode** to the step file from
   `fix_result.FAILURE` (mark the step `[!]`). The episode's
   `what_was_attempted` should describe both the original
   implementation and the review-fix attempt that failed; the
   `why_it_failed` field captures the underlying reason.
3. **Insert `[ ]` retry/split rows** per the existing protocol —
   see §Step Failure for the formats.
4. **Update the Progress section's step count** to reflect the
   inserted rows.
5. **Two-failure rule.** If the new `[!]` makes two consecutive
   `[!]` entries for the same logical step, stop and present both
   failed episodes to the user — see §Two-Failure Rule.

After this handler completes, `step_base_commit` for the retry/split
row is the new HEAD (the `Revert step:` commit).

### `rollback_and_escalate(step, fix_result, step_base_commit)`

Triggered when a `FIX_REVIEW_FINDINGS` respawn returns
`RESULT: DESIGN_DECISION_NEEDED` — applying the review findings
surfaced a design decision that was not visible during the original
implementation. `fix_result.DESIGN_DECISION` carries `context`,
`alternatives`, `recommendation`, and `exploration_notes`.

1. **Run the rollback** (revert + `Revert step:` commit) per the
   common procedure above. The rollback removes the original
   implementer commit and any prior `Review fix:` commits because
   the surfaced design decision affects the step's premise — the
   user's chosen alternative may invalidate the original approach,
   so we start the next attempt from a clean slate.
2. **Present** `fix_result.DESIGN_DECISION` (alternatives + trade-offs
   + recommendation) to the user via the existing escalation protocol
   in [`design-decision-escalation.md`](design-decision-escalation.md).
   Include in the prose that this decision surfaced **post-commit
   during dim review**, so the user understands the rollback context.
3. On user response, capture the new HEAD as `step_base_commit` and
   respawn the implementer with:
   - `mode=WITH_GUIDANCE`
   - `Guidance:` set to the user's chosen alternative + any
     additional direction
   - `exploration_notes_echo` set to
     `fix_result.DESIGN_DECISION.exploration_notes`
4. The respawn's result re-enters the top-level `match result.RESULT`
   dispatch in §Per-Step Orchestration Loop.

### `rollback_and_upgrade(step, fix_result, step_base_commit)`

Triggered when a `FIX_REVIEW_FINDINGS` respawn returns
`RESULT: RISK_UPGRADE_REQUESTED` — applying the review findings
revealed the step is more invasive than its tagged risk.
`fix_result.RISK_UPGRADE` carries `from`, `to`, `category`, and
`evidence`.

1. **Run the rollback** (revert + `Revert step:` commit) per the
   common procedure above. The rollback removes the original commit
   so that the next attempt re-runs the implementation **with full
   dim-review pressure from the start** at the new risk level — not
   stacked on top of an implementation that was reviewed under the
   old risk level.
2. **Apply the upgrade in place** in the step file: rewrite the
   `**Risk:**` line and append an override note in the form
   `override: upgraded mid-Phase-B during dim review (<short reason
   from fix_result.RISK_UPGRADE.evidence>)`. The decomposer-time
   category stays for traceability. Downgrades are not permitted —
   see [`risk-tagging.md`](risk-tagging.md) §Override rules.
3. **Approval flow** (same as `apply_upgrade_then_decide`):
   - `medium → high`: auto-apply (no user prompt). Note the
     auto-apply in the step-file write.
   - `low → high`: pause and confirm with the user before respawning.
4. After application/confirmation, capture the new HEAD as
   `step_base_commit` and respawn the implementer with `mode=INITIAL`.
   The respawn's result re-enters the top-level dispatch.

### Why rollback in all three cases?

The post-commit handlers always rollback rather than try to
patch-on-top because:

- **`FAILED`**: by definition the prior commit cannot be salvaged;
  retry/split needs a clean starting point.
- **`DESIGN_DECISION_NEEDED`**: the user's chosen alternative may
  invalidate the prior implementation. Starting fresh avoids
  carrying assumptions from the old approach into a new one.
- **`RISK_UPGRADE_REQUESTED`**: the prior implementation was reviewed
  under the wrong risk level. Re-implementing with full dim-review
  pressure from the start is the only way to get the review pressure
  the upgraded risk demands.

The cost is one rerun of the implementation. For a database project,
the alternative — leaving an under-reviewed commit on disk and
trying to patch it — is the wrong tradeoff.

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

---

## Step Failure

If the implementer returns `RESULT: FAILED`, the implementer has
already reverted uncommitted changes and removed any untracked
artefacts (the snapshot-and-diff revert sequence). For
pre-commit failures (`mode=INITIAL` / `mode=WITH_GUIDANCE`) the
working tree is now clean at `step_base_commit` and the orchestrator
proceeds directly with the steps below. For post-commit failures
(`mode=FIX_REVIEW_FINDINGS`) the orchestrator first runs the
rollback per §Post-Commit Handlers, then proceeds with the steps
below; the retry/split rows below apply to both. The orchestrator
handles the rest:

1. **Write a failed episode** to the step file from
   `result.FAILURE` (see
   [`episode-format-reference.md`](episode-format-reference.md) for
   the failed-episode format).
2. **Decide retry vs split** based on
   `result.FAILURE.recommended_action`:
   - `retry` — keep the `[!]` entry and insert one new `[ ]` step
     immediately after it with a modified description indicating
     the different approach.
   - `split` — keep the `[!]` entry and insert multiple new `[ ]`
     steps immediately after it.
   - `escalate` — present the situation to the user and consider
     entering ESCALATE per [`inline-replanning.md`](inline-replanning.md).
3. **Update the Progress section's step count** to reflect inserted
   rows.

### Retry representation in the step file

```markdown
- [!] Step: Add histogram header to leaf page
  > **What was attempted:** ...
  > **Why it failed:** ...
  > **Impact on remaining steps:** ...
  > **Key files:** ...

- [ ] Step: Add histogram header to leaf page (retry: use page extension API)
```

### Split representation in the step file

```markdown
- [!] Step: Add histogram header and serialization
  > **What was attempted:** ...
  > **Why it failed:** ...

- [ ] Step: Add histogram header struct (split from failed step above)
- [ ] Step: Add histogram serialization (split from failed step above)
```

Update the **Progress** section's step count to reflect the new
total (e.g., `(2/6 complete)` if a 5-step track gained one retry
step).

---

## Two-Failure Rule

The two-failure rule triggers when two consecutive `[!]` entries
exist for the same logical step (the retry also failed):

```markdown
- [!] Step: Add histogram header to leaf page
  > **What was attempted:** ... (first attempt)

- [!] Step: Add histogram header to leaf page (retry: use page extension API)
  > **What was attempted:** ... (second attempt)
```

When this happens — whether during a session or detected on resume:

- **Stop.** Do not spawn another implementer.
- Present both failed episodes to the user with:
  - What was tried each time
  - Why it failed
  - Your assessment of whether this is a step-level issue or a
    track-level issue
- The user decides: retry with specific guidance, adjust the
  approach, skip the step, or escalate.

**On resume.** When scanning the step list and encountering a `[!]`
entry followed by another `[!]` for the same step (with `(retry:`
in the description), this is a two-failure situation. Present both
failed episodes to the user before proceeding.

---

## Track-Level Failure

If a failure undermines the track's overall approach (not just one
step — e.g., the track's foundational assumption is wrong, or
repeated step failures trace back to a common root cause the track
cannot address):

- Present the situation to the user with full context (affected
  steps, what was tried, the underlying issue).
- Recommend **ESCALATE** if the approach is fundamentally wrong
  (see [`inline-replanning.md`](inline-replanning.md)).
- The user decides how to proceed.

---

## Phase B Completion

After the last step's episode is written to the step file (and its
code changes committed to git):

1. **Mark `Step implementation` as `[x]`** in the Progress section.
2. **Inform the user** that Phase B is complete:
   - How many steps were implemented (including any failed/retried).
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
