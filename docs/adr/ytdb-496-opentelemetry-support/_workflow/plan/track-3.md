# Track 3: OTel listener implementations

## Purpose / Big Picture

After this track lands, `youtrackdb-opentelemetry` contains the three production classes (`OTelQueryMetricsListener`, `OTelTransactionMetricsListener`, `YouTrackDBOpenTelemetry`) that translate YTDB listener callbacks into sem-conv v1.33.0 compliant OTel spans. The Gremlin classifier (`core.GremlinBytecodeClassifier`) is owned by Track 1 — the listener reads pre-populated `QueryDetails.getOperationName()` / `getCollectionName()` accessors without knowing how they were filled. Listeners can be manually registered via the new global registry from Track 1; SQL source wiring lands in Track 4, auto-registration on config flag in Track 5.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Implement `OTelQueryMetricsListener`, `OTelTransactionMetricsListener`, and the `YouTrackDBOpenTelemetry` facade inside the new module. Maps every Gremlin callback to sem-conv-compliant spans with the right kind, attributes, and parent context — Gremlin classification is owned by Track 1 (the listener consumes pre-populated `QueryDetails` accessors). SQL source wiring lands in Track 4; registration logic in Track 5.

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

The track owns code under `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/`. The new module from Track 2 provides the empty skeleton; Track 1 provides the SPI extensions this code consumes (`transactionStarted`, `transactionRolledBack`, the three new `QueryMetricsListener.QueryDetails` accessors, the `TransactionMetricsListener.TransactionDetails.getDatabaseName()` accessor, the static-method global registry on `YourTracks`, plus the two classifier helpers in `core` that populate the new `QueryDetails` accessors before the listener runs). The TX-lifecycle fires happen inside `FrontendTransactionImpl.beginInternal()` / `rollbackInternal()` (Track 1), so this track's listener implementations see callbacks for both Gremlin and native-SQL paths through one fire site.

Sem-conv v1.33.0 references the project uses are listed in design.md §"Sem-conv attribute mapping". The classifier helpers live in `core` (Track 1) and use the same instruction-walk pattern as `produceScript()` in `YTDBQueryMetricsStep` (around line 225, walking `instruction.getOperator()` and `instruction.getArguments()`); this track only consumes their results indirectly through the populated `QueryDetails` accessors.

The TX-lifetime span hierarchy is handled by carrying the TX-span `Context` through the listener instance. When `transactionStarted` fires, `OTelTransactionMetricsListener` creates the TX span, derives `contextWithSpan = Context.current().with(txSpan)`, and stores that `Context` in a `ThreadLocal<Context>` (callbacks fire on the owner thread per D4 because `FrontendTransactionImpl.assertOnOwningThread` pins TX ops). The query listener reads the same `ThreadLocal<Context>` via a package-private accessor to set the TX span as parent of the query span.

Query-text sanitization for Gremlin is already done in `core` — `YTDBQueryMetricsStep` runs `ValueAnonymizingTypeTranslator` (its package-private inner class) and exposes the result via `QueryDetails.getQuery()`. The OTel listener consumes that string directly; no class-level reuse of the translator is needed from the OTel module.

Concrete deliverables:

1. `OTelQueryMetricsListener implements QueryMetricsListener`: translates one `queryFinished` callback into one CLIENT span with sem-conv attributes.
2. `OTelTransactionMetricsListener implements TransactionMetricsListener`: translates `transactionStarted` into an INTERNAL TX span; `writeTransactionCommitted` into a child CLIENT commit span; `writeTransactionFailed` and `transactionRolledBack` into proper end states.
3. `YouTrackDBOpenTelemetry`: static facade. `setOpenTelemetry(OpenTelemetry)`, `shutdown()`. Holds the `Tracer` instance built via `getTracer("com.jetbrains.youtrackdb", YouTrackDBConstants.getRawVersion())`.

## Plan of Work

Five edits, with dependencies among them.

The first edit creates `YouTrackDBOpenTelemetry` as a no-op shell: `setOpenTelemetry` stores the instance in a volatile field, `tracer()` returns the lazy-initialized tracer or `null` if no OTel instance is set yet. Idempotent `shutdown()` clears the field.

The second edit wires the Gremlin classifier from Track 1 into `core.YTDBQueryMetricsStep.close()`. The step constructs the `QueryDetails` inline; this edit overrides the new `getOperationName()` and `getCollectionName()` defaults by running `GremlinBytecodeClassifier.classify(bytecode)` (a static utility delivered by Track 1) against the held bytecode and stashing the returned `Classification` in two `Optional<String>` fields read back by the accessors. No OTel-module dependency is introduced; the classifier sits next to `produceScript()` in `core`.

The third edit implements `OTelQueryMetricsListener`. On `queryFinished`, it reads `Context.current()` for parent, builds a span name using the fallback chain (`querySummary` → `operationName + collectionName` → `collectionName` → `db.system.name`), sets sem-conv attributes (`db.system.name=youtrackdb`, `db.query.text`, `db.query.summary`, `db.operation.name`, `db.collection.name` when present), starts the span with the `startedAtMillis` timestamp, ends it with `startedAtMillis + executionTimeNanos`. Span kind CLIENT.

The fourth edit implements `OTelTransactionMetricsListener`. On `transactionStarted`, opens an INTERNAL TX span as child of `Context.current()` and stores `Context.current().with(txSpan)` (a single `Context` value) in a `ThreadLocal<Context>`. On `writeTransactionCommitted`, fetches the ThreadLocal context, opens a child CLIENT commit span using `commitAtMillis`+`commitTimeNanos`, ends the TX span with OK status. On `writeTransactionFailed`, same but TX span gets ERROR status with `error.type` from the cause. On `transactionRolledBack`, ends the TX span without a commit child (no failure attribute). The ThreadLocal is cleared in a `finally` block.

The fifth edit wires the `Context.makeCurrent()` semantics inside the query listener. Because TX listeners only set the `ThreadLocal<Context>` but do not call `makeCurrent()` (that would leak the context outside YTDB), the query listener explicitly reads from the ThreadLocal owned by the TX listener when both are registered. The lookup is package-private to keep coupling explicit.

Ordering: edit 1 must come first (facade is consumed by all subsequent edits). Edit 2 depends on Track 1's classifier helper being present. Edits 3 and 4 depend on 1. Edit 5 depends on 3 and 4.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 3, with the listeners manually registered via the global registry from Track 1:

- A Gremlin query `g.V().hasLabel("User").toList()` emits one CLIENT span named `SELECT User` with attributes `db.system.name=youtrackdb`, `db.query.text=g.V().hasLabel(?).toList()` (sanitized), `db.query.summary=SELECT User`, `db.operation.name=SELECT`, `db.collection.name=User`.
- A Gremlin query labeled via `g.with(YTDBQueryConfigParam.querySummary, "findActiveUsers")...` emits a span named `findActiveUsers`.
- A write transaction containing two queries and a commit produces three spans: one INTERNAL TX span (parent), two CLIENT query spans (children of TX), one CLIENT commit span (child of TX). The commit span overlaps the tail of the TX span.
- A failed commit emits a TX span with status ERROR and `error.type` populated from the cause exception class name.
- A rollback emits a TX span with OK status and no commit child.
- A read-only transaction emits a TX span with OK status, child query spans, no commit span.
- Spans emitted by YTDB attach to the host's active span: if a span `host-span` is current when the query runs, the YTDB query span's parent is `host-span`.
- An exception thrown inside any OTel listener method is swallowed by the foundation's try/catch wrapper; the transaction completes normally.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

## Interfaces and Dependencies

In scope:
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/YouTrackDBOpenTelemetry.java`
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/listener/OTelQueryMetricsListener.java`
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/listener/OTelTransactionMetricsListener.java`
- Updated `core/.../YTDBQueryMetricsStep.java`, calling `core.GremlinBytecodeClassifier.classify(bytecode)` (Track 1) to populate the inline `QueryDetails` operation/collection fields.

Out of scope:
- `core/.../GremlinBytecodeClassifier.java`, `core/.../SqlSyntaxClassifier.java`, `core/.../Classification.java` — all land in Track 1.
- SQL execution layer hook + SQL sanitizer (Track 4).
- Configuration parameters (Track 5).
- Server lifecycle wiring (Track 5).
- Test suite (Tracks 6a / 6b / 6c; sanity tests inside Track 3 are allowed but the systematic suite lives there).

Inter-track dependencies:
- Depends on Track 1 (SPI extensions, registry, both classifier helpers, `Classification` record).
- Depends on Track 2 (module skeleton).
- Provides for Track 4: the `OTelQueryMetricsListener` instance that the SQL hook will deliver `QueryDetails` to once the SQL source is wired; the Gremlin fire-site wiring pattern in `YTDBQueryMetricsStep` shows the same shape Track 4's SQL fire site reuses.
- Provides for Track 5: the facade with `setOpenTelemetry(OpenTelemetry)` and `shutdown()`; the two listener classes ready to be installed by the auto-enrolment code.

Library / function signatures introduced:

```java
public final class YouTrackDBOpenTelemetry {
  public static void setOpenTelemetry(OpenTelemetry otel);
  public static void shutdown();
  static Tracer tracer();  // package-private, used by listeners
}

public final class OTelQueryMetricsListener implements QueryMetricsListener {
  public void queryFinished(QueryDetails details, long startedAtMillis, long executionTimeNanos);
}

public final class OTelTransactionMetricsListener implements TransactionMetricsListener {
  public void transactionStarted(TransactionDetails txDetails);
  public void writeTransactionCommitted(TransactionDetails txDetails, long commitAtMillis, long commitTimeNanos);
  public void writeTransactionFailed(TransactionDetails txDetails, long commitAtMillis, long commitTimeNanos, Exception cause);
  public void transactionRolledBack(TransactionDetails txDetails);
}

// no new classifier class in this track — Track 1 owns `core.GremlinBytecodeClassifier`,
// called directly from `YTDBQueryMetricsStep.close()` to populate `QueryDetails`.
```
