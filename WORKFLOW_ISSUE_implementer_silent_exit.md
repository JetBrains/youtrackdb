# Workflow Issue — Implementer can hang on long Maven runs and exit silently

**Observed during:** Track 18, Phase C (`/execute-tracks`), 2026-05-07
**Branch:** `unit-test-coverage`
**Target docs:** `.claude/workflow/implementer-rules.md`,
`.claude/workflow/step-implementation.md` § Implementer Prompt Template,
`.claude/workflow/track-code-review.md` § Phase C Implementer Handlers

## Symptom
The Phase C implementer applied 22 review fixes correctly across 14 test
files, **then exited without returning the structured `RESULT` block**.
On disk it left:
- a dirty tree (uncommitted edits),
- a defunct `[java]` zombie (PID 174546) — the second `mvnw test` fork,
- a runaway `bash -c 'until ! pgrep -f "surefire"; do sleep 5; done'`
  poll loop (PID 181742, parent = orchestrator's bash session) that
  never exits because **`pgrep -f "surefire"` matches its own command
  line** — classic self-referential pgrep bug.

The orchestrator received an Agent completion notification whose
`<result>` was the truncated text *"The test is still running (PID
174546). Let me wait for the monitor task to detect completion:"* — no
`RESULT:` block, no `EPISODE_DRAFT`, no `FIX_NOTES`.

## Root cause
1. **Maven test runs were invoked in foreground** with a 600s Bash
   timeout. Track 18's index-package tests (1500+ tests) take >60s
   each iteration, and after the first run failed (one assertion
   needed adjustment for YouTrackDB's `CoreException` re-format
   quirk), the implementer issued a *second* `./mvnw test` and tried
   to wait for it via a tangle of `Monitor`, `cat <output_file>`, and
   `pgrep`-based poll loops.
2. **Context exhaustion.** The two foreground Maven runs dumped tens
   of thousands of tokens of stack traces into the implementer's
   context (cache_read peaked at ~213k). The implementer wrote the
   F18 fix correctly but ran out of message budget before re-running
   tests + committing, and the agent loop terminated without ever
   producing a `RESULT` block.
3. **No safety net for "implementer ended without RESULT".** The
   Phase C handler dispatch in `track-code-review.md` only handles
   `SUCCESS / DESIGN_DECISION_NEEDED / FAILED / RISK_UPGRADE_REQUESTED`.
   A truncated/missing return is not classified — the orchestrator
   has to recover by hand (manual transcript inspection, kill
   leftover poll loops, decide whether to re-spawn or commit
   directly).

## Suggested workflow changes

The fix should land in **`implementer-rules.md`** plus the relevant
inputs in **`step-implementation.md`** and **`track-code-review.md`**:

1. **Mandatory `run_in_background:true` for long Maven invocations.**
   Add an explicit rule in `implementer-rules.md` that *any* `./mvnw
   test`, `./mvnw verify`, or `./mvnw … -P coverage` invocation MUST
   use `run_in_background:true` and be awaited via `TaskOutput`,
   never via foreground Bash + timeout. List the allowed and
   forbidden patterns side-by-side.
2. **Forbid self-referential `pgrep -f` patterns.** Either ban
   `pgrep -f` poll loops outright in favour of `tail --pid=N` /
   `wait` / `TaskOutput`, or require the pattern to exclude the
   shell's own pid (`pgrep -f surefire | grep -v $$`). The current
   incident's leftover poll would have run forever.
3. **Mandatory `RESULT:` block, even on partial completion.** Add a
   contract clause: if the implementer is about to exit for *any*
   reason (success, failure, context pressure, tool-budget pressure,
   timeout), it must first emit a `RESULT:` block — `FAILED` with a
   `recommended_action: retry` and a one-line reason is acceptable
   ("ran out of message budget after applying N of M findings; tests
   not yet re-run; tree dirty"). Silence is forbidden.
4. **Orchestrator handler for `RESULT_MISSING`.** In
   `track-code-review.md` § Phase C Implementer Handlers (and the
   step-level analogue in `step-implementation.md`), add a fifth
   dispatch path: when the Agent return text contains no
   `RESULT:` block, treat it as a contract violation. Recovery:
   - kill any background tasks the implementer left alive,
   - inspect the tree (`git status --short`, `git diff --stat`),
   - present the situation to the user with three options
     (re-spawn finalizer / commit-as-is / discard).
5. **Pre-spawn budget guidance.** When the orchestrator's findings
   list is large (≥ ~15 items) or spans many files (≥ ~10), split
   into multiple iterations rather than one mega-iteration — the
   incident shows that a 22-finding spawn is at the edge of an Opus
   sub-agent's working budget once verification stack traces are
   added.

## Reproducer
1. Spawn a Phase C track-level implementer with ≥20 review findings
   touching ≥10 test files in a Maven module whose test suite takes
   >60 s.
2. Have the prompt require a final `./mvnw test` verification but
   omit the explicit "use `run_in_background:true`" instruction.
3. Observe: implementer applies fixes, kicks off mvnw test in
   foreground, hits Bash timeout, retries, eventually exhausts its
   message budget, and the Agent return text ends mid-sentence with
   no `RESULT:` block.

## Out of scope for this issue
The Track-18 review fixes themselves are correct on disk — the F18
`IndexEngineException` assertion has already been adjusted to
`assertTrue + contains` with a `WHEN-FIXED` note. Recovery of that
specific track is being handled by the orchestrator separately; this
issue is purely about the workflow gap.
