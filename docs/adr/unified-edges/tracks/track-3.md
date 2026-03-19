# Track 3: Delete Relation hierarchy

## Progress
- [x] Review + decomposition
- [x] Step implementation (4/4 complete)
- [ ] Track-level code review

## Base commit
`95564dedb3`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step 1: Remove Relation<?> dispatch from SQL functions, MATCH traversers, and ResultInternal; delete EntityImpl entity-traversal methods
  > **What was done:** Removed all runtime `Relation<?>` dispatch from
  > `SQLFunctionMove` (abstract method + 9 subclass overrides + execute
  > dispatch), `SQLFunctionMoveFiltered`, 3 MATCH traversers, and 2
  > `ResultInternal` switch arms. Deleted `e2v(Relation)` overload.
  > Simplified `v2v()` and `v2e()` to return null for plain entities.
  > Deleted `EntityImpl.getEntities()`/`getBidirectionalLinks()`/
  > `getBidirectionalLinksInternal()` and cascading overrides in
  > `VertexEntityImpl` and `StatefullEdgeEntityImpl`. Deleted dead
  > `EntityRelationsIterable`/`EntityRelationsIterator` (review fix).
  > Deleted `LinkBasedMatchStatementExecutionTest` and
  > `LinkBasedMatchStatementExecutionNewTest` (tested removed functionality).
  >
  > **What was discovered:** `MatchReverseEdgeTraverser` and
  > `MatchFieldTraverser` had no direct Relation refs — they delegate to
  > parent `MatchEdgeTraverser`. `SQLEngine.foreachRecord()` still has an
  > `isRelation()` dispatch path — deferred to Step 2 when `isRelation()`
  > is removed from interfaces.
  >
  > **What changed from the plan:** `EntityRelationsIterable` and
  > `EntityRelationsIterator` were deleted in this step (review fix) rather
  > than waiting for Step 4. Step 4's deletion list is reduced accordingly.
  >
  > **Key files:** `SQLFunctionMove.java` (modified), `SQLFunctionIn.java`
  > (modified), `SQLFunctionOut.java` (modified), `SQLFunctionBoth.java`
  > (modified), `SQLFunctionInE.java` (modified), `SQLFunctionOutE.java`
  > (modified), `SQLFunctionBothE.java` (modified), `SQLFunctionInV.java`
  > (modified), `SQLFunctionOutV.java` (modified), `SQLFunctionBothV.java`
  > (modified), `SQLFunctionMoveFiltered.java` (modified),
  > `MatchEdgeTraverser.java` (modified), `MatchMultiEdgeTraverser.java`
  > (modified), `OptionalMatchEdgeTraverser.java` (modified),
  > `ResultInternal.java` (modified), `EntityImpl.java` (modified),
  > `VertexEntityImpl.java` (modified), `StatefullEdgeEntityImpl.java`
  > (modified), `EntityRelationsIterable.java` (deleted),
  > `EntityRelationsIterator.java` (deleted),
  > `LinkBasedMatchStatementExecutionTest.java` (deleted),
  > `LinkBasedMatchStatementExecutionNewTest.java` (deleted)

- [x] Step 2: Reparameterize BidirectionalLinksIterable/BidirectionalLinkToEntityIterator from Relation<T> to Edge; remove ResultInternal.relation field and isRelation()/asRelation() from all interfaces
  > **What was done:** Reparameterized `BidirectionalLinksIterable` and
  > `BidirectionalLinkToEntityIterator` from generic `Relation<T>` to
  > concrete `Edge`/`Vertex` types (removed generic type parameter entirely).
  > Deleted `ResultInternal.relation` field, `Relation<?>` constructor,
  > `setRelation()`, and cleaned up all `relation != null` checks in 11
  > methods. Removed `isRelation()`/`asRelation()`/`asRelationOrNull()`
  > from `Result`, `Entity`, `Edge`, `ResultInternal`, `UpdatableResult`,
  > and `MatchStepUnitTest` test mock. Removed `isRelation()` dispatch
  > from `SQLEngine.foreachRecord()`.
  >
  > **What was discovered:** `DBRecord`, `EntityImpl`, `RecordBytes`, and
  > `EmbeddedEntityImpl` had no overrides of isRelation/asRelation — they
  > inherited defaults from `Entity`, so only `Entity`'s defaults needed
  > removal. `UpdatableResult` had a `setRelation()` override (throwing
  > UnsupportedOperationException) that needed deletion.
  >
  > **Key files:** `BidirectionalLinksIterable.java` (modified),
  > `BidirectionalLinkToEntityIterator.java` (modified),
  > `ResultInternal.java` (modified), `Result.java` (modified),
  > `Entity.java` (modified), `Edge.java` (modified),
  > `SQLEngine.java` (modified), `UpdatableResult.java` (modified),
  > `VertexEntityImpl.java` (modified),
  > `SQLFunctionShortestPath.java` (modified),
  > `MatchStepUnitTest.java` (modified)

- [x] Step 3: Inline RelationsIteratorAbstract into EdgeIterator, RelationsIterable into EdgeIterable
  > **What was done:** Inlined all fields and methods from
  > `RelationsIteratorAbstract` into `EdgeIterator` (hasNext loop,
  > next(), size/sizeable, resettable, edge loading). Inlined
  > `RelationsIterable` scaffolding into `EdgeIterable` (size/sizeable).
  > Removed `extends` clauses from both. Simplified constructors by
  > removing unused parameters (sourceVertex, connection, labels).
  > Updated all `new EdgeIterable(...)` call sites in VertexEntityImpl.
  >
  > **What was discovered:** The `filter()` method in
  > `RelationsIteratorAbstract` (label filtering via `isLabeled()`) was
  > dead code — never called within the class or externally. The
  > `sourceVertex`, `connection`, and `labels` fields inherited from
  > the abstract parents were also unused in EdgeIterator/EdgeIterable
  > (only used by the already-deleted EntityRelationsIterator and the
  > dead `filter()` method).
  >
  > **What changed from the plan:** Removed unused fields/parameters
  > (sourceVertex, connection, labels) from EdgeIterator and EdgeIterable
  > rather than carrying them forward. This simplified the constructor
  > signatures. The dead `filter()` method was not carried forward.
  >
  > **Key files:** `EdgeIterator.java` (modified), `EdgeIterable.java`
  > (modified), `VertexEntityImpl.java` (modified)

- [x] Step 4: Remove Relation<Vertex> from Edge/EdgeInternal hierarchy; delete Relation and all dependent types
  > **What was done:** Removed `extends Relation<Vertex>` from `Edge` and
  > `EdgeInternal` interfaces. Removed `extends Element` from `DBRecord`.
  > Removed `isLightweight()` default from `Edge`. Added `label()` to
  > `Edge` (previously inherited from `Relation`). Deleted 5 files:
  > `Relation.java`, `Element.java`, `LightweightRelationImpl.java`,
  > `RelationsIteratorAbstract.java`, `RelationsIterable.java`. Updated
  > `MultiCollectionIterator` to reference `EdgeIterator` instead of
  > `RelationsIteratorAbstract`. Review fix: removed dead
  > `getEntity(Direction)` from `Edge` and `StatefullEdgeEntityImpl`
  > (duplicated `getVertex(Direction)` with zero callers), fixed broken
  > `{@link Relation}` in `MatchMultiEdgeTraverser` Javadoc, renamed
  > 3 test methods in `MatchStepUnitTest` from "Relation" to "Edge".
  >
  > **What was discovered:** `getEntity(Direction)` from `Relation` had
  > zero callers — `StatefullEdgeEntityImpl.getEntity()` simply delegated
  > to `getVertex()`. Rather than moving it to `Edge`, it was deleted
  > entirely as dead API.
  >
  > **What changed from the plan:** `EntityRelationsIterator.java` and
  > `EntityRelationsIterable.java` were already deleted in Step 1 (review
  > fix), so they did not need deletion here. `getEntity(Direction)` was
  > not moved to `Edge` — it was removed as dead code instead.
  >
  > **Key files:** `Edge.java` (modified), `EdgeInternal.java` (modified),
  > `DBRecord.java` (modified), `MultiCollectionIterator.java` (modified),
  > `StatefullEdgeEntityImpl.java` (modified),
  > `MatchMultiEdgeTraverser.java` (modified),
  > `MatchStepUnitTest.java` (modified), `Relation.java` (deleted),
  > `Element.java` (deleted), `LightweightRelationImpl.java` (deleted),
  > `RelationsIteratorAbstract.java` (deleted),
  > `RelationsIterable.java` (deleted)
