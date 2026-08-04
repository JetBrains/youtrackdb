# Step 15 sizing probe — measured

**Three parts of DR-S16 are refuted by measurement, and the one that matters most is the fold boundary.** `where(...)`, `filter(...)` and an all-filter `and(...)` are inlined into the root fold before the translator ever sees them, so DR-S16's rule "no `has` inside `not()` / `where()` / `and()` is folded" is wrong for two of those three. `PropertyType` has no `ANY` member, so that branch of the decline does not exist. And `boundaryAlias` is necessary but not sufficient to derive position — `limit()` breaks the fold without advancing the alias.

**The premise that survives is the mechanism.** TinkerPop's unfolded rule really is match-nothing, the fold really does answer the same traversal differently, and a position-aware translation really would close it.

**The premise that survives but no longer sizes the step is the reach.** Measured, the declared-type branch fires in exactly one unfolded shape: when the user writes `hasLabel(L)` adjacent to the range `has()` so both land in one `HasStep`. Everywhere else the declared type is not resolvable at recognition time, and the rule falls through to the decline. Step 15 as designed is mostly a decline widened from `not()` to every unfolded position, and that decline withdraws a currently-correct, currently-tested surface: ten `EdgeTraversalEquivalenceTest` methods flip from `RECOGNIZED` to `DECLINED`.

**Two divergences DR-S16 does not mention are live at HEAD and are not covered by its fix.** An `or(...)` arm and a post-hop `where(...)` both diverge today, and step 13's gate is wired into `NotStepRecogniser` alone.

Measured on `965363e025` in `.claude/worktrees/t9-step15`, one throwaway probe class
(`core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/Step15MeasurementProbe.java`,
uncommitted), translator forced on then off through the session config, boundary steps counted on the
strategised traversal. Raw output: `/tmp/step15-measure/report.txt`.

## The fixture, and why it discriminates

Nine vertices. Four `Item` (class declares `name` STRING and `num` INTEGER), two `Loose` (declares
nothing; stores `name` as the String `"zulu"` on one vertex and the Integer `99` on the other), two
`Anyp` (class exists, key `val` undeclared, holds a String and an Integer), one `Root`. Only `Root`
has out-edges, and it links to all four `Item`s.

Three orderings all differ, so no measurement below can confuse RID order with value order:

| ordering | sequence |
|---|---|
| insertion | delta, alpha, charlie, bravo |
| RID (measured) | alpha, bravo, delta, charlie |
| sorted by name | alpha, bravo, charlie, delta |

Both arms of the fold comparison enumerate the same underlying rows, confirmed before any divergence
is asserted: `g.V().out()` and `g.V().hasLabel("Item")` each return the same four `Item` vertices on
both arms, and `g.V()` returns all nine on both.

## Question 1 — TinkerPop's answer on a cross-type range comparison

**Match nothing. All eight combinations, no exception on any of them.** Traversal
`g.V().out().has(k, P)` — an unfolded position by measurement (see question 2, row c).

| traversal | translated | native |
|---|---|---|
| `.has("name", gt(27))` | 4 `[alpha, bravo, charlie, delta]` | 0 `[]` |
| `.has("name", gte(27))` | 4 `[alpha, bravo, charlie, delta]` | 0 `[]` |
| `.has("name", lt(27))` | 0 `[]` | 0 `[]` |
| `.has("name", lte(27))` | 0 `[]` | 0 `[]` |
| `.has("num", gt("m"))` | 0 `[]` | 0 `[]` |
| `.has("num", gte("m"))` | 0 `[]` | 0 `[]` |
| `.has("num", lt("m"))` | 0 `[]` | 0 `[]` |
| `.has("num", lte("m"))` | 0 `[]` | 0 `[]` |

Same-type controls after the same hop agree on both arms, which is what makes the four zeros above
attributable to the type mismatch rather than to the hop: `.has("num", gt(25))` returns 2
`[charlie, delta]` on both arms, `.has("name", gt("b"))` returns 3 `[bravo, charlie, delta]` on both.

**The fold's own answer, for contrast** (translator on and off agree, so this is `YTDBGraphStep`'s
comparator speaking): `g.V().has("name", gt(27))` returns 6 on both arms — every name-bearing vertex
including the one whose `name` is the Integer `99` — and `gte` likewise 6, `lt` and `lte` 0. So the
comparator ranks every String above the Integer 27. `g.V().has("num", gt("m"))` and its `lt` twin
both return 0, on both arms.

**The divergence is one-sided.** Only a String-valued property against a numeric literal under
`gt` / `gte` diverges. An Integer-valued property against a String literal answers 0 on both arms in
all four directions, because SQL ranks the String above the Integer and so agrees with native's
match-nothing by accident. Any fix that treats "cross-type" as symmetric will change four answers
that are already correct.

## Question 2 — the fold boundary, measured

Structural read of the strategised step list with the translator off. `FOLDED` means the container
ended up inside `YTDBGraphStep`; `UNFOLDED` means a `HasStep` survived.

| # | traversal as written | where the range container landed |
|---|---|---|
| a | `g.V().has(name, gt(27))` | FOLDED |
| b | `g.V().has(num, eq(10)).has(name, gt(27))` | FOLDED — both containers, one `YTDBGraphStep` |
| b2 | `g.V().has(num, eq(10)).limit(100).has(name, gt(27))` | `eq` FOLDED, `gt` UNFOLDED |
| c | `g.V().out().has(name, gt(27))` | UNFOLDED (`VertexStep`, `NoOpBarrierStep`, `HasStep`) |
| d | `g.V().hasLabel(Item).has(name, gt(27))` | FOLDED — `~label` and `name` in one `YTDBGraphStep` |
| d2 | `g.V().hasLabel(Item).out().has(name, gt(27))` | UNFOLDED |
| d3 | `g.V().out().hasLabel(Item).has(name, gt(27))` | UNFOLDED, and the surviving `HasStep` carries both `~label` and `name` |
| e | `g.V().not(has(name, gt(27)))` | UNFOLDED, inside the `NotStep` child |
| f | `g.V().where(has(name, gt(27)))` | **FOLDED** |
| g | `g.V().order().by(name).has(name, gt(27))` | **FOLDED**, and the `OrderGlobalStep` now sits *after* it |
| h | `g.V().hasLabel(Item).limit(100).has(name, gt(27))` | UNFOLDED |
| i | `g.V().and(has(name, gt(27)), has(num, gt(0)))` | **FOLDED** — both arms |
| j | `g.V().or(has(name, gt(27)), has(num, eq(10)))` | UNFOLDED, inside the `OrStep` children |
| k | `g.V().hasLabel(Item).where(has(name, gt(27)))` | FOLDED — `~label` and `name` in one `YTDBGraphStep` |
| l | `g.V().out().where(has(name, gt(27)))` | UNFOLDED, and the `where` child is gone — a top-level `HasStep` |
| m | `g.V().filter(has(name, gt(27)))` | FOLDED |
| n | `g.V().and(has(name, gt(27)), out())` | UNFOLDED — the `AndStep` survives when an arm is not a filter |
| o | `g.V().outE(link).has(since, lt(2025)).inV()` | UNFOLDED |

Rows f, i, k, l and m are TinkerPop's `InlineFilterStrategy` rewriting a filter-only child into a
top-level `HasStep`. Row g is `FilterRankingStrategy` hoisting the `has` above the `order`. Row n is
the boundary of the inlining: one non-filter arm and the `AndStep` stays. Rows e and j show `not` and
`or` are never inlined.

**These rewrites happen before the translator runs, and the translator sees the rewritten list.**
`GremlinToMatchStrategy` is a `ProviderOptimizationStrategy`
(`GremlinToMatchStrategy.java:160-162`), and TinkerPop runs the whole `OptimizationStrategy` category
first. The measured witness is in section 4 of the raw output: `g.V().hasLabel("Item").where(has(name,
startingWith("b")))` emits the declared-String prefix range `name >= ? AND name < ?`, which
`HasStepRecogniser` only produces when the `~label` and the property container are in the *same*
`HasStep` — so the merge had already happened when the recogniser ran.

### The predicate, in terms the recogniser can compute

The surface syntax is not the predicate. `where(__.has(k, p))` folds and `not(__.has(k, p))` does
not, though both are children in the traversal the user wrote. What the recogniser can compute is a
latch over the step list it is already walking, mirroring `rebuildTraversal`'s `isTraversalStart`
exactly:

> A `HasStep` is **folded** if and only if it is dispatched from the top-level walk (not from a
> `walkChild` sub-walk) and every step consumed at top level since the most recent `GraphStep` was
> itself a `HasStep`.

One boolean on `WalkerContext`: set true whenever a `GraphStep` is consumed, cleared by the first
non-`HasStep` consumed at top level, and read as false unconditionally inside `walkChild`. Set on
*any* `GraphStep`, not only the first — `rebuildTraversal` does the same, so a mid-traversal `V()`
restarts the fold in both places and a latch that special-cases the first step would drift from it.

**`boundaryAlias` is not that predicate.** It is necessary, not sufficient: rows b2 and h break the
fold with a `limit()`, which advances no alias. Today that gap is latent rather than live, because a
`limit()` before a `has()` declines anyway (`g.V().hasLabel(Item).limit(100).has(name,
startingWith("b"))` measured as DECLINED, no boundary step). It becomes live the moment
`RangeGlobalStepRecogniser` accepts that position.

## Question 3 — the narrowed shapes under composition

`AGREE` / `DIVERGE` compares the two arms' sorted row sets. `boundary` is the translated arm's
boundary-step count; 0 means the traversal declined and both arms ran native.

| traversal | boundary | translated | native | |
|---|---|---|---|---|
| `g.V().or(has(name, gt(27)), has(num, eq(10)))` | 1 | 6 | 2 `[99, alpha]` | **DIVERGE** |
| `g.V().hasLabel(Item).or(has(name, gt(27)), has(num, eq(10)))` | 1 | 4 | 1 `[alpha]` | **DIVERGE** |
| `g.V().out().or(has(name, gt(27)), has(num, eq(10)))` | 1 | 4 | 1 `[alpha]` | **DIVERGE** |
| `g.V().out().where(has(name, gt(27)))` | 1 | 4 | 0 `[]` | **DIVERGE** |
| `g.V().out().hasLabel(Item).has(name, gt(27))` | 1 | 4 | 0 `[]` | **DIVERGE** |
| `g.V().and(has(name, gt(27)), has(num, gt(0)))` | 1 | 4 | 4 | AGREE |
| `g.V().hasLabel(Item).and(has(name, gt(27)), has(num, gt(0)))` | 1 | 4 | 4 | AGREE |
| `g.V().where(has(name, gt(27)))` | 1 | 6 | 6 | AGREE |
| `g.V().filter(has(name, gt(27)))` | 1 | 6 | 6 | AGREE |
| `g.V().not(has(name, gt(27)))` | 0 | 8 | 8 | AGREE |
| `g.V().hasLabel(Item).not(has(name, gt(27)))` | 0 | 4 | 4 | AGREE |
| `g.V().not(not(has(name, gt(27))))` | 0 | 1 `[99]` | 1 `[99]` | AGREE |
| `g.V().hasLabel(Item).not(not(has(name, gt(27))))` | 0 | 0 | 0 | AGREE |
| `g.V().not(out().has(name, gt(27)))` | 0 | 9 | 9 | AGREE |
| `g.V().out().not(has(name, gt(27)))` | 0 | 4 | 4 | AGREE |
| `g.V().and(has(name, gt(27)), out())` | 0 | 0 | 0 | AGREE |
| `g.V().hasLabel(Loose).not(has(name, gt(27)))` | 0 | 1 `[zulu]` | 1 `[zulu]` | AGREE |
| `g.V().hasLabel(Loose).or(has(name, gt(27)), has(name, eq("zulu")))` | 1 | 2 | 2 | AGREE |

Three things follow.

**Every `not(...)` form already agrees at HEAD, and agrees by declining.** DR-S16's headline —
`g.V().not(has("name", gt(27)))` returning 0 translated against 6 native — describes the pre-step-13
world. Step 13's gate is in place and every negated form above has `boundary=0`. Step 15's value on
the `not()` surface is recovered coverage, not a correctness fix. That is worth stating plainly
because it changes what the step is for.

**The `or(...)` and post-hop `where(...)` surfaces diverge today and are not covered.**
`traversalHasRangeComparison` has exactly one production caller, `NotStepRecogniser.java:79`.
`OrStepRecogniser`, `AndStepRecogniser`, `WhereTraversalStepRecogniser` and
`TraversalFilterStepRecogniser` have no gate. The OR rows above are, as far as this probe found,
newly measured; the review corpus was not searched exhaustively for a prior record.

**`and(...)` is safe only by accident of inlining.** Rows i and n: an all-filter `and` folds and so
both arms run SQL semantics, but the moment one arm is not a filter the `AndStep` survives and the
range container is unfolded. Row n declines today for an unrelated reason. Any change to
`AndStepRecogniser`'s coverage moves that shape into the divergent set.

## What `declaredPropertyType` can return, and the mixed-mode case

**`PropertyType` has no `ANY` member** (`core/.../metadata/schema/schema/PropertyType.java`, 22
constants, all concrete). DR-S16's "a property declared `ANY`" case does not exist in this schema
model. The unknown bucket is: no schema, class not in the schema, or class known but key not
declared.

| lookup | result |
|---|---|
| `Item.name` | declared, `STRING` |
| `Item.num` | declared, `INTEGER` |
| `Item.extra` (undeclared key on a declared class) | `getProperty` returns null |
| `Loose.name` (schema-less class) | null |
| `Anyp.val` | null |
| `Missing` | `schema.getClass` returns null |
| `V.name` | null |

**A stored value cannot violate a declaration — the write path coerces or rejects.** Measured
through `graph.addVertex`:

| write | outcome |
|---|---|
| Integer `5` into declared-STRING `Item.name` | ACCEPTED, stored and read back as the String `"5"` |
| String `"x"` into declared-INTEGER `Item.num` | REJECTED, `NumberFormatException` |
| Double `1.5` into declared-INTEGER `Item.num` | ACCEPTED, coerced to Integer `1` |

So DR-S16's third worry — "a class in mixed mode where a stored value may not match the
declaration" — does not arise for a *declared* property written through this path. A declared type is
a sound static fact. Not measured: data written before the property was declared, which a later
`createProperty` would not retroactively coerce. If step 15 proceeds, that is the one case worth a
targeted check before the declared type is trusted as an invariant.

The widening itself is as small as DR-S16 says. `WalkerContext.isDeclaredStringProperty`
(`WalkerContext.java:393-411`) walks `schema.getClass` → `getProperty` → `getType` and narrows on its
last line; returning the nullable `PropertyType` instead is that one line. Three production call
sites read it (`HasStepRecogniser.java:130`, `EdgeHopRecogniser.java:113`,
`WherePredicateStepRecogniser.java:47`), one delegation forwards it
(`SubTraversalPredicateAdapter.java:229`) and `RecognitionContext.java:133` declares it; keeping the
boolean as a derived form over the new accessor leaves all four untouched.

## Position availability at recognition time

Half of DR-S16's claim holds. Whether the walk is inside a child traversal is known by construction —
`walkChild` is an explicit call and `SubTraversalPredicateAdapter` is a distinct context object. What
does **not** hold is that the declared type is reachable once you are there.

`HasStepRecogniser` keys its type gate on the step's own `~label` container only
(`HasStepRecogniser.java:128-130`: `var typeClass = labelClass`), never on `ctx.boundaryClassName()`.
The `startingWith` routing makes that visible in the emitted plan, because a declared-String property
routes to a prefix range and everything else routes to the strict form:

| position | emitted filter |
|---|---|
| `g.V().hasLabel(Item).has(name, startingWith("b"))` | `name >= ? AND name < ?` |
| `g.V().out().hasLabel(Item).has(name, startingWith("b"))` | `name >= ? AND name < ?` |
| `g.V().hasLabel(Item).out().has(name, startingWith("b"))` | `name STARTSWITH ?` |
| `g.V().hasLabel(Item).not(has(name, startingWith("b")))` | `NOT name STARTSWITH ?` |
| `g.V().hasLabel(Item).where(has(name, startingWith("b")))` | `name >= ? AND name < ?` |

Rows three and four are the ones that matter: after a hop and inside `not()`, the class the user
declared at the root is not consulted, so `Item.name`'s declared STRING is invisible. Row five looks
like a counterexample and is not — the `where` was inlined into the root `HasStep`, carrying the
`~label` with it.

**Consequence for reach.** The declared-type branch fires in exactly one unfolded shape, the one
where `hasLabel(L)` is written adjacent to the range `has()` so both land in one `HasStep`:
`g.V().out().hasLabel("Item").has("name", gt(27))`, measured 4 translated against 0 native, which the
"match nothing" translation would close. Its same-type sibling
`g.V().out().hasLabel("Item").has("num", gt(25))` returns 2 on both arms and must keep translating.
Every other unfolded shape measured resolves to an unknown type and falls to the decline.

Widening the reach means making `HasStepRecogniser` fall back to `ctx.boundaryClassName()`. That is a
second behaviour change, not a free extension: it flips `startingWith` from the strict form to the
index-aware prefix range in exactly the positions in rows three and four above, which is a plan-shape
change on a surface step 15 is not otherwise touching.

## Blast radius

Grep-based over test sources and feature files; mcp-steroid PSI was not used, since it has timed out
in this repository before and the question is textual over test bodies rather than symbol-reference.
Two limits follow: classification reads the written chain rather than the strategised list, and a
range predicate reaching the translator through a helper would be missed.

**Would flip — 12 Java test methods.**

Ten in `core/src/test/.../translator/strategy/EdgeTraversalEquivalenceTest.java`, all asserting
`Recognition.RECOGNIZED` (boundary count 1) plus multiset equality, all filtering an **undeclared**
edge property after a hop, so all land in the unknown → decline bucket and flip to boundary 0:
`nonAdjacentOutEdgeFilter_returnsSameMultisetAsNative` (L208),
`labelLessEdgeFilter_returnsSameMultisetAsNative` (L233),
`nonAdjacentInEdgeFilter_returnsSameMultisetAsNative` (L254),
`nonAdjacentEdgeFilter_parallelEdgesPreserveMultiplicity` (L302),
`hopThenFilteredEdgeChain_recognizedWithInterleavedBarrier` (L329),
`nonAdjacentEdgeFilter_excludesDifferentLabelEdge` (L357),
`nonAdjacentEdgeFilter_spansSubclassEdgeLikeNative` (L547),
`nonAdjacentEdgeFilter_positiveComparisonExcludesAbsentProperty` (L691),
`selfLoop_filteredEdgeChain_returnsSelfLikeNative` (L747),
`nonAdjacentBothEdgeFilter_bothVClose_matchesNative` (L905).

Two plan-text assertions in `core/src/test/.../translator/GremlinToMatchSmokeTest.java`:
`explainReflectsEdgeFilterTranslation` (L800) flips both of its assertions;
`declinedBothEdgeFilterExplainStaysNative` (L853) stays green for a new reason.

That cost is directly measurable on the probe fixture. `g.V().outE("link").has("since",
lt(2025)).inV()` returns 2 `[alpha, bravo]` on both arms today with `since` undeclared, and
`g.V().outE("link").has("w", lt(3.0)).inV()` returns 2 on both arms with `w` declared DOUBLE. The
rule as designed keeps the second and withdraws the first, though both are correct today.

**Stay green, gate relocates — 4 methods.** `NotStepRecogniserTest`'s
`notWithCrossTypeRangeComparison_declinesAndAgreesWithNative` (L320),
`notWithRangeComparisonBehindHop_declinesToNative` (L401), `notWithBetweenPredicate_declinesToNative`
(L417), and `EdgeTraversalEquivalenceTest.nonAdjacentBothEdgeFilter_declinesToNative` (L378), whose
`otherV` decline would be pre-empted by the range decline and stop witnessing what it was written
for.

**Unclear until a design choice is made — 11 methods.** `GremlinPredicateAdapterTest`'s direct
`toFilter` unit tests (L90-L383) assert exact SQL text against `NO_TYPE_INFO` and are
position-agnostic. They flip only if the match-nothing branch lands inside
`GremlinPredicateAdapter.toFilter` rather than in the recognisers. Deciding where the branch lives
decides whether this block is in scope.

**Cucumber — 24 scenarios lose translator coverage, no red expected.** The TinkerPop graphs declare
no property types, so every range predicate in an unfolded position falls to decline and the native
fallback answers. Clusters: `filter/Not.feature` L25/L38/L51, `filter/Or.feature` L25/L38,
`filter/And.feature` L25/L37, `filter/Filter.feature` L63/L73/L85, `map/Edge.feature` L288,
`filter/Has.feature` L228, `filter/HasKey.feature` and `filter/HasValue.feature`,
`sideEffect/Aggregate.feature` L267/L675/L720, `integrated/RepeatUnrollStrategy.feature` (6),
`integrated/SubgraphStrategy.feature` (6).

**Unaffected — 58 Java methods and 12 scenarios** are folded-root, 51 of the 58 being the
`core/src/test/.../tx/SnapshotIsolationIndexes*` suites, which compare a declared-INTEGER `age`
against Integer literals and are same-type on both counts.

## Sizing recommendation

**Do not proceed with step 15 as written. Re-plan it first, then split it.**

Three reasons, in order of weight.

**The reach is not where DR-S16 puts it.** The step is scoped as a translation ("make an unfolded
range comparison match nothing when the declared type is known and cross-type") with a decline as the
residual. Measured, it is the reverse: one shape translates, everything else declines. A step whose
main effect is a decline widening deserves to be planned as one, and weighed against what the decline
costs — ten green equivalence tests and twenty-four Cucumber scenarios, all of them currently
correct.

**The fold predicate is computable, but not from the surface shape or from `boundaryAlias`.** It
needs the `isTraversalStart` latch stated above, mirrored against `rebuildTraversal` including the
mid-traversal-`GraphStep` restart. That is a small piece of work and a real one, and it is the piece
that has to be right — every other decision in the step reads off it. It also wants the pinning test
DR-S16 already asks for, in both directions: one pinning the fold's answer so a later type-aware
`YTDBGraphStep` breaks loudly, and one pinning that `where`/`filter`/all-filter-`and` inline into the
fold, so a TinkerPop upgrade that changes `InlineFilterStrategy` breaks loudly too.

**The defect set is larger than the two defects named.** `or(...)` at the root, `or(...)` after a
hop, and post-hop `where(...)` all diverge at HEAD, and none is reachable from `NotStepRecogniser`,
where the current gate lives. Fixing only the two named defects leaves three measured wrong answers
in place; fixing all of them touches `OrStepRecogniser`, `WhereTraversalStepRecogniser` and probably
`ConnectiveStepSupport` as well.

**Files, if it proceeds as designed.** Production: `WalkerContext`, `RecognitionContext`,
`SubTraversalPredicateAdapter` (the accessor); `GremlinStepWalker` and `WalkerContext` again (the
fold latch); `HasStepRecogniser`, `EdgeHopRecogniser`, `GremlinPredicateAdapter` (the gate and the
match-nothing form); `NotStepRecogniser` (retire step 13's gate); `OrStepRecogniser`,
`WhereTraversalStepRecogniser`, `ConnectiveStepSupport` if the full defect set is in scope. Nine to
twelve files. Tests: `EdgeTraversalEquivalenceTest` (ten methods rewritten),
`GremlinToMatchSmokeTest`, `NotStepRecogniserTest` (three re-homed), `PredicateTraversalEquivalenceTest`
and a new fold-pinning class. Five files. That is two to three steps' worth, not one.

**A suggested split.** (1) The accessor plus the fold latch, with the two pinning tests and no
behaviour change — small, verifiable, and it settles the predicate every later decision reads. (2)
The match-nothing translation in the one position where the declared type resolves, which closes
`g.V().out().hasLabel(L).has(k, range)` and costs nothing. (3) The decline for unknown types, as its
own step, with the ten flipped equivalence tests and the Cucumber coverage loss as its declared
price — so that price is paid deliberately rather than discovered while implementing something else.

**One alternative worth pricing before step (3) is planned.** YouTrackDB SQL has a per-record type
accessor: `SQLMethodType` (`core/.../sql/method/misc/SQLMethodType.java`, `NAME = "type"`) returns
the stored value's type name. An unfolded range comparison could emit a per-record type guard
alongside the comparison and reproduce TinkerPop's rule without any static gate — which would reach
undeclared properties, keep the whole edge-filter surface translating, and remove the dependency on
class-context propagation entirely. It needs its own measurement: the exact category mapping against
`GremlinValueComparator`, and what the extra conjunct does to index selection. If it works, step (3)
disappears and with it most of the blast radius.
