# Track 1: Shared MATCH IR builders + GQL adoption

## Description

Establishes the foundation that the rest of Phase 1 builds on. Creates a
new package `internal/core/sql/executor/match/builder/` with three classes:

**`MatchLiteralBuilder`** — extracted verbatim from
`GqlMatchStatement.toLiteral(Object)`, exposes a single `toLiteral(Object)
→ SQLExpression` static method handling String, Number, Boolean, Date,
List, Set, Map, byte[], `RecordIdInternal`.

**`MatchWhereBuilder`** — fluent API for constructing
`SQLBooleanExpression` trees:
- `eq(field, value)`, `op(field, operator, value)` for the full operator
  set (`SQLEqualsOperator`, `SQLGtOperator`, `SQLGeOperator`,
  `SQLLtOperator`, `SQLLeOperator`, `SQLNeOperator`)
- `in(field, list)`/`notIn(field, list)` for `SQLInCondition`/
  `SQLNotInCondition`
- `between(field, lo, hi)`
- `containsText(field, substring)` (`SQLContainsTextCondition`)
- `startsWith(field, prefix)`, `endsWith(field, suffix)`
- Boolean combinators `and(...)`/`or(...)`/`not(...)` returning
  `SQLAndBlock`/`SQLOrBlock`/`SQLNotBlock`
- Final `wrap(SQLBooleanExpression) → SQLWhereClause` produces a complete
  `SQLWhereClause`.

**`MatchPatternBuilder`** — stateful builder that accumulates `Pattern` +
`aliasClasses` + `aliasFilters`. Operations:
- `addNode(alias, className, where, optional)`
- `addEdge(fromAlias, toAlias, direction, edgeLabel, edgeFilter,
  whileCondition, maxDepth)`

Internally constructs `SQLMatchFilter` / `SQLMatchPathItem` /
`SQLMatchExpression` and passes them to `Pattern.addExpression(...)`.
`build()` returns an immutable triple of the three IR objects ready for
the planner.

After the builders exist, **GQL is refactored onto them** in three
distinct call-sites (consistency review corrected the original description
that conflated them):

1. **`GqlMatchStatement.buildPlan(...)`** — inlines `PatternNode`
   construction (~10 lines) and reads `filter.getFilter()` directly into
   `aliasFilters`. Refactor: replace the loop body with
   `MatchPatternBuilder.addNode(alias, className, filter.getFilter(),
   /*optional*/ false)`; the per-iteration `aliasClasses` / `aliasFilters`
   writes go away (the builder owns them).
2. **`GqlMatchStatement.buildWhereClause(Map<String,Object>)`** — a
   `static` helper called from `GqlMatchVisitor.visitNodePattern` (the
   visitor is the only caller; `buildPlan` does NOT call it). Refactor:
   replace the body with `whereBuilder.and(...).wrap()` over per-entry
   `whereBuilder.eq(field, MatchLiteralBuilder.toLiteral(value))`.
   Visitor's call-site does not change.
3. **`GqlMatchStatement.toLiteral(Object)`** — kept as a thin static
   delegate that returns `MatchLiteralBuilder.toLiteral(value)`, OR
   removed and callers redirected to `MatchLiteralBuilder` directly.
   Decided at step decomposition.

Total diff on the `gql` package is ~40 lines, all behavior-preserving.

Tests: unit tests for each builder covering all operations and edge
cases (null handling, empty lists, deeply nested boolean expressions);
a regression test asserting that `GqlMatchStatement` produces an
execution plan with the same step structure before/after refactor for
a representative GQL query; full GQL test suite must pass.

## Progress
- [x] Review + decomposition
- [x] Step implementation (4/4 complete)
- [x] Track-level code review (3/3 iterations — all dimensions PASS)

## Base commit
`247394051e`

## Reviews completed
- [x] Technical (`reviews/track-1-technical.md`) — 6 should-fix + 4 suggestions; all resolved via step-level decisions below
- [x] Adversarial (`reviews/track-1-adversarial.md`) — 1 blocker resolved (delegate strategy), 3 should-fix and 3 suggestions resolved or deferred

## Steps

- [x] Step: Create `MatchLiteralBuilder` in `internal/core/sql/executor/match/builder/`
  - [x] Context: unavailable
  > **Risk:** low — default (new code, pure extraction with no behavior change)
  >
  > **What was done:** Created
  > `core/.../sql/executor/match/builder/MatchLiteralBuilder.java` as a final
  > class with private constructor and a single
  > `public static SQLExpression toLiteral(Object value)` method, body copied
  > verbatim from `GqlMatchStatement.toLiteral`. Class Javadoc documents the
  > AST routing per type and the deliberate NPE-on-null contract.
  > `MatchLiteralBuilderTest` (16 tests) covers every branch: String (incl.
  > empty), Number (Long, Integer, Double, BigDecimal), Boolean (true/false),
  > RecordId (legacy flag + collection/position), Date, List, Set, Map,
  > byte[], unsupported-type IAE, and the documented null NPE. Tests use
  > reflection because `SQLExpression` exposes setters but no getters for
  > `booleanValue`/`literalValue`, and `SQLBaseExpression.string`/`number`
  > are package-private.
  >
  > **What was discovered:**
  > - `RecordIdInternal` is a `sealed interface`, not instantiable directly —
  >   tests must use the concrete `RecordId` record for the RID path.
  > - `SQLBaseExpression(String s)` constructor wraps and encodes the input
  >   as `"\"" + StringSerializerHelper.encode(s) + "\""`, so the stored
  >   `string` field never matches the raw input. Tests assert the field
  >   contains the original characters rather than equals it.
  > - `SQLBaseExpression.number` is typed `SQLNumber`, not `SQLInteger`;
  >   the runtime instance is the `SQLInteger` produced by `setValue(Number)`.
  > - `GqlMatchStatement.toLiteral` is unchanged — the new class is purely
  >   additive. The delegation in `GqlMatchStatement` is scheduled for the
  >   later refactor step in this track.
  >
  > **Cross-track impact:** None. The new class has zero call sites today;
  > all consumers come online in subsequent steps and tracks.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchLiteralBuilder.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchLiteralBuilderTest.java` (new)

- [x] Step: Create `MatchWhereBuilder` in `internal/core/sql/executor/match/builder/`
  - [x] Context: unavailable
  > **Risk:** medium — multi-file logic in core; introduces new public API
  > with subtle invariants (NOT block negate flag, between vs and(gte,lte),
  > NotIn composition strategy)
  >
  > **What was done:** Created a stateless fluent builder class
  > `MatchWhereBuilder` with `eq`, `op`, `in`, `notIn`, `between`,
  > `containsText`, `startsWith`, `endsWith`, `and(varargs)`, `or(varargs)`,
  > `not`, `wrap`. Each method returns the parser-emitted AST shape:
  > `SQLBinaryCondition` for eq/op/startsWith/endsWith; `SQLInCondition`
  > with a synthetic `SQLBaseExpression → SQLBaseIdentifier →
  > SQLLevelZeroIdentifier → SQLCollection` chain for in;
  > `SQLBetweenCondition` for range; `SQLContainsTextCondition` for
  > substring match; `SQLAndBlock`/`SQLOrBlock` for combinators;
  > `SQLNotBlock` with `negate=true` for negation. notIn composes via
  > `not(in(...))`. The `and`/`or` cardinality matrix is 0→IllegalStateException,
  > 1→passthrough, 2+→block. `MatchWhereBuilderTest` (19 tests) verifies
  > each AST shape and a representative SQL render via
  > `toString(emptyMap, sb)`.
  >
  > **What was discovered:**
  > - `SQLOrBlock` has no `setSubBlocks(List)` method (only
  >   `addSubBlock(SQLBooleanExpression)`); `SQLAndBlock` has both. The
  >   builder uses `addSubBlock` for OR and `setSubBlocks` for AND to keep
  >   parity with the parser's emit pattern.
  > - `SQLInCondition.operator` is package-private with no public setter,
  >   but `supportsBasicCalculation()` calls `operator.supportsBasicCalculation()`
  >   without a null guard. The builder sets the field via reflection to
  >   avoid NPEs once the planner reaches that path. Modifying the parser
  >   class to add a public setter was deferred — out-of-scope for this
  >   track and the reflection failure mode is well-scoped.
  > - For LIKE-style prefix/suffix matching there is `SQLLikeOperator`
  >   (implements `SQLBinaryCompareOperator`); the builder uses
  >   `SQLBinaryCondition` with `SQLLikeOperator` and a literal `prefix%` /
  >   `%suffix` string, matching what hand-written `WHERE name LIKE 'X%'`
  >   produces.
  > - `SQLNotBlock.toString` renders `NOT a = 1` (without grouping
  >   parens). The default `negate=false` is a silent pass-through bug —
  >   the builder explicitly sets `negate=true`.
  >
  > **Cross-track impact:** None invalidating. Future-track impacts:
  > - Predicate adapter (later track) will consume the full operator
  >   surface; reflection-based `setInOperator` is an internal detail
  >   transparent to callers.
  > - The `notIn` composition is observable: callers see
  >   `SQLNotBlock(SQLInCondition(...))` rather than a single
  >   `SQLNotInCondition`. Equivalence under `WHERE` evaluation is
  >   preserved; index hot-path differences are deferred per the plan's
  >   index-awareness note.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchWhereBuilder.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchWhereBuilderTest.java` (new)

- [x] Step: Create `MatchPatternBuilder` in `internal/core/sql/executor/match/builder/`
  - [x] Context: unavailable
  > **Risk:** low — default (new code; routes through existing well-tested
  > `Pattern.addExpression(...)` and `getOrCreateNode` paths)
  >
  > **What was done:** Created `MatchPatternBuilder` as a stateful builder
  > with `addNode(alias, className, where, optional)`,
  > `addEdge(fromAlias, toAlias, dir, edgeLabel, edgeFilter, whileCondition,
  > maxDepth)`, and `build() → PatternIR(Pattern, aliasClasses,
  > aliasFilters)`. `addNode` is idempotent on alias and updates optional
  > on repeat calls. `addEdge` builds a `SQLMatchExpression` (origin =
  > `SQLMatchFilter.fromGqlNode(fromAlias, null)`, items = single
  > `SQLMatchPathItem` with the appropriate `outPath` / `inPath` /
  > `bothPath` method call and target filter), then delegates to
  > `Pattern.addExpression(...)` so the existing well-tested
  > `getOrCreateNode` path performs implicit endpoint creation. The
  > builder defines `Direction.OUT/IN/BOTH` and a public `PatternIR`
  > record. `build()` defensively copies the alias maps so subsequent
  > mutations don't leak into a previously-returned snapshot. 16 unit
  > tests cover single-node / multi-hop / each direction / optional /
  > class+where registration / implicit-creation / anonymous-alias
  > preservation / null-alias NPE / unsupported-feature throws / build
  > snapshot semantics.
  >
  > **What was discovered:**
  > - `SQLMatchFilter` has no public setter for `whileCondition` /
  >   `maxDepth` — those fields live inside `SQLMatchFilterItem` and are
  >   reachable only via reflection or by adding parser setters. The
  >   builder rejects non-null `whileCondition`/`maxDepth` arguments
  >   with `UnsupportedOperationException` so the API stays loud about
  >   the gap; later tracks that need variable-depth traversal will
  >   either add the parser setters or wire reflection.
  > - `addEdge` does not populate `aliasClasses`/`aliasFilters` for the
  >   target alias — the path item's `setFilter` is target-vertex
  >   metadata, distinct from the planner's alias-level inference maps.
  >   Callers that want plan-level selectivity on a target must call
  >   `addNode(toAlias, className, where, …)` separately. Documented in
  >   the `addEdge` Javadoc.
  > - `Pattern.aliasToNode` is a public LinkedHashMap field — no setter
  >   guard — so `computeIfAbsent` is the appropriate API for both the
  >   builder and the parser-emitted `getOrCreateNode`.
  >
  > **Cross-track impact:** None invalidating. The translator will need
  > variable-depth support in a later track — either add public setters
  > to `SQLMatchFilter`/`SQLMatchFilterItem` then or use reflection.
  > Documented as deferred via `UnsupportedOperationException`.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchPatternBuilder.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchPatternBuilderTest.java` (new)

- [x] Step: Refactor `GqlMatchStatement` onto shared builders + golden-plan regression tests
  - [x] Context: unavailable
  > **Risk:** medium — multi-file logic + behavior-equivalence invariant
  > on existing GQL plans (touches stable production class with 86 tests)
  >
  > **What was done:** `GqlMatchStatement.buildPlan` now drives a
  > `MatchPatternBuilder` instead of mutating `Pattern.aliasToNode` and the
  > alias maps directly; the GQL-specific `effectiveAlias` and
  > `effectiveType` helpers stay in place to preserve the `$c<N>` and `"V"`
  > defaults. `buildWhereClause` keeps the same package-private signature
  > and always-wrapped `SQLAndBlock` shape (preserved deliberately because
  > `MatchWhereBuilder.and` would unwrap a single-element call) but now
  > drives `MatchWhereBuilder.eq` + `MatchLiteralBuilder.toLiteral` for
  > each entry. The private `toLiteral` delegate was removed (no internal
  > callers remain after the buildWhereClause refactor). Added a new test
  > class `GqlMatchStatementPlanGoldenTest` (3 tests) that snapshots
  > `plan.prettyPrint(0, 2)` for: single-node anonymous; multi-property AND
  > filter; multi-filter map producing a CARTESIAN PRODUCT. The first two
  > assert full-string equality; the third asserts structural elements
  > (PREFETCH for each alias, FILTER ITEMS WHERE for each predicate,
  > CARTESIAN PRODUCT presence, CALCULATE PROJECTIONS terminator) because
  > the planner's tiebreak between equally-selective branches is not
  > deterministic across runs.
  >
  > **What was discovered:**
  > - `MatchWhereBuilder.and(c)` returning the single operand directly is
  >   the right behavior for parser parity but would change
  >   `buildWhereClause`'s historical plan shape (it always wrapped in
  >   `SQLAndBlock`). The refactored `buildWhereClause` constructs the
  >   `SQLAndBlock` explicitly via `getSubBlocks().add(...)` to preserve
  >   shape. The Javadoc documents this divergence.
  > - The cartesian-product join's internal alias ordering is
  >   non-deterministic across runs (observed with two equally-selective
  >   `GoldenMatchC` filters on aliases `a` and `b`). Full-string
  >   `prettyPrint` equality is therefore unreliable for shapes that hit
  >   `splitDisjointPatterns` / `CartesianProductStep`. The test for that
  >   case asserts structural elements only.
  > - `GqlStructureTest` shows 17 pre-existing failures unrelated to this
  >   refactor (ANTLR-grammar AST shape mismatches in
  >   `testPositiveGqlStructure`); confirmed by re-running on a commit
  >   that has none of this track's changes. Out of scope for this track.
  >
  > **Cross-track impact:** None. All 86 `GqlMatchStatementTest` cases pass
  > unchanged; the 3 new golden tests pass. The visitor's
  > `buildWhereClause` call site is byte-identical (signature unchanged),
  > and the produced `SQLBinaryCondition` AST is byte-identical to the
  > pre-refactor output for the eq path.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gql/parser/GqlMatchStatement.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gql/parser/GqlMatchStatementPlanGoldenTest.java` (new)

