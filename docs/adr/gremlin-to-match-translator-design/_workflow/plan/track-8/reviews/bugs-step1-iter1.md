<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 7, matches: 0}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
flags: [CONTRACT_OK]
-->

## Findings

No single-threaded sequential-reasoning bugs found. `MultiPlanMatchStep` realizes the four base seam hooks correctly: lazy per-child open, exception-stops-advance, close-all-including-un-run, and per-child context fidelity all trace clean against the `AbstractMatchPlanStep` lifecycle, the `MultipleExecutionStream` concatenator, and `SelectExecutionPlan`. Every bug hypothesis I raised was refuted; the checks are recorded in `## Evidence base`.

The clone-isolation surface (deep per-child copy, fresh coordinator context, session publication across armings) carries genuine interleaving questions, but those belong to `review-concurrency`, which the track already triaged onto this step (risk R2). I did not analyze the races and emit no triage-gap note.

## Evidence base

Reference-accuracy basis: mcp-steroid PSI (`steroid_execute_code`) times out in this repo, so symbol facts rest on grep plus declaration reads. This step adds one new file with no rename, delete, or signature change, and I read the full declarations of every collaborator the step touches — `AbstractMatchPlanStep`, `MultipleExecutionStream`, `ExecutionStreamProducer`, `InternalExecutionPlan`, `SelectExecutionPlan`, `BasicCommandContext` — so the residual grep-miss risk is low for the claims below.

#### C1 — Exception at child i closes the reached stream and every plan, un-run children included — REFUTED
Hypothesis: an exception while iterating child i leaks child i's open stream, or fails to close the never-started children i+1…N. Traced `processNextStart`'s terminal `catch` → `releaseStreamAndClosePlan` (`AbstractMatchPlanStep` 269-284, 469-491): it closes `openStream` (the `MultipleExecutionStream`), whose `close` closes the one live child stream through `ChildContextStream` against the child's own context, then calls `closePlan`, which loops every entry of `plans` and closes it. The lazy producer opened no stream for children past the failing one (`MultipleExecutionStream.hasNext` requests `next` only when advancing), so they hold no cursor, and their plans are still closed by the loop. The first close throwable stays primary; the rest attach via `addSuppressed` (`MultiPlanMatchStep.closePlan` 262-279). No leak, no masking. Not a bug.

#### C2 — Each child iterates and closes against its own context, not the coordinator's — REFUTED
Hypothesis: `MultipleExecutionStream` threads the coordinator context to the child stream, splitting the child's session / `$current` / `$matched` reads across two contexts. Confirmed the producer wraps every child stream in `ChildContextStream` (`MultiPlanMatchStep` 241), which ignores the ctx argument the concatenator passes and delegates `hasNext` / `next` / `close` to `childContext` = `childPlan.getContext()`. `SelectExecutionPlan.start()` also drives `lastStep.start(this.ctx)` off the plan's own ctx field — the same object `getContext()` returns and the producer just rebound (SelectExecutionPlan 70-87). Start-time and iteration-time context are the identical object. Not a bug.

#### C3 — Session rebind precedes every child start — REFUTED
Hypothesis: a child opens before the iteration-thread session is bound onto its context, so its first record read throws `SessionNotActivatedException`. Traced: the base's `openArming` sets `coordinatorContext.session` (via `planContext().setDatabaseSession`) before `startPlanStream` (`AbstractMatchPlanStep` 411-443); the producer's `next` reads `ctx.getDatabaseSession()` off that coordinator and calls `childContext.setDatabaseSession(...)` before `childPlan.start()` (`MultiPlanMatchStep` 234-241). `BasicCommandContext.getDatabaseSession` returns the field that was set (504-518), and `setDatabaseSession` sets it plus propagates to any child (521-527). The rebind precedes the start on every child and on every re-arm. Not a bug.

#### C4 — reset()+reopen restarts from the first child and leaks no prior stream — REFUTED
Hypothesis: a re-armed union re-drives already-consumed child streams, or the prior arming's `MultipleExecutionStream` leaks. Traced: `startPlanStream` builds a fresh `ExecutionStreamProducer` (with `iter = childPlans.iterator()`) on every arming, so a reopen restarts from the first child (`MultiPlanMatchStep` 211-251); `openArming` closes any lingering `openStream` before the rewind (`AbstractMatchPlanStep` 395-401); `rewindPlan` resets every child, and the base calls it only in `REARMED`, so a never-run `NEW` step rewinds nothing (`MultiPlanMatchStep` 200-208, base 427-429). On normal drain `releaseStream` already nulled `openStream`, so the stale-close is a no-op on the clean-drain re-arm path. Matches the single-plan step's lifecycle. Not a bug.

#### C5 — clone() isolates plans, coordinator, and lifecycle state — REFUTED (interleaving deferred)
Hypothesis: `clone()` aliases the original's plan list or coordinator, or a clone taken from a closed step is born non-NEW and never closes its own copies. Traced: `clone` overwrites the shallow-copied fields — deep-copies every child via `childPlan.copy(freshIsolatedCtx)` (`SelectExecutionPlan.copy` builds an independent step chain and sets `getContext() == ctx`, starting nothing, 238-277), installs a fresh `coordinatorContext`, and calls `resetLifecycleForClone()` to drop `super.clone()`'s per-arming references and return to `NEW` (`MultiPlanMatchStep` 158-190; base 552-557). All writes are plain writes to non-final fields before publication. Cross-execution / cross-clone context bleed under real interleaving is an interleaving concern owned by `review-concurrency` (triaged onto this step, risk R2); I did not analyze it. No sequential defect.

#### C6 — closePlan's trailing assert is unreachable and un-run close is safe — REFUTED
Hypothesis: `closePlan`'s trailing `assert first == null` can fire, or closing a never-started child NPEs. Traced: the loop catches `RuntimeException | Error` only, so `first` is only ever a `RuntimeException`, an `Error`, or null; both non-null forms are rethrown before the assert, which therefore guards an unreachable checked-throwable case (`MultiPlanMatchStep` 262-283). Un-run child close routes to `SelectExecutionPlan.close()` → `lastStep.close()`; `lastStep` is non-null for a chained (built) plan, and backward propagation through an unstarted chain is the same path the single-plan base's terminal close already exercises. Confirming `lastStep.close()` null-safety across every child step chain is the base / `SelectExecutionPlan` concern the track's reference-accuracy note already flags (risk R4), not new logic in this diff. Not a bug here.

#### C7 — Double-close of the last child stream on normal drain — REFUTED (pre-existing, tolerated)
Hypothesis: the last child's stream is closed twice on clean exhaustion, which could throw if close is not idempotent. It is closed once inside `MultipleExecutionStream.hasNext` when the last child drains (line 19) and again when the base's `releaseStream` calls `MultipleExecutionStream.close` (which re-closes the still-referenced `currentStream`, lines 38-43). This double-close is inherent to `MultipleExecutionStream` — an established production concatenator already driven this way by `ParallelExecStep` and `FetchFromIndexStep` — so `ExecutionStream` implementations tolerate it; the test's `ListStream` asserts `closeCount >= 1`, showing the author accounted for the second close. Not introduced by this diff and not a defect in `MultiPlanMatchStep`.
