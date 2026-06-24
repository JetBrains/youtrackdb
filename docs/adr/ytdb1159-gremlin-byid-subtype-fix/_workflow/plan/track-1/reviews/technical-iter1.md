<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: T1, sev: should-fix, loc: "YTDBGraphStep.java:101-103 (by-id branch); YTDBLabelMatcher.java (new)", anchor: "### T1 ", cert: "Premise: HasContainer.getPredicate() type", basis: "getPredicate() returns P<?>, not P<? super String>; by-id branch needs an unchecked cast to pass each label container's predicate into the matcher's List<P<? super String>> signature"}
  - {id: T2, sev: suggestion, loc: "YTDBGraphStep.java:90-103; YTDBLabelMatcher.java (new)", anchor: "### T2 ", cert: "Integration: matcher call sites; Premise: getElementsByIds element types", basis: "matcher signature takes List<P<...>> but the by-id partition wants per-container matching with AND across containers; clarify whether matcher takes one predicate-list (per container) or the full list"}
  - {id: T3, sev: suggestion, loc: "YTDBGraphStep.java:139-153 (createClassIterator); vertices()", anchor: "### T3 ", cert: "Edge case: V(id) on a YTDBSchemaClass label container", basis: "schema-class iterator path is keyed off the same ~label container the by-id partition now reads; confirm the by-id branch never co-exists with the schema-class-label container"}
evidence_base: {section: "## Evidence base", certs: 11, matches: 9}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: PARTIAL, anchor: "#### C6 "}
  - {id: C7, verdict: CONFIRMED, anchor: "#### C7 "}
  - {id: C8, verdict: CONFIRMED, anchor: "#### C8 "}
  - {id: C9, verdict: CONFIRMED, anchor: "#### C9 "}
  - {id: C10, verdict: PARTIAL, anchor: "#### C10 "}
  - {id: C11, verdict: CONFIRMED, anchor: "#### C11 "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: Premise C6 (`HasContainer.getPredicate()` return type)
**Location**: `YTDBGraphStep.java:101-103` (by-id branch, to be modified); new `YTDBLabelMatcher`
**Issue**: The track's `## Interfaces and Dependencies` declares the matcher as
`matches(Element, List<P<? super String>>, boolean)` and the by-id branch will feed it the label
container's predicate via `HasContainer.getPredicate()`. The TinkerPop-fork signature is
`public final P<?> getPredicate()` (gremlin-core 3.8.0-YTDB-SNAPSHOT sources,
`HasContainer.java:133`) — it returns `P<?>`, **not** `P<? super String>`. Assigning a `P<?>` into a
`List<P<? super String>>` element does not compile without an unchecked cast. This is not a blocker
(the cast is trivial and the existing folding path already does exactly this), but the track text
does not mention it, so an implementer following the signature literally will hit a compile error.
The precedent is `YTDBGraphStepStrategy.java:137`, which builds the `YTDBHasLabelStep` predicate list
with `//noinspection unchecked` + `(P<? super String>) hc.getPredicate()`.
**Proposed fix**: In the step roster, note that the by-id branch (and any place handing
`getPredicate()` to the matcher) must cast `(P<? super String>) container.getPredicate()` with the
same `@SuppressWarnings`/`//noinspection unchecked` convention used at `YTDBGraphStepStrategy:137`.
*Reference-accuracy caveat: signature read from the fork's `-sources.jar`, not via IDE/PSI
(mcp-steroid unreachable); confirmed against the existing cast at the folding site.*

### T2 [suggestion]
**Certificate**: Integration C9 (matcher call sites); Premise C2 (`YTDBHasLabelStep.filter` shape)
**Location**: `YTDBGraphStep.java:90-103`; new `YTDBLabelMatcher`; `YTDBHasLabelStep.java:42-77`
**Issue**: The matcher's planned signature takes the *full* predicate list and OR-combines it (lifted
verbatim from `YTDBHasLabelStep.filter`, where the single step holds one OR-list — confirmed at
`YTDBHasLabelStep:42-44`, `anyMatch`). But the by-id branch's job (Plan-of-Work step 3) is to
partition `hasContainers` into label containers and AND across them — each label container carries one
predicate (which may itself be a `Contains.within` over a list for `hasLabel("A","B")`). So the by-id
caller must invoke the matcher once per label container with a single-element predicate list, then AND
the results, rather than collapsing all label containers into one OR-list. The track text says "ANDing
across label containers" (correct) and "run each label container's predicate through
`YTDBLabelMatcher.matches`" (correct) — but pairing that with a `List<P<...>>` parameter is mildly
inconsistent: per-container calls pass a singleton list. This works, but the signature invites a reader
to merge containers into one list (which would silently turn the AND into an OR).
**Proposed fix**: In the decomposition, make explicit that the by-id branch calls the matcher
once *per label container* (singleton predicate list) and ANDs the booleans; the `List<P<...>>`
overload exists for `YTDBHasLabelStep`, which legitimately holds an OR-list. Optionally add a single
`P<? super String>`-arg helper to remove the singleton-list ceremony at the by-id site.

### T3 [suggestion]
**Certificate**: Edge case C10 (`V(id)` co-existing with a `YTDBSchemaClass.LABEL` container)
**Location**: `YTDBGraphStep.java:139-153` (`createClassIterator`), `vertices()` at `:54-72`
**Issue**: `vertices()` unions the `elements()` result with a `createClassIterator` result whenever any
`hasContainer` is a `~label == YTDBSchemaClass.LABEL` filter (the schema-class meta path). That branch
reads the same `~label` containers the by-id partition will now consume. For a normal
`V(id).hasLabel("Parent")` query the value is `"Parent"`, not `YTDBSchemaClass.LABEL`, so
`createClassIterator` returns null and the union is a no-op — the by-id fix is unaffected. The track
does not call out this interaction, and it is almost certainly benign, but it is the one place where
a label container is read by code *outside* the two `elements()` branches.
**Proposed fix**: No code change required. Add a one-line note to the track's `## Context and
Orientation` that `createClassIterator` keys off `~label == YTDBSchemaClass.LABEL` (the schema-meta
sentinel), is orthogonal to ordinary label values, and is left untouched — so a future reader does not
mistake it for a third place needing the polymorphic fix.

## Evidence base

#### C1 Premise: YTDBGraphStep by-id branch uses an exact `HasContainer.testAll` ignoring `polymorphic`
- **Track claim**: "The by-id branch (when `this.ids.length > 0`) filters loaded elements with a single `HasContainer.testAll(element, this.hasContainers)`, an exact match that ignores the `polymorphic` field — this is the YTDB-1159 defect."
- **Search performed**: Read `YTDBGraphStep.java` in full (grep fallback; mcp-steroid unreachable).
- **Code location**: `YTDBGraphStep.java:99-103`
- **Actual behavior**: `if (this.ids != null && this.ids.length > 0) { return IteratorUtils.filter(getElementsByIds.apply(graph, this.ids), element -> HasContainer.testAll(element, this.hasContainers)); }`. The `polymorphic` field (`:36`) is never read on this branch; only the class-scan `else` branch reads it (`:119`).
- **Verdict**: CONFIRMED
- **Detail**: Exact as described. `vertices()` (`:57`) and `edges()` (`:76`) both call `elements()`, so the edge case is covered with no extra branch — matches the track claim. *Reference-accuracy caveat: read via grep/Read, not PSI.*

#### C2 Premise: `YTDBHasLabelStep.filter()` already does the correct polymorphic OR-then-superclass match
- **Track claim**: "`filter()` already does the correct polymorphic match: test the concrete `schemaClass.getName()`, return on match, else if `!polymorphic` return false, else walk `schemaClass.getAllSuperClasses()`. Its `predicates` list is OR-combined via `anyMatch`. A null schema class returns false ... Non-YouTrackDB traversers fall back to `test(traverser.get().label())`."
- **Search performed**: Read `YTDBHasLabelStep.java` in full.
- **Code location**: `YTDBHasLabelStep.java:42-77`
- **Actual behavior**: `test(String)` = `predicates.stream().anyMatch(p -> p.test(className))` (`:42-44`, OR). `filter` checks `instanceof YTDBElementImpl`, reads `getRawEntity().getSchemaClass()`, returns false on null (`:56-59`), tests concrete name (`:60`), returns false on `!polymorphic` (`:63-65`), walks `getAllSuperClasses()` (`:67-71`), else `test(traverser.get().label())` (`:75`).
- **Verdict**: CONFIRMED
- **Detail**: Every clause matches. Lifting this verbatim into `YTDBLabelMatcher` is a faithful, behavior-preserving extraction.

#### C3 Premise: `YTDBGraphCountStrategy` label-filter branch lacks the `getIds().length == 0` guard
- **Track claim**: "The label-filter branch (`hasContainers.size() == 1 && isLabelFilter(...)`) lacks the `getIds().length == 0` guard the empty-containers branch has, so it drops the id — the Bug 2 defect."
- **Search performed**: Read `YTDBGraphCountStrategy.java` in full.
- **Code location**: `YTDBGraphCountStrategy.java:65-73`
- **Actual behavior**: `if (hasContainers.size() == 1 && isLabelFilter(hasContainers.getFirst())) { classes = extractLabels(...); } else if (hasContainers.isEmpty() && step.getIds().length == 0) { ... }`. The first branch has no id guard; the second does.
- **Verdict**: CONFIRMED
- **Detail**: When `V(id).hasLabel("X").count()` reaches this strategy, `hasContainers.size()==1` and `isLabelFilter` is true, so it rewrites to `YTDBClassCountStep` and the id is discarded — exactly the described id-drop.

#### C4 Premise: count fall-through after the guard yields a correct id-bearing count
- **Track claim**: "Add the `getIds().length == 0` condition ... so an id-bearing count falls through to normal by-id execution."
- **Search performed**: Read `YTDBGraphCountStrategy.apply` (`:42-81`); traced the post-guard control flow.
- **Code location**: `YTDBGraphCountStrategy.java:75-79`
- **Actual behavior**: With the guard added, an id-bearing count makes the first branch false and the second false (`hasContainers` non-empty), so `classes` stays `List.of()`; `if (!classes.isEmpty())` (`:75`) is false; no `removeAllSteps`/rewrite happens. The traversal keeps `YTDBGraphStep(by-id) + CountGlobalStep`. After Bug 1's matcher fix, the by-id branch filters by label (polymorphically), and `CountGlobalStep` counts that filtered output.
- **Verdict**: CONFIRMED
- **Detail**: The fall-through is correct **only because Bug 1 is fixed first** — if the matcher fix landed after the guard, an intermediate commit would count the unfiltered/exact-matched by-id output. This is precisely the fix-order constraint the track states ("Land the matcher and the polymorphism fix before the count guard"). The constraint is real and load-bearing, not decorative.

#### C5 Premise (NAMED REFERENCE): `YTDBLabelMatcher` is a class this track creates
- **Track claim**: "Introduce the shared `YTDBLabelMatcher`" / "`core/.../gremlin/traversal/step/filter/YTDBLabelMatcher.java` (new)".
- **Search performed**: `find . -name 'YTDBLabelMatcher.java'` → zero matches.
- **Code location**: NOT FOUND (expected)
- **Actual behavior**: No existing class; the track marks it `(new)` in `## Interfaces and Dependencies`.
- **Verdict**: CONFIRMED
- **Detail**: Planned by this track — per the NAMED REFERENCES rule, a not-yet-existing name explicitly marked new is CONFIRMED, not a blocker.

#### C6 Premise: `HasContainer.getPredicate()` returns a `P<?>` usable as `P<? super String>`
- **Track claim**: Signature list cites "`HasContainer.getPredicate()`" feeding `List<P<? super String>>`.
- **Search performed**: Extracted `HasContainer.java` from `gremlin-core-3.8.0-YTDB-SNAPSHOT-sources.jar`; grep for `getPredicate`/`getKey`/`testAll`.
- **Code location**: `HasContainer.java:133` (`public final P<?> getPredicate()`), `:171` (`public static boolean testAll(Element, List<HasContainer>)`), `getKey()` present.
- **Actual behavior**: Returns `P<?>`, not `P<? super String>`. The existing folding code at `YTDBGraphStepStrategy.java:137` already bridges this with `//noinspection unchecked` + `(P<? super String>) hc.getPredicate()`.
- **Verdict**: PARTIAL
- **Detail**: The method exists and is usable, but only via an unchecked cast — the track's signature implies a direct fit. Produced finding **T1**. *Reference-accuracy caveat: read from the fork sources jar, not PSI.*

#### C7 Premise: by-id elements are reliably `YTDBElementImpl` (so `getRawEntity().getSchemaClass()` works)
- **Track claim**: Matcher reuses `filter`'s `instanceof YTDBElementImpl` + `getRawEntity().getSchemaClass()` path; the by-id branch's elements must satisfy it.
- **Search performed**: Read `YTDBGraphStep.vertices()/edges()` (the `getElement` mappers); `grep` for `class YTDBVertexImpl`/`class YTDBEdgeImpl`; read `YTDBElementImpl.getRawEntity` and `EntityImpl.getSchemaClass`.
- **Code location**: `YTDBGraphStep.java:57-59,76-78`; `YTDBVertexImpl.java:22`; `YTDBEdgeImpl.java:13`; `EntityImpl.java:3852`
- **Actual behavior**: `vertices()` maps each result to `new YTDBVertexImpl(graph, result.asVertex())`; `edges()` to `new YTDBEdgeImpl(...)`. Both `YTDBVertexImpl` and `YTDBEdgeImpl` `extends YTDBElementImpl`. `YTDBElementImpl.getRawEntity()` returns a bound `Entity`; `EntityImpl.getSchemaClass()` exists (`:3852`).
- **Verdict**: CONFIRMED
- **Detail**: For the by-id path the loaded elements are always `YTDBElementImpl` subtypes, so the matcher's `instanceof` branch (not the `element.label()` fallback) is taken — the polymorphic walk applies. The non-YouTrackDB fallback is dead on this path but harmless. *Reference-accuracy caveat: subtype relation read via grep on class declarations, not PSI hierarchy.*

#### C8 Premise: `SchemaClass.getAllSuperClasses()` / `getName()` exist for the polymorphic walk
- **Track claim**: Matcher walks `schemaClass.getAllSuperClasses()` and tests `c.getName()`.
- **Search performed**: grep `SchemaClass.java`.
- **Code location**: `SchemaClass.java:142` (`Collection<SchemaClass> getAllSuperClasses()`), `:72` (`String getName()`)
- **Actual behavior**: Both declared on the `SchemaClass` interface.
- **Verdict**: CONFIRMED

#### C9 Integration: routing `YTDBHasLabelStep.filter()` and the by-id branch through the shared matcher
- **Plan claim**: Both `YTDBHasLabelStep.filter()` and `YTDBGraphStep`'s by-id branch call `YTDBLabelMatcher.matches(...)`; the `YTDBHasLabelStep` refactor is behavior-preserving.
- **Actual entry point**: `YTDBHasLabelStep.java:46-77` (filter); `YTDBGraphStep.java:99-103` (by-id branch).
- **Caller analysis**: `YTDBHasLabelStep` is constructed only at `YTDBGraphStepStrategy.java:144-145` (the non-folded `hasLabel` path). The by-id branch of `elements()` is reached from `vertices()`/`edges()`, which are the step's iterator supplier (`YTDBGraphStep:46-47`). No other production caller of the matcher exists yet (it is new). *Reference-accuracy caveat: caller search via grep, not PSI find-usages — polymorphic dispatch on `FilterStep.filter` is not exhaustively traced, but `YTDBHasLabelStep` is `final`-ish in effect (no subclasses found).*
- **Breaking change risk**: Low. `YTDBHasLabelStep.filter` delegates to a verbatim extraction; the class-scan regression suite (`testPolymorphicSimple`, `testPolymorphicComplex`, `testPolymorphicWithFilters`, `testPolymorphicMultipleLabels`, etc., all present in `YTDBHasLabelProcessTest`) guards behavior preservation.
- **Verdict**: MATCHES

#### C10 Edge case: `V(id)` query co-existing with a `YTDBSchemaClass.LABEL` schema-meta container
- **Trigger**: A `V(id)` traversal whose folded `hasContainer` is `~label == YTDBSchemaClass.LABEL` (schema-class meta-query), while the by-id partition also reads `~label` containers.
- **Code path trace**:
  1. `vertices()` @ `YTDBGraphStep:54` calls `elements(...)` (by-id branch) then `createClassIterator(graph)` @ `:61`.
  2. `createClassIterator` @ `:139-153` returns non-null only if some container is `~label == YTDBSchemaClass.LABEL`; for an ordinary label value (`"Parent"`) it returns null @ `:152`.
  3. With null, `vertices()` returns the by-id `userVertices` unchanged @ `:62-64`.
- **Outcome**: For normal `V(id).hasLabel("X")` the schema-meta path is inert; the by-id fix is unaffected. The two readers of `~label` containers (by-id partition vs. `createClassIterator`) discriminate on the *value* (`YTDBSchemaClass.LABEL` sentinel vs. a real class name), so they do not collide.
- **Track coverage**: no — the track does not mention `createClassIterator`. Produced finding **T3** (doc-only).
- **Verdict**: PARTIAL — benign, but undocumented interaction.

#### C11 Integration: `YTDBHasLabelProcessTest` is registered in the suite (test path reaches CI)
- **Plan claim**: "the scenario class runs only through the suite"; new test methods added to `YTDBHasLabelProcessTest`.
- **Actual entry point**: `suites/YTDBGremlinProcessTests.java:187` (`YTDBHasLabelProcessTest.class`), imported at `:4`. Runner `YTDBProcessTest.java` carries `@GraphProviderClass(provider = YTDBGraphProvider.class, graph = YTDBGraph.class)`.
- **Caller analysis**: The scenario class is listed in the suite's `@SelectClasses`-equivalent member array; the four existing by-id methods (`testPolymorphicByIdHasLabel`, `testNonPolymorphicByIdHasLabel`, `testPolymorphicHasIdHasLabel`, `testNonPolymorphicHasIdHasLabel`) and `checkSize` (asserting `toList().size() == count()`) are already present in the working tree (`YTDBHasLabelProcessTest.java:112-166`).
- **Breaking change risk**: None — adding methods to a registered class. The planned count-honors-id / edge-by-id / multi-arg-by-id methods will execute through the same suite.
- **Verdict**: MATCHES
- **Detail**: The track's claim that four by-id methods already exist uncommitted is accurate — they are in the working tree (the `git status` shows `YTDBHasLabelProcessTest.java` modified). `checkSize` already asserts the `toList == count` equality the track relies on, so the count-honors-id scenario reuses existing machinery.
