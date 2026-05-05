# Unit Test Coverage — Core Module — Track Details

<!-- DO NOT DELETE THIS FILE. Its presence on disk signals the new
split-file plan format (see .claude/workflow/conventions.md §1.2).
Deleting it flips subsequent workflow operations into legacy mode.
Natural cleanup happens when the branch is deleted after PR merge. -->

## Track 17: Security

> **What**:
> - `core/metadata/security` (593 uncov, 72.3%) — security metadata
>   (Role, Identity, SecurityPolicyImpl, resource classes)
> - `core/security` (548 uncov, 32.1%) — core security
>   (SecurityManager, TokenSign, password hashing)
> - `core/security/authenticator` (140 uncov, 25.5%) —
>   authenticators (DefaultPassword, DatabaseUser)
> - `core/security/symmetrickey` (282 uncov, 26.6%) — symmetric key
>   security
> - `core/metadata/security/binary` (164 uncov, 0.0%) — binary
>   token serialization
> - `core/metadata/security/jwt` (10 uncov, 0.0%) — JWT tokens
> - `core/metadata/security/auth` (9 uncov, 0.0%) — auth info
> - `core/security/kerberos` (114 uncov, 0.0%) — Kerberos auth
>
> **How**:
> - Cover password hashing (PBKDF2 round-trip, salt handling),
>   token sign/verify (HMAC, JWT), role/permission checks (allow/deny
>   matrix), and authenticator chain dispatch (try-each, fall-through,
>   first-match).
> - Symmetric key tests cover key creation, encrypt/decrypt round-trip,
>   key rotation, and serialization shape.
> - Kerberos tests must be limited (no Kerberos infrastructure in test
>   env) — pin construction and rejection paths only.
> - Binary token tests follow the round-trip pattern from Tracks 12–13.
> - Carry forward Tracks 5–16 conventions.
>
> **Constraints**:
> - In-scope: only the listed `core/security*` and
>   `core/metadata/security*` packages.
> - Do NOT introduce real network or external Kerberos KDC
>   dependencies — mock or skip those paths.
> - Security-test secrets must be constants in the test, never read
>   from env vars or files outside the test module.
>
> **Interactions**:
> - Depends on Track 1 and benefits from Track 16 (schema for user/role
>   classes).

## Track 18: Index

<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
> **Interactions**:
> - Depends on Track 1.

## Track 22: Transactions, Gremlin & Remaining Core

> **What** (core target packages — the main sweep):
> - `core/tx` (572 uncov, 61.8%) — transaction management
> - `core/gremlin` (713+166+57+34 uncov) — Gremlin integration
>   (excluding schema classes per constraint 7)
> - `core/engine` (121+21+1 uncov) — engine lifecycle
> - `core/exception` (230 uncov, 40.9%) — exception hierarchy
>
> **What** (smaller target packages):
> - `core/id` (125 uncov, 64.2%) — ID generation
> - `core/compression/impl` (104 uncov, 0.0%) — compression
> - `core/config` (64 uncov, 66.1%) — configuration
> - `core/cache` (60 uncov, 71.4%) — cache utilities
> - Small packages: conflict, dictionary, servlet, replication, type,
>   collate, api/*
>
> **How**:
> - TX tests need a database session to verify begin/commit/rollback
>   semantics (`DbTestBase`).
> - Gremlin tests use `GraphBaseTest`.
> - Engine lifecycle tests verify engine registration via SPI
>   (`META-INF/services`) and lifecycle hooks.
> - Remaining packages are a mix of standalone and DB-dependent tests
>   — the execution agent decides per test class.
> - Carry forward Tracks 5–21 conventions.
>
> **What — inherited DRY / cleanup scope from earlier tracks** (queued
> work absorbed during Tracks 7–13; the agent applies the cleanup as a
> mix of test refactors, dead-code deletions, production-bug fixes, and
> production-asserts):
>
> *From Track 7 iter-1 (CQ3, TS5):* Extract shared test fixtures to
> `test-commons` (or a package-private `SqlTestFixtures` helper in
> `core.sql`): `RecordingFunction` (currently duplicated across
> `SQLMethodRuntimeTest`, `SQLFunctionRuntimeTest`, `RuntimeResultTest`,
> `SQLMethodFunctionDelegateTest`), `StubParser` (duplicated in
> `SQLMethodRuntimeTest` and `SQLFunctionRuntimeTest`), and
> `StubMethod`/`ProbeMethod` (`DefaultSQLMethodFactoryTest` and
> `SQLMethodRuntimeTest`). Consider a builder-pattern
> `RecordingFunctionBuilder`.
>
> *From Track 7 iter-1 (TS3, TS6):* Split oversized test classes:
> `SQLFunctionRuntimeTest` (997 lines) and `SQLMethodRuntimeTest`
> (834 lines) each into 3 focused suites (`setParameters` / `execute` /
> `arity+lifecycle`); `SQLEngineSpiCacheTest` (903 lines) into
> factory-caching / dispatch / registration suites sharing the
> `@After verifyNoStaticStateLeak` base.
>
> *From Track 7 iter-1 (TS4, TS7, TS9):* Convert repetitive test
> groups to `@Parameterized`: six `SQLMethodAs*Test` classes; 8
> `concurrentLegacyResultSet*ThrowsUnsupported` methods in
> `SqlQueryDeadCodeTest`; three sequence tests
> (`SQLMethodCurrent`/`Next`/`Reset`) via shared abstract base.
>
> *From Track 7 iter-1 (TX5):* Stage multi-threaded race-exercising
> tests (`CyclicBarrier` + `CountDownLatch` + `ConcurrentLinkedQueue`)
> paired with each WHEN-FIXED production-side race fix:
> `CustomSQLFunctionFactory` HashMap, `DefaultSQLMethodFactory` HashMap,
> `SQLEngine.registerOperator` non-atomic `SORTED_OPERATORS` clear,
> `SQLEngine.scanForPlugins` partial cache clear.
>
> *From Track 7 iter-1 (CQ1, TC3):* Normalize malformed nested-asterisk
> Apache-2 license banner across 10 `sql/*Test.java` +
> `sql/query/*Test.java` files to match the canonical single-asterisk
> banner. Add unicode / surrogate-pair / Turkish-locale coverage to the
> string-method tests (`SQLMethodToLowerCase`/`ToUpperCase`/`Trim`/
> `Split`/`CharAt`) so a regression from `Locale.ENGLISH` pinning
> would be caught.
>
> *From Track 8 Phase C iter-1 (CQ1/TS1, CQ2/TS2, CQ3):* Hoist the
> duplicated executor-test helpers into `TestUtilsFixture`:
> (a) `protected BasicCommandContext newContext()` (duplicated in ~45
> executor test files), (b) `protected ExecutionStepInternal
> sourceStep(CommandContext, List<? extends Result>)`, (c)
> `protected static List<Result> drain(ExecutionStream, CommandContext)`,
> (d) `protected static String uniqueSuffix()`. Extract the
> `streamOfInts` / `CloseTracker` / `NoOpStep` trio (duplicated across
> `ExecutionStreamWrappersTest`, `ExpireTimeoutResultSetTest`,
> `InterruptResultSetTest`) into a package-private helper alongside
> `LinkTestFixtures` in `core/sql/executor/resultset/` (e.g.
> `StreamTestFixtures`). Replace duplicates file-by-file.
>
> *From Track 8 Phase C iter-1 (CQ4):* Replace inline fully-qualified
> class names with explicit imports — chiefly `SQLOrBlock` /
> `SQLNotBlock` in `FetchFromIndexStepTest`, `DatabaseSessionEmbedded`
> and `ExecutionStreamProducer` in `ExecutionStreamWrappersTest`, and
> the `RID` FQN in `SmallPlannerBranchTest`.
>
> *From Track 8 Phase C iter-1 (CQ8, TS8):* Audit executor tests for
> manual `try { … session.commit(); } catch { rollback; throw }`
> boilerplate that duplicates the `TestUtilsFixture.rollbackIfLeftOpen`
> safety net. Keep explicit `session.rollback()` only where the test
> deliberately rolls back as a success-path expectation; drop the
> duplicative catch in the rest.
>
> *From Track 8 Phase C iter-1 (TC3–TC9, TC12):* Eight executor
> corner-case pins deferred to the final sweep:
> (TC3) `CreateRecordStep total<0` → empty stream;
> (TC4) `UpdateRemoveStep` / `UpdateSetStep` / `UpdateMergeStep` /
> `UpdateContentStep` non-`ResultInternal` pass-through path;
> (TC5) `FetchFromCollection` unknown / negative collection ID;
> (TC6) `FetchFromClass` partial `collections`-filter subset matrix
> (retain only a subclass's collection id while excluding the parent's);
> (TC7) `LetExpressionStep` subquery-throws exception propagation
> (parallel pin in `LetQueryStepTest`);
> (TC8) direct `SkipExecutionStep → LimitExecutionStep` composition test
> (SKIP 2 LIMIT 3 over 6 rows → rows 3-5);
> (TC9) `UpsertStep` multi-row upstream matches behavior;
> (TC12) `InsertValuesStep` rows<tuples boundary (only first N tuples
> applied).
>
> *From Track 8 Phase C iter-1 (suggestion tier, 37 items absorbed):*
> CQ5–CQ7, CQ9–CQ10 (test-class splits, field-access patterns, license
> banner, generator unification); BC1–BC2 (deterministic-clock for
> `AccumulatingTimeoutStep`, `reached[0]` assertion simplification);
> TB8–TB9 (RID-equality pin in `ResultInternalTest`, WHEN-FIXED javadoc
> marker on `onCloseIsNotIdempotentOnRepeatedClose`); TC13–TC21
> (Unwind-absent-field, ForEach prev==null, EmbeddedList negative
> indices, EmbeddedSet add(null), EmbeddedMap compute/merge exception,
> LimitedExecutionStream limit==MAX_VALUE, UpdatableResult toJSON
> round-trip, IfStep runtime nested-IF, RetryStep ExecutionThreadLocal
> interrupt); TS3, TS6–TS7, TS9–TS14 (test-class splits for
> `ResultInternalTest` / `FetchFromIndexStepTest` /
> `ExecutionStreamWrappersTest`, `LinkTestFixtures` rename, Step-9
> `Abstract*Base` rationale note, license banner consistency, short
> class-name clarity, SoftThread cleanup comment, `RetryStep` residual
> boilerplate that duplicates the `TestUtilsFixture.rollbackIfLeftOpen`
> safety net. Keep explicit `session.rollback()` only where the test
> deliberately rolls back as a success-path expectation; drop the
> duplicative catch in the rest.
>
> *From Track 8 Phase C iter-1 (TC3–TC9, TC12):* Eight executor
> corner-case pins deferred to the final sweep:
> (TC3) `CreateRecordStep total<0` → empty stream;
> (TC4) `UpdateRemoveStep` / `UpdateSetStep` / `UpdateMergeStep` /
> `UpdateContentStep` non-`ResultInternal` pass-through path;
> (TC5) `FetchFromCollection` unknown / negative collection ID;
> (TC6) `FetchFromClass` partial `collections`-filter subset matrix
> (retain only a subclass's collection id while excluding the parent's);
> (TC7) `LetExpressionStep` subquery-throws exception propagation
> (parallel pin in `LetQueryStepTest`);
> (TC8) direct `SkipExecutionStep → LimitExecutionStep` composition test
> (SKIP 2 LIMIT 3 over 6 rows → rows 3-5);
> (TC9) `UpsertStep` multi-row upstream matches behavior;
> (TC12) `InsertValuesStep` rows<tuples boundary (only first N tuples
> applied).
>
> *From Track 8 Phase C iter-1 (suggestion tier, 37 items absorbed):*
> CQ5–CQ7, CQ9–CQ10 (test-class splits, field-access patterns, license
> banner, generator unification); BC1–BC2 (deterministic-clock for
> `AccumulatingTimeoutStep`, `reached[0]` assertion simplification);
> TB8–TB9 (RID-equality pin in `ResultInternalTest`, WHEN-FIXED javadoc
> marker on `onCloseIsNotIdempotentOnRepeatedClose`); TC13–TC21
> (Unwind-absent-field, ForEach prev==null, EmbeddedList negative
> indices, EmbeddedSet add(null), EmbeddedMap compute/merge exception,
> LimitedExecutionStream limit==MAX_VALUE, UpdatableResult toJSON
> round-trip, IfStep runtime nested-IF, RetryStep ExecutionThreadLocal
> interrupt); TS3, TS6–TS7, TS9–TS14 (test-class splits for
> `ResultInternalTest` / `FetchFromIndexStepTest` /
> `ExecutionStreamWrappersTest`, `LinkTestFixtures` rename, Step-9
> `Abstract*Base` rationale note, license banner consistency, short
> class-name clarity, SoftThread cleanup comment, `RetryStep` residual
> rationale update); TX1, TX3–TX8 (wall-clock determinism,
> `AtomicBoolean`/`Integer` hygiene, `TimeoutStep RETURN sendTimeout`
> symmetry, `RetryStep` concurrent-tx integration-test note,
> `InterruptResultSet` mid-iteration interrupt, `ParallelExecStep`
> mid-sub-plan throws propagation).
>
> *From Track 9 Phase A reviews (T1/R1, T2, T3, T4, R2):* Seven dead or
> semi-dead command/script code regions pinned via
> `// WHEN-FIXED: Track 22` markers in Track 9 Step 1 and Step 5; Track
> 22 deletes/simplifies:
> (a) `CommandExecutorScript` (719 LOC — only reachable through
> `SQLScriptEngine.eval(Reader, Bindings)` which has no production
> callers; `CommandScript.execute` is a `List.of()` stub with no
> `CommandManager.commandReqExecMap` routing);
> (b) `CommandScript` (114 LOC — see (a));
> (c) `CommandManager`'s class-based legacy dispatch cluster
> (`commandReqExecMap` + `configCallbacks` +
> `registerExecutor(Class,Class,...)` + `unregisterExecutor(Class)` +
> `getExecutor(CommandRequestInternal)` — zero callers; the live path
> is `scriptExecutors` map + `getScriptExecutor`);
> (d) `ScriptExecutorRegister` SPI (zero `META-INF/services` entries,
> zero implementations in core);
> (e) `ScriptInterceptor` + `ScriptInjection` SPIs if kept with
> zero-impl register/unregister loops (consolidated SPI-wiring smoke
> tests will give minimal positive coverage; remaining code is
> production-no-op);
> (f) deprecated `ScriptManager.bind(...)` / `bindLegacyDatabaseAndUtil`
> + `ScriptDocumentDatabaseWrapper` (261 LOC) +
> `ScriptYouTrackDbWrapper` (42 LOC) — reachable only via
> `Jsr223ScriptExecutor.executeFunction` for stored JS functions;
> Track 9 covers the live method subset via a stored-function test and
> pins the rest;
> (g) `SQLScriptEngine.eval(Reader, Bindings)` — routes to the dead
> `CommandScript.execute` stub; only `eval(String, Bindings)` +
> `convertToParameters` are live.
> Also absorbed: `BasicCommandContext.copy()` null-child NPE (T4,
> Track 9 Step 2 pins via expect-NPE + WHEN-FIXED); `TraverseTest.java
> :56-72` dead `activeTx*` local variables (T9, readability cleanup).
>
> *From Track 9 Step 2 iter-1 dimensional review (5 agents; 0 blockers,
> 14 should-fix, 14 suggestions):* Most should-fix items fixed in-step
> via commit `10eac73c8a`. Deferred:
> (TB-4) companion positive assertion for `$PARENT.unknownField` —
> `BasicCommandContext` has no clean JavaBean field reachable via
> `EntityHelper.getFieldValue` reflection, making a falsifiable positive
> pin fragile; absorbed with the `copy()` T4 cleanup.
> (TC-1) `getVariables()` self-overrides-child precedence test — minor
> observable; no production caller depends on the direction today.
> (TC-2) `setParentWithoutOverridingChild` test — isolated method, no
> callers in core would regress; coverage when SQL sub-query planners
> reachable through this method land.
> (TC-3) direct `hasVariable` branch tests — indirectly covered by the
> TB-3 tightening in
> `BasicCommandContextTest.testSetVariableExistingInParent`;
> re-evaluate if JaCoCo still shows uncovered branches.
> Plus ~13 suggestion-tier items (CQ-3..CQ-6 `assertNotSame`/`assertNull`
> idiom consistency, TS4 shared stub helper, TS5 pre-existing
> no-javadoc tests in `SqlScriptExecutorTest`, TS6 expose-wrapper
> naming, TS7 `_T4Pin` method-name suffix, TB-5 reference-identity
> mutation pin, TB-6 setChild-null-idempotency, TB-7 toString regex
> relaxation, TC-8 `convertToParameters` single-null corner, TC-9
> retry-conflict data scenario, TC-10 parameterized positional scalars,
> TC-11 `setChild` replacement observable) fold into existing DRY/
> cleanup scope.
>
> *From Track 9 Phase C iter-1 (CQ1, CQ2, CQ3):* Three DRY candidates
> deferred after the Phase-C dimensional review of all Track 9 test
> files (commit `f66b1bc474`):
> (CQ1) Hoist the hand-rolled `@After rollbackIfLeftOpen` safety net
> currently duplicated in `TraverseTest`, `TraverseContextTest`, and
> the bespoke `restoreAllowedPackagesAndRollbackIfLeftOpen` on
> `ScriptManagerTest` / `closeExecutor` on `PolyglotScriptExecutorTest`
> into `TestUtilsFixture` so that `DbTestBase`-extending Track-9 tests
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
> in commit `24d5a3d967`. Only the static helper
> `embeddedMapFromStream(...)` (used by `SQLHelper.parseValue` and
> `EntityHelper`) is live and stays. Aggregate uncovered lines on this
> class: 360 of 402 (89.6% uncovered) — dominates the residual gap on
> `core/serialization/serializer/record/string`.
> (b) **`RecordSerializerStringAbstract` abstract-instance API
> deletion** (588 LOC, of which ~200 LOC are abstract instance methods;
> pinned in `RecordSerializerStringAbstractDeadCodeTest`): four unused
> public statics on the same class (zero callers in `core/`, `server/`,
> `driver/`, `embedded/`, `gremlin-annotations/`, `tests/`,
> `test-commons/`, `docker-tests/`). The live static helpers
> (`getType(String)`, `getTypeValue(...)`, `simpleValue*`,
> `embeddedMapFromStream(...)`) stay. After (a) and (b) deletions,
> `RecordSerializerCsvAbstractEmbeddedMapTest` and
> `RecordSerializerStringAbstractStaticsTest` /
> `RecordSerializerStringAbstractSimpleValueTest` continue to pin the
> live helper subset.
> (c) **`JSONWriter` deletion** (511 LOC; pinned in
> `JSONWriterDeadCodeTest`): zero callers in `core/`, `server/`,
> `driver/`, `embedded/`, `gremlin-annotations/`, `tests/`,
> `test-commons/`, `docker-tests/`. Despite living next to live
> `JSONReader` (which has one production caller — `DatabaseImport`),
> `JSONWriter` is fully orphaned and accounts for 158 of the 362
> uncovered lines on `core/serialization/serializer`.
> (d) **`Streamable` interface + `StreamableHelper` deletion** (176
> LOC of helper + the marker interface; pinned in
> `StreamableInterfaceDeadCodeTest` and `StreamableHelperDeadCodeTest`):
> the `Streamable` interface has **zero implementors** in the project;
> `StreamableHelper.{toStream,fromStream}` are reachable only through
> that dead interface. Cross-module grep confirms no external callers.
> The `StreamableHelper$1` inner class (2 lines) is part of the same
> dead surface.
> (e) **`SerializationThreadLocal` listener / shutdown path deletion**
> (54 LOC; pinned in `SerializationThreadLocalDeadCodeTest`): the
> `addListener` / `removeListener` API and the listener-dispatch
> shutdown path have zero readers; only the per-thread
> `ThreadLocal<Map>` accessor stays live. The
> `SerializationThreadLocal$1` synthetic inner class accounts for 3 of
> the 3 uncovered lines on `core/serialization/serializer/record`.
> (f) **Residual coverage gap on `JSONSerializerJackson`'s
> `IMPORT_BACKWARDS_COMPAT_INSTANCE` legacy 1.x export branches** (~5
> percentage points on the live class — outer-class coverage 80.0%
> line / 70.1% branch; pinned in
> `JSONSerializerJacksonImportBackwardsCompatTest` Javadoc): the
> pre-version-14 export branch at `DatabaseImport.java:416` is
> reachable only through `DatabaseImport` of legacy 1.x export files.
> Constructing the full 1.x schema/RID layout end-to-end is
> disproportionate to the marginal coverage gain since the four flag
> distinctions (`oldFieldTypesFormat`, `unescapedControlChars`,
> `replacements`, `readAllowGraphStructure`) are already individually
> pinned via the import-mode test files. Track 22 + Track 15 (`db/tool`
> integration) jointly own the residual; if the legacy 1.x exporter
> compatibility is dropped from the product, the branch can be deleted
> outright.
> (g) **Residual coverage gap on `StringSerializerHelper`** (live
> class, 68.2% line / 60.3% branch; 182 of 573 lines uncovered): Track
> 12's scope was "extensions" only — the existing
> `StringSerializerHelperTest` baseline plus targeted new pins. The
> remaining gap is in low-level parser branches (escape handling,
> multi-quote splits, edge-case empty-string returns) and in helper
> methods that are dead in the post-CSV-deletion surface. After (a)–(c)
> deletions land, re-measure; if still below target, decide between
> extending the test or marking the residual lines as dead.
> (h) **Residual coverage gap on `MemoryStream`** (62.3% line / 58.0%
> branch; 69 of 183 lines uncovered): per Phase A cross-track decision,
> Track 12's `MemoryStreamTest` covers the raw read/write/grow/move/
> copyFrom primitives only. `RecordId*` and `RecordBytes` round-trips
> that exercise the remaining surface are deferred to Track 14 (DB Core
> & Config) / Track 15 (Record Implementation & DB Tool).
> `MemoryStream` is `@Deprecated` but still used by `RecordId*` /
> `RecordBytes` / `CommandRequestTextAbstract` / Track-9-pinned-dead
> `CommandScript`, so deletion is gated on those callers being migrated
> first.
> (i) **Residual coverage gap on `UnsafeBinaryConverter`** (live class,
> 75.8% line / 50.0% branch; 31 of 128 lines uncovered): the
> `Safe/UnsafeConverterTest` repair in Step 1 + extensions in Step 3
> cover the round-trip and offset edge cases; the residual is the
> platform-detection cold path (`UnsafeBinaryConverter$1` 60.0% line —
> synthetic inner class for static initializer) and
> `nativeAccelerationUsed` returns whose `MEMORY_USE_UNSAFE` toggle is
> exercised process-wide. Re-measure after `BinaryConverterFactory`
> pinning lands; if irreducible, mark as out-of-scope-by-design.
> (j) **Residual coverage gap on `StreamSerializerRID`** (live class,
> 82.6% line / 100.0% branch; 4 of 23 lines uncovered): the
> `StreamSerializerRIDTest` extension in Step 3 covers the primary
> serialize/deserialize round-trip; the 4 uncovered lines are an
> unused two-arg constructor + a deprecated wrapper method that
> delegates to the primary method. Pinned via shape assertion; delete
> in the same sweep as (a)–(e).
> (k) **Pre-existing inert converter tests are repaired** (Step 1 —
> committed in `683189c1a3` + iter-1 review fix `4ce8111501`):
> `SafeConverterTest`, `UnsafeConverterTest`, and
> `AbstractConverterTest` were declaring eight `testPut*` methods each
> but **zero `@Test` annotations**, so JUnit 4 silently never ran any
> of them. The `@Test` annotations are now in place, the
> `Assert.assertEquals(byte[], byte[])` calls (which resolved to the
> `Object` overload — reference identity) are replaced with
> `Assert.assertArrayEquals(expected, actual)`, and the scalar argument
> order is corrected to `(expected, actual)`. The abstract base now
> uses `protected final assertPut*RoundTrips()` helpers + per-subclass
> `@Test public void put*RoundTrips()` methods (codebase-idiomatic
> shape; precedent `AbstractComparatorTest`). Result: 16 newly-active
> tests on the `common/serialization` surface; baseline
> `common/serialization` coverage corrected from the pre-fix inflated
> **34.5% line / 27.1% branch** to the actual post-fix **82.1% line /
> 61.4% branch**, against which Track 12's other Steps were measured.
> No deletion item — this is a pure test-quality fix recorded for
> traceability.
> No Track 12-specific DRY items: round-trip tests are scoped per
> serializer instance (default vs. import vs. import-backcompat) and do
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
<<< RECOVERY GAP — original line not present in any agent transcript >>>
> Pinned today as `assertNotSame`-style anomaly; WHEN-FIXED flips to
> `assertSame` once the factory is harmonized.
> (h) **`MockSerializer.preprocess` returns null instead of input** —
> sentinel-shape divergence from the conventional contract. Folded
> into the (d) deletion scope.
> (i) **`BytesContainer` overload of `deserializeValue` lacks
> length-prefix validation** that its `ReadBytesContainer` sibling has.
> Production callers that feed attacker-controlled bytes through the
> `BytesContainer` path include `EntitySerializerDelta.deserialize`
> and `EntityImpl.deserializeFieldForComparison`. STRING/BINARY varint
> length up to `Integer.MAX_VALUE` and DECIMAL `unscaledLen` flow into
> `new String(bytes, off, len, UTF-8)` / `new byte[n]` /
> `Arrays.copyOfRange` without bounds checks. Pinned via WHEN-FIXED
> note in the test class Javadoc.
> (j) **`HelperClasses.readLinkCollection` NULL_RECORD_ID
> null-conversion branch is dead**. The
> `if (id.equals(NULL_RECORD_ID)) found.addInternal(null)` branch
> (`HelperClasses.java:408`) is unreachable because
> `EntityLinkListImpl.addInternal` routes through
> `LinkTrackedMultiValue.checkValue` which rejects `null`. Same shape
> at `HelperClasses.java:457` for LINKMAP. Either delete the branch,
> or relax `checkValue` to permit `null` so legacy-byte streams
> carrying the sentinel survive.
> (k) **`RecordSerializerBinaryV1.deserializeEmbeddedAsDocument`
> insecure deserialization**: `Class.forName(className).newInstance()`
> on attacker-controlled bytes BEFORE the `EntitySerializable` cast,
> allowing side-effecting constructors of arbitrary public no-arg
> classes on the classpath. Fix: gate on
> `EntitySerializable.class.isAssignableFrom(clazz)` before
> `newInstance()`, ideally with an allow-list.
> (l) **`EntitySerializerDelta.deserializeValue` insecure
> deserialization**: same gadget vector as (k) but in the EMBEDDED
> branch (lines 1185-1201). Reachable only from embedded transport
> today; needs the same `isAssignableFrom` + allow-list fix.
> (m) **`EntitySerializerDelta` unbounded item-count loops**:
> `readEmbeddedList`/`Set`/`Map` and `readLinkList`/`Map`/`Set`/`Bag`
> accept `varint(zigzag(MAX_INT))` followed by trailing bytes and drive
> a tight loop. Reachable only from embedded transport; pinnable via
> `@Test(timeout=…)` WHEN-FIXED scaffolding.
> (n) **`BinaryComparatorV0` DATE × STRING isEqual NFE crash**: line
> 501 routes STRING and DECIMAL through the same arm, calling
> `DecimalSerializer.deserialize` on the STRING-encoded bytes. STRING
> wire format is `varint(length) + UTF-8 bytes`, NOT
> `int scale + int unscaledLen + unscaled bytes`, so the deserialiser
> interprets the leading bytes as scale+length+payload and crashes
> (d) **`MockSerializer`** (sentinel placeholder; pinned in
> `MockSerializerDeadCodeTest`). Deletion needs lockstep removal of
> the `BinarySerializerFactory` registration for
> `PropertyTypeInternal.EMBEDDED` (id `-10`); rename suggestion
> `EmbeddedTypeSentinelSerializer` (or similar) noted in the
> `*DeadCodeTest` Javadoc.
> The `RecordSerializerNetwork` interface is in scope-but-disjoint —
> its concrete implementation lives in `driver/` (Non-Goals); pinned
> only as a shape reference, no deletion needed.
>
> *Latent production bugs pinned with WHEN-FIXED markers* (Track 22
> owns the production-side fix):
> (e) **`BytesContainer` infinite-loop hang on zero-capacity
> construction**: `new BytesContainer(new byte[0])` followed by
> `c.alloc(N>0)` hangs the JVM indefinitely — `resize()` multiplies
> `newLength` (initially 0) by 2 and never reaches `offset > 0`.
> Reachable via the public byte-array constructor. Pinned via
> `@Test(timeout=…)` scaffold.
> (f) **`RecordSerializerBinary.fromStream(byte[])` asymmetric
> version-byte handling**: the byte[] overload does an unguarded
> `serializerByVersion[iSource[0]]` array index, throwing
> un-decorated `ArrayIndexOutOfBoundsException` for OOB leading bytes;
> the `ReadBytesContainer` overload validates and throws typed
> `IllegalArgumentException`. Pairs with a Base64-of-input WARN log
> path that amplifies log-injection of attacker-controlled bytes.
> (g) **`BinarySerializerFactory.create()` registers a fresh
> `new NullSerializer()`** rather than the `NullSerializer.INSTANCE`
> singleton — every other registered serializer uses its `INSTANCE`.
> Pinned today as `assertNotSame`-style anomaly; WHEN-FIXED flips to
> `assertSame` once the factory is harmonized.
> (h) **`MockSerializer.preprocess` returns null instead of input** —
> sentinel-shape divergence from the conventional contract. Folded
> into the (d) deletion scope.
> wire format is `varint(length) + UTF-8 bytes`, NOT
> `int scale + int unscaledLen + unscaled bytes`, so the deserialiser
> interprets the leading bytes as scale+length+payload and crashes
> with `NumberFormatException("Zero length BigInteger")` from inside
> `new BigInteger`. DoS / crash-on-bad-input risk for any server fed
> an attacker-controlled STRING field value reaching a DATE-vs-STRING
> isEqual check.
> (o) **`BinaryComparatorV0` DATE × LONG isEqual flooring asymmetry**:
> the isEqual arm at line 478 calls
> `convertDayToTimezone(databaseTZ, GMT, value2)` which floors the
> LONG value to the start of its day (positive intra-day rounds to 0;
> negative -1 ms rounds to -86_400_000, not 0). The matching `compare`
> arm at line 1140 does literal `Long.compare` without flooring —
> pinned in `dateCompareLongIntradaySignsAreLiteralLongCompare`.
> (p) **`BinaryComparatorV0` DATETIME × DATE asymmetry**: DATE side is
> multiplied by `MILLISEC_PER_DAY` for both isEqual and compare;
> DATETIME side is NOT floored. So `isEqual(DATETIME 1 ms, DATE 0
> days) == false` and `compare(DATETIME 1 ms, DATE 0 days) == 1` —
> pinned in `datetimeCrossDateIntradayDifference`.
> (q) **`BinaryComparatorV0` DECIMAL × BYTE asymmetry**: `compare`
> supports DECIMAL × BYTE via line 1312-1315; `isEqual` does NOT (line
> 633's DECIMAL switch lacks the BYTE arm). Pinned with
> `assertFalse(isEqual)` AND `compareTo == 0` so a regression that
> adds the isEqual arm without updating the companion pin fails
> loudly.
> (r) **`BinaryComparatorV0` BOOLEAN × STRING case-insensitive
> surface**: `Boolean.parseBoolean` accepts any case for "true"
> (`"TRUE"`, `"True"`, `"tRuE"`); any non-`"true"` string parses to
> false. Pinned for both isEqual and the compare three-way ternary's
> three arms.
>
> *Production-code asserts to add* (lockstep with deletions; from
> Step 6 review iter-1):
> (s) `assert keysSize >= 0` after reading the keyCount header in
> `CompositeKeySerializer`'s deserialise/compare paths and
> `IndexMultiValuKeySerializer`'s WAL variant.
> (t) `assert serializerId >= 0` for `CompositeKeySerializer`
> non-null entries (NOT for `IndexMultiValuKeySerializer` because that
> one uses negative typeIds as null sentinels).
> (u) Post-condition `(startPosition - oldStartPosition) ==
> getObjectSize(...)` assert at the end of
> `CompositeKeySerializer.serialize`.
>
> *Residual coverage gaps* (live class with branches that need
> integration-level or out-of-scope test infrastructure):
> (v) **B-tree-backed LinkBag/LinkSet write paths**:
> `writeLinkBag`/`writeLinkSet` mode-2 branches require a
> `BTreeBasedLinkBag` with a valid `LinkBagPointer` and a
> `session.getBTreeCollectionManager()` non-null — only a real
> disk-backed storage emits this shape. The 9-line `else` branches
> (~16 lines total) stay uncovered by Step 4 and roll up either into
> integration-level B-tree tests or this queue.
> (w) **`EntitySerializerDelta` dry-run path** (`deserializeDelta(
> session, bytes, null)`): used by network transport for byte
> validation; 15+ guarded `if (toUpdate != null)` branches across 8
> collection delegates are not exercised today.
> (x) **CompositeKeySerializer Map-flatten preprocess negative
> branches**: the four-condition AND-guard at lines 282-292
> (`instanceof Map`, type ≠ EMBEDDEDMAP/LINKMAP, `size() == 1`, key
> class assignable from `type.getDefaultJavaType()`). Standalone
> factory has no registered serializer for EMBEDDEDMAP/LINKMAP, so the
> negative-branch tests for "type IS EMBEDDEDMAP" hit an NPE in factory
> dispatch — needs a custom-factory test fixture or production-side
> null-defence.
>
> *DRY / refactor candidates*:
> (y) **`runInTx` and value-byte-dispatch helpers duplicated** across
> the three V1 round-trip test files
> (`RecordSerializerBinaryV1SimpleTypeRoundTripTest`,
> `RecordSerializerBinaryV1CollectionRoundTripTest`,
> `EntitySerializerDeltaRoundTripTest`) — extract to a shared
> `BinarySerializerTestSupport` base class.
> (z) **`field()` helper duplicated** across the new comparator test
> files AND `AbstractComparatorTest` — cross-package refactor touching
> `core/index/` test infrastructure.
> (aa) **`assertCanonicalBytes(String, byte[])` helper** — the same
> pattern recurs across `VarIntSerializerTest`,
> `RecordSerializerBinaryV1SimpleTypeRoundTripTest`, the new
> `UUIDSerializerTest`, and the index-serializer tests; promote to
> `binary/BinaryPinAssertions`.
> (bb) Sibling `*SerializerTest` files in `common/serialization/types`
> (`BooleanSerializerTest` ↔ `ByteSerializerTest` ↔
> `CharSerializerTest` ↔ `ShortSerializerTest` ↔ `FloatSerializerTest`
> etc.) have ~6-11 uncovered lines each (`getId`, `isFixedLength`,
> `getFixedLength`, `preprocess`, `getObjectSize(byte[])`,
> `getObjectSizeNative`, primitive `serializeLiteral`/
> `deserializeLiteral` overloads). Step 7 closed `UUIDSerializer` and
> added `NullSerializerTest`; the residual ~50 lines are a uniform
> extension across 5 files and can be absorbed in the cleanup track's
> DRY pass alongside (bb).
>
> *From the binary-serializer track Phase C iter-3 gate-check
> (deferred suggestions — design-level refinements not landed in
> iter-3's cosmetic sweep):*
> (cc) **`RecordSerializerBinaryV1CollectionRoundTripTest.serializeValueBytesWithLinkedType`
> Javadoc shape**: the opening uses an inline single-line shape
> inconsistent with the canonical multi-line Javadoc shape used by the
> sibling helpers (`serializeValueBytes`, `deserializeValueBytes`,
> `deserializeValueBytesWithOwner`). Cosmetic only; reformat in the
> cleanup track's DRY pass.
> (dd) **LinkBag single-entry middle-byte change-tracker pin gap** in
> `RecordSerializerBinaryV1CollectionRoundTripTest.linkBagWithSingleEntryEncodesEmbeddedConfigByteSizeOneAndTerminator`:
> the leading prelude + terminator are byte-pinned but the middle
> bytes (positions 3..n-2) are excluded because they depend on
> change-tracker secondary RID allocation. A symmetric encoder/decoder
> drift in the middle-byte slot would round-trip cleanly. Suggested
> approach: pre-persist a peer with a deterministic RID at the lowest
> cluster position so the change-tracker entry's RID encoding is
> stable, then pin the full byte sequence; or add a companion test
> pinning one specific middle-byte invariant
> (e.g., the position-varint zigzag(0) at the expected offset).
> (ee) **CompactedLinkSerializer WAL-overlay max-cluster-id pin
> gap**: `testRecordIdMaximumClusterIdRoundTripsThroughCompactedLink`
> exercises `Short.MAX_VALUE` through portable + native + ByteBuffer
> paths but not the WAL-overlay decode at the boundary; the existing
> `testWalOverlayDeserialiseRoundTripsCompactedRid` uses
> `cluster=42`. Add a `testWalOverlayDeserialiseAtMaxClusterId`
> mirroring the existing WAL-overlay shape with
> `cluster=Short.MAX_VALUE`. Narrow gap (the WAL-overlay decode reuses
> the same short-read primitives as the native path, so the existing
> max-cluster pin is largely sufficient), but a one-test addition
> closes the interaction-coverage corner.
>
> *From Track 14 (DB Core & Config) Step 1 Phase B:* The entire
> `core/db/config` package — six public classes
> (`MulticastConfguration`, `MulticastConfigurationBuilder`,
> `NodeConfiguration`, `NodeConfigurationBuilder`,
> `UDPUnicastConfiguration`, `UDPUnicastConfigurationBuilder`) plus the
> inner `UDPUnicastConfiguration.Address` record — has zero references
> outside the package across all five Maven modules (PSI all-scope
> `ReferencesSearch` performed during Phase A; re-confirmed at Step 1
> implementation time). The classes are mutually self-referential dead
> code drafted for cluster discovery configuration that never landed.
> Behavioural shape pinned via `DBConfigDeadCodeTest` covering every
> public ctor / public+protected setter / getter / static `builder()`
> factory / `build()` arm so a deletion that misses any class fails at
> compile time. Delete the entire package together with the
> corresponding test file
> `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/config/DBConfigDeadCodeTest.java`
> in lockstep — no production callers exist anywhere in the codebase.
>
> *From Track 14 (DB Core & Config) Step 2 Phase B:* Eight additional
> production-dead helper / abstract / listener classes under
> `core/db/` and `core/db/record/(record)/` confirmed via PSI all-scope
> `ReferencesSearch` at Step 2 implementation time. All eight ship with
> dedicated `<Class>DeadCodeTest` shape pins so a deletion either
> updates the pin in lockstep or fails at compile time. Deletion
> queue:
> - **`core/db/DatabasePoolBase`** (pure dead — 0 callers, 0 subclasses;
>   the only path that would instantiate the anonymous
>   `DatabasePoolAbstract` subclass it wraps). Test pin:
>   `core/src/test/java/.../db/DatabasePoolBaseDeadCodeTest.java`.
> - **`core/db/DatabasePoolAbstract`** (test-reachable: subclassed only
>   by `DatabasePoolBase` (dead) and the test-only
>   `DatabasePoolAbstractEvictionTest$TestPool`). Deletion is contingent
>   on (a) deleting `DatabasePoolBase` first, and (b) consolidating the
>   inner `Evictor` logic into `DatabasePoolImpl` (which already has its
>   own eviction path) or simply dropping
>   `DatabasePoolAbstractEvictionTest`. Test pin:
>   `.../db/DatabasePoolAbstractDeadCodeTest.java`.
> - **`core/db/HookReplacedRecordThreadLocal`** (pure dead — 0 callers
>   for `INSTANCE`, `getIfDefined`, or `isDefined`; the static
>   initializer registers an engines-manager listener purely to null
>   `INSTANCE` on shutdown — that listener has no behavioural effect
>   because nothing reads `INSTANCE`). Test pin: `.../db/HookReplaced
>   RecordThreadLocalDeadCodeTest.java` runs under
>   `@Category(SequentialTest)` to avoid racing the engines-manager
>   shutdown listener.
> - **`core/db/LiveQueryBatchResultListener`** (pure dead interface —
>   0 references; the live-query pipeline understands only the parent
>   `LiveQueryResultListener`). Test pin: `.../db/LiveQueryBatch
>   ResultListenerDeadCodeTest.java`.
> - **`core/db/DatabaseLifecycleListenerAbstract`** (pure dead abstract
>   — 0 subclasses; the parent interface already provides `default`
>   no-op bodies for every callback, so the adapter has no functional
>   value. The single load-bearing observable distinguishing it from
>   the interface defaults is `getPriority()→REGULAR` vs interface
>   default `LAST`). Test pin: `.../db/DatabaseLifecycleListener
>   AbstractDeadCodeTest.java`.
> - **`core/db/record/RecordMultiValueHelper`** (pure dead utility —
>   0 callers; legacy multi-value tracking has been replaced by typed
>   wrappers like `EntityLinkListImpl`, `EntityEmbeddedListImpl`).
>   Test pin: `.../db/record/RecordMultiValueHelperDeadCodeTest.java`.
> - **`core/db/record/record/EntityHookAbstract`** (test-only reachable
>   — 7 subclasses, all in test code; production code references zero).
>   Deletion contingent on retargeting the test subclasses
>   (`CheckHookCallCountTest$TestHook`,
>   `HookChangeValidationTest`'s anonymous subclasses,
>   `DbListenerTest`'s anonymous subclass) at the parent
>   `RecordHookAbstract` or `RecordHook` directly. Test pin:
>   `.../db/record/record/EntityHookAbstractDeadCodeTest.java`.
> - **`core/db/record/record/RecordHookAbstract`** (test-only reachable
>   — the single production-source reference is a Javadoc `@see` tag
>   in `RecordHook.java`; the only concrete subclasses live in
>   `tests/src/test/`: `BrokenMapHook`, `HookTxTest$RecordHook`).
>   Deletion contingent on either deleting those test files or
>   retargeting them at `RecordHook` directly (the interface already
>   provides a `default` no-op for `onUnregister`). Test pin:
>   `.../db/record/record/RecordHookAbstractDeadCodeTest.java`.
>
> *From Track 14 (DB Core & Config) Step 2 Phase B:*
> `LRUCache.removeEldestEntry` uses `size() >= cacheSize` rather than
> `>`, so the steady-state size of any backed cache (notably
> `core/db/StringCache`) caps at `cacheSize - 1`, not `cacheSize` as
> the parameter name suggests. The off-by-one was originally found in
> the common-utilities track and is now pinned via
> `StringCacheTest#capacityCapsCacheSizeAtOneBelowConstructorArgument`
> with a WHEN-FIXED note. Lift the cap to a true `cacheSize` entries
> (production fix is `size() > cacheSize`) and update the assertion to
> `assertEquals(capacity, cache.size())` in lockstep.
>
> *From Track 14 (DB Core & Config) Step 5 Phase B:* Latent NPE in
> `DatabaseSessionEmbedded.setCustom(name, iValue)` (lines 552–561):
> when `iValue == null` AND `name` is anything other than a
> case-insensitive `"clear"`, the else-branch sets `customValue = null`
> and the subsequent `if (name == null || customValue.isEmpty())`
> short-circuits only on the first arm. With a non-null `name`,
> `customValue.isEmpty()` then runs on null and throws NPE. Production
> callers that use `setCustom("foo", null)` expecting a remove are
> exposed. Pinned via
> `DatabaseSessionEmbeddedAttributesTest#setCustomNonClearNameNullValueThrowsNpePinningLatentBug`
> with a forwards-to marker. Two viable fixes: (a) treat
> null-value-non-clear-name as a remove (route through
> `removeCustomInternal(name)`), or (b) guard `customValue == null`
> before `.isEmpty()`. Pick whichever the SQL `ALTER DATABASE CUSTOM
> X = null` semantics requires.
>
> *From Track 14 (DB Core & Config) Step 5 Phase B:* Misleading TIMEZONE
> backward-compat comment in `DatabaseSessionEmbedded.set(TIMEZONE)`.
> The retry path uppercase-matches first then re-tries with the original
> string — so already-correctly-cased ids like `Europe/Paris` succeed,
> but a fully-lowercase input like `europe/paris` falls back to GMT
> twice. The comment "until 2.1.13 YouTrackDB accepted timezones in
> lowercase as well" is therefore misleading. Either tighten the retry
> to TitleCase normalisation or drop the misleading comment in lockstep
> with the existing observed-shape pin in
> `DatabaseSessionEmbeddedAttributesTest`.
>
> *From Track 14 (DB Core & Config) Step 5 Phase B:* `setCustom` Object
> stringification uses `"" + iValue` (string concatenation) rather than
> `String.valueOf(iValue)`. Identical for most types, but observable
> for `char[]` (concat → `[C@...`, `valueOf` → array contents).
> Refactor candidate only — no behaviour-change request.
>
> *From Track 14 (DB Core & Config) Step 6 Phase B:* `SystemDatabase`
> latent shape — when the OSystem database already exists from a
> previous open (e.g. another test method or a prior process
> reactivated the same data directory), a freshly-constructed
> `SystemDatabase` wrapper's `openSystemDatabaseSession()` skips
> `init()` via the `if (!exists())` guard, leaving the wrapper's
>   delta — this would falsify a refactor that moves cleanup elsewhere.
> - **BC12** — `ExecutionThreadLocalTest` lacks `@After remove()` on the
>   thread-local. Mitigated by per-test set-and-clear, not a leak risk;
>   add for defence in depth if/when the thread-local surface grows.
> - **BC13** — `cleanUpCache` race against `reset()` neutralised by
>   `DatabasePoolImpl.close` idempotence. Awareness item only — record
>   in a class-level Javadoc note so a future change to the close
>   contract surfaces the race correctly.
> - **CQ20** — `DatabasePoolBaseDeadCodeTest:200-201` —
>   `assertEquals(msg, null, x)` should be `assertNull(msg, x)` for the
>   reflective `dbPool` field-stays-null pin. 1-line stylistic fix.
> - **CQ21** — `YouTrackDBConfigImplTest:579` — inline
>   `new java.util.ArrayList<>()` FQN reintroduced inside the
>   `childWithNullAttrsAndConfig` helper. Sibling to a pre-existing
>   instance at `:500`. Replace with a regular `java.util.ArrayList`
>   import in lockstep with the sibling at `:500`.
> - **TB20** — `instanceSetInterruptCurrentOperationOnNullIsNoOp`
>   carries no observable post-state assertion (acceptable per iter-1
>   S1's "no observable state on null arm" rubric). If the
>   `setInterruptCurrentOperation` surface ever grows mutable state, add
>   a state-snapshot assertion to falsify against the broader contract.
> - **TB21** — Cosmetic `LinkBag` Javadoc at the
>   `zeroThresholdYieldsEmbeddedDelegateAtBoundary` test misstates the
>   actual predicate as `embedded.size() >= topThreshold` when the
>   production code is `topThreshold >= 0`. No falsifiability impact —
>   correct the Javadoc in lockstep with any LinkBag refactor.
>
> *From Track 15 (Record Implementation & DB Tool) Steps 1–6 + Phase C:*
> Eight dead-code deletion items + ~14 production-fix WHEN-FIXED
> markers + 3 iter-2/iter-3 suggestion-tier items absorbed.
>
> **Dead-code deletions (lockstep with `*DeadCodeTest` pin removal):**
> - **(a) `core/db/tool` orphans** — `DatabaseRepair` (171 LOC, 0 main /
>   0 test refs), `BonsaiTreeRepair` (124 LOC, 0/0). Both fully dead;
>   delete with their `*DeadCodeTest` pins.
> - **(b) `core/db/tool` test-only-reachable** — `DatabaseCompare` (0
>   main / 36 test refs), `GraphRepair` (0 main / 3 test refs in
>   `GraphRecoveringTest`), `CheckIndexTool` (0 main / 2 test refs in
>   `CheckIndexToolTest`). Delete via two-step: rewrite or drop the
>   test callers (named in each `*DeadCodeTest` Javadoc), then delete
>   the production class + the pin together.
> - **(c) `core/record` chain-dead helpers** — `RecordVersionHelper`
>   (9 dead public static methods + dead `SERIALIZED_SIZE` + protected
>   ctor), `RecordStringable` (interface, 0 implementers per
>   `ClassInheritorsSearch`), `RecordListener` (interface, 0
>   implementers). Delete with their per-method pin tests in
>   `RecordVersionHelperDeadCodeTest` /
>   `RecordStringableDeadCodeTest` / `RecordListenerDeadCodeTest`.
> - **(d) `EntityHelper` 12 dead public methods** — `sort`,
>   `getMapEntry`, `getResultEntry`, `evaluateFunction`,
>   `hasSameContentItem`, both `hasSameContentOf` overloads
>   (5+6-arg, chain-dead via `DatabaseCompare`),
>   `compareMaps`/`compareCollections`/`compareSets`/`compareBags`,
>   `isEntity(byte)`. Each is pinned individually in
>   `EntityHelperDeadCodeTest` so partial deletion stays valid. Plus
>   the inner `EntityHelper.RIDMapper` functional interface — but
>   note iter-1 fix (commit `fb5881c66a`) introduced a live caller of
>   `RIDMapper` from `DatabaseExportImportRoundTripTest`'s round-trip
>   harness, so `RIDMapper` is no longer chain-dead. Update the dead-
>   pin to drop `RIDMapper` from the deletion set, leaving the 12
>   methods + the test-fixture-reachable `RIDMapper` retained.
> - **(e) `EntityComparator`** — chain-dead via
>   `EntityHelper.sort` AND test-only-reachable from one
>   `tests/CRUDDocumentValidationTest` sort-stability assertion. Three
>   landing sites named in `EntityComparatorDeadCodeTest`: drop
>   `EntityHelper.sort`, drop `EntityComparator`, rewrite or drop the
>   `tests/` assertion.
> - **(f) `EntityImpl.hasSameContentOf(EntityImpl)`** — sole non-
>   `DatabaseCompare` production-source caller of the dead
>   `EntityHelper.hasSameContentOf` (5-arg). Has a single
>   test-only-reachable caller in `tests/CRUDDocumentPhysicalTest`.
>   Co-delete with the `EntityHelper` 5+6-arg helpers and rewrite the
>   `tests/` caller's `hasSameContentOf` assertion in lockstep.
> - **(g) `RecordBytes.fromInputStream(InputStream, int)` 2-arg
>   overload** — test-only-reachable. Production callers: 0; test
>   callers: 7 in `DBRecordBytesTest` lines L77, L88, L102, L116,
>   L149, L165, L182. Deletion contingent on rewriting/dropping those
>   7 sites. Pinned via `RecordBytesTestOnlyOverloadTest` (NOT
>   `*DeadCodeTest` — the 1-arg `Blob.fromInputStream(InputStream)` is
>   live via `JSONSerializerJackson:623`). The earlier "RecordBytes
>   `fromInputStream` + `toStream(MemoryStream)` overload deletions"
>   line item is RETRACTED — `toStream(MemoryStream)` does not exist
>   on `RecordBytes` (only on `RecordIdInternal`/`RecordId`/
>   `ContextualRecordId`/`ChangeableRecordId`/
>   `CommandRequestTextAbstract`, all already in this absorption
>   queue per the MemoryStream backlog item h).
> - **(h) `RecordBytes.fromInputStream(InputStream)` body uses
>   `MemoryStream` as a scratch buffer** — rewrite the 1-arg overload
>   body to use `ByteArrayOutputStream` directly so the MemoryStream
>   `RecordBytes` dependency is severed. Track 12's MemoryStream item
>   h ("close via deletion, not migration") covers
>   `RecordIdInternal`/`Command*` callers; this is the
>   `RecordBytes`-side companion.
>
> **Production-bug pins (WHEN-FIXED markers in Track 15 tests, fix
> in Track 22):**
> - **(i) `OPPOSITE_LINK_CONTAINER_PREFIX` should-be-final** — the
>   field is logically a constant but declared mutable; 0 writes per
>   PSI. Tighten to `final` in lockstep with the
>   `EntityImplTest.opposite_link_container_prefix_*` shape pins.
>
> **Iter-2 / iter-3 suggestions deferred to Track 22 absorption:**
> - **CQ12** — `DatabaseExportImportRoundTripTest.
>   roundTripPreservesEntityContentForUnambiguousTypes` is 231 lines
>   spanning fixture build + export + RID-mapper construction + paired
>   session activation + per-entity comparison. Extract two private
>   helpers: `private RIDMapper buildNameKeyedRidMapper(...)` and
>   `private void assertEntityRoundTrip(...)`. Leave the fixture-build
>   block inline (it depends on a single transaction and on RIDs
>   assigned in declaration order). Preserves the BC1 try/finally
>   activation invariant inside the helper.
> - **TC13** — `EmbeddedSetConverterTest` and `EmbeddedMapConverterTest`
>   lack the null-element symmetry test that
>   `EmbeddedListConverterTest.testListWithNullElementReturnedByReferenceWhenNoChange`
>   has. Verify reachability of `add(null)` / `put(k, null)` on the
>   embedded wrappers before adding (the abstract base's null arm may
>   be unreachable through the wrappers' `checkValue` chain — same
>   shape as the TC2 rejection on the four Link converters).
> - **TC14** — `EntityLinkSetImpl.add(@Nullable Identifiable e)` rejects
>   null via NPE (dereferences `e.getIdentity()`) rather than via
>   `checkValue` like its siblings `EntityLinkListImpl` and
>   `EntityLinkMapIml`. The `@Nullable` annotation on the parameter
>   is misleading. Add a pin test `addNullThrowsNPE_pinsCurrentNullRejection`
>   so a future "fix" that honours the annotation (silently skipping
>   null) does not silently make `ImportConvertersFactory`'s abstract-
>   base null arm reachable through LinkSet.

> **How**:
> - TX tests need a database session to verify begin/commit/rollback
>   semantics (`DbTestBase`).
> - Gremlin tests use `GraphBaseTest`.
> - Engine lifecycle tests verify engine registration via SPI
>   (`META-INF/services`) and lifecycle hooks.
> - Remaining packages are a mix of standalone and DB-dependent tests
>   — the execution agent decides per test class.
> - For each inherited DRY/cleanup item: prefer to land the production
>   change + the lockstep update of the WHEN-FIXED pin (so the pin
>   flips from "asserts buggy shape" to "asserts correct shape") in
>   the same commit. Dead-code deletions land with their `*DeadCodeTest`
>   pin removed.
> - Carry forward Tracks 5–13 conventions: `TestUtilsFixture`,
>   falsifiable-regression + WHEN-FIXED markers, `*DeadCodeTest` shape
>   pinning, `// forwards-to: Track NN` cross-track bug-pinning,
>   `Iterable` detach-after-commit, `@Category(SequentialTest)` for
>   static-state mutations.
>
> **Constraints**:
> - In-scope: the listed `core/tx`, `core/gremlin`, `core/engine`,
>   `core/exception`, plus the smaller packages enumerated.
> - Excluded by constraint 7: `**/api/gremlin/embedded/schema/**` and
>   `**/api/gremlin/tokens/schema/**` are NOT targeted.
> - For inherited security-relevant items (k, l, m, n, r above),
>   prefer narrow allow-list / type-gate fixes over rewrites of the
>   embedded-transport path.
> - Production-code asserts (s, t, u) follow the project convention:
>   bare `assert` at the call site backed by a static helper method
>   when JaCoCo branch coverage matters (CLAUDE.md §10).
>
> **Interactions**:
> - Depends on Track 1.
> - Closes the deferred-cleanup queue accumulated by Tracks 7–13.
> - May reduce coverage gaps in Track 12's serializer/Track 13's
>   binary-serializer packages once dead-code deletions land — the
>   final coverage-build verification step in this track also
>   re-measures those packages and updates `coverage-baseline.md`.
>   semantics (`DbTestBase`).
> - Gremlin tests use `GraphBaseTest`.
> - Engine lifecycle tests verify engine registration via SPI
>   (`META-INF/services`) and lifecycle hooks.
> - Remaining packages are a mix of standalone and DB-dependent tests
>   — the execution agent decides per test class.
> - For each inherited DRY/cleanup item: prefer to land the production
>   change + the lockstep update of the WHEN-FIXED pin (so the pin
>   flips from "asserts buggy shape" to "asserts correct shape") in
>   the same commit. Dead-code deletions land with their `*DeadCodeTest`
>   pin removed.
> - Carry forward Tracks 5–13 conventions: `TestUtilsFixture`,
>   falsifiable-regression + WHEN-FIXED markers, `*DeadCodeTest` shape
>   pinning, `// forwards-to: Track NN` cross-track bug-pinning,
>   `Iterable` detach-after-commit, `@Category(SequentialTest)` for
>   static-state mutations.
>
> **Constraints**:
> - In-scope: the listed `core/tx`, `core/gremlin`, `core/engine`,
>   `core/exception`, plus the smaller packages enumerated.
> - Excluded by constraint 7: `**/api/gremlin/embedded/schema/**` and
>   `**/api/gremlin/tokens/schema/**` are NOT targeted.
> - For inherited security-relevant items (k, l, m, n, r above),
>   prefer narrow allow-list / type-gate fixes over rewrites of the
>   embedded-transport path.
> - Production-code asserts (s, t, u) follow the project convention:
>   bare `assert` at the call site backed by a static helper method
>   when JaCoCo branch coverage matters (CLAUDE.md §10).
>
> **Interactions**:
> - Depends on Track 1.
> - Closes the deferred-cleanup queue accumulated by Tracks 7–13.
> - May reduce coverage gaps in Track 12's serializer/Track 13's
>   binary-serializer packages once dead-code deletions land — the
>   final coverage-build verification step in this track also
