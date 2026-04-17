# Track 7: SQL Methods & SQL Core

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/8 complete)
- [ ] Track-level code review

## Base commit
`a452c0539d859f9601f64de9c1dd30d0295deacd`

## Pre-rebase SHA
`14c72eb47853679255122697b28cff93269a34b2`

## Post-rebase SHA
`a452c0539d859f9601f64de9c1dd30d0295deacd`

Rebase verified: `./mvnw -pl core clean test` → BUILD SUCCESS on post-rebase
SHA (log: `/tmp/claude-code-track7-test-385424.log`). 1 upstream commit picked
up (physiological WAL logging, unrelated to Track 7 scope).

## Reviews completed
- [x] Technical → `reviews/track-7-technical.md` (0 blocker, 4 should-fix, 6 suggestion)
- [x] Adversarial → `reviews/track-7-adversarial.md` (1 blocker A2, 4 should-fix, 3 suggestion)

**Risk review intentionally skipped**: Track 7 is purely test-additive with
no performance, crash-safety, or critical-path concerns.

## Review Response Summary

**Blockers addressed in decomposition:**
- **A2 (sql/ root dead code)**: `CommandExecutorSQLAbstract` (0 subclasses),
  `DefaultCommandExecutorSQLFactory` (hardcoded `Collections.emptyMap()`),
  `DynamicSQLElementFactory` (empty mutable maps, no production mutators),
  `ReturnHandler` family (0 external instantiators) represent ~250 LOC of
  vestigial scaffolding. Retarget sql/ root from 440 uncov to ~180 live LOC.
  Dead classes get a single `SqlRootDeadCodeTest` with WHEN-FIXED markers
  for Track 22 removal (not net-new coverage). Assigned to **step 5**.

**Should-fix items absorbed:**
- **T1 (misc class enumeration)**: Step 1 enumerates the full ~22 untested
  classes (28 production minus Track 6's 6 pre-existing). Explicit list
  in step 1 scope; cross-checks `core/src/test/java/.../sql/method/misc/`
  before writing.
- **T2/A1 (sql/query dead code)**: Only `BasicLegacyResultSet` gets real
  tests (standalone, no DB). The 4 dead classes
  (`ConcurrentLegacyResultSet`, `LiveLegacyResultSet`, `LiveResultListener`,
  `LocalLiveResultListener`) get a single `SqlQueryDeadCodeTest` with
  WHEN-FIXED markers (zero-caller evidence captured). Package aggregate
  coverage will be capped around 30-40% until deletion lands — accepted
  and documented in step 7.
- **T3/A8 (SQLFunctionRuntime / SQLMethodRuntime)**: Both have
  parser-free constructors (`SQLFunctionRuntime(SQLFunction)`,
  `SQLMethodRuntime(SQLMethod)`) already used in production by
  `SQLMethodFunctionDelegate`. Track 7 explicitly absorbs the non-parser
  paths. SQLFunctionRuntime (step 4) crosses into `sql/functions/`
  (Track 6's package) — documented boundary-bridge. SQLMethodRuntime
  (step 3) stays in Track 7's primary scope.
- **T4/A5 (factory + SPI cache race)**: `DefaultSQLMethodFactory`
  HashMap matches Track 6's `CustomSQLFunctionFactory` pattern. Apply
  `@Category(SequentialTest)` + `@FixMethodOrder(NAME_ASCENDING)` +
  UUID-qualified prefix. Pin the race as a WHEN-FIXED regression for
  Track 22 to convert both factories to ConcurrentHashMap together.
  `SQLEngine` SPI-cache race similarly pinned in step 6.
- **T6 (createMethod case-inconsistency)**: `DefaultSQLMethodFactory.
  createMethod` does not lowercase while `register`/`hasMethod` do.
  Pin as WHEN-FIXED regression in step 3.
- **A4 (step count)**: Revised to **8 steps** (scope-indicator said ~5).
  Matches Track 6 precedent (6 planned → 8 actual). Track 7 is NOT
  split into 7a/7b because the scope-narrowing in A1+A2 already reduced
  absolute work.
- **A7 (SQLScriptEngine ownership)**: SQLScriptEngine and
  SQLScriptEngineFactory are functionally `core/command/script`
  territory (registered via `ScriptManager.registerEngine`). Defer to
  **Track 9** (Command & Script). Documented in step 5.
- **A7 (IndexSearchResult ownership)**: Already exercised indirectly by
  Track 5 filter/operator tests. Step 5 only adds targeted tests for
  `merge` / `canBeMerged` branches not covered by Track 5.

**Suggestions absorbed:**
- **T5**: SQLHelper split across step 5 (scalar, standalone) and step 6
  (collection/map, DbTestBase). Mirrors Track 5's operator/filter split.
- **T7**: BasicLegacyResultSet equals asymmetry + redundant isEmpty
  double-check pinned in step 7 with WHEN-FIXED markers.
- **T8**: `CommandExecutorSQLAbstract` is not a dedicated-test target —
  covered indirectly by Track 8's executor tests. Documented in step 5's
  dead-code pin.
- **T9**: `SQLMethodFunctionDelegate` paired with SQLFunctionRuntime in
  step 4 (shared fixture).
- **T10**: `DefaultSQLMethodFactory` race — same treatment as Track 6
  (step 3, WHEN-FIXED for Track 22).
- **A6**: SQLHelper.parseValue boundary tests per dispatch branch
  (string-quoted, single-quoted, collection-bracket, RID-hash, numeric,
  function call, scientific notation, null/"null"/"not null", date
  literal, `$variable` lookup). Documented in step 5.

## Conventions for all steps

- **JUnit 4**, `surefire-junit47` runner (constraint 1).
- **DbTestBase** for tests requiring a session/schema/data; standalone
  with `BasicCommandContext` otherwise. Decide per-class.
- **SQLMethod.execute signature**:
  `method.execute(Object iThis, Result iCurrentRecord, CommandContext
  iContext, Object ioResult, Object[] iParams)` — NOT the SQLFunction
  param/context order (Track 6 T3 carry-forward).
- **Falsifiable regressions for bugs found**: Track 5/6 convention —
  bug-pinning tests carry `// WHEN-FIXED: <what to change>` markers.
- **Static-registry hygiene**: Any test that mutates
  `DefaultSQLMethodFactory`, `SQLEngine.FUNCTION_FACTORIES`/
  `METHOD_FACTORIES`/`OPERATOR_FACTORIES`/`COLLATE_FACTORIES`,
  `DynamicSQLElementFactory.FUNCTIONS`/`COMMANDS`/`OPERATORS`, or any
  other process-wide static cache must use `@Category(SequentialTest)`
  + `@FixMethodOrder(NAME_ASCENDING)` + UUID-qualified name prefixes.
  Add a "registered names snapshot" assertion at teardown to catch leaks.
- **`rollbackIfLeftOpen` `@After` idiom** (Track 6): in any DbTestBase
  test that opens transactions, add `@After` that rolls back a leaked
  tx to prevent cascade-fails across test methods.
- **`session.commit()` detach pattern** (Track 6): collect `Identifiable`
  ids into a local `List` before `session.commit()` — `Iterable<Vertex>`
  wrappers detach on commit.
- **UTC timezone** for any date-parsing test (Track 6 carry-forward).
- **Spotless**: `./mvnw -pl core spotless:apply` before every commit.
- **Per-step verification**: `./mvnw -pl core clean test` before commit.
- **Class-level Javadoc**: describe scenario + expected outcome for every
  test class (Track 5/6 convention — descriptive method names + Javadoc).

## Scope inventory (per adversarial+technical enumeration)

**`sql/method/misc`** (~28 production, ~22 untested after Track 6):
String/transform (Step 1): Type, Normalize, Trim, ToLowerCase, ToUpperCase,
Size, IndexOf, LastIndexOf, Split, Contains, Remove, Prefix, RemoveAll,
Format.
Type-coercion (Step 1): AsBoolean, AsFloat, AsInteger, AsLong, AsString,
JavaType.
DB-required (Step 2): Field, FunctionDelegate.

Pre-existing tests (do NOT re-test — Track 6 artifacts or prior):
AsList, AsMap, AsSet, Keys, Values, SubString.

**`sql/method`** (root):
Step 1: SQLMethodCharAt, SQLMethodLeft (misc-style, live in root by
historical accident).
Step 3: DefaultSQLMethodFactory, SQLMethodRuntime, AbstractSQLMethod
(interface helpers).

**`sql/method/sequence`** (Step 2):
SQLMethodCurrent, SQLMethodNext, SQLMethodReset — all DbTestBase (need
DBSequence metadata).

**`sql/functions`** (Step 4, bridging Track 6's deferral):
SQLFunctionRuntime (non-parser path via programmatic constructor).

**`sql` root** (Steps 5+6, scope narrowed per A2):
Live (~180 LOC): SQLEngine (SPI cache + getMethod/getFunction helpers),
SQLHelper (parseValue scalar+collection, parseDefaultValue, getValue,
getFunction), CommandParameters, RuntimeResult, IndexSearchResult
(merge boundaries only).
Dead (~250 LOC, pin only — step 5): CommandExecutorSQLAbstract,
CommandExecutorSQLFactory, DefaultCommandExecutorSQLFactory,
DynamicSQLElementFactory, ReturnHandler, RecordsReturnHandler,
OriginalRecordsReturnHandler, UpdatedRecordsReturnHandler,
RecordCountHandler.
Deferred elsewhere: SQLScriptEngine[Factory] → Track 9 (script
infrastructure); IterableRecordSource + TemporaryRidGenerator are
interfaces, no standalone test needed.

**`sql/query`** (Step 7, scope narrowed per T2/A1):
Live: BasicLegacyResultSet, LegacyResultSet interface.
Dead (~780 LOC, pin only): ConcurrentLegacyResultSet, LiveLegacyResultSet,
LiveResultListener, LocalLiveResultListener.

## Steps

- [x] Step 1: `sql/method/misc` pure standalone methods + `sql/method`
  root simple methods
  - [x] Context: warning
  - Scope: 20 test classes (14 string/transform + 6 type-coercion + 2 root
    simples: CharAt, Left). All standalone with `BasicCommandContext`.
  - Class inventory (final, authoritative — cross-checked against
    `core/src/test/java/.../sql/method/misc/` for pre-existing):
    Type, Normalize, Trim, ToLowerCase, ToUpperCase, Size, IndexOf,
    LastIndexOf, Split, Contains, Remove, Prefix, RemoveAll, Format,
    AsBoolean, AsFloat, AsInteger, AsLong, AsString, JavaType, CharAt,
    Left.
  - Per-class: boundary inputs (null/empty/"null" string/wrong-type),
    edge cases (negative index, index-out-of-range, empty-string edge),
    type coercion where applicable (numeric overflow on AsInteger,
    invalid boolean string), error paths (CommandSQLParsingException
    on null iThis for some methods).
  - Falsifiable-regression + WHEN-FIXED markers for any latent bugs
    surfaced. Candidate: SQLMethodFormat is duplicated in sql/functions/
    as the dead copy — Track 6 already flagged the dead one; here test
    the live method copy.
  - Verify: `./mvnw -pl core -Dtest='SQLMethod*Test' clean test` green.
  - Expected: ~80-120 test methods across 20 new test classes.

  > **What was done:** Added 22 standalone test classes under
  > `core/src/test/java/.../sql/method/misc/` (20) and `.../sql/method/` (2,
  > CharAt + Left). First commit (`88b2db90`) produced 155 tests; the
  > step-level dimensional review drove a follow-up commit (`b8eed293`) that
  > added 28 corner-case/regression tests, pinned a locale-determined Locale
  > in @Before for SQLMethodFormatTest, and tightened assertions
  > (assertSame for identity, instanceof before vacuous empty-list
  > equality). Final: 183 tests, all green. All tests are standalone
  > (BasicCommandContext) except SQLMethodFormatTest, which extends
  > DbTestBase because SQLMethodFormat calls
  > `CommandContext.getDatabaseSession()` unconditionally.
  >
  > **What was discovered:**
  > - SQLMethodFormat requires a live DB session (not discoverable from the
  >   scope-indicator alone). Carry forward pattern: when a method calls
  >   `CommandContext.getDatabaseSession()`, the test must extend DbTestBase
  >   or provide a BasicCommandContext(session) with a real session — a bare
  >   `new BasicCommandContext()` throws `DatabaseException("No database
  >   session found in SQL context")`. Relevant for Step 4 SQLFunctionRuntime
  >   and Step 5 RuntimeResult.
  > - Latent production bugs pinned as WHEN-FIXED regressions for Track 22:
  >   (1) SQLMethodContains guard is `&&` where `||` was intended — always-false
  >   guard means null/empty iParams raise NPE/AIOBE instead of returning
  >   false. (2) SQLMethodNormalize 2-arg branch reads replacement regex
  >   from `iParams[0]` (the form name) instead of `iParams[1]`.
  >   (3) SQLMethodLastIndexOf lacks the null-iThis guard that SQLMethodIndexOf
  >   has — asymmetric NPE. (4) SQLMethodIndexOf/LastIndexOf/Prefix/CharAt all
  >   call `iParams[0].toString()` without null-guards — null first params NPE.
  > - SimpleDateFormat.format(null) throws `IllegalArgumentException`
  >   ("Cannot format given Object as a Date"), not NullPointerException —
  >   pinned in dateCollectionWithNullElementThrowsIllegalArgument.
  > - `MultiValue.getSize` returns 0 for non-multi-values (not 1 as the test
  >   initially assumed). Documented in stringInputDelegatesToMultiValueSizeReturnsZero.
  > - SQLMethodFormat's locale-sensitive `String.format("%.2f", 1.5)` would
  >   render "1,50" under de_DE — added Locale.US pinning in @Before to keep
  >   assertions deterministic across CI runners.
  >
  > **What changed from the plan:** Scope inventory said 20 classes but the
  > enumeration (14 string/transform + 6 type-coercion + 2 root) is 22. All
  > 22 written — no skips. Test method count exceeded the 80-120 estimate
  > (183 methods) because the review-driven TC1-TC6 findings added regression
  > pins.
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/method/SQLMethodCharAtTest.java` (new)
  > - `core/src/test/java/.../sql/method/SQLMethodLeftTest.java` (new)
  > - `core/src/test/java/.../sql/method/misc/SQLMethod{AsBoolean,AsFloat,AsInteger,AsLong,AsString,Contains,Format,IndexOf,JavaType,LastIndexOf,Normalize,Prefix,Remove,RemoveAll,Size,Split,ToLowerCase,ToUpperCase,Trim,Type}Test.java` (all new)
  >
  > **Critical context:** Pre-existing tests (AsList, AsMap, AsSet, Keys,
  > Values, SubString) were deliberately left untouched — Track 6 and prior
  > authorship own them.

- [ ] Step 2: `sql/method/misc` DB-required methods + `sql/method/sequence`
  (all DbTestBase)
  - Scope: 5 test classes.
    - misc: SQLMethodField (session + transaction load), SQLMethodFunctionDelegate
      (delegates to SQLFunctionRuntime; pair fixture with step 4's
      SQLFunctionRuntimeTest).
    - sequence: SQLMethodCurrent, SQLMethodNext, SQLMethodReset.
  - Each sequence test uses the `SQLFunctionSequenceTest` (Track 6
    artifact) pattern: `session.getMetadata().getSequenceLibrary().
    createSequence("name", TYPE, params)`. Cover 3 error paths:
    `iThis==null`, `iThis !instanceof DBSequence`, underlying
    `DatabaseException` re-thrown as `CommandExecutionException`.
  - SQLMethodField: test happy path (entity.getField via Field method),
    nested field access, missing-field null return, null iThis, wrong-type
    iThis.
  - Apply `@After rollbackIfLeftOpen` idiom (Track 6 carry-forward) for
    any test that writes records.
  - Verify: `./mvnw -pl core -Dtest='SQLMethodField*Test,SQLMethodFunctionDelegate*Test,SQLMethodCurrent*Test,SQLMethodNext*Test,SQLMethodReset*Test' clean test` green.
  - Expected: ~25-35 test methods across 5 new test classes.

- [ ] Step 3: `sql/method` infrastructure — DefaultSQLMethodFactory,
  SQLMethodRuntime, AbstractSQLMethod helpers
  - Scope: 3 test classes.
    - `DefaultSQLMethodFactoryTest` (SequentialTest + FixMethodOrder
      NAME_ASCENDING):
      * register()/hasMethod() case-insensitive contract;
      * createMethod() case-inconsistency — PIN as WHEN-FIXED (T6);
      * unknown-method exception ("Unknown method name: X");
      * HashMap race — PIN as WHEN-FIXED for Track 22 twin-fix with
        CustomSQLFunctionFactory (T4/T10);
      * registered-names snapshot at setup, assertion at teardown for
        leak detection.
    - `SQLMethodRuntimeTest`: programmatic path via
      `SQLMethodRuntime(SQLMethod)` + `setParameters(session, params,
      false)` + `execute(iThis, record, result, context)`.
      * Parameter resolver branches (configuredParameters → runtimeParameters):
        SQLFilterItemField.getValue, SQLFilterItemVariable.getValue,
        nested SQLMethodRuntime.execute, nested SQLFunctionRuntime.execute,
        string-quoted (IOUtils.getStringContent), unquoted-string,
        null, iEvaluate=false short-circuit.
      * Arity validation (too few / too many parameters).
      * DbTestBase (setParameters uses DatabaseSessionEmbedded).
    - `AbstractSQLMethodTest`: getName(), getSyntax(), checkArguments(),
      minArgs/maxArgs boundaries, equals/hashCode/compareTo (factory-key
      contract). Standalone.
  - Verify: `./mvnw -pl core -Dtest='DefaultSQLMethodFactoryTest,SQLMethodRuntimeTest,AbstractSQLMethodTest' clean test` green.
  - Expected: ~30 test methods across 3 new test classes.

- [ ] Step 4: `sql/functions` SQLFunctionRuntime absorption (Track 6 deferral)
  - Scope: 1 test class. Lives physically under `sql/functions/` but
    covers a Track 6 hand-off; documented boundary bridge.
    - `SQLFunctionRuntimeTest` (DbTestBase — setParameters needs session):
      * Programmatic path: `new SQLFunctionRuntime(SQLFunctionIfNull)` +
        `setParameters(context, new Object[]{...}, false)` +
        `execute(iThis, record, result, context)`.
      * Parameter resolver branches (configuredParameters → runtimeParameters):
        SQLFilterItemField.getValue, SQLFilterItemVariable.getValue,
        SQLPredicate.evaluate, nested SQLFunctionRuntime.execute,
        nested SQLMethodRuntime.execute, string-quoted
        (IOUtils.getStringContent), unquoted-string, null,
        iEvaluate=false short-circuit.
      * Arity validation via `CommandExecutionException` ("arguments").
      * `aggregateResults()` / `filterResult()` delegation.
      * `getResult(session)` after `execute`.
      * Use `SQLFunctionCount` (aggregation) and `SQLFunctionIfNull`
        (simple no-DB) as wrapped functions for test variety.
      * Skip `setRoot(BaseParser, String)` parser path — explicitly
        documented as Track 8 or integration-test territory.
      * Pair fixture with step 2's `SQLMethodFunctionDelegateTest` if
        they can share setup (both wrap SQLFunctionRuntime).
  - Verify: `./mvnw -pl core -Dtest='SQLFunctionRuntimeTest' clean test`
    green.
  - Expected: ~20-25 test methods in 1 new test class.

- [ ] Step 5: `sql` root — live classes (scalar SQLHelper + pure utils)
  + dead-code pin
  - Scope: 5 test classes + 1 dead-code pin class.
    - `SQLHelperParseValueScalarTest` (standalone, BasicCommandContext):
      * Prefix-dispatch per A6 guidance: null/"null"/"not null"/"defined",
        booleans (true/false/"true"/"false"), double-quoted strings,
        single-quoted strings, RID (`#clusterId:pos`), numeric (int/long/
        double/scientific), hex-literal, date-literal, `$variable` lookup
        via `context.setVariable`.
      * Boundary cases per Track 5 precedent (tryDownscaleToInt off-by-one):
        Integer.MAX/MIN boundaries for numeric parsing, malformed RID.
      * WHEN-FIXED markers for any coercion asymmetry.
    - `SQLHelperMiscTest` (standalone):
      * `parseDefaultValue`, `getValue`, helper methods not exercised by
        scalar parseValue.
    - `CommandParametersTest` (pure):
      * Named + positional argument binding, `reset()`, iteration order,
        null-value handling.
    - `IndexSearchResultTest` (pure — targeted, Track 5 already covers
      most paths via FilterOptimizer):
      * `merge(other)` branches: compatible ranges, disjoint ranges,
        overlapping operators.
      * `canBeMerged(other)`: positive + negative cases not hit by Track 5.
      * `equals`/`hashCode` contract.
      * Skip paths exercised by Track 5's SQLFilterClassesTest —
        document cross-track coverage in Javadoc.
    - `RuntimeResultTest` (DbTestBase):
      * `createProjectionDocument(session, fieldNames, fieldValues)`,
        projection evaluation with and without SQLFunctionRuntime context.
    - `SqlRootDeadCodeTest` (standalone — the dead-code pin — per A2):
      * Single test per dead class documenting zero-caller evidence:
        CommandExecutorSQLAbstract (no subclasses),
        CommandExecutorSQLFactory + DefaultCommandExecutorSQLFactory
        (empty COMMANDS hardcoded), DynamicSQLElementFactory (empty
        mutable maps), ReturnHandler + RecordsReturnHandler +
        OriginalRecordsReturnHandler + UpdatedRecordsReturnHandler +
        RecordCountHandler (0 external instantiators).
      * Each test: `// WHEN-FIXED: Track 22 — delete <ClassName> and
        update this test to assert deletion.`
      * Scope note: Covers ~250 LOC that would otherwise inflate the
        sql/ root coverage number; pinning documents the obsolescence
        in source control.
      * Note: `SQLScriptEngine[Factory]` deferred to Track 9 — NOT
        included here.
  - Verify: `./mvnw -pl core -Dtest='SQLHelper*Test,CommandParametersTest,IndexSearchResultTest,RuntimeResultTest,SqlRootDeadCodeTest' clean test` green.
  - Expected: ~50 test methods across 6 new test classes.

- [ ] Step 6: `sql` root — SQLHelper collection paths + SQLEngine SPI cache
  - Scope: 2 test classes.
    - `SQLHelperParseValueCollectionTest` (DbTestBase):
      * Embedded list `[a, b, c]`, embedded map `{k: v}`, link list,
        link map, embedded entity `{@class: X, field: v}`.
      * `schemaProperty`-driven typed collection parsing.
      * Nested collection (list of maps, etc.).
      * Edge: empty collection, null element, trailing comma.
    - `SQLEngineSpiCacheTest` (SequentialTest + FixMethodOrder
      NAME_ASCENDING):
      * `getFunctionFactories`/`getMethodFactories`/`getOperatorFactories`/
        `getCollateFactories`/`getCommandFactories` — lazy init + cache
        hit + SORTED_OPERATORS consistency.
      * `scanForPlugins()` only clears FUNCTION_FACTORIES — PIN as
        WHEN-FIXED regression flagging the stale-cache bug (test:
        register a new operator factory, verify `SORTED_OPERATORS`
        stays stale until tested — if bug is fixed, cache gets
        invalidated).
      * `registerOperator`/`registerFunction` static-state leak
        detection: snapshot registered names at setup, assert on
        teardown.
      * Pin the volatile-cache-mutation race (concurrent
        `registerOperator` from two threads) as WHEN-FIXED for Track 22
        (twin-fix with CustomSQLFunctionFactory + DefaultSQLMethodFactory).
      * `getFunction(session, funcName)` happy path (registered
        function) + unknown function → null return.
      * `getMethod(methodName)` happy path + case-insensitive lookup
        via `SQLEngine.getMethod` (masks T6 bug — pin behaviour).
  - Verify: `./mvnw -pl core -Dtest='SQLHelperParseValueCollectionTest,SQLEngineSpiCacheTest' clean test` green.
  - Expected: ~35 test methods across 2 new test classes.

- [ ] Step 7: `sql/query` — BasicLegacyResultSet + dead-code pin
  - Scope: 2 test classes.
    - `BasicLegacyResultSetTest` (standalone — List contract, no DB):
      * All 30+ `List`-delegated methods: size, isEmpty, contains,
        iterator, toArray (both overloads), add, set, get, clear,
        listIterator (both), subList.
      * UOE branches: remove(Object), remove(int), containsAll,
        removeAll, retainAll, indexOf, lastIndexOf — one test per UOE
        method (T7).
      * `setLimit(int)` + add overflow: adding beyond limit triggers
        truncation or silent drop (verify behaviour).
      * `copy()`: Externalizable round-trip verification.
      * `writeExternal`/`readExternal` round-trip (T7).
      * `equals` asymmetry — PIN as WHEN-FIXED: `new
        BasicLegacyResultSet<>().equals(Collections.emptyList())` vs
        reverse (T7).
      * `isEmpty()` double-check pattern
        (`if (empty) empty = underlying.isEmpty();`) — PIN as
        WHEN-FIXED regression: document no-op idiom.
      * `currentSize()` pre-limit behaviour.
    - `SqlQueryDeadCodeTest` (standalone — pin only, per T2/A1):
      * One test per dead class documenting zero-caller evidence:
        ConcurrentLegacyResultSet, LiveLegacyResultSet,
        LiveResultListener, LocalLiveResultListener.
      * Each test: `// WHEN-FIXED: Track 22 — delete <ClassName>`.
      * Coverage expectation: `sql/query` package aggregate will NOT
        reach 85% line — only BasicLegacyResultSet + LegacyResultSet
        interface (~300 LOC of 1034 total) are live. Accepted per A1.
  - Verify: `./mvnw -pl core -Dtest='BasicLegacyResultSetTest,SqlQueryDeadCodeTest' clean test` green.
  - Expected: ~45 test methods across 2 new test classes.

- [ ] Step 8: Coverage verification
  - Run coverage build: `./mvnw -pl core -am clean package -P coverage`.
  - Run analyzer: `python3 .github/scripts/coverage-analyzer.py
    --coverage-dir .coverage/reports`. Focus on:
    * `core/sql/method/misc` — expect 58.6% → ≥85%.
    * `core/sql/method` — expect 62.0% → ≥85%.
    * `core/sql/method/sequence` — expect 23.1% → ≥85%.
    * `core/sql` (live classes only, per A2) — expect 39.7% → ≥85%
      on the ~180 live LOC; overall package aggregate stays lower
      due to pinned dead code (accepted).
    * `core/sql/query` — expect 2.9% → 30-40% (BasicLegacyResultSet
      aggressively covered; 4 dead classes pinned not tested — per
      A1/T2, NOT expected to hit 85% until Track 22 deletes the dead
      code).
  - Run `python3 .github/scripts/coverage-gate.py --line-threshold 85
    --branch-threshold 70 --compare-branch origin/develop
    --coverage-dir .coverage/reports` to ensure Track 7's *changed
    lines* meet 85%/70% gates (per CLAUDE.md `coverage-gate.py` is
    authoritative for changed-line coverage).
  - Update `docs/adr/unit-test-coverage/coverage-baseline.md` with
    post-Track-7 measurements (append a "post-Track-7" section; do not
    overwrite baseline).
  - Run full `./mvnw -pl core clean test` — BUILD SUCCESS.
  - Apply `./mvnw -pl core spotless:apply`; commit any formatting
    drift.
  - Expected: ~0 new tests; this step produces the verification commit
    + the coverage-baseline update.
