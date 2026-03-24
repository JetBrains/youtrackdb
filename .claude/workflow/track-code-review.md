# Track Execution — Phase C: Track-Level Code Review

After all steps are committed, spawn **ten sub-agents in parallel** to review
the full track diff. These are deliberately sub-agents — fresh eyes catch
systematic issues that you (as the implementer) are blind to.

**Five dimensional code review agents** (each reviews code from its own
perspective):
1. `review-code-quality` — code quality, conventions, readability
2. `review-bugs-concurrency` — bugs, logic errors, concurrency, resource leaks
3. `review-crash-safety` — WAL correctness, durability, crash recovery
4. `review-security` — injection, auth, data exposure, dependencies
5. `review-performance` — algorithmic complexity, allocations, lock contention

**Five dimensional test quality agents** (each reviews tests from its own
perspective):
6. `review-test-behavior` — behavior-driven quality, assertion precision, exception testing
7. `review-test-completeness` — corner cases, boundary conditions, test data quality
8. `review-test-structure` — isolation, independence, readability, documentation
9. `review-test-concurrency` — concurrent behavior testing quality
10. `review-test-crash-safety` — crash/recovery test quality, production assert statements

All ten reviews run against the same diff (`git diff {base_commit}..HEAD`)
and produce independent findings. Launching them in parallel saves
wall-clock time since they examine different aspects of the changes.

---

## Phase C Startup

1. Read the step file's `## Base commit` section to get the SHA recorded
   at the start of Phase B.
2. Read the **implementation plan** (`docs/adr/<dir-name>/implementation-plan.md`)
   — this provides strategic context (goals, architecture notes, decision
   records, track episodes from completed tracks).
3. Read the **step file** (`docs/adr/<dir-name>/tracks/track-N.md`) — this
   provides the step descriptions and episodes.
4. Use `{base_commit}` when spawning all review sub-agents.
   All sub-agents review `git diff {base_commit}..HEAD`.

If `## Base commit` is missing (e.g., older step file format), fall back to
finding the parent of the first step's commit via git log.

---

## Sub-agents

### Context passed to all sub-agents

Every sub-agent receives the same context block. Assemble it once and
include it in each agent's prompt:

```
## Review Target
Track {N}: {track title}
Reviewing commit range: {base_commit}..HEAD

## Implementation Plan (strategic context)
{contents of implementation-plan.md}

## Track Steps (tactical context)
{contents of tracks/track-N.md — all steps with their episodes}

## Changed Files
{output of git diff {base_commit}..HEAD --name-only}

## Skip These Files (generated code)
- core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/*
- Any files under generated-sources/ or generated-test-sources/
- Generated Gremlin DSL classes

## Diff
{output of git diff {base_commit}..HEAD}
```

### Five dimensional code review agents

Launch all five using the Agent tool with the respective `subagent_type`:

| Agent | `subagent_type` | Finding prefix |
|---|---|---|
| Code quality | `review-code-quality` | `CQ1, CQ2, ...` |
| Bugs & concurrency | `review-bugs-concurrency` | `BC1, BC2, ...` |
| Crash safety | `review-crash-safety` | `CS1, CS2, ...` |
| Security | `review-security` | `SE1, SE2, ...` |
| Performance | `review-performance` | `PF1, PF2, ...` |

### Five dimensional test quality agents

Launch all five using the Agent tool with the respective `subagent_type`:

| Agent | `subagent_type` | Finding prefix |
|---|---|---|
| Test behavior | `review-test-behavior` | `TB1, TB2, ...` |
| Test completeness | `review-test-completeness` | `TC1, TC2, ...` |
| Test structure | `review-test-structure` | `TS1, TS2, ...` |
| Test concurrency | `review-test-concurrency` | `TX1, TX2, ...` |
| Test crash safety | `review-test-crash-safety` | `TY1, TY2, ...` |

Each agent's prompt is:

```
Review the following code changes from your specialized perspective.

{context block from above}
```

### Launching all sub-agents

**Launch all ten sub-agents in a single message** (parallel tool calls) to
maximize efficiency. Wait for all to complete before proceeding to
synthesis.

---

## Synthesis

After all ten sub-agents complete, produce a unified findings list:

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

Iterate on the synthesized findings:

1. If any findings need fixes:
   - Apply fixes as **additional commits** (never amend prior commits)
   - Run tests to verify fixes don't break anything
   - **Update the Progress section** to record the completed iteration
     (e.g., `- [ ] Track-level code review (1/3 iterations)`) and commit
     this update together with the fix commits. This ensures the iteration
     count survives session interruptions.
   - Spawn **fresh sub-agents** to verify (gate check) — only re-run the
     review dimension(s) that had open findings. For example, if only
     crash-safety code findings and test-completeness findings remain,
     spawn only `review-crash-safety` and `review-test-completeness`.
     If findings span all dimensions, re-run all ten.
   - **Context consumption check** (after each iteration, except the
     last): run `cat /tmp/claude-code-context-usage-$PPID.txt`. If the
     level is `warning` (≥25%) or `critical` (≥40%), do NOT start the
     next iteration. Save all work (update Progress section with current
     iteration count, commit) and ask the user for a session refresh
     (see workflow.md §Context Consumption Check). If the file does not
     exist or the level is `safe`/`info`, continue to the next iteration.
2. Max 3 iterations **total across sessions** — on resume, read the
   iteration count from the Progress section to determine how many remain.
   The iteration count is shared across all review dimensions (not
   independent counters).
3. If blockers persist after 3 iterations, note them — they'll be presented
   to the user during track review (workflow.md §Track Completion Protocol)
4. When all reviews pass (or max iterations reached), mark
   `Track-level code review` as `[x]` in the step file's Progress section.
   Commit this update.

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

2. **Commit plan changes** — commit the updated `implementation-plan.md`
   as a separate commit. Reference the finding IDs in the commit message
   so the plan correction is traceable to the review that motivated it.

If no findings were deferred, skip this section.

---

## Phase C Completion

After all track-level reviews pass (or max iterations reached):

1. **Verify `Track-level code review` is marked `[x]`** and committed.
2. **Inform the user** that Phase C is complete:
   - Review outcomes across all code and test quality dimensions
     (passed / passed with noted findings)
   - Any unresolved findings to present during track completion
   - Instruct: "Clear session and re-run `/execute-tracks` to complete
     the track (write track episode, present results)."
3. **End the session.** Do not proceed to track completion in the same
   session.

The next session detects all phases `[x]` and enters the Track Completion
Protocol (workflow.md): compiles the track episode, writes it to the plan
file, marks the track `[x]`, and presents results to the user for approval.
