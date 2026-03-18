# Track 3 Risk Review

## Iteration 1

### Finding R1 [should-fix]
**Location**: ResultInternal Relation<?> constructor, MatchEdgeTraverser, OptionalMatchEdgeTraverser
**Issue**: After Track 3, no code path returns a Relation<?> object, making several dispatch branches dead code. The ResultInternal.relation field is only populated from the Relation constructor and becomes permanently unused. Risk: if dead branches are not removed before type deletion, compile errors result.
**Proposed fix**: Remove all Relation<?> dispatch branches in a step BEFORE the Relation type deletion. Order: remove callers → remove interface methods → delete types.

### Finding R2 [should-fix]
**Location**: EntityImpl.getEntities()/getBidirectionalLinks() + SQLFunctionMove.v2v()/v2e()
**Issue**: Deleting EntityImpl traversal methods without updating SQLFunctionMove callers causes compile errors. Risk is low (straightforward update) but must be done atomically.
**Proposed fix**: Delete EntityImpl methods and update SQLFunctionMove in the same step.

### Finding R3 [should-fix]
**Location**: BidirectionalLinksIterable reparameterization
**Issue**: Reparameterizing from Relation<T> to Edge is only type-safe AFTER EntityImpl.getEntities() (the sole caller passing non-Edge Relation types) is deleted. If done in wrong order, compile errors.
**Proposed fix**: Enforce step ordering: delete EntityImpl methods first, then reparameterize.

### Finding R4 [should-fix]
**Location**: RelationsIteratorAbstract inlining into EdgeIterator
**Issue**: RelationsIteratorAbstract (122 lines) implements filtering/null-skipping/lazy iteration logic. Inlining requires converting generic type parameters to concrete Vertex/EdgeInternal types and preserving: null-skipping in hasNext() loop, label filtering via isLabeled(), exception handling (RecordNotFoundException logged and skipped). No dedicated EdgeIterator unit tests exist — behavior is covered by integration tests (Cucumber suite, graph traversal tests).
**Proposed fix**: During inlining, carefully preserve all edge cases. Rely on existing integration test coverage (Cucumber ~1900 scenarios exercise edge iteration extensively). Coverage gate will catch gaps.

### Finding R5 [should-fix]
**Location**: SQL function Relation<?> overloads — SQLFunctionMove and subclasses
**Issue**: SQLFunctionMove has move(db, Relation<?>, labels) and e2v(Relation<?>, Direction) overloads that become dead code. Subclasses (SQLFunctionOut, SQLFunctionIn, SQLFunctionBoth, etc.) may override move(). If dead overloads aren't removed atomically with the dispatch, they persist as confusing dead code.
**Proposed fix**: Remove all Relation-typed overloads in the same step as the dispatch removal.

### Finding R6 [suggestion]
**Location**: Track 3 step count
**Issue**: The scope indicator says "~3-4 steps" but the actual complexity (revealed by dependency analysis) suggests 4 steps is the minimum for safe commit ordering: (1) remove dispatch/callers, (2) reparameterize + remove interface methods, (3) inline iterators, (4) atomic delete. This matches the upper bound of the scope indicator.
**Proposed fix**: Decompose into 4 well-ordered steps.

## Summary
No blockers. All risks are mitigable through careful step ordering. The key risk pattern is compile-order dependency: callers must be removed before interface methods, which must be removed before type deletion. The 4-step decomposition addresses this naturally.
