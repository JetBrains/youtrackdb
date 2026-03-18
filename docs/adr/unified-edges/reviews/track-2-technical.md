# Track 2 Technical Review: Delete Relation hierarchy and merge StatefulEdge into Edge

## Review Summary

**Verdict:** Plan is sound but understates the scope of `StatefulEdge` references. The plan
lists ~5 steps but the actual `StatefulEdge` footprint spans 22 production files (99 occurrences)
and 17 test files (90 occurrences). The step decomposition below accounts for this expanded scope.

## Validated Assumptions

1. **`Element.java` is truly empty and unreferenced by name.** Confirmed — only appears in
   `extends` clauses of `DBRecord`, `Edge`, and `Relation`. No `instanceof Element` or
   `import ...Element` references in the YouTrackDB namespace (only TinkerPop's
   `org.apache.tinkerpop.gremlin.structure.Element` exists, which is unrelated).

2. **`StatefulEdge` interface is minimal.** Confirmed — contains only a default `asEntity()`
   method returning `this`. Its value is purely as a type intersection of `Edge + Entity`.

3. **`EdgeInternal` has static utility methods that need relocation.** Confirmed —
   `checkPropertyName()`, `isEdgeConnectionProperty()`, `isInEdgeConnectionProperty()`,
   `isOutEdgeConnectionProperty()`, `filterPropertyNames()` are all static and can move to `Edge`.

4. **`Relation` hierarchy files cross-reference each other.** Confirmed —
   `RelationsIteratorAbstract` is parameterized on `Relation`, `EntityRelationsIterator` extends
   it with `LightweightRelationImpl`, `RelationsIterable` is parameterized on `Relation`,
   `EntityRelationsIterable` extends it with `LightweightRelationImpl`. All must be deleted
   atomically with their callers updated.

5. **`BidirectionalLinksIterable`/`BidirectionalLinkToEntityIterator` use `Relation<T>` only.**
   Confirmed — these take `Iterable<? extends Relation<T>>` / `Iterator<? extends Relation<T>>`.
   After Relation deletion, they must be reparameterized to `Edge`.

6. **`ResultInternal.relation` field stores lightweight relations only.** Confirmed — the
   constructor at line 99-107 stores relation in the `relation` field only if
   `relation.isLightweight()`, otherwise it extracts the entity via `setIdentifiable()`.

## Findings Requiring Plan Adjustment

### F1: StatefulEdge scope is much larger than the audit checklist

The audit checklist for Track 2 lists ~25 files. The actual `StatefulEdge` footprint includes:

**Production files (22 files, 99 occurrences):**
- `Transaction.java` — 8 method signatures return `StatefulEdge` (`loadEdge`, `newStatefulEdge`)
- `FrontendTransactionNoTx.java` — 8 implementations of those methods
- `FrontendTransactionImpl.java` — 13 implementations of those methods
- `DatabaseSessionEmbedded.java` — 13 references (`newStatefulEdge`, `newStatefulEdgeInternal`,
  `loadEdge`, `addEdgeInternal`)
- `VertexEntityImpl.java` — 9 references (return types from `addStateFulEdge`, casts)
- `Vertex.java` — 3 method signatures (`addStateFulEdge` overloads returning `StatefulEdge`)
- `StatefullEdgeEntityImpl.java` — 4 references
- `YTDBStatefulEdgeImpl.java` — 8 references (constructor, `getRawEntity`)
- `GremlinResultMapper.java` — 8 references
- `YTDBEdgeInternal.java` — `getRawEntity()` returns `StatefulEdge`
- `YTDBStatefulEdge.java` (API) — interface extending `YTDBEdge`
- `YTDBGremlinPlugin.java` — class import registration
- `YTDBVertexImpl.java`, `YTDBPropertyImpl.java`, `YTDBGraphImplAbstract.java`,
  `YTDBGraphStep.java` — various casts
- `Edge.java` — `asStatefulEdge()`/`asStatefulEdgeOrNull()` declarations
- `CreateEdgesStep.java`, `SQLUpdateItem.java`, `ScriptDatabaseWrapper.java`,
  `JSONSerializerJackson.java`

**Test files (17 files, 90 occurrences):**
- `TransactionTest.java` — 19 occurrences
- `SQLFunctionAstarTest.java` — 16 occurrences
- `SelectStatementExecutionTest.java` — 9 occurrences
- `LinkBagIndexTest.java`, `GraphDatabaseTest.java` — 8 each
- Plus 12 more test files

**Impact:** The `StatefulEdge` merge step (originally planned as steps 1-3) requires touching
significantly more files than listed. However, most changes are mechanical type replacements:
`StatefulEdge` → `Edge` in return types and parameter types, removal of casts.

### F2: Transaction/Vertex/DatabaseSession method signatures use StatefulEdge

`Transaction.java`, `Vertex.java`, and `DatabaseSessionEmbedded.java` have method signatures
returning `StatefulEdge`. These are **not** in the original audit checklist for Track 2 but
must be updated when merging `StatefulEdge` into `Edge`:
- `Transaction.loadEdge()` → return `Edge` (or keep as `StatefulEdge` temporarily, but since
  we're deleting `StatefulEdge`, these must change)
- `Transaction.newStatefulEdge()` → Track 3 renames to `newEdge()`, but Track 2 must at minimum
  change the return type from `StatefulEdge` to `Edge`
- `Vertex.addStateFulEdge()` → Track 3 renames to `addEdge()`, but Track 2 must change return type

**Decision needed:** Should Track 2 change return types but keep method names (Track 3 does
renames), or should Track 2 only replace `StatefulEdge` type references and let the method
exist with `Edge` return type until Track 3 renames?

**Recommendation:** Track 2 changes return types from `StatefulEdge` to `Edge` but preserves
method names. This is consistent with the plan's approach: "Replace all StatefulEdge type
references with Edge." Track 3 then renames `newStatefulEdge()` → `newEdge()` etc.

### F3: `YTDBStatefulEdge` is a public API interface

`YTDBStatefulEdge` in `api/gremlin/embedded/` is a public API interface. It extends `YTDBEdge`
and adds only `RID id()`. After `StatefulEdge` deletion:
- `YTDBStatefulEdgeImpl` constructor changes from `StatefulEdge` → `Edge`
- `YTDBEdgeInternal.getRawEntity()` changes return type from `StatefulEdge` → `Edge`
- `YTDBStatefulEdge` interface can be deleted (its only added method `RID id()` can move to
  `YTDBEdge`) — but this is a **public API change** that may be better deferred to Track 3/6.

**Recommendation:** In Track 2, change `YTDBStatefulEdgeImpl` constructor and
`YTDBEdgeInternal.getRawEntity()` to use `Edge`. Keep `YTDBStatefulEdge` interface alive
(it still compiles since it extends `YTDBEdge`). Delete it in Track 3 with the rename.

### F4: `SQLUpdateItem` lightweight edge path is dead code

`SQLUpdateItem.java:273-275` calls `session.newLightweightEdge()` and `res.setRelation()`.
Since Track 1 made `newLightweightEdgeInternal()` throw `UnsupportedOperationException`, this
path is effectively dead. It should be cleaned up as part of the `isLightweight()` removal step.

### F5: EdgeIterator inlining needs careful treatment

`EdgeIterator` extends `RelationsIteratorAbstract<Vertex, EdgeInternal>`. When inlining:
- The abstract class has 122 lines of logic (iterator management, size, reset, hasNext)
- `EdgeIterator` overrides only `createBidirectionalLink()`
- After inlining, `EdgeIterator` should implement `Iterator<Edge>`, `Resettable`, `Sizeable`
  directly (dropping the `EdgeInternal` type parameter)
- `EdgeIterable` similarly extends `RelationsIterable<Vertex, EdgeInternal>` and needs inlining

**Note:** After inlining, `EdgeIterator` should produce `Edge` (not `EdgeInternal`) since
`EdgeInternal` is being deleted in the same atomic commit.

### F6: `EntityImpl.getEntities()` and `getBidirectionalLinks()` call chain

`EntityImpl.getEntities()` → `getBidirectionalLinksInternal()` → creates
`EntityRelationsIterable` → `EntityRelationsIterator` → `LightweightRelationImpl`.

`VertexEntityImpl.getBidirectionalLinksInternal()` chains
`super.getBidirectionalLinksInternal()` (entity LINK relations) with `getEdgesInternal()`.

`SQLFunctionMove.v2v()` calls `rec.getEntities()` for non-vertex, non-edge entities.
`SQLFunctionMove.v2e()` calls `rec.getBidirectionalLinks()` for non-edge entities.

After deletion:
- `v2v()` for non-vertex/non-edge: return `null` (per plan)
- `v2e()` for non-edge entities: currently returns entity LINK relations + edges for vertices.
  For vertices, `VertexEntityImpl` overrides to chain entity links with edges. After Relation
  deletion, `getBidirectionalLinks()` on `EntityImpl` returns entity LINK relations which are
  `LightweightRelationImpl` — these go away. For vertices, only the edge part survives.

**Decision:** `v2e()` on non-vertex entities will return `null`. On vertices, it returns edges
only (via `getEdgesInternal()`). This is correct since entity LINK traversal via
`out()`/`in()`/`both()` is being removed per the ADR.

## Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| Large number of StatefulEdge references (189 total) | Medium | Mechanical replacements; IDE refactoring safe |
| Public API change (YTDBStatefulEdge) | Low | Defer deletion to Track 3; keep interface alive |
| EdgeIterator inlining complexity | Medium | Test thoroughly; single atomic commit |
| getBidirectionalLinks removal changes v2e() behavior for non-vertex entities | Low | Only affects entity LINK traversal which has zero test callers |
| 17 test files need StatefulEdge → Edge updates | Medium | Most are type changes; some need addStateFulEdge → addStateFulEdge with Edge return |

## Recommended Step Decomposition

See `tracks/track-2.md` for the detailed step breakdown. Summary:

1. **Move StatefulEdge methods to Edge + replace StatefulEdge type refs in production code** (~40 files)
2. **Replace StatefulEdge type refs in test code** (~17 files)
3. **Delete StatefulEdge.java and Edge.asStatefulEdge()/asStatefulEdgeOrNull()** (interface deletion)
4. **Remove isLightweight() from Edge/Relation + remove call sites** (~12 files)
5. **Atomic deletion of Relation hierarchy + Element + EdgeInternal + dependent types** (~20 files, inline EdgeIterator/EdgeIterable)
