<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: CQ1, sev: suggestion, loc: ResultShaping.java:10, anchor: "### CQ1 ", cert: n/a, basis: "class Javadoc names YTDBMatchPlanStep as the shaping reader after projection moved wholesale to AbstractMatchPlanStep; self-contradicts the new @param"}
  - {id: CQ2, sev: suggestion, loc: ListShapingOp.java:34, anchor: "### CQ2 ", cert: n/a, basis: "interface contract omits the once-per-arming re-invocation / fresh-iterator guarantee that Track 9 fold/tail buffer ops depend on"}
evidence_base: {section: "## Evidence base", certs: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### CQ1 [suggestion] ResultShaping class Javadoc still names YTDBMatchPlanStep as the shaping reader

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/ResultShaping.java` (line 10)

**Issue**: The class Javadoc says each instance is built by a terminator "and `{@link YTDBMatchPlanStep}` reads it when projecting each MATCH row onto a traverser." After this track, all projection and all shaping reads live in `AbstractMatchPlanStep`; `YTDBMatchPlanStep` now contains only the plan field, constructors, `getPlan()`, `clone()`, and the four plan-seam hooks — no projection code at all. The `{@link}` sends a maintainer to the subclass, where none of the described row-projection logic exists. The same doc already contradicts itself: the newly added `@param listShapingOps` (line 35) correctly points the reader to `AbstractMatchPlanStep` ("the boundary base bypasses the stage entirely"). The project rule is to keep comments in sync when behavior moves.

**Suggestion**: Change the class-doc reference from `{@link YTDBMatchPlanStep}` to `{@link AbstractMatchPlanStep}` (the boundary base) so both the class-level sentence and the `listShapingOps` param name the same reader.

### CQ2 [suggestion] ListShapingOp Javadoc omits the once-per-arming re-invocation contract

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/ListShapingOp.java` (line 34, the interface Javadoc)

**Issue**: The interface documents cardinality (1->1 / 1->N / N->1) and asks implementations to "stay lazy where the op allows it," but never states that `apply` is invoked more than once for the same step. The base rebuilds `shapedPayloads` on every (re)open of an arming — `processNextStart` nulls it on reopen, then `openShapedPayloads` -> `applyListShaping` calls `op.apply(...)` afresh each arming (reset + reopen, and per child plan in Track 8's multi-plan form). An op must therefore return a fresh, independent iterator on each `apply` call and hold no state across calls, or a re-iterated traversal replays stale output. This is load-bearing for the ops Track 9 registers: `fold` and `tail` carry buffers, and an implementation that allocates the buffer once outside the returned iterator (rather than per `apply` as the test's `TagRepeatOp` does) would break silently on re-iteration. The contract clarity here is exactly what a `@FunctionalInterface` implementer keys off.

**Suggestion**: Add one sentence to the interface Javadoc: `apply` may be called more than once per step (once per arming — on reset + reopen, and per child plan for a multi-plan boundary), so each call must return an independent iterator and the op must retain no state across calls.

## Evidence base
