# Track Execution — Phase B: Step Implementation

Implement steps sequentially, one at a time. All steps within a track are
implemented in the same Phase B session (or continued across sessions via
mid-phase checkpoints). The step file from Phase A provides the decomposed
steps and review findings.

---

## Phase B Startup

Before implementing the first step, record the current `HEAD` as the base
commit for Phase C's track-level code review:

1. Run `git rev-parse HEAD` to get the current SHA.
2. Write it to the step file's `## Base commit` section (creating the
   section if it doesn't exist).

This only needs to happen once per track — skip if `## Base commit` already
has a SHA (e.g., when resuming Phase B after a mid-phase checkpoint).

---

## Phase B Resume — Incomplete Step Recovery

When resuming Phase B (mid-phase checkpoint or session restart), the next
`[ ]` step may have been **partially completed** in the previous session —
code committed but episode not yet written. This happens when a session ends
between sub-step 3 (code commit) and sub-step 7 (episode writing), e.g., due
to high context consumption or an unexpected session termination.

**Detection:** After identifying the next `[ ]` step, check for orphan
commits — code commits that exist but have no corresponding episode in
the step file on disk:

1. Count the `[x]` steps in the step file. Scan
   `git log --oneline {base_commit}..HEAD` for code commits.
2. If there are more code commits than accounted for by completed steps:
   - The previous session committed code for this step but didn't write
     the episode.
   - **Resume from the appropriate sub-step** by checking commit messages
     (see `commit-conventions.md` for the patterns):
     - If any orphan commit message contains `Review fix:` → the code
       review loop already ran. Skip directly to episode production.
     - If no `Review fix:` commits exist → run the code review loop.
   - Write the episode, mark the step `[x]`, update the Progress count.
   - Then proceed to the next `[ ]` step normally.
3. If no orphan commits exist: implement the step from scratch.

**Why this matters:** Without this check, the agent would attempt to
re-implement code that is already committed, potentially creating
duplicate or conflicting changes.

---

## Step Completion Gate

**A step is not complete until all seven sub-steps below are done.** Do NOT
begin implementing the next step until the current step's episode is
committed and cross-track impact is assessed. This is a hard gate, not a
guideline.

**Why this matters:** Skipping the gate — even for steps that "feel
mechanical" — defeats the workflow's purpose. Code review catches issues
early (before they propagate to later steps). Episodes record discoveries
that inform remaining steps. Cross-track checks catch assumption failures
before they compound. Batching these activities across steps loses all
three benefits.

**Prohibited patterns:**
- Starting Step N+1 code before Step N's episode is written
- Batching code reviews across multiple steps
- Batching episodes across multiple steps ("retroactive" episodes)
- Running tests in the background and continuing to the next step
  without waiting for results

---

## Per-Step Workflow

For each step in the step file, execute sub-steps 1–6 **in order, to
completion**, before moving to the next step:

1. **Implement the code** with defensive assertions generously (without
   performance penalty). **If you encounter a design decision** — a choice
   between alternatives that affects architecture, API shape, data
   structures, or behavioral semantics beyond what the plan prescribes —
   **pause and ask the user for guidance** before proceeding (see
   workflow.md §Design decision escalation).
2. **Write tests**, ensure all tests pass, ensure 85% line / 70% branch
   coverage using JaCoCo (triggered by `coverage` Maven profile). Run clean
   phase for each Maven command. **Wait for test results before proceeding.**
3. **Stage and commit** the code changes. You know exactly which files you
   modified, so stage them explicitly (no `git add -A`). Follow the project's
   commit message conventions (see `CLAUDE.md`).
4. **Dimensional review loop** (up to 3 iterations, within your context):
   a. **Select review agents** based on code characteristics (see
      [`review-agent-selection.md`](review-agent-selection.md)), then spawn
      them in parallel (fresh sub-agents each iteration). Baseline agents
      (4) always run; conditional agents are added based on the step
      description and changed files.

      Each agent receives the same context:
      ```
      ## Workflow Context
      This is a **step-level code review** within a structured development
      workflow. A **track** is a coherent stream of related work within an
      implementation plan; a **step** is a single atomic change (one commit,
      fully tested). You are reviewing one step's diff. The implementation
      plan below provides strategic context: goals, architecture decisions
      (Decision Records), constraints, and component topology (Component Map).
      The track steps file provides tactical context: what each step does and
      what was discovered. **Episodes** are the blockquoted sections under
      completed steps (starting with `**What was done:**`) — they are
      structured records of implementation outcomes. Use episodes to
      understand intent behind prior steps and check for cross-step
      consistency issues. Severities: **blocker** (must fix), **should-fix**
      (should fix before merge), **suggestion** (optional improvement).

      ## Review Target
      Track {N}, Step {M}: {step description}
      Reviewing: uncommitted changes or last commit (as appropriate)

      ## Implementation Plan (strategic context)
      {contents of implementation-plan.md}

      ## Track Steps (tactical context)
      {contents of tracks/track-N.md — all steps with their episodes}

      ## Skip These Files (generated code)
      - core/.../sql/parser/*, generated-sources/*, Gremlin DSL

      ## Diff
      {the step's diff}
      ```
   b. **Synthesize**: After all selected agents complete, deduplicate findings across
      dimensions and prioritize (blocker > should-fix > suggestion). Merge
      findings that multiple agents flagged for the same code location.
   c. If findings need fixes, fix them, commit fixes (using the
      `Review fix:` prefix — see `commit-conventions.md`), and re-run
      **only the dimension(s) with open findings**.
   d. Repeat until approved OR **max 3 iterations** reached.
   e. If max iterations reached, note remaining findings in the episode.
5. **Cross-track impact check** — quick self-assessment against the plan:
   - Does this step contradict assumptions in upcoming tracks?
   - Does it affect the Component Map or Decision Records?
   - Does it invalidate remaining track dependencies?
   If minor impact is detected (recommendation: **Continue**), note the
   affected tracks and weakened assumptions — these go into the episode's
   **What was discovered** field in the next sub-step.
   If **Pause and ADJUST** or **ESCALATE** is needed, alert the user
   immediately (see workflow.md §Cross-Track Impact Monitoring for the
   full escalation process).
6. **Context consumption check** (mandatory, including after the last
   step). Always run it:

   ```bash
   cat /tmp/claude-code-context-usage-$PPID.txt
   ```

   - Record the result: `safe`, `info`, `warning`, `critical`.
   - If the file does not exist or the command fails: this is **not an
     error** — record `unavailable` and treat as `safe`.

7. **Produce the step episode** — a structured record of what happened
   (see conventions-execution.md §2.2 for format). Write it to the step file
   under the step item. Include any cross-track impact notes from sub-step 5
   in the **What was discovered** field. **Write the context check sub-item**
   under the step with the measured level:

   ```markdown
   - [x] Step: <description>
     - [x] Context: safe
     > **What was done:** ...
   ```

   If measurement failed, write `- [x] Context: unavailable`.

   Mark the step as `[x]`. Update the **Progress** section's `Step
   implementation` count (e.g., `(3/5 complete)`). If this is the last step,
   mark `Step implementation` as `[x]`.

   After writing the episode: if the context level was `warning` or
   `critical`, do NOT start the next step. Save all work and ask the user
   for a session refresh (see workflow.md §Context Consumption Check).

**→ GATE: Step is now complete.** Do not begin the next step until all
seven sub-steps above are done.

---

## Episode Production

After committing code (including any review fix commits), produce the
structured episode. You have full context of what you implemented, what you
discovered, and what deviated from the plan.

The episode includes:
- **What was done** — factual summary reconstructed from your changes
- **What was discovered** — unexpected findings (fill this whenever anything
  unexpected is found, even if it didn't block the step)
- **What changed from the plan** — deviations, naming affected future steps
- **Key files** — files created/modified with (new)/(modified) annotations
- **Critical context** — anything essential that doesn't fit above (use
  sparingly)

Write the episode to the step file on disk.

---

## Step Failure

If you encounter a fundamental issue you cannot resolve within the step's
scope (wrong API assumption, tests cannot pass, code reviewer finds
architectural problems, coverage cannot be met):

1. Revert any uncommitted changes (`git checkout -- .`)
2. Produce a **failed episode** (see conventions-execution.md §2.2)
3. Write the failed episode to the step file on disk
4. Decide: **retry** with a different approach, or **split** the step into
   smaller pieces that can succeed independently

### Retry representation in the step file

When retrying a failed step, keep the `[!]` entry in place and insert a
new `[ ]` step immediately after it with a modified description indicating
the different approach:

```markdown
- [!] Step: Add histogram header to leaf page
  > **What was attempted:** ...
  > **Why it failed:** ...
  > **Impact on remaining steps:** ...
  > **Key files:** ...

- [ ] Step: Add histogram header to leaf page (retry: use page extension API)
```

This preserves the full failure history inline and makes the two-failure
rule trivially detectable on resume.

When **splitting** a failed step, keep the `[!]` entry and insert multiple
new `[ ]` steps after it:

```markdown
- [!] Step: Add histogram header and serialization
  > **What was attempted:** ...
  > **Why it failed:** ...

- [ ] Step: Add histogram header struct (split from failed step above)
- [ ] Step: Add histogram serialization (split from failed step above)
```

Update the **Progress** section's step count to reflect the new total
(e.g., `(2/6 complete)` if a 5-step track gained one retry step).

---

## Two-Failure Rule

The two-failure rule triggers when two consecutive `[!]` entries exist
for the same logical step (the retry also failed):

```markdown
- [!] Step: Add histogram header to leaf page
  > **What was attempted:** ... (first attempt)

- [!] Step: Add histogram header to leaf page (retry: use page extension API)
  > **What was attempted:** ... (second attempt)
```

When this happens — whether during a session or detected on resume:

- **Stop.** Do not attempt a third time.
- Present both failed episodes to the user with:
  - What was tried each time
  - Why it failed
  - Your assessment of whether this is a step-level issue or a track-level
    issue
- The user decides: retry with specific guidance, adjust the approach, skip
  the step, or escalate.

**On resume:** When scanning the step list and encountering a `[!]` entry
followed by another `[!]` for the same step (with `(retry:` in the
description), this is a two-failure situation. Present both failed
episodes to the user before proceeding.

See workflow.md §Failure Handling for the broader failure handling context
and track-level escalation rules.

---

## Mid-Phase Checkpoint

If you've completed 5+ steps and more remain, suggest ending the session
to shed accumulated context:

- Ensure all episodes are written to the step file on disk
- Update the **Progress** section's `Step implementation` count
- Inform the user: "Completed N of M steps. Suggest clearing session and
  re-running `/execute-tracks` to resume with fresh context."
- The next session auto-resumes Phase B from the next incomplete step
  (State C in workflow.md §Startup Protocol)

This is a suggestion, not a hard rule. If only 1-2 steps remain, finishing
in the same session is usually better.

---

## Phase B Completion

After the last step's episode is committed:

1. **Mark `Step implementation` as `[x]`** in the Progress section.
2. **Inform the user** that Phase B is complete:
   - How many steps were implemented (including any failed/retried)
   - Key discoveries from step episodes
   - Any unresolved code review findings
   - Instruct: "Clear session and re-run `/execute-tracks` to start
     Phase C (track-level code review)."
3. **End the session.** Do not proceed to Phase C in the same session.

**Why:** Phase B accumulates deep implementation context — variable names,
debugging history, workaround decisions, test fixtures. This context would
bias the track-level code reviewer (who needs fresh eyes) and is no longer
useful. The step episodes carry forward everything the code reviewer needs.
