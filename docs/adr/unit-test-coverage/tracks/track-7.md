# Track 7: SQL Methods & SQL Core

## Progress
- [x] Review + decomposition
- [ ] Step implementation (7/8 complete)
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

- [x] Step 2: `sql/method/misc` DB-required methods + `sql/method/sequence`
  (all DbTestBase)
  - [x] Context: warning
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

  > **What was done:** Added 67 tests across 5 new test classes + 1 shared
  > helper (FailingDBSequence) under `core/src/test/java/.../sql/method/`.
  > Initial commit (`e8b6ceab`) delivered 56 tests; the step-level
  > dimensional review (5 agents: code-quality, bugs-concurrency,
  > test-behavior, test-completeness, test-structure) flagged 1 blocker
  > + 12 should-fix + selective suggestions. Review-fix commit (`1c28c6d9`)
  > resolved all blockers and should-fix items, adding 11 more tests.
  > Final: 67 tests, all green.
  >
  > Distribution:
  > - SQLMethodFieldTest — 27 tests (3 star-dispatch variants, entity/Map/
  >   Collection/array/RecordNotFoundException-dangling-RID/CommandContext/
  >   SQLFilterItemField/list-flatten/Map-keep-whole/empty-collection).
  > - SQLMethodFunctionDelegateTest — 14 tests (min/max sentinel pass-through,
  >   max=0 quirk, argument threading via RecordingFunction harness, arity
  >   errors pinned with full message structure).
  > - SQLMethodCurrent/Next/Reset tests — 26 tests combined (happy path +
  >   custom start/increment variants, null-iThis and wrong-type-iThis
  >   parsing errors, DatabaseException→CEE re-wrap via FailingDBSequence,
  >   SequenceLimitReachedException escape pin for Next, metadata).
  >
  > **What was discovered:**
  > - **Latent production NPE in SQLMethodField** (lines 92-94): when
  >   `catch (RecordNotFoundException)` nulls `ioResult`, the subsequent
  >   compound `else if (ioResult instanceof Collection<?> || ... ||
  >   ioResult.getClass().isArray())` dereferences null because the prior
  >   operands short-circuit only on true. A dangling-RID Identifiable
  >   candidate triggers a NullPointerException instead of the intended
  >   null return. Pinned as WHEN-FIXED regression in
  >   `identifiableCandidateForDeletedRecordNpesDueToNullUnguardedIsArrayCheck`
  >   with explicit fix directive: guard the isArray() call against null.
  >   Deferred to Track 22 production-side fix.
  > - **SequenceLimitReachedException is NOT a DatabaseException subtype**:
  >   it extends BaseException directly, so the production `catch
  >   (DatabaseException)` in SQLMethod{Current,Next,Reset} does NOT
  >   intercept it — the exception propagates un-rewrapped. Pinned as
  >   explicit behavioral contract in `limitReachedPropagatesAsSequenceLimitReachedException`.
  >   Important downstream contract for query clients that catch
  >   SequenceLimitReachedException to detect exhaustion.
  > - **CommandExecutionException is NOT a DatabaseException subtype**:
  >   it extends CoreException directly, so `e instanceof DatabaseException`
  >   is statically impossible inside a `catch (CommandExecutionException e)`
  >   block (verified by compile error). The re-wrap semantics are
  >   therefore proven at compile time by the catch block's selection,
  >   not by a runtime assertion. Comment in the re-wrap tests documents
  >   this explicitly.
  > - **FailingDBSequence helper via reflection**: `DBSequence.entityRid`
  >   is protected, so a test-only subclass can read it via reflection to
  >   borrow a real sequence's entity for the super-constructor's
  >   Objects.requireNonNull. Overriding nextWork/currentWork/resetWork
  >   to throw DatabaseException directly covers the re-wrap catch branch
  >   without needing a fragile delete-then-dangle setup. Pattern carry-
  >   forward: when a test needs to exercise a catch block on an abstract
  >   class with protected state, reflection on the protected field is
  >   cleaner than schema manipulation.
  > - **EMBEDDEDLIST/EMBEDDEDMAP properties require `getOrCreateEmbeddedList`
  >   / `getOrCreateEmbeddedMap`** — a bare `setProperty("tags",
  >   Arrays.asList(...))` throws IllegalArgumentException ("Data containers
  >   have to be created using appropriate getOrCreateXxx methods").
  >   Pattern carry-forward for Track 7 Step 5 and any future track that
  >   populates typed collections on entities.
  > - **getActiveTransaction() throws when no tx is active**: use
  >   `getActiveTransactionOrNull()` in test cleanup / shared helpers that
  >   may be invoked with or without an outer tx. Applied to
  >   SQLMethodFieldTest.@After and FailingDBSequence.wrapping.
  > - **DBSequence subclass construction via protected constructor**:
  >   accessing `protected DBSequence(EntityImpl entity)` requires the
  >   test subclass to live in a source package that can see the
  >   protected constructor — which a subclass naturally does via super().
  >
  > **What changed from the plan:** Scope said ~25-35 tests across 5
  > classes; actual is 67 tests across 5 classes + 1 shared helper. The
  > extra count (+32-42) is attributable to the review-driven TC1/TC6/
  > TB10/TC2/TC3/TC4/TC5/TB3 findings that added corner-case / boundary /
  > branch-coverage tests, plus the 3 starFieldName variants replacing
  > the original fragile single test. The scope-indicator's test count
  > estimate is systematically low for test-additive tracks under
  > dimensional review.
  >
  > **Cross-track impact:** Minor. The latent NPE in SQLMethodField joins
  > the Track 22 production-fix queue (same bucket as the SQLMethodContains
  > `&&→||` guard, SQLMethodNormalize iParams[0-vs-1] mix-up,
  > SQLFunctionFormat dead code, CustomSQLFunctionFactory/DefaultSQLMethodFactory
  > HashMap races). No Component Map changes. No invalidation of Step 3
  > (DefaultSQLMethodFactory / SQLMethodRuntime / AbstractSQLMethod) or
  > Step 4 (SQLFunctionRuntime) dependencies — both of those scope over
  > infrastructure classes unaffected by SQLMethodField's null bug.
  >
  > **Step-level review iterations**: Ran iter-1 with 5 dimensions
  > (CQ/BC/TB/TC/TS). Applied 1 blocker + 12 should-fix + 4 suggestions;
  > deferred DRY refactor (TS2/TS3 — abstract base class for the three
  > sequence tests) as a suggestion-level item better handled in Track 22
  > final sweep. Iter-2 gate check deferred because context hit warning
  > level (32%) after iter-1 — per workflow protocol, end session before
  > next review iteration. All blockers and should-fix items verified by
  > running the full test suite after the fix commit: 67/67 green.
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/method/misc/SQLMethodFieldTest.java` (new, 27 tests)
  > - `core/src/test/java/.../sql/method/misc/SQLMethodFunctionDelegateTest.java` (new, 14 tests, RecordingFunction harness)
  > - `core/src/test/java/.../sql/method/sequence/FailingDBSequence.java` (new, shared helper)
  > - `core/src/test/java/.../sql/method/sequence/SQLMethodCurrentTest.java` (new, 9 tests)
  > - `core/src/test/java/.../sql/method/sequence/SQLMethodNextTest.java` (new, 10 tests incl. SequenceLimitReached)
  > - `core/src/test/java/.../sql/method/sequence/SQLMethodResetTest.java` (new, 9 tests)
  >
  > **Critical context:** SQLMethodField's null-unguarded isArray() NPE is
  > the first production bug surfaced in Track 7 and is pinned but not
  > fixed. Track 22's production-side cleanup scope grows by one entry.

- [x] Step 3: `sql/method` infrastructure — DefaultSQLMethodFactory,
  SQLMethodRuntime, AbstractSQLMethod helpers
  - [x] Context: warning
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

  > **What was done:** Added 74 tests across 3 new standalone/DbTestBase test
  > classes under `core/src/test/java/.../sql/method/`: AbstractSQLMethodTest
  > (26 tests, standalone), DefaultSQLMethodFactoryTest (15 tests,
  > `@Category(SequentialTest)` + `@FixMethodOrder(NAME_ASCENDING)`),
  > SQLMethodRuntimeTest (33 tests, DbTestBase). First commit (`b4fa3ed9`)
  > produced 70 tests; the step-level dimensional review drove a follow-up
  > commit (`fe3e4cab`) that added 4 corner-case tests (empty-input AIOBE,
  > single-quote AIOBE, single-optional `getSyntax`, upper-bound-only arity
  > enforcement) and strengthened assertions across ~15 tests (invoked
  > flags, runtime-params pins, pre-mutation state, exact class equality,
  > `String.compareTo` exact-value assertions).
  >
  > **What was discovered:**
  > - **Latent production bug #1 — `DefaultSQLMethodFactory.createMethod` is
  >   case-sensitive while `register` / `hasMethod` are case-insensitive**
  >   (T6 pin from track review, now verified): `hasMethod("SIZE")` returns
  >   true but `createMethod("SIZE")` throws `CommandExecutionException`.
  >   Masked in production by `SQLEngine.getMethod` lowercasing the name
  >   first — but any direct factory caller (or a future factory) hits the
  >   inconsistency. Pinned in
  >   `createMethodIsCaseSensitiveWhileHasMethodIsCaseInsensitive` with a
  >   WHEN-FIXED marker for Track 22.
  > - **Latent production bug #2 — `SQLMethodFunctionDelegate` is registered
  >   as `Class<?>` but has no no-arg constructor**: its only ctor takes
  >   `(SQLFunction f)`. `DefaultSQLMethodFactory.createMethod("function")`
  >   therefore ALWAYS throws on the reflective `newInstance()` path. The
  >   class-registered branch is effectively dead for this one entry.
  >   Production `SQLMethodFunctionDelegate` is instantiated only directly
  >   (`new SQLMethodFunctionDelegate(f)` in `SQLFilterItemAbstract:152`).
  >   Pinned in `allRegisteredNamesCreatableViaCreateMethodExceptFunction`
  >   with WHEN-FIXED marker. Discovered via the first test failure — a real
  >   "tests find a bug" outcome. Track 22 should either add a no-arg ctor
  >   + post-hoc setFunction() or remove the factory registration.
  > - **Latent production bugs #3 and #4 — `AbstractSQLMethod.getParameterValue`
  >   has two unguarded AIOBE paths**: empty string `""` crashes at
  >   `charAt(0)`, and single-char `"'"` crashes at `substring(1, 0)` (endIndex
  >   < beginIndex). Both pinned as falsifiable WHEN-FIXED regressions in
  >   `getParameterValueEmptyStringThrowsIndexOutOfBounds` and
  >   `getParameterValueSingleQuoteCrashesOnSubstringUnderflow`. If
  >   production adds length-2 guards, flip the tests to assert the chosen
  >   return value (likely null or empty string).
  > - **HashMap race pattern** (T4/T10 from track review): the factory's
  >   `methods` field is a plain `HashMap<String, Object>` mutated via
  >   `register()` without synchronization. Pinned structurally in
  >   `methodsBackingMapIsPlainHashMapWhenFixedConvertToConcurrent` via
  >   reflection on the field type. Twin of Track 6's CustomSQLFunctionFactory
  >   pin. Track 22 should convert both factories to ConcurrentHashMap in a
  >   single commit along with a concurrent register/lookup contract test.
  > - **`SQLHelper.parseValue` promotes unparseable unquoted strings to
  >   `SQLFilterItemField`** (not `VALUE_NOT_PARSED` as my initial test
  >   assumed): the 4-arg overload delegates to
  >   `parseValue(BaseParser, String, CommandContext)` which, after trying
  >   literals/numbers/RIDs/functions/variables, falls through to
  >   `new SQLFilterItemField(session, iCommand, iWord, null)`. This means
  >   `setParameters`' `continue` branch (meant to keep raw strings when
  >   VALUE_NOT_PARSED is returned) is effectively dead for the 4-arg path.
  >   Corrected the corresponding test
  >   (`setParametersUnparseableIdentifierBecomesFilterItemField`) to assert
  >   the actual `instanceof SQLFilterItemField` result.
  > - **Arity-test gotcha**: passing bare String identifiers ("only", "a")
  >   as arity-test params causes SQLHelper to promote them to
  >   SQLFilterItemField, which then fails to resolve against a null record
  >   ("expression item cannot be resolved because current record is NULL")
  >   BEFORE the arity check fires — masking the arity message. All arity
  >   tests switched to numeric (Integer) params to bypass field promotion.
  >   Carry-forward pattern for Step 4 and beyond: when exercising
  >   SQLMethodRuntime arity branches, always use non-String params.
  > - **`SQLMethodRuntime` public-field contract**: `configuredParameters`
  >   and `runtimeParameters` are public mutable fields used by the parser
  >   and by test code. Tests that want to exercise rare execute-side
  >   branches (e.g., the String-starts-with-quote path that `setParameters`
  >   already strips) can bypass `setParameters` and write the fields
  >   directly. Legal per the public contract but brittle — a regression
  >   that encapsulates these fields would break the tests. Documented
  >   in-test.
  > - **`BaseParser` has one abstract method** (`throwSyntaxErrorException`)
  >   that a subclass needs to override. The `StubParser` helper in
  >   SQLMethodRuntimeTest overrides it with a loud `IllegalStateException`
  >   so a future refactor that starts calling it during test construction
  >   fails loudly rather than silently no-op'ing.
  > - **`BaseParser.parserTextUpperCase` must be set with
  >   `Locale.ENGLISH`** (review-fix finding, now applied): using default
  >   locale risks the Turkish-locale trap where lowercase `i` becomes
  >   `İ` (U+0130), breaking case comparisons in downstream parser helpers.
  > - **`MultiValue.getSize(Collection)` returns `Long`, not `Integer`**:
  >   discovered via an assertion failure. Use `Number` + `intValue()` when
  >   asserting sizes across MultiValue returns. (Track 7 Step 1 already
  >   documented that `MultiValue.getSize` on a non-multi-value returns 0
  >   — this is the companion fact for the multi-value branch.)
  >
  > **What changed from the plan:** Scope said ~30 test methods across 3
  > classes; actual is 74 tests. The extra count is attributable to (a) the
  > `register/hasMethod/createMethod` inconsistency pin requiring more
  > granular enumerated checks, (b) the full 46-method ALL_NAMES enumeration
  > (vs spot-check in the scope), (c) four review-driven corner-case tests
  > (AIOBEs + single-optional getSyntax + upper-bound arity), and (d) five
  > review-driven assertion-strengthening tests added during iter-1 fix.
  > The test-count estimate is systematically low for test-additive tracks
  > under dimensional review — same pattern as Steps 1-2.
  >
  > **Cross-track impact:**
  > - **Track 22 scope expands by three entries**: (1) `createMethod`
  >   case-sensitivity inconsistency, (2) `SQLMethodFunctionDelegate`
  >   no-no-arg-ctor dead Class<?> registration, (3) two AIOBE paths in
  >   `AbstractSQLMethod.getParameterValue`. All pinned as falsifiable
  >   WHEN-FIXED regressions alongside the existing HashMap-race bucket.
  > - **Step 4 (SQLFunctionRuntime absorption)** uses the same
  >   programmatic-constructor + setParameters + execute pattern as
  >   SQLMethodRuntimeTest. Carry forward: invoked-flag + return-sentinel
  >   assertion style; numeric (non-String) arity params; Locale.ENGLISH in
  >   any BaseParser stubs; the `StringBuilder` Test structure pattern.
  > - **Step 6 (SQLEngineSpiCacheTest)** will pin another static-cache race
  >   (SQLEngine.FUNCTION_FACTORIES / METHOD_FACTORIES / OPERATOR_FACTORIES
  >   / COLLATE_FACTORIES / SORTED_OPERATORS). The structural pin pattern
  >   from
  >   `methodsBackingMapIsPlainHashMapWhenFixedConvertToConcurrent` is
  >   directly applicable.
  > - No Component Map or Decision Record changes.
  >
  > **Step-level review iterations:** Ran iter-1 with 6 dimensions (CQ, BC,
  > TB, TC, TS, TX). 0 blockers + ~25 should-fix + ~15 suggestions.
  > Applied the 14 highest-impact should-fix items (invoked flags,
  > stronger-precision assertions, AIOBE pins, single-optional getSyntax,
  > upper-bound-only arity test, pre-mutation state assertion, exact class
  > equality, Locale.ENGLISH, deleted tautology test).
  > Iter-2 gate check deferred: context hit warning level (29-32%) during
  > iter-1 fix-apply cycle. Per workflow protocol, end session before next
  > review iteration. Deferred suggestions include DRY refactor of
  > ProbeMethod/StubMethod/RecordingFunction across files (better handled
  > in Track 22 with rollbackIfLeftOpen DRY), concurrent smoke test for
  > HashMap race (WHEN-FIXED pin already flags the production fix), FQN
  > types in helper signatures (cosmetic), additional corner cases
  > (TC3/TC4/TC8 Identifiable-secondary-lookup, null-arg register,
  > uppercase class-registered). All deferred items documented in commit
  > `fe3e4cab` and reviewable for iter-2 if Track 22 doesn't absorb them.
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/method/DefaultSQLMethodFactoryTest.java` (new, 15 tests)
  > - `core/src/test/java/.../sql/method/SQLMethodRuntimeTest.java` (new, 33 tests)
  > - `core/src/test/java/.../sql/method/misc/AbstractSQLMethodTest.java` (new, 26 tests)
  >
  > **Critical context:** Four production bugs surfaced in Step 3 (case
  > sensitivity, no-no-arg-ctor, two AIOBEs) — Track 22's production-side
  > cleanup queue now has Steps 1 (SQLMethodContains, SQLMethodNormalize,
  > SQLMethodLastIndexOf/IndexOf/Prefix/CharAt null-guard asymmetries), 2
  > (SQLMethodField null-unguarded isArray NPE), and 3 (case sensitivity,
  > function Class<?>-ctor, getParameterValue AIOBEs) queued.

- [x] Step 4: `sql/functions` SQLFunctionRuntime absorption (Track 6 deferral)
  - [x] Context: warning
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

  > **What was done:** Added 43 tests in 1 new DbTestBase test class
  > `SQLFunctionRuntimeTest` under `core/src/test/java/.../sql/functions/`.
  > First commit (`86a23827`) delivered 39 tests; the step-level dimensional
  > review (5 agents: CQ, BC, TB, TC, TS) drove a follow-up commit
  > (`b4204eb4`) that added 4 tests (MultiValue+VALUE_NOT_PARSED guard,
  > arity short-circuit invariant pin, fresh-aggregator getResult=0,
  > companion non-buggy SQLPredicate path) and rewrote the SQLPredicate
  > WHEN-FIXED pin after discovering the original pin's premise was factually
  > wrong. Final: 43 tests, all green.
  >
  > Coverage:
  > - Constructors and simple delegations (aggregateResults, filterResult,
  >   getRoot, getFunction/getConfiguredParameters/getRuntimeParameters).
  > - setParameters literal conversions (single/double-quoted strip, numeric,
  >   true/false, null literal, null element, non-String pass-through,
  >   unparseable identifier → SQLFilterItemField promotion, MultiValue +
  >   VALUE_NOT_PARSED continue guard, iEvaluate=false short-circuit with
  >   null element).
  > - setParameters slot allocation (runtimeParameters sized to configured,
  >   "live type" SQLFilterItemField / SQLFunctionRuntime slots stay null
  >   until execute).
  > - setParameters invokes function.config exactly once with the internal
  >   configuredParameters array reference AND POST-parsed content.
  > - execute resolver branches: SQLFilterItemField, nested SQLFunctionRuntime
  >   (recursion + iThis propagation), SQLFilterItemVariable, SQLPredicate
  >   (buggy + non-buggy paths), double-quote / single-quote strip, unquoted
  >   pass-through, null pass-through, nested SQLMethodRuntime pass-through
  >   (NOT recursed — pinned as asymmetry with SQLMethodRuntime.execute).
  > - Arity validation: too-few, too-many, equal-min-max single-number
  >   message, unequal-range format, maxParams==0 skip-all branch,
  >   maxParams==-1 upper-bound-skip variadic, exact lower/upper bound
  >   pass-through, AND a dedicated `executeArityCheckShortCircuitsBefore
  >   FunctionDispatch` using RecordingFunction.invoked to pin the
  >   BEFORE-dispatch invariant.
  > - getResult on fresh aggregator returns 0L, getResult after aggregate
  >   executes returns running total, setResult delegation, getValue threads
  >   record as iThis AND swallows RecordNotFoundException → null.
  >
  > **What was discovered:**
  > - **Latent production bug — SQLPredicate branch type-punning
  >   (ClassCastException)**: SQLFunctionRuntime.java:104 has
  >   `(iCurrentRecord instanceof EntityImpl ? (EntityImpl) iCurrentResult
  >   : null)`. The instanceof check tests `iCurrentRecord` but the cast
  >   applies to `iCurrentResult`. Because EntityImpl IS-A Result (via
  >   `EntityImpl extends RecordAbstract implements Entity`, and
  >   `Entity extends Result`), the true branch IS reachable whenever
  >   `iCurrentRecord` is entity-backed. When that happens AND
  >   `iCurrentResult` is a non-null, non-EntityImpl object, the cast
  >   throws ClassCastException. Author likely meant a self-consistent
  >   `(iCurrentResult instanceof EntityImpl ? (EntityImpl) iCurrentResult
  >   : null)` pattern. Pinned as falsifiable regression in
  >   `executeSQLPredicateBranchTypePunsResultAndEntityImplArgs` — passes
  >   an EntityImpl as iCurrentRecord and a String as iCurrentResult,
  >   asserts CCE + non-invocation of function.execute. After the Track 22
  >   fix the ternary would short-circuit to null and the test must flip
  >   from CCE-expected to success-expected.
  > - **Initial WHEN-FIXED pin premise was wrong (caught by review)**:
  >   the first commit's pin claimed `EntityImpl` was never a `Result`,
  >   making the true branch "dead code" — that's false (EntityImpl
  >   implements Entity which extends Result). The review agents
  >   (BC1 / CQ2 / TB1 / TS4 — four independent flags) corrected this.
  >   Carry-forward lesson: when a pin describes a latent bug, validate
  >   the type hierarchy with a grep before writing the comment. A
  >   "factually wrong but falsifiable-looking" pin can mislead future
  >   maintainers into incorrect fixes.
  > - **MultiValue+VALUE_NOT_PARSED continue guard**: the
  >   `(MultiValue.isMultiValue(v) && MultiValue.getFirstValue(v) ==
  >   VALUE_NOT_PARSED) continue;` branch at SQLFunctionRuntime.java:179-182
  >   is live and non-obvious. Triggered by passing `"[unparseableToken]"`
  >   through setParameters: SQLHelper.parseValue routes through LIST_BEGIN,
  >   the recursive call stores VALUE_NOT_PARSED into the list, and the
  >   guard detects this and skips the overwrite, leaving the raw string
  >   in configuredParameters. A regression removing the guard would leak
  >   a toxic List-with-sentinel downstream to function.execute.
  > - **Arity check non-dispatch invariant** was implicitly tested (throw
  >   observed) but not structurally pinned. Track 6/7 convention from
  >   Step 3 (RecordingFunction.invoked flag + assertFalse) was missing
  >   from the first commit — review TB2 flagged it. Added explicit test
  >   `executeArityCheckShortCircuitsBeforeFunctionDispatch` for both
  >   too-few and too-many cases with fresh RecordingFunctions to avoid
  >   state aliasing.
  > - **SQLFunctionRuntime has no iThis-null guard** (unlike
  >   SQLMethodRuntime). A null iThis propagates through execute without
  >   NPE because the resolver loop only iterates configuredParameters,
  >   not iThis. Noted but not pinned as a bug — this is a legitimate
  >   API difference (function.execute may not dereference iThis the
  >   same way method.execute does).
  > - **SQLFunctionRuntime.execute resolver is asymmetric with
  >   SQLMethodRuntime.execute** in ONE way: SQLMethodRuntime recurses
  >   into nested SQLMethodRuntime params; SQLFunctionRuntime does NOT
  >   recurse into nested SQLMethodRuntime params (it only recurses into
  >   nested SQLFunctionRuntime). The nested SQLMethodRuntime slot flows
  >   through unchanged via the pre-resolver seed. Pinned in
  >   `executeWithNestedSQLMethodRuntimeParameterIsNotRecursed`. Track 22
  >   should either align the two resolvers or document the intentional
  >   difference (MATCH / WITHIN / nested method chains don't appear in
  >   the SQLFunctionRuntime caller path, so the asymmetry may be
  >   deliberate — verify before changing).
  > - **session.newEntity() requires an open transaction**: needed for the
  >   type-pun CCE test. Pattern: `session.begin()` → test body →
  >   `tx.rollback()` in a finally. Carry-forward for Step 5/6 when
  >   populating entities for SQLHelper.parseDefaultValue /
  >   IndexSearchResult.merge contract tests that need non-trivial
  >   entity fixtures.
  > - **Review synthesis value**: five review agents flagged the same
  >   SQLPredicate-pin correctness issue with different specific details
  >   (BC1: type-hierarchy fact; TB1: falsifiability failure; CQ2: test
  >   name/comment inconsistency; TS4: pin quality). Synthesis avoided
  >   duplicate fixes — one rewrite addressed all four. This is the
  >   expected payoff from running multiple dimensions in parallel on a
  >   single diff.
  >
  > **What changed from the plan:** Scope said ~20-25 tests; actual is
  > 43 tests. The extra count is attributable to (a) dimensional-review-
  > driven regression pins for the SQLPredicate type-pun bug (3 new tests
  > total including companion + non-buggy-path control), (b) MultiValue
  > guard pin (TC1, 1 new test), (c) arity short-circuit invariant pin
  > (TB2, 1 new test combining too-few and too-many), (d) fresh-aggregator
  > getResult=0 (TC3, 1 new test), (e) null element in iEvaluate=false
  > (TC4, extended an existing test), plus the original resolver-branch
  > enumeration which was richer than the scope's bullet list anticipated
  > (each resolver type got a dedicated test rather than a single
  > combined case). The scope-indicator test count estimate is
  > systematically low for test-additive tracks under dimensional review —
  > same pattern as Steps 1-3.
  >
  > **Cross-track impact:**
  > - **Track 22 scope expands by one entry**: SQLPredicate type-pun
  >   (SQLFunctionRuntime.java:104). Joins the existing Track 22
  >   production-fix queue alongside the HashMap-race bucket
  >   (CustomSQLFunctionFactory, DefaultSQLMethodFactory),
  >   SQLMethodContains `&&→||` guard, SQLMethodNormalize iParams[0-vs-1]
  >   mix-up, SQLMethodField null-unguarded isArray NPE,
  >   DefaultSQLMethodFactory createMethod case-sensitivity,
  >   SQLMethodFunctionDelegate no-no-arg-ctor, AbstractSQLMethod
  >   getParameterValue AIOBEs.
  > - **Step 5 (sql root — SQLHelper scalar + dead-code pin)** gains
  >   a DbTestBase pattern note: tests that exercise SQLHelper.parseValue
  >   on inputs like `"[unparseableToken]"` should expect the raw string
  >   to flow through unchanged (due to the VALUE_NOT_PARSED guard).
  >   SQLHelper.parseDefaultValue and getValue need the same MultiValue
  >   awareness. Carry-forward: when a test needs an EntityImpl as
  >   iCurrentRecord / iCurrentResult, wrap in session.begin() +
  >   tx.rollback() pattern.
  > - **Step 6 (SQLHelper collection paths + SQLEngineSpiCache)** is
  >   the natural home for the LIST_BEGIN / MAP_BEGIN happy-path tests
  >   (where the recursive parse succeeds) — complementary to the
  >   VALUE_NOT_PARSED sad-path pinned here.
  > - No Component Map or Decision Record changes.
  >
  > **Step-level review iterations:** Ran iter-1 with 5 dimensions
  > (CQ, BC, TB, TC, TS) in parallel; 0 blockers + ~7 should-fix + ~15
  > suggestions. Applied all 7 should-fix items (including the
  > 4-agent-convergent SQLPredicate pin rewrite) plus 7 high-value
  > suggestions (TS2/CQ4 rename, TS7 rename, TB3 Javadoc sync, TB5
  > content assertion, TC3 fresh getResult, TC4 null+evaluate=false,
  > TS5/TS6/TS8 Javadoc tweaks). Deferred as suggestion-level:
  > TS1/CQ3/CQ5 (cross-file DRY for RecordingFunction/StubParser —
  > Track 22 DRY sweep), TB4 (non-RNF exception propagation — would
  > require non-final RecordingFunction), TC2 (variadic "1--1" ugly
  > message format pin — low-value WHEN-FIXED). Iter-2 gate check
  > deferred: context hit warning level (26%) after iter-1 fix-apply
  > cycle. Per workflow protocol, end session before next review
  > iteration. All fixes verified: 43 tests green, full
  > `./mvnw -pl core clean test` BUILD SUCCESS.
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/functions/SQLFunctionRuntimeTest.java`
  >   (new, 43 tests — 996 lines after review fix)
  >
  > **Critical context:** The SQLPredicate type-pun is the FIRST production
  > bug surfaced by Track 7 Step 4, and it's a ClassCastException waiting
  > for a predicate-caller that sets iCurrentResult to a non-null
  > non-EntityImpl. Because EntityImpl extends RecordAbstract implements
  > Entity extends Result (NOT the other way around — the review agents
  > caught a fundamental misreading of the hierarchy in the first commit),
  > this is a latent runtime hazard rather than dead code. Track 22's
  > production-side fix queue now includes this as a one-line instanceof
  > swap. Iter-2 gate check deferred — remaining review findings
  > documented above are either deferred with rationale or absorbed into
  > the commit diff.

- [x] Step 5: `sql` root — live classes (scalar SQLHelper + pure utils)
  + dead-code pin
  - [x] Context: critical
  > **What was done:** Added 153 `@Test` methods across 6 new test files
  > under `core/src/test/java/.../sql/`:
  > - `SQLHelperParseValueScalarTest` (42 tests, DbTestBase): scalar
  >   parseValue dispatch — sentinels, booleans, quoted strings, RID,
  >   numeric classification, $variable resolution, sub-command UOE branch
  >   with boundary-char pin, Integer.MAX/MIN/Long.MAX boundary
  >   classification, parseStringNumber direct coverage.
  > - `SQLHelperMiscTest` (27 tests, DbTestBase): getValue 1-arg/3-arg
  >   overloads including the live Entity-backed field-resolution path
  >   (wrapped with `session.begin()/rollback()`), getFunction no-match
  >   branches plus underscore-prefix positive branch (pinned via
  >   CommandSQLParsingException which proves the `_` branch dispatched to
  >   construction), BaseParser overload dispatch, SQLPredicate overload
  >   positional/named parameter wrapping.
  > - `CommandParametersTest` (19 tests, standalone): ctor variants with
  >   adopt-by-reference pin, set/getByName including null-key and
  >   null-value, getNext counter semantics with full message substring
  >   pins ("Parameter N", "Total parameters received: N"), reset
  >   preserves entries, mixed positional+named storage, iterator
  >   coverage, null-keyed retrieval.
  > - `IndexSearchResultTest` (33 tests, DbTestBase): canBeMerged
  >   isLong-guard (both sides via parsed `a.b` chain using SQLMethodField
  >   op), four-branch merge() dispatch pins (promotions between Equals,
  >   range, ContainsKey/Value/Contains), equals/hashCode including
  >   FieldChain distinct-instance inequality, two equals-NPE
  >   bug pins (null lastValue dereference + missing-key in
  >   fieldValuePairs), containsNullValues branch-2 bug pin
  >   (mergeFields uses `this.containsNullValues` = left's, dropping
  >   right's null flag), accumulator carry-over semantics.
  > - `RuntimeResultTest` (14 tests, DbTestBase): instance
  >   applyValue/getResult round-trip, static getResult null-input
  >   short-circuit, already-set-property skip with RecordingFunction
  >   invocation pin, SQLFunctionRuntime evaluation, filterResult exclusion
  >   matrix, canExcludeResult overwrite bug pin (two projections where
  >   first flips filterResult=true and second flips it back → flag lost),
  >   entriesPersistent dead-code reflection pin.
  > - `SqlRootDeadCodeTest` (13 tests, standalone): non-vacuous pins for
  >   CommandExecutorSQLAbstract constants, DefaultCommandExecutorSQLFactory
  >   empty-map + "Unknowned" typo literal pin, DynamicSQLElementFactory
  >   UUID-qualified marker assertion (robust against process-wide state
  >   mutation), Original/Updated RecordsReturnHandler asymmetric
  >   before/after storage (calls BOTH hooks and verifies only ONE stores),
  >   RecordCountHandler increment + reset.
  >
  > All 153 tests pass. Full core test suite clean.
  >
  > **What was discovered:**
  > - `SQLHelper.parseStringNumber` **suffix-strip bug**: classifies 'l',
  >   's', 'b', 'c', 'a', 't' suffixes correctly via `RecordSerializerStringAbstract.getType`,
  >   but then passes the raw string (suffix included) to
  >   `Long.parseLong`/`Short.parseShort`/`Byte.parseByte`/`BigDecimal(new)`/
  >   `Long.parseLong(date)` — all throw NumberFormatException instead of
  >   returning the typed value. Only 'f' and 'd' work end-to-end because
  >   `Float.parseFloat` / `Double.parseDouble` accept the trailing
  >   suffix. Pinned with classifier-type-AND-NFE double assertions.
  > - `SQLHelper.parseValue` eagerly calls `context.getDatabaseSession()`
  >   before any branch is taken — a bare `BasicCommandContext()`
  >   (no session) raises `DatabaseException("No database session
  >   found in SQL context")`. This forced `SQLHelperParseValueScalarTest`
  >   and `SQLHelperMiscTest` onto DbTestBase. Plan said "standalone"
  >   for those tests (per D2's "check dependencies" guidance); the
  >   execution agent decided per-class. Same pattern as Track 5/6 "some
  >   classes appear standalone but need a session".
  > - `Integer.MIN_VALUE` (`-2147483648`) is 11 characters including the
  >   sign. `RecordSerializerStringAbstract.getType` uses character-count
  >   (not digit-count) against `MAX_INTEGER_DIGITS=10`, so it's classified
  >   LONG. Value fits in int but type is Long. Minor quirk; pinned as
  >   WHEN-FIXED for Track 22.
  > - `IndexSearchResult.equals` has two latent NPEs: (1) line 161
  >   `lastValue.equals(that.lastValue)` NPEs when lastValue is null;
  >   (2) line 150 `that.fieldValuePairs.get(entry.getKey()).equals(entry.getValue())`
  >   NPEs when `that` is missing the key. Query-planner dedup relies on
  >   equals; these NPEs could cause non-deterministic plan collapse.
  >   Pinned for Track 22 via dedicated regression tests.
  > - `IndexSearchResult.mergeFields` uses `this.containsNullValues`
  >   (outer enclosing-class reference = left, the merge receiver) instead
  >   of `mainSearchResult.containsNullValues`. In branch 2 (this IS
  >   Equals, searchResult IS NOT), main = right (the range side) but
  >   the OR reads `left.containsNullValues || left.containsNullValues`
  >   — right's null flag is silently dropped. Pinned with a test where
  >   left is non-null and right carries null, observing the bug.
  > - `RuntimeResult.getResult` static line 73:
  >   `canExcludeResult = f.filterResult()` (assignment, not OR-assignment)
  >   overwrites the flag each iteration. If two SQLFunctionRuntime
  >   projections are iterated in LinkedHashMap order where the first has
  >   `filterResult=true` and the second has `filterResult=false`, the
  >   flag is clobbered and the empty-record-exclusion never fires.
  > - `RuntimeResult.entriesPersistent(Collection<Identifiable>)` has
  >   zero callers in `core/src/main`. Pinned via reflection so Track 22
  >   deletion flips the test red.
  > - `DynamicSQLElementFactory.FUNCTIONS/COMMANDS/OPERATORS` are
  >   process-wide static maps. COMMANDS is never mutated in production.
  >   Tests use UUID-qualified marker names for robust assertions in case
  >   future tests register entries. FUNCTIONS snapshot test uses
  >   `Map.copyOf(...)` for full key+value equality (keys-only snapshot
  >   was insufficient).
  > - `DefaultCommandExecutorSQLFactory.createCommand` error message has
  >   an "Unknowned" typo pinned literally so Track 22's typo fix flips
  >   the test red.
  > - `ReturnHandler` family contract asymmetry:
  >   `OriginalRecordsReturnHandler` stores on `beforeUpdate` and no-ops
  >   on `afterUpdate`; `UpdatedRecordsReturnHandler` does the inverse.
  >   Previous vacuous tests that only checked `reset()` → empty list
  >   were replaced with non-vacuous pins that call BOTH hooks and
  >   verify only ONE stores.
  >
  > **What changed from the plan:**
  > - Step 5 scope-indicator said "~50 test methods across 6 new test
  >   classes"; actual count is 153 test methods because review iteration
  >   added: TC1 Entity-Result resolution (3 tests), TC2 equals NPE pins
  >   (2), TC3 underscore-prefix positive (1), TC4 entriesPersistent pin
  >   (1), TC5 Long boundaries (2), TC6 null-key (1), BC2 branch-2
  >   null-propagation pins (2). No cross-track impact — Track 8's
  >   executor tests are independent of these additions.
  > - Both `SQLHelperParseValueScalarTest` and `SQLHelperMiscTest` became
  >   DbTestBase-backed (plan said "standalone"). Plan acknowledged per
  >   D2 that some classes need a session — documented in each file's
  >   Javadoc.
  > - Test run length increases by ~1–2 minutes due to DbTestBase lifecycle
  >   on ~70 additional tests using per-method in-memory DB. Acceptable
  >   given the coverage benefit; Track 22 could consolidate some suites
  >   into `@FixMethodOrder(NAME_ASCENDING)` single-DB patterns if
  >   flakiness emerges.
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/SQLHelperParseValueScalarTest.java` (new, 42 tests)
  > - `core/src/test/java/.../sql/SQLHelperMiscTest.java` (new, 27 tests)
  > - `core/src/test/java/.../sql/CommandParametersTest.java` (new, 19 tests)
  > - `core/src/test/java/.../sql/IndexSearchResultTest.java` (new, 33 tests)
  > - `core/src/test/java/.../sql/RuntimeResultTest.java` (new, 14 tests)
  > - `core/src/test/java/.../sql/SqlRootDeadCodeTest.java` (new, 13 tests)
  >
  > **Critical context:** Step-level code review iter-1 PASS across all
  > 5 dimensions (code-quality, bugs-concurrency, test-behavior,
  > test-completeness, test-structure) with 14 should-fix items, all
  > addressed in the review-fix commit. Iter-2 gate check deferred due
  > to critical context consumption (40% at end of iter-1). The iter-1
  > commit (`d318916cd`) tightens all 14 items with test verification;
  > a subsequent Phase C track-level review will provide a fresh-eyes
  > gate check on the accumulated Track 7 diff.
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

- [x] Step 6: `sql` root — SQLHelper collection paths + SQLEngine SPI cache
  - [x] Context: warning
  > **What was done:** Added 66 `@Test` methods across 2 new test files
  > (after iter-1 review fixes; 63 on the initial commit + 3 TC-fill tests).
  > - `SQLHelperParseValueCollectionTest` (29 tests, DbTestBase): LIST_BEGIN
  >   and MAP_BEGIN recursive dispatch of the 7-arg parseValue overload.
  >   Covers every propertyType × isMultiValue × isLink allocator branch
  >   (newEmbeddedList, newLinkList, newEmbeddedMap, newLinkMap), scalar-
  >   propertyType IllegalArgument defensive checks for both list and map,
  >   malformed-entry CommandSQLParsingException, @type-keyed map →
  >   entity promotion (null/embedded/link/scalar parentProperty),
  >   schemaClass-driven JSONSerializerJackson path (null/embedded/link/
  >   scalar parentProperty), schemaProperty-driven recursion (list via
  >   LINKLIST+linkedClass discriminator, map via linkedType), VALUE_NOT_PARSED
  >   sentinel leak in list branch, SQLPredicate retry in map branch,
  >   StringSerializerHelper.decode on map String values, nested list-of-
  >   maps/list-of-lists/map-of-list. `@After rollbackIfLeftOpen` idiom
  >   applied per Track 7 convention.
  > - `SQLEngineSpiCacheTest` (37 tests, SequentialTest + FixMethodOrder
  >   NAME_ASCENDING, DbTestBase): every SPI factory getter's lazy init +
  >   cache hit (with TB-3 reflection-verified container identity on
  >   FUNCTION_FACTORIES), aggregated-name queries (getFunctionNames,
  >   getMethodNames, getCollateNames), SORTED_OPERATORS identity-cached
  >   and rebuilt after registerOperator, getFunction/getFunctionOrNull
  >   happy path / case-insensitive / "any"-"all" short-circuit / unknown
  >   exception with quoted-name + available-names clause, getMethod
  >   case-insensitive masking DefaultSQLMethodFactory bug, getCollate
  >   known/unknown, getCommand empty-registry WHEN-FIXED pin and dynamic
  >   dispatch via TestDynamicCommand fixture (instanceof checks on exact/
  >   trim/prefix paths), registerFunction/unregisterFunction lowercasing
  >   + overwrite semantics, registerOperator ordering pin (add-then-clear
  >   contract + next-call rebuild), scanForPlugins WHEN-FIXED bug pin
  >   (only FUNCTION_FACTORIES cleared; 5 other caches stay stale).
  >   Snapshot-equality @After guards FUNCTIONS/COMMANDS/OPERATORS against
  >   static-state leak. Operator-set snapshots held under
  >   `synchronized (OPERATORS)` for robustness to SequentialTest-category
  >   removal. UUID-qualified prefix with lowercase-invariant assertion.
  >
  > **What was discovered:**
  > - Empty list `[]` returns an empty `EmbeddedList` (not a singleton
  >   containing `""`). Initial assumption — that smartSplit("") yields a
  >   singleton empty-string — was wrong; pinned as
  >   `parseEmptyListReturnsEmptyEmbeddedList`.
  > - `newEntity()` (no-arg, schemaClass=null, @type-promoted path)
  >   requires an active transaction because `newInstance` touches
  >   `FrontendTransactionNoTx.addRecordOperation`. `newEmbeddedEntity()`
  >   does NOT — embedded entities have no cluster. Three @type-promotion
  >   tests open a tx; rollback is handled by the `@After
  >   rollbackIfLeftOpen` safety net rather than inline try/finally.
  > - `newEmbeddedEntity(schemaClass)` rejects non-abstract classes with
  >   `DatabaseException("Embedded entities can be only of abstract
  >   classes")`. Tests that need a schemaClass for the embedded path
  >   must use `createAbstractClass`. Pattern worth flagging for future
  >   tracks that exercise embedded-entity allocation.
  > - `DefaultCommandExecutorSQLFactory` registers an empty `COMMANDS`
  >   map (hardcoded emptyMap) and `DynamicSQLElementFactory.COMMANDS`
  >   has no production writers. `SQLEngine.getCommandNames()` therefore
  >   returns an empty set in production; the entire `SQLEngine.getCommand`
  >   dispatch path is effectively dead code (consistent with Step 5's
  >   SqlRootDeadCodeTest pins on both factory classes). Pinned as
  >   `getCommandNamesMatchesSetupSnapshotBecauseProductionFactoriesRegisterNothingBugPin`
  >   comparing against `commandsBefore` snapshot rather than absolute
  >   `isEmpty()` — robust to prior-class leak.
  > - `SQLEngine.scanForPlugins()` only clears `FUNCTION_FACTORIES`,
  >   leaving `METHOD_FACTORIES`, `OPERATOR_FACTORIES`, `COLLATE_FACTORIES`,
  >   `EXECUTOR_FACTORIES`, `SORTED_OPERATORS` stale. Pinned with 6
  >   assertNotNull WHEN-FIXED markers for Track 22 to flip to assertNull.
  > - `SQLEngine.registerOperator` is non-atomic with respect to
  >   `SORTED_OPERATORS` rebuild. The OPERATORS.add and `SORTED_OPERATORS
  >   = null` writes happen outside `LOCK`. Single-threaded pin asserts
  >   add-happens-before-clear AND next-call rebuild contains the op; the
  >   actual multi-threaded stale-read race is explicitly deferred to
  >   Track 22's twin-fix with CustomSQLFunctionFactory +
  >   DefaultSQLMethodFactory ConcurrentHashMap conversion (same pattern
  >   as Track 6's BC1/TX1).
  > - Map-branch value-retry via SQLPredicate (SQLHelper line 240-242):
  >   a bareword like `foo` as a map value reaches `new SQLPredicate(ctx,
  >   "foo").evaluate(ctx)`, which promotes to SQLFilterItemField and
  >   raises `CommandExecutionException("expression item 'foo' cannot be
  >   resolved because current record is NULL")`. The exception itself
  >   is the observable proof that the retry fired — a regression that
  >   dropped the retry would leak VALUE_NOT_PARSED into the map instead.
  > - StringSerializerHelper.decode on map String values collapses `\\`
  >   → `\` as expected. Pinned with escaped-backslash literal.
  > - `SQLHelper.parseValue` with `propertyType != null && !isMultiValue()`
  >   and a bracketed/braced literal throws `IllegalArgumentException` with
  >   a message including the input and "property is not a collection"
  >   phrase. Two defensive-check branches (list + map) pinned.
  >
  > **What changed from the plan:** Step 6 scope-indicator said ~35 test
  > methods; final count is 66 because iter-1 review surfaced 3 missing
  > branch tests (TC-1 SQLPredicate retry, TC-2 decode, TC-3
  > schemaProperty map recursion) and because both scoped classes had
  > ~28 and ~35 tests after completing each branch enumeration. Two
  > tests from the original Step 6 commit (parseEmptyListReturnsEmbeddedListWithOneEmptyStringElement,
  > parseListWithSchemaPropertyRecursesWithLinkedTypeAndLinkedClass)
  > were revised during iter-1: the first because initial assumption
  > about smartSplit("") was wrong, the second because the original
  > EMBEDDEDLIST+INTEGER setup was indistinguishable from the
  > no-schemaProperty branch (fixed to LINKLIST+linkedClass
  > discriminator). No cross-track impact.
  >
  > **Key files:**
  > - `core/src/test/java/.../sql/SQLHelperParseValueCollectionTest.java` (new, 29 tests)
  > - `core/src/test/java/.../sql/SQLEngineSpiCacheTest.java` (new, 37 tests)
  >
  > **Critical context:** Step-level code review iter-1 ran 6 dimensional
  > sub-agents in parallel (code-quality, bugs-concurrency, test-behavior,
  > test-completeness, test-structure, test-concurrency): 0 blocker / 15
  > should-fix / 20 suggestion. Review-fix commit (`e9882bd51c`) applied
  > 11 should-fix items across all 6 dimensions (TB-1/2/3/4/5, TC-1/2/3,
  > CQ1, BC-1, TS-1, TX-3, `uuidPrefix` lowercase invariant). 4 should-fix
  > deferred: CQ2 (assertThrows helper), CQ3 (cache-hit helper method),
  > CQ8 (unqualify inner-class FQCNs), TS-2 (case-insensitive removeIf
  > predicate — resolved alternatively via setup-time lowercase-invariant
  > assert). 20 suggestion-grade items legitimately deferred for Track 22
  > or future iteration. Iter-2 gate check deferred due to context
  > consumption hitting warning level (30% → 33% after fixes); Phase C
  > track-level review will provide a fresh-eyes gate.
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

- [x] Step 7: `sql/query` — BasicLegacyResultSet + dead-code pin
  - [x] Context: warning
  > **What was done:** Added 109 standalone unit tests across 2 new files
  > for the `core/sql/query` package — 59 in `BasicLegacyResultSetTest`
  > (live class) and 50 in `SqlQueryDeadCodeTest` (four zero-caller dead
  > classes). Commits: `de81ad7419` (Step 7) and `4fd79739e6` (Review fix).
  >
  > Live-class coverage (BasicLegacyResultSet + LegacyResultSet interface):
  > List contract (add/addAll at boundary indices, clear, get, set,
  > contains, iterator, toArray both overloads, listIterator/subList
  > including boundary positions, setLimit honoured by add but bypassed
  > by addAll, copy independence, Externalizable round-trip including
  > null elements). Latent-bug pins (WHEN-FIXED): iterator exhaustion
  > guard uses strict `>` instead of `>=` → IOOBE from `underlying.get`
  > rather than NoSuchElementException; equals delegates to
  > synchronizedList contract (reflexive + symmetric across Lists);
  > UOE message copy-paste drift (`containsAll`, `removeAll`, `retainAll`
  > all throw `new UOE("remove")`).
  >
  > Dead-class coverage (ConcurrentLegacyResultSet, LiveLegacyResultSet,
  > LiveResultListener, LocalLiveResultListener): construction,
  > add/addAll, setCompleted/complete divergence (pinned via reflection
  > on the protected `completed` field — `LiveLegacyResultSet.setCompleted`
  > override has its `completed = true;` line commented out, only
  > `complete()` sets it), copy producing a fresh independent instance
  > (verified via reflection on `wrapped`), Externalizable round-trip,
  > UOE branches (one test per method for surefire diagnosability),
  > `LiveLegacyResultSet.setLimit` returning null (fluent-contract
  > break), `ConcurrentLegacyResultSet.lastIndexOf` always returning 0
  > (stub), iterator exhaustion mirror-pin for the strict-`>` guard in
  > the concurrent iterator, `LiveLegacyResultSet.iterator.next()`
  > InterruptedException branch exercised on a helper thread.
  > `LocalLiveResultListener` delegate forwarding + CommandResultListener
  > stubs (result→false, end→noop, getResult→null) + non-forwarding
  > pin (CommandResultListener half must NOT delegate to any
  > LiveResultListener callback). `LiveResultListener` interface
  > implemented with all three callbacks.
  >
  > **What was discovered:**
  > 1. **Iterator exhaustion strict-`>` guard bug (both classes).**
  >    `BasicLegacyResultSet.iterator()` and `ConcurrentLegacyResultSet`'s
  >    anonymous iterator both have the same guard
  >    `if (index > size() || size() == 0) throw NSE;`. After
  >    `next()` returns the only element, `index==size==1`, the guard
  >    is false, and the method falls through to `underlying.get(1)` /
  >    `wrapped.get(1)` — yielding IOOBE. Fix is `> → >=`. Pinned as
  >    WHEN-FIXED in both test classes for Track 22.
  > 2. **UOE message copy-paste drift on BasicLegacyResultSet.** The
  >    search-only methods `containsAll`, `removeAll`, `retainAll` all
  >    throw `new UnsupportedOperationException("remove")` — same
  >    message as the actual `remove` methods. `indexOf`/`lastIndexOf`
  >    correctly carry their own method names. Pinned with explicit
  >    `assertEquals("remove", message)` per branch for Track 22.
  > 3. **Deadlock hazard: `ArrayList.equals` uses `iterator()`, not
  >    `listIterator()`.** The JDK's `ArrayList.equalsRange` calls
  >    `o.iterator()` after iterating — and `ConcurrentLegacyResultSet`'s
  >    iterator `hasNext()` blocks on `waitForNewItemOrCompleted` when
  >    `completed==false`. So any `rs1.equals(rs2)` where `rs2` is a
  >    non-completed ConcurrentLegacyResultSet deadlocks. First run of
  >    the tests hung for 929s on
  >    `concurrentLegacyResultSetHashCodeAndEqualsDelegateToWrapped`
  >    before the deadlock-watchdog tripped. Fix: always call
  >    `setCompleted()` BEFORE the equals assertion. Added
  >    `@Rule Timeout.seconds(10)` to both test classes as a defensive
  >    net against a future edit dropping the setCompleted call
  >    (core/pom.xml has no `forkedProcessTimeoutInSeconds`, so
  >    without the @Rule an accidental hang would block the entire
  >    CI fork indefinitely).
  > 4. **`LiveLegacyResultSet.setCompleted` override has its
  >    `completed = true;` line commented out.** The public `complete()`
  >    method is the only path that sets the flag. Pinned via
  >    reflection on the parent's protected `completed` field.
  > 5. **`LiveLegacyResultSet.setLimit` returns null**, breaking the
  >    fluent LegacyResultSet contract (every other impl returns
  >    `this`). Annotated `@Nullable` in production. Pinned.
  > 6. **`ConcurrentLegacyResultSet.lastIndexOf` always returns 0**
  >    regardless of contents or argument — a stub. The sibling
  >    `indexOf` throws UOE. Pinned.
  > 7. **`LiveLegacyResultSet.iterator().next()` InterruptedException
  >    branch** was entirely unexercised in the first-pass test.
  >    Added a helper-thread test that pre-interrupts, calls next(),
  >    and verifies the branch returns null + re-raises the interrupt
  >    flag.
  > 8. **`addAll` ignores `setLimit`** while `add(T)` honours it — a
  >    contract asymmetry. Pinned as WHEN-FIXED.
  >
  > **What changed from the plan:** Test count grew from the planned
  > ~45 to 109 (59 + 50). Excess is driven by (a) one-UOE-per-branch
  > splitting (17 UOE branches on LiveLegacyResultSet + 6 on
  > ConcurrentLegacyResultSet + 7 on BasicLegacyResultSet = 30 small
  > tests) for surefire-diagnosability consistency with Step 6's
  > methodology, (b) WHEN-FIXED latent-bug pins surfaced during
  > implementation (8 markers), (c) boundary tests added after
  > dimensional review (zero/negative capacity, addAll-below-limit,
  > setLimit-below-current-size, insertion at index 0/size,
  > listIterator at size, subList 0,0 / size,size, negative-index get,
  > Externalizable with null, non-forwarding pin on
  > LocalLiveResultListener.end/result/getResult).
  >
  > **Step-level dimensional review** (1 iteration, 6 agents:
  > code-quality, bugs-concurrency, test-behavior, test-completeness,
  > test-concurrency, test-structure). Verdicts: all PASS (0 blockers
  > across all dimensions). 17 should-fix items converged on 7 themes,
  > all addressed in the Review fix commit: (1) vacuous setCompleted
  > pin → use reflection on `completed` field; (2) misleading
  > equals-asymmetry Javadoc → rewrite to match the actual List-
  > contract pin; (3) bundled UOE ladder tests → split one-per-method;
  > (4) vacuous isEmpty-double-check pin → delete test, document in
  > class Javadoc why no pin is possible; (5) vacuous copy pin for
  > LiveLegacyResultSet → use reflection on `wrapped` to verify
  > independence; (6) 7 missing boundary tests → added; (7) no
  > defensive hang protection → `@Rule Timeout.seconds(10)` on both
  > files. Hygiene suggestions absorbed: java.io imports (dropped
  > inline package prefixes), tautological `assertSame(adapter,
  > (Interface) adapter)` removed, duplicate `assertNotNull` in
  > liveLegacyResultSetCopy removed, UOE message pins tightened on
  > BasicLegacyResultSet (exposing the copy-paste drift as a TC#2
  > discovery), LocalLiveResultListener stub test now explicitly pins
  > "must NOT forward to delegate" via counters.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/query/BasicLegacyResultSetTest.java` (new — 59 tests, ~700 lines)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/query/SqlQueryDeadCodeTest.java` (new — 50 tests, ~720 lines)
  >
  > **Critical context:** The `ArrayList.equals` → `iterator()` →
  > `waitForNewItemOrCompleted` blocking chain is a real hang trap
  > for any future test in this package that compares
  > ConcurrentLegacyResultSet instances. The class-level `@Rule
  > Timeout.seconds(10)` surfaces this as a bounded failure rather
  > than a silent CI fork hang — keep this rule when Track 22
  > eventually deletes the dead classes (the rule itself can be
  > removed at that point along with the tests).
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
