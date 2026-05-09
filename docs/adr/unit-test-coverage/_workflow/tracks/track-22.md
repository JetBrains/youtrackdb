# Track 22: Transactions, Gremlin & Remaining Core

## Description

Write tests for transaction management, Gremlin integration, and
all remaining uncovered core packages. This is the final sweep
track and absorbs the deferred-cleanup queue accumulated by earlier
tracks (production-bug fixes pinned via WHEN-FIXED markers,
dead-code deletions, DRY/refactor candidates, residual coverage
gaps).

**Scope:** ~6 steps covering transaction management, Gremlin
integration, engine lifecycle, exception/compression/config,
remaining small packages, and verification; plus ~3-4 steps
absorbing the inherited DRY / cleanup scope from Tracks 7–17
(security adds 5 dead-code lockstep groups + 21 per-method
`SymmetricKey` pins + 6 latent production issues, including the
newly-discovered `TokenSignImpl.readKeyFromConfig` unreachable
inner branch — tokens currently cannot be verified across server
restarts because configured `NETWORK_TOKEN_SECRETKEY` is silently
ignored). After the Pre-Flight clarifications below, the realistic
step count for execution-time decomposition is closer to ~6 main
coverage + ~8–10 deletion-lockstep + ~1–2 marker-rewrite steps
(Phase A may inline-pack deletion into the related coverage step).

**Depends on:** Track 1

**Operational note (carried forward from `implementation-plan.md`):**
The original backlog Track 22 section contained ~263 lines of
inherited-DRY-queue content covering Tracks 10–13 that were lost
in the 2026-05-04 `git clean -fd` incident — the
`<<< RECOVERY GAP >>>` markers below mark the lost region. The
**Reconstructed inherited DRY queue (Tracks 10–13)** subsection
appended after the verbatim backlog body stitches the missing
content from each track's `**Track episode:**` block in the plan
file. Track 16 and Track 17 absorption blocks are fully present in
the verbatim backlog body below.


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

> *From Track 16 (Metadata Schema & Functions) Phase C:* Four
> should-fix test-addition / pre-existing-naming items + ~17
> suggestion-tier readability nits absorbed.
>
> **Should-fix tier (test-additions and pre-existing-code naming):**
> - **TC9** — `PropertyTypeInternal.LINK.convert(...)` `Result` arm
>   has three uncovered sub-paths
>   (`isIdentifiable()→asIdentifiable()` short-circuit;
>   `isProjection()→toMap()` recurse;
>   neither→post-switch throw at `PropertyTypeInternal:861`). Add
>   three tests under the appropriate link-convert test class with
>   `ResultInternal` setup using `setIdentifiable(rid)` /
>   `setProperty(...)`.
> - **TC10** — `PropertyTypeInternal.EMBEDDEDMAP.convert(...)` `Result`
>   arm has two uncovered sub-paths
>   (projection→entries-from-property-names;
>   non-projection→wrap-under-`"value"`-key). Add two tests under the
>   appropriate embeddedmap-convert test class.
> - **TC11** — `PropertyTypeInternal.EMBEDDED.convert(...)` String
>   arm calls `JSONSerializerJackson.INSTANCE.fromString(...)` which
>   throws on malformed JSON. Add an `assertThrows` test for the
>   parse-failure path; tighten exception class once the first run
>   reveals the surfaced type.
> - **TS10** — pre-existing `testSimpleFunctionCreate`,
>   `testDuplicateFunctionCreate`, `testFunctionCreateDrop` in
>   `FunctionLibraryTest` use the legacy `test*` prefix while every
>   Track 16-added test uses `actionDescribesExpectedOutcome` style.
>   Either rename the three pre-existing methods or add per-method
>   one-line comments explaining what they pin. Pre-existing code,
>   acceptable to defer.
>
> **Suggestion tier (~17 items, low-priority readability /
> diagnostic-clarity nits):**
> - **BC5/6/7** — diagnostic-clarity nits in
>   `SchemaSharedLockApiTest.multipleReadersAreConcurrent` (in-test
>   join loop duplicates `@After` join);
>   `bAttemptingAcquire` countdown ordering relative to
>   `acquireSchemaWriteLock` in `writersAreSerializedAcrossThreads`
>   (microsecond-scale window where A could exit the latch wait
>   while B is still in JIT/scheduling); and
>   `SchemaProxyBoundaryTest.inactiveSessionTriggersSessionNotActivatedException`
>   could use an `assertFalse(t.isAlive())` after the in-test join
>   for clearer diagnostic on a stuck worker.
> - **CQ6/7/8/9** — fully-qualified-type usages in
>   `FunctionLibraryTest` (`EntityImpl` cast),
>   `PropertyTypeInternalLinkConvertTest` (`RID` field type),
>   `ImmutableSchemaPropertyShapeTest` (`DefaultCollate`, `HashMap`
>   inside lambdas), and `SchemaClassOperationsTest` (redundant
>   `dbSession = session` aliases).
> - **TC12/13** — `BalancedCollectionSelectionStrategyDeadCodeTest`
>   REFRESH_TIMEOUT cache-hit branch (low priority — class is dead-
>   code scheduled for deletion — line item 'Track 16 cluster-
>   selection' below);
>   `PropertyTypeInternalNumericConvertTest` boundary values
>   (`Long.MAX_VALUE→Integer/Short`, `Double.NaN→Long`,
>   `Double.POSITIVE_INFINITY→Long`).
> - **TX7** — drop body-level join loop in
>   `multipleReadersAreConcurrent` (5s+ latency penalty when a real
>   failure occurs; the `failures` collector + `@After` join already
>   handle the contract).
> - **TS5/6/7/8/9** — DRY hoist candidates: extract
>   `TrackedSpawnTestSupport` once N≥3 consumers exist (currently 2);
>   collapse duplicated `classExposesExpectedPublicSurface` helpers
>   in `BalancedCollectionSelectionStrategyDeadCodeTest` /
>   `DefaultCollectionSelectionStrategyDeadCodeTest` (both scheduled
>   for lockstep deletion); add `currentSnapshot()` /
>   `snapshotClass(name)` helpers in `ImmutableSchemaShapeTest`;
>   split `SchemaClassOperationsTest` (805 LOC, 31 tests) into
>   thematic siblings; consider a shared row-schema for the
>   `PropertyTypeInternal*ConvertTest` siblings.
> - **TB10** — drop misleading "Void setters … pinned by absence of
>   compile-time assertion" comment in
>   `FunctionRecordRoundTripTest.settersAreFluentWhereTheyReturnFunctionAndOverwriteFields`,
>   or add a reflective return-type pin on `setCode` / `setLanguage`.
>
> **Track 16 dead-code deletions (lockstep with `*DeadCodeTest`
> pin removal):** the four dead-code pin classes added in Track 16
> Step 1 carry the `WHEN-FIXED: Track 22` deletion-marker convention
> already used by Tracks 9 / 12 / 14 / 15 — when the targets below
> are deleted, drop the matching pin in the same commit:
> - `IndexConfigPropertyDeadCodeTest` →
>   `core/.../metadata/schema/IndexConfigProperty` (zero production
>   callers per PSI find-usages).
> - `BalancedCollectionSelectionStrategyDeadCodeTest`,
>   `DefaultCollectionSelectionStrategyDeadCodeTest`,
>   `CollectionSelectionFactoryDeadCodeTest` →
>   `core/.../metadata/schema/clusterselection/{Balanced,Default}CollectionSelectionStrategy`
>   plus the SPI factory entry pinning them in
>   `META-INF/services/...CollectionSelectionStrategy`. The trio
>   has zero production callers per PSI; the live cluster-selection
>   path is the round-robin strategy.

> *From Track 17 (Security) Steps 1–7 + Phase C:* Test-additive coverage
> for the security subsystem (37 new/extended test files, ~9 600 LOC,
> zero production-source changes). The Track 22 absorption inventory
> from Track 17 is:
>
> *A. Dead-code lockstep deletion groups (whole-class, 5 groups):*
> 1. **Kerberos pair**: `KerberosCredentialInterceptor` +
>    `Krb5ClientLoginModuleConfig`. Zero production callers per PSI;
>    the `CredentialInterceptor` SPI itself is uncalled. Kerberos-pair
>    deletion unlocks group 4 below.
> 2. **Binary-token quintet**: `BinaryToken` +
>    `BinaryTokenSerializer` + `BinaryTokenPayloadImpl` +
>    `BinaryTokenPayloadDeserializer` + `DistributedBinaryTokenPayload`.
>    Includes the BC3 + BC4 deferrals: 30-second `isCloseToExpire`
>    window (Phase B Step 6 iter-2) and 5-minute sibling TOCTOU window
>    (Phase C iter-1 BC4) in `BinaryTokenDeadCodeTest`. No point
>    fixing timing windows in a test that should be deleted.
> 3. **JWT trio**: `JsonWebToken` + `JwtPayload` +
>    `YouTrackDBJwtHeader`. Gated on the binary-token quintet
>    (`BinaryTokenSerializer` references `YouTrackDBJwtHeader`).
> 4. **CI plug-in chain**: `DefaultCI` whole class +
>    `SecurityManager.newCredentialInterceptor()` method +
>    `GlobalConfiguration.CLIENT_CREDENTIAL_INTERCEPTOR` config slot.
>    Gated on the Kerberos pair deletion.
> 5. **Symmetric-key trio**: `SymmetricKeyCI` +
>    `SymmetricKeySecurity` + `UserSymmetricKeyConfig`
>    (`SymmetricKeySecurity` and `UserSymmetricKeyConfig` delete as a
>    lockstep pair).
>
> *B. 21 per-method `SymmetricKey` deletions — PSI-confirmed safe
> deletion order (live class with 21 dead public/protected methods,
> per-method pinning so partial deletion stays valid):*
> - **Phase 1 — after `SymmetricKeyCI` deleted** (7 methods):
>   `setDefaultCipherTransform(String)`, `fromString(String)`,
>   `fromFile(String, String)`,
>   `fromKeystore(String, String, String, String)`,
>   `fromKeystore(InputStream, String, String, String)`,
>   `fromStream(InputStream, String, String)`,
>   `separateAlgorithm(String)` (protected-static).
> - **Phase 2 — after `SymmetricKeySecurity` +
>   `UserSymmetricKeyConfig` deleted** (2 methods):
>   `fromConfig(SecurityConfig, String)`,
>   `decryptAsString(String)`.
> - **Phase 3 — independent (zero callers, any order)** (12 methods):
>   6 dead getters (`getName`, `getPassword`, `getKeyAlgorithm`,
>   `getKeystore`, `getKeystorePassword`, `getKeyId`) +
>   6 dead setters (`setName`, `setPassword`, `setKeyAlgorithm`,
>   `setKeystore`, `setKeystorePassword`, `setKeyId`).
>
> *C. Latent production issues pinned by observable behaviour
> (6 issues, with `// WHEN-FIXED: Track 22 — <fix>` markers in tests):*
> 1. **`SecurityManager.SALT_CACHE` algorithm-omission bug**
>    (Step 1 + Phase C iter-1 F3): cache key omits the algorithm; a
>    verify call under one algorithm short-circuits on a cached
>    PBKDF2 result computed under a different algorithm. Pin:
>    `SecurityManagerTest.saltCacheCurrentlyConfusesAlgorithmsLatentBugPin`.
>    Fix: include the algorithm in the cache key.
> 2. **`DefaultPasswordAuthenticator.createServerUser` empty-password
>    bug** (Step 2): JSON `"password"` field is read but not passed
>    to `ImmutableUser`; stored password is always `""`;
>    `authenticate()` always returns null for in-memory-config users.
> 3. **`SecuritySystemUserImpl.populateSystemRoles` NPE** (Step 3 +
>    Phase C iter-1 F4): the `databaseName`-non-empty branch reads
>    `getProperty(SystemRole.DB_FILTER)` which returns null for
>    regular-database roles; the for-each NPEs without a null check.
>    Pin: `ImmutableUserTest.testSecuritySystemUserImplWithNonEmptyDbNameNpesOnRegularDbRolesLatentBugPin`.
>    Only safe on the system database where roles have `dbFilter`
>    populated.
> 4. **`UserSymmetricKeyConfig` line 133 NPE** (Step 6 + Phase C iter-1
>    F5): the no-recognized-keys branch falls through to a null
>    dereference when `props` is non-null but contains none of `key`
>    / `keyFile` / `keyStore`. Dead-code path (the class itself is
>    queued for deletion in group A.5), but the defect exists in the
>    shipped bytecode. Pin:
>    `UserSymmetricKeyConfigDeadCodeTest.unrecognizedPropertiesKeyNpesOnLine133LatentBugPin`.
> 5. **`Function#execute(Object...)` deprecated overload always throws
>    "No database session found"** (forwarded from Track 16): because
>    `executeInContext` reads `iContext.getDatabaseSession()` before
>    the callback short-circuit.
> 6. **`TokenSignImpl.readKeyFromConfig` unreachable inner branch**
>    (Phase C iter-1 F6 — NEW finding): the inner `if (configKey != null
>    && configKey.length() > 0)` is the logical negation of the outer
>    guard, so a non-null non-empty `NETWORK_TOKEN_SECRETKEY` is
>    silently ignored — every `TokenSignImpl` falls through to a
>    `SecureRandom`-derived key. **Tokens cannot be verified across
>    server restarts or cluster nodes regardless of operator
>    configuration.** Pin:
>    `TokenSignImplTest.readKeyFromConfigIgnoresConfiguredSecretKeyLatentBugPin`.
>    Fix: invert the inner condition (or restructure as a guard chain)
>    so the configured Base64 key path is reachable.
>
> *D. Suggestion-tier deferred items from Track 17 reviews:*
> - **R6 (Phase A) + iter-1 F1 reinforcement**: extract the
>   `@BeforeClass` PBKDF2 iteration override (lower
>   `SECURITY_USER_PASSWORD_SALT_ITERATIONS` to ~100, restored in
>   `@AfterClass`, gated by `@Category(SequentialTest)`) into a shared
>   rule / base class so future security tests avoid copy-paste.
>   Currently inline in `SecurityManagerTest`.
> - **R7**: verify Track 17 carry-forward conventions (selective
>   `@Category(SequentialTest)` discipline, static-state inventory,
>   `@After rollbackIfLeftOpen` safety net, corrected-baseline rule)
>   against Track 22's test approach.
> - **A6**: extract `enableAndPrepareSystem()` /
>   `buildAuthenticationConfig()` helpers from
>   `DefaultSecuritySystemReloadTest` to a shared fixture class when
>   Track 22 adds more `DefaultSecuritySystem` tests (e.g., the
>   import-LDAP happy path).
> - **A7**: Track 22 should prefer extending existing test classes
>   (`DefaultSecuritySystemReloadTest`,
>   `AuthenticatorChainDispatchTest`, `SymmetricKeyTest`,
>   `SecuritySharedTest`) over creating new classes.
> - **A8**: deferred live paths to fold in when building the deletion
>   scaffold — `SecurityShared` transactional methods plus the
>   `DefaultSecuritySystem` import-LDAP happy path.
> - **A9**: verify Track 22 queue count at Phase A: 5 lockstep groups
>   + 21 per-method `SymmetricKey` pins + 6 latent issues +
>   suggestion tier = the full absorption inventory above.
> - **Phase C iter-1 TS-5 / CQ1 / CQ2**: extract a shared
>   `TokenStubs` (`stubToken(...)` / `stubHeader(...)` builders) and
>   `TestTokenHeader` POJO under
>   `core/src/test/java/.../security/testutil/`. Currently ~400 LOC
>   of duplicated anonymous `Token` / `TokenHeader` stubs across
>   `TokenSignImplTest`, `ParsedTokenTest`, `DefaultKeyProviderTest`,
>   `AuthenticatorChainDispatchTest`, `AuthInfoTest`. A single
>   signature change on the `Token` interface currently requires
>   editing all five files.
> - **Phase C iter-1 CQ3**: hoist the `@After rollbackIfLeftOpen()`
>   helper into `DbTestBase` (or extract a JUnit 4 `@Rule`
>   `RollbackOnLeftOpen`). Currently duplicated verbatim across
>   `ImmutableUserTest`, `AuthenticatorChainDispatchTest`,
>   `DefaultSecuritySystemReloadTest`, `SecuritySharedTest`,
>   `ImmutableSecurityPolicyTest`, plus several pre-existing tests.
> - **Phase C iter-1 CQ15 / CQ16 / TS-11 / TS-12 / TS-13**: residual
>   inline FQN cleanups — `SecurityUser` and `DatabaseSessionEmbedded`
>   in `TokenSignImplTest` / `SecurityAuthenticatorAbstractTest`,
>   `org.junit.Assert.fail` static import in `PasswordValidatorTest`,
>   `java.util.Optional` / `java.util.Set` /
>   `DatabaseSessionEmbedded` in `AuthInfoTest` /
>   `SecurityRoleAndIdentityShapeTest`. Defer for batch consistency
>   with the FQN audit Track 22 already plans.
> - **Phase C iter-1 TS-4**: `SymmetricKeyTest` extends `DbTestBase`
>   but never uses the database session; the per-method DB
>   create/drop cycle adds latency for no functional reason. Drop
>   the inheritance during Track 22 cleanup or add the
>   `rollbackIfLeftOpen` net for future-author safety.
> - **Phase C iter-1 TS-7 / TS-8 / TS-10**: minor naming /
>   consistency nits — `configAuthenticator*` prefix in
>   `AuthenticatorChainDispatchTest` is ambiguous;
>   `SymmetricKeyDeadMethodsDeadCodeTest` bundles 21 method pins
>   into one class (the only `*DeadCodeTest` that does so);
>   inconsistent license-header preamble across the 16 dead-code
>   pin files. All cosmetic; address as part of the wider Track 22
>   convention sweep.
>
> **Track 17 dead-code deletions (lockstep with `*DeadCodeTest`
> pin removal):** the 16 `*DeadCodeTest` files added in Track 17
> Step 6 carry the `WHEN-FIXED: Track 22` deletion-marker convention
> already used by Tracks 9 / 12 / 14 / 15 / 16 — when the production
> targets in groups A.1–A.5 above are deleted, drop the matching pin
> file(s) in the same commit. The 21 per-method pins inside
> `SymmetricKeyDeadMethodsDeadCodeTest` are removed in three lockstep
> phases per group B above (do NOT delete the file as a whole — the
> live `SymmetricKey` class survives until Track 22 closes the
> remaining live methods).
>
> *From Track 18 (Index) Phase A technical review (iter-1, finding T2):*
> Four dead-code classes in `core/index` discovered during Track 18
> Phase A by PSI `ReferencesSearch` (all-scope): all four have **0
> production references** and form an isolated dead cluster. Track 18
> covers these via a `*DeadCodeTest` shape pin (per Track-17
> precedent) instead of synthesising live coverage.
>
> 1. **`com.jetbrains.youtrackdb.internal.core.index.IndexCursor`** —
>    interface; only implementer is `IndexAbstractCursor` which itself
>    is dead (see #2). 0 production references.
> 2. **`com.jetbrains.youtrackdb.internal.core.index.IndexAbstractCursor`** —
>    abstract class; only subclass is `IndexCursorStream` which is
>    dead (see #3). 0 production references outside the dead chain.
> 3. **`com.jetbrains.youtrackdb.internal.core.index.iterator.IndexCursorStream`** —
>    concrete; 0 callers. Bundled with #1 + #2 in a single
>    lockstep deletion (the three together are one dead cluster).
> 4. **`com.jetbrains.youtrackdb.internal.core.index.IndexKeyCursor`** —
>    interface; 0 implementers, 0 callers. Independent of cluster
>    #1/#2/#3 — separate lockstep deletion.
>
> **Lockstep deletion** (executed by Track 22): drop production
> classes #1+#2+#3 in one commit alongside the matching
> `*DeadCodeTest` pins from Track 18 Step 2; drop production class
> #4 in the same or a sibling commit. Track 18's Step 2
> `*DeadCodeTest` pins are added with the existing
> `WHEN-FIXED: Track 22` marker convention.

> *From Track 18 (Index) Phase C iter-2 gate-check (suggestions, all
> non-blocking — gate PASSED on every dimension):* Seven items
> surfaced when the iter-2 gate-check fanned out across code-quality
> / test-behavior / test-completeness on commit `84e117de31`. All
> are pure test-additive / stylistic and were absorbed here rather
> than burning Track 18's iter-3 counter on non-blocking work.
>
> **A. Cleared-TX branch coverage gaps** (test-completeness suggestions —
> tighten falsifiability under future cleared-branch refactors):
>
> 1. **TC16** — add `IndexOneValue.stream(session)` cleared-TX
>    coverage. Production guard at `IndexOneValue:421` (`if
>    (indexChanges.cleared) return ... txStream`) is currently
>    untested; the iter-2 fan-out covered `streamEntries(keys,asc)`,
>    `streamEntriesMajor`, `streamEntriesMinor` but not the
>    whole-index `stream` variant. Mirror the existing
>    `streamEntries_clearedTxChanges_returnsOnlyTxAddedKeys` shape
>    in `IndexOneValueTxTest`.
> 2. **TC17** — add `IndexOneValue.descStream(session)` cleared-TX
>    coverage. `IndexMultiValuesTxTest` gained
>    `descStream_clearedTxChanges_returnsOnlyTxAddedKeys` in iter-2;
>    `IndexOneValueTxTest` lacks the symmetric counterpart.
>    Production guard at `IndexOneValue:461`. Same shape as TC16.
> 3. **TC18** — add `IndexMultiValues.stream(session)` cleared-TX
>    coverage. Production guard at `IndexMultiValues:521`. Mirror
>    the new `descStream_clearedTxChanges` test in
>    `IndexMultiValuesTxTest`, swapping `index.descStream(session)`
>    for `index.stream(session)`.
> 4. **TC19** (minor) — add `IndexOneValue.getRids` inverse-cleared
>    coverage. Production at `IndexOneValue:117-122` reads
>    `if (!indexChanges.cleared) ... else rid = null` — the cleared
>    short-circuit returns null and is currently uncovered. Add a
>    test that asserts `getRids(session, "alpha")` is empty after
>    `addIndexEntry CLEAR + PUT` for a different key.
>
> All four items reuse the `capturedRidForCommittedKey` /
> `addIndexEntry CLEAR + PUT delta` setup pattern iter-2 introduced
> in those test files; expect each test to be ~10 lines.
>
> **B. Code-quality polish** (suggestions — non-blocking, pick up if
> Track 22 touches the cleared-TX test files for any other reason):
>
> 5. **CQ15** — `IndexOneValueTxTest` lacks the
>    `descStream_clearedTxChanges_returnsOnlyTxAddedKeys` test that
>    `IndexMultiValuesTxTest` gained in iter-2. Symmetric to TC17;
>    addressing TC17 closes CQ15 automatically. Listed separately
>    because it surfaced under code-quality (asymmetric coverage)
>    rather than as a falsifiability gap.
> 6. **CQ16** — extract a shared cleared-TX setup helper. Across
>    `IndexOneValueTxTest` (4 cleared-TX tests) and
>    `IndexMultiValuesTxTest` (5 cleared-TX tests), every test
>    repeats the same 6-line setup (`capturedRidForCommittedKey` →
>    `session.begin()` → resolve `index` and `tx` → CLEAR delta →
>    PUT delta → try-with-resources stream call → `assertEquals(...
>    List.of("delta") ...)`). Extract `Index
>    prepareClearedTxWithDeltaPut()` (returning the index with
>    cleared-TX state already set up) and a `drainKeys(stream)`
>    helper. ~9 copies × 6 lines collapse to ~3 helper lines per
>    test.
> 7. **CQ17** — `IndexMultiValuesTxTest:379-380`'s helper writes
>    `assertTrue("baseline collection for '" + committedKey + "'
>    must be non-empty", !collection.isEmpty())`. The same file
>    already imports and uses `assertFalse`; rewrite as
>    `assertFalse("...", collection.isEmpty())` for idiom parity.
>
> **Tracking note.** All seven items are pure test-additive (no
> production-code change) and trivial in isolation. They are listed
> separately rather than rolled into the dead-code lockstep block
> above because they don't gate any production deletion — Track 22
> may pick them up opportunistically when it visits the index test
> files for the dead-code deletions, or skip them entirely if the
> deferred-cleanup budget is tight (the index package's coverage
> miss is already documented as known scope per Track 18's Step 5
> baseline; closing TC16-19 would lift it slightly without changing
> the strategic outcome).

> *From Track 19 (Storage Fundamentals) Phase B + Phase C:* Test-
> additive coverage track with zero production-source changes.
> Track 22 inherits four production-bug pins (with `WHEN-FIXED:
> Track 22 — <fix>` markers) and three suggestion-tier items.
>
> *Production bugs pinned with WHEN-FIXED markers (4 issues, fix the
> pin lockstep with the production change):*
> 1. **`CollectionBasedStorageConfiguration.setMinimumCollections`
>    deadlock** (Step 1): write-lock → `getContextConfiguration()` →
>    read-lock on the same non-reentrant `ScalableRWLock` — deadlocks.
>    Pinned in commentary only (`CollectionBasedStorageConfigurationTest`)
>    because an executable pin would leak a daemon thread spinning in
>    `Thread.yield()`. Fix: replace the `getContextConfiguration()`
>    call inside `setMinimumCollections` (line 326) with a direct
>    `configuration.setValue(...)` call mirroring `readMinimumCollections`
>    line 346 precedent.
> 2. **`CollectionBasedStorageConfiguration.removeProperty` cache
>    staleness** (Step 1): does not invalidate the in-memory
>    `PROPERTIES` cache map. Pinned by
>    `testRemovePropertyDoesNotInvalidateInMemoryCache` and
>    `testRemovePropertyRemovesFromPersistentBtree` with WHEN-FIXED
>    inversion. Fix: add `properties.remove(name)` to `dropProperty`
>    (line 1738) symmetrically to `doSetProperty`'s `properties.put`
>    (line 1095).
> 3. **`AbstractLinkBag.EnhancedIterator.reset()` stale `nextPair`**
>    (Phase C iter-1, surfaced by TB1/BC1 falsifiability tightening):
>    `reset()` only re-creates the spliterator at lines 797-799; does
>    NOT clear or re-prime the cached `nextPair` field. After one
>    `next()` on a 2-element bag, post-reset traversal yields 3
>    elements (stale `nextPair` leaks an extra). Pin:
>    `LinkBagIteratorOpsTest.testEnhancedIteratorResetRestartsTraversal`
>    with `assertNotEquals` and `postResetCount == 3` WHEN-FIXED
>    assertions. Fix: in `reset()`, add `nextPair = null;
>    spliterator.tryAdvance(p -> nextPair = p);` mirroring the
>    constructor.
> 4. **`DiskStorage.XXHashOutputStream.write(byte[], int, int)`
>    length/end-index mismatch** (Phase C iter-1, surfaced by TB2/TC3
>    hash-state tightening): the hash update at lines 1979-1982 calls
>    `xxHash64.update(bts, st, end - st)` (interpreting the third
>    parameter as an end-INDEX), but `super.write(bts, st, end)`
>    passes the third parameter verbatim as a length. With the
>    standard `(b, off, len)` calling convention, hash sees `len -
>    off` bytes while the underlying stream gets `len` bytes. Latent
>    today because all production callers pass `off == 0`
>    (DataOutputStream wraps the writer). No executable pin (would
>    require a non-zero-offset caller, which doesn't exist in
>    production). Fix: align the two — change
>    `xxHash64.update(bts, st, end - st)` to
>    `xxHash64.update(bts, st, end)` (interpreting `end` as a length,
>    matching `super.write` and the standard contract).
>
> *Track 22 absorption work for Track 19 forwards:*
> - For items 1, 2, 3: land the production fix + flip the WHEN-FIXED
>   pin to its correct-behaviour assertion in the same commit.
> - For item 4: add a non-zero-offset hash-update test to
>   `DiskStorageStaticHelpersTest` (would surface today's bug
>   immediately) before applying the fix; flip the test to assert
>   correct hash semantics in the same commit.
>
> *Suggestion-tier deferred items from Track 19 reviews:*
> - **TS12 (Phase C iter-2 gate)**: stale Javadoc reference in
>   `AsyncFileTest` lines 30-36 — the comment mentions a
>   `testCopyToCopiesAllData` test that does not exist in the file.
>   Either drop the second sentence of the Javadoc or rewrite to
>   describe the actual code (e.g., name `testReplaceContentWith` as
>   the only second-AsyncFile case).
> - **TS13 (Phase C iter-2 gate)**: `executor.shutdownNow()` in
>   `AsyncFileTest.@After` does not await termination. Comment claims
>   "AsyncFile worker threads release file channels before the delete
>   races" but `shutdownNow()` only interrupts; on a thread blocked
>   in a write, the interrupt does not synchronously close the
>   channel. Every test calls `file.close()` before returning so the
>   race window is small in practice. Either follow `shutdownNow()`
>   with `executor.awaitTermination(5, TimeUnit.SECONDS)` or soften
>   the comment to "best-effort — every test also calls
>   `file.close()` synchronously which is the actual channel-release
>   barrier."
> - **PageOperation toString chain non-accumulation** (Phase C iter-2
>   implementer note): `PageOperation` / `AbstractPageWALRecord` /
>   `LogSequenceNumber` `toString()` chain replaces rather than
>   appends — each subclass `@Override` shows only its own appended
>   string, NOT the parent's fields. As a code-quality cleanup (not
>   a bug fix) Track 22 may consider rewriting the chain so each
>   subclass appends, making debug log output more diagnostic. Out
>   of scope for Track 19's coverage focus; pure suggestion tier.
>   **Track 20 note:** Track 20 tests pin getter values rather than
>   `toString()` content throughout to avoid this trap; the underlying
>   production-code cleanup remains a Track 22 item (reinforced from
>   Track 19's queue).

> *From Track 20 (Storage Cache & WAL) Phase B:* Test-additive
> coverage track with zero production-source changes. Track 22
> inherits the following absorption items.
>
> *Dead-code deletion (PSI-confirmed zero project-wide references):*
> 1. **`cache.local.aoc.FileSegment` dead-code deletion** (Phase A
>    adversarial F1): The sole class in `cache.local.aoc`,
>    `FileSegment`, has zero callers and zero implementers project-wide
>    (PSI-confirmed at Phase A review and re-confirmed at Step 6
>    baseline). Track 20 accepted 0% coverage on `cache.local.aoc`
>    explicitly because adding tests for dead code would be
>    counter-productive. Track 22 should delete `FileSegment` and the
>    `cache.local.aoc` package. Phase A adversarial review F1 — safe
>    to delete without test retrofit.
>
> *Package mislocation cleanup (non-bug, historical artifact):*
> 2. **`WOWCacheTestIT` package mislocation** (Phase A adversarial F8):
>    `WOWCacheTestIT` currently lives in package
>    `storage.index.hashindex.local.cache` (historical artifact from
>    OrientDB ancestry). It tests `WOWCache` behaviour and belongs in
>    `storage.cache.local`. Track 22 should relocate via IDE
>    refactor (move class in IntelliJ, update imports and
>    `META-INF/services` if any) and verify all surefire tests still
>    pass. Non-blocking for coverage; informational only.
>
> *Production bugs pinned with WHEN-FIXED markers (3 issues, fix the
> pin lockstep with the production change):*
> 3. **`addOnlyWriters` / `removeOnlyWriters` counter-set
>    non-atomicity** (`WOWCache.java:1350-1358`): `exclusiveWritePages`
>    and `exclusiveWriteCacheSize` are mutated in `addOnlyWriters` and
>    `removeOnlyWriters` without the per-page `lockManager` exclusive
>    lock; the author comment at :3975-3977 admits eventual consistency.
>    A concurrent `store` + `addOnlyWriters` + `flush` sequence can
>    produce counter drift or orphan `PageKey`. Pinned by
>    `WOWCacheConcurrencyShapesTest.counterSetNonAtomicityProbe` with
>    WHEN-FIXED marker. Fix: synchronize access to the two counters
>    under the per-page `lockManager` exclusive lock, mirroring the
>    pattern at the `store` entry point. Track 22 should apply the
>    fix and flip the WHEN-FIXED pin to the correct-behaviour
>    assertion.
> 4. **`fileIdByName` visibility race** (`WOWCache.java:846-854` /
>    `:831-832`): `addFile()` writes `nameIdMap.put(:831)` before
>    `idNameMap.put(:832)`. A concurrent `fileIdByName()` call between
>    the two `put` calls (`:846-854`, no `filesLock`) sees an external
>    fileId in `nameIdMap` that is not yet in `idNameMap`. Pinned by
>    `WOWCacheConcurrencyShapesTest.fileIdByNameRaceWindowProbe` with
>    WHEN-FIXED marker. Fix: either reorder the `addFile` writes
>    (`idNameMap.put` first, `nameIdMap.put` second) or protect
>    `fileIdByName` with the `filesLock` read-lock that `addFile`
>    holds. Track 22 should apply the fix and flip the WHEN-FIXED pin.
> 5. **`store` re-entry silent swallow** (`WOWCache.java:1213-1239`):
>    When a page is already in the store (existing `pagePointer`),
>    `store()` contains `assert pagePointer.equals(dataPointer)`.
>    Asserts run only with `-ea`; in production without `-ea`, a
>    mismatching `dataPointer` is silently ignored and the existing
>    mapping is kept. Pinned by
>    `WOWCacheConcurrencyShapesTest.storeReentryMismatchProbe` with
>    WHEN-FIXED marker. Fix: replace the `assert` with an explicit
>    check and throw `IllegalStateException` unconditionally, making
>    the mismatch detectable in production. Track 22 should apply the
>    fix and flip the WHEN-FIXED pin.
>
> *Static helper informational pin (not a production bug, but
> asymmetry worth noting):*
> 6. **`AbstractWriteCache.composeFileId` negative-fileId
>    sign-extension asymmetry**: `composeFileId` does NOT mask the
>    long-promoted `fileId` before OR-ing with `storageId`. A negative
>    `fileId` sign-extends and overwrites the upper 32 bits, so
>    `extractStorageId` returns -1 for negative fileIds. WOWCache only
>    uses negative fileIds as "booked but not yet added" sentinels with
>    no live `storageId` paired — no production impact — but the
>    asymmetry is now pinned in `AbstractWriteCacheStaticHelpersTest`.
>    Track 22 may consider adding a `0xFFFFFFFFL` mask in
>    `composeFileId` for defensive correctness. Informational only;
>    not a bug pin. No WHEN-FIXED marker.
>
> *Test-convention note (codify if Track 22 adds shared test infra):*
> 7. **Mockito Void-stub trap**: stubbing `void`-returning methods with
>    `when(...).thenReturn(...)` throws `CannotStubVoidMethodWithReturnValue`
>    in Mockito. Default-null return is sufficient for
>    `FlushTillSegmentTask` and `FindMinDirtySegment` tests. Future
>    Track 21 / Track 22 wrappers should use `doReturn(...)` or rely
>    on the Mockito default for void methods. Worth codifying in test
>    conventions if Track 22 introduces shared `cache.local` test
>    infrastructure.
>
> *PageOperation toString chain (reinforcement from Track 19, Track 20
> adds context):*
> 8. **WAL record toString chain replace-vs-append** (Phase C iter-2
>    Track 19, reinforced by Track 20): `AbstractPageWALRecord.toString()`
>    and its chain beneath it replace parent fields rather than append.
>    Track 20 tests pin getter values throughout (`assertEquals(42L,
>    rec.getPageIndex())`) to avoid the trap. The underlying
>    production-code cleanup (rewriting the chain so each subclass
>    appends its own fields rather than replacing the whole string)
>    remains a Track 22 suggestion-tier item. No test flipping needed;
>    the existing Track 20 tests already avoid the trap.
>
> *Track 20 Phase C deferred suggestions (track-level review surfaced
> ~27 suggestion-tier items beyond the should-fix items applied across
> two iterations; the most significant are recorded below — see the
> `Review fix:` commits on the branch for the full list and the
> Phase C track episode for the synthesis summary):*
> 9. **`DoubleWriteLogGLTest` is 805 LoC / 26 tests in one flat class**
>    (Phase C `review-test-structure` TS4 + `review-code-quality` CQ5):
>    the `DoubleWriteLogNoOP` block (~9 tests, 100 LoC at the file
>    tail) is a clean candidate to extract as a separate
>    `DoubleWriteLogNoOPTest` class. The remaining 17 tests can stay
>    or be split further (lifecycle vs write-read). Same shape as
>    `CASDiskWriteAheadLogLifecycleTest` (758 LoC, TS5) — both deferred
>    here. Pure refactor; Track 22 may consolidate in one cleanup commit.
> 10. **`TrackingWriteCache` inline stub in `LockFreeReadCacheFileOpsTest`
>     (~250 LoC, half the file)** (Phase C TS6): extract to a top-level
>     test fixture class — likely
>     `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/TrackingWriteCache.java`
>     — keeping only the stub-tracking counters package-visible.
>     Eliminates 250 LoC of empty `WriteCache` interface stubs and
>     enables reuse if Track 21 / Track 22 needs a similar stub.
> 11. **Page-level test fixture duplication across `BoundedBuffer*`,
>     `CacheEntryImplTest`, `LockFreeReadCacheFileOpsTest`'s stub**
>     (Phase C TS7): all four implement the same Track 19 page-level
>     pattern (acquireDirect → CachePointer → incrementReadersReferrer
>     → register for tearDown decrement). A small `PageEntryFixture`
>     utility under `test-commons` (or in the `cache` test package)
>     would centralise the boilerplate.
> 12. **`BoundedBuffer*Test` are 100% sequential — lock-free MPSC
>     contract not exercised under contention** (Phase C
>     `review-test-concurrency` TX7): `BoundedBufferDrainTest` and
>     `BoundedBufferRingTest` never spawn a thread, so the
>     `Buffer.FAILED` branch (CAS lost), the lazy-publish race in
>     `drainTo`, and the "producer fills past capacity while consumer
>     drains" interleaving are never reached. A regression that swaps
>     `compareAndSet` for `set` in `offer`, or that drops `lazySet`
>     for the slot publish in the constructor, would still pass every
>     existing test. Track 22 should add an MT probe (≥4 producers
>     racing offer + 1 consumer racing drainTo, `CountDownLatch`
>     start gate, < 5 s timeout, capture thread errors via
>     `AtomicReference<Throwable>`). See the spawn template in
>     iteration 1's F11 (the new MT-on-same-key probe in
>     `WOWCacheConcurrencyShapesTest`) for the canonical pattern.
> 13. **Direct-memory pool cleanup gaps**
>     (Phase C `review-bugs-concurrency` BC3 + `review-performance` PF3
>     + `review-crash-safety` CS5): `LockFreeReadCacheFileOpsTest`,
>     `BoundedBufferRingTest`, `BoundedBufferDrainTest`, and
>     `CacheEntryImplTest` allocate per-test `ByteBufferPool` plus
>     `DirectMemoryAllocator` but `@After` does not call
>     `bufferPool.clear()` or `allocator.checkMemoryLeaks()`. Bounded
>     leak per class (≤ 256 × 4 KiB = 1 MiB on `LockFreeReadCacheFileOpsTest`,
>     smaller elsewhere; JVM Cleaner reclaims on GC) — tolerable today
>     but inconsistent with the codified Track 19 pattern in
>     `CachePointerPageFrameTest:88-89`. Add the cleanup pair across
>     the four classes for hygiene.
> 14. **`LockFreeReadCacheFileOpsTest` swallowed `StorageException` in
>     tearDown** (Phase C CS6): `readCache.clear()` throws
>     `StorageException` if any entry is still acquired (the very
>     thing the test exists to catch); the current tearDown wraps it
>     in `catch (StorageException ignored)`, masking the regression.
>     Track 22 should `Assert.fail(e.getMessage())` (or at minimum log
>     to stderr) so a referrer-leak surfaces as a CI failure.
> 15. **`AbstractPageWALRecord.toString()` and `Cursor`/`Node.toString()`
>     no longer pinned by tests after iter-2** (Phase C iter-2 gate
>     check TC-iter3-2): iter-2 deleted four substring-only
>     `toString().contains(...)` tests on `Cursor` and `Node` (per the
>     R3 forbidden-as-primary-assertion rule). The deletions did not
>     introduce a new test gap because the deleted tests were
>     substring-only (weakly falsifiable), but if recovery diagnostics
>     scrape these strings, a refactor that drops `itemIndex` /
>     `deqidx` / `enqidx` from the output now passes. Track 22 may add
>     a single canonical-form `assertEquals(...)` pin per class as
>     diagnostic-output guard, paired with the wider toString
>     replace-vs-append cleanup at item 8 above. Suggestion tier only.
> 16. **`exactBufferSizeOffersFitInSingleNode` test name overstates
>     production invariant** (Phase C iter-2 gate check TC-iter3-1):
>     `Node.enqidx` initialises to 1, so an offer of `BUFFER_SIZE = 1024`
>     items fills slots 1..1023 in node 1 and the 1024th offer triggers
>     a fresh node. The test still passes for the right reason (1024
>     polls succeed, then null), but the docstring claim "must fit in a
>     single node" is wrong. Track 22 should rename to
>     `exactBufferSizeOffersDrainCleanly` and rewrite the comment, or
>     strengthen with a reflective node-count probe. Cosmetic — does
>     not affect coverage.
> 17. **Production assert / `equals` reflection-fragility hardening**
>     (Phase C `review-crash-safety` CS4, `review-code-quality` CQ7,
>     `review-test-structure` TS12): `WOWCacheConcurrencyShapesTest`
>     reflectively `setField(cache, "exclusiveWriteCacheSize", ...)`
>     etc. on a Mockito spy. A field rename throws
>     `NoSuchFieldException` (loud — fine), but a **type change** (e.g.,
>     `AtomicLong` → `LongAdder`) succeeds silently due to type-erasure
>     on generic fields, leaving the WHEN-FIXED pin broken. Track 22
>     should either add explicit `assertSame(AtomicLong.class,
>     WOWCache.class.getDeclaredField("exclusiveWriteCacheSize").getType())`
>     pre-flight checks, or — when the production fix lands and the
>     pin flips — replace the reflective injection with a real
>     `WOWCache` constructor invocation.
> 18. **Smaller suggestion-tier items absorbed without restating in
>     full here** (Phase C dimensions code-quality, test-behavior,
>     test-completeness, test-structure): `assertNotEquals` on byte[]
>     should be `assertNotSame` in `ActiveWALRecordsRoundTripTest:108`
>     (CQ10 / TB12); `hashCode` non-zero rather than exact-value pin in
>     `CachePointerPageFrameTest:616` (TB13); seven `DoubleWriteLogNoOP`
>     coverage-only no-exception tests could be parametrised
>     (TB14); redundant equals tests in `PageDataVerificationErrorTest`
>     (TB15); `EventWrapper` null-Runnable + `WALChannelFile.position()`
>     past-EOF (TC4); `FrequencySketch` `tableMask == 0` boundary (TC5);
>     `BoundedBuffer.offer` null entry + FAILED branch (TC6); test-method
>     naming consistency (CQ8 / TS8); `BUFFER_SIZE` magic number in
>     `BoundedBufferRingTest` (CQ9); `BLOCK_SIZE` comment imprecision
>     in `DoubleWriteLogGLTest` (CQ11); `String` concat in `fileIdByName`
>     MT loop (PF5); fsync-without-verification note for
>     `WALHelperClassesTest` (CS7); `WALHelperClassesTest.deleteTestDir`
>     null-safety on `dir.listFiles()` (TS11); `CASDiskWriteAheadLogLifecycleTest`
>     `@After` resource cleanup robustness on test throws (TS10).
>     Track 22 may absorb these in a single style/cleanup commit or
>     leave them as-is; none affect correctness.

> *From Track 21 (Storage B-tree & Impl) Phase B:* Test-additive coverage track with zero
> production-source changes. Track 22 inherits the following absorption items.
>
> *Dead-code deletion groups (forwarded per Track 17/18/20 precedent):*
> 1. **`DecimalKeyNormalizer.java:43–101` dead-helper deletion** — three private methods
>    (`scaleToDecimal128`, `clampAndRound`, `ensureExactRounding`) unreachable from any
>    production caller (confirmed by grep at Step 2). Deletion will lift
>    `nkbtree/normalizers` branch% from 23.3% to ≥70%. The `unsigned()` helper is live and
>    stays. No WHEN-FIXED pin was added for these helpers since they are structural dead code
>    (method-level, not assert-phantom), not a regression risk.
> 2. **`sbtree/singlevalue/v1` deletion lockstep group** — delete
>    `CellBTreeBucketSingleValueV1.java` + `CellBTreeSingleValueEntryPointV1.java` (242 LOC,
>    0 main + 0 test refs; PSI-confirmed at Phase A). Atomically also delete the new
>    `CellBTreeBucketSingleValueV1DeadCodeTest` and
>    `CellBTreeSingleValueEntryPointV1DeadCodeTest` added by Track 21 (shape pins whose only
>    purpose is to serve as the deletion marker). No legacy test files to delete for v1
>    single-value (the v1 bucket/entry-point classes had no pre-existing tests).
> 3. **`sbtree/local/v1` deletion lockstep group** — delete `SBTreeBucketV1.java` +
>    `SBTreeNullBucketV1.java` + `SBTreeValue.java` (`SBTreeValue` has 8 main refs but all
>    intra-v1-package; transitively dead once the bucket pair is removed). Atomically also
>    delete the legacy test files `SBTreeLeafBucketV1Test.java`, `SBTreeNonLeafBucketV1Test.java`,
>    `SBTreeNullBucketV1Test.java` (these are pre-existing coverage tests of dead code) and
>    the new `SBTreeBucketV1DeadCodeTest`, `SBTreeNullBucketV1DeadCodeTest`,
>    `SBTreeValueDeadCodeTest` added by Track 21 (shape pins acting as deletion markers). One
>    coordinated commit per Track 17/18 precedent.
>
> *Production bugs pinned with WHEN-FIXED markers (fix the pin lockstep with the production
> change):*
> 4. **`StorageStartupMetadata.makeDirty` precondition gap** — calling `makeDirty(version)`
>    on an uninitialised instance (before `create()` or `open()`) falls past the volatile
>    early-return into `update(serialize())` which calls `channel.truncate(0)` on a null
>    channel and throws NPE. Current behaviour pinned by
>    `StorageStartupMetadataTest.testMakeDirtyOnUninitialisedThrows` (WHEN-FIXED marker). The
>    `clearDirty` asymmetry (no-op due to `!dirtyFlag` early return) pinned by
>    `testClearDirtyOnUninitialisedFails`. Fix: add an explicit state guard at the top of
>    `makeDirty` (and `clearDirty`) — `if (channel == null) throw new
>    IllegalStateException("channel not initialised — call create() or open() first")` — so
>    misuse is diagnosed without reading an NPE stack trace.
>
> *Coverage gap notes (informational for Track 22 IT expansion):*
> 5. **`paginated` top-level branch% gap** (65.3% vs ≥70% target; D4-accepted in Track 21)
>    — recovery/legacy paths in `StorageStartupMetadata.open()` exercised only by the IT
>    suite (`LocalPaginatedStorageRestoreFromWALIT`, `StorageTestIT`). Candidates for IT
>    expansion: (a) `StorageTestIT` scenarios that corrupt the metadata file and verify the
>    backup-restore recovery path; (b) test that writes a size-9 or size-1 legacy metadata
>    file and verifies `open()` reads the older format correctly.
> 6. **`multivalue/v2` assert-phantom branch tracking** (47 assert statements in the
>    package): raw JaCoCo branch% is 69.2%; `coverage-gate.py` strips assert-line branches
>    and the gate PASSES. Future top-up of `multivalue/v2` branch% should use
>    `coverage-gate.py` as the authoritative gate, not raw JaCoCo, to avoid chasing phantom
>    gaps.
>
> *Test conventions codified by Track 21:*
> 7. **`@Category(SequentialTest.class)` for `GlobalConfiguration` mutations** — any test
>    class that mutates `GlobalConfiguration.BTREE_MAX_KEY_SIZE` (or other process-wide
>    `GlobalConfiguration` values) must carry `@Category(SequentialTest.class)` to prevent
>    parallel surefire thread pollution. `BTreeLifecycleTest` carries this; Track 22 should
>    audit other B-tree test classes for similar mutations.
>
> *From Track 21 Phase C (track-level review fix iterations):*
> 8. **`StorageStartupMetadata.open()` legacy-format reader BIG_ENDIAN dependency** —
>    the legacy paths at `StorageStartupMetadata.java:182–194` (size ≤ 9) silently rely
>    on `ByteBuffer`'s default `BIG_ENDIAN` order rather than `nativeOrder()`. The new
>    Phase C iter-2 tests `testOpenWithLegacy9ByteFileReadsLastTxId` and
>    `testOpenWithLegacyOneByteFileReadsDirtyFlag` hand-craft big-endian bytes to match.
>    Two acceptable fixes: (a) add an explicit `.order(ByteOrder.BIG_ENDIAN)` call to
>    the production reader so the dependency is self-documenting, OR (b) add a Javadoc
>    note on `open()` pinning the legacy-format byte-order expectation. Either fix
>    keeps the new tests green; pick (a) for clarity if the reader is otherwise
>    untouched. No semantic change — the tests prove the production code already reads
>    big-endian today.
> 9. **Null-snapshot test-helper accessor on `AbstractStorage`** — populating
>    `sharedNullIndexesSnapshot` from a high-level `db.begin / commit` sequence is
>    fragile (depends on UNIQUE-vs-NOTUNIQUE index semantics + insert-with-null then
>    update-to-non-null sequencing). The new Phase C iter-2 test
>    `hasActiveIndexSnapshotEntries_routesNullSuffixEngineToNullMap` works around this
>    by registering a real engine and cross-checking the sub-null-snapshot factory
>    shape (a routing-only verification). For a positive-direction null-snapshot
>    test in the future, consider adding a package-private helper to `AbstractStorage`
>    that lets test code seed `sharedNullIndexesSnapshot` directly, mirroring how some
>    tests already manipulate `sharedIndexesSnapshot` via setter-style helpers.
>    Otherwise the routing-only verification is the durable test pattern.
> 10. **Ephemeral-identifier sweep across earlier-track test files** — Track 21
>    Phase C iter-1's adversarial review surfaced ~7 test files outside Track 21's
>    diff (in `core/src/test/.../command/...` and `command/script/...`) still
>    citing "Track 22" in durable Javadoc / comments, inherited from earlier
>    tracks. These are durable-content rule violations of the same shape as
>    Track 21 iter-1's CQ1/CQ2 fixes. Track 22 should run a single sweep across
>    `core/src/test/` for `\bTrack [0-9]+\b` and `\bStep [0-9]+\b` patterns
>    outside `_workflow/` and rewrite each match with a label-free phrasing.

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

### Reconstructed inherited DRY queue (Tracks 10–13 — stitches the recovery gap above)

The 263 lines of backlog content lost in the 2026-05-04 `git clean -fd`
incident covered the inherited cleanup absorptions from Tracks 10, 11,
12, and 13. The Operational Notes section of `implementation-plan.md`
prescribed a reconstruction protocol: re-read the `**Track episode:**`
block of each affected track and stitch the items it forwarded back
into Track 22's queue. The reconstructed inventory follows; Phase A
reviews must validate this against the plan-file episodes before any
step that consumes these items begins.

**Track 10 (Query & Fetch) — forwarded items:**

> *Deletion lockstep groups (live-query / fetch dead-code reframe):*
> - Entire `core/query/live/` package (`LiveQueryHookV2` listener +
>   public-static surface; cross-module grep found 0 callers in
>   `server/`, `driver/`, `embedded/`, `gremlin-annotations/`, `tests/`);
>   the only live surface is `LiveQueryHookV2.unboxRidbags`, called from
>   `CopyRecordContentBeforeUpdateStep.java:52` — preserve.
> - Three orphan listener interfaces in `core/query/`.
> - Entire `core/fetch/` package (`FetchHelper`, `FetchPlan`,
>   `FetchContext`, `FetchListener` — 0 callers).
>
> *Production-bug pins (WHEN-FIXED forwarded):*
> - `LiveQueryHookV2.calculateProjections` always-returns-empty-or-null
>   (consequence: `calculateBefore`/`calculateAfter` load ALL properties
>   regardless of subscriber projection filters).
> - V1 `break` vs V2 `continue` divergent `InterruptedException`
>   handling in the live-query loop.
> - `ExecutionStep.java:41` duplicate `getSubSteps()` call whose return
>   value is discarded.
>
> *DRY / cleanup items:*
> - `DepthFetchPlanTest` style modernization to `TestUtilsFixture` +
>   `executeInTx` callbacks (started in Track 10; consistency sweep
>   across siblings).
> - ~25 suggestion-grade items deferred from the iter-1 / iter-2
>   track-level review (most fold into Track 22's DRY sweep).

**Track 11 (Scheduler) — forwarded items:**

> *Deletion lockstep groups:*
> - `CronExpression.getTimeZone()` lazy `TimeZone.getDefault()`
>   fallback (refined from track plan's broader scope — the
>   `setTimeZone(TimeZone)` setter itself stays live).
> - Deprecated `Scheduler.{load, close, create}` interface methods +
>   their three `SchedulerProxy` overrides.
>
> *Out-of-scope-by-design items (recorded for completeness; not
> deletion candidates):*
> - Two log-and-swallow `catch (Exception)` paths in `SchedulerImpl`
>   plus the interrupt-during-run race.
>
> *Production-bug pins (WHEN-FIXED forwarded; falsifiable regression
> tests already in place):*
> - `ScheduledEvent` ctor silently swallows `ParseException` and
>   leaves `cron == null` (paired with the cron-field unsafe-publication
>   finding — `cron` is non-final / non-volatile while reads are
>   timer-locked).
> - `executeEventFunction` retry-loop bug — 10× loop runs unconditionally
>   because `catch NeedRetryException` is mis-scoped inside the lambda.
> - `SchedulerImpl.onEventDropped` NPE when the dropped-events
>   custom-data map was never populated.
> - `CronExpression` DOM-field parser leniency — e.g.,
>   `"0 0 12 5X * ?"` silently dropped trailing `X`.
>
> *DRY / cleanup items (~14 iter-2 suggestion-tier):*
> - Interrupt-with-null-timer branch coverage.
> - Tab-separator parse coverage.
> - DST spring-forward test.
> - Direct `SchedulerImpl.{create, load}` pins (needed once proxy
>   deprecated methods are deleted in lockstep with the deletion above).
> - DRY/cohesion sweep candidates carried forward.

**Track 12 (Serialization — String & Core) — forwarded items:**

> *Deletion lockstep groups (5 dead-code surfaces; pinned via
> `*DeadCodeTest` shape pins so deletion is atomic with test removal):*
> - `(a)` `RecordSerializerCSVAbstract` instance API (402 lines, 10.4%
>   covered, dead).
> - `(b)` `RecordSerializerStringAbstract` abstract instance API +
>   four unused statics.
> - `(c)` `JSONWriter`.
> - `(d)` `Streamable` interface + `StreamableHelper`.
> - `(e)` `SerializationThreadLocal` listener path
>   (`$1` synthetic inner class).
>
> *Residual coverage gaps forwarded with explicit deferred-cleanup
> rationale:*
> - `(f)` JSON Jackson legacy 1.x export branches.
> - `(g)` `StringSerializerHelper` parser-token branches.
> - `(h)` `MemoryStream` record-id paths (re-measure after Tracks 14–15
>   migrated `RecordId*` / `RecordBytes` callers off the `@Deprecated`
>   class).
> - `(i)` `UnsafeBinaryConverter` platform-detection cold path.
> - `(j)` `StreamSerializerRID` deprecated two-arg ctor + wrapper.
>
> *DRY / cleanup items (~12 iter-2 suggestion-tier):*
> - Code-quality cosmetics, test-behavior pin tightening, additional
>   completeness pins, defense-in-depth security pins, test-structure
>   cleanups across the new ~480 serialization tests.
> - Step 1 inert-converter-test repair recorded for traceability —
>   pattern available for future `*Test` files lacking `@Test`
>   annotations.

**Track 13 (Serialization — Binary) — forwarded items:**

> *Deletion lockstep groups (4 dead-code surfaces; `*DeadCodeTest`
> shape-pinned):*
> - `SerializableWrapper`.
> - `RecordSerializationDebug`.
> - `RecordSerializationDebugProperty`.
> - `MockSerializer` (sentinel — needs lockstep removal of the
>   `BinarySerializerFactory` registration for
>   `PropertyTypeInternal.EMBEDDED` id `-10`).
>
> *Production-bug pins (WHEN-FIXED forwarded):*
> - `BytesContainer` zero-capacity infinite-loop hang via the byte-array
>   constructor.
> - `SerializableWrapper.fromStream` security gap (no `ObjectInputFilter`,
>   no class allow-list, no length cap on `ObjectInputStream.readObject()`).
> - Asymmetric version-byte handling in
>   `RecordSerializerBinary.fromStream(byte[])` — unguarded
>   `serializerByVersion[iSource[0]]` AIOOBE + Base64-of-input WARN-log
>   path that amplifies log-injection.
> - `BinarySerializerFactory.create()` registers a fresh
>   `new NullSerializer()` rather than the singleton.
> - `MockSerializer.preprocess` returns null instead of input
>   (sentinel — folded into the `(d)` deletion scope).
> - `RecordSerializationDebug*` carries `faildToRead` typo.
> - Cluster-id `(short)` cast in `LinkSerializer` /
>   `CompactedLinkSerializer` is unreachable through public API but the
>   silent truncation would surface if the upstream
>   `RecordId.checkCollectionLimits` guard relaxed.
>
> *DRY / refactor candidates:*
> - `runInTx` helper extension (already in
>   `RecordSerializerBinaryTestFixture`).
> - `field()` helper extension (already in
>   `BinaryComparatorV0TestFixture`).
> - `assertCanonicalBytes` helper consolidation across tests that
>   currently inline byte-array assertions.
> - Sibling `*SerializerTest` extension uniformity.
>
> *Residual coverage gaps:*
> - B-tree-backed LinkBag / LinkSet write paths (currently exercised
>   only via in-memory paths).
> - `EntitySerializerDelta` dry-run path.
> - `CompositeKeySerializer` Map-flatten preprocess negative branches.
>
> *Iter-3 design-level suggestions (cataloged for completeness):*
> - Javadoc shape consistency across the `*SerializerTest` family.
> - LinkBag middle-byte change-tracker pin gap.
> - `CompactedLinkSerializer` WAL-overlay max-cluster pin gap.

### Clarifications

> Track 22 must include a dedicated step (and/or sub-step within each
> coverage step) that creates a YouTrack `YTDB`-project issue for each
> WHEN-FIXED test currently pinned in `core/src/test/`, then rewrites
> the inline `// WHEN-FIXED: Track 22` (and `// WHEN-FIXED: deferred-
> cleanup track`) marker to reference the issue ID — e.g.,
> `// WHEN-FIXED: YTDB-NNNN`. The marker rewrite and the issue
> creation must be in lockstep: every issue created must have its ID
> threaded back into the corresponding test source within Track 22, and
> no rewritten marker may reference a YTDB ID that was not created in
> this branch.
>
> **Inventory at Phase A start:** 164 distinct test files contain WHEN-
> FIXED markers (440 markers total across the core test sources),
> split as 63 `*DeadCodeTest.java` lockstep deletion pins and 101 non-
> dead-code production-fix / refactor pins. Within a single file,
> multiple markers usually pin the same logical fix; granularity
> guidance for the issue split is **one issue per logical fix**, not
> one per marker — collapse dead-code clusters (Binary Token cluster
> ~6 files; SBTree V1 cluster 5 files; Database dead-pools cluster 4
> files; Symmetric Key cluster 3 files; Hooks cluster 5 files; Live
> Query cluster 2 files; Command Script cluster ~3 files; etc.) into
> a single deletion-tracking issue per cluster, and collapse same-fix
> multi-marker files (e.g., the eight markers in `ScriptManagerTest`
> map to ~5 distinct production issues — split or merge as the
> issue-by-issue review dictates).
>
> **Issue field defaults for the YTDB project** (per
> `get_issue_fields_schema` at Pre-Flight time): no required custom
> fields, but Type=Bug for production-fix pins, Type=Task for dead-
> code-deletion pins, Priority=Normal default; Subsystem set per
> package/area as appropriate; State=Submitted on creation. Issue
> body should always include (1) the test class file path and method
> name(s) carrying the marker, (2) a verbatim quote of the WHEN-FIXED
> comment block from the test source so the issue is self-contained,
> (3) a one-line "what to do when the issue is fixed" pointing at the
> test assertion that needs flipping, (4) the originating track
> reference (e.g., "Surfaced during Track NN").
>
> **Cross-step interaction:** the WHEN-FIXED rewrite step must run
> **before** any dead-code lockstep deletion in Track 22 — once the
> production class is deleted, the corresponding `*DeadCodeTest.java`
> file is also deleted, taking its WHEN-FIXED marker with it. So the
> deletion lockstep groups can either (a) skip issue creation
> entirely (the deletion happens in this branch, no future fix is
> needed) and just delete the test, or (b) create a tracking issue
> first that's marked Fixed in this same branch's PR. Phase A should
> decide which convention; "(a)" is simpler and avoids issue churn.
>
> **No issues created during Pre-Flight.** This clarification only
> records the requirement; the actual issue creation and marker
> rewrite happen in the appropriate Track 22 steps after Phase A
> review and decomposition.

> **Dead-code deletion policy for Track 22: Hybrid (cluster-by-cluster).**
>
> Phase A's adversarial review classifies each dead-code cluster
> (the ~63 `*DeadCodeTest.java` files mapping to ~15–20 logical
> clusters) into one of two dispositions, using **PSI find-usages
> via `mcp-steroid` and the `mcp-steroid://ide/safe-delete` recipe**
> against the production class/method/package the cluster pins —
> grep is not acceptable for this classification because a missed
> external consumer (especially in abstract base classes or SPIs)
> would corrupt the deletion claim.
>
> **Deletion-in-Track-22 (cluster meets ALL of these):**
> - PSI find-usages reports zero production callers (across this
>   repo).
> - The class/method is not part of `com.jetbrains.youtrackdb.api`
>   (the public-API surface) — no `internal/api` package boundary
>   crossed.
> - The class is not an abstract base class designed for
>   subclassing, an SPI service interface (`META-INF/services`
>   registered), or an exception type that may be caught by
>   external code.
> - Deletion does not require coordinated changes in the `server`,
>   `tests`, or `embedded` modules beyond the `core` test source.
>
> **Issue-only-defer (cluster fails ANY of the above):**
> - YTDB issue created with full deletion plan and consumer-search
>   notes.
> - WHEN-FIXED marker rewritten from `Track 22` to `YTDB-NNNN`.
> - Production source untouched in Track 22; deletion happens in a
>   dedicated follow-up PR with wider review.
>
> **Initial cluster-disposition guidance** (subject to PSI re-confirmation in Phase A):
>
> *Strong candidates for in-track deletion (low SPI risk):*
> `sbtree/singlevalue/v1`; `sbtree/local/v1`; `DecimalKeyNormalizer`
> dead helpers; Binary Token / JWT cluster (already inert
> historically); Kerberos credential / Krb5 login module dead code;
> SQL `*DeadCodeTest` clusters where the test-source comment
> explicitly says "0 production references" (verify); narrow
> singletons like `IndexConfigPropertyDeadCodeTest`,
> `MockSerializerDeadCodeTest`, `RecordBytesTestOnlyOverloadTest`,
> `CronExpressionDeadCodeTest`, `IndexCursorClusterDeadCodeTest`,
> `EntityLinkSetImplTest` (partial dead methods only).
>
> *Defer to follow-up PR (likely SPI / external-consumer risk):*
> Hooks cluster (`RecordHookAbstract`, `EntityHookAbstract`,
> `LiveQueryBatchResultListener`, `LiveQueryHookStaticApi`,
> `HookReplacedRecordThreadLocal`,
> `DatabaseLifecycleListenerAbstract`); database-pool cluster
> (`DatabasePoolAbstract`, `DatabasePoolBase`); database-tool
> cluster (`DatabaseRepair`, `DatabaseCompare`, `BonsaiTreeRepair`,
> `GraphRepair`, `CheckIndexTool`); command-script SPI cluster
> (`CommandScript`, `CommandManager.getExecutor`,
> `ScriptExecutorRegister`); serializer-base cluster
> (`RecordSerializerCsvAbstract`, `RecordSerializerStringAbstract`).
>
> **No-issue convention for in-track deletions.** When a cluster is
> deleted in this branch (production class deletion + test pin
> deletion in the same commit), no YTDB issue is created — the
> deletion itself is the resolution. Avoid issue churn for resolved-
> in-this-PR deletions.
>
> **Coverage-gate recompute.** After each in-track deletion lockstep
> commit, re-run `coverage-analyzer.py` to refresh per-package
> baselines — deleted dead lines drop out of the denominator and
> displayed coverage may rise substantially without any new test
> work. Track 22's verification step must reconcile pre- and post-
> deletion baselines.
>
> **Step decomposition implication.** Phase A is expected to produce
> at least three categories of steps: (1) main-package coverage
> sweep (`tx`, `gremlin`, `engine`, `exception`, `compression`,
> `config`, `id`, `cache`, smaller packages); (2) per-cluster
> deletion lockstep commits (each in-track-deletion cluster gets a
> self-contained step with PSI safe-delete + atomic deletion + test
> re-run); (3) WHEN-FIXED issue creation + marker rewrite for
> deferred clusters and non-dead-code production-bug pins. The
> step-count guidance moves from the original "~6 + ~3–4" to
> potentially "~6 main + ~8–10 deletion steps + ~1–2 marker-rewrite
> steps" — Phase A may choose to inline-pack deletion clusters into
> the related coverage step rather than separate them.

## Progress

- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed

## Steps

(populated during Phase A decomposition — sub-step 4-5)
