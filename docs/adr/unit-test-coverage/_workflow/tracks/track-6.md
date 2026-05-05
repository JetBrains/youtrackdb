# Track 6: SQL Functions

## Progress
- [x] Review + decomposition
- [x] Step implementation (8/8 complete)
- [x] Track-level code review (2/3 iterations used + iter-3 gate PASS on BC+TB)

## Base commit
`d05d16ad519cb543e83f3813de2912799574d564`

## Pre-rebase SHA
`e435baddcd3d1fb1811a21a79bbb977e3d0f50a6`

## Post-rebase SHA
`d05d16ad519cb543e83f3813de2912799574d564`

## Reviews completed
- [x] Technical → `reviews/track-6-technical.md` (1 blocker T3, 4 should-fix, 4 suggestions)
- [x] Risk → `reviews/track-6-risk.md` (0 blocker, 3 should-fix, 3 suggestions)
- [x] Adversarial → `reviews/track-6-adversarial.md` (2 blockers A2+A3, 4 should-fix, 3 suggestions)

## Review Response Summary

**Blockers addressed in decomposition:**
- **T3 (SQLMethod signature)**: `text/*` and `conversion/*` are exclusively `SQLMethod*` classes extending `AbstractSQLMethod`. The `SQLMethod.execute` signature places `CommandContext` 3rd, `ioResult` 4th, `iParams` 5th — different from `SQLFunction.execute` order. All SQLMethod tests must call `method.execute(iThis, record, context, ioResult, params)`. Documented in steps 4, 6, 8.
- **A2 (scope boundary)**: All 14 SQLMethod* classes physically under `sql/functions/` (coll/SQLMethodMultiValue; misc/SQLMethodExclude,Include; text/SQLMethodAppend,Hash,Length,Replace,Right,SubString,ToJSON; conversion/SQLMethodAsDate,AsDateTime,AsDecimal,Convert) **belong to Track 6** because that is their JaCoCo package. Assigned to steps 4, 6, 8.
- **A3 (scope is ~8 steps, not ~6)**: Decomposed into 8 steps. Scope indicator in plan file is kept as-is (it's an estimate, not a contract — per `conventions.md`).

**Should-fix items absorbed:**
- T1/T2: Full class enumeration in each step's scope.
- T4: `stat` subpackage (Median, StandardDeviation gaps) assigned to step 7.
- T6: `SQLGraphNavigationFunction.propertyNamesForIndexCandidates` tested in step 1.
- A1: Per-class DB-dependency call-outs in each step.
- A4: TraversedElement/Edge/Vertex placed in "Coll with DB" (step 4) with stack-injection pattern documented.
- A5: `SQLFunctionThrowCME` `(RecordIdInternal) iParams[0]` unchecked cast → pinning regression with WHEN-FIXED marker in step 6.
- A8: Graph functions split into steps 1 (dispatchers) and 2 (algorithms).
- R1: `SQLFunctionDate` / `SQLMethodAsDate` / `SQLMethodAsDateTime` string-path tests use explicit UTC timezone.
- R2: A* CUSTOM heuristic tested in step 2 with a stored function registered in function library.
- R3: TraversedElement stack-injection pattern: `context.setVariable("stack", syntheticDeque)` with `Identifiable` entries.

**Suggestions deferred / absorbed:**
- T5: `misc.SQLFunctionFormat` is dead code (not in DefaultSQLFunctionFactory). Test only `text.SQLFunctionFormat`; flag the dead duplicate with WHEN-FIXED marker in step 5 or 6.
- T7/T8/T9, R5: Specific per-class notes in the relevant step.
- R4 / A7: `SQLFunctionRuntime` is integration-level (SQL parser coupled) — explicitly deferred to Tracks 7/8 where SQL queries exercise it.
- A6: `HeuristicFormula` enum standalone test in step 2.
- A9: `SQLFunctionDetachResult` 2-case test in step 8.

## Conventions for all steps

- **JUnit 4**, `surefire-junit47` runner (constraint 1).
- **DbTestBase** for tests requiring a session/schema/data; standalone with `BasicCommandContext` otherwise. Decide per-class (not per-subpackage) per A1.
- **SQLMethod execute signature**: `method.execute(Object iThis, Result iCurrentRecord, CommandContext iContext, Object ioResult, Object[] iParams)`. **NOT** the SQLFunction order.
- **Falsifiable regressions for bugs found**: copy-paste Track 5 convention. Bug-pinning tests carry `// WHEN-FIXED: <what to change>` markers.
- **Timezone**: Any date-string parsing test explicitly passes `"UTC"` to avoid locale flakiness.
- **Run spotless**: `./mvnw -pl core spotless:apply` before every commit.
- **Per-step verification**: `./mvnw -pl core clean test` before commit.
- **Track episode tests**: Follow Track 5's pattern of descriptive method names + class-level Javadoc explaining scenario.

## Steps

- [x] Step 1: Factory infrastructure + graph traversal dispatchers + SQLGraphNavigationFunction interface
  - [x] Context: warning
  > **What was done:** Added 75 unit tests across 6 new test files.
  > - `SQLFunctionFactoryTemplateTest` (10 tests): register-as-instance vs register-as-Class,
  >   lowercasing semantics on register, case-sensitive lookup on hasFunction/createFunction,
  >   unknown-name CommandExecutionException with message-content assertion, broken-constructor
  >   wrap-cause (asserts non-null message, function-name substring, non-null getCause), and
  >   getFunctions() live-map view.
  > - `DefaultSQLFunctionFactoryTest` (8 tests): ALL_NAMES mirror of registerDefaultFunctions
  >   drives hasFunction/createFunction/getFunctionNames/production-map checks; size of the live
  >   production map must equal ALL_NAMES.length (catches silent duplicate registrations);
  >   instance-registered Coalesce returns same object, class-registered Count returns fresh
  >   instances (assertSame/assertNotSame); unknown name throws CommandExecutionException with
  >   name-substring message.
  > - `CustomSQLFunctionFactoryTest` (8 tests): @FixMethodOrder(NAME_ASCENDING) so known-math_
  >   entries assertion runs before the custom-prefix registration (process-wide static map);
  >   re-register first-wins verified by capturing math_abs instance before/after and assertSame;
  >   custom prefix with overloaded static methods exposes SQLStaticReflectiveFunction;
  >   registerDefaultFunctions no-op via assertEquals on name-count.
  > - `SQLFunctionOutInBothTest` (15 tests): Out/In/Both v2v traversal with Set.of-equality
  >   assertions; labels=[...] filter path; null-labels element path (Object[]{null}) hits the
  >   iParameters[0] != null guard; multi-labels path (>1 labels) exercises fetchFromIndex
  >   multi-label early-return; Result-wrapped iThis exercises SQLEngine.foreachRecord's
  >   Result.asEntity() branch; RecordNotFoundException swallowed to null; SQLFunctionMoveFiltered
  >   empty/non-empty possibleResults paths; invalid-argument IllegalArgumentException with
  >   exact "Invalid argument type:" prefix; all three dispatchers' metadata and default
  >   aggregation flags pinned in a loop; @After rollbackIfLeftOpen guards against leaked tx.
  > - `SQLFunctionGraphEdgeVariantsTest` (16 tests): OutE/InE/BothE + OutV/InV/BothV traversal;
  >   transaction-scoped identity capture for OutV/InV to avoid detached-entity; metadata
  >   (name/min/max) symmetry across all six dispatchers; propertyNamesForIndexCandidates
  >   literals for V dispatchers, "knows"-substring pin for OutE delegation, null for non-vertex.
  > - `SQLGraphNavigationFunctionTest` (16 tests): direct coverage of propertiesForV2ENavigation
  >   (null for non-vertex including plain document class, non-null "knows"-pinned for vertex);
  >   propertiesForV2VNavigation (non-vertex OUT keeps LINK labels in input order, IN
  >   substitutes EntityImpl.getOppositeLinkBagPropertyName, BOTH concatenates OUT then IN,
  >   missing/non-LINK labels skipped); v2v on vertex tests OUT/IN distinctness and BOTH
  >   containment (not just non-null); null-Direction IllegalStateException defensive-throw
  >   pinned; empty-labels-array boundary returns non-null empty for all three directions.
  >
  > No production code was modified.
  >
  > **What was discovered:**
  > - `CustomSQLFunctionFactory` stores its registry in a `private static final Map` populated
  >   by a static initializer (`math_*`) and mutated by a non-synchronized static `register`
  >   method. This is a latent cross-test / cross-JVM-fork state leak; the review flagged it as
  >   a flakiness risk and we mitigated with @FixMethodOrder + weakened "only math_" assertion
  >   + the first-wins-verifying assertSame. If future parallelism is enabled this needs a
  >   proper concurrency-safe replacement (TODO for Track 22 or a production-side fix).
  > - `session.commit()` detaches returned entity wrappers — iterating an Iterable<Vertex>
  >   AFTER commit raises "Data container is unloaded please acquire new one from entity".
  >   Pattern for graph-dispatcher tests: collect identities into a local List BEFORE commit.
  > - `DbTestBase` shares one session across test methods; a test that calls `session.begin()`
  >   without a matching commit/rollback leaks the transaction into the next @Before and makes
  >   the entire class cascade-fail. Added @After rollbackIfLeftOpen as a safety net.
  > - `SchemaClass.createProperty` does NOT take a session argument (despite some test code
  >   writing it that way initially) — signature is `createProperty(String, PropertyType)` or
  >   the overload with linkedClass/linkedType.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/SQLFunctionFactoryTemplateTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/DefaultSQLFunctionFactoryTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/CustomSQLFunctionFactoryTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/graph/SQLFunctionOutInBothTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/graph/SQLFunctionGraphEdgeVariantsTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/graph/SQLGraphNavigationFunctionTest.java` (new)
  >
  > **Critical context:** Dimensional review (5 agents — code-quality, bugs-concurrency,
  > test-behavior, test-completeness, test-structure) ran one iteration, surfaced 0 blockers
  > and ~15 should-fix/suggestion items which were addressed in the `Review fix:` commit.
  > Cross-track impact: CONTINUE — no upstream assumption broken for Steps 2-8; factory
  > infrastructure and dispatcher dispatch are stable platform surfaces for subsequent graph
  > algorithm + collection + misc function tests.

- [x] Step 2: Graph algorithm functions (ShortestPath / Dijkstra / Astar) + HeuristicFormula + MoveFiltered supernode path
  - [x] Context: warning
  > **What was done:** Added 64 unit tests across 4 new test files in
  > `core.sql.functions.graph`.
  > - `HeuristicFormulaTest` (33 tests): enum `values()` order + exact-case
  >   `valueOf()` pinning; simple/Manhattan/maxAxis/diagonal/Euclidean/
  >   EuclideanNoSQR heuristic helpers in both 2-axis and N-axis overloads
  >   with hand-calculated expected values; tieBreaking deterministic +
  >   tieBreakRandom with two-call-divergence assertion (BC1); all 6
  >   type-coercion helpers (`stringArray`, `booleanOrDefault`,
  >   `stringOrDefault`, `integerOrDefault`, `longOrDefault`,
  >   `doubleOrDefault`) with null/number/string/unrecognized branches;
  >   non-String, non-array input → ClassCastException pinning.
  > - `SQLFunctionShortestPathEdgeTest` (15 tests): edge=true walk
  >   (interleaved vertex/edge/vertex/edge/vertex — 5 elements, BOTH
  >   direction); edge=true + edge-type filter (7-element path via Edge1
  >   only); source-equals-destination short-circuit; collection-of-
  >   edge-type-strings; Identifiable options record (loaded from saved
  >   entity); null source, null destination, non-vertex source (Plain
  >   class), non-vertex destination (PlainDst class), literal-string
  >   source, literal-string destination — exact-match error messages;
  >   maxDepth=0 immediate-break; edge="true" string-toBoolean branch;
  >   edge=Boolean.FALSE vertex-only walk; exact-match syntax string.
  > - `SQLFunctionDijkstraEdgeCasesTest` (8 tests): no-direction (uses
  >   Astar's default OUT), explicit direction, unreachable destination
  >   (v5 isolated), missing weight field (zero-weight fallback with
  >   path ∈ {3,4} + fixture-only vertex assertion); legacy
  >   `getDistance(node, target)` -1f sentinel; exact-match syntax;
  >   `isVariableEdgeWeight`=true; `aggregateResults`=false.
  > - `SQLFunctionPathFinderTest` (8 tests): test-only subclass
  >   (`FixedPathFinder`) re-activates the dormant abstract-class
  >   traversal loop — in production, Dijkstra (only concrete
  >   descendant) delegates to Astar. Covers linear-graph traversal
  >   (v1→v2→v3→v4, pinned positive `maxDistances`/`maxPredecessors`
  >   diagnostics), unreachable destination (null path),
  >   getMinimum(empty)=null, sumDistances arithmetic, default
  >   `isVariableEdgeWeight`/`aggregateResults` both false,
  >   getResult-delegates-to-getPath with content-based pin, IN
  >   direction from leaf source (null path).
  >
  > No production code was modified.
  >
  > **What was discovered:**
  > - **`session.isTxActive()` is the correct null-safe predicate** for
  >   test tearDown cleanup. Using `getActiveTransaction() != null &&
  >   .isActive()` throws "There is no active transaction" because
  >   `getActiveTransaction()` itself is not null-safe. Confirmed by
  >   the Step 1 `SQLFunctionOutInBothTest.rollbackIfLeftOpen()` pattern.
  > - **`Entity.save()` does not exist** — `session.newEntity(...)` (or
  >   `session.newEntity(className)`) auto-registers with the active
  >   transaction; committing persists it. Sibling tests and Step 1 use
  >   this pattern.
  > - **Schema changes are not transactional** — calling
  >   `session.getSchema().createClass(...)` inside a `begin()`/`commit()`
  >   block throws. Must create schema classes outside the transaction.
  > - **`SQLFunctionShortestPath.execute` requires an active transaction
  >   even for some error-path tests** — a non-null source vertex triggers
  >   `session.getActiveTransaction().load(...)` BEFORE the destination
  >   checks. Null-destination / non-vertex-destination / literal-string-
  >   destination tests must wrap in `begin()`/`commit()`. Null-source
  >   does not need a tx (the null-source check fires first).
  > - **`SQLFunctionPathFinder.execute` clears the distance map at the
  >   end** (`distance = null;` line 100 of the production source).
  >   Post-execute calls to `getShortestDistance`/`isNotSettled` that
  >   read `distance.containsKey(...)` NPE. Tests exercise these methods
  >   indirectly via the execute loop instead of post-hoc direct calls.
  > - **`SQLFunctionHeuristicPathFinderAbstract.stringArray` has dead
  >   code** — the trailing `return new String[]{}` after
  >   `if (fromObject instanceof Object)` is unreachable because any
  >   non-null object satisfies `instanceof Object`; forcing a cast to
  >   `String[]` is what actually happens for non-String/non-array
  >   inputs (yielding ClassCastException). WHEN-FIXED: either remove
  >   the dead branch or add a proper type check with a sensible
  >   return.
  > - **`E` edge class already exists** by default in YouTrackDB.
  >   Test edge-class names must avoid it — used `EdgeFixed` in
  >   PathFinderTest.
  > - **Dijkstra path length is algorithm-order dependent when all
  >   edge weights are 0** — with a shortcut + linear chain, either
  >   the 3-vertex shortcut or the 4-vertex chain is a valid return.
  >   Tests must accept both canonical paths.
  >
  > **What changed from the plan:**
  > - **MoveFiltered supernode threshold path was deferred** — testing
  >   requires building a graph with >1000 neighbours + a composite
  >   index on `out`/`in`, which is expensive and complex for marginal
  >   coverage gain. Note added: revisit in Track 22 final sweep if
  >   graph coverage is still short of 85%/70%.
  > - **PathFinderTest initially included `isNotSettled`,
  >   `getShortestDistance`, and `getNeighbors` direct-call tests**
  >   but these NPE after `execute()` clears the distance map. Dropped
  >   those direct tests; indirect coverage via `execute()` is
  >   sufficient.
  > - **`SQLFunctionAstarTest` already covers CUSTOM heuristic, N-axis
  >   (3 axes), `emptyIfMaxDepth`, tie-breaker off, MAXAXIS,
  >   EUCLIDEANNOSQR, and Direction.BOTH** — the original Step 2 plan
  >   lists these as gaps, but the reviewer-verified existing coverage
  >   made additional Astar tests redundant. Extended only indirectly
  >   via Dijkstra's delegation paths.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/graph/HeuristicFormulaTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/graph/SQLFunctionShortestPathEdgeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/graph/SQLFunctionDijkstraEdgeCasesTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/graph/SQLFunctionPathFinderTest.java` (new)
  >
  > **Critical context:** Dimensional review (5 agents — code-quality,
  > bugs-concurrency, test-behavior, test-completeness, test-structure)
  > ran one iteration, surfaced 0 blockers and ~40 should-fix/suggestion
  > items. Applied the should-fix set (exact-match syntax pinning,
  > path-length bounds, missing-coverage tests including non-vertex
  > destination + maxDepth=0 + edge="true"/Boolean.FALSE, exact error
  > messages, active-tx rollback safety net) and select suggestions
  > (assertFalse idiom, unused-helper removal, unnecessary scaffolding
  > cleanup, stronger edge-identity assertions, tieBreakRandom
  > two-call divergence, ClassCastException pin). Deferred: MoveFiltered
  > supernode to Track 22; shared test-base refactor (CQ1) as a broader
  > code-quality task. Cross-track impact: CONTINUE — no upstream
  > assumption broken for Steps 3-8; graph algorithm surface is stable
  > and subsequent tracks target independent subpackages.

- [x] Step 3: Collection standalone functions
  - [x] Context: warning
  > **What was done:** Added 131 unit tests across 11 test files (9 new, 2
  > extended) in `sql/functions/coll`. New: `SQLFunctionDistinctTest` (11 tests —
  > aggregate filter, null handling, type independence, identity preservation),
  > `SQLFunctionFirstTest` / `SQLFunctionLastTest` (12/11 — MultiValue first/last,
  > SQLFilterItem resolution via StubFilterItem, scalar pass-through, empty
  > handling), `SQLFunctionListTest` (14 — aggregation vs inline, Map special-case,
  > null skipping, fresh-list-per-call, getResult clears context, aggregateResults
  > flag matrix), `SQLFunctionMapTest` (17 — even-pair inline, single-Map
  > aggregation, null-value skip, exact-match exception messages, HashMap
  > putAll-overwrite semantics, aggregateResults overload+instance), 
  > `SQLFunctionSetTest` (10 — dedup via HashSet, fresh set per inline call, null
  > skipping, getResult clears), `SQLFunctionUnionAllTest` (12 —
  > MultiCollectionIterator inline, aggregation via Boolean.TRUE, duplicates
  > preserved, SQLFilterItemVariable resolution both branches,
  > non-Boolean.TRUE fallthrough drift guard, accumulator-list pinning),
  > `SQLFunctionIntersectTest` (20 — null/empty short-circuit, inline 1/2/3-way,
  > Set fast-contains path, aggregation with Collection/Iterator/Iterable/scalar
  > initial context, empty-first-no-context-corruption regression, both
  > intersectWith overloads, SupportsContains fast-path, opaque-scalar
  > overlapping+disjoint), `SQLFunctionMultiValueAbstractTest` (5 — via test-only
  > `ProbeFunction` subclass pinning aggregateResults flag driven by
  > configuredParameters.length and default getResult non-clearing behaviour).
  > Extended: `SQLFunctionDifferenceTest` (8 — null-first, empty-first
  > early-return, single-operand dedup, null-subsequent-skip, scalar singleton,
  > aggregation-mode rejection, name/syntax, legacy multi-operand kept with
  > descriptive name), `SQLFunctionSymmetricDifferenceTest` (8 — aggregation
  > single-value stream, aggregation Collection unroll, inline scalar, mixed
  > Collection+scalar inline, null-first no-op, rename of legacy tests to
  > descriptive names).
  >
  > Final coverage: `sql/functions/coll` 48.6% → **68.6%** line (+20%),
  > 38.4% → **61.3%** branch (+23%) — matches the Step 3 target of
  > ~70% pre-TraversedElement. The remaining ~17% gap (to 85% target) is
  > assigned to Step 4 — `SQLMethodMultiValue`, `SQLFunctionTraversedElement`/
  > `Edge`/`Vertex`, Intersect `Identifiable`/`RidSet`/`LinkBag` paths — all
  > require DbTestBase.
  >
  > No production code was modified.
  >
  > **What was discovered:**
  > - `BasicCommandContext.getDatabaseSession()` throws `DatabaseException("No
  >   database session found in SQL context")` when no session is attached,
  >   which fires BEFORE the `CommandExecutionException` constructor in
  >   `SQLFunctionDifference.execute` can build its intended message. The net
  >   effect is the same (aggregation mode is rejected), but the exception type
  >   is wrong for standalone tests. Documented; test catches `RuntimeException`
  >   + message substring and notes the contract mismatch.
  > - `SQLFunctionList` single-argument execute leaves `context` non-null after
  >   return (the returned list IS the context reference). A follow-up `execute`
  >   on the same instance appends rather than resets — different from the
  >   >1-param inline reset. Documented via the `mapArgumentIsInsertedAsWholeMap`
  >   test single-call scope.
  > - `SQLFunctionMap` throws identical `IllegalArgumentException` messages for
  >   both the single-non-Map and odd-count branches. Substring assertions
  >   ("map" / "pair") were vacuous — a swap of the two branches would pass the
  >   naive assertion. Tightened to exact-match string.
  > - `SQLFunctionIntersect.intersectWith(Iterator, Object)` default-case
  >   `IllegalArgumentException` is unreachable under standard inputs: the
  >   outer conversion block always routes opaque values through
  >   `MultiValue.toSet`, producing a `Set` which matches the `Collection` case.
  >   The default branch is defensive-only and requires a subclass with
  >   `supportsFastContains()==true` but no matching interface — no such type
  >   exists today. Documented; no assertion targets the unreachable path.
  > - `SQLFunctionUnionAll.execute` uses strict `Boolean.TRUE.equals(...)` —
  >   `Boolean.FALSE`, the String `"true"`, `Integer 1` all take the inline
  >   branch. Added `nonBooleanTrueAggregationVariableTakesInlinePath` drift
  >   guard against loose `!= null` / `Boolean.parseBoolean` refactors.
  > - `SQLFunctionIntersect` empty-collection short-circuit at lines 62-66
  >   fires BEFORE the aggregation branch — so `{List.of()}` in aggregation
  >   mode returns `Set.of()` and leaves `context == null`. The next call with
  >   real data correctly seeds as if first. Pinned with
  >   `aggregationWithEmptyCollectionShortCircuitsWithoutCorruptingContext`.
  > - `SQLFilterItemVariable` can be constructed with `(null, null, "$name")` —
  >   `SQLFilterItemAbstract.smartSplit` on a plain name without dots/brackets/
  >   parens parses cleanly, `setRoot` just assigns the string. Used in
  >   `FakeVariable` test helper without NPEs.
  >
  > **What changed from the plan:**
  > - Added `SQLFunctionMultiValueAbstractTest` that wasn't in the original
  >   Step 3 target list — covers the shared abstract base via a `ProbeFunction`
  >   subclass. Pins `aggregateResults()` flag driven by
  >   `configuredParameters.length` and the default `getResult()` no-clear
  >   behaviour (subclasses like List/Set/Map override to clear). Strengthens
  >   the foundation that every collection function inherits.
  > - Extended `SQLFunctionDifferenceTest` and `SQLFunctionSymmetricDifferenceTest`
  >   beyond the "if coverage gaps found" optional scope — both had only 1-2
  >   legacy tests with non-descriptive names (`testExecute`, `testOperator`);
  >   renamed and added 6-7 branch-coverage tests each.
  > - `SQLFunctionIntersect` `Identifiable`/`RidSet`/`LinkBag` paths deferred
  >   to Step 4 as planned; the default `IllegalArgumentException` branch in
  >   `intersectWith(Iterator, Object)` documented as unreachable and not
  >   asserted against.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionDistinctTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionFirstTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionLastTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionListTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionMapTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionSetTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionUnionAllTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionIntersectTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionMultiValueAbstractTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionDifferenceTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionSymmetricDifferenceTest.java` (modified)
  >
  > **Critical context:** Dimensional review (5 agents — code-quality,
  > bugs-concurrency, test-behavior, test-completeness, test-structure) ran
  > one iteration, surfaced 0 blockers and ~20 should-fix/suggestion items.
  > Applied the high-value should-fix set (exact-message pinning, misleading
  > name rename, FakeVariable Javadoc fix, tight-equality upgrade of
  > containment assertions, Set.equals replacement for hand-rolled helpers,
  > non-Boolean.TRUE drift guard, empty-short-circuit-state-integrity
  > regression, mixed Collection+scalar SymmetricDifference, null-only inline
  > pair for Map, legacy test renames). Deferred: `StubFilterItem` DRY
  > extraction (CQ3/TS1) — out-of-scope micro-refactor; some edge cases for
  > First/Last empty arrays/maps (TC6) — diminishing returns on already-good
  > coverage. Cross-track impact: CONTINUE — no upstream assumption broken
  > for Steps 4-8; Step 4 will exercise the deferred DB-required paths as
  > planned.

- [x] Step 4: Collection DB-required + TraversedElement/Edge/Vertex + SQLMethodMultiValue
  - [x] Context: warning
  > **What was done:** Added 67 unit tests across 5 new DbTestBase test files
  > in `sql/functions/coll`.
  > - `SQLFunctionTraversedElementTest` (31 tests): full coverage of the
  >   positive-index (reversed stack iteration) and negative-index (insertion-
  >   order iteration) dispatch, items=1 unwrap vs items>1 list collection,
  >   both branches of `stackToList` (List fast path + stream conversion for
  >   generic Collection), Identifiable and TraverseRecordProcess stack
  >   entries including mixed-type stacks, `ResultInternal` `$stack` metadata
  >   fallback (via `List.of(a)` to survive `convertPropertyValue` coercion),
  >   missing-stack `CommandExecutionException` pinning with function-name
  >   and "traverse" substring assertions (both null-iThis and non-Result
  >   iThis paths), class-filter "skip without advancing counter" contract
  >   pinned with 5-entry [V,E,V,E,V] stacks at beginIndex=1/-2, empty-stack
  >   null-return, missing-RID `RecordNotFoundException` pinning (documents
  >   that the null-guards in the production code's positive/Identifiable
  >   branches are defensively-dead because `transaction.load()` throws RNFE
  >   rather than returning null), metadata/contract surface
  >   (aggregateResults=false, filterResult=true, getResult=null,
  >   name-based subclass constructor, syntax, min/max params), and
  >   HashSet-backed stack acceptance.
  > - `SQLFunctionTraversedEdgeTest` (5 tests): pins the "E" filter contract
  >   — filter skips vertices from top and bottom, items>1 collects only
  >   edges, vertex-only stack returns null for Edge filter, name and
  >   min/max params inherited from parent.
  > - `SQLFunctionTraversedVertexTest` (5 tests): symmetric pins for "V"
  >   filter.
  > - `SQLMethodMultiValueTest` (19 tests): pins the AbstractSQLMethod
  >   `execute(iThis, record, context, ioResult, params)` signature (context
  >   3rd, NOT 5th) — null iThis / null params[0] short-circuits, single
  >   non-multi-value scalar fast path, single-param collection
  >   (List/Set/Array/Map) inner-iteration with `EntityHelper.getFieldValue`
  >   resolution, single-Set unwrap vs multi-Set "each inner name"
  >   resolution, multi-param path with collection-first vs scalar-first
  >   ordering, empty-collection null-vs-empty-list contract, missing-field
  >   null handling, 2-arity list result with no unwrap, size-1 unwrap,
  >   non-String key toString() coercion, metadata (name, syntax, min/max
  >   params).
  > - `SQLFunctionIntersectDbTest` (7 tests): covers the DB-dependent
  >   branches of `SQLFunctionIntersect.intersectWith(Iterator, Object)` that
  >   Step 3's standalone suite could not reach — ResultSet of only
  >   Identifiables converts to RidSet (ids branch, value=ids), mixed rows
  >   with non-id first (ids.isEmpty→ else branch, value=nonIds), mixed rows
  >   with id first (`nonIds.addAll(ids)` merge branch), only-non-id rows
  >   yields empty intersection via nonIds.add(result) branch, Result
  >   isIdentifiable() normalization in intersectWith's iterator, aggregation
  >   across identifiable collections. The LinkBag right-hand-side test is
  >   tightly pinned to the deterministic-empty behaviour (RidPair set vs RID
  >   mismatch) with a WHEN-FIXED marker documenting that the production
  >   `case LinkBag` arm is unreachable dead code under the current LinkBag
  >   class hierarchy.
  >
  > No production code was modified.
  >
  > **What was discovered:**
  > - `SQLFunctionIntersect.intersectWith(Iterator, Object)` has a dead
  >   `case LinkBag rids` arm. `LinkBag implements Iterable<RidPair>` but
  >   not `Set` and not `SupportsContains`, so the outer conversion block
  >   calls `MultiValue.toSet(linkBag)` which iterates its `RidPair`
  >   stream into a `HashSet<RidPair>`. Subsequent
  >   `collection.contains(curr.getIdentity())` compares `RidPair` entries
  >   against bare `RID`s — always false — so LinkBag intersections are
  >   deterministically empty. The `case LinkBag rids` branch at
  >   `SQLFunctionIntersect.java:192` is unreachable. WHEN-FIXED: either
  >   hoist `LinkBag` above the outer conversion (so the case fires) or
  >   delete the dead arm.
  > - `ResultInternal.setMetadata` runs `convertPropertyValue` on its
  >   value, which rejects `ArrayDeque` with "Invalid property value for
  >   Result: … - java.util.ArrayDeque". The `$stack` metadata fallback
  >   path must therefore be tested with a `List<Identifiable>` (converted
  >   to `LinkListResultImpl`) rather than arbitrary Collection
  >   implementations. Not a bug — the function still sees a Collection of
  >   RIDs — but an implementation detail worth documenting.
  > - `SQLFunctionTraversedElement`'s positive-branch Identifiable and
  >   TraverseRecordProcess paths both guard `if (entity != null)` before
  >   calling `entity.getImmutableSchemaClass(session)`, but the negative-
  >   branch TraverseRecordProcess path does NOT. Initially hypothesised as
  >   an NPE risk; empirically `transaction.load()` throws
  >   `RecordNotFoundException` (not returns null), so all null-guards are
  >   defensive. Still worth noting as a minor asymmetry; pinned by the
  >   `missingRidInTraverseRecordProcessTargetThrowsRecordNotFound` test.
  > - `Property name has to start with a letter or underscore` — schema
  >   validation rejects numeric property names like "42". The SQLMethod
  >   non-String key coercion test therefore uses `StringBuilder` instead
  >   of `Integer` to exercise the `iParams[0].toString()` path.
  > - `RID.getCollectionId()` (not `getClusterId()` despite the "cluster"
  >   naming elsewhere). Minor API inconsistency.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionTraversedElementTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionTraversedEdgeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionTraversedVertexTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLMethodMultiValueTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/coll/SQLFunctionIntersectDbTest.java` (new)
  >
  > **Critical context:** Dimensional review (5 agents — code-quality,
  > bugs-concurrency, test-behavior, test-completeness, test-structure) ran
  > one iteration, surfaced 0 blockers and ~30 should-fix/suggestion items.
  > Applied the majority of should-fix items in the `Review fix:` commit:
  > LinkBag deterministic-empty tightening with WHEN-FIXED marker; mixed-
  > branch Result ordering regression + symmetric nonIds.addAll merge
  > regression; multipleParams and Set tests renamed and split so names
  > match behaviour; counter-advance contract pinned; Map/empty-
  > collection/collection-first-multiparam/only-nonId ResultSet gaps filled;
  > exact-message pinning on missingStackFromPlainIThis; assertTrue/
  > assertFalse idiom; unused locals removed; field names identity1/2/3
  > match declared Identifiable type; custom contains helper dropped in
  > favour of Collection.contains; class-level Javadoc documents missing-
  > stack contract; anyEdgeRid() helper uses try-with-resources.
  > Deferred: `ctx()`/`stackOf()` DRY extraction across 4 DbTestBase classes
  > (CQ6/TS7) — out-of-scope micro-refactor noted for Track 22 cleanup;
  > minor comment reorder (TS10); non-Identifiable-non-TRP stack entries
  > (TC9) — low-value defensive branch. Iteration 2 (gate check) skipped
  > because the context window reached `warning` (30%) before the check
  > could run; remaining items are informational. Cross-track impact:
  > CONTINUE — Step 4 surfaces a LinkBag dead-code flag that applies only
  > to `SQLFunctionIntersect` (Track 6 internal), not to any downstream
  > track's assumptions. The `ResultInternal.setMetadata` coercion detail
  > is pre-existing behaviour. No upstream assumptions broken for Steps
  > 5–8.

- [x] Step 5: Misc standalone functions + SQLStaticReflectiveFunction
  - [x] Context: info
  > **What was done:** Added 104 unit tests across 11 new standalone test files
  > in `sql/functions/misc` (no DbTestBase, all use `BasicCommandContext`):
  > - `SQLFunctionCountTest` (8): non-null incrementation, null-skip, zero-arg
  >   drift guard (minParams=1 is not enforced by the body), aggregateResults
  >   pin, getResult no-clear, setResult coercion via Number.longValue (incl.
  >   negative-value sign preservation), non-Number ClassCastException pin,
  >   metadata.
  > - `SQLFunctionIfTest` (12): Boolean/String/Number condition-type dispatch,
  >   Number >0 / =0 / <0 intValue truncation, unsupported type → null,
  >   null condition → null, 2-param false-path AIOOBE caught and swallowed to
  >   null, syntax.
  > - `SQLFunctionIfNullTest` (6): 2-arg null/non-null, 3-arg counter-intuitive
  >   replacement semantics (third param is the "non-null replacement", not
  >   the fallback), explicit null replacement honoured, metadata.
  > - `SQLFunctionCoalesceTest` (7): first-non-null-wins, null-null-value,
  >   all-null, single-non-null, single-null, empty-array drift guard
  >   (minParams=1 not enforced).
  > - `SQLFunctionAssertTest` (12): switch dispatch across Boolean/String/
  >   Number/null/default with CommandExecutionException ("Unsupported
  >   condition type: …") pinning; AssertionError-on-false with default
  >   empty-message, explicit 2-arg message, numeric message toString
  >   coercion; covers all -ea paths.
  > - `SQLFunctionUUIDTest` (5): canonical 8-4-4-4-12 lowercase hex regex,
  >   distinct-per-call, aggregateResults(params)=false, getResult=null,
  >   metadata.
  > - `SQLFunctionStrcmpciTest` (11): null/null, null/string, string/null
  >   for both positions, equality, case-insensitive equality, less-than /
  >   greater-than normalization (short- AND long-distance), non-String
  >   first/second param treated-as-null drift guards, both non-String
  >   returns zero (drift guard against hard-cast refactor).
  > - `SQLFunctionSysdateTest` (6): zero-arg returns stored `now`
  >   (assertSame across two calls — the "same date for all iterations"
  >   contract), 2-arg path with explicit UTC timezone (expected ==
  >   SimpleDateFormat equivalent, regex-shape drift guard), format-cache
  >   contract (second call with different pattern/tz returns EXACTLY
  >   firstCall), aggregateResults/getResult, metadata.
  > - `SQLFunctionDecodeTest` (10): base64 String / binary round-trip,
  >   case-insensitive format match (BASE64/Base64/base64), non-String
  >   input via toString (StringBuilder drift guard), invalid base64 →
  >   propagated IAE, empty-string boundary, unknown format → documented
  >   wrong DatabaseException (with WHEN-FIXED marker), null candidate /
  >   null format NPEs.
  > - `SQLFunctionFormatMiscDeadTest` (6): covers the dead `misc.SQLFunctionFormat`
  >   class (only `text.SQLFunctionFormat` is registered by
  >   DefaultSQLFunctionFactory) with a prominent WHEN-FIXED marker in the
  >   class Javadoc requesting deletion of the dead duplicate.
  > - `SQLStaticReflectiveFunctionTest` (21): happy path, arity-based
  >   overload resolution, primitive-weight sort order (int/long/double
  >   with deliberately wrong input order), Integer→double autoboxing,
  >   null arg matches any arity-1 method, String→Object assignability,
  >   sort stability via private-field reflection (assertSame on each
  >   position), 2→3 arity sort order; pickMethod()-via-reflection
  >   negative tests (Integer→boolean, Double→long, Integer→StringBuilder
  >   all return null from pickMethod rather than requiring a session for
  >   QueryParsingException); byte→long, short→int, short→double, char→int,
  >   byte→short, int→float, long→float, float→double widening matrix;
  >   metadata.
  >
  > Dimensional review iteration 1 (5 agents) raised 0 blockers and ~17
  > should-fix items. Applied in the Review-fix commit: replaced weak
  > catch(Throwable)+assertNotNull tests with direct pickMethod reflection
  > calls (TB1/BC3/CQ5/TS4); upgraded vacuous length assertion on Sysdate
  > 2-arg test to exact+regex (TB2/BC1/BC2/TS3); upgraded format-cache
  > test to full-string equality (TB3); tightened UUID to canonical regex
  > (TB4); added negative-value case to Count.setResult (TB9); added
  > exact-message pins for Assert empty-message cases (TB5/TB6) + numeric
  > message toString pin (TC9); added invalid-base64 IAE propagation +
  > empty-string boundary to Decode (TC2); added byte→long, short→double,
  > int→float, long→float widening tests + Integer→StringBuilder
  > incompatibility test via new `numericFloat`/`takesStringBuilder`
  > fixtures (TC4/TC5); replaced Class.getMethods()[0] with explicit
  > Method lookup (TB8/TS1); cleaned up assertEquals(true/false, …) in
  > favour of assertTrue/assertFalse (CQ2/CQ3); simplified
  > new Object[]{null}[0] obfuscation (CQ4).
  >
  > No production code was modified.
  >
  > **What was discovered:**
  > - `misc.SQLFunctionFormat` is dead code: `DefaultSQLFunctionFactory`
  >   imports only `text.SQLFunctionFormat` and registers it under the
  >   NAME "format". The `misc` variant with the same NAME is never
  >   instantiated by production code. Flagged with a prominent Javadoc
  >   WHEN-FIXED marker in the test file — removal should happen in a
  >   future cleanup.
  > - `SQLFunctionSysdate.execute`'s 1-arg path requires
  >   `iContext.getDatabaseSession()` (for `DateHelper.getDatabaseTimeZone`)
  >   so it cannot be tested with a bare `BasicCommandContext`. The
  >   zero-arg and 2-arg (with explicit timezone) paths are fully
  >   covered; the 1-arg path is deferred to Step 6 (DB-required misc
  >   tests).
  > - `SQLFunctionDecode.execute`'s unknown-format branch calls
  >   `new DatabaseException(iContext.getDatabaseSession(), ...)`, which
  >   triggers `BasicCommandContext.getDatabaseSession()`'s
  >   "No database session found in SQL context" DatabaseException
  >   BEFORE the intended message can be constructed. Same contract
  >   mismatch documented in Step 3 (Difference/Intersect). WHEN-FIXED
  >   marker added; fix should also correct the "unknowned" typo.
  > - `SQLStaticReflectiveFunction` QueryParsingException paths
  >   (method==null, ReflectiveOperationException) require a session
  >   for `getDatabaseName()`. Rather than resorting to catch(Throwable),
  >   the review fix added reflection-based `pickMethod` direct invocation
  >   to isolate the isAssignable negative branches cleanly.
  > - Java `Method.invoke` performs JLS-compliant primitive widening on
  >   unboxed values: Character→int, byte→short/int/long/float/double,
  >   short→int/long/float/double, int→long/float/double, long→float/double,
  >   float→double. All of these are exercised via the widening matrix.
  > - `SQLStaticReflectiveFunction`'s constructor sorts `methods` in
  >   place by `(arity ascending, then primitive weight differential
  >   ascending)`; verified via reflection on the private `methods`
  >   field using assertSame at each position.
  > - Java `assert x : message` coerces any Object `message` via
  >   `.toString()` at AssertionError construction — `SQLFunctionAssert`
  >   therefore supports non-String messages (e.g. `42` → message "42").
  >
  > **What changed from the plan:**
  > - Step 5 plan scoped to ~10 test classes; landed exactly 11 test
  >   classes as planned.
  > - Coverage target for `sql/functions/misc` (plan: 53.0% → ~72% this
  >   step, then 85/70 in Step 6) is on track; no early verification
  >   run because Step 8 does the final aggregate check.
  > - No cross-track impact: all discoveries are localised to the misc
  >   subpackage; WHEN-FIXED markers document the dead-code class and
  >   two session-dependency contract mismatches for a future cleanup
  >   pass (potentially Track 22).
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionCountTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionIfTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionIfNullTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionCoalesceTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionAssertTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionUUIDTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionStrcmpciTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionSysdateTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionDecodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionFormatMiscDeadTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLStaticReflectiveFunctionTest.java` (new)
  >
  > **Critical context:** Dimensional review (5 agents — code-quality,
  > bugs-concurrency, test-behavior, test-completeness, test-structure)
  > ran one iteration, surfaced 0 blockers and ~17 should-fix/suggestion
  > items. Applied the high-value set in the Review-fix commit.
  > Deferred: shared `ctx()` / `new BasicCommandContext()` DRY extraction
  > (CQ1/TS6) — same micro-refactor noted in Step 4, carried to Track 22.
  > Cross-track impact: CONTINUE — no upstream assumption broken for
  > Steps 6-8; the `misc` dead-code `SQLFunctionFormat` and contract
  > mismatches in `Decode`/`StaticReflectiveFunction` unknown-method
  > paths are Track 6 internal. Step 6 will cover the DB-required misc
  > portion (SQLFunctionDate, SQLFunctionEncode, SQLFunctionEval,
  > SQLFunctionIndexKeySize, SQLMethodExclude/Include, SQLFunctionThrowCME,
  > SQLFunctionSequence, SQLFunctionDistance) + sequence/geo subpackages.

- [x] Step 6: Misc DB-required + SQLFunctionThrowCME + SQLMethodExclude/Include + SQLFunctionEval + Sequence/Distance
  - [x] Context: warning
  > **What was done:** Added 116 unit tests across 9 test files (8 new, 1
  > extended) covering the DB-required remainder of `sql/functions/misc` plus
  > the `sequence` and `geo` subpackages.
  > - `SQLFunctionDateTest` (15 tests, DbTestBase, new): zero-arg stored-Date
  >   stability (assertSame), wall-clock monotonicity + 30s bound, null first
  >   param → null, Long/Integer epoch-ms → new Date(), 1/2/3-arg string paths
  >   with explicit UTC timezone (cross-TZ 9h delta between UTC and Asia/Tokyo),
  >   format caching (cache is lazily set and reused across calls even if
  >   pattern changes — second call with unmatched pattern throws
  >   QueryParsingException with cached-pattern + bad-input message content),
  >   getResult clears the cached format and returns null, aggregateResults
  >   false, two instances capture distinct Dates (assertNotSame).
  > - `SQLFunctionEncodeTest` (12 tests, DbTestBase, new): byte[] →
  >   base64 round-trip with empty-array boundary, case-insensitive format
  >   (BASE64/Base64/base64), non-String format via toString, RID→Blob happy
  >   path (session.newBlob + commit + reload), RID→non-Blob returns null
  >   (pins the `if (rec instanceof Blob)` gate), missing RID → null
  >   (RecordNotFoundException caught), SerializableStream candidate with
  >   test-only `FixedBytesStream`, unrecognized String candidate → null,
  >   null candidate → null. Unknown format throws DatabaseException with
  >   the "unknowned format :" typo pinned (WHEN-FIXED marker).
  > - `SQLFunctionEvalTest` (10 tests, DbTestBase, new): literal arithmetic
  >   "1 + 1" → 2, "3 * 4" → 12, String.valueOf coercion via StringBuilder,
  >   predicate caching (first-parse-wins — second call with different
  >   expression returns the SAME cached evaluation), division-by-zero
  >   observed as Boolean.FALSE (NOT ArithmeticException → 0 because
  >   SQLPredicate parses "10 / 0" as a predicate; pin this so a behavioural
  >   change is noticed), non-EntityImpl currentResult falls back without
  >   ClassCastException, empty-iParams CommandExecutionException gate,
  >   metadata.
  > - `SQLFunctionThrowCMETest` (9 tests, DbTestBase, new): happy path pins
  >   CME fields (rid, databaseVersion, recordVersion) and message content
  >   ("UPDATE", "#17:42"), DELETED operation produces startsWith("Cannot
  >   DELETE the record #7:9"), negative-dbVersion wording "does not exist",
  >   non-RID/non-Integer/Long casts trigger ClassCastException with
  >   exact-class (not subclass) pin (WHEN-FIXED: validate types before
  >   cast), metadata.
  > - `SQLFunctionSequenceTest` (12 tests, DbTestBase, new): happy path
  >   (assertSame DBSequence), unknown sequence → CEE with "Sequence not
  >   found: DOES_NOT_EXIST", mixed-case "mySeq" still resolves (pinning
  >   internal toUpperCase), configured SQLFilterItem "old stuff" branch
  >   overrides iParams, configured non-FilterItem falls back to iParams,
  >   empty configuredParameters falls back, non-String iParam via toString,
  >   null iParam → "null" literal name, FilterItem returning null crashes
  >   with NullPointerException (latent bug — ConcurrentHashMap.get(null)
  >   from SequenceLibraryImpl fires BEFORE the intended CEE; WHEN-FIXED
  >   marker), metadata.
  > - `SQLFunctionDistanceTest` (14 tests, DbTestBase, new): identical
  >   points → 0km, London→Paris ≈343.5km Haversine reference (2km
  >   tolerance), symmetry (a→b == b→a), km multiplier no-op matches 4-arg,
  >   mi multiplier 0.621371192, nmi multiplier 0.539956803, case-insensitive
  >   unit matching (KM/Mi/NMI), unknown unit throws IAE with exact message,
  >   null coordinates in each of 4 positions → null short-circuit, Integer
  >   (0,0,0,0) and Long (0,0,45,90) inputs coerced via PropertyTypeInternal,
  >   StringBuilder unit toString, metadata.
  > - `SQLMethodExcludeTest` (18 tests, DbTestBase, new): null iThis → null,
  >   unrecognised (non-Entity/Map/MultiValue) → null, literal field removal,
  >   multi-field removal, non-existent field no-op, empty iParams → full
  >   copy, null field name skipped (iFieldName != null guard), "prefix*"
  >   wildcard removes matching, single-star "*" removes all, RID load +
  >   exclude (commit + reopen + load), missing RID → null, Result →
  >   asEntity + exclude, Map iThis literal + wildcard, multi-value
  >   Identifiable list + exclude from each, missing RID inside multi-value
  >   silently dropped, non-Identifiable multi-value entries skipped,
  >   metadata.
  > - `SQLMethodIncludeTest` (17 tests, DbTestBase, new): null iParams[0] →
  >   null (DIFFERENT from Exclude which tests iThis==null), unrecognised
  >   iThis → null, literal include, multi-include, non-existent field →
  >   null property, null-name-in-list skipped (note: iParams[0] must be
  >   non-null for outer guard), wildcard-key quirk pin ("addr_*" matches
  >   store under literal "addr_*" key, NOT under original property names —
  >   latent bug, WHEN-FIXED marker), Identifiable load + include, missing
  >   RID → null, Result → asEntityOrNull + include, Map literal + wildcard
  >   (same quirk), empty field name ("") boundary, multi-value list + each
  >   included, missing RID inside multi-value skipped, non-Identifiable
  >   skipped, metadata.
  > - `SQLFunctionIndexKeySizeTest` (9 tests, DbTestBase, extended): the
  >   existing SQL-parser happy path retained; added direct-call unique
  >   index (3 distinct keys), non-unique index with duplicates (distinct
  >   count only), empty index → 0L boundary, empty-string name → null,
  >   unknown index → null, null input (String.valueOf → "null") → null,
  >   non-String via String.valueOf coercion, metadata. Direct-call tests
  >   open a local begin/rollback because `index.stream(session)` requires
  >   an active transaction.
  >
  > No production code was modified. All tests use the AbstractSQLMethod
  > `execute(iThis, iCurrentRecord, iContext, ioResult, iParams)` signature
  > (context 3rd, NOT 5th) for SQLMethod* classes, per Track 6 convention.
  >
  > **What was discovered:**
  > - **Schema changes are not transactional** (confirmed again from Step 2).
  >   Tests that need schema classes must create them BEFORE `session.begin()`
  >   — otherwise "Cannot change the schema while a transaction is active"
  >   fires. Pattern locked in the `@Before setUp()` of every new DbTestBase
  >   test in Step 6.
  > - **`index.stream(session)` in SQLFunctionIndexKeySize requires an
  >   active transaction** (`session.getActiveTransaction()` is called from
  >   `IndexOneValue.stream`). Direct-call tests must wrap in a local
  >   begin/rollback. Only the `unknown index` and `null input` paths are
  >   tx-free because they short-circuit before calling `.stream()`.
  > - **`SequenceLibraryImpl.getSequence(null)` throws NullPointerException**
  >   (from `ConcurrentHashMap.get(null)`) rather than returning null. A
  >   FilterItem that yields null therefore crashes the function with a
  >   naked NPE, never reaching the `CommandExecutionException("Sequence
  >   not found: null")` branch. Latent bug — pinned with WHEN-FIXED marker.
  > - **SQLPredicate evaluates "10 / 0" as Boolean.FALSE, not an arithmetic
  >   expression** — the predicate treats binary operators as boolean
  >   conditions. This means the `catch (ArithmeticException) return 0;`
  >   branch of SQLFunctionEval is effectively unreachable from public SQL
  >   inputs. Test pins the observed Boolean.FALSE contract; a refactor
  >   that routes to the catch would fail this test and flag the behavioural
  >   change.
  > - **SQLFunctionEval's `new SQLPredicate(...)` is outside its try-catch**,
  >   so SQLPredicate constructor parse errors propagate rather than being
  >   swallowed to null by the generic `catch (Exception)`. We don't test
  >   this directly because deliberately malformed inputs often parse
  >   cleanly; the production contract ("parse errors propagate") is
  >   documented in the class-level Javadoc.
  > - **SQLFunctionDate's format cache has no per-pattern key** — once set,
  >   the cached SimpleDateFormat is reused for all subsequent inputs.
  >   Feeding a second call with a mismatching pattern throws
  >   QueryParsingException (wrapped from a ParseException on the cached
  >   format). `getResult()` is the only reset mechanism.
  > - **SQLMethodInclude's wildcard-key branch stores every match under the
  >   wildcard string** (`result.setProperty(fieldName, ...)` where
  >   `fieldName` is e.g. `"addr_*"`) instead of the matched property name.
  >   All matches overwrite the same key, keeping only the last iteration's
  >   value. Latent bug — pinned with WHEN-FIXED marker in both the
  >   EntityImpl and Map paths.
  > - **SQLFunctionThrowCME's `(RecordIdInternal) iParams[0]` and
  >   `(int) iParams[1]/[2]/[3]` casts have no type validation** — a non-RID
  >   first param or non-Integer db/record/operation param crashes with
  >   ClassCastException rather than a helpful CommandExecutionException.
  >   WHEN-FIXED markers pin all three casts.
  > - **SQLFunctionEncode's unknown-format DatabaseException has the typo
  >   "unknowned format :"** (should be "unknown format:"). WHEN-FIXED
  >   marker pins the typo verbatim.
  >
  > **What changed from the plan:**
  > - The review-fix pass removed two weak/misnamed tests and replaced them
  >   with tighter contract pins:
  >   * `arithmeticDivisionExpressionDoesNotThrow` (vacuous any-type check)
  >     → `divisionByZeroExpressionIsSwallowedToBooleanOrNumber` with
  >     `assertEquals(Boolean.FALSE, result)`.
  >   * `currentResultEntityImplIsPassedToPredicate` (name contradicted what
  >     the test actually exercised — the FALSE branch of instanceof) →
  >     `currentResultNonEntityImplFallsBackWithoutClassCast`.
  > - The review-fix also added 5 missing tests: `zeroArgIsClose_To…` was
  >   renamed for Java convention AND gained a monotonicity check;
  >   `sequenceLookupAcceptsMixedCaseName` replaced a duplicate that passed
  >   uppercase input; `configuredSqlFilterItemReturningNull…` surfaced the
  >   NPE latent bug; `nullCandidateFirstParamReturnsNull` for Encode;
  >   `emptyIndexReturnsZero` / `emptyStringIndexNameReturnsNull` for
  >   IndexKeySize; `emptyParamsReturnsFullCopy` for Exclude.
  > - Deferred (low-value or cross-track micro-refactors carried to
  >   Track 22):
  >   * Helper extraction `call(Object...)` across Distance/Eval/Date
  >     (CQ2/CQ3).
  >   * Abstract base for SQLMethodExclude/Include shared fixtures
  >     (CQ5/TS4).
  >   * `rollbackIfLeftOpen` helper hoisted into DbTestBase (TS5).
  >   * Valid cluster-id usage in missing-RID tests (BC4 — current
  >     RecordId(999,999) works but is engine-dependent).
  >   * Targeted Eval catch-branch coverage via reflection/mocking (TC1).
  >   * Blob-in-multivalue CCE pin for Exclude (TC3 — low value).
  >   * Antipodal + null-unit distance tests (TC4 — low value).
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionDateTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionEncodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionThrowCMETest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLMethodExcludeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLMethodIncludeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/misc/SQLFunctionIndexKeySizeTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionEvalTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/sequence/SQLFunctionSequenceTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/geo/SQLFunctionDistanceTest.java` (new)
  >
  > **Critical context:** Dimensional review (5 agents — code-quality,
  > bugs-concurrency, test-behavior, test-completeness, test-structure) ran
  > one iteration, surfaced 0 blockers and ~20 should-fix/suggestion items.
  > Applied the high-value should-fix set in the Review-fix commit
  > (misleading names, vacuous assertions, dead placeholders, tightened
  > exception-class pins, added missing boundary/null tests). Iteration 2
  > (gate check) skipped because the context window reached `warning` (33%)
  > after Iteration 1. Cross-track impact: CONTINUE — all discoveries
  > localised to Track 6 classes (SQLFunctionSequence NPE-vs-CEE contract
  > mismatch, SQLMethodInclude wildcard-key bug, SQLFunctionThrowCME
  > unchecked casts, SQLFunctionEncode typo, SQLFunctionEval catch-branch
  > unreachability). No upstream assumptions broken for Steps 7-8. Steps
  > 7-8 target sql/functions/math accumulators and sql/functions/text +
  > conversion SQLMethod*, respectively, which are independent surfaces.

- [x] Step 7: Math functions + stat gaps + final math cleanup
  - [x] Context: warning
  > **What was done:** Added 162 unit tests across 13 test files (7 new,
  > 6 extended) covering `sql/functions/math` accumulators +
  > `SQLFunctionMathAbstract` helpers + `abs/interval/decimal` residual
  > branches, and `sql/functions/stat` gap-fills for `median`, `stddev`,
  > plus `percentile`/`variance`/`mode` MultiValue / non-Number / syntax
  > paths.
  >
  > - `SQLFunctionSumTest` (14 tests, new): zero-sum sentinel, single-arg
  >   Number / MultiValue / non-supported fall-through, multi-arg reset
  >   semantics, Integer+Long promotion, BigDecimal propagation, Short
  >   overflow widening to Integer, empty Collection keeps zero sentinel,
  >   aggregateResults config contract, getSyntax. Pins the production
  >   bug where `PropertyTypeInternal.increment` on Integer+Integer
  >   overflow returns `(long) Integer.MIN_VALUE` (type promoted, value
  >   wrong) — WHEN-FIXED token embedded in the test body.
  > - `SQLFunctionAverageTest` (15 tests, new): empty→null (divide-by-
  >   zero sentinel), per-type division (Integer/Long/Float/Double/
  >   BigDecimal + HALF_UP), Collection unwrap with null filter,
  >   non-Number/non-MultiValue ignore, aggregateResults config contract,
  >   getSyntax. Pins the multi-arg total-not-reset bug (WHEN-FIXED),
  >   plus pinning tests for single-Short and single-BigInteger input
  >   returning null because `computeAverage` has no Short/BigInteger
  >   branches (WHEN-FIXED markers).
  > - `SQLFunctionMaxTest` (13 tests, new): aggregate mode across rows,
  >   Integer→Long context promotion, all-null rows stay null, per-row
  >   mode (config length ≠ 1) returns per-row max, Collection scan with
  >   null filter, Date comparison in per-row AND aggregate paths (non-
  >   Number guard), empty Collection, `$current` LET-definition guard
  >   disables aggregation, aggregated global max across Collection rows,
  >   getSyntax.
  > - `SQLFunctionMinTest` (13 tests, new): symmetrical mirror of Max
  >   (same matrix with reversed comparator).
  > - `SQLFunctionMathAbstractTest` (18 tests, new): `getContextValue`
  >   identity-short-circuit, Integer → Long/Short/Float/Double
  >   conversions, unhandled target fall-through, Float→Long truncation,
  >   Double→Short silent overflow, null context NPE (precondition pin);
  >   `getClassWithMorePrecision` precision lattice (Integer/Long/Float
  >   beaten by higher, same-class short-circuit, unhandled first class
  >   fall-through, Double-first keeps itself, BigDecimal-first keeps
  >   itself); aggregateResults default; getName inherited from
  >   constructor. Test-only `TestMathSubclass` exposes the protected
  >   helpers. Javadoc now flags both helpers as unreferenced-by-
  >   production (WHEN-FIXED: candidate for removal or integration into
  >   Max/Min accumulation path).
  > - `SQLFunctionAbsoluteValueTest` (20 tests, 2 new): added getSyntax
  >   and aggregateResults-always-false (with config guard) — previously
  >   uncovered.
  > - `SQLFunctionIntervalTest` (8 tests, 5 new): aggregateResults
  >   always false, execute-return-value separate from getResult
  >   (strengthened), getSyntax, strict `>` semantics (equal bound does
  >   not match), null-bound NPE precondition (WHEN-FIXED).
  > - `SQLFunctionDecimalTest` (16 tests, 11 new; kept in `stat/` pkg
  >   with WHEN-FIXED relocation note): BigDecimal identity pass-
  >   through, BigInteger promotion, Short/Float/Double Number branch,
  >   malformed String swallowed to null, null/unsupported-type fall-
  >   through, aggregateResults, getSyntax; plus a pinning test for
  >   unsupported-type-after-valid-input leaking the prior BigDecimal
  >   (WHEN-FIXED: reset `result` at start of execute).
  > - `SQLFunctionMedianTest` (8 tests, new): empty → null, single
  >   value, odd/even count interpolation, Collection with nulls,
  >   multi-row accumulation (correctly named + commented), getSyntax,
  >   aggregateResults.
  > - `SQLFunctionStandardDeviationTest` (6 tests, new): empty/n≤1
  >   guard (evaluate `variance == null`), known-sample sqrt of
  >   variance, two-sample boundary (n=2), getSyntax, aggregateResults.
  > - `SQLFunctionPercentileTest` (14 tests, 5 new): MultiValue
  >   unwrap + null filter, non-Number ignored, empty-collection +
  >   multi-quantile path, aggregateResults, getSyntax.
  > - `SQLFunctionVarianceTest` (9 tests, 5 new): MultiValue unwrap,
  >   empty-collection null, non-Number ignored, aggregateResults,
  >   getSyntax.
  > - `SQLFunctionModeTest` (8 tests, 3 new): single-null ignored,
  >   empty-collection, getSyntax, aggregateResults.
  >
  > No production code was modified.
  >
  > **What was discovered:**
  > - **Production bug #1**: `PropertyTypeInternal.increment` applies the
  >   `(long) ...` cast to the already-overflowed `int` result, so
  >   Integer.MAX_VALUE + 1 as Sum produces a Long whose value is
  >   Integer.MIN_VALUE. Pinned (WHEN-FIXED) in
  >   `SQLFunctionSumTest.integerOverflowPromotesResultTypeToLongButLosesValue`.
  > - **Production bug #2**: `SQLFunctionAverage.execute()` multi-arg
  >   branch resets `sum = null` but not `total = 0`. The second
  >   `execute([a,b,c])` on the same instance produces an average
  >   polluted by the prior row's count. Pinned (WHEN-FIXED) in
  >   `multiArgExecuteResetsSumButNotTotalBetweenCalls`.
  > - **Production bug #3**: `SQLFunctionAverage.computeAverage` has no
  >   Short or BigInteger branches — single-value inputs of those types
  >   fall through to `return null`. Pinned (WHEN-FIXED) in two tests.
  > - **Production bug #4**: `SQLFunctionDecimal.execute()` does not
  >   reset the `result` field at the start of each call, so an
  >   unsupported-type input after a valid input leaks the prior
  >   BigDecimal. Pinned (WHEN-FIXED) in
  >   `testUnsupportedTypeAfterValidInputLeaksPreviousResult`.
  > - **Test-file location anomaly**: `SQLFunctionDecimalTest.java`
  >   physically lives in `sql/functions/stat/` but tests a class in
  >   `sql/functions/math/`. JaCoCo attributes it to the wrong package
  >   for the purposes of test-class-per-package conventions. WHEN-FIXED
  >   note added to the file Javadoc; actual relocation left for a
  >   follow-up.
  > - **Dead helpers**: `SQLFunctionMathAbstract.getContextValue` and
  >   `getClassWithMorePrecision` are `protected` but never called by
  >   any concrete subclass or other code in the module. They carry
  >   deprecated `new Float(...)` / `new Double(...)` constructors. Left
  >   covered via `TestMathSubclass` with a WHEN-FIXED note in the test
  >   Javadoc.
  >
  > **What changed from the plan:**
  > - Coverage of `sql/functions/math` now includes explicit null-context
  >   / non-Integer-source paths for `getContextValue` (plan only said
  >   "cover via concrete subclass + one test-only subclass if needed").
  > - `SQLFunctionDecimalTest` was extended rather than rewritten —
  >   avoided creating a duplicate under `sql/functions/math/`.
  > - Step file listed `SQLFunctionMedian`/`SQLFunctionStandardDeviation`
  >   as the primary stat targets; plan hit them plus filled
  >   Percentile/Variance/Mode gaps that were not named but surfaced in
  >   the JaCoCo method-level scan (MultiValue branches + getSyntax
  >   lines).
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionSumTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionAverageTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMaxTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMinTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionMathAbstractTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionAbsoluteValueTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/math/SQLFunctionIntervalTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/stat/SQLFunctionMedianTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/stat/SQLFunctionStandardDeviationTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/stat/SQLFunctionPercentileTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/stat/SQLFunctionVarianceTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/stat/SQLFunctionModeTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/stat/SQLFunctionDecimalTest.java` (modified)
  >
  > **Critical context:** Step-level dimensional review ran one iteration
  > (4 agents: code-quality, bugs-concurrency, test-behavior,
  > test-completeness). Findings: 0 blockers, 6 unique should-fix (after
  > deduplication), ~12 suggestions. Applied all should-fix plus most
  > suggestions in the `Review fix:` commit (162 tests total, up from
  > 146 in the initial Step 7 commit). Iteration 2 (gate check) skipped
  > because context reached `warning` after Iteration 1 — deferred to
  > the track-level Phase C review. Full core test suite: 1176 tests,
  > 0 failures, 0 errors. No cross-track impact: all discoveries are
  > localised to `sql/functions/math` and `sql/functions/stat`. Step 8
  > targets `sql/functions/text`/`conversion`/`result` SQLMethod* —
  > independent of math/stat.

- [x] Step 8: Text SQLMethod* + conversion SQLMethod* + result + final verification
  - [x] Context: warning
  > **What was done:** Added 162 unit tests across 14 new test files covering
  > `sql/functions/text` (9 files), `sql/functions/conversion` (4 files), and
  > `sql/functions/result` (1 file).
  >
  > - `SQLMethodAppendTest` (11 tests): null-subject / null-first-param early
  >   exits (assertSame identity pin via `new String()`), single-arg, empty
  >   strings, multi-arg in order (buffer seeded with iThis only), interleaved
  >   null entries skipped, non-String toString coercion, metadata.
  > - `SQLMethodLengthTest` (6 tests): null→0, empty→0, ASCII count, Integer
  >   coercion via toString, surrogate-pair counts as 2 UTF-16 code units
  >   (pins String.length semantics), metadata.
  > - `SQLMethodReplaceTest` (10 tests): pins the three null-short-circuit
  >   asymmetries (null subject leaks needle out; null replacement leaks
  >   needle out; only null needle yields null) with WHEN-FIXED markers;
  >   replace-all non-regex semantics; $/\\ literal; empty replacement =
  >   delete-all; non-String coercion; metadata.
  > - `SQLMethodRightTest` (13 tests): null early exits; tail slice; offset
  >   ==length and offset>length return whole string; offset 0 returns empty;
  >   negative offset throws StringIndexOutOfBounds (WHEN-FIXED); non-numeric
  >   param NumberFormatException; Integer coercion; metadata.
  > - `SQLMethodSubStringTest` (19 tests): ALL eight clamp branches across
  >   the 1-param and 2-param paths (from<0, from>=length, to>length,
  >   to<=from, to==from, from==to==0, negative; 1-param from==length → "");
  >   null-iParams[1] NPE pin (WHEN-FIXED); String-digit parsing;
  >   non-numeric NFE; non-String iThis coercion; metadata.
  > - `SQLMethodHashTest` (11 tests): null-iThis short-circuit; default
  >   SHA-256 for non-empty + empty; explicit SHA-256 / MD5 / SHA-1 via
  >   SecurityManager.createHash; non-String iThis/algorithm toString
  >   coercion; unknown algorithm wraps NoSuchAlgorithmException into
  >   CommandExecutionException with message-and-cause assertions;
  >   null-algorithm NPE pin (WHEN-FIXED); metadata.
  > - `SQLMethodToJSONTest` (16 tests): null short-circuit; entity with/
  >   without format overload dispatch (assertNotEquals + property-
  >   exclusion pins); quote-stripping equality pin (`"\"rid\""` normalises
  >   to `"rid"`); Result-wrapping-entity `asEntity()` unwrap; non-entity
  >   Result fall-through to null; Map via JSONSerializerJackson;
  >   MultiValue List, array, empty, and nested-list recursion; "null"
  >   literal appended for unrecognised MultiValue entry (WHEN-FIXED);
  >   non-matching fall-through; null-format NPE pin (WHEN-FIXED);
  >   non-String format ClassCastException pin; metadata.
  > - `SQLFunctionConcatTest` (10 tests): aggregateResults=true;
  >   execute-returns-null on every call; getResult-before-execute=null;
  >   single-arg and two-arg aggregation (delim read per call, not cached);
  >   empty-string delim no-op; null element appends "null"; non-String
  >   coercion; metadata.
  > - `SQLFunctionFormatTest` (10 tests): single/multi-arg formats
  >   (locale-independent %s/%d only); pattern-without-specifiers;
  >   MissingFormatArgumentException; IllegalFormatConversionException;
  >   null pattern NPE; aggregateResults=false; registration cross-check
  >   via DefaultSQLFunctionFactory.getFunctions() asserts the TEXT
  >   variant is bound to "format" (not the dead misc duplicate);
  >   metadata.
  > - `SQLMethodAsDateTest` (10 tests): null; Date + Number (Long/Integer)
  >   zeroing; String branch via DateHelper round-trip; unparseable string
  >   → null (swallowed ParseException); toString-coerced non-String iThis
  >   lossless round-trip via the DB format (replaces a tautological
  >   assertion); numeric-input-in-PM-window lands on 12:00 (noon), not
  >   00:00 (midnight) — WHEN-FIXED pin for the production Calendar.HOUR
  >   vs HOUR_OF_DAY latent bug; metadata.
  > - `SQLMethodAsDateTimeTest` (7 tests): null; Date returned as same
  >   instance (assertSame no-defensive-copy pin, WHEN-FIXED); Long/Integer
  >   full epoch-ms preservation (no HMS zeroing); String via DateHelper
  >   round-trip; unparseable → null; metadata.
  > - `SQLMethodAsDecimalTest` (13 tests): null; Date epoch-ms via
  >   `new BigDecimal(long)`; epoch-zero scale-stable (`new BigDecimal(0L)`
  >   rather than `BigDecimal.ZERO` so a valueOf refactor is visible);
  >   Integer/Long/Double/BigDecimal toString paths; whitespace trim;
  >   non-numeric and whitespace-only NumberFormatException; metadata.
  > - `SQLMethodConvertTest` (16 tests): null iThis / null first param;
  >   Java-class path (`java.lang.Integer`, `Long`, `BigDecimal`), unknown
  >   Java class returns null via caught CNFE; PropertyTypeInternal path
  >   (`INTEGER`, `LONG`, `STRING`, `DOUBLE`); case-insensitive resolution;
  >   unknown type-name IllegalArgumentException with non-null-message +
  >   name-substring assertion (strengthened from earlier-permissive
  >   disjunction); same-type no-op; non-String param toString coercion;
  >   unconvertible String→INTEGER wrapped DatabaseException with
  >   message-and-target-type pins; metadata.
  > - `SQLFunctionDetachResultTest` (7 tests): null record raises
  >   CommandSQLParsingException with "detach()"/"NULL was found" message
  >   pins; non-null record returns Result with preserved properties;
  >   assertNotSame-pinned fresh-instance contract (ResultInternal.detach
  >   always constructs a new Result); entity-wrapping Result detach
  >   materialises observable entity properties; aggregateResults=false;
  >   getResult=null; metadata.
  >
  > No production code was modified.
  >
  > **What was discovered:**
  > - **Calendar.HOUR (AM/PM) vs HOUR_OF_DAY bug in `SQLMethodAsDate`**:
  >   production uses `cal.set(Calendar.HOUR, 0)` — the AM/PM 0-11 hour —
  >   not `HOUR_OF_DAY` (0-23). A PM input stays PM, producing 12:00 (noon)
  >   rather than 00:00 (midnight). Pinned by
  >   `numericInputInPmWindowLandsOnNoonNotMidnightPerCalendarHourBug`
  >   (WHEN-FIXED). The test uses an `AM_PM == PM` guard so it doesn't
  >   fail on runners whose default TZ happens to push the wall-clock
  >   back into AM.
  > - **Asymmetric null contracts in `SQLMethodReplace`**: null subject
  >   returns the needle (not null); null replacement returns the needle
  >   (not null or the input); only null needle yields null. Three
  >   WHEN-FIXED pins.
  > - **NPE paths on null params**: `SQLMethodSubString.execute(subject,
  >   from, null)` and `SQLMethodHash.execute(subject, null)` and
  >   `SQLMethodToJSON.execute(entity, null)` all NPE rather than returning
  >   null or falling back — early-exit guards check only the first param.
  >   Three WHEN-FIXED pins.
  > - **`SQLMethodToJSON` appends literal "null"** when a MultiValue entry
  >   falls through the inner dispatch — StringBuilder.append(null) emits
  >   the 4-char `"null"`. Pinned via `[null]` expected output.
  > - **`SQLMethodAsDateTime` returns `Date` input by reference**, not a
  >   defensive copy (assertSame pin) — callers mutating the returned Date
  >   affect the source.
  > - **Unconvertible-value path in `SQLMethodConvert`** throws wrapped
  >   `DatabaseException` (NOT the raw underlying `NumberFormatException`),
  >   with a message naming both the bad value and the target class. Pinned.
  > - **`SQLMethodToJSON` requires the entity's RID to be persisted** —
  >   `toJSON()` dereferences linked RIDs during serialisation. Without a
  >   commit, the entity carries a temporary cluster position (`#N:-2`) and
  >   serialisation throws. The test helper commits + reloads to obtain a
  >   persistent RID.
  > - **`ResultInternal.detach()` always constructs a fresh Result**, never
  >   returning `this`. An earlier Javadoc assertion ("returns the same
  >   instance per ResultInternal semantics") was wrong; corrected and
  >   pinned with `assertNotSame`.
  > - **SQLFunctionConcat is registered as `.class` (not instance)** in
  >   `DefaultSQLFunctionFactory`, so each createFunction call returns a
  >   fresh accumulator — state is per-query, not per-JVM. The TEXT variant
  >   of `SQLFunctionFormat` is registered as an instance and is the one
  >   dispatched for the name "format" (verified via
  >   `DefaultSQLFunctionFactory.getFunctions()` lookup).
  > - **`SimpleDateFormat` cannot be shared across tests via a static helper
  >   without an explicit timezone** — Track 6 convention mandates a
  >   deterministic timezone; this step uses `DateHelper.getDateFormatInstance
  >   (session)` for all DB-format round-trips rather than hand-rolled
  >   SimpleDateFormat, avoiding locale-dependent flake.
  >
  > **What changed from the plan:**
  > - Plan bullet for `SQLMethodToJSON` anticipated "check if session needed
  >   for entity serialization" — confirmed: entity path requires commit +
  >   reload so the RID is persistent. This drove the `thing()` helper
  >   pattern.
  > - Plan did not call out MultiValue array / empty / nested coverage —
  >   added during the review-fix pass after test-completeness flagged the
  >   List-only coverage. All three new tests pass.
  > - Plan's "dead duplicate" tag for `misc.SQLFunctionFormat` was already
  >   covered in Step 5 via `SQLFunctionFormatMiscDeadTest`; Step 8's
  >   `SQLFunctionFormatTest` adds the cross-check against
  >   `DefaultSQLFunctionFactory.getFunctions()` showing that the text
  >   variant (not the misc duplicate) is bound to "format".
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/text/SQLMethodAppendTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/text/SQLMethodLengthTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/text/SQLMethodReplaceTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/text/SQLMethodRightTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/text/SQLMethodSubStringTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/text/SQLMethodHashTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/text/SQLMethodToJSONTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/text/SQLFunctionConcatTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/text/SQLFunctionFormatTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/conversion/SQLMethodAsDateTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/conversion/SQLMethodAsDateTimeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/conversion/SQLMethodAsDecimalTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/conversion/SQLMethodConvertTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/functions/result/SQLFunctionDetachResultTest.java` (new)
  >
  > **Critical context:** Step-level dimensional review ran one iteration
  > with 5 agents (code-quality, bugs-concurrency, test-behavior,
  > test-completeness, test-structure). Findings: 0 blockers, ~15 unique
  > should-fix (after deduplication across agents), ~20 suggestions.
  > Applied all should-fix items plus the highest-value missing-coverage
  > suggestions (MultiValue array/empty/nested, non-entity-Result
  > fall-through, entity-wrapping DetachResult, null-param NPE pins,
  > NoSuchAlgorithm cause-chain) in the `Review fix:` commit. Remaining
  > suggestions deferred to track-level Phase C (TS5 split DetachResult
  > contract-vs-DB tests, TC7 `java.sql.Timestamp` subclass,
  > TS8 SHA-256 length constant, BC6 known-hex empty-SHA-256,
  > TB10 exact-JSON mapReturnsMapToJson). Iteration 2 (gate check) skipped
  > because context reached `warning` after Iteration 1 — the track-level
  > Phase C dimensional review (next session) will verify the review
  > fixes and check for regressions. **Coverage verification deferred to
  > Phase C** — the per-track `./mvnw -P coverage` run and the analyzer/
  > gate scripts are mandatory but heavy; running them in this session
  > would push context beyond `critical`. Step 8's test count (162) is
  > the full scope planned for text/conversion/result subpackages.
  > Cross-track impact: CONTINUE — all discoveries localised to
  > `sql/functions/{text,conversion,result}`; no upstream assumption
  > broken for Track 7+.

### Step 1: Factory infrastructure + graph traversal dispatchers + SQLGraphNavigationFunction interface (original plan retained for reference)

- **Target packages**: `sql/functions` (root), `sql/functions/graph` (dispatchers only)
- **Target classes**:
  - `SQLFunctionFactoryTemplate`, `DefaultSQLFunctionFactory`, `CustomSQLFunctionFactory` — register/createFunction/hasFunction/getFunctionNames paths
  - Graph navigation dispatchers: `SQLFunctionOut`, `SQLFunctionIn`, `SQLFunctionBoth`, `SQLFunctionOutE`, `SQLFunctionInE`, `SQLFunctionBothE`, `SQLFunctionOutV`, `SQLFunctionInV`, `SQLFunctionBothV` — cover `move()` dispatch for each direction variant
  - `SQLFunctionMove` / `SQLFunctionMoveFiltered` — dispatch plumbing, label parsing (non-supernode)
  - `SQLGraphNavigationFunction.propertyNamesForIndexCandidates` + static helpers (`propertiesForV2ENavigation`, `propertiesForV2VNavigation`)
- **Test class strategy**: 
  - `SQLFunctionFactoryTemplateTest` — standalone, covers register + createFunction (instance + Class variants) + error paths (needs DbTestBase for exception messages)
  - `DefaultSQLFunctionFactoryTest` — DbTestBase, verifies all expected function names are registered
  - `SQLFunctionOutInBothTest` (parameterized by direction) — DbTestBase graph with vertex + edge classes
  - `SQLFunctionGraphEdgeVariantsTest` — covers OutE/InE/BothE/OutV/InV/BothV (can share vertex fixture)
  - `SQLGraphNavigationFunctionTest` — standalone tests for the two static helpers + a DbTestBase test for `propertyNamesForIndexCandidates` with a real SchemaClass
- **Expected coverage delta**: `sql/functions` root 70.4% → ~85%; `sql/functions/graph` ~53% → ~65% (dispatchers only; algorithms in step 2)

### Step 2: Graph algorithm functions (ShortestPath / Dijkstra / Astar) + HeuristicFormula + MoveFiltered supernode path

- **Target packages**: `sql/functions/graph` (remaining algorithms)
- **Target classes**:
  - `SQLFunctionShortestPath` — uncovered `edge=true` walk path (walkLeft lines 360–393, walkRight 427–460)
  - `SQLFunctionDijkstra` — null direction branch, no-path-found edge case, edge-weight-property path
  - `SQLFunctionAstar` — `HeuristicFormula.CUSTOM` branch (requires stored function), N>2 vertex axis, `paramEmptyIfMaxDepth=true` with max-depth exceeded, tie-breaker
  - `SQLFunctionHeuristicPathFinderAbstract` — `getCustomHeuristicCost` (covered via Astar CUSTOM test); heuristic math helpers (Manhattan, Euclidean, Diagonal, DiagonalShortcut, Max, Custom) exercised through `HeuristicFormula` enum
  - `HeuristicFormula` enum — standalone distance-formula coverage (per A6)
  - `SQLFunctionMoveFiltered` — `supernodeThreshold` path (graph with index + >1000 neighbors). Document as ACHIEVABLE if coverage gap is significant; otherwise defer.
- **Test class strategy**:
  - Extend existing `SQLFunctionShortestPathTest` — add `edge=true` test methods
  - Extend existing `SQLFunctionDijkstraTest` — add null-direction + no-path tests
  - Extend existing `SQLFunctionAstarTest` — add CUSTOM heuristic test (register stored function via `session.getMetadata().getFunctionLibrary().createFunction(...)`), max-depth test, N-axis test
  - New `HeuristicFormulaTest` — standalone, validate each enum's distance calculation
  - New `SQLFunctionPathFinderTest` — covers `paramMaxDepth`, direction parsing, null handling
- **Expected coverage delta**: `sql/functions/graph` ~65% → 85%/70%

### Step 3: Collection standalone functions

- **Target packages**: `sql/functions/coll` (standalone portion)
- **Target classes**: `SQLFunctionDistinct`, `SQLFunctionFirst`, `SQLFunctionLast`, `SQLFunctionList`, `SQLFunctionMap`, `SQLFunctionSet`, `SQLFunctionUnionAll`, `SQLFunctionIntersect` (List/Set path only), `SQLFunctionMultiValueAbstract` (aggregateResults via subclass)
- **Test class strategy**:
  - Individual test classes per function (e.g., `SQLFunctionDistinctTest`, `SQLFunctionListTest`) using `BasicCommandContext` (no DB session)
  - Extend existing `SQLFunctionDifferenceTest` / `SQLFunctionSymmetricDifferenceTest` if coverage gaps are found
  - `SQLFunctionIntersectTest` — focus on standalone paths only; RidSet/LinkBag paths deferred to step 4 if reachable only with DB
- **Expected coverage delta**: `sql/functions/coll` 48.6% → ~70% (pre-TraversedElement)

### Step 4: Collection DB-required + TraversedElement/Edge/Vertex + SQLMethodMultiValue

- **Target packages**: `sql/functions/coll` (DB portion)
- **Target classes**:
  - `SQLFunctionTraversedElement`, `SQLFunctionTraversedEdge`, `SQLFunctionTraversedVertex` — cover positive/negative-index paths with/without className filter, using synthetic stack injection (`context.setVariable("stack", syntheticDeque)`)
  - `SQLMethodMultiValue` (AbstractSQLMethod — note parameter order) — DbTestBase, tests field-name resolution via `EntityHelper.getFieldValue`
  - `SQLFunctionIntersect` RidSet / LinkBag branches (if not covered in step 3)
- **Test class strategy**:
  - `SQLFunctionTraversedElementTest` (DbTestBase) — two test methods: stack with Identifiable entries, stack with TraverseRecordProcess entries (if accessible). Accept ~65% branch if TraverseRecordProcess not directly instantiable (document gap).
  - `SQLFunctionTraversedEdgeTest`, `SQLFunctionTraversedVertexTest` — share fixture pattern
  - `SQLMethodMultiValueTest` (DbTestBase) — use `entity.setProperty(...)` fixtures
- **Expected coverage delta**: `sql/functions/coll` → 85%/70% (target met)

### Step 5: Misc standalone functions + SQLStaticReflectiveFunction

- **Target packages**: `sql/functions/misc` (standalone portion)
- **Target classes**:
  - Pure: `SQLFunctionCount`, `SQLFunctionIf`, `SQLFunctionIfNull`, `SQLFunctionCoalesce`, `SQLFunctionAssert` (switch dispatch), `SQLFunctionUUID`, `SQLFunctionStrcmpci`, `SQLFunctionSysdate` (zero-arg), `SQLFunctionDecode`, `SQLFunctionFormat` (misc variant — dead duplicate, see next line)
  - `SQLFunctionStaticReflectiveFunction` — happy path + `pickMethod` / `isAssignable` logic (using e.g. `Math.abs` as fixture)
- **Dead code flag**: `misc.SQLFunctionFormat` is dead (not registered in DefaultSQLFunctionFactory). Pin with a test that verifies its behavior but mark the class with `// WHEN-FIXED: remove dead duplicate of text.SQLFunctionFormat` in the test Javadoc.
- **Test class strategy**: One test class per function. All with `BasicCommandContext` (no DB).
- **Expected coverage delta**: `sql/functions/misc` 53.0% → ~72% (pre-DB portion)

### Step 6: Misc DB-required + SQLFunctionThrowCME + SQLMethodExclude/Include + SQLFunctionEval + Sequence/Distance

- **Target packages**: `sql/functions/misc` (DB portion), `sql/functions/sequence`, `sql/functions/geo`
- **Target classes**:
  - `SQLFunctionDate` — string-path tests with explicit UTC timezone
  - `SQLFunctionEncode` — RID path (loads record from transaction), SerializableStream path
  - `SQLFunctionEval` — math package class but naturally fits here due to DB coupling. Cover predicate caching (same expression twice → reused), divide-by-zero → 0, general exception → null. Note: despite being in `math` subpackage, the DB dependency makes this step the right home.
  - `SQLFunctionIndexKeySize` — extend existing `SQLFunctionIndexKeySizeTest` for uncovered paths
  - `SQLMethodExclude`, `SQLMethodInclude` — AbstractSQLMethod execute signature, DbTestBase with entities containing RIDs
  - `SQLFunctionThrowCME` — happy path verifies CME is thrown with correct fields from valid RID; pinning regression for `(RecordIdInternal) iParams[0]` with non-RID → `ClassCastException` (WHEN-FIXED: validate param type before cast)
  - `SQLFunctionSequence` — DbTestBase with a sequence created via `metadata.getSequenceLibrary().createSequence(...)`
  - `SQLFunctionDistance` — DbTestBase; check standalone Double path if reachable
- **Test class strategy**: Individual test classes per function, mostly DbTestBase.
- **Expected coverage delta**: `sql/functions/misc` 72% → 85%/70%; `sql/functions/sequence` ~85%; `sql/functions/geo` ~85%

### Step 7: Math functions + stat gaps + final math cleanup

- **Target packages**: `sql/functions/math`, `sql/functions/stat`
- **Target classes**:
  - Accumulators: `SQLFunctionAverage`, `SQLFunctionMax`, `SQLFunctionMin`, `SQLFunctionSum` — multi-row via repeated `execute()` then `getResult()`. Cover null, Number, Date, BigDecimal mixed types, aggregateResults branch.
  - `SQLFunctionMathAbstract` — cover via concrete subclass (existing classes + one test-only subclass if needed)
  - Extend existing `SQLFunctionAbsoluteValueTest` / `SQLFunctionIntervalTest` / `stat/SQLFunctionDecimalTest` for uncovered branches (e.g., null input, type coercion)
  - Stat: `SQLFunctionMedian`, `SQLFunctionStandardDeviation` — new tests following existing `SQLFunctionModeTest` / `SQLFunctionVarianceTest` pattern
- **Test class strategy**: Standalone accumulator tests; extend existing tests where possible.
- **Expected coverage delta**: `sql/functions/math` 73.9% → 85%/70%; `sql/functions/stat` 88.9% → ~92%

### Step 8: Text SQLMethod* + conversion SQLMethod* + result + final verification

- **Target packages**: `sql/functions/text`, `sql/functions/conversion`, `sql/functions/result`
- **Target classes**:
  - Text SQLMethod* (all use AbstractSQLMethod signature): `SQLMethodAppend`, `SQLMethodHash`, `SQLMethodLength`, `SQLMethodReplace`, `SQLMethodRight`, `SQLMethodSubString`, `SQLMethodToJSON` (check if session needed for entity serialization)
  - Text functions: `SQLFunctionConcat`, `SQLFunctionFormat` (text variant, registered)
  - Conversion SQLMethod* (AbstractSQLMethod): `SQLMethodAsDate` (string path with explicit UTC), `SQLMethodAsDateTime` (same), `SQLMethodAsDecimal` (standalone), `SQLMethodConvert` (DbTestBase, uses `PropertyTypeInternal.convert(db, ...)`)
  - `SQLFunctionDetachResult` — 2 cases: happy path with ResultInternal, null-record exception path
- **Test class strategy**: One test class per SQLMethod*. Reminder: **SQLMethod execute signature** is `execute(iThis, record, context, ioResult, params)` — context 3rd, NOT 5th.
- **Verification at end of step**: 
  1. Run `./mvnw -pl core -am clean package -P coverage`
  2. Run `python3 .github/scripts/coverage-analyzer.py --coverage-dir .coverage/reports` and assert target subpackages meet 85%/70%
  3. If gaps remain, document them in the step episode
  4. Run `python3 .github/scripts/coverage-gate.py --line-threshold 85 --branch-threshold 70 --compare-branch origin/develop --coverage-dir .coverage/reports`
- **Expected coverage delta**: `sql/functions/text` 72.5% → 85%/70%; `sql/functions/conversion` 52.5% → 85%/70%; `sql/functions/result` 0% → ~85%

## Coverage target summary

| Subpackage | Baseline | Target | Covered in step |
|---|---|---|---|
| sql/functions (root) | 70.4% / 52.2% | 85% / 70% | 1 |
| sql/functions/graph | 53.4% / 40.5% | 85% / 70% | 1, 2 |
| sql/functions/coll | 48.6% / 38.4% | 85% / 70% | 3, 4 |
| sql/functions/misc | 53.0% / 38.3% | 85% / 70% | 5, 6 |
| sql/functions/math | 73.9% / 62.7% | 85% / 70% | 6 (Eval), 7 |
| sql/functions/stat | 88.9% / 77.1% | ≥88.9% | 7 |
| sql/functions/text | 72.5% / 65.7% | 85% / 70% | 8 |
| sql/functions/conversion | 52.5% / 50.0% | 85% / 70% | 8 |
| sql/functions/geo | 63.0% / 35.7% | 85% / 70% | 6 |
| sql/functions/result | 0.0% / 0.0% | 85% / 70% | 8 |
| sql/functions/sequence | 68.8% / 50.0% | 85% / 70% | 6 |

`sql/functions/SQLFunctionRuntime` is explicitly deferred — Tracks 7/8 will exercise it via full SQL queries. Document as an acceptable gap.

## Parallel steps

Steps 3 and 5 (both standalone-only, different subpackages, no shared files) could run in parallel but will likely be sequential for simplicity. No `*(parallel with Step N.M)*` annotations.
