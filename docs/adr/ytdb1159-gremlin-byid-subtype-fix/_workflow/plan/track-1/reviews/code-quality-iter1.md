<!--MANIFEST
dimension: code-quality
iteration: 1
verdict: PASS
findings_total: 3
blocker: 0
should_fix: 0
suggestion: 3
evidence_base: { certs: 0 }
cert_index: []
flags: [reference-accuracy-caveat]
index:
  - id: CQ1
    sev: suggestion
    anchor: "CQ1"
    loc: "core/.../gremlin/traversal/step/sideeffect/YTDBGraphStep.java:122-128"
    cert: n/a
    basis: "diff + full-file read"
  - id: CQ2
    sev: suggestion
    anchor: "CQ2"
    loc: "core/.../gremlin/gremlintest/scenarios/YTDBHasLabelProcessTest.java:292-310"
    cert: n/a
    basis: "diff + full-file read"
  - id: CQ3
    sev: suggestion
    anchor: "CQ3"
    loc: "core/.../gremlin/traversal/step/sideeffect/YTDBGraphStep.java:107,131"
    cert: n/a
    basis: "full-file read"
-->

## Findings

### CQ1 [suggestion] `//noinspection unchecked` placement inside a multi-line argument expression

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/traversal/step/sideeffect/YTDBGraphStep.java` (line 122-128)

**Issue**: The unchecked cast is suppressed with a `//noinspection unchecked` comment placed at line 126, mid-way through a nested `allMatch(...)` lambda whose body spans the `YTDBLabelMatcher.matches(element, List.of((P<? super String>) ...), polymorphicFlag)` call. The existing fold site this was modelled on (`YTDBGraphStepStrategy.java:136-137`) places the same comment on its own line immediately before a standalone statement, where the suppression scope is unambiguous. Here the comment is wedged between two arguments of a method call. That is a less conventional and harder-to-read spot for a line suppression, even though it does precede the cast line.

**Suggestion**: Consider hoisting the per-container cast out of the lambda into a named helper, for example a small private `boolean matchesLabel(Element, HasContainer, boolean)` that performs the cast and the `List.of(...)` wrap. The suppression then sits on its own line before a single statement and the filter lambda reads as a plain `allMatch(c -> matchesLabel(element, c, polymorphicFlag))`. This also removes the per-element `List.of(...)` allocation noise from the hot filter expression. Optional; current code is correct.

### CQ2 [suggestion] Edge test bypasses the `checkSize` helper used by every other method

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBHasLabelProcessTest.java` (line 292-310)

**Issue**: `testByIdHasLabelEdgePolymorphism` asserts with raw `assertEquals(1, gp().E(edgeId).hasLabel(...).toList().size())` calls, while every other method in the class routes through `checkSize(...)`. The `checkSize` helper additionally pins the `toList().size() == count()` equality, so the edge test silently loses the count-agreement check that the vertex tests get for free. The divergence is understandable, since the file's `checkSize` is typed `Supplier<GraphTraversal<Vertex, Vertex>>` and cannot accept an edge traversal, but it remains an undocumented inconsistency.

**Suggestion**: Either add a brief comment on the edge test noting why it cannot use `checkSize` (the `Vertex`-typed signature), or introduce an edge-typed `checkSize` overload or generified helper so the edge path also asserts `toList == count`. The count-agreement invariant is exactly the Bug 2 surface, so exercising it on the edge by-id path too would strengthen coverage. Optional.

### CQ3 [suggestion] `labelContainers` local declared with different meaning in two branches of `elements()`

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/traversal/step/sideeffect/YTDBGraphStep.java` (line 107, 131)

**Issue**: Both the by-id branch (line 107) and the class-scan branch (line 131) declare a local named `labelContainers`. They hold semantically different things: the by-id branch's list is partitioned by the `T.label` accessor key and fed to `YTDBLabelMatcher`; the class-scan branch's list is the set of containers `YTDBGraphQueryBuilder.addCondition` classified as `LABEL`. Same name, different population rule and different downstream use. A reader scanning the method may conflate them.

**Suggestion**: Rename the by-id branch local to something that signals its role, e.g. `labelContainersById` or `polymorphicLabelContainers`, to disambiguate from the query-builder-classified list in the sibling branch. Purely cosmetic; no behavioral impact.

## Evidence base
