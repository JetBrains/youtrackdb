<!--MANIFEST
dimension: test-behavior
prefix: TB
high_water_mark: 0
verdict: changes-requested
counts: {blocker: 0, should-fix: 1, suggestion: 2}
evidence_base: present
cert_index: present
flags: []
index:
  - id: TB1
    sev: should-fix
    anchor: "#tb1-by-id-and-across-hascontainers-and-otherContainers-partition-unverified"
    loc: "YTDBHasLabelProcessTest.java (whole file — missing test)"
    cert: C1
    basis: behavior-trace
  - id: TB2
    sev: suggestion
    anchor: "#tb2-edge-by-id-test-skips-the-count-path"
    loc: "YTDBHasLabelProcessTest.java testByIdHasLabelEdgePolymorphism (line 184)"
    cert: C2
    basis: falsifiability
  - id: TB3
    sev: suggestion
    anchor: "#tb3-count-assertion-inert-in-single-vertex-by-id-tests"
    loc: "YTDBHasLabelProcessTest.java testPolymorphicByIdHasLabel (line 112) et al."
    cert: C3
    basis: falsifiability
-->

## Findings

### TB1 [should-fix] By-id AND-across-hasContainers and otherContainers partition unverified

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBHasLabelProcessTest.java` — missing test (the new by-id branch is at `YTDBGraphStep.java:107-128`)

**Issue**: Coverage-driven gap. The by-id branch rewritten in this track introduces two
new pieces of logic that no test exercises:

1. The **partition** of `hasContainers` into `labelContainers` vs `otherContainers`
   (`YTDBGraphStep.java:107-115`), with `otherContainers` run through
   `HasContainer.testAll` and label containers run through the matcher. No by-id test
   combines a `hasLabel(...)` with a property filter (e.g.
   `gp().V(id).hasLabel("Parent").has("name", "x")`), so the AND between the two
   partitions and the routing of non-label containers to `otherContainers` is never
   asserted.
2. The **AND across distinct label containers** via `allMatch`
   (`YTDBGraphStep.java:122-128`). T2 in the Phase-A notes is explicit that
   `hasLabel("A").hasLabel("B")` must be an AND, not a union. The only chained-`hasLabel`
   test is `testPolymorphicWithAdditionalHasLabelFiltering` (lines 67-87), which uses
   `gp().V()...` — the **class-scan** branch, not the by-id `allMatch` path. No test
   chains two `hasLabel` calls after `V(id)`.

**Evidence**:
```
BEHAVIOR TRACE: by-id branch, YTDBGraphStep.elements() lines 117-128
  otherContainers -> HasContainer.testAll  : NO by-id test supplies a non-label container
  labelContainers.stream().allMatch(...)   : NO by-id test supplies >1 label container
FALSIFIABILITY: mutate allMatch -> anyMatch (turn AND into OR across containers).
  Every existing by-id test has exactly one label container, so allMatch and anyMatch
  are indistinguishable -> ALL TESTS PASS. The T2 invariant is not falsifiable by the
  current suite.
FALSIFIABILITY: mutate the partition so a non-label HasContainer is dropped (routed to
  neither list). No by-id test pairs hasLabel with a property has(...), so the dropped
  filter is never observed -> PASS.
```

**Missing behavior**: A by-id query that (a) chains two `hasLabel` calls and expects the
AND (0 results when the two labels are incompatible, 1 when compatible), and (b) pairs
`V(id).hasLabel(...)` with a `has(property, value)` filter and asserts the property
filter still applies.

**Suggested fix**:
```java
@Test
public void testByIdHasLabelAndedAcrossContainers() {
  createSimpleHierarchy();
  final var child = g().addV("Child").property("name", "c1").next();
  final var childId = child.id();

  // AND across distinct hasLabel containers (T2): a Child IS-A Parent AND IS-A
  // Grandparent, so the conjunction matches; an incompatible second label drops it.
  checkSize(1, () -> gp().V(childId).hasLabel("Parent").hasLabel("Grandparent"));
  checkSize(0, () -> gp().V(childId).hasLabel("Parent").hasLabel("Child")
      // mutate allMatch->anyMatch and this would wrongly return 1
      .where(__.not(__.hasLabel("Child"))));

  // Non-label container is routed to otherContainers and still applies.
  checkSize(1, () -> gp().V(childId).hasLabel("Parent").has("name", "c1"));
  checkSize(0, () -> gp().V(childId).hasLabel("Parent").has("name", "nope"));
}
```
(Adjust the incompatible-label case to a sibling pair from a richer hierarchy if a
strict 0-result AND is wanted without the `where`/`not` wrapper.)

### TB2 [suggestion] Edge by-id test skips the count path

**File**: `YTDBHasLabelProcessTest.java`, method `testByIdHasLabelEdgePolymorphism` (line 184)

**Issue**: Unlike every other by-id test, this method asserts on
`...toList().size()` directly (lines 197-202) instead of through the `checkSize` helper,
so the `count()` path is never exercised for the edge by-id case. The `YTDBGraphCountStrategy`
id-drop guard at `YTDBGraphCountStrategy.java:67` applies to edges as much as vertices
(the strategy keys on `step.getIds()`, not the element type), but no edge test pins it.

**Evidence**:
```
FALSIFIABILITY for testByIdHasLabelEdgePolymorphism:
  The test calls only .toList().size(); it never calls .count().
  MUTATION: remove the getIds() guard at YTDBGraphCountStrategy.java:67.
  ANALYSIS: gp().E(edgeId).hasLabel("SubEdge").count() would rewrite to a whole-class
  count. With a single SubEdge in the graph it still returns 1, so even a count call
  would not catch it here -- but more to the point, no count call exists, so the edge
  count path is entirely unasserted. PASS (no count coverage for edges).
```

**Missing behavior**: The edge by-id `count()` path, ideally with two edges of the same
class and one pinned by id (mirroring `testByIdHasLabelCountHonoursId` for vertices) so
the id-drop guard is falsifiable for edges.

**Suggested fix**: Route the edge assertions through `checkSize` (it is typed to
`GraphTraversal<Vertex, Vertex>`; either generalize the helper to `Element` or add an
edge-typed overload), and add a second `SubEdge` so the count is falsifiable:
```java
// add a second SubEdge between fresh vertices, then:
assertEquals(1L, gp().E(edgeId).hasLabel("SuperEdge").count().next().longValue());
assertEquals(1, gp().E(edgeId).hasLabel("SuperEdge").toList().size());
```

### TB3 [suggestion] count() assertion inert in single-vertex by-id tests

**File**: `YTDBHasLabelProcessTest.java`, methods `testPolymorphicByIdHasLabel` (line 112),
`testNonPolymorphicByIdHasLabel` (line 125), `testByIdHasLabelMultipleArguments` (line 205)

**Issue**: These tests run through `checkSize`, whose second assertion calls `count()`
and so traverses `YTDBGraphCountStrategy`. But because each graph holds a single vertex
in the relevant hierarchy, the `count()` assertion would pass with the id-drop bug
present (a whole-class count of 1 equals the pinned count of 1). The `count()` line
therefore adds no falsifiability for Bug 2 in these methods; only `testByIdHasLabelCountHonoursId`
(two `Child` vertices, line 164) actually pins the id-drop guard.

**Evidence**:
```
FALSIFIABILITY for testPolymorphicByIdHasLabel, count() assertion:
  MUTATION: remove getIds() guard at YTDBGraphCountStrategy.java:67.
  gp().V(childId).hasLabel("Parent").count() rewrites to a polymorphic whole-class
  count of Parent. Graph holds exactly one Parent-or-subtype vertex -> 1. Test expects
  1 -> PASS. The mutation survives in this method.
  (testByIdHasLabelCountHonoursId, with two Child vertices, DOES catch the same
  mutation -> 2 != 1 -> FAIL. So Bug 2 is pinned, just not here.)
```

This is not a defect: the `toList()` half of `checkSize` still pins the polymorphism fix
in these methods, and Bug 2 is covered by `testByIdHasLabelCountHonoursId`. The note is
that the `count()` half is effectively decorative in the single-vertex methods.

**Missing behavior**: None strictly required. If a stronger statement per-method is
wanted, seed a second same-class vertex in `testNonPolymorphicByIdHasLabel` and
`testByIdHasLabelMultipleArguments` too, so each by-id method independently falsifies the
id-drop on both the polymorphic and non-polymorphic count paths rather than relying on
one method to carry Bug 2.

## Evidence base

#### C1 — by-id AND/partition gap is real (CONFIRMED)
Confirmed against `YTDBGraphStep.java:107-128` and a full scan of the seven by-id/has-id
methods: every by-id test supplies exactly one `~label` container and no non-label
container, so `allMatch` vs `anyMatch` and the `otherContainers` routing are both
unfalsifiable by the current suite. `testPolymorphicWithAdditionalHasLabelFiltering`
(lines 67-87) is the only chained-`hasLabel` test and runs the class-scan branch (`V()`
without id), not the new by-id `allMatch` code.

#### C2 — edge count path unasserted (CONFIRMED)
`testByIdHasLabelEdgePolymorphism` (lines 184-203) calls only `.toList().size()`; grep of
the method body shows no `.count(` call. The id-drop guard at `YTDBGraphCountStrategy.java:67`
is element-type-agnostic (keys on `step.getIds()`), so an edge count regression has no
test. Severity held at suggestion because the polymorphism behavior the track targets is
pinned by the `toList()` assertions; only the count rewrite for edges is uncovered.

#### C3 — single-vertex count assertion inert (CONFIRMED, non-defect)
Traced the `count()` half of `checkSize` (lines 224-227) through `YTDBGraphCountStrategy`
→ `YTDBClassCountStep.countClass`. With a single hierarchy vertex, the post-mutation
whole-class count equals the pinned count, so the assertion survives the guard-removal
mutation. `testByIdHasLabelCountHonoursId` (line 164) is the one method whose two-vertex
fixture makes the same assertion falsify the mutation (2 != 1). Recorded as the weakest
of the three because it identifies redundant rather than missing coverage; the bug is
covered.
