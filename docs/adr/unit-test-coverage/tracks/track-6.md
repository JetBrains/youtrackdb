# Track 6: SQL Functions

## Progress
- [x] Review + decomposition
- [ ] Step implementation (3/8 complete)
- [ ] Track-level code review

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
