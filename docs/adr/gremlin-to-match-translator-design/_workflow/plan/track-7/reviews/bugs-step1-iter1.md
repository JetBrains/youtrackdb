<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 8, matches: 0}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: REFUTED, anchor: "#### C6 "}
  - {id: C7, verdict: NOTE, anchor: "#### C7 "}
  - {id: C8, verdict: NOTE, anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

## Findings

No bugs found. The step is a clean, behavior-neutral extraction of `AbstractMatchPlanStep` from `YTDBMatchPlanStep`: the lifecycle, state machine, and projection code moved verbatim, with `plan.getContext()` / `plan.reset(ctx)` / `plan.start()` / `plan.close()` replaced 1:1 by the `planContext()` / `rewindPlan(ctx)` / `startPlanStream()` / `closePlan()` hooks that the concrete subclass implements. Every candidate defect in the focus set (stream lifecycle, clone isolation, per-arming graph injection, projection behavior-neutrality, null-safety / RID handling) was traced to a refutation. The refutation trail is in the Evidence base.

## Evidence base

Reference-accuracy caveat: mcp-steroid PSI (`steroid_execute_code`) is non-functional in this repo (cold-kotlinc compile exceeds the ~60s MCP HTTP limit), so all symbol audits below are grep + declaration reads over `core/src`, not PSI find-usages `(grep-only)`. The enumeration covered both `main` and `test` trees.

#### C1 Per-arming graph (A1): no null/stale `armingGraph` read on any projection path — REFUTED
Focus claim: projection could read a null or stale `armingGraph`. Traced every path into projection.
- `armingGraph` is set in `openArming()` (AbstractMatchPlanStep.java:318-326) before `startPlanStream()` and before any row is pulled, and is nulled only in `releaseStream()` (:370), `releaseStreamAndClosePlan()` (:385), and `resetLifecycleForClone()` (:465).
- First pull: `processNextStart()` (:236-240) is in `State.NEW`/`REARMED`, calls `openArming()` (sets `armingGraph`), moves to `OPEN`, then projects — non-null.
- Subsequent pulls: `State.OPEN` skips the open block, so `armingGraph` persists from the arming's first open across every pull; it is not re-nulled between pulls. Non-null.
- After drain: `releaseStream()` nulls `armingGraph` and moves to `DRAINED`; the next `processNextStart()` throws `FastNoSuchElementException` at :232-234 before any projection. Never projects with a null graph.
- `emitAccumulatedGroupMap()` (:283-297) runs inside the `try` after `openArming()`, so `armingGraph` is set for the whole drain; it is nulled only by the trailing `releaseStream()` after the map is fully built.
- `projectOrSkip` passes `armingGraph` to `projectElement(row, armingGraph)` while `projectMap`/`projectSingleValue`/`projectScalar` read the `armingGraph` field via `convertValue`/`convertMapColumn`/`convertGroupValue` — identical to the pre-refactor code (deleted lines 1234-1241 of the diff read the field the same way). No drift. A1 is honored: the graph is per-arming-injected, never construction-captured.

#### C2 Clone isolation: no double-close and no leak of the parent's live stream — REFUTED
Candidate: the clone shares or tears down the original's `openStream`/`armingGraph`/`state`.
- `YTDBMatchPlanStep.clone()` (YTDBMatchPlanStep.java:94-124) does `super.clone()` (Object.clone via `AbstractStep`), installs the clone's own deep `plan` copy against an isolated child context, then calls `cloned.resetLifecycleForClone()`.
- `resetLifecycleForClone()` (AbstractMatchPlanStep.java:463-467) drops the aliased references (`openStream = null; armingGraph = null`) WITHOUT closing the stream, and sets `state = State.NEW`. This is byte-equivalent to the pre-refactor inline `cloned.openStream = null; cloned.armingGraph = null; cloned.state = State.NEW` (diff deleted lines 1223-1225). The original instance keeps and later closes its own stream; the clone never closes it. No double-close, no leak.
- `reset()` fired by `AbstractStep.clone()` on the freshly-cloned instance (:417-423) does not touch `openStream`/`armingGraph` (deliberate, per its Javadoc), so the parent's in-flight cursor is safe during the clone. Unchanged from the pre-refactor `reset()`.
- The fields moved to the base stay `private`; the concrete `clone()` reaches them only through the base's `protected final resetLifecycleForClone()`, correct encapsulation with identical effect.

#### C3 Hook dispatch returns identical values to the inlined plan calls — REFUTED
Candidate: a hook returns a different value than the code it replaced.
- `planContext()` → `plan.getContext()`; `rewindPlan(ctx)` → `plan.reset(ctx)`; `startPlanStream()` → `plan.start()`; `closePlan()` → `plan.close()` (YTDBMatchPlanStep.java:128-146). Each is a 1:1 substitution with the same argument. `rewindPlan(ctx)` receives `ctx = planContext() = plan.getContext()`, the same object the pre-refactor `plan.reset(ctx)` used.
- `plan` is `@Nonnull`, set in the concrete constructor after `super(...)` returns and re-set to a non-null copy in `clone()`, so every hook call dereferences a non-null plan — no new NPE surface.

#### C4 No overridable hook invoked during construction — REFUTED
Candidate: the base constructor calls a hook before the subclass sets `plan`.
- `AbstractMatchPlanStep` constructor (:178-192) sets only its own fields (`super(traversal)` + the six projection/shaping fields); it invokes none of the abstract hooks. The concrete constructor sets `this.plan` after `super(...)` returns (YTDBMatchPlanStep.java:84-85). The first hook call happens at iteration time (`processNextStart` → `openArming`), long after construction. No partially-constructed-object dispatch.

#### C5 Stream lifecycle and exception paths preserved byte-for-byte — REFUTED
Candidate: the move altered open/drain/close, the `State` transitions, `addSuppressed` ordering, or `close()` idempotency.
- `processNextStart` (:231-276), `emitAccumulatedGroupMap` (:283-297), `openArming` (:310-360), `releaseStream` (:367-374), `releaseStreamAndClosePlan` (:382-402), `reset` (:417-423), `close` (:435-452) are line-identical to the deleted originals (diff lines 977-1201) except the four hook substitutions in C3. The `NEW/OPEN/DRAINED/REARMED/CLOSED` transitions, the "iteration failure → CLOSED + release stream and plan, original exception primary, release failure `addSuppressed`" path, the partial-start `closePlan()`-on-throw path, and the `close()` CLOSED-gate idempotency are all unchanged. Behavior-neutral.

#### C6 Idempotency-scan retarget is a behavior-preserving superset — REFUTED
Candidate: retargeting the `instanceof` from `YTDBMatchPlanStep` to `AbstractMatchPlanStep` changes recognition.
- `containsBoundaryStep` now tests `step instanceof AbstractMatchPlanStep<?,?>` (GremlinToMatchStrategy.java:350). `YTDBMatchPlanStep` is today the only concrete subclass of `AbstractMatchPlanStep` `(grep-only, both trees)`, so the set of matched instances is exactly the pre-refactor set. The `hasVertexGraphStart` O(1) gate and the construction site (`:444`, still `new YTDBMatchPlanStep(...)`) are unchanged. Behavior-neutral; the broadening only prepares for Track 8's sibling.

#### C7 No test stranded by the move — NOTE (verified, not a defect)
The changed-files set carries no test files. Confirmed this is correct, not an omission:
- `YTDBMatchPlanStepTest:854` reads `YTDBMatchPlanStep.class.getDeclaredField("plan")` — the `plan` field stays declared in the concrete class, so this still resolves.
- `projectElement` is called as an instance method (`step.projectElement(row, graph)`, YTDBMatchPlanStepTest:288/308/309/332/348); it is now inherited from the base but package-private in the same `...translator.step` package, so inherited instance calls compile and run. No test does `getDeclaredMethod("projectElement", ...)` on the concrete class `(grep-only)`.
- `new YTDBMatchPlanStep(...)` (both public ctors) and `instanceof YTDBMatchPlanStep` in tests remain valid. The refactor deliberately kept `plan` in the concrete class and `projectElement` package-private-inherited to preserve exactly these test hooks (track R1).

#### C8 Concurrency-relevant code is unchanged by this move — NOTE (triage backstop)
`clone()` carries JMM/publication reasoning (non-final `plan`, `setParentWithoutOverridingChild`, the "concurrent clones must not touch the shared parent context" invariant) and the per-instance lifecycle fields (`openStream`/`armingGraph`/`state`) are mutable shared-ish state. This is concurrent-looking code, so flagging it for the orchestrator's triage awareness: if `review-concurrency` was not triaged onto this step, the clone-publication / per-execution-isolation model is its territory. Caveat for the reader — this behavior-neutral move introduces no new shared-mutable surface (the fields kept `private`, the JMM logic and per-run context isolation are verbatim), so no new interleaving hazard originates here; the note is a routing pointer, not an interleaving analysis (which is `review-concurrency`'s alone).
