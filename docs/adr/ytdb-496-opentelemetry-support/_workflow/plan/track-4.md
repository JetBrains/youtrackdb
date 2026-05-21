# Track 4: SQL execution layer hook, sanitizer, and syntax classifier

## Purpose / Big Picture

After this track lands, every native SQL statement (`db.command("SELECT ...")`, MATCH, INSERT, UPDATE, DELETE, DDL) fires `QueryMetricsListener.queryFinished(...)` through a single hook at `DatabaseSessionEmbedded.executeInternal()`. The OTel module ships `SqlSanitizer` (literal-to-`?` replacement for `db.query.text`) and `SqlSyntaxClassifier` (operation + collection extraction from the parsed AST), both registered through the `QueryClassifier` SPI from Track 1.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Add a listener fire site at `DatabaseSessionEmbedded.executeInternal()` so every native SQL statement (SELECT / INSERT / UPDATE / DELETE / MATCH / DDL) flows through `QueryMetricsListener.queryFinished(...)`. Implement `SqlSanitizer` (literal-to-`?` replacement for `db.query.text`) and `SqlSyntaxClassifier` (operation type + target class from the parsed `SQLStatement` AST) inside the OTel module. Both classifier impls register through the `QueryClassifier` SPI introduced in Track 1.

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

OTel-module-side classes:
- `SqlSanitizer`: pure function `sanitize(String rawSql) → String`. Replaces string literals (between single quotes), numeric literals, date literals, and boolean literals with `?`. Parameterized query text (already contains `?`) passes through unchanged. Conservative on edge cases: when uncertain, leaves the text as-is rather than corrupting it.
- `SqlSyntaxClassifier`: implements `QueryClassifier` SPI. Method `classify(Object source) → Classification` receives the `SQLStatement` object passed by the hook. Returns operation name based on statement subclass (`SQLSelectStatement` → SELECT, `SQLInsertStatement` → INSERT, `SQLUpdateStatement` → UPDATE, `SQLDeleteStatement` → DELETE, `SQLMatchStatement` → MATCH, `SQLCreateClassStatement` → CREATE, `SQLAlterClassStatement` → ALTER, `SQLDropClassStatement` → DROP) and collection name from FROM / INTO / UPDATE target class.

Concrete deliverables:

1. New listener fire site in `DatabaseSessionEmbedded.executeInternal()` wrapping `statement.execute(...)` with a timer and a `QueryDetails` construction. Reads the global registry snapshot from `currentTx` (captured at `beginInternal` time per Track 1).
2. A SQL-flavored `QueryDetails` implementation carrying raw SQL, sanitized SQL, operation name, collection name, database name, tracking ID. Lives in `core` (close to the hook site) and uses the `QueryClassifier` SPI to populate operation/collection lazily; database name comes from `getDatabaseName()` on the session; tracking ID is `String.valueOf(currentTx.getId())`.
3. `SqlSanitizer` in `youtrackdb-opentelemetry`, regex-based with carefully tested edge cases.
4. `SqlSyntaxClassifier` in `youtrackdb-opentelemetry`, registered as a `QueryClassifier` service via `META-INF/services/`.

The `QueryClassifier` interface and `Classification` record both land in Track 1 — Track 4 only adds the SQL impl on top of them. No new `FrontendTransaction.getTrackingId()` accessor is needed (CR16 resolution).

## Plan of Work

Five edits. The `QueryClassifier` SPI and `Classification` record both come from Track 1, so Track 4 only adds the SQL impl on top.

The first edit adds `SqlSanitizer` to the OTel module. Pure regex-based replacement for string literals (`'[^']*'` → `?`), numeric literals (`\b\d+(\.\d+)?\b` → `?`), date/timestamp literals (`DATE 'YYYY-MM-DD'`, `TIMESTAMP '...'`), and boolean literals. The implementation is conservative: when a sequence is ambiguous (e.g., string contains an escaped quote), it returns the input unchanged rather than producing a corrupted output. Unit-tested independently in this track (full coverage of statement-type variations runs in Track 6).

The second edit adds `SqlSyntaxClassifier implements QueryClassifier` to the OTel module. The implementation reads the `SQLStatement` subclass via `instanceof` checks and returns the operation name from a small lookup. Collection name comes from:
- `SQLSelectStatement.getTarget()` → first class name in FROM clause
- `SQLMatchStatement` → first pattern node's class
- `SQLInsertStatement.getTargetClass()` → INTO target
- `SQLUpdateStatement.getTarget()` → UPDATE target
- `SQLDeleteStatement.getFromClause()` → DELETE target (first FROM class)
- `SQLCreateClassStatement` / `SQLAlterClassStatement` / `SQLDropClassStatement` → class name read from the parsed statement
- Returns `Optional.empty()` for shapes that don't fit (anonymous targets, multi-target).

The third edit adds the listener fire site in `DatabaseSessionEmbedded.executeInternal()`. Wraps `statement.execute(this, args, true)` with `System.nanoTime()` and `System.currentTimeMillis()` start measurements, fires `queryFinished(...)` on every listener in the per-TX snapshot held by `currentTx` after the call completes. The `QueryDetails` impl carries raw SQL (from `stringStatement` or `statement.getOriginalStatement()`), lazy-sanitized SQL (calls `SqlSanitizer.sanitize(...)` on first `getQuery()` call), lazy operation/collection (calls `QueryClassifier.classify(statement)` on first access), the session's database name as `getDatabaseName()`, and `String.valueOf(currentTx.getId())` as the tracking ID. Wrapped in try/catch (widened to `Throwable` per Track 1) so a listener throw does not propagate.

The fourth edit registers `SqlSyntaxClassifier` in the OTel module's `META-INF/services/<QueryClassifier-FQN>` file alongside `GremlinBytecodeClassifier` (also registered there by Track 3).

The fifth edit adds unit tests for `SqlSanitizer` and `SqlSyntaxClassifier` covering each statement type: SELECT with WHERE clause containing string and numeric literals; MATCH pattern; INSERT INTO ... VALUES; UPDATE ... SET; DELETE FROM; CREATE CLASS / ALTER CLASS / DROP CLASS. Tests confirm sanitization output and classifier output independently of the listener wiring (full end-to-end flows land in Track 6).

Ordering: edits 1, 2 parallel → edit 3 → edit 4 → edit 5.

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
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (listener fire site in `executeInternal()`, SQL-flavored `QueryDetails` inline impl)
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/sql/SqlSanitizer.java`
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/sql/SqlSyntaxClassifier.java`
- `youtrackdb-opentelemetry/src/main/resources/META-INF/services/<QueryClassifier-FQN>` (extended to list `SqlSyntaxClassifier` alongside `GremlinBytecodeClassifier` from Track 3)

Out of scope:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransaction.java` — no new `getTrackingId()` accessor; the hook uses the existing `getId(): long` (CR16 resolution).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/YTDBTransaction.java` — not touched.
- `core/.../QueryClassifier.java` and `core/.../Classification.java` — both land in Track 1.

Out of scope:
- Configuration parameters and lifecycle wiring (Track 5).
- End-to-end integration tests with the OTel SDK (Track 6).
- Native SQL DDL semantics beyond extracting the target class name. The classifier does not validate DDL correctness.

Inter-track dependencies:
- Depends on Track 1 (TX SPI extensions, global listener registry, `QueryClassifier` SPI + `Classification` record, exception-wrapper widening to `Throwable`, snapshot fields on `FrontendTransactionImpl`).
- Depends on Track 3 (`OTelQueryMetricsListener` exists; the SQL hook feeds into the same listener instance via the global registry snapshot).
- Provides for Track 6: full SQL coverage in the test suite, including each statement type and the sanitizer edge cases.

Library / function signatures introduced:

```java
// youtrackdb-opentelemetry
public final class SqlSanitizer {
  public static String sanitize(String rawSql);
}

public final class SqlSyntaxClassifier implements QueryClassifier {
  public Classification classify(Object source);  // source is SQLStatement
}
```
