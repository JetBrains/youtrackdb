# Track 4: SQL execution layer helper, Gremlin-SQL suppression, sanitizer, and syntax classifier wire

## Purpose / Big Picture

After this track lands, every native SQL statement (`db.command(...)`, `db.execute(...)`, AND read-only `db.query(...)` — including MATCH, INSERT, UPDATE, DELETE, DDL) fires `QueryMetricsListener.queryFinished(...)` through a single private helper `executeStatementWithMetrics(SQLStatement, String, Object)` on `DatabaseSessionEmbedded`, invoked from both `query()` (line 617) and `executeInternal()` (line 702). A new `GremlinSqlSuppression` utility (re-entrant ThreadLocal counter + `AutoCloseable` token) activated by `YTDBGraphQuery.execute(session)` short-circuits the helper for Gremlin-driven SQL so one traversal emits exactly one span. The OTel module ships `SqlSanitizer` (literal-to-`?` replacement for `db.query.text`); the SQL classifier (`core.SqlSyntaxClassifier`) is owned by Track 1, called directly from the helper to populate the inline `QueryDetails` operation/collection accessors.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Extract `executeStatementWithMetrics(SQLStatement, String, Object)` as a private helper on `DatabaseSessionEmbedded` and invoke it from both `query()` and `executeInternal()` so every native SQL statement (SELECT / INSERT / UPDATE / DELETE / MATCH / DDL) flows through `QueryMetricsListener.queryFinished(...)`. Add a `GremlinSqlSuppression` static utility in `core` and wrap `YTDBGraphQuery.execute`'s underlying `transaction.query(...)` call with a try-with-resources `activate()` token so the helper stays silent during Gremlin-driven SQL. Implement `SqlSanitizer` (literal-to-`?` replacement for `db.query.text`) inside the OTel module. The SQL classifier lives in `core` (Track 1) and is called directly from the helper to populate the inline `QueryDetails`.

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

The track has three halves: the core-side helper extraction (`DatabaseSessionEmbedded` + `YTDBGraphQuery`), the new `GremlinSqlSuppression` utility, and the OTel-module-side sanitizer.

Core-side helper insertion point: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` has TWO parallel SQL paths today — `query()` at lines 617-686 (backs `db.query(...)` and the Gremlin bridge) and `executeInternal()` at lines 702-751 (backs `db.command(...)` / `db.execute(...)`). Both follow the same shape: parse via `SQLEngine.parse(...)`, dispatch via `statement.execute(this, args, true)`, post-process the result. Track 4 extracts a private helper that wraps `statement.execute(...)` with the listener fire and invokes it from both call sites:

```
db.command("...") / db.execute("...")
  → FrontendTransactionImpl.execute()   (lines 1765-1776)
  → DatabaseSessionEmbedded.executeInternal()   (line 702)
  → SQLEngine.parse(...)
  → executeStatementWithMetrics(statement, rawSql, args)   ← HELPER

db.query("...")
  → FrontendTransactionImpl.query()   (lines 1751-1762)
  → DatabaseSessionEmbedded.query()   (line 617)
  → SQLEngine.parse(...)
  → isIdempotent() check
  → executeStatementWithMetrics(statement, rawSql, args)   ← SAME HELPER

g.V().hasLabel("X").toList()   (Gremlin)
  → YTDBGraphStep
  → YTDBGraphQuery.execute(session)
  → GremlinSqlSuppression.activate() (try-with-resources)
  → transaction.query(...)
  → DatabaseSessionEmbedded.query()
  → executeStatementWithMetrics(...)
  → checks GremlinSqlSuppression.isActive() → short-circuits
```

Available state at helper invocation:
- `statement` (SQLStatement): the parsed AST, both call sites parse before delegating.
- `rawSql` (String): raw SQL text — `stringStatement` from `executeInternal`'s caller, or the original SQL string from `query()`. Null fallback: `statement.getOriginalStatement()`.
- `args` (Object): positional `Object[]` or named `Map<Object, Object>` parameters.
- `currentTx` (FrontendTransaction): the active transaction. The existing `FrontendTransaction.getId(): long` accessor returns a stable internal ID; the helper uses `String.valueOf(currentTx.getId())` as the tracking ID — no new accessor is added (CR16 resolution).

New `GremlinSqlSuppression` class in `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/GremlinSqlSuppression.java`:
- `activate(): AutoCloseable` — increments a ThreadLocal counter; returns a token whose `close()` decrements.
- `isActive(): boolean` — counter > 0.
- Re-entrant so nested Gremlin steps inside one another don't underflow.

`YTDBGraphQuery.execute` site: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphQuery.java` line 23-26. Wrap the existing `transaction.query(this.query, this.params)` in `try (var ignored = GremlinSqlSuppression.activate())`.

OTel-module-side class:
- `SqlSanitizer`: pure function `sanitize(String rawSql) → String`. Replaces string literals (between single quotes), numeric literals, date literals, and boolean literals with `?`. Parameterized query text (already contains `?`) passes through unchanged. Conservative on edge cases: when uncertain, leaves the text as-is rather than corrupting it.

Core-side classifier (delivered by Track 1, consumed here):
- `core.SqlSyntaxClassifier.classify(SQLStatement)`: static utility that dispatches on the AST subclass (`SQLSelectStatement` → SELECT with first FROM target, `SQLInsertStatement` → INSERT, `SQLUpdateStatement` → UPDATE, `SQLDeleteStatement` → DELETE, `SQLMatchStatement` → MATCH, `SQLCreateClassStatement` / `SQLAlterClassStatement` / `SQLDropClassStatement` → CREATE / ALTER / DROP); returns `Classification.EMPTY` for shapes that don't fit.

Concrete deliverables:

1. New private helper `executeStatementWithMetrics(SQLStatement, String, Object)` in `DatabaseSessionEmbedded` wrapping `statement.execute(this, args, true)` with a timer and a `QueryDetails` construction. Reads the global registry snapshot AND the `QueryMonitoringMode` snapshot from `currentTx` (both captured at `beginInternal` time per Track 1). Short-circuits when listeners empty OR `GremlinSqlSuppression.isActive()` — returns `statement.execute(...)` directly with zero overhead. Timing routes through `GranularTicker` under `LIGHTWEIGHT` (default, no syscall on hot path) or `System.nanoTime()` under `EXACT` (sub-millisecond precision, two syscalls per query), matching the pre-existing pattern in `FrontendTransactionImpl.doCommit` and `YTDBQueryMetricsStep.close()`.
2. Wire the helper into `DatabaseSessionEmbedded.executeInternal()` (line 702) — replace the direct `statement.execute(...)` call inside the existing `switch (args)` block with a single helper invocation. Preserve the `isIdempotent`-driven prefetch vs streaming branch around the helper's return value.
3. Wire the helper into `DatabaseSessionEmbedded.query()` (line 617 and the boolean-syncTx overload at line 652) — replace the direct `statement.execute(...)` call (after the `isIdempotent()` check) with a single helper invocation.
4. New `GremlinSqlSuppression` static utility class in `core/.../profiler/monitoring/`. Re-entrant ThreadLocal counter + `AutoCloseable` activation token; `isActive()` boolean check.
5. Wire `GremlinSqlSuppression.activate()` into `YTDBGraphQuery.execute(session)` (lines 23-26) via try-with-resources around the existing `transaction.query(this.query, this.params)` call.
6. A SQL-flavored `QueryDetails` implementation carrying raw SQL, sanitized SQL, operation name, collection name, database name, tracking ID. Lives in `core` (close to the helper) and calls `SqlSyntaxClassifier.classify(statement)` (Track 1's static utility) to populate operation/collection lazily; database name comes from `getDatabaseName()` on the session; tracking ID is `String.valueOf(currentTx.getId())`.
7. `SqlSanitizer` in `youtrackdb-opentelemetry`, regex-based with carefully tested edge cases.

The `Classification` record and `SqlSyntaxClassifier` both land in Track 1 — Track 4 only wires the call into the SQL helper. No new `FrontendTransaction.getTrackingId()` accessor is needed (CR16 resolution).

## Plan of Work

Six edits. The `Classification` record and `SqlSyntaxClassifier` both come from Track 1, so Track 4 ships the OTel-module sanitizer plus the core-side helper extraction, the suppression utility, the `YTDBGraphQuery` activation, and the SQL helper wiring (which calls Track 1's classifier directly).

The first edit adds the `GremlinSqlSuppression` static utility in `core/.../profiler/monitoring/`. Single class with a private ThreadLocal `AtomicInteger` (or boxed `Integer`) counter; static `activate()` returns an `AutoCloseable` whose `close()` decrements the counter; static `isActive()` returns counter > 0. Re-entrant so nested Gremlin steps inside one another increment and decrement symmetrically. No public constructor.

The second edit extracts a private helper `executeStatementWithMetrics(SQLStatement statement, String rawSql, Object args)` in `DatabaseSessionEmbedded` and wires it into `executeInternal()` (line 702). The helper reads the listener snapshot from `currentTx.getGlobalQueryListeners()`, short-circuits when empty OR `GremlinSqlSuppression.isActive()` (returns `statement.execute(this, args, true)` directly with zero overhead). Otherwise reads `mode = currentTx.getQueryMonitoringMode()` and routes timing capture: `LIGHTWEIGHT` uses `YouTrackDBEnginesManager.instance().getTicker().approximateCurrentTimeMillis()` + `approximateNanoTime()` for both start and duration; `EXACT` uses `System.currentTimeMillis()` + `System.nanoTime()`. Same pattern as `FrontendTransactionImpl.doCommit` (lines 650-722). Wraps `statement.execute(this, args, true)` with the chosen start measurements, fires `queryFinished(...)` on every listener in the per-TX snapshot after the call completes. The `QueryDetails` impl carries raw SQL (from `rawSql` parameter, with `statement.getOriginalStatement()` fallback in the caller), lazy-sanitized SQL (calls `SqlSanitizer.sanitize(...)` on first `getQuery()` call), lazy operation/collection (calls `core.SqlSyntaxClassifier.classify(statement)` — Track 1's static utility — on first access), the session's database name as `getDatabaseName()`, and `String.valueOf(currentTx.getId())` as the tracking ID. Wrapped in try/catch (widened to `Throwable` per Track 1) so a listener throw does not propagate.

The third edit wires the helper into `DatabaseSessionEmbedded.query()` (line 617) and the `query(String, boolean syncTx, Map)` overload (line 652). Both methods replace their direct `statement.execute(this, args, true)` call (placed AFTER the `isIdempotent()` check) with a single `executeStatementWithMetrics(statement, query, args)` invocation. The post-execute `queryStartedLifecycle(...)` wrapping remains in each caller.

The fourth edit wires `GremlinSqlSuppression.activate()` into `YTDBGraphQuery.execute(session)` at `core/.../gremlin/YTDBGraphQuery.java` lines 23-26. The existing `return transaction.query(this.query, this.params);` is wrapped in a try-with-resources: `try (var ignored = GremlinSqlSuppression.activate()) { return transaction.query(this.query, this.params); }`. Cleanup runs even if the SQL throws.

The fifth edit adds `SqlSanitizer` to the OTel module. Pure regex-based replacement for string literals (`'[^']*'` → `?`), numeric literals (`\b\d+(\.\d+)?\b` → `?`), date/timestamp literals (`DATE 'YYYY-MM-DD'`, `TIMESTAMP '...'`), and boolean literals. The implementation is conservative: when a sequence is ambiguous (e.g., string contains an escaped quote), it returns the input unchanged rather than producing a corrupted output. Unit-tested independently in this track (full coverage of statement-type variations runs in Track 6).

The sixth edit adds JUnit unit tests covering: `GremlinSqlSuppression` counter behavior (activate/close round-trips, re-entrance, exception safety), `SqlSanitizer` literal-replacement edge cases, helper short-circuit when no listeners or when suppression active, helper fire when listeners present and suppression inactive, both `query()` and `executeInternal()` invoking the helper with the same end-to-end semantics. The SQL classifier's per-statement-type unit tests land in Track 1 alongside the classifier itself; full end-to-end OTel-SDK assertions land in Track 6.

Ordering: edit 1 (suppression utility — no dependencies) → edit 2 (helper extraction + `executeInternal` wire) → edit 3 (`query()` wire — depends on edit 2's helper) → edit 4 (Gremlin activation site — depends on edit 1) → edit 5 (sanitizer, independent, runs in the OTel module) → edit 6 (tests cover all five preceding edits).

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 4:

- A `db.command("SELECT FROM User WHERE name = 'Alice'")` call fires the registered `QueryMetricsListener` with a `QueryDetails` whose `getQuery()` returns `"SELECT FROM User WHERE name = ?"`, `getOperationName()` returns `Optional.of("SELECT")`, `getCollectionName()` returns `Optional.of("User")`.
- A `db.query("SELECT FROM User WHERE name = ?", "Alice")` call fires the listener with the same `QueryDetails` shape as the equivalent `db.command(...)` — both call sites delegate to the helper, so coverage is identical (regression check for the pre-mutation gap where `query()` was uncovered).
- A `db.command("INSERT INTO Order SET amount = 100, customer = 'X'")` call yields operation `INSERT`, collection `Order`, sanitized `"INSERT INTO Order SET amount = ?, customer = ?"`.
- A `db.command("MATCH {class:User, as:u}-knows->{class:User, as:f} RETURN u, f")` call yields operation `MATCH`, collection `User` (the first pattern node).
- A `db.command("CREATE INDEX User.email UNIQUE")` call yields operation `CREATE`, collection `User`, sanitized text unchanged (DDL contains no literals).
- A parameterized `db.command("SELECT FROM User WHERE id = ?", 42)` call yields sanitized text `"SELECT FROM User WHERE id = ?"` (unchanged, already parameterized) and the listener sees the parameters separately.
- A Gremlin traversal `g.V().hasLabel("User").toList()` fires the listener exactly once at `YTDBQueryMetricsStep.close()` (Track 3 owner). The SQL helper does NOT fire when called from inside `YTDBGraphQuery.execute` because `GremlinSqlSuppression.isActive()` returns true for the duration of the `transaction.query(...)` call.
- A multi-hop Gremlin traversal `g.V().out("knows").out("knows").toList()` still fires exactly one listener callback (the Gremlin one). Each underlying `YTDBGraphStep` invokes the SQL helper, but every invocation short-circuits because the suppression counter is > 0 throughout.
- An exception thrown inside the Gremlin-driven SQL call does NOT leak the suppression state to the next operation on that thread — `AutoCloseable.close()` runs in the implicit `finally` of try-with-resources.
- A listener exception during SQL hook firing does not propagate to the caller; the SQL statement completes normally.
- A SQL statement run inside a YTDBTransaction emits a span that is a child of the TX span (parent context flows correctly through the global registry snapshot).
- The OTel module remains opt-in: with `OPENTELEMETRY_ENABLED=false`, no SQL classifier or sanitizer code runs.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

## Interfaces and Dependencies

In scope:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (private `executeStatementWithMetrics` helper, wires from both `query()` and `executeInternal()`, SQL-flavored `QueryDetails` inline impl calling `core.SqlSyntaxClassifier.classify(...)`).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/GremlinSqlSuppression.java` (new static utility — re-entrant ThreadLocal counter + `AutoCloseable` token).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBGraphQuery.java` (try-with-resources `GremlinSqlSuppression.activate()` wrapping the existing `transaction.query(...)` call in `execute(session)`).
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/sql/SqlSanitizer.java`

Out of scope:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransaction.java` — no new `getTrackingId()` accessor; the helper uses the existing `getId(): long` (CR16 resolution).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBTransaction.java` — not touched.
- `core/.../SqlSyntaxClassifier.java`, `core/.../GremlinBytecodeClassifier.java`, `core/.../Classification.java` — all land in Track 1.
- Configuration parameters and lifecycle wiring (Track 5).
- End-to-end integration tests with the OTel SDK (Track 6).
- Native SQL DDL semantics beyond extracting the target class name. The classifier does not validate DDL correctness.

Inter-track dependencies:
- Depends on Track 1 (TX SPI extensions, global listener registry, both classifier helpers + `Classification` record, exception-wrapper widening to `Throwable`, snapshot fields on `FrontendTransactionImpl` including the new `QueryMonitoringMode` field, and the new `FrontendTransaction.getQueryMonitoringMode()` accessor).
- Depends on Track 2 (the `youtrackdb-opentelemetry` Maven module where `SqlSanitizer` lives).
- Depends on Track 3 (`OTelQueryMetricsListener` exists; the SQL hook feeds into the same listener instance via the global registry snapshot).
- Provides for Track 6: full SQL coverage in the test suite, including each statement type and the sanitizer edge cases.

Library / function signatures introduced:

```java
// youtrackdb-opentelemetry
public final class SqlSanitizer {
  public static String sanitize(String rawSql);
}

// core/.../profiler/monitoring/GremlinSqlSuppression.java (new)
public final class GremlinSqlSuppression {
  public static AutoCloseable activate();   // increments thread-local counter
  public static boolean isActive();          // counter > 0
  private GremlinSqlSuppression() {}         // no instances
}

// DatabaseSessionEmbedded helper (private surface, not in any public API)
private ResultSet executeStatementWithMetrics(
    SQLStatement statement, String rawSql, Object args);

// SqlSyntaxClassifier lives in core/.../profiler/monitoring/ (Track 1).
// Track 4 calls it from executeStatementWithMetrics via the lazy QueryDetails
// accessor, no new public surface added here.
```
