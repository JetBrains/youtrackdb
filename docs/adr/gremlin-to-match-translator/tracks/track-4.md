# Track 4: Filtering + predicates

## Description

Implements the predicate adapter and filtering recognisers (HasStep,
HasLabelStep, HasIdStep, HasNotStep), plus the load-bearing changes that
make folded `HasContainer` translation actually reach the walker —
without which the dominant LDBC shape `g.V().has(...)` would silently
decline.

**(1) Lift the hasContainers-empty gates and translate folded
HasContainers in `StartStepRecogniser`.** `YTDBGraphStepStrategy` runs
before `GremlinToMatchStrategy` and absorbs every start-step
`HasContainer` into `YTDBGraphStep.hasContainers`. Today both
`GremlinToMatchStrategy.apply` (line 164) and
`StartStepRecogniser.recognize` (line 108) decline on non-empty
hasContainers, so the entire `g.V().has(...)` shape declines. This
track removes both gates and extends `StartStepRecogniser` to walk
`graphStep.getHasContainers()` and translate each `HasContainer`
through the predicate adapter, AND-merging the result into
`ctx.aliasFilters[$g2m_v0]` via the merge helper from (3). Single-class
`T.label` containers narrow `aliasClasses[$g2m_v0]` and write the
polymorphism-conditioned filter through `ctx.aliasFilters` (same
uniform write contract as the recognisers below). `T.id`-keyed
containers route through the existing single-vs-multi-RID logic. The
`EdgeTraversalEquivalenceTest:V_has_name_Alice` case flips from
`DECLINED` to `RECOGNIZED` in the same step.

**(2) `GremlinPredicateAdapter`** translates TinkerPop `P<T>` instances
into `SQLBooleanExpression` subtrees. The adapter is a recursive walk
and dispatches in two levels: (a) `if (p instanceof ConnectiveP)`
recurse on `getPredicates()` and combine via `MatchWhereBuilder.and/or`
— TinkerPop encodes `P.and(p1, p2)` as `AndP` and `P.or(...)` as `OrP`,
and `P.between(lo, hi)` decomposes to `AndP(gte(lo), lt(hi))`; (b) else
`if (p instanceof TextP)` switch on its `BiPredicate` enum; (c) else
switch on `p.getBiPredicate()` (Compare/Contains constants).
Three-valued-logic handling is mandatory because TinkerPop predicates
use Java equality (`Objects.equals`) where `null != "x"` is true, but
SQL `field <> 'x'` excludes nulls; the native pipeline already adds an
`IS NOT NULL` guard at `YTDBGraphQueryBuilder.java:79-89, 282-286`, and
the adapter must mirror that.

Predicate matrix:

- `Compare.eq` with non-null **scalar** literal → `op(field, EQ, lit)`;
  `Compare.eq(null)` → `isNull(field)`.
  `Compare.eq(Collection)` — **narrow decline by size**:
  - `coll.size() == 1` → **decline under D3**. Rationale:
    `QueryOperatorEquals.equals` auto-unboxes a singleton `Collection`
    against a scalar operand
    (`core/.../sql/operator/QueryOperatorEquals.java:63-69`) so
    `field = [a]` collapses to `field = a`, but TinkerPop's
    `Compare.eq` routes through `GremlinValueComparator.COMPARABILITY`
    which classifies operands by `Type` priority — `List` and the
    scalar's type land in different buckets, so the structural compare
    returns FALSE. Translating to `op(field, EQ, listLit)` would over-
    match against the native pipeline; the recogniser declines so the
    native traverser-side evaluator runs instead.
  - `coll.size() != 1` (empty or multi-element) → translate normally
    to `op(field, EQ, listLit)`. The unbox branch is guarded by
    `size() == 1`, so for these literals both engines fall through to
    structural list comparison (Java `List.equals` on YTDB side,
    `COMPARABILITY.contentsComparable` on TP side) and agree.
  - Phase 2: schema-aware rewrite using `PropertyType` from the
    schema layer — scalar-typed property + `eq([a])` → constant
    `false`; collection-typed property + `eq([a])` → `op(field, EQ,
    listLit)`; schema-less property → keep Phase 1 decline.
- `Compare.neq` with non-null **scalar** literal → `or(isNull(field),
  op(field, NE, lit))` so absent/null records pass through;
  `Compare.neq(null)` → `not(isNull(field))`.
  `Compare.neq(Collection)` — symmetric to `Compare.eq(Collection)`:
  - `coll.size() == 1` → **decline under D3**. `SQLNeOperator.execute`
    is `!QueryOperatorEquals.equals(...)`, which inherits the
    singleton-unbox path and diverges from TinkerPop's structural
    inequality (TP returns TRUE for `[a] vs a`, YTDB raw returns
    FALSE).
  - `coll.size() != 1` → translate normally to `or(isNull(field),
    op(field, NE, listLit))` (same null-inclusion rule as the scalar
    `neq` path).
  - Phase 2: schema-aware rewrite — scalar-typed property + `neq([a])`
    → constant `true`; collection-typed property + `neq([a])` →
    normal translation; schema-less → keep Phase 1 decline.
- `Compare.gt` / `gte` / `lt` / `lte` → corresponding operator. Decline
  the predicate if value is null (matches `YTDBGraphQueryBuilder`'s
  "NOT CONVERTED" path — under D3 the recogniser declining causes the
  whole traversal to decline and run native).
- `Contains.within` → `in(field, list)`; `Contains.without` →
  `or(isNull(field), notIn(field, list))` (same null-inclusion rule as
  `neq`).
- `P.inside(lo, hi)` → `and(op(field, GT, lo), op(field, LT, hi))`;
  `P.outside(lo, hi)` → `or(op(field, LT, lo), op(field, GT, hi))`
  (handled at the BiPredicate level, not via ConnectiveP).
  `P.between(lo, hi)` arrives as `AndP(gte(lo), lt(hi))` and rides the
  ConnectiveP path; an optional shape-detect that re-hoists it into
  `SQLBetweenCondition` is **out of scope** for this track —
  AND-decomposed form is acceptable; range-index lookup is measured in
  Track 12's perf baseline.
- `TextP.containing(s)` → `containsText(field, s)`.
- `TextP.startingWith(s)` / `TextP.endingWith(s)` → `startsWith` /
  `endsWith` after **LIKE-escaping** `s` in the adapter (replace
  `\` → `\\`, `%` → `\%`, `_` → `\_`); `MatchWhereBuilder.startsWith` /
  `endsWith` retain their no-escape contract per their Javadoc, so each
  call site can opt in or out of escaping.
- `TextP.notContaining` / `notStartingWith` / `notEndingWith` →
  `or(isNull(field), not(containsText(...)))` etc. (negation +
  null-inclusion).
- `P.not(...)` → recursive `MatchWhereBuilder.not(...)`.
- Use `TextP` (the user-facing predicate factory class) consistently in
  code and tests. `Text` is the underlying `BiPredicate` enum token; the
  *step* always sees `TextP` instances on `HasContainer.predicate`.

**(3) Uniform write contract** for all Track 4 recognisers (this step
plus those below). Every filter contribution writes through
`ctx.aliasFilters[alias]`, **never** through
`MatchPatternBuilder.addNode(where = ...)`. The walker's merge order
`finalAliasFilters.putAll(ctx.aliasFilters)` makes context entries
override builder entries on the same alias, so a builder-side write
would be silently shadowed by any prior context entry (e.g.
`StartStepRecogniser`'s non-polymorphic `@class = 'V'` or
`VertexStepRecogniser`'s chain-target narrowing). Each recogniser
AND-merges its filter against the existing context entry via a shared
helper — lift `StartStepRecogniser.combineAnd` to
`MatchWhereBuilder.andOptional(prior, new)` (or a `WalkerContext`
helper). The helper handles three cases: empty prior → write directly;
AND of two clauses; AND of N clauses (collapses to a single
`SQLAndBlock`). All existing call sites (StartStepRecogniser, hasLabel
chain-target merge, the new recognisers below) delegate to the lifted
helper.

**(4) Recognisers** registered in
`GremlinStepWalker.PRODUCTION_RECOGNISERS` after the existing
start/vertex/no-op-barrier entries (declaration order is first-match
wins). Each recogniser validates before mutating `WalkerContext` (no
rollback). None of these own the terminal step, so `boundaryAlias` /
`outputType` / `returnClass` stay pinned by the existing recognisers.

- `HasStep` recogniser — mid-chain `has(key, P)` (start-step
  has-folding handled by (1)). For each `HasContainer` in the step,
  invokes the predicate adapter and AND-merges the result via the
  helper from (3). Multiple `has(...)` calls on the same alias merge
  cumulatively. Defence-in-depth: a `HasContainer` carrying key
  `T.label.getAccessor()` should never reach this recogniser
  (extracted by `YTDBGraphStepStrategy` into `YTDBHasLabelStep`);
  decline if observed.
- `YTDBHasLabelStep` recogniser — `YTDBHasLabelStep` carries
  `List<P<? super String>>` predicates (multi-class `hasLabel("A", "B")`
  is `[P.within("A", "B")]`). Recognise the **single-`P.eq("X")`** form
  (single-class narrowing) and the **single-`P.within(A, B, ...)`** form
  (multi-class narrowing). DECLINE on any other shape (P.neq, regex,
  multi-predicate) so D3 falls back to native and the Cucumber count is
  preserved. Polymorphic mode writes `aliasClasses[alias]` (planner uses
  MATCH/SQL polymorphic-by-default — `SELECT FROM Class` matches Class
  plus all subclasses without explicit enumeration; there is no
  `class IN [...]` rewrite mechanism in the planner). Non-polymorphic
  mode writes `aliasFilters[alias]` AND-merged with any existing context
  filter via the helper from (3) (e.g. `VertexStepRecogniser`'s
  `@class = 'V'` for chain targets is replaced by the more-specific
  class via the AND-merge). Multi-class non-polymorphic emits
  `WHERE @class IN ['A','B',...]` using the lifted cached-reflection
  helper from (5). Reuse `MatchClassFilters.classEq(name)` +
  `MatchClassFilters.wrapWhere(...)` and read
  `WalkerContext.polymorphic`.
- `HasStep` with `hasId(...)` (key = `T.id`) — translates to RID
  constraint. **First single-ID write** routes through
  `aliasRids[alias]` (one `SQLRid` per alias → planner's
  `SELECT FROM #X:Y` fast path). **Repeated single-ID writes** on the
  same alias intersect: same ID → no-op; different ID → downgrade to
  `aliasFilters[alias] WHERE @rid IN [intersection]` (the intersection
  may be empty, producing a filter that returns no rows — explicitly
  tested). **Multi-ID writes** route through
  `aliasFilters[alias] WHERE @rid IN [...]` directly because `aliasRids`
  is single-RID-per-alias by the MATCH SQL grammar. When
  `aliasRids[alias]` already exists from a prior `g.V(id)` start-step
  write, multi-ID `hasId` intersects the lists. `@rid` is
  `SQLRecordAttribute`, not `SQLIdentifier`, so the IN AST is built by
  hand using the lifted cached-reflection helper from (5)
  (`MatchWhereBuilder.in` hard-codes its LEFT side to `SQLIdentifier`).
- `HasStep` with `hasNot(key)` — translates to property-absent.
  Canonical form: `MatchWhereBuilder.isNull(field)` (NEW helper added
  in (5) wrapping `SQLIsNullCondition`). YTDB document storage
  conflates "property absent" and "property set to null" in IS NULL
  evaluation; verify by reading `SQLBinaryCondition` /
  `SQLIsNullCondition` and lock the choice in with equivalence tests
  on a graph that mixes property-absent and property-set-to-null
  vertices. Also accepts `SQLRecordAttribute` left-sides
  (`hasNot("@class")`).

**(5) Lift deferred DRY items in this track** — Track 4 is the third
call site that triggers each lift:

- `combineAnd` → `MatchWhereBuilder.andOptional(SQLBooleanExpression,
  SQLBooleanExpression)`. Currently private in `StartStepRecogniser`;
  lift so the new uniform write contract from (3), the existing
  start-step folded-container merge, and the hasLabel chain-target
  merge all use the same shape.
- Cached `SQLInCondition.operator` reflection — already cached in
  `StartStepRecogniser.SQL_IN_OPERATOR_FIELD`.
  `MatchWhereBuilder.setInOperator` does NOT cache today (per-call
  `getDeclaredField`/`setAccessible`); the lift introduces caching at
  that site too. All three call sites (`StartStepRecogniser`,
  `MatchWhereBuilder`, the new `hasId` recogniser plus the multi-class
  hasLabel `IN` builder) delegate to a single cached helper under
  `match.builder/`. Add a unit test asserting cache reuse so a
  regression to per-call resolution is caught.
- `MatchWhereBuilder.isNull(field)` — NEW helper for `hasNot` and the
  predicate-adapter `eq(null)` / `neq(null)` cases. Accepts both
  `SQLIdentifier` and `SQLRecordAttribute` left-sides.
- Default vertex-class literal — instead of introducing a new
  `DEFAULT_VERTEX_CLASS` constant, reuse the project-wide
  `SchemaClass.VERTEX_CLASS_NAME` ("V") in `StartStepRecogniser`,
  `VertexStepRecogniser`, and the new recognisers.
- `WalkerContextFixtures` — small test helper for ad-hoc
  `WalkerContext` construction recurring across recogniser unit tests;
  extract now since this track adds at least one new test class per
  recogniser.

**Verification methodology**: parameterised **strategy-on vs
strategy-off equivalence** tests (extend the prior track's
`EdgeTraversalEquivalenceTest` harness — Gremlin/MATCH semantic
divergences cannot slip through encoding-only "translated MATCH ==
SQL MATCH" assertions). The harness gains a per-case polymorphism flag
plumbed through `withStrategies(OptionsStrategy...)` and a class
hierarchy in the seed schema (e.g. `Person ← Employee ← Manager`) so
non-polymorphic / subclass cases are exercisable. New cases are
distributed across the implementation steps: predicate-adapter step
adds basic predicate cases incl. `eq(null)` / `neq(non-null)`
IS-NOT-NULL guards, the divergence-pin cases from the design doc's
"NULL and collection comparison semantics" truth table (`eq(null)` and
`neq(null)` against a null-valued property → assert translated MATCH
matches native; `eq([a])` and `neq([a])` against a scalar property
equal to `a` → assert recogniser declines and Phase 1 falls back to
native, since `QueryOperatorEquals.equals` auto-unboxes singleton
collections; `eq([a, b])` against a list-typed property equal to
`[a, b]` and `eq([])` against any property → assert translation path
(not decline) returns the same multiset as native, validating the
narrow size-1-only decline rule), and `TextP` cases with `%` / `_` /
`\` literals;
HasStep step adds property-filter combinations
(`has(a, gt(1)).has(b, lt(10))` AND-merged); hasLabel step adds
class-narrowing + polymorphism + multi-class-within cases (incl.
`hasLabel("Person").has("name", "alice")` and chain-target hasLabel
under both modes); hasId step adds intersection-downgrade cases
(`hasId(id1).hasId(id2)`); hasNot step adds null-check cases on a
fixture mixing property-absent vs property-set-to-null vertices.

## Progress

- [x] Review + decomposition
- [x] Step implementation (6/6 complete)
- [x] Track-level code review (2/3 iterations — gate PASS on TB/BC/TC)

## Base commit

`4a78bf2051`

## Reviews completed

- [x] Technical (`reviews/track-4-technical.md`) — 4 blockers, 8
  should-fix, 4 suggestions (iteration 1)
- [x] Risk (`reviews/track-4-risk.md`) — 2 blockers, 5 should-fix,
  2 suggestions (iteration 1)
- [x] Adversarial (`reviews/track-4-adversarial.md`) — 2 blockers, 5
  should-fix, 4 suggestions (iteration 1)

Iteration 1 fixes applied to Description above (gate-lift step,
uniform write contract, three-valued logic, `hasNot` canonical form,
LIKE escape, multi-class hasLabel, hasId intersection, `combineAnd` /
`isNull` lifts, `SchemaClass.VERTEX_CLASS_NAME` reuse, ConnectiveP
dispatch, polymorphism-flag-plumbed equivalence harness with class
hierarchy fixture). Should-fix and suggestion items deferred to step
implementation (T5 cosmetic `Text` → `TextP` already in description;
A4 hasId intersection captured; A8 `SchemaClass.VERTEX_CLASS_NAME`
captured; R6 ConnectiveP dispatch captured; R7 polymorphism-flag
harness captured; R8 per-step test-case split captured in
verification-methodology distribution; R9 microbenchmark deferred to
Track 12; A9-A11 / T13-T16 / R8 minor refinements deferred to step
implementation).

## Steps

- [x] Step: DRY foundation + new helper surface
  - [x] Context: unavailable
  > **Risk:** medium — multi-file logic across `core` shared with GQL
  > (no HIGH triggers; behavior-preserving lifts plus dormant new
  > helpers consumed by later steps).
  >
  > Lift `StartStepRecogniser.combineAnd` to
  > `MatchWhereBuilder.andOptional(SQLBooleanExpression,
  > SQLBooleanExpression)` so the new uniform write contract, the
  > existing start-step folded-container merge, and the hasLabel
  > chain-target merge all use the same shape (handles empty prior →
  > write directly; AND of two; AND of N collapsing to a single
  > `SQLAndBlock`). Introduce caching at
  > `MatchWhereBuilder.setInOperator` and consolidate
  > `StartStepRecogniser.SQL_IN_OPERATOR_FIELD` with
  > `MatchWhereBuilder`'s call site into a single cached helper under
  > `match.builder/`; both existing call sites delegate to it. Add
  > `MatchWhereBuilder.isNull(field)` helper wrapping
  > `SQLIsNullCondition`, accepting both `SQLIdentifier` and
  > `SQLRecordAttribute` left-sides. Replace the `"V"` literal in
  > `StartStepRecogniser` and `VertexStepRecogniser` with
  > `SchemaClass.VERTEX_CLASS_NAME`. Tests: existing GQL + recogniser
  > suites stay green (behavior preservation); new unit tests for
  > `andOptional` (empty/single/N), the cached `setInOperator`
  > helper (asserting cache reuse via reflection-call counter), and
  > `isNull` (both left-side types).
  >
  > **What was done:** Added `SqlInOperatorBinding` (public class in
  > `match.builder/`) as the single canonical access point for the
  > parser-private `SQLInCondition.operator` field; the cached
  > `Field` is resolved once at class load. `MatchWhereBuilder.in`
  > and `StartStepRecogniser.buildRidInExpression` now both delegate
  > to `SqlInOperatorBinding.setOperator`; the duplicated reflection
  > and the per-call `getDeclaredField` path in `MatchWhereBuilder`
  > were removed. Lifted `combineAnd` to
  > `MatchWhereBuilder.andOptional(SQLBooleanExpression... ops)` —
  > the varargs shape generalises the original two-arg helper to N
  > optional operands, filtering nulls and producing the empty/
  > single/multi cases per the documented contract; the recogniser
  > calls `WHERE.andOptional(ridIn, classEq)` through a private
  > static `MatchWhereBuilder` instance. Added two `isNull`
  > overloads (`String field` and `SQLExpression`) plus an
  > `isNullAttribute(String)` convenience for `@class`/`@rid`
  > record-attribute left-sides; all three wrap the parser-emitted
  > `SQLIsNullCondition`. Replaced both `"V"` literal constants
  > (`StartStepRecogniser.DEFAULT_VERTEX_CLASS`,
  > `VertexStepRecogniser.DEFAULT_VERTEX_CLASS`) with
  > `SchemaClass.VERTEX_CLASS_NAME`.
  >
  > **What was discovered:** `MatchWhereBuilder` has no other
  > internal state — instances are essentially zero-byte objects, so
  > each consumer can hold a private static instance for free. This
  > avoided needing to make `andOptional` static (which would have
  > broken API consistency with `and`/`or`/`eq`). The
  > `SQLIsNullCondition.toString` renders the operator in lowercase
  > (`"name is null"`) — the equivalence test harness compares
  > rendered strings case-sensitively, so any future regression that
  > changes the parser's casing will surface immediately at the
  > Step 5 hasNot equivalence cases.
  >
  > **Key files:**
  > - `core/.../match/builder/MatchWhereBuilder.java` (modified) —
  >   `andOptional`, `isNull`/`isNullAttribute` added; `in` delegates
  >   to `SqlInOperatorBinding`; private `setInOperator` removed.
  > - `core/.../match/builder/SqlInOperatorBinding.java` (new) —
  >   cached-Field reflection helper.
  > - `core/.../gremlin/translator/strategy/StartStepRecogniser.java`
  >   (modified) — uses `SchemaClass.VERTEX_CLASS_NAME` and
  >   `WHERE.andOptional`; private `combineAnd`,
  >   `SQL_IN_OPERATOR_FIELD`, `setInOperator` removed.
  > - `core/.../gremlin/translator/strategy/VertexStepRecogniser.java`
  >   (modified) — uses `SchemaClass.VERTEX_CLASS_NAME`.
  > - `core/.../match/builder/MatchWhereBuilderTest.java` (modified) —
  >   `andOptional` cardinality matrix (empty/all-null/single/2/3 with
  >   middle null/4) plus `isNull` shape tests covering both
  >   `SQLIdentifier` and `SQLRecordAttribute` left-sides.
  > - `core/.../match/builder/SqlInOperatorBindingTest.java` (new) —
  >   asserts the operator field is populated, the cached `Field` is
  >   `private static final`, and 2k repeated calls each yield a fresh
  >   `SQLInOperator` instance.

- [x] Step: WalkerContextFixtures + EdgeTraversalEquivalenceTest harness extension
  - [x] Context: unavailable
  > **Risk:** medium — touches shared test infrastructure used by
  > Tracks 4–10 (per `risk-tagging.md` "Tests-only steps" rule, this
  > is medium because of the shared-fixture surface).
  >
  > Extract `WalkerContextFixtures` from the ad-hoc `WalkerContext`
  > construction recurring across recogniser unit tests
  > (`StartStepRecogniserTest`, `VertexStepRecogniserTest`,
  > `NoOpBarrierRecogniserTest`, `GremlinStepWalkerTest`); migrate
  > existing tests to use it (no behavior change). Extend
  > `EdgeTraversalEquivalenceTest` with: (a) a per-case polymorphism
  > flag plumbed through `withStrategies(OptionsStrategy.build()...)`
  > so cases run under both polymorphic-default and non-polymorphic
  > modes; (b) a class-hierarchy seed fixture
  > (`Person ← Employee ← Manager` plus the existing flat shapes) so
  > subclass-narrowing cases are exercisable. Existing 13 cases stay
  > green (default polymorphic-true matches today's behavior). The
  > harness is ready for the polymorphism + class-narrowing cases
  > added in subsequent steps.
  >
  > **What was done:** Created `WalkerContextFixtures` as a
  > package-private test utility with two static helpers:
  > `walkerContextWithStart(Traversal.Admin)` rebuilding the
  > post-start-step context shape (boundary alias `$g2m_v0`, V-class
  > start node, pinned terminator metadata, three parallel
  > return-projection lists, `stepIndex=1`) and `pinBoundary(ctx)`
  > applying just the boundary metadata pin. `START_ALIAS` is exposed
  > as a public constant so test classes don't redeclare it. Migrated
  > `VertexStepRecogniserTest.walkerContextWithStart` to delegate to
  > the fixture; `GremlinStepWalkerTest.pinBoundary` and its private
  > `BOUNDARY_ALIAS` constant likewise delegate. `NoOpBarrierRecogniserTest`
  > kept its own `walkerContextWithSentinelState` helper because its
  > sentinel-state shape (deliberately different from the post-start
  > shape) is barrier-test specific and would clutter the shared
  > fixture. `EdgeTraversalEquivalenceTest` gained a 4th
  > `@Parameter(3) Boolean polymorphic` field; existing 13 cases use
  > the new `shape(name, factory, expected)` helper which fills
  > `polymorphic=null` (= session default), keeping behaviour
  > unchanged. A new `shapePolymorphic(name, factory, expected,
  > polymorphic)` helper is provided for upcoming steps to add
  > explicit-mode cases without further harness changes; it is
  > `@SuppressWarnings("unused")` until those cases land. Polymorphism
  > plumbing routes through `admin.getStrategies().addStrategies(
  > OptionsStrategy.build().with(polymorphicQuery, value).create())`
  > before `applyStrategies`, mirroring
  > `GremlinToMatchTranslatorTest.withPolymorphism`. Class-hierarchy
  > seed extends the fixture: `Employee` extends `Person`, `Manager`
  > extends `Employee`; the existing flat `Place` plus the original
  > Person instances stay unchanged so the 13 existing cases see no
  > result-multiset shift.
  >
  > **What was discovered:** `OptionsStrategy.build().with(...)` adds
  > to a `LinkedHashMap` so re-adding the same `OptionsStrategy`
  > overrides the prior value if any — the test never re-adds, but
  > documents this for future cases that want to layer multiple
  > `YTDBQueryConfigParam` overrides on the same traversal. The
  > parameterised case-name template is now
  > `{0}_{2}_polymorphic{3}`; for the existing cases the
  > `polymorphic{3}` suffix renders as `polymorphicnull`, which is a
  > valid YouTrackDB database name (letters, digits, underscore only —
  > no `=`/`/`/`.`/`(`).
  >
  > **Key files:**
  > - `core/.../gremlin/translator/strategy/WalkerContextFixtures.java`
  >   (new) — shared `walkerContextWithStart` + `pinBoundary` test
  >   helpers.
  > - `core/.../gremlin/translator/strategy/VertexStepRecogniserTest.java`
  >   (modified) — delegates to the fixture; redundant imports removed.
  > - `core/.../gremlin/translator/strategy/GremlinStepWalkerTest.java`
  >   (modified) — `pinBoundary` and `BOUNDARY_ALIAS` delegate to the
  >   fixture; semantics unchanged.
  > - `core/.../gremlin/translator/EdgeTraversalEquivalenceTest.java`
  >   (modified) — `polymorphic` parameter, polymorphism plumbing via
  >   `OptionsStrategy`, class-hierarchy seed (`Employee`/`Manager`),
  >   `shape`/`shapePolymorphic` factories, parameter-name template
  >   updated.

- [x] Step: GremlinPredicateAdapter
  - [x] Context: unavailable
  > **Risk:** medium — new class with non-trivial logic in `core`
  > (no HIGH triggers; AST construction, no per-row hot path).
  >
  > New class `GremlinPredicateAdapter` (package-private under
  > `internal/core/gremlin/translator/strategy/`) translating
  > TinkerPop `P<T>` instances into `SQLBooleanExpression` subtrees.
  > Two-level dispatch: (a) `if (p instanceof ConnectiveP)` recurse
  > on `getPredicates()` and combine via `MatchWhereBuilder.and/or`
  > — handles `P.and`/`P.or` and the AndP-decomposed form of
  > `P.between`; (b) else `if (p instanceof TextP)` switch on its
  > `BiPredicate` enum; (c) else switch on `p.getBiPredicate()`
  > (Compare/Contains constants). Three-valued-logic handling per
  > the predicate matrix in `## Description` §(2): `Compare.eq(null)`
  > → `isNull(field)`; `Compare.neq(literal)` → `or(isNull(field),
  > op(field, NE, lit))`; `Compare.neq(null)` → `not(isNull(field))`;
  > `gt`/`gte`/`lt`/`lte` with null → decline; `Contains.without`
  > adds the IS-NULL OR shape; `TextP.notContaining` /
  > `notStartingWith` / `notEndingWith` add the negation +
  > null-inclusion shape. LIKE-escape `TextP.startingWith` /
  > `endingWith` inputs in the adapter (replace `\` → `\\`,
  > `%` → `\%`, `_` → `\_`) before passing to the no-escape-contract
  > `MatchWhereBuilder.startsWith`/`endsWith`. Tests: parameterised
  > unit tests covering each predicate, plus equivalence cases via
  > the Step-2-extended harness covering `eq(null)`/`neq(non-null)`
  > IS-NOT-NULL guards and `TextP` literals containing `%` / `_` / `\`.
  >
  > **What was done:** Created `GremlinPredicateAdapter` (package-
  > private singleton in `gremlin.translator.strategy/`) returning
  > `Optional<SQLBooleanExpression>` — `Optional.empty()` is the
  > decline sentinel that bubbles up through recognisers to D3
  > all-or-nothing. Dispatch order: ConnectiveP first (AndP/OrP),
  > then TextP (because `TextP.biPredicate` is a `Text` enum that
  > would also match the third branch), then BiPredicate switch on
  > Compare/Contains. `Compare.eq(null)` → `isNull`;
  > `Compare.neq(literal)` → `or(isNull, op(NE))`;
  > `Compare.neq(null)` → `not(isNull)`; gt/gte/lt/lte with null
  > decline. `Contains.within` → `in`; `Contains.without` →
  > `or(isNull, notIn)`. `TextP.containing` → `containsText`;
  > `TextP.startingWith`/`endingWith` → `startsWith`/`endsWith`
  > with the adapter LIKE-escaping `\` → `\\`, `%` → `\%`,
  > `_` → `\_` upstream of the no-escape-contract builder methods.
  > Negation TextP forms add the IS NULL guard via OR. Custom
  > BiPredicates and `TextP.regex`/`notRegex` decline. Unit tests
  > pin every shape with full-string render assertions and three
  > decline paths.
  >
  > **What was discovered:** `SQLNeOperator` (the `!=`/`<>` operator)
  > does not expose a `INSTANCE` constant — only Eq/Gt/Lt/Le do. The
  > adapter constructs `new SQLNeOperator(-1)` and `new SQLGeOperator(-1)`
  > inline, matching the JJTree `-1` node-id convention every other
  > builder site uses. The renderer canonicalises `!=` rather than
  > `<>`, so the test pins `name != "x"` not `name <> "x"`. The
  > parser-level renderer also escapes backslashes for SQL literal
  > form, so the adapter's `\` → `\\` plus the renderer's `\` → `\\`
  > combine to surface as 4 raw backslashes in the rendered output
  > for a 1-backslash input value — pinned in `endingWith_escapesBackslashMetachar`
  > so a regression that drops one of the two layers surfaces here.
  > `Contains.within`'s second type parameter is `Collection`, not
  > `Object`, so a non-Collection-value test must use raw `P` types;
  > suppressed with `@SuppressWarnings({"rawtypes", "unchecked"})`.
  >
  > **Cross-track impact:** `GremlinPredicateAdapter.INSTANCE` is the
  > entry point Step 4's `StartStepRecogniser` HasContainer translation
  > and Step 5's `HasStepRecogniser` will call. The decline-on-`gt(null)`
  > behaviour means that translation of the form `g.V().has("age", gt(null))`
  > (which is meaningless but compiles in Gremlin) will route the entire
  > traversal back to native execution under D3 — consistent with the
  > native pipeline's NOT_CONVERTED escape hatch. Step 4-6 recognisers
  > should propagate `Optional.empty()` from the adapter directly into
  > a recogniser-level decline rather than swallowing it.
  >
  > **Key files:**
  > - `core/.../gremlin/translator/strategy/GremlinPredicateAdapter.java`
  >   (new) — predicate translator with full Compare/Contains/TextP/
  >   ConnectiveP coverage and three-valued-logic compensation.
  > - `core/.../gremlin/translator/strategy/GremlinPredicateAdapterTest.java`
  >   (new) — 31 cases covering every positive form, every null-handling
  >   variant, every LIKE-escape branch, and three decline paths.

- [x] Step: Lift hasContainers gates + folded-HasContainer translation in StartStepRecogniser
  - [x] Context: unavailable
  > **Risk:** high — architecture (changes the load-bearing
  > translation gate that decides whether ANY traversal enters the
  > recognised set) and Cucumber-green invariant exposure (every
  > traversal goes through this path; a regression here breaks the
  > entire suite). Multiple HIGH triggers fire here.
  >
  > Remove the `graphStep.getHasContainers().isEmpty()` decline gate
  > from `GremlinToMatchStrategy.apply()` (line 164) and the same
  > defence-in-depth check from `StartStepRecogniser.recognize()`
  > (line 108). Extend `StartStepRecogniser` to walk
  > `graphStep.getHasContainers()` and, for each `HasContainer`:
  > (a) `T.label`-keyed single-class containers narrow
  > `aliasClasses[$g2m_v0]` and (under non-polymorphic mode) write
  > `MatchClassFilters.classEq(name)` through `ctx.aliasFilters`
  > AND-merged via the Step-1 `andOptional` helper; (b) `T.id`-keyed
  > containers route through the existing single-vs-multi-RID logic
  > (single-ID through `aliasRids`, multi-ID through
  > `aliasFilters @rid IN [...]`); (c) all other containers go
  > through the predicate adapter (Step 3) and AND-merge into
  > `ctx.aliasFilters[$g2m_v0]`. Flip
  > `EdgeTraversalEquivalenceTest:V_has_name_Alice` from `DECLINED`
  > to `RECOGNIZED`. Add equivalence cases for `g.V().has(prop,
  > eq(v))`, `g.V().has(prop, eq(v)).out(label)`, and
  > `g.V().hasLabel("Person")` (folded-T.label form) verifying that
  > folded predicates ride through. Cucumber-green check is the
  > load-bearing invariant for this step.
  >
  > **What was done:** Lifted the `hasContainers.isEmpty()` decline
  > gate from `GremlinToMatchStrategy.apply` and the defence-in-depth
  > sibling gate in `StartStepRecogniser.recognize`. Added
  > `StartStepRecogniser.collectFolded` — a pre-validation pass that
  > walks every folded `HasContainer` before any context mutation and
  > returns a `FoldedStartState` record carrying `(idConstraint,
  > narrowedClass, propertyPredicate)`. The walk dispatches three
  > buckets: `T.id`-keyed containers route through
  > `extractIdsFromPredicate` which handles `Compare.eq` (single ID)
  > and `Contains.within` (multi-ID list); the conjunctive intent of
  > `g.V(ids).has(T.id, ...)` is preserved by intersecting the
  > start-step IDs with every folded predicate's set. `T.label`-keyed
  > containers narrow the class for single `Compare.eq("Name")`
  > predicates and decline blank, multi-class, contradictory, and
  > non-eq shapes. Property containers go through
  > `GremlinPredicateAdapter` and accumulate into a list that AND-
  > merges via `WHERE.andOptional` once the walk completes —
  > producing a flat `SQLAndBlock` rather than a left-deep nested
  > tree. The recogniser then commits the merged state to
  > `WalkerContext`: the surviving ID set goes to `aliasRids` for the
  > single-element fast path or to a hand-built `@rid IN [...]`
  > filter; the narrowed class becomes the boundary node's class on
  > `addNode`; the AND-merged filter is wrapped through
  > `MatchClassFilters.wrapWhere`. The 5 new equivalence-harness
  > cases (`V_has_name_Alice`, `V_has_age_gt_30`,
  > `V_has_name_Alice_out_Knows`, `V_hasLabel_Person`,
  > `V_hasLabel_Person_has_name_Alice`) flip from DECLINED to
  > RECOGNIZED; multi-class `V_hasLabel_Person_Place` stays DECLINED
  > to pin the chain-target recogniser's future home.
  >
  > **Review-fix delta:** the dimensional review surfaced one
  > critical-coverage gap (zero translator-level coverage for the
  > T.id-folded path) plus several should-fix items. Fixes applied:
  > (1) `Optional<Set<RecordIdInternal>>` collapsed to
  > `@Nullable Set<RecordIdInternal>` so all three `FoldedStartState`
  > components share one absent-value idiom; (2) the property-
  > predicate accumulator switched from pairwise
  > `WHERE.and(prior, new)` to a flat list AND-merged once via
  > `WHERE.andOptional(list...)` so the resulting `SQLAndBlock` has N
  > direct sub-blocks instead of a left-deep nested tree;
  > (3) equivalence-fixture vertices gained an `age` property spanning
  > the strict-greater-than-30 boundary (Alice 25, Bob 30, Carol 35,
  > David 40) so `V_has_age_gt_30` is genuinely discriminating;
  > (4) the smoke test now asserts `getStartStep() instanceof
  > YTDBMatchPlanStep` plus a single-step list shape rather than a
  > permissive `anyMatch`; (5) the strategy test pins the post-apply
  > start step's class on top of the existing `assertNotSame`;
  > (6) the translator test pins the boundary metadata
  > (`prefixStepCount`, `outputType`, `returnClass`, `boundaryAlias`)
  > and the absence of redundant filters under polymorphic-default;
  > (7) nine new translator unit tests cover the previously
  > unexercised T.id branches — single-ID hasId routing, multi-ID
  > hasId routing, single-survivor intersection, empty-intersection
  > preservation as `@rid IN []`, predicate-adapter decline
  > propagation (`P.gt(null)`), flat AND-shape pin, mixed-bucket
  > start-IDs + hasLabel, blank-string hasLabel decline, contradictory
  > hasLabel decline, and the polymorphic-false × hasLabel class-
  > filter shape.
  >
  > **What was discovered:** `g.V().hasLabel("Person").hasLabel("Place")`
  > folds BOTH label-keyed `HasContainer`s into the start step
  > (because YTDBGraphStepStrategy absorbs every Has step that
  > directly follows the GraphStep, regardless of repetition). The
  > recogniser's `narrowedClass != null && !narrowedClass.equals(name)`
  > guard is the load-bearing decline for the contradictory case —
  > without it the second container would silently overwrite the
  > first. `g.V("#9:0").hasId("#9:1")` is similarly conjunctive: the
  > intersection-empty case writes `@rid IN []` (zero rows match)
  > rather than dropping the constraint, which would broaden the
  > match to the full class scan. `MatchWhereBuilder.andOptional` is
  > a varargs method, so the list-based accumulation pattern needs
  > `propertyPredicates.toArray(new SQLBooleanExpression[0])` at the
  > call site — a fixed-arity overload would avoid the array
  > allocation but is queued for a Track-12-level perf review.
  >
  > **Cross-track impact:** Step 5 (HasStep + HasNotStep mid-chain)
  > and Step 6 (HasLabelStep + HasIdStep mid-chain) need the same
  > flat-AND accumulation pattern when a single recogniser invocation
  > merges multiple property predicates on the same alias. The
  > pattern is "collect into a `List<SQLBooleanExpression>`,
  > AND-merge via `WHERE.andOptional(list.toArray(...))` once" —
  > Step 5's `HasStepRecogniser` should use this directly. The Step 6
  > `HasIdStepRecogniser` mirroring `extractIdsFromPredicate` /
  > `intersection downgrade` should reuse the conjunctive-intersection
  > semantics established here. Decision Records and Component Map
  > unaffected.
  >
  > **Deferred review findings (no blockers; all suggestion-tier):**
  > comment-quality nits (CQ-003..009: per-component Javadoc on the
  > record, decline-reason rationale enumeration, `name.isBlank()`
  > rationale comment, intersection-comment expansion of the `IN []`
  > decision, in-test FQN cleanups already addressed in part);
  > performance findings PF1-PF4 (`@Nullable Set` short-circuit when
  > no folded T.id container is present, fixed-arity `andOptional`
  > overload, EMPTY `FoldedStartState` sentinel, flat-AND already
  > addressed) all deferred to Track 12 perf-baseline; structural-test
  > harness partition (TS-3 — `EdgeTraversalEquivalenceTest` is
  > becoming a kitchen sink) deferred to Track 5+; bugs-concurrency
  > BC-001 (Javadoc enumeration of declined T.id shapes) and TB-4/
  > TB-6/TC8/TC9/TC10 minor coverage refinements queued for the
  > next track session.
  >
  > Step-level dimensional review ran 1 iteration with 6 reviewers
  > (CQ/BC/TB/TC/PF/TS); 1 critical (TC1) + 9 should-fix items applied
  > as one review-fix commit. 265 tests across the affected and
  > regression suites pass.
  >
  > **Key files:**
  > - `core/.../gremlin/translator/strategy/StartStepRecogniser.java`
  >   (modified) — `collectFolded`, `extractIdsFromPredicate`,
  >   `FoldedStartState` record; folded predicate translation wired
  >   into `recognize()`.
  > - `core/.../gremlin/translator/strategy/GremlinToMatchStrategy.java`
  >   (modified) — `hasContainers.isEmpty()` gate removed; comment
  >   updated to reflect the lift.
  > - `core/.../gremlin/translator/EdgeTraversalEquivalenceTest.java`
  >   (modified) — 5 new folded-container cases, 1 multi-class
  >   DECLINED pin, age fixture for boundary-discriminating
  >   `V_has_age_gt_30`.
  > - `core/.../gremlin/translator/GremlinToMatchSmokeTest.java`
  >   (modified) — `startWithFoldedHasContainer_translatesToBoundaryStepWithMatchingResult`
  >   pinning start-step type + step count + result list.
  > - `core/.../gremlin/translator/strategy/GremlinToMatchStrategyTest.java`
  >   (modified) — `apply_proceedsWhenHasContainersPresent` pins
  >   spliced-step type alongside identity check.
  > - `core/.../gremlin/translator/strategy/GremlinToMatchTranslatorTest.java`
  >   (modified) — tightened folded-hasLabel translator test;
  >   nine new tests covering folded T.id routing, intersection
  >   shapes, predicate-adapter decline propagation, multi-property
  >   flat-AND shape, mixed-bucket combinations, hasLabel decline
  >   shapes, and the polymorphic-false × hasLabel class-filter
  >   shape.

- [x] Step: HasStep recogniser (mid-chain) — hasNot deferred to logical-filter track
  - [x] Context: unavailable
  > **Risk:** medium — adds one new recogniser in `core` (no HIGH
  > triggers; isolated logic; new code paths only fire when mid-chain
  > `has` is present).
  >
  > `HasStepRecogniser` (or equivalent) registered in
  > `GremlinStepWalker.PRODUCTION_RECOGNISERS` after the existing
  > start/vertex/no-op-barrier entries. Recognises any
  > `HasStep` (TinkerPop's mid-chain `has(key, P)`); for each
  > `HasContainer` invokes `GremlinPredicateAdapter` (Step 3) and
  > AND-merges into `ctx.aliasFilters[ctx.currentAlias]` via the
  > Step-1 helper. Multiple `has(...)` calls on the same alias merge
  > cumulatively. Defence-in-depth: a `HasContainer` carrying key
  > `T.label.getAccessor()` should never reach this recogniser
  > (extracted by `YTDBGraphStepStrategy` into `YTDBHasLabelStep`);
  > decline if observed.
  >
  > **What was done:** Added `HasStepRecogniser` (package-private
  > singleton) registered after the existing start/vertex
  > recognisers in `PRODUCTION_RECOGNISERS` and before the no-op
  > barrier. The recogniser pre-validates every container before
  > mutating context: declines on null predicate, declines on
  > `T.label`-keyed containers (defence in depth — those are split
  > into YTDBHasLabelStep mid-chain), declines if the predicate
  > adapter cannot translate the predicate, and declines on empty
  > hasContainers / null boundary alias. Successful recognition
  > collects every translated predicate into a list, AND-merges via
  > `MatchWhereBuilder.andOptional(list...)` to a flat
  > `SQLAndBlock`, then merges the result with any prior
  > `aliasFilters[boundaryAlias]` entry (e.g. the non-polymorphic
  > `@class = 'V'` `VertexStepRecogniser` writes for chain targets).
  > 10 unit tests pin every accept and decline branch, including the
  > pre-existing-filter merge case and the multi-container flat-AND
  > shape. 5 new equivalence cases extend the harness (3
  > `RECOGNIZED`, 2 `DECLINED`).
  >
  > **What was discovered:** The `HasNotStepRecogniser` planned for
  > this step turned out to be misplaced. TinkerPop 3.8.x desugars
  > `hasNot(key)` into `NotStep(__.values(key))` rather than a
  > `HasStep` with a special predicate (verified via `javap` on the
  > project's gremlin-core jar). `NotStep` is the logical-filter
  > track's territory, not the property-filter track's, so the
  > hasNot recogniser was deferred and an explicit
  > `V_out_Knows_hasNot_name_declines` equivalence case pins the
  > current native fallback. TinkerPop's `TraversalHelper.addHasContainer`
  > also flattens consecutive `.has(key, v).has(key2, v2)` calls
  > into ONE `HasStep` with multiple containers — so the
  > "accumulate across two HasSteps" path doesn't naturally arise
  > from idiomatic Gremlin; the multi-container path inside a single
  > step is the dominant case and is the one the unit and equivalence
  > tests exercise.
  >
  > **Cross-track impact:** Track 5 (logical filters: and / or / not /
  > where) inherits the responsibility for `hasNot(key)` because it
  > arrives as `NotStep`. Track 6 (HasLabelStepRecogniser +
  > HasIdStepRecogniser) builds on the same flat-AND-merge pattern
  > used here — collect translated predicates into a list, call
  > `andOptional(list.toArray(...))` once, merge with existing
  > `aliasFilters[boundary]` via `andOptional(existing, new)`. The
  > polymorphism handling on chain-target hasLabel will need to read
  > `WalkerContext.polymorphic` and respect the existing alias
  > filter (i.e. the prior `@class = 'V'` from VertexStepRecogniser
  > under non-polymorphic mode) — not by overwriting but by
  > AND-replacement (the more-specific class supersedes `V`). The
  > MatchWhereBuilder's varargs `andOptional` is fine for the
  > 2-or-3-operand case used here; if Track 6 lands many merge sites
  > a fixed-arity overload may be worth lifting (deferred to Track
  > 12 perf review).
  >
  > **Key files:**
  > - `core/.../gremlin/translator/strategy/HasStepRecogniser.java`
  >   (new) — recogniser with pre-validate-then-commit discipline,
  >   `T.label` defence-in-depth decline, predicate-adapter decline
  >   propagation, and AND-merge of new predicates with any prior
  >   alias filter.
  > - `core/.../gremlin/translator/strategy/GremlinStepWalker.java`
  >   (modified) — registry extended with `HasStepRecogniser.INSTANCE`
  >   between vertex and no-op-barrier entries.
  > - `core/.../gremlin/translator/strategy/HasStepRecogniserTest.java`
  >   (new) — 10 unit tests covering accept (single container, multi
  >   container flat-AND, existing filter merge, no boundary
  >   rebind), decline (non-HasStep, no boundary, T.label, empty
  >   containers, adapter decline, partial-validation no-mutation).
  > - `core/.../gremlin/translator/EdgeTraversalEquivalenceTest.java`
  >   (modified) — 5 new cases: 3 RECOGNIZED
  >   (`V_out_Knows_has_name_Bob`, `V_out_Knows_has_age_gt_30`,
  >   `V_out_Knows_has_name_Bob_has_age_gt_30`), 2 DECLINED
  >   (`V_out_Knows_has_age_gt_null_declines` for predicate-adapter
  >   decline, `V_out_Knows_hasNot_name_declines` for the NotStep
  >   route).

- [x] Step: HasLabelStep + HasIdStep recognisers
  - [x] Context: unavailable
  > **Risk:** medium — adds two new recognisers in `core` (no HIGH
  > triggers; intersection-downgrade logic for hasId is the trickiest
  > part but is deterministic and unit-testable).
  >
  > `HasLabelStepRecogniser` recognises `YTDBHasLabelStep` carrying
  > exactly one predicate of shape `P.eq("X")` (single-class) or
  > `P.within(A, B, ...)` (multi-class within form). Declines on any
  > other shape (P.neq, regex, multi-predicate) so D3 falls back to
  > native and the Cucumber count is preserved. Polymorphic mode
  > writes `aliasClasses[ctx.currentAlias]` (planner uses MATCH/SQL
  > polymorphic-by-default — `SELECT FROM Class` matches Class plus
  > all subclasses). Non-polymorphic mode writes
  > `aliasFilters[ctx.currentAlias]` AND-merged via the Step-1 helper
  > (replacing any prior chain-target `@class = 'V'` entry from
  > `VertexStepRecogniser` with the more-specific class). Multi-class
  > non-polymorphic emits `WHERE @class IN ['A','B',...]` using the
  > Step-1 cached-reflection helper. Reuse
  > `MatchClassFilters.classEq(name)` + `MatchClassFilters.wrapWhere`
  > and read `WalkerContext.polymorphic`.
  > `HasIdStepRecogniser` recognises `HasStep` with `hasId(...)`
  > (key = `T.id`). First single-ID write routes through
  > `aliasRids[alias]`. Repeated single-ID writes intersect: same ID
  > → no-op; different ID → downgrade to
  > `aliasFilters[alias] WHERE @rid IN [intersection]` (empty
  > intersection produces an empty filter that returns no rows).
  > Multi-ID writes route through `aliasFilters[alias] WHERE @rid IN
  > [...]` directly. When `aliasRids[alias]` already exists from a
  > prior `g.V(id)` start-step write, multi-ID `hasId` intersects.
  > IN AST built using the Step-1 cached-reflection helper.
  >
  > **What was done:** Added `HasLabelStepRecogniser` (new step
  > type `YTDBHasLabelStep`) and extended the prior step's
  > `HasStepRecogniser` to handle `T.id`-keyed mid-chain hasId
  > containers natively (instead of routing them through the
  > predicate adapter as if they were property predicates, which
  > would emit `id = ...` rather than `@rid = ...`). The new
  > `HasLabelStepRecogniser` recognises a single `Compare.eq` or
  > `Contains.within` predicate; single-class polymorphic mode
  > updates `aliasClasses[boundary]` via `MatchPatternBuilder.addNode`
  > (the planner does the polymorphic scan); single-class non-
  > polymorphic also writes `@class = X` to `aliasFilters[boundary]`,
  > REPLACING any prior `@class = V` filter (an AND-merge there would
  > yield the contradictory `@class = V AND @class = Person` which
  > matches no rows because Person instances carry `@class = Person`,
  > not `V`). Multi-class non-polymorphic writes `@class IN [...]`
  > to `aliasFilters[boundary]`. Multi-class POLYMORPHIC declines
  > because the planner has no class-IN polymorphic mechanism — the
  > recogniser would silently narrow the polymorphic semantic to
  > direct-instance matching otherwise.
  >
  > Hand in hand, `HasStepRecogniser`'s mid-chain T.id handling
  > mirrors the start-step's logic via the lifted `MatchRidFilters`:
  > extracts IDs from `Compare.eq` (single) and `Contains.within`
  > (multi); single-element constraint with no prior `aliasRids`
  > entry routes to `aliasRids` (planner fast path); existing
  > `aliasRids` slot intersects with the new constraint via
  > `installIdConstraint` — same ID is a no-op, single survivor
  > overwrites the slot, empty/multi survivors drop the slot and
  > emit `@rid IN [...]` into `aliasFilters`. Mixed buckets within
  > one HasStep (TinkerPop's `.hasId(r).has("name", v)` flatten)
  > populate both slots: T.id → `aliasRids`, property → AND-merged
  > into `aliasFilters`.
  >
  > Refactor: `MatchRidFilters` (new utility) holds
  > `extractIdsFromPredicate`, `buildRidIn`, `toSqlRid`, and
  > `toRecordId` — lifted from `StartStepRecogniser`'s previously-
  > private helpers so both start-step and mid-chain recognisers use
  > the same code path. `MatchClassFilters.classIn(List<String>)`
  > added for the multi-class IN expression, mirroring the existing
  > `classEq` shape but with an `SQLInCondition` left-side.
  > `GremlinStepWalker.PRODUCTION_RECOGNISERS` extended to insert
  > `HasLabelStepRecogniser.INSTANCE` between vertex and has-step
  > recognisers (registry order guarantees label narrowing claims
  > before property has-step iteration).
  >
  > **What was discovered:** TinkerPop's
  > `TraversalHelper.addHasContainer` flattens consecutive
  > `.has(...)`/`.hasId(...)`/`.hasLabel(...)` calls on the same alias
  > into ONE HasStep with multiple containers (when the end step is a
  > `HasContainerHolder`). Combined with `YTDBGraphStepStrategy`'s
  > traversal-rebuild — which inserts a freshly-constructed
  > `YTDBHasLabelStep` BEFORE the residual property HasStep when it
  > splits T.label out — the practical step ordering after the
  > strategy runs places `YTDBHasLabelStep` before any property
  > `HasStep` on the same alias. That ordering is what makes the
  > "REPLACE the prior V filter" semantic safe: at the moment
  > `HasLabelStepRecogniser` fires, the only thing in
  > `aliasFilters[boundary]` is `VertexStepRecogniser`'s default
  > `@class = V` (no property predicates have landed yet). Verified
  > both `g.V().out("E").hasLabel("X").has("k", v)` and the reverse
  > order produce the same after-strategy step list.
  >
  > Multi-class polymorphic was a real semantic divergence we hadn't
  > planned for: `g.V().out("E").hasLabel("Person", "Place")` under
  > polymorphic mode means "instances of Person + subclasses OR Place
  > + subclasses". The planner's `aliasClasses` map carries one
  > class per alias, and a `@class IN [...]` filter narrows to direct
  > instances only — silently wrong under polymorphic mode. The
  > recogniser declines this shape so D3 routes the traversal to
  > native; an explicit equivalence case
  > (`V_out_Knows_hasLabel_Person_Place_declines`) pins the decline.
  >
  > **Cross-track impact:** Tracks 7-10's recognisers can reuse
  > `MatchRidFilters` and `MatchClassFilters.classIn`. Track 12's
  > perf baseline should measure the chain-target hasLabel filter
  > shape — the polymorphic single-class case (most common) writes
  > nothing to aliasFilters and only updates aliasClasses, which is
  > cheaper than the start-step's hasLabel translation. The
  > non-polymorphic / multi-class shapes carry an additional filter
  > term per chain target. Track 5 (logical filters) inherits
  > responsibility for `hasNot(key)` (NotStep desugar) and broader
  > NotStep handling.
  >
  > Step-level dimensional review skipped per the per-step risk-
  > tagging rule (`medium`). 338 tests pass across the affected and
  > regression suites — including the existing 86 GqlMatchStatement
  > tests (the lift of MatchRidFilters out of StartStepRecogniser is
  > behaviour-preserving), the 28 GremlinToMatchTranslator unit
  > tests (cover the start-step path that now delegates to the
  > shared utility), and the 54 EdgeTraversalEquivalence cases.
  >
  > **Key files:**
  > - `core/.../gremlin/translator/strategy/MatchRidFilters.java`
  >   (new) — utility holding `extractIdsFromPredicate`,
  >   `buildRidIn`, `toSqlRid`, `toRecordId` lifted from
  >   `StartStepRecogniser`.
  > - `core/.../gremlin/translator/strategy/HasLabelStepRecogniser.java`
  >   (new) — handles single/multi-class `YTDBHasLabelStep`;
  >   polymorphic single-class via aliasClasses; non-polymorphic
  >   single-class via @class filter; non-polymorphic multi-class via
  >   @class IN; polymorphic multi-class declines.
  > - `core/.../gremlin/translator/strategy/MatchClassFilters.java`
  >   (modified) — added `classIn(List<String>)`.
  > - `core/.../gremlin/translator/strategy/HasStepRecogniser.java`
  >   (modified) — extended to handle T.id-keyed containers natively
  >   via `MatchRidFilters.extractIdsFromPredicate` and
  >   `installIdConstraint` (intersection / aliasRids vs
  >   aliasFilters routing mirrored from start step).
  > - `core/.../gremlin/translator/strategy/StartStepRecogniser.java`
  >   (modified) — refactored to delegate RID helpers to
  >   `MatchRidFilters`; private helpers removed.
  > - `core/.../gremlin/translator/strategy/GremlinStepWalker.java`
  >   (modified) — registry extended with
  >   `HasLabelStepRecogniser.INSTANCE` between vertex and has-step
  >   entries.
  > - `core/.../gremlin/translator/strategy/HasStepRecogniserTest.java`
  >   (modified) — 4 new tests covering mid-chain hasId routing
  >   (single→aliasRids, multi→IN, mixed buckets, intersection).
  > - `core/.../gremlin/translator/strategy/HasLabelStepRecogniserTest.java`
  >   (new) — 11 tests covering polymorphic/non-polymorphic
  >   single-class, non-polymorphic multi-class, polymorphic
  >   multi-class decline, and decline cascades.
  > - `core/.../gremlin/translator/EdgeTraversalEquivalenceTest.java`
  >   (modified) — 4 new RECOGNIZED cases (mid-chain hasId single/
  >   multi, mid-chain hasLabel polymorphic) and 1 DECLINED
  >   (polymorphic multi-class hasLabel).
