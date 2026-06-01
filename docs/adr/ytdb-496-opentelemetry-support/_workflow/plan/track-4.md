<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# Track 4: SQL execution layer LocalResultSet hook, sanitizer, and syntax classifier wire

## Purpose / Big Picture

After this track lands, every native SQL statement (`db.command(...)`, `db.execute(...)`, AND read-only `db.query(...)` — including MATCH, INSERT, UPDATE, DELETE, DDL) fires `QueryMetricsListener.queryFinished(...)` through the `LocalResultSet` outer-boundary fire site. `LocalResultSet` is the result-set wrapper every SQL execution entry point on `DatabaseSessionEmbedded` returns; its constructor captures the start clock when `executionPlan.start()` opens (line 45 of `LocalResultSet.java`), and `close()` computes elapsed time and fires the listener. Multi-statement scripts (`ScriptExecutionPlan` wrapped in one outer `LocalResultSet`) emit one span per script call. Sub-plans nested inside (MATCH steps, sub-query steps, IF / WHILE flow control, script line steps) call `ExecutionPlan.start()` directly without wrapping in `LocalResultSet`, so they do not double-fire. The SQL sanitizer (`core.SqlSanitizer`, added by this track) and the SQL classifier (`core.SqlSyntaxClassifier`, owned by Track 1) both live in `core` because both walk the parser-output AST that lives in `core`; `LocalResultSet`'s lazy `QueryDetails` accessors call them on first read.

Under Gremlin Path B fallback (`YTDBGraphQuery.execute` → `transaction.query(...)` → `LocalResultSet`), the SQL span emitted by `LocalResultSet.close()` attaches as a child of the active Gremlin span via OTel `Context.current()` propagation — managed by the OTel module's Gremlin span lifecycle hook on `YTDBQueryMetricsStep` (Track 3 owner). No thread-local suppression machinery is needed (D20). Path A (translated Gremlin, post-PR-1038) does not reach `LocalResultSet` because `YTDBMatchPlanStep` calls `SelectExecutionPlan.start()` directly without wrapping.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Instrument `LocalResultSet`'s constructor and `close()` to capture the start clock, store the listener snapshot, build `QueryDetails`, and fire `queryFinished` on every registered listener. Wire the three SQL execution entry points (`query(String, Object...)` line 617, `query(String, boolean, Map)` line 652, `executeInternal()` line 702) on `DatabaseSessionEmbedded` to pass the parsed `SQLStatement` plus the raw SQL text plus the listener snapshot from `currentTx.iterateAllQueryListeners()` to the result-set constructor. Implement `SqlSanitizer` as an AST-walk static utility in `core` (alongside Track 1's `SqlSyntaxClassifier`). The SQL classifier and sanitizer are both called by `LocalResultSet`'s lazy `QueryDetails` accessors. Tag source for SQL is `Optional.empty()` (Gremlin-only tag source per D15); the resolver short-circuits to the per-TX default.

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

The track has two halves: the core-side `LocalResultSet` instrumentation (`DatabaseSessionEmbedded` entry-point wiring + the result-set constructor + `close()` changes) and the core-side sanitizer.

Core-side `LocalResultSet` instrumentation point: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/LocalResultSet.java`. Today the constructor takes the execution plan and calls `stream = executionPlan.start()` at line 45. Track 4 extends the constructor signature to also take the parsed `SQLStatement`, the raw SQL text, the args, and the listener snapshot. When listeners are present, the constructor captures the start clock under the resolved mode, then calls `executionPlan.start()`. `close()` computes elapsed time, builds the `QueryDetails`, fires every listener inside the multi-catch wrapper, and closes the underlying stream.

`DatabaseSessionEmbedded` has three SQL execution entry points today — `query()` at lines 617-686 (backs `db.query(...)` and the Gremlin bridge), the Map-args overload at line 652, and `executeInternal()` at lines 702-751 (backs `db.command(...)` / `db.execute(...)`). All three follow the same shape: parse via `SQLEngine.parse(...)`, build the execution plan via `statement.execute(this, args, true)`, wrap the resulting stream in a `LocalResultSet`. Track 4 changes each entry point to pass the parsed `SQLStatement` plus the raw SQL plus the listener snapshot to the new `LocalResultSet` constructor:

```
db.command("...") / db.execute("...")
  → FrontendTransactionImpl.execute()   (lines 1765-1776)
  → DatabaseSessionEmbedded.executeInternal()   (line 702)
  → SQLEngine.parse(...)
  → executionPlan = statement.execute(...)
  → new LocalResultSet(executionPlan, statement, rawSql, args, currentTx)   ← INSTRUMENTED

db.query("...")
  → FrontendTransactionImpl.query()   (lines 1751-1762)
  → DatabaseSessionEmbedded.query()   (line 617)
  → SQLEngine.parse(...)
  → isIdempotent() check
  → executionPlan = statement.execute(...)
  → new LocalResultSet(executionPlan, statement, rawSql, args, currentTx)   ← SAME CONSTRUCTOR

g.V().hasLabel("X").toList()   (Gremlin Path B fallback)
  → YTDBGraphStep
  → YTDBGraphQuery.execute(session)
  → transaction.query(...)
  → DatabaseSessionEmbedded.query()
  → new LocalResultSet(...)
  → Constructor reads Context.current() (Gremlin span via Track 3 lifecycle hook)
  → close() emits SQL span as child of Gremlin span
```

Available state at `LocalResultSet` constructor invocation:
- `executionPlan` (InternalExecutionPlan): the compiled plan whose `start()` returns the result stream.
- `statement` (SQLStatement): the parsed AST. Stored for lazy classification + sanitization.
- `rawSql` (String): raw SQL text — `stringStatement` from `executeInternal`'s caller, or the original SQL string from `query()`. Null fallback: `statement.getOriginalStatement()`.
- `args` (Object): positional `Object[]` or named `Map<Object, Object>` parameters.
- `currentTx` (FrontendTransaction): the active transaction. The existing `FrontendTransaction.getId(): long` accessor returns a stable internal ID; the result-set uses `String.valueOf(currentTx.getId())` as the tracking ID — no new accessor is added (CR16 resolution).
- Listener snapshot from `currentTx.iterateAllQueryListeners()` (Track 1 accessor) — stored at constructor time so listener iteration order is fixed.

Core-side sanitizer (lives in `core` because it walks the parser-output AST):
- `SqlSanitizer`: static utility `sanitize(SQLStatement) → String` in `core/.../profiler/monitoring/` next to `SqlSyntaxClassifier`. Walks the parsed AST and emits a rendering with every literal node (`SQLBaseExpression`, `SQLInputParameter`, `SQLNumber`, `SQLBoolean`, `SQLString`, date / timestamp literal nodes) replaced by `?`; structural nodes (FROM targets, WHERE operators, GROUP BY / ORDER BY identifiers) render verbatim. Already-parameterized text (`SQLInputParameter` nodes carrying `:name` / `?` placeholders) renders as `?` idempotently. Handles edge cases regex-based sanitizers leak: doubled single quotes (`'It''s'`), JSON strings inside SELECT projections, regex predicates in `MATCHES` clauses — the parser already classified those as `SQLString` nodes, so the walk renders them as `?` without re-parsing quoting rules.

Core-side classifier (delivered by Track 1, consumed here):
- `core.SqlSyntaxClassifier.classify(SQLStatement)`: static utility that dispatches on the AST subclass (`SQLSelectStatement` → SELECT with first FROM target, `SQLInsertStatement` → INSERT, `SQLUpdateStatement` → UPDATE, `SQLDeleteStatement` → DELETE, `SQLMatchStatement` → MATCH, `SQLCreateClassStatement` / `SQLAlterClassStatement` / `SQLDropClassStatement` → CREATE / ALTER / DROP); returns `Classification.EMPTY` for shapes that don't fit.

Concrete deliverables:

1. `LocalResultSet` field additions and constructor signature change in `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/LocalResultSet.java`. New fields: `SQLStatement statement`, `String rawSql`, `Object args`, `Iterable<QueryMetricsListener> listeners`, `QueryMonitoringMode mode`, `long startMillis`, `long startEpochNanos`, `long startNanoTime`, `Throwable caughtError = null`, `boolean instrumented`. New constructor signature `LocalResultSet(InternalExecutionPlan executionPlan, SQLStatement statement, String rawSql, Object args, FrontendTransaction currentTx)`. The constructor logic: read `listeners = currentTx.iterateAllQueryListeners()`; if listeners empty, set `instrumented = false`, call `stream = executionPlan.start()`, and return (un-instrumented fast path with zero overhead). Otherwise resolve `mode = currentTx.resolveQueryMonitoringMode(Optional.empty())` (SQL has no tag source; resolver short-circuits to per-TX default per D15), capture `startMillis` / `startEpochNanos` / `startNanoTime` under the chosen mode (`LIGHTWEIGHT` reads from `YouTrackDBEnginesManager.instance().getTicker()`; `EXACT` reads `Instant.now()` + `System.nanoTime()`), call `stream = executionPlan.start()`, set `instrumented = true`.

2. `LocalResultSet.hasNext()` / `next()` iteration changes. Delegate to the underlying stream as today. If the stream throws, set `caughtError = e` and rethrow. Zero overhead on the hot iteration path.

3. `LocalResultSet.close()` body. If `instrumented` is false, just close the underlying stream and return. Otherwise compute `elapsedNanos = (mode == LIGHTWEIGHT) ? ticker.approximateNanoTime() - startNanoTime : System.nanoTime() - startNanoTime`. Build the `QueryDetails` (lazy `getQuery()` calls `SqlSanitizer.sanitize(statement)`; lazy `getOperationName()` / `getCollectionName()` call `core.SqlSyntaxClassifier.classify(statement)`; `getDatabaseName()` reads from the session; `getTransactionTrackingId()` returns `String.valueOf(currentTx.getId())`; `getErrorType()` returns `Optional.ofNullable(caughtError).map(t -> t.getClass().getName())`; `getStartedAtEpochNanos()` returns the stored value). Iterate the listener snapshot and call `listener.queryFinished(details, startMillis, elapsedNanos)` on each, wrapped in try/catch `(Exception | LinkageError | AssertionError t)` so a listener throw does not propagate. Close the underlying stream.

4. Wire the three SQL execution entry points on `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` to construct the instrumented `LocalResultSet` with the parsed `SQLStatement` + raw SQL + args + `currentTx`: `query(String, Object...)` (line 617), `query(String, boolean, Map)` (line 652), `executeInternal()` (line 702). Each entry point already parses via `SQLEngine.parse(...)` before delegating, so the parsed `SQLStatement` is in scope; the raw SQL text comes from `stringStatement` when non-null, else from `statement.getOriginalStatement()`.

5. `SqlSanitizer` static utility in `core/.../profiler/monitoring/` (alongside `SqlSyntaxClassifier`). AST-walk implementation: `sanitize(SQLStatement)` returns the sanitized rendering. Lives in `core` because the walk needs the parser-output AST.

The `Classification` record and `SqlSyntaxClassifier` both land in Track 1 — Track 4 consumes them via `LocalResultSet`'s lazy `QueryDetails` accessors. No new `FrontendTransaction.getTrackingId()` accessor is needed (CR16 resolution). Gremlin's tag mechanism (`g.with(YTDBQueryConfigParam.querySummary, "X")`) is pre-existing and consumed unchanged by Track 1's `YTDBQueryMetricsStep` mode-read; SQL has no tag source (M1).

## Plan of Work

Five edits. The `Classification` record and `SqlSyntaxClassifier` both come from Track 1, so Track 4 ships the `LocalResultSet` instrumentation, the SQL entry-point wiring, and the sanitizer (which calls Track 1's classifier indirectly via the lazy `QueryDetails` accessors).

The first edit adds the new fields and the new constructor signature on `LocalResultSet`. The constructor reads the listener snapshot from `currentTx.iterateAllQueryListeners()`. When empty, the constructor takes the un-instrumented fast path (no clock read, no listener iteration, no `QueryDetails` allocation). Otherwise the constructor resolves the timing mode via `currentTx.resolveQueryMonitoringMode(Optional.empty())` (Track 1's resolver short-circuits on empty tag and returns the per-TX default), captures the start clock pair under the resolved mode, and calls `executionPlan.start()`. The constructor stores `statement`, `rawSql`, `args`, `listeners`, `mode`, the three start-clock values, and `caughtError = null`.

The second edit modifies the iteration path. `hasNext()` / `next()` delegate to the underlying stream. If the stream throws during `hasNext` / `next`, `caughtError = e` and the exception rethrows so the caller sees the original failure. The iteration path has zero added overhead when the constructor took the un-instrumented fast path.

The third edit modifies `close()`. When `instrumented` is false, just close the underlying stream. Otherwise compute `elapsedNanos`, build the `QueryDetails`, fire every listener in the snapshot wrapped in try/catch `(Exception | LinkageError | AssertionError t)`, and close the underlying stream. The `QueryDetails` is lazy: sanitization runs on first `getQuery()` call, classification on first `getOperationName()` / `getCollectionName()` call, so listeners that ignore those accessors pay no walk cost.

The fourth edit wires the three SQL execution entry points on `DatabaseSessionEmbedded` (`query(String, Object...)` line 617, `query(String, boolean, Map)` line 652, `executeInternal()` line 702) to construct the instrumented `LocalResultSet`. Each entry point already has the parsed `SQLStatement` in scope after the `SQLEngine.parse(...)` call; the only change is to pass the statement, raw SQL, and args (already available) plus `currentTx` to the constructor.

The fifth edit adds `SqlSanitizer` as a static utility in `core/.../profiler/monitoring/` alongside `SqlSyntaxClassifier`. The `sanitize(SQLStatement)` method walks the parsed AST and renders each literal node as `?`: `SQLBaseExpression`, `SQLInputParameter`, `SQLNumber`, `SQLBoolean`, `SQLString`, and the date / timestamp literal node types the parser produces. Structural nodes (FROM targets, WHERE operators, GROUP BY / ORDER BY identifiers, function calls) render verbatim. Already-parameterized text (`SQLInputParameter` nodes carrying `:name` / `?` placeholders in the input) renders as `?` idempotently. The AST walk correctly handles doubled single quotes (`'It''s'`), JSON strings inside SELECT projections, and regex predicates in `MATCHES` clauses — all classified by the parser as `SQLString` nodes. Unit-tested independently in this track (full coverage of statement-type variations runs in Track 6a's `OTelSqlQueryTest`).

The sixth edit adds JUnit unit tests covering: `LocalResultSet` constructor fast-path when listeners empty (no clock read, no state stored); constructor instrumented path (clock read, state stored); iteration delegates and captures `caughtError`; `close()` fires every listener with correct `elapsedNanos`; `close()` listener exception isolation (one listener throwing does not prevent others from firing or block the close); `SqlSanitizer` literal-replacement edge cases (doubled single quotes, JSON literals, MATCHES regex predicates); all three entry points on `DatabaseSessionEmbedded` invoking the instrumented `LocalResultSet`. The SQL classifier's per-statement-type unit tests land in Track 1 alongside the classifier itself; full end-to-end OTel-SDK assertions land in Tracks 6a / 6b / 6c.

Ordering: edit 1 (constructor signature + fields — no dependencies on other Track 4 edits) → edit 2 (iteration changes — depends on edit 1 fields) → edit 3 (`close()` — depends on edit 1 fields) → edit 4 (entry-point wiring — depends on the new constructor from edit 1) → edit 5 (sanitizer, independent of edits 1-4 but called by `close()`'s lazy `QueryDetails`) → edit 6 (tests cover all five preceding edits).

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 4:

- A `db.command("SELECT FROM User WHERE name = 'Alice'")` call constructs an instrumented `LocalResultSet`; `close()` fires the registered `QueryMetricsListener` with a `QueryDetails` whose `getQuery()` returns `"SELECT FROM User WHERE name = ?"`, `getOperationName()` returns `Optional.of("SELECT")`, `getCollectionName()` returns `Optional.of("User")`.
- A `db.query("SELECT FROM User WHERE name = ?", "Alice")` call fires the listener with the same `QueryDetails` shape as the equivalent `db.command(...)` — both entry points construct the same `LocalResultSet` constructor, so coverage is identical (regression check for the pre-mutation gap where `query()` was uncovered).
- A `db.command("INSERT INTO Order SET amount = 100, customer = 'X'")` call yields operation `INSERT`, collection `Order`, sanitized `"INSERT INTO Order SET amount = ?, customer = ?"`.
- A `db.command("MATCH {class:User, as:u}-knows->{class:User, as:f} RETURN u, f")` call yields operation `MATCH`, collection `User` (the first pattern node).
- A `db.command("CREATE INDEX User.email UNIQUE")` call yields operation `CREATE`, collection `User`, sanitized text unchanged (DDL contains no literals).
- A parameterized `db.command("SELECT FROM User WHERE id = ?", 42)` call yields sanitized text `"SELECT FROM User WHERE id = ?"` (unchanged, already parameterized) and the listener sees the parameters separately.
- A multi-statement script (`db.executeSqlScript("INSERT INTO X ...; INSERT INTO Y ...; ")` or the equivalent) emits exactly one SQL span (the outer `LocalResultSet` wraps the `ScriptExecutionPlan`); inner statements via `ScriptLineStep.start()` do not double-fire because they call inner-plan `start()` directly without wrapping in `LocalResultSet`.
- A MATCH query that spawns sub-plans via hash-join or correlated-optional steps emits exactly one SQL span (the outer `LocalResultSet`); the sub-plans' `start()` calls do not construct `LocalResultSet` instances.
- A Gremlin Path A traversal (translated by PR #1038's `GremlinToMatchStrategy`) emits exactly one Gremlin span (Track 3 owner); `LocalResultSet` is never constructed because `YTDBMatchPlanStep` calls `SelectExecutionPlan.start()` directly.
- A Gremlin Path B fallback traversal `g.V().out("knows").out("knows").toList()` (when the translator declines) emits one Gremlin parent span + one SQL child span; the child's `parentSpanId` matches the Gremlin span's `spanId` via OTel `Context.current()` propagation (Track 3 owns the Gremlin span lifecycle hook).
- An exception thrown during stream iteration propagates to the caller as before; `LocalResultSet.close()` captures the exception class on its way out and surfaces it through `QueryDetails.getErrorType()`; the span carries status ERROR and `error.type` populated.
- A listener exception during SQL hook firing does not propagate to the caller; the SQL statement completes normally.
- A SQL statement run inside a YTDBTransaction emits a span whose parent is determined by `Context.current()` at `LocalResultSet` construction time — under Gremlin Path B that is the active Gremlin span; under direct SQL with a host-wrapping span it is the host span; otherwise the trace root.
- The OTel module remains opt-in: with `OPENTELEMETRY_ENABLED=false`, no SQL classifier or sanitizer code runs (the `LocalResultSet` constructor takes the un-instrumented fast path when no listeners are registered).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

## Interfaces and Dependencies

In scope:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/LocalResultSet.java` (new constructor signature, fields, iteration capture of `caughtError`, instrumented `close()`).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (entry-point wiring — three call sites at lines 617, 652, 702 pass the parsed `SQLStatement` + raw SQL + args + `currentTx` to the new `LocalResultSet` constructor).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/SqlSanitizer.java` (AST-walk static utility alongside `SqlSyntaxClassifier`).

Out of scope:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransaction.java` — no new `getTrackingId()` accessor; the SQL fire site uses the existing `getId(): long` (CR16 resolution).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBTransaction.java` — not touched.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphQuery.java` — not touched. Path B fallback works because the Gremlin span lifecycle hook (Track 3 owner) makes the Gremlin span `Context.current()` during inner `transaction.query(...)` execution; the inner `LocalResultSet` then attaches its SQL span as a child via OTel `Context.current()` propagation. No try-with-resources wrapping needed.
- `core/.../SqlSyntaxClassifier.java`, `core/.../GremlinBytecodeClassifier.java`, `core/.../Classification.java` — all land in Track 1.
- OTel module's Gremlin span lifecycle hook on `YTDBQueryMetricsStep` — Track 3 owner.
- Configuration parameters and lifecycle wiring (Track 5).
- End-to-end integration tests with the OTel SDK (Tracks 6a / 6b / 6c).
- Native SQL DDL semantics beyond extracting the target class name. The classifier does not validate DDL correctness.
- SQL parser grammar — no changes. SQL has no tag source (M1; Gremlin-only tag source via `g.with(...)`). The resolver short-circuits on `Optional.empty()`.

Inter-track dependencies:
- Depends on Track 1 (TX SPI extensions, global listener registry, both classifier helpers + `Classification` record, exception-wrapper widening to `Exception | LinkageError | AssertionError`, listener snapshot fields on `FrontendTransactionImpl`, the new `FrontendTransaction.getDefaultQueryMonitoringMode()` + `resolveQueryMonitoringMode(Optional<String>)` accessors, and the new `QueryMonitoringModeResolver` + `TagRule<T>` sealed interface).
- Depends on Track 2 (the `youtrackdb-opentelemetry` Maven module exists; the SQL fire path invokes the OTel listener via the global registry snapshot — `SqlSanitizer` itself lives in `core` next to `SqlSyntaxClassifier` because both walk the parser-output AST).
- Depends on Track 3 (`OTelQueryMetricsListener` exists; the SQL hook feeds into the same listener instance via the global registry snapshot. Track 3 also owns the OTel module's Gremlin span lifecycle hook on `YTDBQueryMetricsStep` that makes Path B parent-child span hierarchy work via `Context.current()`).
- Provides for Track 6a (`OTelSqlQueryTest` covers each statement type and the sanitizer edge cases) and Track 6c (`OTelDbQuerySpanTest` covers the `db.query(...)` regression path; `OTelPathSpanShapeTest` covers Path A vs Path B span counts).

Library / function signatures introduced:

```java
// core/.../profiler/monitoring/SqlSanitizer.java (new — AST-walk over parser output)
public final class SqlSanitizer {
  public static String sanitize(SQLStatement statement);
}

// core/.../sql/parser/LocalResultSet.java (extended)
public class LocalResultSet implements ResultSet {
  // New constructor signature wires the parsed statement + raw SQL + args + tx
  // into instrumentation fields. Existing constructor (executionPlan only) is
  // retained for callers that bypass instrumentation (sub-plans, internal use).
  public LocalResultSet(
      InternalExecutionPlan executionPlan,
      SQLStatement statement,
      String rawSql,
      Object args,
      FrontendTransaction currentTx);
}

// SqlSyntaxClassifier lives in core/.../profiler/monitoring/ (Track 1).
// Track 4 calls it from LocalResultSet's lazy QueryDetails accessor on first
// read of getOperationName() / getCollectionName(); no new public surface here.
```
