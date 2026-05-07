---
severity: high
phase: phase-b
source-session: 2026-05-07 /execute-tracks unit-test-coverage
---

# Implementer launches parallel `./mvnw` processes while diagnosing test failures

## Symptom

During the Phase B implementation of Track 19 Step 1 — specifically while
debugging a `CollectionBasedStorageConfigurationTest` JVM crash — the
implementer (general-purpose sub-agent) dispatched **multiple concurrent**
`./mvnw -pl core test -Dtest=…` invocations in the background. The
parallel runs collided in the same worktree, hit OOM secondary crashes
(both 4 GB heap forks running simultaneously on a 33 GB host), produced
mixed-up output between two `/tmp/test-run-track19-step1-*.log` files,
and eventually the implementer exited without emitting a parsable
`RESULT` block. The orchestrator had to recover by inspecting git state
and re-running the implementer's verification work directly.

The agent's transcript explicitly recognizes the violation:

> "Multiple background processes are running simultaneously! This violates
> the … Two concurrent Maven test processes are running. I need to wait
> for them to finish without starting another one."

…but only after the damage was done.

## Reproduction context

- Phase: phase-b
- Workflow doc(s) involved: `.claude/workflow/implementer-rules.md`
  (the rulebook does not forbid concurrent test launches);
  `.claude/workflow/step-implementation.md` (the orchestrator-side spawn
  contract assumes a single in-flight test verification per implementer)
- Tool / sub-agent involved: implementer sub-agent
  (`subagent_type: general-purpose`, model: sonnet for `risk: low` step)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: the implementer encounters a test that fails for a
  non-obvious reason (in this case, JVM aborts with "VM crash or
  System.exit called?" before any test runs) and enters a "try again,
  inspect, try again" loop. Each retry dispatches a new background
  `./mvnw` invocation without confirming the previous one finished.

The session-start CLAUDE.md does state:

> "NEVER run multiple test processes simultaneously in the same
> worktree/directory."

…but that constraint is in the project's `CLAUDE.md` § Testing, not in
the implementer rulebook. The implementer reads its rulebook at spawn
time and treats it as authoritative; the project CLAUDE.md is loaded
into the parent conversation but is not echoed into the sub-agent
context unless something explicitly references it.

## Why it's a problem

Three downstream effects, all costly:

1. **Phantom crashes.** Two concurrent surefire forks compete for the
   same heap and cache files. One process kills the other (or hits
   OOM). The implementer mis-attributes these secondary crashes to its
   test code and adds spurious "fixes" — the JVM-shutdown leak fix
   applied during this session was correct in principle but did not
   resolve the apparent crashes the implementer was actually seeing
   (those were the parallel-launch collisions).
2. **Wasted time.** Each surefire fork takes 1–16 min to settle.
   Concurrent runs do not parallelise — they slow each other down via
   I/O contention and collide in `target/surefire/`. A 30-minute
   diagnostic loop turns into 90 minutes.
3. **Silent exit.** The implementer's transcript ended with "I'll wait
   for the build to complete" without a `RESULT` block. The
   orchestrator's recovery path (already partially codified after a
   prior session) handled it, but the recovery cost two extra
   round-trips and required reading the implementer's transcript by
   hand.

## Proposed fix

Edit `.claude/workflow/implementer-rules.md` to add an explicit
"single in-flight build" rule under the verification section
(approximately the §Sub-step 2 area where the implementer runs tests):

> **Single in-flight build.** Never launch a `./mvnw` test invocation
> while a previous one is still running in the same worktree. The
> project's `CLAUDE.md` § Testing forbids parallel test processes
> because of file-locking, classloader, and direct-memory contention.
> If a previous run did not produce a clear pass/fail signal, **wait
> for it to finish or kill it** — `pgrep -af mvnw` plus `kill -TERM`
> on the leftover PIDs — before launching another. When in doubt,
> assume the previous run is still active.

Optionally also add a recipe for "diagnosing a test that aborts the
surefire JVM": the standard playbook should be (a) run once with full
output captured to a file, (b) wait for completion, (c) inspect the
diagnostic block (deadlock-watchdog stack, surefire dump, hs_err.log)
**before** retrying. This prevents the "try again, hope it works"
spiral that triggered the parallel launches in the first place.

A complementary smaller fix: have the orchestrator's spawn template
include a one-line reminder — *"Single in-flight `./mvnw` per
worktree; consult project `CLAUDE.md` § Testing"* — so the implementer
sees the rule explicitly even without re-reading the rulebook.

## Acceptance criteria

- `.claude/workflow/implementer-rules.md` has a "Single in-flight
  build" subsection (or equivalent), including the `pgrep -af mvnw` /
  `kill -TERM` recovery procedure.
- Either the orchestrator's implementer prompt template
  (`step-implementation.md` § Implementer Prompt Template) carries a
  one-line reminder of the rule, or the rulebook's prelude is the
  first thing the implementer reads on every spawn.
- Optional: a "Test JVM aborts before any test runs" diagnosis recipe
  in `.claude/docs/mcp-steroid/recipes.md` or
  `.claude/workflow/step-implementation-recovery.md` that scripts the
  full-output-then-inspect playbook.
- Regression check: a future Phase B session that hits a JVM-abort
  test failure produces a single linear `./mvnw` history with one
  run at a time.
