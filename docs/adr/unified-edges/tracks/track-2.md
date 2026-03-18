# Track 2: Merge StatefulEdge into Edge

## Progress
- [x] Review + decomposition
- [x] Step implementation (4/4 complete)
- [ ] Track-level code review (1/3 iterations)

## Base commit
`f7f835659a`

## Reviews completed
- [x] Technical

## Steps

- [x] Step 1: Make `Edge` extend `Entity` and resolve method conflicts
  > **What was done:** Changed `Edge` from `extends Element, Relation<Vertex>`
  > to `extends Entity, Relation<Vertex>`. Added `default asEntity()` on Edge
  > (resolves Entity/Relation conflict). Removed `asStatefulEdge()`/
  > `asStatefulEdgeOrNull()` from Edge (inherited from Entity). Added
  > `@Override` to `getSchemaClass()`, `getSchemaClassName()`, `delete()`.
  > Added `isRelation()`/`asRelation()`/`asRelationOrNull()` overrides
  > returning true/this (code review fix). Removed `asEntity()` default from
  > StatefulEdge (now on Edge). Disambiguated 3 ResultInternal constructor
  > calls with `(Identifiable)` cast. Removed dead EdgeInternal branch in
  > ScriptTransformerImpl (code review fix).
  >
  > **What was discovered:** Edge extending both Identifiable (via Entity)
  > and Relation causes constructor ambiguity in `ResultInternal` which has
  > `(session, Identifiable)` and `(session, Relation<?>)` overloads.
  > 3 call sites needed explicit `(Identifiable)` casts. Also, Entity's
  > `isRelation()` default returns false — edges need explicit override to
  > return true now that Edge IS-A Entity.
  >
  > **Key files:** `Edge.java` (modified), `StatefulEdge.java` (modified),
  > `ScriptTransformerImpl.java` (modified), `FetchEdgesFromToVerticesStep.java`
  > (modified), `FetchEdgesToVerticesStep.java` (modified)

- [x] Step 2: Remove `isLightweight()` from `Edge` and all call sites
  > **What was done:** Made `isLightweight()` a default returning false on
  > Edge. Removed override from StatefullEdgeEntityImpl. Removed dead
  > `|| edge.isLightweight()` branch in VertexEntityImpl (also dropped
  > now-unused `edge` parameter). Simplified ResultInternal.toMapValue()
  > to use `edge.getIdentity()` directly (Edge extends Entity extends
  > Identifiable). Removed test assertions in DbImportExportTest.
  >
  > **What was discovered:** `LightweightRelationImpl.equals()` calls
  > `isLightweight()` on a `Relation<?>`, not an `Edge` — correctly left
  > untouched. Also, `VertexEntityImpl.removeLinkFromEdge()` still has
  > a dead `instanceof StatefulEdge` branch and stale "lightweight edge"
  > comment that will be cleaned up in Track 4/5.
  >
  > **Key files:** `Edge.java` (modified), `StatefullEdgeEntityImpl.java`
  > (modified), `VertexEntityImpl.java` (modified), `ResultInternal.java`
  > (modified), `DbImportExportTest.java` (modified)

- [x] Step 3: Replace all `StatefulEdge` type references with `Edge`
  > **What was done:** Mechanical replacement of `StatefulEdge` type across
  > 22 files: return types, parameter types, local variables, instanceof,
  > casts, generic args. Key areas: Transaction interface (7 methods),
  > FrontendTransactionImpl/NoTx, Vertex interface, VertexEntityImpl,
  > Entity/DBRecord/Result (asStatefulEdge return types → Edge),
  > ResultSet (Stream/Spliterator/List generics), DatabaseSessionEmbedded,
  > Gremlin layer (YTDBEdgeInternal, YTDBStatefulEdgeImpl,
  > GremlinResultMapper switch collapse), EntityImpl (instanceof Edge),
  > 3 test files. Fixed pre-existing Javadoc error in
  > ResultSet.statefulEdgeStream() (code review fix).
  >
  > **Key files:** Transaction.java, FrontendTransactionImpl.java,
  > FrontendTransactionNoTx.java, Vertex.java, VertexEntityImpl.java,
  > Entity.java, DBRecord.java, Result.java, ResultSet.java,
  > DatabaseSessionEmbedded.java, EntityImpl.java, RecordBytes.java,
  > YTDBEdgeInternal.java, YTDBStatefulEdgeImpl.java,
  > YTDBGraphImplAbstract.java, YTDBPropertyImpl.java, YTDBVertexImpl.java,
  > YTDBGraphStep.java, GremlinResultMapper.java, TransactionTest.java,
  > SelectStatementExecutionTest.java, TestGraphElementDelete.java
  > (all modified)

- [x] Step 4: Delete `StatefulEdge.java` and remove `asStatefulEdge()`/`asStatefulEdgeOrNull()`
  > **What was done:** Deleted `StatefulEdge.java`. Removed
  > `asStatefulEdge()`/`asStatefulEdgeOrNull()` from DBRecord, Entity,
  > Result, EntityImpl, RecordBytes. Added `isEdge()`/`asEdge()`/
  > `asEdgeOrNull()` to DBRecord (replacing the removed methods — these
  > were previously only on Entity/Result, but DBRecord needs them for
  > `tx.load().asEdge()` pattern). Added implementations to RecordBytes.
  > Removed `implements StatefulEdge` from StatefullEdgeEntityImpl.
  > Replaced 15 `asStatefulEdge()` call sites across 12 files. Removed
  > duplicate `statefulEdgeStream()`/`forEachStatefulEdge()`/
  > `toStatefulEdgeList()` from ResultSet (code review fix).
  >
  > **What was discovered:** `DBRecord` lacked `isEdge()`/`asEdge()`/
  > `asEdgeOrNull()` — they were only on Entity and Result. Removing
  > `asStatefulEdge()` from DBRecord exposed this gap since
  > `tx.load(rid).asEdge()` uses the DBRecord interface.
  >
  > **Key files:** `StatefulEdge.java` (deleted), `DBRecord.java` (modified),
  > `Entity.java` (modified), `Result.java` (modified), `ResultSet.java`
  > (modified), `EntityImpl.java` (modified), `RecordBytes.java` (modified),
  > `StatefullEdgeEntityImpl.java` (modified), `CastToEdgeStep.java`
  > (modified), `CreateEdgesStep.java` (modified), `SQLFunctionAstar.java`
  > (modified), `SQLFunctionShortestPath.java` (modified), plus 7 more files
