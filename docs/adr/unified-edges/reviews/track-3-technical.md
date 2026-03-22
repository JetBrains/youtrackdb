# Track 3 Technical Review

## Iteration 1

### Finding T1 [should-fix]
**Location**: Track description — "Reparameterize BidirectionalLinksIterable from Relation<T> to Edge"
**Issue**: BidirectionalLinksIterable is used not only by EntityImpl.getEntities() (being deleted) but also by VertexEntityImpl (lines 174, 179, 184), SQLFunctionShortestPath (line 310), and SQLProjectionItem (lines 155-166). The reparameterization is feasible because all remaining callers pass Edge iterables, but this must happen AFTER EntityImpl.getEntities() deletion to avoid compile errors (getEntities passes non-Edge Relation types).
**Proposed fix**: Order steps so EntityImpl.getEntities() is deleted before BidirectionalLinks reparameterization. Verify all remaining callers pass Edge-compatible iterables.

### Finding T2 [should-fix]
**Location**: Track description — atomic deletion claim
**Issue**: The atomic deletion scope can be refined. After inlining RelationsIteratorAbstract into EdgeIterator (separate step), the truly interdependent files for atomic deletion are: Relation, Element, EdgeInternal (extends Relation), LightweightRelationImpl, EntityRelationsIterator, EntityRelationsIterable, RelationsIteratorAbstract (now unused), RelationsIterable (now unused).
**Proposed fix**: Separate iterator inlining into its own step; atomic deletion handles the final Relation hierarchy removal.

### Finding T3 [should-fix]
**Location**: Track description — "Delete EntityImpl.getEntities()/getBidirectionalLinks()/getBidirectionalLinksInternal()"
**Issue**: These are called from SQLFunctionMove.v2v() (line 96) and v2e() (line 117). Deleting them requires updating SQLFunctionMove to return null for non-vertex, non-edge entities. The plan mentions "v2v() returns null for non-graph entities" in the Component Map, but this isn't explicit in Track 3's description.
**Proposed fix**: Include SQLFunctionMove.v2v()/v2e() updates in the same step as EntityImpl method deletion.

### Finding T4 [should-fix]
**Location**: Track description — completeness of Relation<?> pattern match removal
**Issue**: Multiple locations contain Relation<?> dispatch that must be removed:
- SQLFunctionMove.execute() line 66: `instanceof Relation<?>`
- SQLFunctionMove.e2v(Relation<?>, Direction) line 159: dead overload
- MatchEdgeTraverser.toExecutionStream() line 605: `case Relation<?>`
- OptionalMatchEdgeTraverser.computeNext() line 121: `isRelation()` branch
- ResultInternal.convertPropertyValue() line 439: `case Relation<?>`
- ResultInternal.toResultInternal() line 1320: `case Relation<?>`
- SQLEngine.foreachRecord(): `isRelation()` check (if present)
**Proposed fix**: Enumerate all locations in step descriptions for completeness.

### Finding T5 [should-fix]
**Location**: Edge.java — methods inherited from Relation that must survive deletion
**Issue**: Edge currently inherits getFrom(), getTo(), isLabeled(), getEntity(), label() from Relation<Vertex>. When Relation is deleted, these must be moved onto Edge directly.
**Proposed fix**: In the Relation deletion step, explicitly move these methods to Edge as abstract/default methods before removing the extends clause.

### Finding T6 [suggestion]
**Location**: ResultInternal.relation field removal
**Issue**: The relation field (line 55), its constructor (lines 99-107), setRelation(), and all read sites (equals, detach, toMap, toJSON, toString) must be removed together. After Step 1 removes external callers, this is safe.
**Proposed fix**: Handle as part of the isRelation()/asRelation() removal step.

## Summary
No blockers. 5 should-fix findings primarily about step ordering, completeness, and precision. All are addressed in the decomposition.
