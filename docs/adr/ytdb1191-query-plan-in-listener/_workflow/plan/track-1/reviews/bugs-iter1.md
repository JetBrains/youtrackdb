<!--MANIFEST
dimension: bugs
prefix: BG
iteration: 1
track: 1
level: high
findings_count: 0
index: []
evidence_base:
  - cert: C1
    topic: plan-capture null/leak safety on LocalResultSet.getExecutionPlan()
  - cert: C2
    topic: reset() ordering and re-iteration staleness
  - cert: C3
    topic: capturedExecutionPlan() null-safety and lifecycle window
  - cert: C4
    topic: interface default method + anonymous override
  - cert: C5
    topic: concurrency-triage-gap scan (shared mutable field)
cert_index: [C1, C2, C3, C4, C5]
flags:
  reference_accuracy_caveat: true
  mcp_steroid_reachable: false
-->

# Bugs review — Track 1 (iteration 1)

## Findings

No bugs found by single-threaded sequential reasoning. Every candidate defect was
refuted; the reasoning is recorded in `## Evidence base`.

## Evidence base

Reference-accuracy caveat: mcp-steroid was not reachable, so caller/override/reader
audits below used grep plus direct file reads. Any "only caller / only reader" claim
carries the usual grep-miss risk (polymorphic dispatch, generic call sites).

#### C1 — Plan capture: null-safety and exception-path leak on `getExecutionPlan()`
HYPOTHESIS: The new line `this.lastExecutionPlan = resultSet.getExecutionPlan();`
inserted between `query.execute(session)` and `resultSet.stream()` in
`YTDBGraphStep.elements()` (query branch) could NPE, return a wrong value, or leak
the result set if it throws.
EVIDENCE / TRACE:
- `LocalResultSet.getExecutionPlan()`
  (`core/.../sql/parser/LocalResultSet.java:133-137`) returns the `executionPlan`
  field, which is set non-null in the constructor (`:35-36`); the body contains only
  an `assert`. It cannot NPE and cannot return null for a normal SELECT.
- The value is stored into a nullable field and only read read-only downstream; it is
  never used to drive execution. No wrong-behavior path.
- Leak: the pre-change code was `query.execute(session).stream().map(...)` with no
  try/finally — a throw from `.stream()` already leaked the result set. Inserting a
  trivial field-returning getter (only an `assert`) between `execute` and `stream`
  does not introduce a realistic new throw site, and the result set's close path
  (`CloseableIteratorWithCallback(..., theStream::close)`) is unchanged.
VERDICT: REFUTED — not a bug. No NPE (getter returns a constructor-set field), no
wrong value, no material new leak surface.

#### C2 — `reset()` ordering and re-iteration staleness
HYPOTHESIS: `reset()` overriding to `super.reset()` then `lastExecutionPlan = null`
could either leave a stale plan across re-iteration or break `GraphStep`
re-iteration.
EVIDENCE / TRACE:
- `super.reset()` (base `GraphStep`) re-arms the element iterator; it does not touch
  the YTDB-declared `lastExecutionPlan` field, so the two statements are order-
  independent for correctness (the in-code comment slightly overstates that "order
  matters", but this is a comment-accuracy nit, not a defect).
- After `reset()`, the next iteration re-invokes the constructor-set iterator supplier
  → `vertices()/edges()` → `elements()`, which re-assigns a fresh plan on the query
  branch or leaves it null on the by-id branch. No stale plan survives.
- Re-iteration correctness is exercised by
  `resetClearsPlanAndReIterationYieldsCorrectResults`.
VERDICT: REFUTED — not a bug. Field is cleared on reset and re-populated per run;
super.reset() re-arms iteration.

#### C3 — `capturedExecutionPlan()` null-safety and validity window
HYPOTHESIS: `YTDBQueryMetricsStep.capturedExecutionPlan()` could NPE, or read a plan
that was already nulled by a `reset()` fired during traversal close before the metrics
step's `close()` reads it.
EVIDENCE / TRACE:
- `TraversalHelper.getFirstStepOfAssignableClass(...).map(...).orElse(null)` is fully
  null-safe: absent source step → null; present step with null plan → null.
- Grep of the monitoring package shows no `reset()` invocation on the source step
  during close; TinkerPop calls `reset()` only on re-iteration, not on `close()`. The
  `YTDBGraphStep` is not `AutoCloseable`, so traversal close does not close/reset it.
  The metrics step reads the plan synchronously in the same thread during `close()`,
  within the documented validity window.
- Confirmed behaviorally by `executionPlanReadableInsideCallbackAfterResultSetClosed`
  (plan readable after the result set closed) — `SelectExecutionPlan.close()`
  propagates close to steps but does not drop the steps list.
VERDICT: REFUTED — not a bug. Null-safe resolution; plan read inside the valid
synchronous callback window.

#### C4 — Interface default method + anonymous override
HYPOTHESIS: The new `@Nullable default ExecutionPlan getExecutionPlan()` on
`QueryDetails` plus the anonymous override in `YTDBQueryMetricsStep` could break an
existing implementer or dispatch to the wrong method.
EVIDENCE / TRACE: The default returns `null`, so pre-existing `QueryDetails`
implementers keep compiling and report no plan (pinned by
`queryDetailsDefaultExecutionPlanIsNull`). The anonymous class overrides it to call
`capturedExecutionPlan()`; standard virtual dispatch. No abstract-method break.
VERDICT: REFUTED — not a bug. Opt-in default is backward compatible.

#### C5 — Concurrency-triage-gap scan (backstop, not an interleaving analysis)
The change adds one mutable instance field (`lastExecutionPlan`) on `YTDBGraphStep`,
written during iteration and read from `YTDBQueryMetricsStep.close()`. Both occur on
the same thread within a single traversal execution (Gremlin traversal instances are
single-threaded); `GROOVY_TRANSLATOR` is `ThreadLocal`. No shared-across-threads
mutable state is introduced, so no concurrency-triage-gap note is raised for the
orchestrator.
