# Track Execution — Phase C: Track-Level Code Review

After all steps are committed, spawn a **sub-agent** to review the full track
diff. This is deliberately a sub-agent — fresh eyes catch systematic issues
that you (as the implementer) are blind to.

---

## Phase C Startup

1. Read the step file's `## Base commit` section to get the SHA recorded
   at the start of Phase B.
2. Use this as `{base_commit}` when spawning the code review sub-agent.
   The sub-agent reviews `git diff {base_commit}..HEAD`.

If `## Base commit` is missing (e.g., older step file format), fall back to
finding the parent of the first step's commit via git log.

---

## Sub-agent prompt

**Prompt file:** [`prompts/track-level-code-review.md`](prompts/track-level-code-review.md)

## Review loop

1. If the sub-agent returns findings that need fixes:
   - Apply fixes as **additional commits** (never amend prior commits)
   - Run tests to verify fixes don't break anything
   - **Update the Progress section** to record the completed iteration
     (e.g., `- [ ] Track-level code review (1/3 iterations)`) and commit
     this update together with the fix commits. This ensures the iteration
     count survives session interruptions.
   - Spawn a fresh sub-agent to verify (gate check)
2. Max 3 iterations **total across sessions** — on resume, read the
   iteration count from the Progress section to determine how many remain.
3. If blockers persist after 3 iterations, note them — they'll be presented
   to the user during track review (workflow.md §Track Completion Protocol)
4. When the review passes (or max iterations reached), mark
   `Track-level code review` as `[x]` in the step file's Progress section.
   Commit this update.

---

## Phase C Completion

After the track-level code review passes (or max iterations reached):

1. **Verify `Track-level code review` is marked `[x]`** and committed.
2. **Inform the user** that Phase C is complete:
   - Review outcome (passed / passed with noted findings)
   - Any unresolved findings to present during track completion
   - Instruct: "Clear session and re-run `/execute-tracks` to complete
     the track (write track episode, present results)."
3. **End the session.** Do not proceed to track completion in the same
   session.

The next session detects all phases `[x]` and enters the Track Completion
Protocol (workflow.md): compiles the track episode, writes it to the plan
file, marks the track `[x]`, and presents results to the user for approval.
