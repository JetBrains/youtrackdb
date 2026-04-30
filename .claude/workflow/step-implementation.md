# Track Execution — Phase B: Step Implementation

Implement steps sequentially, one at a time. All steps within a track are
implemented in the same Phase B session (or continued across sessions via
mid-phase checkpoints). The step file from Phase A provides the decomposed
steps and review findings.

---

## Tooling — IDE-routed Maven, refactors, and PSI

Phase B is the phase where the user-global routing rules in
`~/.claude/CLAUDE.md` (§"Maven — when to route through mcp-steroid"
and §"Refactoring — IDE refactor vs raw Edit") earn their keep:

- **Single-test reruns** (`-Dtest=Foo#bar` after a focused fix) and
  **compile-fix loops** route through `steroid_execute_code` when
  mcp-steroid is reachable — the IDE returns a parsed test tree and
  filtered compiler output instead of full Maven INFO chatter.
- **Full-suite runs**, **coverage profiles** (`-P coverage` followed by
  `coverage-gate.py`), **integration tests** (`-P ci-integration-tests`),
  **JMH benchmarks**, and anything that runs more than ~5 minutes stay
  on Bash `./mvnw`. The Pre-Commit Verification commands in the
  project `CLAUDE.md` are Bash-only by design.
- **Refactors that touch more than one reference site** — renames,
  moves, signature changes, extract-method, pull-up/push-down — route
  through the IDE refactoring engine via mcp-steroid, not raw `Edit`.
  After an IDE refactor, run Spotless on the affected modules and the
  module's tests; the engine doesn't enforce project formatting.

Reference-accuracy questions during step implementation (e.g., "what
calls this method I'm about to delete", "every caller of this enum
constant", "which subclasses override this") MUST use mcp-steroid PSI
find-usages when the IDE is reachable — see
[`conventions.md`](conventions.md) §1.4 *Tooling discipline* for the
full rule. Run `steroid_list_projects` once at the start of the
session to confirm the open project matches the working tree, then do
not re-probe.

When delegating a step-level code review to sub-agents (sub-step 4
below), the canonical context block includes a PSI instruction; keep
it intact when customising the prompt.

---

## Phase B Startup

Before implementing the first step:

1. **Record the base commit.** Run `git rev-parse HEAD` to get the
   current SHA, then write it to the step file's `## Base commit`
   section (creating the section if it doesn't exist). Skip if
   `## Base commit` already has a SHA (e.g., when resuming Phase B
   after a mid-phase checkpoint).
2. **Generate the slim plan snapshot** at
   `/tmp/claude-code-plan-slim-$PPID.md`. Read the plan, apply the
   rendering rule in [`plan-slim-rendering.md`](plan-slim-rendering.md),
   and write the result. Sub-agents spawned for step-level code review
   will read this snapshot by path — this keeps the main agent's
   tool-call history from accumulating a plan copy per spawn.
   Regenerate the snapshot only if inline replanning (ESCALATE) modifies
   the plan mid-session — Phase B does not otherwise write to the plan
   file.

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

For each step in the step file, execute sub-steps 1–7 **in order, to
completion**, before moving to the next step:

1. **Implement the code** with defensive assertions generously (without
   performance penalty). **If you encounter a design decision** — a choice
   between alternatives that affects architecture, API shape, data
   structures, or behavioral semantics beyond what the plan prescribes —
   **pause and ask the user for guidance** before proceeding (see
   workflow.md §Design decision escalation).
   **Do not reference workflow-internal identifiers** (`Track N`, `Step N`,
   `Track N Step M`, review finding IDs like `CQ33` / `F-12` / `R-4`,
   review iteration counters, or named-only invariants from the plan) in
   **source code comments, Javadoc, test names, or test descriptions**.
   Those identifiers live in untracked files (plan, backlog, step files,
   reviews) that are deleted with the branch, so citing them in committed
   code creates dangling references on `main`. See
   [`conventions-execution.md`](conventions-execution.md) §2.3 for the
   full rule and rewrite examples.
2. **Write tests**, ensure all tests pass, ensure 85% line / 70% branch
   coverage using JaCoCo (triggered by `coverage` Maven profile). Run clean
   phase for each Maven command. **Wait for test results before proceeding.**
3. **Stage and commit** the code changes. You know exactly which files you
   modified, so stage them explicitly (no `git add -A`). Follow the project's
   commit message conventions (see `CLAUDE.md`). The Ephemeral identifier
   rule (see [`conventions-execution.md`](conventions-execution.md) §2.3)
   applies to commit messages — no `Track N` / `Step N` / finding IDs /
   iteration counters in the message body or subject.
4. **Dimensional review loop — runs only when the step's `**Risk:**` line
   is `high`.** For `medium` and `low` steps, skip directly to sub-step 5.
   If implementation revealed that the step is higher risk than tagged
   (e.g., the "trivial refactor" turned out to require lock-ordering
   changes), upgrade the risk in the step file before proceeding — see
   [`risk-tagging.md`](risk-tagging.md) §Override rules for the upgrade
   protocol. Downgrades mid-Phase B are not permitted.

   **Missing `**Risk:**` line.** If the step entry has no `**Risk:**`
   line at all (e.g., the step file was authored before per-step risk
   tagging existed and the session resumes against an older plan),
   treat the step as `high` (safe direction — the cost of an extra
   review is much lower than missing a real high-risk issue) and ask
   the user to confirm or override before running the loop. Record the
   inferred tag in the step's risk note as `high — inferred (no risk
   line on disk; user-confirmed at <date>)` so subsequent phases see a
   normal locked tag.

   When the loop runs (i.e., the step is `high`), it follows the protocol
   unchanged: up to 3 iterations within your context.
   See [`code-review-protocol.md`](code-review-protocol.md) for the two-tier
   protocol overview and [`review-iteration.md`](review-iteration.md) for
   iteration limits, finding ID prefixes, and gate format.
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
      Read the slim plan snapshot at:
        /tmp/claude-code-plan-slim-{PPID}.md
      Filtered view of the plan — completed tracks show title + intro +
      track episode + strategy refresh only; the current track and
      other not-started tracks are shown in full. If the snapshot is
      missing, fall back to docs/adr/{dir-name}/implementation-plan.md.

      ## Track Steps (tactical context)
      Read the step file at:
        docs/adr/{dir-name}/tracks/track-{N}.md
      The file begins with a `## Description` section carrying the
      track's original description — intro paragraph +
      **What/How/Constraints/Interactions** subsections + any
      track-level diagram — copied there at Phase A start. Below
      that, all steps for this track appear with their episodes.

      ## Skip These Files (generated code)
      - core/.../sql/parser/*, generated-sources/*, Gremlin DSL

      ## Tooling
      Use **mcp-steroid PSI find-usages, not grep**, for any
      reference-accuracy question about a Java symbol in the diff or
      its callers (callers/overrides/usages of a method, field, class,
      or annotation; whether a slot has any consumer; whether a
      reference is confined to one component). Grep is acceptable for
      filename globs, unique string literals, and orientation reads,
      but the load-bearing answer behind a finding must be PSI-backed
      when mcp-steroid is reachable. If the SessionStart hook reported
      `mcp-steroid: NOT reachable`, fall back to grep and note the
      reference-accuracy caveat in any finding that depends on a
      symbol search.

      ## Diff
      {the step's diff — passed inline since it is the review target}
      ```

      **Why paths, not inline contents.** Inlining `{contents}` for each
      of the plan and track file places a copy of those files in the
      main agent's tool-call history for every sub-agent spawn. Across
      a Phase B session this dominates main-agent context. Paths keep
      the main agent lean; sub-agents Read the files themselves so the
      contents land only in sub-agent context. The diff is the one
      exception — it is the review target, small, and step-specific, so
      it is passed inline.

      The main agent substitutes `{PPID}`, `{dir-name}`, and `{N}` with
      concrete values when composing each prompt.
   b. **Synthesize**: After all selected agents complete, deduplicate findings across
      dimensions and prioritize (blocker > should-fix > suggestion). Merge
      findings that multiple agents flagged for the same code location.
   c. If findings need fixes, fix them, commit fixes (using the
      `Review fix:` prefix — see `commit-conventions.md`), and re-run
      **only the dimension(s) with open findings**.
   d. Repeat until approved OR **max 3 iterations** reached.
   e. If max iterations reached, note remaining findings in the episode.
5. **Cross-track impact check** — quick self-assessment against the plan.
   See §Cross-Track Impact Check below for the full protocol.
   If minor impact is detected (recommendation: **Continue**), note the
   affected tracks and weakened assumptions — these go into the episode's
   **What was discovered** field in the next sub-step.
   If **Pause and ADJUST** or **ESCALATE** is needed, alert the user
   immediately.
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

### When the failure mode is opaque — consider an IDE debug session

Phase B is a code-execution phase, so it's easy to stay focused on
re-running the build instead of consulting general guidance. This
note is a deliberate in-line reminder.

If the failure is **opaque from the stack trace + test output** —
concurrency hang, unexpected branch taken, mid-operation state
corruption, "wrong value at line N and I don't know why" — and
mcp-steroid is reachable per the SessionStart hook with the project
open in the IDE, fetch `mcp-steroid://debugger/overview` via
`steroid_fetch_resource` and run an IntelliJ debug session: set a
breakpoint, debug-run the failing test, wait for suspend, evaluate
the relevant expressions/fields, step over as needed. Each step is a
separate `steroid_execute_code` call per the overview's failure-
recovery pattern.

**Skip the debugger** for clean assertion failures, compile errors,
or anything where the stack trace already names the bug — adding a
debugger session there is pure overhead. Skip it also when
mcp-steroid is unreachable (`mcp-steroid: NOT reachable` in the
status line) — fall back to print-debugging or an extra assertion in
the test.

After the debug session yields a root cause, capture the finding in
the failed episode's **What was discovered** field and proceed with
the retry / split decision in the steps above.

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

---

## Track-Level Failure

If a failure undermines the track's overall approach (not just one step —
e.g., the track's foundational assumption is wrong, or repeated step
failures trace back to a common root cause the track cannot address):

- Present the situation to the user with full context (affected steps,
  what was tried, the underlying issue).
- Recommend **ESCALATE** if the approach is fundamentally wrong
  (see [`inline-replanning.md`](inline-replanning.md)).
- The user decides how to proceed.

---

## Cross-Track Impact Check

After each step implementation (sub-step 5 of the per-step workflow), do a
lightweight assessment — this is a quick check, not a full strategy
refresh. You have the plan context in your session, so this is a natural
self-check.

For each completed step, assess:

1. **Assumption validity** — Does this discovery contradict assumptions
   in any upcoming track's description?
2. **Architecture impact** — Does this change affect the Component Map
   or Decision Records in ways that touch other tracks?
3. **Dependency ordering** — Does this invalidate the dependency ordering
   of remaining tracks?

### If impact is detected

Alert the user immediately with:

- Which upcoming track(s) are affected.
- What assumption is weakened or invalidated.
- What the step discovered that triggered this alert.
- Recommended action:
  - **Continue** (minor impact — record in the step episode's **What was
    discovered** field so strategy refresh and future track reviews can
    see it; no user notification needed).
  - **Pause and ADJUST** (remaining steps in current track need revision).
  - **ESCALATE** (the discovery fundamentally changes the plan — see
    [`inline-replanning.md`](inline-replanning.md)).

### If no impact is detected

Continue to the next step. No user notification needed.

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

After the last step's episode is written to the step file (and its code
changes committed to git):

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
