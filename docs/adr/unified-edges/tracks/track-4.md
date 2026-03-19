# Track 4: Unify edge implementations and creation API

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/4 complete)
- [ ] Track-level code review

## Base commit
`d163c394c7`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step 1: Rename `StatefullEdgeEntityImpl` to `EdgeEntityImpl` and `YTDBStatefulEdgeImpl` to `YTDBEdgeImpl`
  > **What was done:** Renamed `StatefullEdgeEntityImpl` → `EdgeEntityImpl` (file +
  > class + all 6 referencing files), `YTDBStatefulEdgeImpl` → `YTDBEdgeImpl`
  > (file + class + all 7 referencing files), and `newStatefulEdgeInternal()` →
  > `newEdgeInternal()` in `DatabaseSessionEmbedded` + `JSONSerializerJackson`.
  > Updated error message and local variable name (`statefulEdge` → `edgeEntity`)
  > in `addEdgeInternal()`. Review fix: renamed record type label from
  > `"statefulEdge"` to `"edge"` in `RecordFactoryManager`.
  >
  > **Key files:** `EdgeEntityImpl.java` (renamed), `YTDBEdgeImpl.java` (renamed),
  > `DatabaseSessionEmbedded.java` (modified), `RecordFactoryManager.java` (modified),
  > `EntityHelper.java` (modified), `JSONSerializerJackson.java` (modified),
  > `SQLRecordAttribute.java` (modified), `YTDBGraphStep.java` (modified),
  > `GremlinResultMapper.java` (modified), `YTDBVertexImpl.java` (modified),
  > `YTDBPropertyImpl.java` (modified), `YTDBGraphImplAbstract.java` (modified),
  > `GremlinResultMapperTest.java` (modified), `YTDBGraphProvider.java` (modified)

- [ ] Step 2: Unify Vertex edge creation API — collapse `addStateFulEdge()` and `addLightWeightEdge()` into `addEdge()`
  > In `Vertex.java` interface: delete `addStateFulEdge(Vertex to)` (callers
  > already route through `addEdge()`), delete `addStateFulEdge(Vertex, String)`,
  > `addStateFulEdge(Vertex, SchemaClass)`, `addLightWeightEdge(Vertex, String)`,
  > `addLightWeightEdge(Vertex, SchemaClass)`. Add `addEdge(Vertex to)` no-label
  > overload. Result: 3 `addEdge()` overloads.
  >
  > In `VertexEntityImpl.java`: delete `addStateFulEdge()` implementations (3),
  > delete `addLightWeightEdge()` implementations (2), add `addEdge(Vertex to)`
  > implementation delegating to `addEdge(to, EdgeInternal.CLASS_NAME)`. Delete
  > 4-param `createLink()` overload (only called from lightweight paths).
  > Update 5-param `createLink()` Javadoc to remove lightweight references.
  >
  > Update any external callers of deleted methods (search for
  > `addStateFulEdge` and `addLightWeightEdge` across test and production code).

- [ ] Step 3: Unify DatabaseSession edge creation — remove `newLightweightEdge()`, rename `newStatefulEdge()` to `newEdge()`
  > In `DatabaseSessionEmbedded.java`: delete `newLightweightEdge(Vertex, String)`
  > (line ~1080), `newLightweightEdge(Vertex, SchemaClass)` (line ~2924), and
  > `newLightweightEdgeInternal()` (line ~3348). Rename `newStatefulEdge()` overloads
  > to `newEdge()`: `newStatefulEdge(Vertex, Vertex, String)` -> `newEdge(...)`,
  > `newStatefulEdge(Vertex, Vertex, SchemaClass)` -> `newEdge(...)`,
  > `newStatefulEdge(Vertex, Vertex)` -> `newEdge(...)`. Rename
  > `newEdgeInternal()` return type from `StatefullEdgeEntityImpl` to
  > `EdgeEntityImpl` (from Step 1 rename). Update `addEdgeInternal()` to call
  > `newEdge()`-family methods. Update the error message in line ~958 that
  > references `newStatefulEdge()`.
  >
  > Update all callers of renamed methods: `VertexEntityImpl.addEdge()` calls
  > `session.newStatefulEdge()` -> `session.newEdge()`.

- [ ] Step 4: RidPair legacy guard — add compact constructor, delete `ofSingle()` and `isLightweight()`
  > Add compact constructor to `RidPair` record that throws
  > `IllegalStateException` when `primaryRid.equals(secondaryRid)`, with
  > descriptive message about legacy lightweight edges and YTDB-605.
  >
  > Delete `RidPair.ofSingle()` factory method (no callers after Step 2
  > deleted lightweight paths). Delete `RidPair.isLightweight()` method.
  >
  > Update tests:
  > - `VertexFromLinkBagIteratorTest.testOfSingleCreatesLightweightPair()` —
  >   replace with test that verifies `new RidPair(rid, rid)` throws
  >   `IllegalStateException`
  > - `DoubleSidedEdgeLinkBagTest` — remove all `isLightweight()` assertions
  >   (lines ~105, 122, 162, 179, 217, 285, 295, 336, 346, 822); replace
  >   lightweight assertions with `assertNotEquals(pair.primaryRid(),
  >   pair.secondaryRid())` where appropriate
