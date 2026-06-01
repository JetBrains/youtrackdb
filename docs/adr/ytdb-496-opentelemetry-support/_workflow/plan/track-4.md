<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# Track 4: SQL execution layer InstrumentedSqlResultSet wrapper, sanitizer, and syntax classifier wire

## Purpose / Big Picture

After this track lands, every native SQL statement (`db.command(...)`, `db.execute(...)`, AND read-only `db.query(...)` — including MATCH, INSERT, UPDATE, DELETE, DDL) fires `QueryMetricsListener.queryFinished(...)` through a new `InstrumentedSqlResultSet` session-boundary wrapper. The wrapper lives in `internal.core.db` next to `DatabaseSessionEmbedded` and `LocalResultSetLifecycleDecorator`. The three SQL execution entry points on `DatabaseSessionEmbedded` (`query(String, Object...)` line 617, `query(String, boolean, Map)` line 652, `executeInternal()` line 702) each end with `return InstrumentedSqlResultSet.wrapIfListening(lifecycled, statement, rawSql, args, currentTx)` after the existing `queryStartedLifecycle(original)` call. The wrapper captures the start clock and `Context.current()` at construction, counts emitted rows during iteration, captures any exception thrown during iteration, and fires `queryFinished` on every listener at `close()`. Sub-plans nested inside (MATCH steps, sub-query steps, IF / WHILE flow control, script line steps) construct their own un-instrumented inner `LocalResultSet` instances via the existing 2-arg constructor and never reach the entry-point wrapper, so they do not double-fire. The SQL sanitizer (`core.SqlSanitizer`, added by this track) and the SQL classifier (`core.SqlSyntaxClassifier`, owned by Track 1) both live in `core` because both walk the parser-output AST that lives in `core`; the wrapper's lazy `QueryDetails` accessors call them on first read.

**Coordination with YTDB-820.** The forthcoming transaction-scoped query result cache ([PR #1077](https://github.com/JetBrains/youtrackdb/pull/1077)) lands first. YTDB-820 hooks into the same three SQL entry points and, depending on cache state, returns either the existing `LocalResultSet` or a new `CachedResultSetView`. The wrapper sits one layer above whichever inner the cache decision yielded, so a single fire site covers every state: hits, misses for cacheable RECORD / MATCH / AGGREGATE shapes, splice-failure fallback, non-deterministic bypass, the per-query `NOCACHE` hint, nested calls under WHERE evaluation, and the disabled-cache default in v1. `LocalResultSet.java` itself stays unchanged in Track 4 scope — the wrapper is the only new file on the result-set side — so YTDB-820's stream-slot substitution machinery (which substitutes `IdempotentExecutionStream` into the constructed `LocalResultSet`'s stream slot) remains untouched. See `design.md` §"SQL execution layer hook → Coordination with YTDB-820 cache contract" for the full inner-type matrix.

Under Gremlin Path B fallback (`YTDBGraphQuery.execute` → `transaction.query(...)` → entry-point wrapper), the SQL span emitted by `InstrumentedSqlResultSet.close()` attaches as a child of the active Gremlin span. The wrapper's constructor captures `Context.current()` into a `capturedContext` field; `close()` wraps the listener-fire loop in `try (Scope ignored = capturedContext.makeCurrent()) { ... }` so each listener's `Context.current()` read returns the construction-time parent (the Gremlin span the OTel module made-current via the Track 3 lifecycle hook on `YTDBQueryMetricsStep`). No thread-local suppression machinery is needed (D20). Path A (translated Gremlin, post-PR-1038) does not reach the wrapper because `YTDBMatchPlanStep` calls `SelectExecutionPlan.start()` directly without going through any SQL entry point.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Add `InstrumentedSqlResultSet` as a new `ResultSet` implementation in `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/InstrumentedSqlResultSet.java`. Wire the three SQL execution entry points on `DatabaseSessionEmbedded` to call `InstrumentedSqlResultSet.wrapIfListening(...)` after their existing `queryStartedLifecycle(original)` call. Implement `SqlSanitizer` as an AST-walk static utility in `core` (alongside Track 1's `SqlSyntaxClassifier`). The SQL classifier and sanitizer are both called by the wrapper's lazy `QueryDetails` accessors. Tag source for SQL is `Optional.empty()` (Gremlin-only tag source per D15); the resolver short-circuits to the per-TX default. `LocalResultSet.java` is NOT modified by this track.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

## Context and Orientation

The track has two halves: the new core-side `InstrumentedSqlResultSet` wrapper (`DatabaseSessionEmbedded` entry-point wiring + the new wrapper class file) and the core-side sanitizer.

Core-side wrapper class: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/InstrumentedSqlResultSet.java` (new file). It implements `ResultSet`, holds a single `inner: ResultSet` reference plus snapshot state (start clock, listener iterable, classifier inputs, captured OTel `Context`), and delegates `hasNext()` / `next()` to `inner` while incrementing a row counter and capturing any thrown exception. `wrapIfListening(...)` is the static factory the three entry points call: when `currentTx.iterateAllQueryListeners()` is empty it returns `inner` directly (zero-overhead fast path); otherwise it allocates the wrapper, captures `Context.current()` into `capturedContext`, and stores the snapshot. `close()` computes elapsed nanos, builds `QueryDetails`, wraps the listener-fire loop in `try (Scope ignored = capturedContext.makeCurrent())`, fires `queryFinished` on each listener inside the multi-catch wrapper, then closes the inner result-set.

`DatabaseSessionEmbedded` has three SQL execution entry points today — `query()` at lines 617-686 (backs `db.query(...)` and the Gremlin bridge), the Map-args overload at line 652, and `executeInternal()` at lines 702-751 (backs `db.command(...)` / `db.execute(...)`). All three follow the same shape: parse via `SQLEngine.parse(...)`, build the execution plan via `statement.execute(this, args, true)`, route the result through `queryStartedLifecycle(original)` for `activeQueries` tracking, return. Track 4 adds one trailing call per entry point to wrap the lifecycled result:

```
db.command("...") / db.execute("...")
  → FrontendTransactionImpl.execute()   (lines 1765-1776)
  → DatabaseSessionEmbedded.executeInternal()   (line 702)
  → SQLEngine.parse(...)
  → original = statement.execute(...)
  → lifecycled = queryStartedLifecycle(original)
  → return InstrumentedSqlResultSet.wrapIfListening(lifecycled, statement, rawSql, args, currentTx)   ← NEW

db.query("...")
  → FrontendTransactionImpl.query()   (lines 1751-1762)
  → DatabaseSessionEmbedded.query()   (line 617)
  → SQLEngine.parse(...)
  → isIdempotent() check
  → original = statement.execute(...)
  → lifecycled = queryStartedLifecycle(original)
  → return InstrumentedSqlResultSet.wrapIfListening(lifecycled, statement, rawSql, args, currentTx)   ← NEW

g.V().hasLabel("X").toList()   (Gremlin Path B fallback)
  → YTDBGraphStep
  → YTDBGraphQuery.execute(session)
  → transaction.query(...)
  → DatabaseSessionEmbedded.query()
  → returns InstrumentedSqlResultSet(inner=LocalResultSet, capturedContext=Gremlin span via Track 3 hook)
  → wrapper.close() emits SQL span as child of Gremlin span

db.query under YTDB-820 cache hit (post-PR-1077)
  → DatabaseSessionEmbedded.query()
  → SQLEngine.parse(...)
  → cache.lookup → CachedResultSetView
  → lifecycled = queryStartedLifecycle(CachedResultSetView)
  → returns InstrumentedSqlResultSet(inner=CachedResultSetView, ...)
  → wrapper.next() delegates to view.next(), increments row counter
  → wrapper.close() fires listener with row count from the cache
```

Available state at `InstrumentedSqlResultSet.wrapIfListening(...)` invocation:
- `inner` (ResultSet): the lifecycled result-set returned by `queryStartedLifecycle`. Concrete type is `LocalResultSet` today; under YTDB-820 it may be `CachedResultSetView` (cache hit / cache miss for cacheable shapes) or `LocalResultSet` (splice failure, non-det bypass, `NOCACHE`, re-entrant, cache-disabled). The wrapper inspects nothing about the concrete type.
- `statement` (SQLStatement): the parsed AST. Stored for lazy classification + sanitization.
- `rawSql` (String): raw SQL text — `stringStatement` from `executeInternal`'s caller, or the original SQL string from `query()`. Null fallback: `statement.getOriginalStatement()`.
- `args` (Object): positional `Object[]` or named `Map<Object, Object>` parameters.
- `currentTx` (FrontendTransaction): the active transaction. The existing `FrontendTransaction.getId(): long` accessor returns a stable internal ID; the wrapper uses `String.valueOf(currentTx.getId())` as the tracking ID — no new accessor is added (CR16 resolution).
- Listener snapshot from `currentTx.iterateAllQueryListeners()` (Track 1 accessor) — stored at constructor time so listener iteration order is fixed.

Core-side sanitizer (lives in `core` because it walks the parser-output AST):
- `SqlSanitizer`: static utility `sanitize(SQLStatement) → String` in `core/.../profiler/monitoring/` next to `SqlSyntaxClassifier`. Walks the parsed AST and emits a rendering with every literal node (`SQLBaseExpression`, `SQLInputParameter`, `SQLNumber`, `SQLBoolean`, `SQLString`, date / timestamp literal nodes) replaced by `?`; structural nodes (FROM targets, WHERE operators, GROUP BY / ORDER BY identifiers) render verbatim. Already-parameterized text (`SQLInputParameter` nodes carrying `:name` / `?` placeholders) renders as `?` idempotently. Handles edge cases regex-based sanitizers leak: doubled single quotes (`'It''s'`), JSON strings inside SELECT projections, regex predicates in `MATCHES` clauses — the parser already classified those as `SQLString` nodes, so the walk renders them as `?` without re-parsing quoting rules.

Core-side classifier (delivered by Track 1, consumed here):
- `core.SqlSyntaxClassifier.classify(SQLStatement)`: static utility that dispatches on the AST subclass (`SQLSelectStatement` → SELECT with first FROM target, `SQLInsertStatement` → INSERT, `SQLUpdateStatement` → UPDATE, `SQLDeleteStatement` → DELETE, `SQLMatchStatement` → MATCH, `SQLCreateClassStatement` / `SQLAlterClassStatement` / `SQLDropClassStatement` → CREATE / ALTER / DROP); returns `Classification.EMPTY` for shapes that don't fit.

Concrete deliverables:

1. New file `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/InstrumentedSqlResultSet.java` implementing `ResultSet`. Fields: `ResultSet inner`, `SQLStatement statement`, `String rawSql`, `Object args`, `FrontendTransaction currentTx`, `Iterable<QueryMetricsListener> listeners`, `QueryMonitoringMode mode`, `io.opentelemetry.context.Context capturedContext`, `long startMillis`, `long startEpochNanos`, `long startNanoTime`, `long rowCount = 0`, `Throwable caughtError = null`. Static factory `public static ResultSet wrapIfListening(ResultSet inner, SQLStatement statement, String rawSql, Object args, FrontendTransaction currentTx)`. The factory logic: read `listeners = currentTx.iterateAllQueryListeners()`; if listeners empty, return `inner` directly (zero-overhead fast path). Otherwise resolve `mode = currentTx.resolveQueryMonitoringMode(Optional.empty())` (SQL has no tag source; resolver short-circuits to per-TX default per D15), capture `startMillis` / `startEpochNanos` / `startNanoTime` under the chosen mode (`LIGHTWEIGHT` reads from `YouTrackDBEnginesManager.instance().getTicker()`; `EXACT` reads `Instant.now()` + `System.nanoTime()`), capture `capturedContext = io.opentelemetry.context.Context.current()`, allocate the wrapper, and return it.

2. `InstrumentedSqlResultSet.hasNext()` / `next()` iteration: delegate to `inner.hasNext()` / `inner.next()`; on success of `next()` returning non-null, increment `rowCount`; if the inner throws, set `caughtError = e` and rethrow. Single counter increment is the only added cost on the hot iteration path.

3. `InstrumentedSqlResultSet.close()` body. Compute `elapsedNanos = (mode == LIGHTWEIGHT) ? ticker.approximateNanoTime() - startNanoTime : System.nanoTime() - startNanoTime`. Build the `QueryDetails` (lazy `getQuery()` calls `SqlSanitizer.sanitize(statement)`; lazy `getOperationName()` / `getCollectionName()` call `core.SqlSyntaxClassifier.classify(statement)`; `getDatabaseName()` reads from the session; `getTransactionTrackingId()` returns `String.valueOf(currentTx.getId())`; `getErrorType()` returns `Optional.ofNullable(caughtError).map(t -> t.getClass().getName())`; `getResultCount()` returns `OptionalLong.of(rowCount)`; `getStartedAtEpochNanos()` returns the stored value). Wrap the listener-fire loop in `try (Scope ignored = capturedContext.makeCurrent())` so each listener's `Context.current()` read returns the construction-time parent (defense-in-depth against future async refactors). Iterate the listener snapshot and call `listener.queryFinished(details, startMillis, elapsedNanos)` on each, wrapped in try/catch `(Exception | LinkageError | AssertionError t)` so a listener throw does not propagate. Close the inner result-set.

4. Wire the three SQL execution entry points on `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` to call the wrapper factory: `query(String, Object...)` (line 617), `query(String, boolean, Map)` (line 652), `executeInternal()` (line 702). Each entry point already parses via `SQLEngine.parse(...)` before delegating, and already routes the result through `queryStartedLifecycle(original)`. The change is to replace `return queryStartedLifecycle(original);` with the two-step `var lifecycled = queryStartedLifecycle(original); return InstrumentedSqlResultSet.wrapIfListening(lifecycled, statement, rawSql, args, currentTx);`. The raw SQL text comes from `stringStatement` when non-null, else from `statement.getOriginalStatement()`.

5. `SqlSanitizer` static utility in `core/.../profiler/monitoring/` (alongside `SqlSyntaxClassifier`). AST-walk implementation: `sanitize(SQLStatement)` returns the sanitized rendering. Lives in `core` because the walk needs the parser-output AST.

The `Classification` record and `SqlSyntaxClassifier` both land in Track 1 — Track 4 consumes them via the wrapper's lazy `QueryDetails` accessors. No new `FrontendTransaction.getTrackingId()` accessor is needed (CR16 resolution). Gremlin's tag mechanism (`g.with(YTDBQueryConfigParam.querySummary, "X")`) is pre-existing and consumed unchanged by Track 1's `YTDBQueryMetricsStep` mode-read; SQL has no tag source (M1). `LocalResultSet.java` is NOT modified by this track — the wrapper composes over it via the existing public `ResultSet` interface, keeping YTDB-820's stream-slot substitution machinery untouched.

Deferred coordination: when YTDB-820's eventual implementation lands a `getCacheOutcome()` accessor on `CachedResultSetView` (returning `hit | miss | bypass`), the wrapper will stamp `youtrackdb.query.cache.outcome` on the SQL span by type-checking `inner`. Until then, the attribute is omitted; Track 4 ships with no hard dependency on the YTDB-820 API surface.

## Plan of Work

Five edits. The `Classification` record and `SqlSyntaxClassifier` both come from Track 1, so Track 4 ships the new `InstrumentedSqlResultSet` wrapper class, the SQL entry-point wiring, and the sanitizer (which calls Track 1's classifier indirectly via the lazy `QueryDetails` accessors).

The first edit creates the new file `InstrumentedSqlResultSet.java` with the field declarations and the static `wrapIfListening(...)` factory. The factory reads the listener snapshot from `currentTx.iterateAllQueryListeners()`. When empty, the factory returns `inner` directly (no clock read, no listener iteration, no `QueryDetails` allocation; zero-overhead fast path). Otherwise the factory resolves the timing mode via `currentTx.resolveQueryMonitoringMode(Optional.empty())` (Track 1's resolver short-circuits on empty tag and returns the per-TX default), captures the start clock pair under the resolved mode, captures `io.opentelemetry.context.Context.current()` into `capturedContext`, and allocates the wrapper. The wrapper stores `inner`, `statement`, `rawSql`, `args`, `currentTx`, `listeners`, `mode`, `capturedContext`, the three start-clock values, `rowCount = 0`, and `caughtError = null`.

The second edit implements the iteration path. `hasNext()` / `next()` delegate to `inner.hasNext()` / `inner.next()`. On `next()` returning a non-null `Result`, increment `rowCount`. If the inner throws during `hasNext` / `next`, `caughtError = e` and the exception rethrows so the caller sees the original failure. Single counter increment is the only added overhead on the hot iteration path.

The third edit implements `close()`. Compute `elapsedNanos`, build the `QueryDetails`, open a `try (Scope ignored = capturedContext.makeCurrent())` block, iterate every listener in the snapshot wrapped in try/catch `(Exception | LinkageError | AssertionError t)`, and close the inner result-set. The `QueryDetails` is lazy: sanitization runs on first `getQuery()` call, classification on first `getOperationName()` / `getCollectionName()` call, so listeners that ignore those accessors pay no walk cost. `getResultCount()` returns `OptionalLong.of(rowCount)` eagerly (the counter is already incremented per `next()`; no recomputation).

The fourth edit wires the three SQL execution entry points on `DatabaseSessionEmbedded` (`query(String, Object...)` line 617, `query(String, boolean, Map)` line 652, `executeInternal()` line 702) to call the wrapper factory after `queryStartedLifecycle(original)`. Each entry point already has the parsed `SQLStatement` in scope after the `SQLEngine.parse(...)` call. The change is mechanical — replace `return queryStartedLifecycle(original);` with the two-step `var lifecycled = queryStartedLifecycle(original); return InstrumentedSqlResultSet.wrapIfListening(lifecycled, statement, rawSql, args, currentTx);`.

The fifth edit adds `SqlSanitizer` as a static utility in `core/.../profiler/monitoring/` alongside `SqlSyntaxClassifier`. The `sanitize(SQLStatement)` method walks the parsed AST and renders each literal node as `?`: `SQLBaseExpression`, `SQLInputParameter`, `SQLNumber`, `SQLBoolean`, `SQLString`, and the date / timestamp literal node types the parser produces. Structural nodes (FROM targets, WHERE operators, GROUP BY / ORDER BY identifiers, function calls) render verbatim. Already-parameterized text (`SQLInputParameter` nodes carrying `:name` / `?` placeholders in the input) renders as `?` idempotently. The AST walk correctly handles doubled single quotes (`'It''s'`), JSON strings inside SELECT projections, and regex predicates in `MATCHES` clauses — all classified by the parser as `SQLString` nodes. Unit-tested independently in this track (full coverage of statement-type variations runs in Track 6a's `OTelSqlQueryTest`).

The sixth edit adds JUnit unit tests covering: `wrapIfListening` fast-path when listeners empty (returns `inner` unchanged, no allocation); `wrapIfListening` instrumented path (allocates wrapper, captures clocks + context); iteration delegates and increments `rowCount` + captures `caughtError`; `close()` fires every listener with correct `elapsedNanos` and correct `rowCount`; `close()` listener exception isolation (one listener throwing does not prevent others from firing or block the close); `close()` honors `capturedContext` (a listener that reads `Context.current()` sees the captured context, not whatever was current at close-time); `SqlSanitizer` literal-replacement edge cases (doubled single quotes, JSON literals, MATCHES regex predicates); all three entry points on `DatabaseSessionEmbedded` invoking the wrapper. YTDB-820 inner-type coverage tests (wrapper composes over `LocalResultSet` and over a mock `CachedResultSetView`-shaped inner) — the latter is mocked because PR #1077 design-only lands first; the wrapper does not inspect the concrete type, so the mock just implements `ResultSet`. The SQL classifier's per-statement-type unit tests land in Track 1 alongside the classifier itself; full end-to-end OTel-SDK assertions land in Tracks 6a / 6b / 6c.

Ordering: edit 1 (wrapper class file with fields + factory — no dependencies on other Track 4 edits) → edit 2 (iteration body — depends on edit 1 fields) → edit 3 (`close()` — depends on edit 1 fields) → edit 4 (entry-point wiring — depends on the factory from edit 1) → edit 5 (sanitizer, independent of edits 1-4 but called by `close()`'s lazy `QueryDetails`) → edit 6 (tests cover all five preceding edits).

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 4:

- A `db.command("SELECT FROM User WHERE name = 'Alice'")` call constructs an `InstrumentedSqlResultSet`; `close()` fires the registered `QueryMetricsListener` with a `QueryDetails` whose `getQuery()` returns `"SELECT FROM User WHERE name = ?"`, `getOperationName()` returns `Optional.of("SELECT")`, `getCollectionName()` returns `Optional.of("User")`, `getResultCount()` returns the actual row count the consumer pulled.
- A `db.query("SELECT FROM User WHERE name = ?", "Alice")` call fires the listener with the same `QueryDetails` shape as the equivalent `db.command(...)` — both entry points go through the same wrapper factory, so coverage is identical (regression check for the pre-mutation gap where `query()` was uncovered).
- A `db.command("INSERT INTO Order SET amount = 100, customer = 'X'")` call yields operation `INSERT`, collection `Order`, sanitized `"INSERT INTO Order SET amount = ?, customer = ?"`.
- A `db.command("MATCH {class:User, as:u}-knows->{class:User, as:f} RETURN u, f")` call yields operation `MATCH`, collection `User` (the first pattern node).
- A `db.command("CREATE INDEX User.email UNIQUE")` call yields operation `CREATE`, collection `User`, sanitized text unchanged (DDL contains no literals).
- A parameterized `db.command("SELECT FROM User WHERE id = ?", 42)` call yields sanitized text `"SELECT FROM User WHERE id = ?"` (unchanged, already parameterized) and the listener sees the parameters separately.
- A multi-statement script (`db.executeSqlScript("INSERT INTO X ...; INSERT INTO Y ...; ")` or the equivalent) emits exactly one SQL span (the outer `InstrumentedSqlResultSet` wraps the script's outer result-set); inner statements via `ScriptLineStep.start()` do not double-fire because they do not go through the SQL entry points.
- A MATCH query that spawns sub-plans via hash-join or correlated-optional steps emits exactly one SQL span (the outer `InstrumentedSqlResultSet`); the sub-plans construct their own un-instrumented `LocalResultSet` instances via the existing 2-arg constructor and never reach the wrapper.
- A Gremlin Path A traversal (translated by PR #1038's `GremlinToMatchStrategy`) emits exactly one Gremlin span (Track 3 owner); `InstrumentedSqlResultSet` is never constructed because `YTDBMatchPlanStep` calls `SelectExecutionPlan.start()` directly without going through any SQL entry point.
- A Gremlin Path B fallback traversal `g.V().out("knows").out("knows").toList()` (when the translator declines) emits one Gremlin parent span + one SQL child span; the child's `parentSpanId` matches the Gremlin span's `spanId` because the wrapper's `capturedContext` (read at construction inside the Gremlin step's makeCurrent scope) is restored around the listener fire via `Scope.makeCurrent()` (Track 3 owns the Gremlin span lifecycle hook).
- An exception thrown during inner iteration propagates to the caller as before; `InstrumentedSqlResultSet.close()` captures the exception class on its way out and surfaces it through `QueryDetails.getErrorType()`; the span carries status ERROR and `error.type` populated.
- A listener exception during SQL hook firing does not propagate to the caller; the SQL statement completes normally.
- A SQL statement run inside a YTDBTransaction emits a span whose parent is determined by `Context.current()` at wrapper construction time — under Gremlin Path B that is the active Gremlin span; under direct SQL with a host-wrapping span it is the host span; otherwise the trace root.
- The OTel module remains opt-in: with `OPENTELEMETRY_ENABLED=false`, no SQL classifier or sanitizer code runs (the `wrapIfListening` factory returns `inner` directly when no listeners are registered).
- YTDB-820 inner-type coverage: the wrapper handles both `LocalResultSet` (cache disabled / non-deterministic / `NOCACHE` / re-entrant / splice-failure paths) and `CachedResultSetView` (cache hit / cache miss for cacheable shapes) uniformly. The row counter, exception capture, and listener fire site are identical regardless of inner type. `LocalResultSet.java` is NOT modified by this track.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

## Interfaces and Dependencies

In scope:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/InstrumentedSqlResultSet.java` (new file — `ResultSet` wrapper with `wrapIfListening` factory, iteration delegation + row counter, `close()` listener fire).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (entry-point wiring — three call sites at lines 617, 652, 702 each call `InstrumentedSqlResultSet.wrapIfListening(lifecycled, statement, rawSql, args, currentTx)` after the existing `queryStartedLifecycle(original)` call).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/SqlSanitizer.java` (AST-walk static utility alongside `SqlSyntaxClassifier`).

Out of scope:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/LocalResultSet.java` — NOT modified. The wrapper composes over `LocalResultSet` via the public `ResultSet` interface; the existing 2-arg constructor stays in use by sub-plan callers and by YTDB-820's stream-slot substitution mechanism. Track 4 ships zero edits to this file.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransaction.java` — no new `getTrackingId()` accessor; the SQL fire site uses the existing `getId(): long` (CR16 resolution).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBTransaction.java` — not touched.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphQuery.java` — not touched. Path B fallback works because the Gremlin span lifecycle hook (Track 3 owner) makes the Gremlin span `Context.current()` during inner `transaction.query(...)` execution; the wrapper's `capturedContext` field captures that value at construction and `close()` restores it via `Scope.makeCurrent()` before firing listeners.
- `core/.../SqlSyntaxClassifier.java`, `core/.../GremlinBytecodeClassifier.java`, `core/.../Classification.java` — all land in Track 1.
- OTel module's Gremlin span lifecycle hook on `YTDBQueryMetricsStep` — Track 3 owner.
- Configuration parameters and lifecycle wiring (Track 5).
- End-to-end integration tests with the OTel SDK (Tracks 6a / 6b / 6c).
- Native SQL DDL semantics beyond extracting the target class name. The classifier does not validate DDL correctness.
- SQL parser grammar — no changes. SQL has no tag source (M1; Gremlin-only tag source via `g.with(...)`). The resolver short-circuits on `Optional.empty()`.
- `youtrackdb.query.cache.outcome` span attribute — deferred until YTDB-820's implementation adds a `getCacheOutcome()` accessor on `CachedResultSetView`. Track 4 ships with no hard dependency on the YTDB-820 API surface.

Inter-track dependencies:
- Depends on Track 1 (TX SPI extensions, global listener registry, both classifier helpers + `Classification` record, exception-wrapper widening to `Exception | LinkageError | AssertionError`, listener snapshot fields on `FrontendTransactionImpl`, the new `FrontendTransaction.getDefaultQueryMonitoringMode()` + `resolveQueryMonitoringMode(Optional<String>)` accessors, the new `QueryMonitoringModeResolver` + `TagRule<T>` sealed interface, and the `QueryDetails.getResultCount(): OptionalLong` default accessor signature).
- Depends on Track 2 (the `youtrackdb-opentelemetry` Maven module exists; the SQL fire path invokes the OTel listener via the global registry snapshot — `SqlSanitizer` itself lives in `core` next to `SqlSyntaxClassifier` because both walk the parser-output AST).
- Depends on Track 3 (`OTelQueryMetricsListener` exists; the SQL hook feeds into the same listener instance via the global registry snapshot. Track 3 also owns the OTel module's Gremlin span lifecycle hook on `YTDBQueryMetricsStep` that makes Path B parent-child span hierarchy work via `Context.current()`).
- Coordinates with YTDB-820 ([PR #1077](https://github.com/JetBrains/youtrackdb/pull/1077)): YTDB-820 lands first; the wrapper composes over both `LocalResultSet` and `CachedResultSetView` without inspecting the concrete inner type. The wrapper does not depend on any YTDB-820-introduced API surface in v1; the deferred `cache.outcome` attribute is a follow-up.
- Provides for Track 6a (`OTelSqlQueryTest` covers each statement type and the sanitizer edge cases) and Track 6c (`OTelDbQuerySpanTest` covers the `db.query(...)` regression path; `OTelPathSpanShapeTest` covers Path A vs Path B span counts).

Library / function signatures introduced:

```java
// core/.../profiler/monitoring/SqlSanitizer.java (new — AST-walk over parser output)
public final class SqlSanitizer {
  public static String sanitize(SQLStatement statement);
}

// core/.../db/InstrumentedSqlResultSet.java (new — session-boundary listener wrapper)
public final class InstrumentedSqlResultSet implements ResultSet {
  // Static factory used by the three DatabaseSessionEmbedded SQL entry points.
  // Returns the inner result-set unchanged when the listener snapshot is empty
  // (zero-overhead fast path); otherwise allocates the wrapper.
  public static ResultSet wrapIfListening(
      ResultSet inner,
      SQLStatement statement,
      String rawSql,
      Object args,
      FrontendTransaction currentTx);

  // Iteration delegates to inner; close() fires queryFinished on each listener
  // in the snapshot, restoring capturedContext via Scope.makeCurrent() around
  // the fire so the listener's Context.current() read sees the construction-time
  // parent span.
  @Override public boolean hasNext();
  @Override public Result next();
  @Override public void close();
}

// SqlSyntaxClassifier lives in core/.../profiler/monitoring/ (Track 1).
// Track 4 calls it from the wrapper's lazy QueryDetails accessor on first
// read of getOperationName() / getCollectionName(); no new public surface here.

// LocalResultSet.java is NOT modified by this track. The wrapper composes over
// it via the public ResultSet interface; the existing 2-arg constructor stays
// in use by sub-plan callers and by YTDB-820's stream-slot substitution.
```
