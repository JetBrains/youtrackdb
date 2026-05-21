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
- `currentTx` (FrontendTransaction): the active transaction. The `getTrackingId()` method lives on `YTDBTransaction`, not on `FrontendTransaction`, so the hook either casts when possible or generates a synthetic tracking ID from `currentTx`'s internal ID when no `YTDBTransaction` wraps it.
- `statement` (SQLStatement): the parsed AST, available after `SQLEngine.parse(...)`.

OTel-module-side classes:
- `SqlSanitizer`: pure function `sanitize(String rawSql) → String`. Replaces string literals (between single quotes), numeric literals, date literals, and boolean literals with `?`. Parameterized query text (already contains `?`) passes through unchanged. Conservative on edge cases: when uncertain, leaves the text as-is rather than corrupting it.
- `SqlSyntaxClassifier`: implements `QueryClassifier` SPI. Method `classify(Object source) → Classification` receives the `SQLStatement` object passed by the hook. Returns operation name based on statement subclass (SELECT / INSERT / UPDATE / DELETE / MATCH / CREATE / ALTER / DROP) and collection name from FROM / INTO / UPDATE target class.

Concrete deliverables:

1. New listener fire site in `DatabaseSessionEmbedded.executeInternal()` wrapping `statement.execute(...)` with a timer and a `QueryDetails` construction. Reads the global registry snapshot from the current transaction (via the registry SPI from Track 1).
2. A SQL-flavored `QueryDetails` implementation carrying raw SQL, sanitized SQL, operation name, collection name, tracking ID. Lives in `core` (close to the hook site) and uses the new `QueryClassifier` SPI to populate operation/collection lazily.
3. `core` interface `QueryClassifier` with `classify(Object source) → Classification` (the source object is statement-source-specific: `Bytecode` for Gremlin, `SQLStatement` for SQL). ServiceLoader-discovered.
4. `SqlSanitizer` in `youtrackdb-opentelemetry`, regex-based with carefully tested edge cases.
5. `SqlSyntaxClassifier` in `youtrackdb-opentelemetry`, registered as a `QueryClassifier` service via `META-INF/services/`.
6. Transaction-tracking-id resolution: add `getTrackingId(): Optional<String>` default method to the lower-level transaction interface that `currentTx` is typed as, with `YTDBTransaction` overriding it to return the actual tracking ID. Fallback to a synthetic ID from `currentTx.hashCode()` when no override is present.

## Plan of Work

Six edits.

The first edit defines the `QueryClassifier` SPI in `core` and refactors Track 3's `GremlinBytecodeClassifier` integration to use it (the SPI shape was introduced in Track 3 as ServiceLoader-discovered; this edit formalizes the interface into a named type in `core` so both classifier impls can implement it). The Classification record stays in `core` as a small value type.

The second edit adds `SqlSanitizer` to the OTel module. Pure regex-based replacement for string literals (`'[^']*'` → `?`), numeric literals (`\b\d+(\.\d+)?\b` → `?`), date/timestamp literals (`DATE 'YYYY-MM-DD'`, `TIMESTAMP '...'`), and boolean literals. The implementation is conservative: when a sequence is ambiguous (e.g., string contains an escaped quote), it returns the input unchanged rather than producing a corrupted output. Unit-tested independently in this track (full coverage of statement-type variations runs in Track 6).

The third edit adds `SqlSyntaxClassifier implements QueryClassifier` to the OTel module. The implementation reads the `SQLStatement` subclass via `instanceof` checks and returns the operation name from a small lookup. Collection name comes from:
- `SQLSelectStatement.getTarget()` → first class name in FROM clause
- `SQLMatchStatement` → first pattern node's class
- `SQLInsertStatement.getTargetClass()` → INTO target
- `SQLUpdateStatement.getTarget()` → UPDATE target
- `SQLDeleteStatement.getTarget()` → DELETE target
- DDL statements → class name from the statement subclass (CREATE CLASS X → "X", CREATE INDEX X.Y → "X")
- Returns `Optional.empty()` for shapes that don't fit (anonymous targets, multi-target).

The fourth edit adds the `getTrackingId(): Optional<String>` method to `FrontendTransaction` interface (default returning `Optional.empty()`), with `YTDBTransaction` overriding it to return the existing tracking ID. This unblocks the hook from needing an instanceof check.

The fifth edit adds the listener fire site in `DatabaseSessionEmbedded.executeInternal()`. Wraps `statement.execute(this, args, true)` with `System.nanoTime()` and `System.currentTimeMillis()` start measurements, fires `queryFinished(...)` on every listener in the global-registry snapshot held by `currentTx` after the call completes. The `QueryDetails` impl carries raw SQL (from `stringStatement` or `statement.getOriginalStatement()`), lazy-sanitized SQL (calls `SqlSanitizer.sanitize(...)` on first `getQuery()` call), and lazy operation/collection (calls `QueryClassifier.classify(statement)` on first access). Wrapped in try/catch so a listener throw does not propagate.

The sixth edit adds unit tests for `SqlSanitizer` and `SqlSyntaxClassifier` covering each statement type: SELECT with WHERE clause containing string and numeric literals; MATCH pattern; INSERT INTO ... VALUES; UPDATE ... SET; DELETE FROM; CREATE INDEX; ALTER CLASS. Tests confirm sanitization output and classifier output independently of the listener wiring (full end-to-end flows land in Track 6).

Ordering: edits 1 → 2,3 (parallel) → 4 → 5 → 6.

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
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/QueryClassifier.java` (new SPI interface)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/Classification.java` (small value record)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (listener fire site in `executeInternal()`)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransaction.java` (or its parent interface; new `getTrackingId(): Optional<String>` default)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/YTDBTransaction.java` (override `getTrackingId()` returning the actual ID)
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/sql/SqlSanitizer.java`
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/sql/SqlSyntaxClassifier.java`
- `youtrackdb-opentelemetry/src/main/resources/META-INF/services/<QueryClassifier-FQN>` (extended to list both Gremlin and SQL classifier impls, or one service entry per implementation if the SPI permits multiple discovery)

Out of scope:
- Configuration parameters and lifecycle wiring (Track 5).
- End-to-end integration tests with the OTel SDK (Track 6).
- Native SQL DDL semantics beyond extracting the target class name. The classifier does not validate DDL correctness.

Inter-track dependencies:
- Depends on Track 1 (TX SPI extensions, global listener registry, `QueryClassifier` SPI slot).
- Depends on Track 3 (`OTelQueryMetricsListener` exists; the SQL hook feeds into the same listener instance).
- Provides for Track 6: full SQL coverage in the test suite, including each statement type and the sanitizer edge cases.

Library / function signatures introduced or modified:

```java
// core
package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

public interface QueryClassifier {
  Classification classify(Object source);
}

public record Classification(
    Optional<String> operationName,
    Optional<String> collectionName) {
  public static final Classification EMPTY =
      new Classification(Optional.empty(), Optional.empty());
}

// core, FrontendTransaction interface
default Optional<String> getTrackingId() { return Optional.empty(); }

// youtrackdb-opentelemetry
public final class SqlSanitizer {
  public static String sanitize(String rawSql);
}

public final class SqlSyntaxClassifier implements QueryClassifier {
  public Classification classify(Object source);  // source is SQLStatement
}
```
