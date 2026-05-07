---
severity: medium
phase: phase-b
source-session: 2026-05-07 /execute-tracks unit-test-coverage
---

# `tail -N` log filters clip the diagnostic block when a test aborts the JVM

## Symptom

While diagnosing the `CollectionBasedStorageConfigurationTest` JVM
abort during Phase B of Track 19 Step 1, the orchestrator initially
captured surefire output with:

```bash
./mvnw -pl core test -Dtest=CollectionBasedStorageConfigurationTest 2>&1 | tail -100
```

The trailing 100 lines contained only the surefire-plugin error
banner and the maven-launcher stack trace — i.e., the post-mortem
boilerplate that any failed surefire run produces. The actual root
cause — `JUnitTestListener`'s deadlock-watchdog stack dump showing
`pool-3-thread-1` blocked in `ScalableRWLock.sharedLock`, called from
`CollectionBasedStorageConfiguration.setMinimumCollections` — sat
**hundreds of lines earlier** in the output, well outside the `-100`
window.

The orchestrator then re-ran the same command without the `tail`
filter (capturing the full ~480-line output), located the watchdog
diagnostic block, and finally identified the deadlock as the root
cause. Cost: one extra 16-minute test cycle.

## Reproduction context

- Phase: phase-b (also relevant to phase-a Phase A test-runs and any
  `/code-review`-driven test verification)
- Workflow doc(s) involved:
  - `.claude/workflow/step-implementation.md` (per-step verification
    block does not prescribe full vs. tail capture)
  - `.claude/workflow/implementer-rules.md` (verification step is
    silent on diagnostic capture)
  - Project `CLAUDE.md` § Pre-Commit Verification (mentions the test
    commands but not how to capture output for diagnosis)
- Tool / sub-agent involved: `Bash` tool, `./mvnw` test runs
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: a test fails in a way that does NOT produce an
  exception or assertion failure visible in surefire's normal report —
  JVM abort, deadlock-watchdog halt, OOM, native crash. The diagnostic
  block (watchdog stack, surefire dump, `hs_err.log` reference) lives
  mid-output, not at the tail.

## Why it's a problem

Tail-clipping turns a deterministic 1-cycle root-cause into a 2- or
3-cycle "try, retry with full capture, then think" loop. Each cycle
in the `core` module's sequential-tests phase is 1–16 minutes
depending on which categories run; multiple cycles per diagnosis add
up across a Phase B session.

Worse, the implementer in this session mis-attributed the
"BUILD FAILURE / VM crash or System.exit called?" tail to a
direct-memory leak (the only hypothesis the tail supports — the
`directMemory.trackMode=true` argLine flag is in plain sight) and
applied a fix that targeted the wrong root cause. The tail-clipped
output was load-bearing for a wrong diagnosis.

## Proposed fix

Add a small "Capturing test output for diagnosis" recipe in
`.claude/workflow/step-implementation.md` (or a new on-demand doc
linked from `step-implementation-recovery.md`). Prescribe the
two-mode pattern:

- **Summary capture** (when you only need the pass/fail headline):
  `tail -50` or `grep -aE "Tests run:|BUILD"` is fine.
- **Diagnostic capture** (when surefire reports BUILD FAILURE without
  a clear exception, or you see "VM crash", "exit code", "Tests run: 0"):
  redirect to a uniquely-named file under `/tmp` (with PID/UUID per
  CLAUDE.md's `/tmp` rule), then `grep -anE "T E S T S|deadlock|hs_err|
  watchdog|RUNNING TESTS|Tests run:|Crashed tests|VM crash|System.exit"`
  to locate the diagnostic block, **then** read the surrounding
  context with `Read` (not `tail`).

Complementary tooling: a one-liner `mvn-test-with-diagnosis.sh`
helper script in `.claude/scripts/` that writes a uniquely-named log
and prints the diagnostic block + the result summary in one go. Keeps
the recipe one Bash call instead of three.

A small documentation tweak that pays for itself the first time the
agent hits a JVM abort.

## Acceptance criteria

- `.claude/workflow/step-implementation.md` (or a doc loaded by it)
  has a "Test output capture: summary vs. diagnosis" subsection with
  the two patterns and the trigger conditions.
- The implementer rulebook
  (`.claude/workflow/implementer-rules.md`) cross-references the new
  recipe in its verification step.
- Optional: `.claude/scripts/mvn-test-with-diagnosis.sh` exists and
  is documented in the recipe.
- Regression check: a future Phase B session that hits a JVM abort
  reaches the diagnostic block on the first capture, not the second.
