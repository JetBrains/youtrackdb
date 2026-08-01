<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 1, suggestion: 3}
index:
  - {id: BG1, sev: should-fix, loc: AbstractMatchPlanStep.java:565, anchor: "### BG1 ", cert: C1, basis: "close()'s un-gating rationale names an unreachable state and omits the reachable one, inviting a gate that leaks the installed plan copy"}
  - {id: BG2, sev: suggestion, loc: AbstractMatchPlanStep.java:578, anchor: "### BG2 ", cert: C2, basis: "close() before any iteration marks NEW as CLOSED without closing the plan; the next reset() copies and discards it"}
  - {id: BG3, sev: suggestion, loc: AbstractMatchPlanStep.java:441, anchor: "### BG3 ", cert: C3, basis: "both ordering constraints documented around the plan swap are inert under the current hooks"}
  - {id: BG4, sev: suggestion, loc: MultiPlanMatchStep.java:274, anchor: "### BG4 ", cert: C4, basis: "a mid-loop copy() failure abandons the already-built child copies with nothing to close them"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 4}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
  - {id: C9, verdict: REFUTED, anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED, anchor: "#### C10 "}
  - {id: C11, verdict: REFUTED, anchor: "#### C11 "}
  - {id: C12, verdict: REFUTED, anchor: "#### C12 "}
flags: [CONTRACT_OK]
-->

## Findings

### BG1 [should-fix] `close()`'s un-gating rationale names a state the machine cannot reach

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java` (lines 565-569)

**Issue.** The new paragraph explains why `close()` skips its early return for `REARMED_AFTER_CLOSE`: "the re-arm may have been followed by a re-run, in which case the plan now installed is a live copy that this call must close." That state combination cannot occur. `processNextStart()` assigns `state = State.OPEN` on the statement immediately after `openArming()` returns (`:272-273`), so a re-arm that produced rows leaves the step in `OPEN`, and from there `reset()` and `close()` can only reach `DRAINED`, `REARMED`, or `CLOSED`. Whenever `close()` observes `REARMED_AFTER_CLOSE`, the re-arm was never driven and the plan is the already-closed original. That is the case the paragraph's *second* sentence covers, so the paragraph's stated reason for the un-gating is dead and its stated fallback is the only live case.

The un-gating is still load-bearing, along a path the paragraph never mentions. `openArming()` installs the copy at `:445`, then runs four statements before entering the try that guards `startPlanStream()`: `planContext()`, `armingGraph.tx()`, `tx.readWrite()`, and `ctx.setDatabaseSession(...)` (`:447-457`). `tx.readWrite()` reaches the transaction machinery and can throw. `openArming()` also sits outside `processNextStart()`'s terminal handler by design, documented at `:274-277`. A throw in that window therefore leaves the step in `REARMED_AFTER_CLOSE` holding a live, unstarted plan copy, and the un-gated `close()` is the only code that releases it.

**Failure scenario.** A maintainer reads the paragraph, checks whether a driven re-arm can be in `REARMED_AFTER_CLOSE`, finds it cannot, and concludes the un-gating is a leftover. They add `REARMED_AFTER_CLOSE` to the `close()` early return next to `CLOSED`. From then on, any translated traversal whose re-armed second pass hits a `tx.readWrite()` failure abandons the plan copy that `replaceClosedPlanWithCopy()` just installed, with nothing left to close it. The step's own state machine gives no other release point, because the terminal handler in `processNextStart()` never sees the exception.

**Refutation considered.** I looked for a route into `REARMED_AFTER_CLOSE` that carries a live copy from a *completed* re-run: `reset()` maps `OPEN` and `DRAINED` to `REARMED`, and maps only `CLOSED` to `REARMED_AFTER_CLOSE` (`:549-553`), so the copy a completed re-run installed is already closed by the `close()` that produced that `CLOSED`. I also checked whether the `tx.readWrite()` window is real rather than theoretical: `armingGraph.tx()` and `tx.readWrite()` are plain calls with no surrounding try, and the comment at `:274-277` states that `openArming()` is deliberately outside the caller's handler.

**Suggestion.** Replace the first sentence with the path that actually reaches this state: a re-arm whose `openArming()` threw after installing the copy but before `startPlanStream()`'s try. Keep the second sentence as the common case.

### BG2 [suggestion] A `close()` before any iteration marks the step `CLOSED` without closing its plan, and the next `reset()` discards that plan

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java` (lines 578-586)

**Issue.** `close()` computes `boolean started = state != State.NEW` at `:578`, then writes `state = State.CLOSED` at `:579` unconditionally. For a step that was never iterated, `started` is false and `openStream` is null, so neither branch runs and `closePlan()` is skipped — correct on its own, since a `NEW` step holds nothing. The step is nonetheless now in `CLOSED`, a state whose new meaning is "the plan is closed". `reset()` maps `CLOSED` to `REARMED_AFTER_CLOSE` (`:551-552`), and the next open calls `replaceClosedPlanWithCopy()` (`:440-446`), which overwrites the field and drops the original past any reference. The dropped plan was never started and never closed.

**Failure scenario.** A caller writes `try (var t = g.V().hasLabel("person")) { if (skip) { return; } … }` and returns before iterating. `Traversal.close()` closes every `AutoCloseable` step, so the boundary step goes `NEW → CLOSED` with its plan untouched. A later `t.asAdmin().reset()` followed by iteration then deep-copies the whole step chain (once per union child in `MultiPlanMatchStep`) purely to re-run a plan that had never started, and the original chain is never handed to `close()`. `MultiPlanMatchStep.closePlan()`'s own Javadoc (`:434-441`) states the opposite rule for un-run children: their "step chains still exist and must be released".

**Refutation considered.** I checked whether an unstarted `SelectExecutionPlan` holds anything a missed `close()` would leak. It does not: `internalStart` is where each step claims its cursor, and `SelectExecutionPlan.close()` only walks `lastStep.close()` back through the chain. So the cost is a needless full plan copy plus a violation of the codebase's own release rule, not a cursor leak. I also confirmed the boundary step cannot be left `NEW` by a partially-iterated traversal: translation is all-or-nothing, so the boundary is the traversal's only step and any iteration drives it.

**Suggestion.** Separate "closed" from "closed without ever having started". The cheapest shape is a `reset()` branch that returns a never-started step to `NEW` so its first open just starts the original plan; a `CLOSED_UNSTARTED` state would make the distinction explicit at the cost of a sixth enum constant.

### BG3 [suggestion] Both ordering constraints documented around the plan swap are inert under the current hook implementations

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java` (lines 441-444)

**Issue.** The comment above `replaceClosedPlanWithCopy()` asserts two ordering constraints. Neither binds today.

The second one cannot arise at all: "The stale-cursor close above deliberately runs first, since it must close against the context of the plan whose stream it is." `openStream` is provably null whenever `state == REARMED_AFTER_CLOSE`. Every route into `CLOSED` nulls it first — `close()` delegates to `releaseStreamAndClosePlan()`, which assigns `openStream = null` at `:508` before touching the stream, and the iteration-failure path at `:305-307` calls the same method. `reset()` reaches `REARMED_AFTER_CLOSE` only from `CLOSED`, so the stale-cursor block at `:424-430` never executes in this state.

The first one ("swap the plan BEFORE `planContext()` is read below") has no observable effect either. `YTDBMatchPlanStep.replaceClosedPlanWithCopy()` copies against `closedPlan.getContext()` (`YTDBMatchPlanStep.java:153`), so `planContext()` returns the same object before and after the swap. `MultiPlanMatchStep.planContext()` returns `coordinatorContext` (`MultiPlanMatchStep.java:249-252`), which the swap never touches.

**Failure scenario.** The comment reads as a tested invariant, so a later change inherits false confidence in both directions. If someone gives the copy its own derived context, the "swap first" ordering becomes genuinely load-bearing for the first time and no test covers it, because none can distinguish the orders today. If someone wants to move the swap after the session rebind for an unrelated reason, the comment blocks a reordering that costs nothing.

**Refutation considered.** I traced every assignment to `openStream` in the class (`:272`, `:429`, `:493`, `:508`, `:601`) to confirm the null claim rather than inferring it from the two obvious paths. I also checked whether `MultiPlanMatchStep`'s children could make `planContext()` swap-sensitive; the hook returns the coordinator context, and the child contexts are reached only inside `startPlanStream()`'s producer.

**Suggestion.** Drop the stale-cursor sentence and note that `openStream` is null in this state. Keep the "swap first" constraint if it is meant to protect a future context derivation, but say so — write it as a constraint the code is choosing to hold, not as a dependency the current hooks have.

### BG4 [suggestion] A mid-loop `copy()` failure in the union's re-arm abandons the child copies already built

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStep.java` (lines 274-287)

**Issue.** `replaceClosedPlanWithCopy()` accumulates copies into a local `ArrayList` and publishes them to `plans` only after the loop completes (`:287`). If `childPlan.copy(...)` throws on child *k*, copies `0 … k-1` are already built and become unreachable when the local list is discarded with the stack frame. `plans` still holds the closed originals, so no later `closePlan()` can find them. The call site compounds this: `openArming():445` runs the hook outside the try that guards `startPlanStream()`, and `openArming()` itself is outside `processNextStart()`'s terminal handler (`:274-277`), so the exception reaches the caller with no release pass at all.

**Failure scenario.** A union whose second child plan contains a step that cannot be deep-copied. `InternalExecutionPlan.copy` defaults to `throw new UnsupportedOperationException()` (`InternalExecutionPlan.java:75-77`), and `SelectExecutionPlan.copyOn` calls `step.copy(ctx)` on every step in the chain, so a single non-copyable step raises. Child 1's freshly built copy is then dropped without `close()`.

**Refutation considered.** As with BG2, the abandoned copies are unstarted and hold no cursors, which is why this sits at suggestion rather than higher. I also checked whether `clone()` has the same shape and would already have surfaced the problem: it does (`MultiPlanMatchStep.java:180-208`), so this is a pattern the class already carries rather than one the diff introduces. The diff does widen the number of code paths that reach it.

**Suggestion.** Wrap the loop so a failure closes whatever the loop already copied before rethrowing, in the same first-failure-primary / rest-suppressed shape `closePlan()` already uses.

## Evidence base

#### C1 CONFIRMED — `close()`'s `REARMED_AFTER_CLOSE` paragraph describes an unreachable state (BG1)

`processNextStart():272-273` sets `state = State.OPEN` directly after `openArming()` returns, and `reset():549-553` maps `OPEN`/`DRAINED` to `REARMED` and only `CLOSED` to `REARMED_AFTER_CLOSE`, so no driven re-arm can be observed in `REARMED_AFTER_CLOSE` by `close()`; the reachable case is a throw between `replaceClosedPlanWithCopy()` at `:445` and the `startPlanStream()` try at `:466`.

#### C2 CONFIRMED — `NEW → close() → reset()` reaches `REARMED_AFTER_CLOSE` with a never-closed plan (BG2)

`close():578-586` skips `closePlan()` when `started` is false but still writes `state = State.CLOSED` at `:579`, and `reset():551-552` then routes to `REARMED_AFTER_CLOSE`, where `openArming():440-446` overwrites the plan reference with a copy.

#### C3 CONFIRMED — `openStream` is null in `REARMED_AFTER_CLOSE` and `planContext()` is swap-insensitive (BG3)

`releaseStreamAndClosePlan():508` nulls `openStream` before any close and is the only route into `CLOSED` from both `close():583` and the iteration-failure handler at `:305-307`; `YTDBMatchPlanStep.replaceClosedPlanWithCopy():153` copies against the same context object, and `MultiPlanMatchStep.planContext():249-252` returns the untouched coordinator context.

#### C4 CONFIRMED — the union's copy loop publishes only on success (BG4)

`MultiPlanMatchStep.java:274-287` builds copies into a local list and assigns `plans` after the loop, and the call site at `AbstractMatchPlanStep:445` lies outside both the `startPlanStream()` try and `processNextStart()`'s terminal handler.

#### C5 REFUTED — the copy is built before the session rebind, so a step's `copy()` could capture a stale session

**Claim.** `openArming()` calls `replaceClosedPlanWithCopy()` at `:445` but rebinds the iteration-thread session at `:457`, twelve lines later. The context object is shared between the closed plan and its copy, and it still carries the previous pass's session. The comment at `:452-454` states that a re-iteration after `reset()` may run on a different thread than the first pass, and each server worker thread owns its own pooled session. If any step's `copy(CommandContext)` reads `ctx.getDatabaseSession()` and stores it, the copied chain would run against the previous pass's session and throw `SessionNotActivatedException`.

**Check.** Searched every `public ExecutionStep copy(CommandContext …)` body under `core/.../sql/executor` (including the `match/` subpackage) for a read of `getDatabaseSession` / `getSession` / `getDb`. No match. Step copies rebuild from descriptors and AST fragments and take the session from `ctx` at `internalStart` time, after the rebind.

**Verdict.** REFUTED. No step captures the session at copy time, so the twelve-line gap between the copy and the rebind is harmless.

#### C6 REFUTED — `close()` on a `REARMED_AFTER_CLOSE` step double-closes the plan and re-releases resources

**Claim.** Before this diff, `reset()` left a `CLOSED` step in `CLOSED`, so the `if (state == State.CLOSED) return;` guard at `:573` made a second `close()` a no-op. Now `reset()` moves the step to `REARMED_AFTER_CLOSE`, which the guard does not cover, so `closePlan()` runs a second time on a plan the first `close()` already closed. The new Javadoc asserts every `InternalExecutionPlan` treats that as a no-op; if any step does real cleanup outside the sticky guard, the second pass would repeat it.

**Check.** `SelectExecutionPlan.close():76-78` forwards to `lastStep.close()`, and `AbstractExecutionStep.close():109-118` returns early on the private `alreadyClosed` flag. Enumerated every `close()` override under `core/.../sql/executor`: `DistinctExecutionStep:142`, `LimitExecutionStep:66`, and `SkipExecutionStep:65` skip the flag but only forward to `prev.close()`, which is itself guarded, so the second walk terminates without repeating work; `FetchFromIndexStep:918` and `BackRefHashJoinStep:871` call `super.close()`, and `BackRefHashJoinStep` re-nulls fields that are already null.

**Verdict.** REFUTED. The double close is idempotent for every plan shape the boundary step can hold.

#### C7 REFUTED — `InternalExecutionPlan.copy` may be unsupported for a boundary plan, so the re-arm would throw where the old code silently ended iteration

**Claim.** `InternalExecutionPlan.copy(CommandContext)` defaults to `throw new UnsupportedOperationException()` (`InternalExecutionPlan.java:75-77`), and `SelectExecutionPlan.copyOn` requires every step in the chain to implement `copy`. A translated MATCH plan containing one non-copyable step would turn a previously silent empty second pass into a thrown exception.

**Check.** `YTDBMatchPlanStep.clone():125` and `MultiPlanMatchStep.clone():207` already call `plan.copy(...)` on the same plans, and the class Javadoc records that TinkerPop clones a traversal once per execution. A plan whose steps cannot be copied would therefore fail on the *first* execution, long before any re-arm.

**Verdict.** REFUTED. The re-arm adds no new dependency on `copy()` beyond what every execution already exercises.

#### C8 REFUTED — the copy reuses a context the completed pass seeded, so the second pass reads stale MATCH bindings

**Claim.** Both hooks copy against the closed plan's own context, which by then holds `$current`, `$matched`, `$current_match`, `$depth`, alias/LET bindings and profiling statistics from the finished pass. A MATCH step that reads one of those slots before writing it would see pass 1's residue on pass 2's first candidate.

**Check.** The pre-existing `REARMED` path has the same exposure: `rewindPlan(ctx)` resets the step chain but reuses the identical context object, and `reset_thenProcessNextStart_reRunsPlanOnSameInstance` covers it. The new real-plan tests seed a per-run context variable on purpose (`ReplayablePlanFixture.PER_RUN_VARIABLE`) and assert the copy runs against that seeded context, and both new suites are green (`YTDBMatchPlanStepTest` 41/41, `MultiPlanMatchStepTest` 31/31, run locally with `-ea` from `core/pom.xml:36`).

**Verdict.** REFUTED. Context reuse across passes is the pre-existing contract, not something the copy path introduces.

#### C9 REFUTED — the new abstract hook breaks a subclass outside the two in the diff

**Claim.** `replaceClosedPlanWithCopy()` is added as `protected abstract` on `AbstractMatchPlanStep`, which breaks any subclass that does not implement it. `GremlinToMatchStrategy:79` refers to "the single-plan `YTDBMatchPlanStep` or any other `AbstractMatchPlanStep`", hinting at more implementations.

**Check.** `grep -rn "extends AbstractMatchPlanStep"` across the whole tree (excluding sibling worktrees) returns exactly `MultiPlanMatchStep:97` and `YTDBMatchPlanStep:34`, both `final` and both implementing the hook. Reference-accuracy caveat: `steroid_execute_code` is known to exceed the 60-second MCP timeout in this repository, so this is a grep result, not a PSI find-implementations result. The `extends AbstractMatchPlanStep` phrase is a literal that grep matches reliably for a class with no generic-alias indirection, and both subclasses are `final`, which bounds the hierarchy.

**Verdict.** REFUTED. Two subclasses, both updated.

#### C10 REFUTED — `MultiPlanMatchStep.clone()`'s isolation assert now fires on a re-armed step

**Claim.** `clone()` asserts that each child's template context carries no per-run state (`MultiPlanMatchStep:197-204`). After a re-arm, `plans` holds copies whose `getContext()` is the seeded context of the pass that ran, so cloning a re-armed step would trip the assert under `-ea`.

**Check.** The copies reuse the *same* context objects the originals held (`:276`), so `childPlan.getContext()` returns exactly what it returned before the swap. A step that had completed a pass already exposed a seeded context to `clone()` before this diff, through the originals. The diff changes which plan object holds the context, not the context's contents.

**Verdict.** REFUTED. Pre-existing exposure, unchanged by the diff.

#### C11 REFUTED — the real-plan tests do not run with assertions enabled, so the production asserts are untested

**Claim.** `ReplayablePlanFixture`'s Javadoc and both new real-plan tests claim "this module compiles its surefire `argLine` with `-ea`", which is what makes the two production `assert copy != null && copy != …` statements executable under test. If the flag were absent, the acceptance criterion "a real-plan re-arm runs with assertions enabled" would be unmet.

**Check.** `core/pom.xml:36` opens the module `argLine` with `-ea`.

**Verdict.** REFUTED. The claim in the fixture is accurate.

#### C12 REFUTED — `YTDBQueryMetricsStep` can observe a plan from the wrong pass across a close-then-reset

**Claim.** `capturedExecutionPlan()` reads `getPlan()` / `getPlans().getFirst()` from inside the listener callback that `YTDBQueryMetricsStep.close()` fires (`:193-195`). If the boundary step swapped its plan before that read, the metrics record would attribute a pass's timings to the next pass's plan.

**Check.** The swap happens only in `openArming()` (`AbstractMatchPlanStep:445`), which runs on the first `processNextStart()` of a pass, never in `reset()` or `close()`. `Traversal.close()` walks the step list in order, and the boundary step is the traversal's only translated step while the metrics step is appended last, so the boundary closes first and leaves the plan reference untouched. `YTDBQueryMetricsStep.close():162` returns early when `hasStarted` is false, so a `reset()` with no re-run reports nothing at all. `getPlan_tracksThePlanThatProducedTheCurrentPass_acrossACloseThenReset` pins all four observation points.

**Verdict.** REFUTED. The window the new Javadoc claims is the window the code provides.

## Reviewer notes

**Concurrency triage gap here.** The diff adds plain (non-`volatile`) writes to the non-final `plan` and `plans` fields on the iteration path (`YTDBMatchPlanStep:153`, `MultiPlanMatchStep:287`), and both fields are read through public accessors that `YTDBQueryMetricsStep.capturedExecutionPlan()` calls. Both new comments reason explicitly about JMM publication. That is interleaving-and-publication territory, which `review-concurrency` owns; if this step was not triaged onto the `concurrency` category, it may need to be. I did no interleaving analysis.

**Verification performed.** Ran `./mvnw -pl core -o test -Dtest='YTDBMatchPlanStepTest,MultiPlanMatchStepTest'`: 41 and 31 tests respectively, zero failures, zero errors. The four findings above are all reasoning-and-invariant defects that a green suite does not contradict.

**Scope note.** No blocker survived the refutation pass. The state machine itself — the six-state enum, the `processNextStart()` dispatch, the `reset()` mapping, and the `close()` gating — is exhaustive and sequentially sound: every enum constant is handled on both dispatch paths, and every route into `CLOSED` nulls `openStream` first, so the re-arm never inherits a stale cursor. The findings sit at the edges: one comment that specifies the machine wrongly, one state the machine conflates, one inert constraint, and one non-atomic copy loop.
