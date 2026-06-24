<!--MANIFEST
dimension: test-structure
prefix: TS
range: c5d0812e2d037f1b08c7689182a6294dd368a19b..HEAD
evidence_base:
  certs: 0
cert_index: []
flags: []
index:
  - id: TS1
    sev: suggestion
    anchor: "TS1"
    loc: "YTDBHasLabelProcessTest.java:185 testByIdHasLabelEdgePolymorphism"
    cert: n/a
    basis: read
  - id: TS2
    sev: suggestion
    anchor: "TS2"
    loc: "YTDBHasLabelProcessTest.java:184-222"
    cert: n/a
    basis: read
-->

## Findings

### TS1 [suggestion] Edge by-id test loses the count()/toList() agreement check that every other method gets

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBHasLabelProcessTest.java`, method `testByIdHasLabelEdgePolymorphism` (lines 197-202)

**Issue**: Every vertex-path test routes through the `checkSize` helper (line 224), which
asserts `toList().size()` and `count().next()` agree. The edge test cannot use that helper
because `checkSize` is typed `Supplier<GraphTraversal<Vertex, Vertex>>` and edge traversals
are `GraphTraversal<Edge, Edge>`, so it falls back to bare `assertEquals(n, ...toList().size())`
on four lines. The consequence is structural, not cosmetic: the edge by-id path is the one
place in this file where the `count()` rewrite is exercised against edges, yet it is the only
by-id method that never checks the count path. The count id-drop regression (the second bug
this track fixes) rides exactly on `count()`, so the edge variant silently skips the
assertion that would catch an edge-specific recurrence.

**Suggestion**: Generalize the helper so the edge test can share it and regain the count
check. A minimal change keeps the existing vertex callers working:

```java
private static void checkEdgeSize(int size, Supplier<GraphTraversal<Edge, Edge>> query) {
  assertEquals(size, query.get().toList().size());
  assertEquals(size, query.get().count().next().longValue());
}
```

Then call `checkEdgeSize(1, () -> gp().E(edgeId).hasLabel("SubEdge"))` etc. This restores the
toList/count parity for the edge path without weakening the vertex helper.

### TS2 [suggestion] Per-method fixture duplication invites drift across the four-line by-id block

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBHasLabelProcessTest.java`, methods `testPolymorphicByIdHasLabel`, `testNonPolymorphicByIdHasLabel`, `testPolymorphicHasIdHasLabel`, `testNonPolymorphicHasIdHasLabel`, `testByIdHasLabelCountHonoursId`, `testByIdHasLabelMultipleArguments` (lines 112-222)

**Issue**: Five of the six new methods open with the identical three-line preamble
(`createSimpleHierarchy(); final var child = g().addV("Child").next(); final var childId =
child.id();`). The repetition is independently isolated and correct — each method makes its
own child and `tearDown()` drops all vertices after each test, so there is no shared-state or
ordering hazard. The note is purely about maintainability: six copies of the same setup drift
apart over time (one gets a property, another a different class), and a reader scanning the
block has to diff each preamble to confirm they are the same. The existing class already
accepts this pattern (the older vertex tests repeat `createSimpleHierarchy()` + `addV`), so
this is consistency-preserving rather than a new defect.

**Suggestion**: Optional. A small private helper such as `private Object freshChildId() {
createSimpleHierarchy(); return g().addV("Child").next().id(); }` would collapse the five
preambles to one line each and make each method's distinct intent (single vertex vs. two
vertices vs. multi-arg) the only thing that varies. Leave `testByIdHasLabelCountHonoursId`
explicit about its second `addV("Child")` since the extra vertex is its whole point.

## Evidence base
