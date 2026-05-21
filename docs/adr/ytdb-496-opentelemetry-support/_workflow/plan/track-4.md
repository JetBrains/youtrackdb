# Track 4: SQL execution layer hook, sanitizer, and syntax classifier

## Purpose / Big Picture

After this track lands, every native SQL statement (`db.command("SELECT ...")`, MATCH, INSERT, UPDATE, DELETE, DDL) fires `QueryMetricsListener.queryFinished(...)` through a single hook at `DatabaseSessionEmbedded.executeInternal()`. The OTel module ships `SqlSanitizer` (literal-to-`?` replacement for `db.query.text`); the SQL classifier (`core.SqlSyntaxClassifier`) is owned by Track 1, called directly from the fire site to populate the inline `QueryDetails` operation/collection accessors.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Add a listener fire site at `DatabaseSessionEmbedded.executeInternal()` so every native SQL statement (SELECT / INSERT / UPDATE / DELETE / MATCH / DDL) flows through `QueryMetricsListener.queryFinished(...)`. Implement `SqlSanitizer` (literal-to-`?` replacement for `db.query.text`) inside the OTel module. The SQL classifier lives in `core` (Track 1) and is called directly from the new fire site to populate the inline `QueryDetails`.

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

The track has two halves: the core-side hook (`DatabaseSessionEmbedded`) and the OTel-module-side classifier and sanitizer.

Core-side hook insertion point: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java`, method `executeInternal()` around lines 702-751. The method dispatches via `statement.execute(this, args, true)` for every SQL statement type. All SQL flows through this single path:

```
db.command("SELECT ...") | db.query("...")
  → Transaction.query() / Transaction.execute()
  → FrontendTransactionImpl.query() / .execute()  (lines ~1751-1776)
  → DatabaseSessionEmbedded.executeInternal()  ← HOOK POINT
  → SQLEngine.parse(stringStatement, this)  (returns SQLStatement)
  → statement.execute(this, args, true)  (returns ResultSet)
```

Available state at hook time:
- `stringStatement` (String): the raw SQL text. Null only on internal recursive call with pre-parsed statement; the fallback is `statement.getOriginalStatement()`.
- `args` (Object): positional `Object[]` or named `Map<Object, Object>` parameters.
- `currentTx` (FrontendTransaction): the active transaction. The existing `FrontendTransaction.getId(): long` accessor returns a stable internal ID; the hook uses `String.valueOf(currentTx.getId())` as the tracking ID — no new accessor is added (CR16 resolution).
- `statement` (SQLStatement): the parsed AST, available after `SQLEngine.parse(...)`.

OTel-module-side class:
- `SqlSanitizer`: pure function `sanitize(String rawSql) → String`. Replaces string literals (between single quotes), numeric literals, date literals, and boolean literals with `?`. Parameterized query text (already contains `?`) passes through unchanged. Conservative on edge cases: when uncertain, leaves the text as-is rather than corrupting it.

Core-side classifier (delivered by Track 1, consumed here):
- `core.SqlSyntaxClassifier.classify(SQLStatement)`: static utility that dispatches on the AST subclass (`SQLSelectStatement` → SELECT with first FROM target, `SQLInsertStatement` → INSERT, `SQLUpdateStatement` → UPDATE, `SQLDeleteStatement` → DELETE, `SQLMatchStatement` → MATCH, `SQLCreateClassStatement` / `SQLAlterClassStatement` / `SQLDropClassStatement` → CREATE / ALTER / DROP); returns `Classification.EMPTY` for shapes that don't fit.

Concrete deliverables:

1. New listener fire site in `DatabaseSessionEmbedded.executeInternal()` wrapping `statement.execute(...)` with a timer and a `QueryDetails` construction. Reads the global registry snapshot AND the `QueryMonitoringMode` snapshot from `currentTx` (both captured at `beginInternal` time per Track 1). Timing routes through `GranularTicker` under `LIGHTWEIGHT` (default, no syscall on hot path) or `System.nanoTime()` under `EXACT` (sub-millisecond precision, two syscalls per query), matching the pre-existing pattern in `FrontendTransactionImpl.doCommit` and `YTDBQueryMetricsStep.close()`.
2. A SQL-flavored `QueryDetails` implementation carrying raw SQL, sanitized SQL, operation name, collection name, database name, tracking ID. Lives in `core` (close to the hook site) and calls `SqlSyntaxClassifier.classify(statement)` (Track 1's static utility) to populate operation/collection lazily; database name comes from `getDatabaseName()` on the session; tracking ID is `String.valueOf(currentTx.getId())`.
3. `SqlSanitizer` in `youtrackdb-opentelemetry`, regex-based with carefully tested edge cases.

The `Classification` record and `SqlSyntaxClassifier` both land in Track 1 — Track 4 only wires the call into the SQL fire site. No new `FrontendTransaction.getTrackingId()` accessor is needed (CR16 resolution).

## Plan of Work

Three edits. The `Classification` record and `SqlSyntaxClassifier` both come from Track 1, so Track 4 only ships the OTel-module sanitizer plus the SQL fire-site wiring (which calls Track 1's classifier directly).

The first edit adds `SqlSanitizer` to the OTel module. Pure regex-based replacement for string literals (`'[^']*'` → `?`), numeric literals (`\b\d+(\.\d+)?\b` → `?`), date/timestamp literals (`DATE 'YYYY-MM-DD'`, `TIMESTAMP '...'`), and boolean literals. The implementation is conservative: when a sequence is ambiguous (e.g., string contains an escaped quote), it returns the input unchanged rather than producing a corrupted output. Unit-tested independently in this track (full coverage of statement-type variations runs in Track 6).

The second edit adds the listener fire site in `DatabaseSessionEmbedded.executeInternal()`. Reads `mode = currentTx.getQueryMonitoringMode()` (Track 1's new accessor) and routes timing capture: `LIGHTWEIGHT` uses `YouTrackDBEnginesManager.instance().getTicker().approximateCurrentTimeMillis()` + `approximateNanoTime()` for both start and duration; `EXACT` uses `System.currentTimeMillis()` + `System.nanoTime()`. Same pattern as `FrontendTransactionImpl.doCommit` (lines 650-722). Wraps `statement.execute(this, args, true)` with the chosen start measurements, fires `queryFinished(...)` on every listener in the per-TX snapshot held by `currentTx` after the call completes. The `QueryDetails` impl carries raw SQL (from `stringStatement` or `statement.getOriginalStatement()`), lazy-sanitized SQL (calls `SqlSanitizer.sanitize(...)` on first `getQuery()` call), lazy operation/collection (calls `core.SqlSyntaxClassifier.classify(statement)` — Track 1's static utility — on first access), the session's database name as `getDatabaseName()`, and `String.valueOf(currentTx.getId())` as the tracking ID. Wrapped in try/catch (widened to `Throwable` per Track 1) so a listener throw does not propagate.

The third edit adds unit tests for `SqlSanitizer` covering literal-replacement edge cases. The SQL classifier's per-statement-type unit tests land in Track 1 alongside the classifier itself; Track 4's tests focus on the sanitizer plus a smoke test of the executeInternal hook's end-to-end behaviour (full statement-type coverage runs in Track 6).

Ordering: edit 1 → edit 2 (the hook calls the sanitizer) → edit 3 (tests cover both).

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 4:

- A `db.command("SELECT FROM User WHERE name = 'Alice'")` call fires the registered `QueryMetricsListener` with a `QueryDetails` whose `getQuery()` returns `"SELECT FROM User WHERE name = ?"`, `getOperationName()` returns `Optional.of("SELECT")`, `getCollectionName()` returns `Optional.of("User")`.
- A `db.command("INSERT INTO Order SET amount = 100, customer = 'X'")` call yields operation `INSERT`, collection `Order`, sanitized `"INSERT INTO Order SET amount = ?, customer = ?"`.
- A `db.command("MATCH {class:User, as:u}-knows->{class:User, as:f} RETURN u, f")` call yields operation `MATCH`, collection `User` (the first pattern node).
- A `db.command("CREATE INDEX User.email UNIQUE")` call yields operation `CREATE`, collection `User`, sanitized text unchanged (DDL contains no literals).
- A parameterized `db.command("SELECT FROM User WHERE id = ?", 42)` call yields sanitized text `"SELECT FROM User WHERE id = ?"` (unchanged, already parameterized) and the listener sees the parameters separately.
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
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (listener fire site in `executeInternal()`, SQL-flavored `QueryDetails` inline impl calling `core.SqlSyntaxClassifier.classify(...)`)
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/sql/SqlSanitizer.java`

Out of scope:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransaction.java` — no new `getTrackingId()` accessor; the hook uses the existing `getId(): long` (CR16 resolution).
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

// SqlSyntaxClassifier lives in core/.../profiler/monitoring/ (Track 1).
// Track 4 only calls it from DatabaseSessionEmbedded.executeInternal(),
// no new public surface added here.
```
