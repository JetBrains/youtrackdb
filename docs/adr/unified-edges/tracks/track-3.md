# Track 3: Delete Relation hierarchy

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/4 complete)
- [ ] Track-level code review

## Base commit
(to be set at Phase B start)

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [ ] Step 1: Remove Relation<?> dispatch from SQL functions, MATCH traversers, and ResultInternal; delete EntityImpl entity-traversal methods
  > Remove all runtime Relation<?> dispatch and dead code paths. This makes
  > Relation a compile-time-only dependency (type hierarchy), with no runtime
  > usage remaining.
  >
  > **SQL functions:**
  > - `SQLFunctionMove.execute()`: remove `instanceof Relation<?>` dispatch
  >   branch and `move(db, Relation<?>, labels)` overload
  > - `SQLFunctionMove.e2v(Relation<?>, Direction)`: delete dead overload
  > - `SQLFunctionMove.v2v()`: remove call to `rec.getEntities()`, return
  >   null for non-vertex, non-edge entities
  > - `SQLFunctionMove.v2e()`: remove call to `rec.getBidirectionalLinks()`,
  >   return null for non-vertex, non-edge entities
  >
  > **MATCH traversers:**
  > - `MatchEdgeTraverser.toExecutionStream()`: remove `case Relation<?>`
  > - `OptionalMatchEdgeTraverser.computeNext()`: remove `isRelation()` branch
  > - Audit other MATCH traversers (`MatchMultiEdgeTraverser`,
  >   `MatchReverseEdgeTraverser`, `MatchFieldTraverser`) for Relation refs
  >
  > **ResultInternal dispatch:**
  > - `ResultInternal.convertPropertyValue()`: remove `case Relation<?>` arm
  > - `ResultInternal.toResultInternal()`: remove `case Relation<?>` arm
  >
  > **EntityImpl:**
  > - Delete `getEntities()`, `getBidirectionalLinks()`,
  >   `getBidirectionalLinksInternal()` — only called from SQLFunctionMove
  >   (updated above)

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
