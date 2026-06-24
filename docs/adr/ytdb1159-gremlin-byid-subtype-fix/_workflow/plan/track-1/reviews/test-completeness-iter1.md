<!--
MANIFEST
dimension: test-completeness
target: "Track 1: Polymorphic by-id hasLabel and count id-drop fix"
commit_range: c5d0812e2d037f1b08c7689182a6294dd368a19b..HEAD
verdict: changes-requested
counts: { blocker: 0, should-fix: 2, suggestion: 2 }
evidence_base: "## Evidence base"
cert_index: [C1, C2, C3, C4]
flags: [grep-only-no-psi]
index:
  - id: TC1
    sev: should-fix
    anchor: "#tc1-by-id-haslabel-with-a-coexisting-property-filter-is-untested"
    loc: "core/.../gremlin/traversal/step/sideeffect/YTDBGraphStep.java:117-128; YTDBHasLabelProcessTest.java"
    cert: C1
    basis: "otherContainers AND-branch (HasContainer.testAll) on the by-id path has no test exercising a non-empty otherContainers list"
  - id: TC2
    sev: should-fix
    anchor: "#tc2-by-id-chained-haslabela-haslabelb-and-across-containers-is-untested"
    loc: "core/.../gremlin/traversal/step/sideeffect/YTDBGraphStep.java:122-128; YTDBHasLabelProcessTest.java"
    cert: C2
    basis: "allMatch AND-across-label-containers on by-id path untested; T2 note warns this must be AND not OR, only class-scan path covers it"
  - id: TC3
    sev: suggestion
    anchor: "#tc3-multiple-pinned-ids-vid1-id2-haslabel-count-is-untested"
    loc: "core/.../gremlin/traversal/strategy/optimization/YTDBGraphCountStrategy.java:65-67; YTDBGraphStep.java:101-128"
    cert: C3
    basis: "all by-id tests pin exactly one id; the multi-id count guard and per-element filtering are unexercised"
  - id: TC4
    sev: suggestion
    anchor: "#tc4-by-id-haslabel-on-a-vertex-of-an-unrelated-sibling-class-is-untested"
    loc: "core/.../gremlin/traversal/step/sideeffect/YTDBGraphStep.java:117-128"
    cert: C4
    basis: "the pinned id always belongs to a class on the queried label's hierarchy; a real element of an unrelated sibling class is never filtered out by id+label"
-->

## Findings

### TC1 [should-fix] By-id `hasLabel` with a coexisting property filter is untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBHasLabelProcessTest.java`
**Production code**: `core/.../gremlin/traversal/step/sideeffect/YTDBGraphStep.java` (lines 117-128)

**Missing scenario**: A by-id query that combines a property filter with `hasLabel`, e.g. `gp().V(childId).has("name", "x").hasLabel("Parent")`. No test puts a non-`~label` container into the by-id path.

**Why it matters**: The new by-id branch partitions `this.hasContainers` into `labelContainers` and `otherContainers`, then ANDs two independent predicates: `HasContainer.testAll(element, otherContainers)` and the per-label-container matcher loop (YTDBGraphStep.java:119-128). Every new and pre-existing by-id test produces an **empty** `otherContainers` list, so `HasContainer.testAll(element, [])` (which is vacuously true) is the only case ever run. The partition loop's `else` arm (line 112-113, the path that adds to `otherContainers`) and the conjunction of a real property filter with the label matcher are never executed. A regression that mis-partitions a property container as a label container, or drops the `otherContainers` conjunction, would pass the whole suite. This is the by-id analogue of the class-scan `testPolymorphicWithFilters`, which does cover property+label, but only on the no-id branch.

**Evidence**: Input-domain entry `otherContainers = non-empty` on the by-id branch is uncovered; see C1.

**Refutation considered**: Could the GraphStepStrategy fold prevent a `has(...)` from landing in the by-id step's containers? The class-scan `testPolymorphicWithFilters` shows `has()` containers do reach the step on the no-id path; the by-id path reads the same `this.hasContainers`, so a `has()` adjacent to `V(id)` would partition into `otherContainers`. The branch is reachable and unexercised. (PSI unavailable this session; the reachability claim rests on grep plus the class-scan test as a witness — see flags.)

**Suggested test**:
```java
@Test
public void testByIdHasLabelWithPropertyFilter() {
  createSimpleHierarchy();
  final var child = g().addV("Child").property("name", "keep").next();
  final var childId = child.id();
  // Same id, a property filter that the element fails -> otherContainers rejects it
  // even though the polymorphic label matches.
  checkSize(1, () -> gp().V(childId).has("name", "keep").hasLabel("Parent"));
  checkSize(0, () -> gp().V(childId).has("name", "other").hasLabel("Parent"));
}
```

### TC2 [should-fix] By-id chained `hasLabel("A").hasLabel("B")` (AND across containers) is untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBHasLabelProcessTest.java`
**Production code**: `core/.../gremlin/traversal/step/sideeffect/YTDBGraphStep.java` (lines 122-128)

**Missing scenario**: A by-id query with two distinct `hasLabel` containers, e.g. `gp().V(childId).hasLabel("Parent").hasLabel("Child")` (AND) versus `gp().V(childId).hasLabel("Parent").hasLabel("Grandparent")`. No by-id test produces a `labelContainers` list of size > 1.

**Why it matters**: The implementer note T2 (track-1.md lines 136-140) explicitly warns that multiple label containers must be ANDed (`labelContainers.stream().allMatch(...)`) and must **not** be collapsed into a single OR-list, because `hasLabel("A").hasLabel("B")` is intersection, not union. The code at YTDBGraphStep.java:122-128 implements `allMatch`, but the only test that exercises AND-across-`hasLabel` containers (`testPolymorphicWithAdditionalHasLabelFiltering`, lines 67-87) uses `gp().V().hasLabel(...).hasLabel(...)` — the **class-scan** path, a completely different code branch (lines 129-157). If the by-id `allMatch` were accidentally written as `anyMatch` (OR), or the loop folded into a single container, no by-id test would catch it. This is the exact failure mode T2 was raised to prevent, and it is the highest-value uncovered branch in the change.

**Evidence**: Input-domain entry `labelContainers.size() > 1` on the by-id branch is uncovered; the `allMatch` semantics are asserted nowhere for by-id. See C2.

**Refutation considered**: Does GraphStepStrategy fold a *second* `hasLabel` into the same step, or leave it as a separate `YTDBHasLabelStep`? If the second `hasLabel` does not fold, the by-id step sees one container and the second filter runs through `YTDBHasLabelStep` instead — meaning the `allMatch` loop over `labelContainers.size() > 1` may in fact be unreachable. That uncertainty is itself the reason to add the test: it either covers a real branch or documents that the branch is dead. Without PSI I cannot confirm the fold count for two adjacent `hasLabel` calls on a by-id step (grep-only; see flags), which is precisely why a behavioral test is warranted over a code-reading conclusion.

**Suggested test**:
```java
@Test
public void testByIdChainedHasLabelIsConjunction() {
  createSimpleHierarchy();
  final var child = g().addV("Child").next();
  final var childId = child.id();
  // AND across containers: Child satisfies both polymorphically.
  checkSize(1, () -> gp().V(childId).hasLabel("Parent").hasLabel("Grandparent"));
  // AND where the second container cannot match -> empty (would be 1 if OR-collapsed).
  g().command("CREATE CLASS Sibling IF NOT EXISTS EXTENDS Grandparent");
  checkSize(0, () -> gp().V(childId).hasLabel("Parent").hasLabel("Sibling"));
}
```

### TC3 [suggestion] Multiple pinned ids (`V(id1, id2).hasLabel(...).count()`) is untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBHasLabelProcessTest.java`
**Production code**: `core/.../gremlin/traversal/strategy/optimization/YTDBGraphCountStrategy.java` (lines 65-67); `YTDBGraphStep.java` (lines 117-128)

**Missing scenario**: A by-id query pinning more than one id, e.g. `gp().V(id1, id2).hasLabel("Parent").count()` and its `toList().size()`. Every by-id test pins exactly one id.

**Why it matters**: The count-strategy guard added in this track keys on `step.getIds().length == 0` (line 67), so any non-zero count short-circuits the rewrite — but the guard is only ever hit with `length == 1`. A multi-id count (`length == 2`) takes the same fall-through, and the `IteratorUtils.filter` in `elements()` (line 117) must apply the polymorphic label match independently to each loaded element. The prompt explicitly flags "count with multiple pinned ids" as a case to weigh. The risk is low because the guard is a simple `== 0` check, but the count-honors-id test would be strictly stronger if it pinned two ids and asserted 2.

**Evidence**: Input-domain entry `ids.length > 1` is uncovered on both the count-strategy guard and the per-element filter. See C3.

**Refutation considered**: The behavior at `length == 2` differs from `length == 1` only in iteration count, and the per-element filter is the same lambda. Marginal incremental coverage, hence suggestion rather than should-fix; but it directly extends the existing `testByIdHasLabelCountHonoursId` at near-zero cost.

**Suggested test** (fold into `testByIdHasLabelCountHonoursId`):
```java
final var childA = g().addV("Child").next();
final var childB = g().addV("Child").next();
// Two pinned ids, both Child -> count and toList agree at 2, id filter preserved.
checkSize(2, () -> gp().V(childA.id(), childB.id()).hasLabel("Parent"));
```

### TC4 [suggestion] By-id `hasLabel` on a vertex of an unrelated sibling class is untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBHasLabelProcessTest.java`
**Production code**: `core/.../gremlin/traversal/step/sideeffect/YTDBGraphStep.java` (lines 117-128)

**Missing scenario**: Pin the id of a vertex whose class is a real sibling (not on the queried label's hierarchy) and assert the label filter rejects it, e.g. create a `Sibling extends Grandparent`, then `gp().V(siblingId).hasLabel("Parent")` -> 0. The negative cases that exist (`testByIdHasLabelMultipleArguments` "Unrelated","AlsoUnrelated") test labels that do not exist as classes at all, which exercises a predicate-miss but not a real superclass-walk that terminates without a match.

**Why it matters**: The matcher's polymorphic loop walks `schemaClass.getAllSuperClasses()` and returns false only after exhausting a non-empty superclass chain (YTDBLabelMatcher.java:122-128). With "Unrelated" the predicate never matches any name in the chain because the name is absent; with a real `Sibling extends Grandparent` the element has a genuine multi-level superclass chain (`Grandparent`) that is walked and still must not match `Parent`. That distinguishes "predicate matched nothing" from "the supertype walk correctly excludes a cousin", which is the off-by-one-in-the-hierarchy class of bug.

**Evidence**: Input-domain entry "by-id element of an unrelated sibling class, polymorphic label miss after a real superclass walk" is uncovered. See C4.

**Refutation considered**: `testNonPolymorphicByIdHasLabel` covers a non-match, but with polymorphism off the superclass loop is skipped entirely (matcher line 118-119 early-returns). So the polymorphic superclass-walk-then-miss is genuinely uncovered. Low severity because the class-scan `testPolymorphicComplex` covers the same walk shape on the no-id path; the by-id path reuses the identical matcher, so the marginal risk is small.

**Suggested test**:
```java
@Test
public void testByIdHasLabelSiblingClassDoesNotMatch() {
  createSimpleHierarchy();
  g().command("CREATE CLASS Sibling IF NOT EXISTS EXTENDS Grandparent");
  final var sibling = g().addV("Sibling").next();
  final var siblingId = sibling.id();
  // Sibling IS-A Grandparent but NOT-A Parent; the polymorphic walk must miss "Parent".
  checkSize(1, () -> gp().V(siblingId).hasLabel("Grandparent"));
  checkSize(0, () -> gp().V(siblingId).hasLabel("Parent"));
  checkSize(0, () -> gp().V(siblingId).hasLabel("Child"));
}
```

## Evidence base

#### C1 — `otherContainers` AND-branch on the by-id path is unexercised

`YTDBGraphStep.elements()` lines 107-115 partition `this.hasContainers`; the `else` arm at 112-113 fills `otherContainers`. Line 119 ANDs `HasContainer.testAll(element, otherContainers)` with the label-matcher loop. Reading all eight by-id/has-id test methods (`testPolymorphicByIdHasLabel` through `testByIdHasLabelMultipleArguments`, lines 111-222), none place a non-`~label` container on a `V(id)` traversal — every one is `V(id).hasLabel(...)` only. `otherContainers` is therefore always empty and `testAll(element, [])` is vacuously true in every run. The class-scan `testPolymorphicWithFilters` (lines 296-376) proves `has()` containers do reach the step, but on the no-id branch (lines 129-157). CONFIRMED-as-issue: the by-id property-filter conjunction is uncovered.

#### C2 — `allMatch` AND-across-label-containers on the by-id path is unexercised

`YTDBGraphStep.elements()` lines 122-128 run `labelContainers.stream().allMatch(...)`. The track's T2 implementer note (track-1.md:136-140) raises this exact concern: multiple `hasLabel` containers must be ANDed, not OR-collapsed. Scanning the by-id tests, every one uses a single `hasLabel(...)` call (one container). The only AND-across-`hasLabel` coverage, `testPolymorphicWithAdditionalHasLabelFiltering` (lines 66-87), is on `gp().V().hasLabel(...).hasLabel(...)` — the class-scan branch, which does not touch the by-id `allMatch`. A by-id `allMatch`->`anyMatch` regression would be silent. There is residual uncertainty about whether GraphStepStrategy folds a second adjacent `hasLabel` into one by-id step (grep-only, PSI unavailable); the test resolves it either way. CONFIRMED-as-issue.

#### C3 — multi-id count and per-element filter are unexercised

`YTDBGraphCountStrategy.apply()` line 67 guards on `step.getIds().length == 0`. `testByIdHasLabelCountHonoursId` (lines 163-182) pins exactly one id and asserts 1. No test pins two ids, so `length == 2` through the guard and the per-element `IteratorUtils.filter` (YTDBGraphStep.java:117) over two loaded elements is unrun. Behavior at `length >= 2` differs from `length == 1` only by iteration count over the same per-element lambda, so the marginal risk is low; the extension is near-zero cost on the existing method. CONFIRMED as a low-value gap (suggestion).

#### C4 — polymorphic superclass-walk-then-miss on the by-id path is unexercised

The matcher (YTDBLabelMatcher.java:122-128) walks `getAllSuperClasses()` and returns false after exhausting a non-empty chain. The by-id negative cases that exist use label strings with no backing class (`testByIdHasLabelMultipleArguments` "Unrelated"/"AlsoUnrelated", lines 217, 221) or run with polymorphism off (`testNonPolymorphicByIdHasLabel`, lines 126-136) where the superclass loop is skipped at matcher line 118-119. Neither pins a real sibling class whose populated superclass chain is walked and still misses the queried label. The class-scan `testPolymorphicComplex` covers the walk shape on the no-id branch through the shared matcher, so the by-id marginal risk is small. CONFIRMED as a low-value gap (suggestion).
