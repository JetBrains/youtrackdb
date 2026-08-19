<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
  - {id: R5, verdict: VERIFIED}
  - {id: R6, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Track 8 risk review — gate verification (iteration 2)

All six iteration-1 risk findings (R1–R6, all ACCEPTED) are folded correctly into
the amended track file. The re-pointing of the advance realization to a
`MultipleExecutionStream` over the base's existing `startPlanStream()` hook (DR-U1)
is the load-bearing fix: it retires R1's feasibility gap and makes R6's
"terminal-failure release runs unchanged" the sanctioned path rather than one of two
branches. No new risk surfaced. PASS.

**Reference-accuracy caveat.** mcp-steroid PSI (`steroid_execute_code`) times out in
this repo, so the three symbol facts the fixes rest on were re-confirmed by direct
declaration reads, not PSI find-usages: `startPlanStream()` is a protected abstract
hook on `AbstractMatchPlanStep` (`:805`); `MultipleExecutionStream` is a lazy
one-live-stream concatenator over `ExecutionStreamProducer` that closes the drained
child before opening the next (`resultset/MultipleExecutionStream.java:16-42`); the
base's terminal-failure path calls `closePlan()` on a `startPlanStream` failure
(`:437`) and close-all on teardown (`:459-490`). These are declaration reads at cited
lines, so they carry only the residual that a reflective caller could be missed.

## Findings

_No new findings._

## Verification certificates

#### Verify R1: N-plan advance seam over the private single-plan base
- **Original issue**: Track 8 planned to iterate `plans[0..N]` "advance on drain" by
  extending `AbstractMatchPlanStep` without listing the base in modified scope, but the
  base exposes no advance hook and its per-stream state / session-rebind are private and
  per-arming-singular. The seam was a hidden feasibility gap that would surface
  mid-implementation and re-open the Track 7 lifecycle.
- **Fix applied**: DR-U1 re-points the realization from the per-plan NEW/REARMED reopen
  (and from overriding `processNextStart`) to a `MultipleExecutionStream` over an
  `ExecutionStreamProducer`, supplied through the base's existing `startPlanStream()`
  hook. One base arming spans all N plans, so no advance mechanism is needed. The base is
  named conditionally in "In scope (modified)" for the residual per-child-context-threading
  edit.
- **Re-check**:
  - Location: `## Decision Log` DR-U1 (line 30); `## Plan of Work` item 1 (line 61);
    `## Interfaces and Dependencies` "In scope (modified)" (line 92); `## Context and
    Orientation` reference-accuracy note (line 48); codebase
    `AbstractMatchPlanStep.java:805`.
  - Current state: `startPlanStream()` is confirmed a protected abstract hook the base
    already exposes (`:805`), so the concatenation seam needs no base edit — the central
    feasibility gap is closed. The remaining question — whether the composite needs a small
    `AbstractMatchPlanStep` edit for per-child context threading or whether
    `childPlan.start(childCtx)` pre-binding suffices — is stated in Plan of Work item 1 and
    the Context reference-accuracy note, and the base is conditionally in scope
    ("`AbstractMatchPlanStep` **only if** decomposition finds the composite realization
    needs a per-child-context-threading seam"). The seam is named, not left to be discovered.
  - Criteria met: R1's proposed fix asked to add the base to modified scope and design the
    N-plan seam explicitly. Both hold — the seam is designed via `startPlanStream()`/
    `MultipleExecutionStream`, and the base's conditional in-scope entry covers the residual.
- **Regression check**: Checked the DR-U1 re-pointing against the Track 7 base shape and
  the prior-episode hand-off. The base's own episode prescribed the per-plan reopen or an
  advance hook; the Surprises entry (line 20) records that adversarial review superseded
  that hand-off, so the contradiction with the prior episode is acknowledged in-file, not
  silent. `startPlanStream()` being a real protected hook makes the re-pointing feasible
  without a base edit. The per-child-context-threading residual is the same concern R1
  raised, correctly carried forward as a named decomposition question — not a new defect.
  Clean.
- **Verdict**: VERIFIED

#### Verify R2: multi-alias clone / context isolation + concurrency category
- **Original issue**: Union children are multi-alias sub-patterns — the exact case the
  single-plan clone invariant warns breaks isolation. `MultiPlanMatchStep.clone()` must
  give each of the N children its own isolated context (not one shared context), and the
  new multi-plan clone surface warrants a concurrency reviewer that Track 7 explicitly left
  off its roster.
- **Fix applied**: The track states per-child context isolation (single-plan template
  applied per child), assigns the `concurrency` code-review category to the
  `MultiPlanMatchStep` step, and strengthens the clone-isolation test to a concurrent
  assertion.
- **Re-check**:
  - Location: `## Context and Orientation` (line 46, "`clone()` isolates each child plan
    against its own child context … single-plan clone template is applied per child");
    `## Plan of Work` item 1 (line 61) and item 4 tests (line 64, "concurrent
    clone-isolation across multi-alias children"); `## Surprises & Discoveries` (line 23,
    concurrency category confirmed for the `MultiPlanMatchStep` step); `## Validation and
    Acceptance` (line 76, "Two concurrent clones … show no cross-clone/parent variable
    bleed").
  - Current state: per-child isolation is explicit ("each child plan against its own child
    context"), ruling out the one-shared-context trap the finding flagged. The concurrency
    category is pinned to the specific step carrying `clone()`. The validation assertion is
    CONCURRENT ("Two concurrent clones"), not sequential.
  - Criteria met: all three fix sub-items land — per-child isolation, concurrency-category
    assignment filling Track 7's deferral, and a concurrent (not sequential) isolation test.
- **Regression check**: Checked that per-child isolation does not contradict DR-U1's
  "opens each child plan lazily against its own isolated, session-rebound context" — the
  two are the same isolation stated from the clone and the producer sides. Consistent. Clean.
- **Verdict**: VERIFIED

#### Verify R3: shared `GremlinPlanCache` union keying
- **Original issue**: The cache is single-plan on three axes (single-input fingerprint,
  single-`InternalExecutionPlan` value, single-plan `canBeCached`/`copy`/`close`), and the
  union cache policy was an open Decision-Log item. A naive union fingerprint risks
  wrong-plan service or a broken multi-plan carrier.
- **Fix applied**: DR-U5 pins the safe default — union sets `cacheEligible = false`,
  mirroring the RID-bypass path; a multi-input fingerprint / multi-plan value is deferred
  unless a later need justifies designing plus collision-testing it.
- **Re-check**:
  - Location: `## Decision Log` DR-U5 (line 34); `## Plan of Work` item 2 (line 62,
    "sets `cacheEligible = false` for union"); `## Interfaces and Dependencies`
    (line 92 modified, line 93 out-of-scope "a multi-plan cache value / union fingerprint
    (deferred under DR-U5)"); `## Validation and Acceptance` (line 78).
  - Current state: the open Decision-Log item is now a pinned decision (`cacheEligible =
    false`), the multi-plan cache value/fingerprint is explicitly out of scope, and the
    validation asserts a RID-bearing-child union bypasses the cache and still returns the
    correct multiset.
  - Criteria met: the finding's "safe default = do not cache union" is exactly what DR-U5
    pins; the fingerprint/value-type hazards are foreclosed by not caching.
- **Regression check**: Checked that setting `cacheEligible=false` does not conflict with
  the single-RID-child validation line (it reinforces it). Clean.
- **Verdict**: VERIFIED

#### Verify R4: close of un-run plans + build-time partial-build leak
- **Original issue**: Two leak surfaces unique to N plans — `closePlan()` must close
  never-`start()`ed `plans[N+1..]` (the base's single-plan guard skips unstarted plans, so
  the N-plan form cannot borrow it), and a mid-build throw on `plans[k]` must close the
  already-built `plans[0..k-1]` rather than merely decline.
- **Fix applied**: Plan of Work item 1 makes `closePlan()` close every child including
  un-run `plans[N+1..]`; item 2 closes already-built plans on a mid-build throw; the
  validation section adds both a close-all and a mid-build-throw acceptance line.
- **Re-check**:
  - Location: `## Plan of Work` item 1 (line 61, "`closePlan()` closes every child
    including un-run `plans[N+1..]`") and item 2 (line 62, "closes already-built plans on a
    mid-build throw") and item 4 tests (line 64, "close-all-including-un-run and build-time
    partial-build leak"); `## Validation and Acceptance` (line 75, "every child including
    un-run ones is closed … A mid-build throw on child k closes children 0..k-1").
  - Current state: both leak surfaces are named with explicit close semantics and each has
    a validation line. The N-plan `closePlan()` deliberately does not skip un-run children,
    resolving the base-guard mismatch the finding raised.
  - Criteria met: both fix sub-items (close-all-incl-un-run test with per-plan spies;
    build-time guard that closes already-built plans before rethrow) are represented in the
    plan and validation.
- **Regression check**: The finding's unstarted-plan `close()` null-safety caveat (no PSI)
  is carried forward in the Context reference-accuracy note (line 48) as an
  implementation-time re-confirm item. That is the correct home for an unresolved
  symbol-safety question, not a new defect. Clean.
- **Verdict**: VERIFIED

#### Verify R5: union must not ride MATCH `splitDisjointPatterns` (cartesian hazard)
- **Original issue**: Routing union children into one `MatchPlanInputs` as disjoint
  sub-patterns would silently produce a `CartesianProductStep` join (product) instead of
  concatenation (sum). The design commits to one plan per child, which avoids it, but an
  implementation shortcut could fold children into one inputs.
- **Fix applied**: The validation section adds an explicit anti-cartesian assertion — a
  two-child union whose product ≠ sum must return `|c1| + |c2|`, not the product.
- **Re-check**:
  - Location: `## Validation and Acceptance` (line 73, "the anti-cartesian case (children
    whose product ≠ sum) returns `|c1| + |c2|`, not the product"); `## Plan of Work` item 4
    (line 64, "anti-cartesian `|c1|+|c2|` ≠ `|c1|·|c2|` case"); `## Context and
    Orientation` (line 42, "Union cannot ride MATCH's `splitDisjointPatterns` … joins …
    by cartesian product").
  - Current state: the anti-cartesian test is explicit with children chosen so product ≠
    sum, so a fold-into-one-inputs shortcut would fail loudly. The Context section states
    the design premise (one plan per child, no `splitDisjointPatterns`).
  - Criteria met: the finding's "make the anti-cartesian case explicit with children whose
    product ≠ sum" is exactly the added line.
- **Regression check**: Checked the `splitDisjointPatterns`→`CartesianProductStep` claim
  is stated consistently in Context (line 42). Clean.
- **Verdict**: VERIFIED

#### Verify R6: exception-stops-advance semantics (three-assert test)
- **Original issue**: "Exception in plan N re-throws without opening N+1" is automatic
  only under the composite-stream realization; the override-`processNextStart` branch would
  have to hand-replicate the base's terminal semantics (addSuppressed, close-all, move to
  CLOSED) and is easy to get subtly wrong. The exception test asserted one thing, not three.
- **Fix applied**: DR-U1 fixes the realization to the composite stream, so the base's
  terminal-failure release runs unchanged. The exception test is strengthened to three
  assertions: N+1 never started, all children (incl. un-run) closed, original iteration
  exception primary.
- **Re-check**:
  - Location: `## Context and Orientation` (line 46, "an exception in `plans[N]` never
    opens `plans[N+1..]` and the base's terminal-failure release (move to CLOSED, close
    every child including un-run ones, keep the iteration exception primary) runs
    unchanged"); `## Plan of Work` item 4 (line 64, "exception-stops-advance asserting N+1
    never started, all closed, and the original exception primary"); `## Validation and
    Acceptance` (line 75, three assertions).
  - Current state: because the realization is pinned to `MultipleExecutionStream` (which
    keeps one live child and closes it before opening the next — confirmed at
    `resultset/MultipleExecutionStream.java:16-24`), the "never opens N+1" and close-all
    semantics fall out of the concatenator and the base's terminal handler, exactly as the
    finding's composite branch predicted. The test asserts all three properties.
  - Criteria met: the three-assert exception test is present, and the seam ambiguity the
    finding hinged on is resolved by DR-U1 selecting the composite branch.
- **Regression check**: The override-`processNextStart` branch is no longer live (DR-U1),
  so R6's "hand-replicate is error-prone" concern is retired rather than deferred. Verified
  `MultipleExecutionStream.close()` closes current stream + producer (`:38-42`), consistent
  with close-all. Clean.
- **Verdict**: VERIFIED
