# Track 3: Delete Relation hierarchy

## Progress
- [x] Review + decomposition
- [ ] Step implementation (3/4 complete)
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

- [ ] Step 4: Remove Relation<Vertex> from Edge/EdgeInternal hierarchy; delete Relation and all dependent types
  > Final atomic deletion of the Relation type hierarchy and all types
  > that exist only to support it.
  >
  > **Edge.java:**
  > - Remove `extends Relation<Vertex>` from Edge interface declaration
  > - Move methods inherited from Relation to Edge as abstract/default:
  >   `getFrom()`, `getTo()`, `isLabeled()`, `getEntity()`, `label()`
  > - Remove `isLightweight()` default (no longer meaningful without
  >   Relation)
  >
  > **EdgeInternal.java:**
  > - Remove `extends Relation<Vertex>` — keep as marker interface
  >   extending Edge, retain static utility methods
  >
  > **DBRecord.java:**
  > - Remove `extends Element` — Element marker becomes unnecessary
  >
  > **Files to delete:**
  > - `Relation.java` — interface
  > - `Element.java` — marker interface (only extended by DBRecord after
  >   Relation deletion)
  > - `LightweightRelationImpl.java` — only created by
  >   EntityRelationsIterator (dead since Step 1)
  > - `EntityRelationsIterator.java` — only used by
  >   EntityImpl.getBidirectionalLinksInternal() (deleted in Step 1)
  > - `EntityRelationsIterable.java` — only used by
  >   EntityImpl.getBidirectionalLinksInternal() (deleted in Step 1)
  > - `RelationsIteratorAbstract.java` — only extended by
  >   EntityRelationsIterator (after Step 3 inlining)
  > - `RelationsIterable.java` — only extended by
  >   EntityRelationsIterable (after Step 3 inlining)
