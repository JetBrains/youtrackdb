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
3. Commit this update to the step file.

This only needs to happen once per track — skip if `## Base commit` already
has a SHA (e.g., when resuming Phase B after a mid-phase checkpoint).

---

## Step Completion Gate

**A step is not complete until all six sub-steps below are done.** Do NOT
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
- Starting Step N+1 code before Step N's episode is committed
- Batching code reviews across multiple steps
- Batching episodes across multiple steps ("retroactive" episodes)
- Running tests in the background and continuing to the next step
  without waiting for results

---

## Per-Step Workflow

For each step in the step file, execute sub-steps 1–6 **in order, to
completion**, before moving to the next step:

1. **Implement the code** with defensive assertions generously (without
   performance penalty).
2. **Write tests**, ensure all tests pass, ensure 85% line / 70% branch
   coverage using JaCoCo (triggered by `coverage` Maven profile). Run clean
   phase for each Maven command. **Wait for test results before proceeding.**
3. **Stage and commit** the code changes. You know exactly which files you
   modified, so stage them explicitly (no `git add -A`). Follow the project's
   commit message conventions (see `CLAUDE.md`).
4. **Code review loop** (up to 3 iterations, within your context):
   a. Spawn a **code-reviewer sub-agent** (fresh sub-agent each iteration).
   b. If findings are returned, fix them, commit fixes, and re-submit.
   c. Repeat until approved OR **max 3 iterations** reached.
   d. If max iterations reached, note remaining findings in the episode.
5. **Produce the step episode** — a structured record of what happened
   (see conventions-execution.md §2.2 for format). Write it to the step file
   under the step item. Mark the step as `[x]`. Update the **Progress**
   section's `Step implementation` count (e.g., `(3/5 complete)`). If this is
   the last step, mark `Step implementation` as `[x]`. Commit the episode as
   a **separate episode commit**.
6. **Cross-track impact check** — quick self-assessment against the plan:
   - Does this step contradict assumptions in upcoming tracks?
   - Does it affect the Component Map or Decision Records?
   - Does it invalidate remaining track dependencies?
   Alert the user only if impact is detected (see workflow.md §Cross-Track
   Impact Monitoring for the full escalation process).

**→ GATE: Step is now complete. Proceed to the next step.**

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

Write the episode to the step file and commit it as a separate episode commit.
See `conventions-execution.md` §2.2 (Commit and episode ordering) for the
rationale behind separate episode commits.

---

## Step Failure

If you encounter a fundamental issue you cannot resolve within the step's
scope (wrong API assumption, tests cannot pass, code reviewer finds
architectural problems, coverage cannot be met):

1. Revert any uncommitted changes (`git checkout -- .`)
2. Produce a **failed episode** (see conventions-execution.md §2.2)
3. Write the failed episode to the step file and commit it
4. Decide: **retry** with a different approach, or **split** the step into
   smaller pieces that can succeed independently

---

## Two-Failure Rule

If the same step fails twice (original attempt + one retry):

- **Stop.** Do not attempt a third time.
- Present both failed episodes to the user with:
  - What was tried each time
  - Why it failed
  - Your assessment of whether this is a step-level issue or a track-level
    issue
- The user decides: retry with specific guidance, adjust the approach, skip
  the step, or escalate.

See workflow.md §Failure Handling for the broader failure handling context
and track-level escalation rules.

---

## Mid-Phase Checkpoint

If you've completed 5+ steps and more remain, suggest ending the session
to shed accumulated context:

- Ensure all episodes are written and committed
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
   Commit the step file update.
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
