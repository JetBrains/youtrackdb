<!-- MANIFEST
verdict: PASS
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: A1, sev: should-fix, loc: YTDBGraphStep.java:reset, anchor: "### A1 ", cert: C2, basis: "reset() override that skips super.reset() breaks graph-step re-iteration; the D4 invariant test passes anyway and masks the regression"}
  - {id: A2, sev: suggestion, loc: QueryMetricsListener.java:QueryDetails.getExecutionPlan, anchor: "### A2 ", cert: C4, basis: "plan accessor is only valid inside the queryFinished callback (lazy source-step walk over a reset()-cleared field); contract should say so"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 3}
flags: [CONTRACT_OK]
-->

## Findings

### A1 [should-fix]
**Certificate**: C2 (Violation scenario — `getLastExecutionPlan()` returns null after reset())
**Target**: Invariant "`getLastExecutionPlan()` returns null after `reset()`" (D4) / Plan of Work step 2
**Challenge**: The Plan of Work step 2 says to "clear the field in `reset()`" but `YTDBGraphStep` today has **no** `reset()` override at all (verified: the class ends at line 211 with no `reset` method) — `GraphStep.reset()`/`AbstractStep.reset()` run as inherited. Introducing a `reset()` override that clears `lastExecutionPlan` silently changes behavior if it forgets to chain `super.reset()`: the inherited reset resets the step's cached iterator and traverser state used by both the by-id and query branches, and a re-iterated traversal would then reuse stale iterator state. The dangerous part is that the D4 invariant test the track adds ("after `reset()`, `getLastExecutionPlan()` returns `null`") **passes even with a broken super-chain**, because the field-clear line is exactly what the test checks — so the regression is masked, not caught, by the very test meant to cover this step.
**Evidence**: `YTDBGraphStep.java` has no `reset()` method (lines 1-211); the plan text ("clear the field in `reset()`") does not name the `super.reset()` obligation. Reference-accuracy caveat: `GraphStep.reset()`/`AbstractStep.reset()` bodies were not read from source (fork classes not extracted locally, mcp-steroid unreachable); the super-chain obligation is the standard TinkerPop `Step.reset()` contract (reset traverser/iterator state) rather than a line-verified fact here.
**Proposed fix**: Amend Plan of Work step 2 to state the override must call `super.reset()` **then** clear the field, and add an acceptance line that a traversal re-iterated after `reset()` still returns correct rows (not just that the plan is null) so the invariant test cannot pass on a broken super-chain.

### A2 [suggestion]
**Certificate**: C4 (Assumption test — the plan accessor is valid whenever the listener holds a `QueryDetails`)
**Target**: Assumption behind Plan of Work steps 1 and 3 (accessor contract) / Invariant "`getSteps()`/`prettyPrint()` succeed after the result set closes"
**Challenge**: Step 3 overrides `getExecutionPlan()` on the anonymous `QueryDetails` to resolve the source step *lazily* via `getFirstStepOfAssignableClass(...)` and read `getLastExecutionPlan()` at call time. That is only meaningful **while the field still holds the captured plan** — i.e. inside the `queryFinished(...)` invocation, before the traversal is reset/reused. A consumer that retains the `QueryDetails` object and calls `getExecutionPlan()` after the callback returns can observe `null` or a stale plan once `reset()` (D4) runs on traversal reuse. This is not a new hazard — `getQuery()` is equally lazy (it reads `traversal.getBytecode()` on demand, `YTDBQueryMetricsStep.java:129-133`) — but the plan's step-1 javadoc contract as described states only "read-only; `null` means no plan captured" and does not pin the validity window.
**Evidence**: `YTDBQueryMetricsStep.java:124-145` builds the anonymous `QueryDetails` whose getters read live state lazily; D4 clears `lastExecutionPlan` on `reset()`. The accessor therefore inherits the same callback-scoped validity as `getQuery()`.
**Proposed fix**: Add one sentence to the step-1 javadoc: the returned plan (like `getQuery()`) is only guaranteed valid for the duration of the `queryFinished` callback; a consumer that inspects it must do so synchronously and not cache the `QueryDetails`.

## Evidence base

#### C1 [SCOPE — track sizing] MATCHES (survives, no finding)
- **Chosen approach**: One single track of 3 source files (`QueryMetricsListener`, `YTDBGraphStep`, `YTDBQueryMetricsStep`) + 1-2 existing test files, no split.
- **Best rejected alternative**: split interface change from capture/wiring; or merge into a neighbor.
- **Counterargument trace**:
  1. Footprint is ~3 source + ~1-2 test files, far below the ~20-25 split candidate bound and above the trivially-mergeable floor.
  2. There is no neighbor track (single-track change; `## Interfaces and Dependencies` records no upstream/downstream deps), so merge is not applicable and split would create an artificial interface-only PR that does not stand alone (an accessor no caller reads).
  3. Bottom-up ordering (interface → capture → wiring → tests) is the natural compile-forward sequence and each edit compiles against the one below.
- **Codebase evidence**: `track-1.md` §Interfaces and Dependencies "Dependencies: none"; three in-scope source files all in `core`.
- **Survival test**: YES — sizing is correct; no split/merge finding.

#### C2 [INVARIANT] CONSTRUCTIBLE — produced A1
- **Invariant claim**: after `reset()`, `getLastExecutionPlan()` returns `null` (D4), with no other behavior change.
- **Violation construction**:
  1. Start state: a traversal is executed once (query branch populates `lastExecutionPlan`), then re-iterated (TinkerPop calls `reset()` on step reuse).
  2. Action sequence: the new `reset()` override clears the field but (if mis-implemented) omits `super.reset()` — `YTDBGraphStep.java` currently inherits reset, so the override is net-new code.
  3. Intermediate state: the step's inherited iterator/traverser state from the prior run is not reset.
  4. Violation point: re-iteration reuses stale iterator state; the D4 test still sees `getLastExecutionPlan()==null` and passes.
  5. Observable consequence: wrong/duplicate rows on traversal reuse, undetected by the added test.
- **Feasibility**: CONSTRUCTIBLE (a plausible implementation slip that the planned test cannot catch). Reference-accuracy caveat noted in A1: the exact inherited `reset()` body was not read from source.

#### C3 [ASSUMPTION — helper exists and scans root-direct steps] HOLDS (no finding)
- **Claim**: `TraversalHelper.getFirstStepOfAssignableClass(YTDBGraphStep.class, traversal)` exists, returns `Optional<S>`, and scans the root's direct steps only (D3).
- **Stress scenario**: method absent or recursive into child traversals.
- **Code evidence**: TinkerPop fork `TraversalHelper.java:412-418` — method present, `Optional<S>`, iterates `traversal.getSteps()` (direct steps only, non-recursive). The metrics step is added to the root traversal (`YTDBQueryMetricsStrategy.java` `apply`: guarded by `traversal.isRoot()`, then `traversal.addStep(metricsStep)`), and `YTDBQueryMetricsStep` holds that same root `traversal`, so the lookup resolves the root `YTDBGraphStep`.
- **Verdict**: HOLDS. (My initial repo-only grep missed the method — it lives in the TinkerPop fork dependency, not repo source; verified against the extracted fork source.)

#### C4 [ASSUMPTION — accessor validity window] FRAGILE — produced A2
- **Claim**: a listener holding a `QueryDetails` can read `getExecutionPlan()` and get the query's plan.
- **Stress scenario**: consumer caches the `QueryDetails` and calls `getExecutionPlan()` after the `queryFinished` callback returns, after the traversal is reset/reused.
- **Code evidence**: `YTDBQueryMetricsStep.java:124-145` — all `QueryDetails` getters read live state lazily; D4 clears the captured field on `reset()`. So the accessor is only valid synchronously inside the callback.
- **Verdict**: FRAGILE (holds within the callback; a documentation gap, not a mechanism defect).

#### C5 [SIMPLIFICATION] no simpler mechanism
- **Claim**: the same goal could be reached with fewer moving parts (e.g. reuse `YTDBGraphQuery.usedIndexes()`/`explain()` instead of capturing the executed plan).
- **Code evidence**: `YTDBGraphQuery.java:29-34` `explain()` runs a **second** `EXPLAIN <query>` round-trip and re-plans; capturing `resultSet.getExecutionPlan()` on the already-executed populating run avoids the second plan build (D2, vetted). No lighter mechanism exists that yields the plan that actually ran.
- **Verdict**: no simplification available; the three-edit shape is minimal.
