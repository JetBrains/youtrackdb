<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: BC1, sev: should-fix, loc: YTDBMatchPlanStep.java:258, anchor: "### BC1 ", cert: C1, basis: "clone copies plan against the shared CommandContext (plan.getContext()), not an isolated one; independent step chains still share non-thread-safe per-run context state, unlike HashJoinMatchStep's isolated copy"}
  - {id: BC2, sev: should-fix, loc: YTDBMatchPlanStep.java:282, anchor: "### BC2 ", cert: C2, basis: "reflective write to a final field after super.clone() is unsafely published under the JMM final-field freeze and is avoidable by reconstructing the clone via ctor (HashJoinMatchStep.copy pattern) or a non-final field"}
  - {id: BC3, sev: suggestion, loc: YTDBMatchPlanStep.java:127, anchor: "### BC3 ", cert: C3, basis: "GraphStep.reset() re-arms the inherited done/iterator fields for re-iteration but leaves started=true, so a TinkerPop-driven reset()+re-iterate on the same instance throws IllegalStateException, diverging from GraphStep's reset contract"}
  - {id: BC4, sev: suggestion, loc: YTDBMatchPlanStep.java:258, anchor: "### BC4 ", cert: C4, basis: "clone() after the original has been drained/closed copies a closed plan; low likelihood on the current path but the copy assumes a live plan"}
evidence_base: {section: "## Evidence base", certs: 4, matches: 0}
cert_index:
  - {id: C1, verdict: PLAUSIBLE, anchor: "#### C1 "}
  - {id: C2, verdict: PLAUSIBLE, anchor: "#### C2 "}
  - {id: C3, verdict: PLAUSIBLE, anchor: "#### C3 "}
  - {id: C4, verdict: PLAUSIBLE, anchor: "#### C4 "}
flags: [CONTRACT_OK]
-->

## Findings

### BC1 [should-fix] clone() copies the plan against the shared CommandContext, not an isolated one

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStep.java` (line 258)

**Issue.** `clone()` calls `plan.copy(plan.getContext())`. `SelectExecutionPlan.copy(ctx)` (SelectExecutionPlan.java:238-242) builds a fully independent step chain but stores the passed-in `ctx` on the copy verbatim. Passing the original's own context makes the original plan and the clone's plan point at the **same** `CommandContext`. `BasicCommandContext` backs its variable maps with plain `HashMap` / `Int2ObjectOpenHashMap` (BasicCommandContext.java:56, 485) — no synchronization. The step's own Javadoc (lines 92-103) and R1 justify the copy as "per-execution isolation ... two executions with no shared mutable plan state," but the step chain is only half of that state; the `CommandContext` carries per-run variables (`$current`, `$matched`, input parameters, the step-stats map) that later MATCH steps read and write.

The step comment explicitly contrasts this with `HashJoinMatchStep`, but the contrast is inverted from what the code does. `HashJoinMatchStep.buildHashSet`/`nestedLoopProbe` (HashJoinMatchStep.java:143-146, 343-345) create a **fresh** `BasicCommandContext` and `setParentWithoutOverridingChild(ctx)` before `buildPlan.copy(isolatedCtx)`, precisely so the copied plan's `$matched` cannot pollute the parent. This step reuses the parent context, so the isolation `HashJoinMatchStep` buys is not achieved here.

**Failure scenario.** Two independent executions derived from one source (original + clone) run against the shared `CommandContext`. If both are alive at once (TinkerPop clones a traversal per spawn; two spawns iterated on different threads), their `MatchStep`s race on the shared context's unsynchronized variable maps — lost updates, `ConcurrentModificationException`, or a `$current`/`$matched` value from one execution leaking into the other. Even single-threaded, sequential re-execution (original drained, then clone started) inherits residual `$current`/`$matched` from the original's run because the maps are shared and never reset between the two plans.

**Why should-fix, not blocker.** The only shape Track 2 recognizes is the single-node `g.V()` pattern, whose plan has no `$matched` dependency and no multi-alias join, so the shared-context state that collides is empty today. The defect is latent: it activates when a later track plans a shape that writes context variables, and the boundary step is the cross-cutting scaffolding every later track reuses unchanged. The decision log records R1 as `plan.copy(ctx)` with `ctx = plan.getContext()`, so this may be the intended contract — but the code's own comparison to `HashJoinMatchStep` claims an isolation property the shared context does not provide.

**Refutation considered.** I checked whether `BasicCommandContext` is thread-safe (it is not — plain maps, no locks) and whether the single supported shape actually touches shared context variables (it does not, which is why this is not a live blocker). I confirmed `HashJoinMatchStep` uses a fresh isolated context, so the "mirrors HashJoinMatchStep" comment overstates the parity. I could not, within this diff, prove that TinkerPop concurrently iterates an original and its clone for this step class — that would require a caller audit the diff does not contain — so the concurrent-race arm is PLAUSIBLE rather than CONFIRMED; the sequential residual-state arm holds regardless of threading.

**Suggestion.** Copy against an isolated child context, mirroring `HashJoinMatchStep`: `var isolated = new BasicCommandContext(); isolated.setParentWithoutOverridingChild(plan.getContext()); var copiedPlan = plan.copy(isolated);`. If sharing the session is required, share only the session/transaction, not the full mutable context. Either way, correct the Javadoc so it no longer claims `HashJoinMatchStep`-equivalent isolation while reusing the parent context.

### BC2 [should-fix] Reflective write to a final field is unsafely published and avoidable

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStep.java` (line 282, invoked from 261)

**Issue.** `plan` is a `final` field (line 112). `clone()` cannot reassign it through normal assignment, so `setPlanField` writes it via `Field.setAccessible(true); field.set(...)` after `super.clone()` has already frozen the shallow copy's final fields. Two problems:

1. **Safe-publication gap.** The JMM's final-field guarantee (a correctly-constructed object's final fields are visible to any thread that sees a reference to it, without further synchronization) is voided when a final field is mutated after construction via reflection. The clone is constructed by `Object.clone()` (which freezes `plan` pointing at the original's plan), then `setPlanField` overwrites it. A thread that later receives the clone reference without a happens-before edge to the reflective write may observe the pre-write value — the original's plan — reintroducing the exact plan-sharing R1 forbids. TinkerPop hands cloned traversals to worker threads in several execution paths, so "the clone is never published across threads" is not guaranteed by this class alone.

2. **Avoidable smell.** The sibling `HashJoinMatchStep.copy(ctx)` (HashJoinMatchStep.java:441-445) achieves the same per-execution plan copy by **reconstructing via the constructor** — no reflection, no final-field mutation. `YTDBMatchPlanStep` could do the same (build a new instance with the copied plan and the same alias/outputType, then transfer the inherited GraphStep labels/traversal), or make `plan` non-final and assign it directly in `clone()`. Both eliminate the reflection and restore normal field-write visibility semantics.

**Failure scenario.** Under a graph-computer or parallel-iteration path, thread T1 runs `clone()` (reflective write installs the copied plan) and publishes the clone to thread T2 without a synchronizing edge; T2 reads `cloned.plan` and, under the final-field-freeze semantics that the reflective write violated, may see the frozen original plan reference. T1 and T2 then both drive the **same** original plan — the single-shot `started` guard trips on one side (`IllegalStateException`) or, if the original was cloned before starting, both executions corrupt the one plan's step-chain state.

**Refutation considered.** I confirmed the field is genuinely `final` (line 112) and that `super.clone()` (AbstractStep.clone, decompiled) does not reconstruct fields — it shallow-copies via `Object.clone()` then resets a few, leaving `plan` pointing at the original. The `setPlanField` failure path is correct (it throws `IllegalStateException` loudly on a field rename, per line 291), so the fail-loud requirement is met; the issue is the visibility/design smell, not fail-safety. This is PLAUSIBLE rather than CONFIRMED because the visibility hole only bites on a cross-thread publish without a happens-before edge, which I cannot demonstrate is reachable from this diff alone — but the cleaner ctor-based construction (already used by the sibling) removes both the smell and the risk at no cost.

**Suggestion.** Reconstruct the clone through the constructor (as `HashJoinMatchStep.copy` does) so `plan` is assigned during construction and the final-field freeze covers the copied plan, or make `plan` non-final and assign it in `clone()`. Drop `setPlanField` entirely.

### BC3 [suggestion] reset() re-arms the inherited fields but leaves started=true, breaking re-iteration on the same instance

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStep.java` (line 127, in relation to createIterator lines 179-185)

**Issue.** `GraphStep.reset()` (decompiled) sets `done=false` and `iterator=EmptyIterator`, which re-arms the step so the next `processNextStart()` calls `iteratorSupplier.get()` again (GraphStep.processNextStart, decompiled: `done` is set true then the supplier is invoked). The `started` flag is not part of the inherited GraphStep state and is not reset by `reset()` — only `clone()` resets it (line 265). So a `reset()` followed by re-iteration on the **same** instance calls `createIterator()` a second time, hits `if (started)` (line 180), and throws `IllegalStateException`. This diverges from the `GraphStep`/`Step` contract, under which `reset()` is precisely the operation that makes a step re-iterable.

**Failure scenario.** TinkerPop calls `Step.reset()` on a start step in re-execution and traversal-reuse paths (e.g. a traversal cloned and reset for reuse rather than freshly cloned, or an engine that resets the whole step list before a second `applyStrategies`/iteration). If the engine resets this boundary step and then re-drives it, the second `createIterator()` throws `IllegalStateException("invoked twice")` instead of re-running the plan — surfacing to the user as a spurious failure on a re-iterated traversal.

**Refutation considered.** The design intent (Javadoc lines 116-126) is that the plan is single-shot per instance and re-execution goes through `clone()`, which does reset `started`. For the common top-level `g.V().toList()` path, `reset()` is not called mid-flight on the start step, so this is not a live bug on the recognized shape. It is a contract mismatch that becomes reachable if any TinkerPop path resets-and-re-iterates rather than clones. I could not prove such a path is exercised for this step from the diff alone (a caller/engine audit is out of scope), so this is a suggestion, not a should-fix.

**Suggestion.** Either override `reset()` to throw a clear "boundary step is single-shot; clone to re-run" message (making the single-shot contract explicit at the lifecycle method), or — if `reset()` is meant to re-arm the step — reset `started=false` inside `reset()` and re-open the plan (which requires the plan to be re-startable, i.e. `plan.reset(ctx)` before the next `start()`). At minimum, document that `reset()` does not make this step re-iterable and that clone is the only re-execution path.

### BC4 [suggestion] clone() of an already-drained/closed original copies a closed plan

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStep.java` (line 258)

**Issue.** `clone()` reads `plan.getContext()` and calls `plan.copy(...)` on the original's `plan`. If the original has already been iterated to exhaustion (or `Traversal.close()` fired), the close hook has run `plan.close()` (lines 220-223), and `SelectExecutionPlan.close()` propagates `lastStep.close()` (SelectExecutionPlan.java:76-78). Cloning after that copies a closed plan. `copy()` reconstructs the step chain from scratch via `step.copy(ctx)` (SelectExecutionPlan.copyOn, lines 260-272), so the copy is structurally fresh regardless of the source plan's closed state — but this relies on `ExecutionStepInternal.copy()` being safe to call on a closed step, which is not guaranteed by the `InternalExecutionPlan` contract (the documented lifecycle at InternalExecutionPlan.java:18-25 puts `copy()` before `start()`/`close()`, not after `close()`).

**Failure scenario.** A traversal is partially or fully iterated (plan closed by the close hook), then TinkerPop clones it for reuse. `clone()` copies the closed plan; if any step's `copy()` reads state invalidated by `close()`, the copy is malformed or the copy throws. Low likelihood on the current path — TinkerPop typically clones before first iteration — but the diff does not guard against clone-after-close.

**Refutation considered.** I confirmed `getContext()` returns the stored `ctx` unconditionally (SelectExecutionPlan.java:70-72), so it survives close. `copyOn` reconstructs steps rather than reusing them, so a closed source plan does not directly corrupt the copy's chain. Whether `step.copy(ctx)` is safe on a closed step is step-implementation-specific and not verifiable from this diff, so this is a suggestion flagging an unguarded assumption, not a confirmed defect.

**Suggestion.** If clone-after-close is not a supported path, document it; if it is reachable, guard `clone()` (e.g. assert the plan is not closed, or track a per-instance closed flag and reject cloning a closed original with a clear message).

## Evidence base

#### C1 shared-context copy — PLAUSIBLE
`SelectExecutionPlan.copy(ctx)` stores the passed `ctx` on the copy (SelectExecutionPlan.java:238-242, 276) and `copyOn` produces an independent step chain (lines 260-272). `plan.copy(plan.getContext())` therefore yields two plans sharing one `CommandContext`. `BasicCommandContext` variable maps are unsynchronized `HashMap`/`Int2ObjectOpenHashMap` (BasicCommandContext.java:56, 371-374, 485). `HashJoinMatchStep` isolates via a fresh `BasicCommandContext` + `setParentWithoutOverridingChild` (HashJoinMatchStep.java:143-146, 174-175, 343-345). Refutation check on the "actively-a-bug-today" arm: the single recognized shape has no `$matched`/join context state, so the collision set is empty now — CONFIRMED as a latent defect / documentation-vs-code mismatch, PLAUSIBLE as a live concurrent race (no in-diff proof that original + clone iterate concurrently for this step class).

#### C2 reflective final-field write — PLAUSIBLE
`plan` is `final` (YTDBMatchPlanStep.java:112); `setPlanField` mutates it post-`super.clone()` via reflection (lines 282-293). `AbstractStep.clone()` (decompiled: `Object.clone()` + selective field resets, no reconstruction of `plan`) leaves the shallow copy's `plan` frozen at the original's referent until the reflective write. `HashJoinMatchStep.copy` (HashJoinMatchStep.java:441-445) reconstructs via ctor with no reflection. Refutation: the fail-loud-on-rename requirement is met (line 287-292); the residual issue is JMM safe-publication of a post-construction final-field mutation plus an avoidable smell. PLAUSIBLE (cross-thread publish without a happens-before edge not proven reachable from this diff), fix is free via the sibling's ctor pattern.

#### C3 reset() vs started — PLAUSIBLE
`GraphStep.reset()` (decompiled) sets `done=false`, `iterator=EmptyIterator`; `processNextStart()` (decompiled) sets `done=true` then invokes `iteratorSupplier.get()`, so a re-armed step re-invokes `createIterator()`. `createIterator()` throws on `started` (YTDBMatchPlanStep.java:179-185). Only `clone()` resets `started` (line 265); `reset()` does not. Refutation: the recognized shape's top-level path does not reset-and-re-iterate the start step, and the design routes re-execution through clone — so this is a contract divergence, reachable only if a TinkerPop path resets-then-re-iterates this instance (not proven from the diff). PLAUSIBLE → suggestion.

#### C4 clone-after-close — PLAUSIBLE
Close hook runs `plan.close()` (YTDBMatchPlanStep.java:220-223); `SelectExecutionPlan.close()` → `lastStep.close()` (SelectExecutionPlan.java:76-78). `clone()` then copies via `copyOn`, which reconstructs steps (lines 260-272) rather than reusing closed ones, and `getContext()` survives close (lines 70-72). Whether `step.copy(ctx)` is safe on a closed step is not guaranteed by the documented lifecycle (InternalExecutionPlan.java:18-25 orders `copy` before `close`). PLAUSIBLE, unguarded-assumption suggestion.
