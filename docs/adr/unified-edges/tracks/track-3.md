# Track 3: Delete Relation hierarchy

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/4 complete)
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

- [ ] Step 2: Reparameterize BidirectionalLinksIterable/BidirectionalLinkToEntityIterator from Relation<T> to Edge; remove ResultInternal.relation field and isRelation()/asRelation() from all interfaces
  > Now that no code dispatches on Relation<?> at runtime (Step 1), remove
  > the remaining Relation-typed API surface.
  >
  > **BidirectionalLinks reparameterization:**
  > - `BidirectionalLinksIterable`: change generic bound from
  >   `Iterable<? extends Relation<T>>` to `Iterable<? extends Edge>`
  > - `BidirectionalLinkToEntityIterator`: change from
  >   `Iterator<? extends Relation<T>>` to `Iterator<? extends Edge>`
  > - Update callers: `VertexEntityImpl` (lines 174, 179, 184),
  >   `SQLFunctionShortestPath` (line 310), `SQLProjectionItem`
  >   (lines 155-166) — all already pass Edge iterables, so changes
  >   are minimal type adjustments
  >
  > **ResultInternal.relation field removal:**
  > - Delete `relation` field, `Relation<?>` constructor, `setRelation()`
  > - Clean up all `relation != null` checks in: `equals()`, `detach()`,
  >   `toMap()`, `toJSON()`, `toString()`, `setIdentifiable()`
  >
  > **isRelation()/asRelation()/asRelationOrNull() removal from interfaces:**
  > - Remove from: `Result`, `Entity`, `DBRecord`, `Edge`,
  >   `ResultInternal`, `EntityImpl`, `RecordBytes`, `EmbeddedEntityImpl`,
  >   `UpdatableResult`
  > - Note: `Edge.isRelation()` default (returns true) and
  >   `Edge.asRelation()`/`asRelationOrNull()` defaults (return this) are
  >   removed — no callers remain after Step 1

- [ ] Step 3: Inline RelationsIteratorAbstract into EdgeIterator, RelationsIterable into EdgeIterable
  > Make EdgeIterator and EdgeIterable standalone classes that no longer
  > depend on the Relations* abstract hierarchy.
  >
  > **EdgeIterator:**
  > - Copy filtering/iteration logic from `RelationsIteratorAbstract`
  >   (hasNext loop, null-skipping, label filtering via isLabeled())
  > - Replace generic `<E extends Entity, L extends Relation<E>>` with
  >   concrete `Vertex`/`EdgeInternal` types
  > - Preserve: lazy hasNext/next pattern, RecordNotFoundException
  >   handling (log and skip), MultiCollectionIterator compatibility
  >   (Resettable, Sizeable interfaces)
  > - Remove extends clause
  >
  > **EdgeIterable:**
  > - Copy iterable scaffolding from `RelationsIterable` (sourceEntity,
  >   connection, labels, identifiables fields)
  > - Replace generic types with concrete types
  > - Remove extends clause
  >
  > After this step, RelationsIteratorAbstract and RelationsIterable are
  > only referenced by EntityRelationsIterator/EntityRelationsIterable
  > (dead code since Step 1).

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
