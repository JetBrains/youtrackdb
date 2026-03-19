# Track Execution — Phase C: Track-Level Code Review

After all steps are committed, spawn **two sub-agents in parallel** to review
the full track diff. These are deliberately sub-agents — fresh eyes catch
systematic issues that you (as the implementer) are blind to.

1. **Code review sub-agent** (`code-reviewer` agent type) — reviews code
   quality, systematic patterns, integration issues, cross-step consistency.
2. **Test quality review sub-agent** (`test-quality-reviewer` agent type) —
   reviews whether tests are behavior-driven, thorough, and meaningful.

Both reviews run against the same diff (`git diff {base_commit}..HEAD`) and
produce independent findings. Launching them in parallel saves wall-clock time
since they examine different aspects of the changes.

---

## Phase C Startup

1. Read the step file's `## Base commit` section to get the SHA recorded
   at the start of Phase B.
2. Use this as `{base_commit}` when spawning both review sub-agents.
   Both sub-agents review `git diff {base_commit}..HEAD`.

If `## Base commit` is missing (e.g., older step file format), fall back to
finding the parent of the first step's commit via git log.

---

## Sub-agents

### Code review

Use the **code-reviewer** agent type (defined in `.claude/agents/code-reviewer.md`).

Pass the following context in the agent prompt:
- Review mode: specific commit range (`{base_commit}..HEAD`)
- Track description for high-level context
- Step file path for step episode context

The agent's built-in review process handles the rest (code quality, bugs,
concurrency, crash safety, security, performance).

Finding prefix: `C1, C2, ...`

### Test quality review

Use the **test-quality-reviewer** agent type (defined in
`.claude/agents/test-quality-reviewer.md`).

Pass the following context in the agent prompt:
- Review mode: specific commit range (`{base_commit}..HEAD`)
- Track description for high-level context
- Step file path for step episode context

The agent's built-in review process handles the rest (behavior verification,
assertion depth, corner cases, test isolation, Java assert recommendations).

Finding prefix: `Q1, Q2, ...`

### Launching both sub-agents

**Launch both sub-agents in a single message** (parallel tool calls) to
maximize efficiency. Wait for both to complete before proceeding to the
review loop.

Example:
```
Agent(subagent_type="code-reviewer", prompt="Review commit range {base_commit}..HEAD. Track: ...")
Agent(subagent_type="test-quality-reviewer", prompt="Review commit range {base_commit}..HEAD. Track: ...")
```

---

## Review loop

Merge findings from both sub-agents into a single list, preserving their
respective prefixes (`C<N>` for code review, `Q<N>` for test quality).
Then iterate:

1. If either sub-agent returns findings that need fixes:
   - Apply fixes as **additional commits** (never amend prior commits)
   - Run tests to verify fixes don't break anything
   - **Update the Progress section** to record the completed iteration
     (e.g., `- [ ] Track-level code review (1/3 iterations)`) and commit
     this update together with the fix commits. This ensures the iteration
     count survives session interruptions.
   - Spawn **fresh sub-agents** to verify (gate check) — only re-run the
     review type(s) that had open findings. If only test quality findings
     remain, spawn only the test-quality-reviewer; if only code findings
     remain, spawn only the code-reviewer; if both have open findings,
     spawn both in parallel.
2. Max 3 iterations **total across sessions** — on resume, read the
   iteration count from the Progress section to determine how many remain.
   The iteration count is shared across both review types (not independent
   counters).
3. If blockers persist after 3 iterations, note them — they'll be presented
   to the user during track review (workflow.md §Track Completion Protocol)
4. When both reviews pass (or max iterations reached), mark
   `Track-level code review` as `[x]` in the step file's Progress section.
   Commit this update.

---

## Phase C Completion

After both track-level reviews pass (or max iterations reached):

1. **Verify `Track-level code review` is marked `[x]`** and committed.
2. **Inform the user** that Phase C is complete:
   - Review outcomes for both code review and test quality review
     (passed / passed with noted findings)
   - Any unresolved findings to present during track completion
   - Instruct: "Clear session and re-run `/execute-tracks` to complete
     the track (write track episode, present results)."
3. **End the session.** Do not proceed to track completion in the same
   session.

The next session detects all phases `[x]` and enters the Track Completion
Protocol (workflow.md): compiles the track episode, writes it to the plan
file, marks the track `[x]`, and presents results to the user for approval.
