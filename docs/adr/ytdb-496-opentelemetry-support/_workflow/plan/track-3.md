<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# Track 3: OTel listener implementations

## Purpose / Big Picture

After this track lands, `youtrackdb-opentelemetry` contains four production classes — `OTelQueryMetricsListener`, `OTelTransactionMetricsListener`, `OTelGremlinSpanLifecycleHook`, and the `YouTrackDBOpenTelemetry` facade — that translate YTDB listener callbacks into sem-conv v1.33.0 compliant OTel spans. The listener reads pre-populated `QueryDetails` accessors (`getOperationName`, `getCollectionName`, `getDatabaseName`, `getErrorType`, `getQuerySource`, `getResultCount`, the six structural `db.youtrackdb.*` accessors from D19), uses `getQuerySource()` to route between enrich-existing-span and create-new-span paths per design.md §"Context propagation in embedded", and applies the slow-query + heartbeat gates from D16 / D18 before any allocation on the SQL path. The lifecycle hook implements the `core.GremlinSpanLifecycleHook` interface from Track 1 and owns the Gremlin span's open-at-first-hasNext / end-at-close lifecycle so inner SQL spans inside a Path B fallback traversal attach as children of the Gremlin span via `Context.current()` propagation. A new `FinalizationStrategy` registered with TinkerPop (`OTelGremlinFinalizationStrategy`) walks the step list after `YTDBQueryMetricsStrategy` runs and calls `step.setLifecycleHook(otelHook)` on each `YTDBQueryMetricsStep` instance. Track 1 ships the global registry + classifiers + lifecycle hook interface; Track 4 wires the SQL fire site; Track 5 owns auto-registration on the config flag.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Implement `OTelQueryMetricsListener`, `OTelTransactionMetricsListener`, `OTelGremlinSpanLifecycleHook`, `OTelGremlinFinalizationStrategy`, and the `YouTrackDBOpenTelemetry` facade inside the new module. Map every Gremlin and SQL callback to sem-conv-compliant spans with the right kind, attributes, and parent context — classification is owned by Track 1; the listener consumes pre-populated `QueryDetails` accessors. SQL source wiring lands in Track 4; auto-registration on config flag in Track 5.

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

The track owns code under `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/`. The new module from Track 2 provides the empty skeleton; Track 1 provides the SPI extensions this code consumes — the new `QueryMetricsListener.QueryDetails` default accessors (operation, collection, namespace, errorType, querySource, resultCount, and the six structural fields from D19), the `TransactionMetricsListener.TransactionDetails.getDatabaseName()` accessor, the static-method global registry on `YourTracks`, the `GremlinSpanLifecycleHook` interface plus the `setLifecycleHook(...)` setter on `YTDBQueryMetricsStep`, and the two classifier helpers in `core` that populate the new accessors before the listener runs. No new lifecycle methods are added to `TransactionMetricsListener` (D3 / D10 reversed); commit-side spans emit only on `writeTransactionCommitted` / `writeTransactionFailed`, for write transactions only.

Sem-conv v1.33.0 references the project uses are listed in design.md §"Sem-conv attribute mapping". The classifier helpers live in `core` (Track 1) and use the same instruction-walk pattern as `produceScript()` in `YTDBQueryMetricsStep` (around line 225, walking `instruction.getOperator()` and `instruction.getArguments()`); this track only consumes their results indirectly through the populated `QueryDetails` accessors.

Span hierarchy under Path B Gremlin fallback comes from OTel `Context` propagation, not from a per-listener ThreadLocal. `OTelGremlinSpanLifecycleHook` opens the Gremlin span at first `hasNext()` of `YTDBQueryMetricsStep` and ends it at `close()` AFTER the listener iteration. During iteration `Context.current()` resolves to the Gremlin span; any inner `InstrumentedSqlResultSet` constructor (Track 4) captures that Context into `capturedContext` and restores it via `Scope.makeCurrent()` around its own `queryFinished` fire, so the inner SQL span attaches as a child of the Gremlin span without any cross-listener coordination. `OTelQueryMetricsListener.queryFinished` distinguishes the two fire sites via `details.getQuerySource()`: on `GREMLIN` it reads `Span.fromContext(Context.current())` and sets attributes on the hook-managed span (no `spanBuilder` / `end` calls); on `SQL` it creates a new span via `spanBuilder().setParent(Context.current())` as in the workflow diagram. The commit listener takes its parent from `Context.current()` at `writeTransactionCommitted` fire time — typically the host's TX-wrapping span (D3 reversed dropped the YTDB-side TX-lifetime wrapper).

Query-text sanitization for Gremlin is already done in `core` — `YTDBQueryMetricsStep` runs `ValueAnonymizingTypeTranslator` (its package-private inner class) and exposes the result via `QueryDetails.getQuery()`. The OTel listener consumes that string directly; no class-level reuse of the translator is needed from the OTel module.

Concrete deliverables:

1. `OTelQueryMetricsListener implements QueryMetricsListener`: translates one `queryFinished` callback into span work routed by `details.getQuerySource()`. Constructor signature `(Tracer tracer, SpanKind clientKind, long defaultThresholdNanos, long defaultHeartbeatNanos)` plus a package-private `AtomicLong lastHeartbeatNanos` field initialized to `0`. On `Optional.of(SQL)`: evaluates the heartbeat gate (D18) and the slow-query gate (D16) at the top of `queryFinished` before any `spanBuilder()` call; if both gates drop, returns early with no allocation; otherwise builds the span via `tracer.spanBuilder(name).setSpanKind(clientKind).setStartTimestamp(getStartedAtEpochNanos(), NS).setParent(Context.current()).startSpan()`, sets the sem-conv attributes (D5, D6, D9, D19), sets `error.type` + status `ERROR` when `details.getErrorType().isPresent()`, and calls `span.end(startEpochNanos + executionTimeNanos, NS)`. On `Optional.of(GREMLIN)`: evaluates the same gates; on pass it reads `Span.fromContext(Context.current())` (the hook-managed Gremlin span), sets the same sem-conv attributes + error state, and does NOT call `spanBuilder()` or `end()` — the lifecycle hook ends the span. On gate-drop the listener returns without enriching the span, leaving a skinny Gremlin span the hook still ends.
2. `OTelTransactionMetricsListener implements TransactionMetricsListener`: translates `writeTransactionCommitted` into a standalone commit span with kind = `clientKind`, parent = `Context.current()` at fire time. Translates `writeTransactionFailed` into a span with status `ERROR` and `error.type` populated from the cause exception class FQN. No TX-lifetime span (D3 reversed); no `transactionStarted` / `transactionRolledBack` callbacks (D10 reversed). Constructor signature `(Tracer tracer, SpanKind clientKind, long defaultCommitThresholdNanos)` populated by `YouTrackDBOpenTelemetry` from `OPENTELEMETRY_COMMIT_SLOW_THRESHOLD_MILLIS` per D38 (default `0` = emit every successful commit; positive value gates `executionTimeNanos < threshold` for successful commits; failed commits always bypass). The gate sits at the top of `writeTransactionCommitted` and returns early before any span allocation when the comparison drops the commit.
3. `OTelGremlinSpanLifecycleHook implements core.GremlinSpanLifecycleHook`: at `onFirstHasNext(details, startEpochNanos, parent)`, calls `tracer.spanBuilder(name).setSpanKind(clientKind).setStartTimestamp(startEpochNanos, NS).setParent(parent).startSpan()` and `scope = span.makeCurrent()`, storing the pair on an instance field keyed by the step instance (so concurrent steps on the same listener instance don't collide — an `IdentityHashMap<YTDBQueryMetricsStep, SpanPair>` guarded by a `ConcurrentHashMap`-style structure works; a per-step `volatile SpanPair currentPair` slot on the step itself is also acceptable). At `onClose(details, endEpochNanos)`, closes the scope and calls `span.end(endEpochNanos, NS)`. Idempotent (a double `onClose` on the same step is a no-op).
4. `OTelGremlinFinalizationStrategy extends TraversalStrategy<FinalizationStrategy>`: walks the step list after `YTDBQueryMetricsStrategy` ran (TinkerPop's `FinalizationStrategy` category guarantees order) and calls `step.setLifecycleHook(otelHook)` on every `YTDBQueryMetricsStep` instance. Registered via `TraversalSource.withStrategies(...)` plumbing the OTel module wires when `YouTrackDBOpenTelemetry.setOpenTelemetry(...)` runs.
5. `YouTrackDBOpenTelemetry`: facade with static entry points (`setOpenTelemetry`, `shutdown`) and instance-managed state. Holds the `Tracer` instance built via `getTracer("com.jetbrains.youtrackdb", YouTrackDBConstants.getRawVersion())` and the `serverMode` flag selected at SDK wiring time. Builds the listener pair + the lifecycle hook + the finalization strategy together; reads the three `OPENTELEMETRY_*_MILLIS` defaults from `GlobalConfiguration` (multiplied to nanoseconds for the listener's threshold / heartbeat / commit-threshold fields). Registers the listeners via `YourTracks.registerGlobalQueryListener` + `registerGlobalTransactionListener` (Track 1 accessors) and installs the OTel finalization strategy onto the project's Gremlin `GraphTraversalSource`.

## Plan of Work

Six edits, with dependencies among them.

The first edit creates `YouTrackDBOpenTelemetry` as a no-op shell: `setOpenTelemetry` stores the instance in a volatile field, `tracer()` returns the lazy-initialized tracer or `null` if no OTel instance is set yet, `clientKind()` returns INTERNAL or CLIENT depending on the `serverMode` flag set by the package-private 3-arg variant `setOpenTelemetry(otel, ownedByYtdb, serverMode)`. Idempotent `shutdown()` clears the field, calls `unregisterGlobalQueryListener` / `unregisterGlobalTransactionListener` on the registered instances, and (when ownership is YTDB) closes the SDK.

The second edit wires the Gremlin classifier from Track 1 into `core.YTDBQueryMetricsStep.close()`. The step constructs the `QueryDetails` inline; this edit overrides the new accessors by running `GremlinBytecodeClassifier.classify(traversal)` (Track 1) against the held traversal and stashing the returned `Classification` in `Optional<String>` / `Optional<Boolean>` / `Optional<Integer>` fields read back by the accessors. The same edit populates `QueryDetails.getQuerySource()` with `Optional.of(QuerySource.GREMLIN)` and `getErrorType()` with the FQN of any exception caught around the traversal-close path. No OTel-module dependency is introduced; the edit lives entirely in `core` and the classifier sits next to `produceScript()`.

The third edit implements `OTelQueryMetricsListener`. The class is final, holds the `Tracer`, `SpanKind clientKind`, `defaultThresholdNanos`, `defaultHeartbeatNanos`, and `AtomicLong lastHeartbeatNanos` fields, and uses `details.getQuerySource()` to route between two paths. SQL path: evaluate heartbeat gate (D18), then slow-query gate (D16); on pass build the span via `tracer.spanBuilder(name).setSpanKind(clientKind).setStartTimestamp(details.getStartedAtEpochNanos(), NS).setParent(Context.current()).startSpan()`, set sem-conv attributes (D5, D6, D9, D14, D19) including `db.youtrackdb.*` from the six structural accessors, set error.type + status ERROR when `details.getErrorType().isPresent()`, call `span.end(startEpochNanos + executionTimeNanos, NS)`. Gremlin path: evaluate the same gates; on pass read `Span.fromContext(Context.current())` (the hook-managed span) and set the same attributes + error state; do NOT call `spanBuilder()` or `span.end()` — the lifecycle hook ends the span. On gate-drop the listener returns silently, leaving the hook-managed span without sem-conv attributes for the SpanProcessor to filter downstream. Span-name fallback chain (`querySummary` → `operationName + collectionName` → `collectionName` → `db.system.name`) lives in a shared private helper used by both paths.

The fourth edit implements `OTelTransactionMetricsListener`. The class is final, holds `Tracer`, `SpanKind clientKind`, and `defaultCommitThresholdNanos`. `writeTransactionCommitted`: if `defaultCommitThresholdNanos > 0 && executionTimeNanos < defaultCommitThresholdNanos` return early (D38); otherwise build a standalone commit span via `tracer.spanBuilder("commit " + details.getTransactionTrackingId()).setSpanKind(clientKind).setStartTimestamp(details.getCommittedAtEpochNanos(), NS).setParent(Context.current()).startSpan()`, set status OK, end with `committedAtEpochNanos + executionTimeNanos`. `writeTransactionFailed`: same shape with status ERROR and `error.type` populated from `cause.getClass().getName()`; the gate is bypassed (failed commits always emit). No TX-lifetime parent span, no `transactionStarted` / `transactionRolledBack` methods (D3 / D10 reversed).

The fifth edit implements `OTelGremlinSpanLifecycleHook` and `OTelGremlinFinalizationStrategy`. The hook stores the active `(Span, Scope)` pair on a per-step slot (the simplest implementation reuses an instance `IdentityHashMap` keyed by `YTDBQueryMetricsStep`; a per-step `volatile` field on the step itself is also acceptable, decided at decomposition time). The strategy implements `TraversalStrategy<FinalizationStrategy>`, walks the step list at apply time, and calls `step.setLifecycleHook(otelHook)` on every `YTDBQueryMetricsStep` instance. TinkerPop's `FinalizationStrategy` category orders this strategy after both `YTDBQueryMetricsStrategy` and PR #1038's `GremlinToMatchStrategy` automatically.

The sixth edit composes the listeners + hook + strategy inside `YouTrackDBOpenTelemetry` at SDK init: read the three `OPENTELEMETRY_*_MILLIS` defaults from `GlobalConfiguration`, build `OTelQueryMetricsListener` + `OTelTransactionMetricsListener` + `OTelGremlinSpanLifecycleHook` + `OTelGremlinFinalizationStrategy` from the same `Tracer` + `clientKind` pair, register the listeners via `YourTracks.registerGlobalQueryListener` + `registerGlobalTransactionListener`, and install the finalization strategy on the project's Gremlin traversal source (the exact install site — typically the `GraphTraversalSource` produced by `YourTracks.gremlin(...)` — is fixed at decomposition time after reading the embedded entry-point shape).

Ordering: edit 1 must come first (facade is consumed by all subsequent edits). Edit 2 depends on Track 1's classifier helper + `setLifecycleHook` setter. Edits 3, 4, 5 depend on edit 1. Edit 6 depends on 3, 4, 5.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 3, with the listeners manually registered via the global registry from Track 1:

- A Gremlin query `g.V().hasLabel("User").toList()` in embedded mode emits one INTERNAL span named `SELECT User` with attributes `db.system.name=youtrackdb`, `db.query.text=g.V().hasLabel(?).toList()` (sanitized), `db.query.summary=SELECT User`, `db.operation.name=SELECT`, `db.collection.name=User`, plus the structural `db.youtrackdb.*` attributes from D19. The same query in server mode emits the same span but with kind CLIENT (per D5).
- A Gremlin query labeled via `g.with(YTDBQueryConfigParam.querySummary, "findActiveUsers")...` emits a span named `findActiveUsers`.
- A write transaction containing two queries and a commit produces three spans: two query spans with kind = `clientKind` plus one standalone commit span with kind = `clientKind`. There is no YTDB-side TX-lifetime parent span (D3 reversed); spans attach directly to `Context.current()` at fire time. Hosts that want a logical container span open one outer span themselves.
- A failed commit emits a standalone commit span with status ERROR and `error.type` populated from the cause exception class name.
- A rollback emits no commit-side span (D3 reversed — write transactions emit on commit only, read-only transactions emit no commit-side span at all).
- A Gremlin Path B fallback (the translator declined; `transaction.query(...)` runs per-step SQL) emits one Gremlin parent span (via the OTel lifecycle hook) plus one SQL child span per inner SQL call (Track 4 owner). The SQL child's `parentSpanId` matches the Gremlin span's `spanId` because the hook makes the Gremlin span `Context.current()` during iteration; the wrapper restores that context around its fire (D20).
- A Gremlin Path A (translated by PR #1038's `GremlinToMatchStrategy`) emits one Gremlin span with no children because `YTDBMatchPlanStep` calls `SelectExecutionPlan.start()` directly without going through any SQL entry point.
- Spans emitted by YTDB attach to the host's active span: if a span `host-span` is current when the query runs, the YTDB query span's parent is `host-span`.
- A query that fails the slow-query gate on the SQL path returns from `queryFinished` before any allocation. On the Gremlin path the lifecycle hook still emits a "skinny" span (no sem-conv attributes); operators install a `SpanProcessor` filtering on the absence of `db.system.name` to drop these downstream.
- An exception thrown inside any OTel listener method is swallowed by the foundation's try/catch wrapper (widened to `Exception | LinkageError | AssertionError` per D11); the transaction completes normally.

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
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/gremlin/OTelGremlinSpanLifecycleHook.java` (impl of `core.GremlinSpanLifecycleHook` interface from Track 1)
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/gremlin/OTelGremlinFinalizationStrategy.java` (TinkerPop `TraversalStrategy<FinalizationStrategy>` that installs the hook on each `YTDBQueryMetricsStep` after `YTDBQueryMetricsStrategy` ran)
- Updated `core/.../YTDBQueryMetricsStep.java`, calling `core.GremlinBytecodeClassifier.classify(traversal)` (Track 1) to populate the inline `QueryDetails` operation / collection / structural / source / error fields.

Out of scope:
- `core/.../GremlinBytecodeClassifier.java`, `core/.../SqlSyntaxClassifier.java`, `core/.../Classification.java`, `core/.../GremlinSpanLifecycleHook.java`, `core/.../QuerySource.java`, and `setLifecycleHook(...)` on `YTDBQueryMetricsStep` — all land in Track 1.
- SQL execution layer hook + SQL sanitizer (Track 4).
- Configuration parameters and SDK auto-configure (Track 5).
- Server lifecycle wiring (Track 5).
- Test suite (Tracks 6a / 6b / 6c; sanity tests inside Track 3 are allowed but the systematic suite lives there).

Inter-track dependencies:
- Depends on Track 1 (SPI extensions, registry, both classifier helpers, `Classification` record, `QuerySource` enum, `GremlinSpanLifecycleHook` interface + `setLifecycleHook` setter on `YTDBQueryMetricsStep`).
- Depends on Track 2 (module skeleton).
- Provides for Track 4: the `OTelQueryMetricsListener` instance that the SQL hook delivers `QueryDetails` to once the SQL source is wired; the SQL fire site reuses the same listener instance via the global registry snapshot.
- Provides for Track 5: the facade with `setOpenTelemetry(OpenTelemetry)` and `shutdown()`; the listeners + lifecycle hook + finalization strategy ready to be installed by the auto-enrolment code.

Library / function signatures introduced:

```java
public final class YouTrackDBOpenTelemetry {
  public static void setOpenTelemetry(OpenTelemetry otel);
  static void setOpenTelemetry(OpenTelemetry otel, boolean ownedByYtdb, boolean serverMode);
  public static void shutdown();
  static Tracer tracer();      // package-private, used by listeners + hook
  static SpanKind clientKind(); // INTERNAL embedded / CLIENT server, per D5
}

public final class OTelQueryMetricsListener implements QueryMetricsListener {
  public OTelQueryMetricsListener(Tracer tracer, SpanKind clientKind,
                                  long defaultThresholdNanos, long defaultHeartbeatNanos);
  public void queryFinished(QueryDetails details, long startedAtMillis, long executionTimeNanos);
  // GREMLIN path: enrich Span.fromContext(Context.current()); no spanBuilder / end.
  // SQL path:    spanBuilder().setParent(Context.current()).startSpan(); end(...).
  // Both paths:  heartbeat + slow-query gates evaluated before any attribute work.
}

public final class OTelTransactionMetricsListener implements TransactionMetricsListener {
  public OTelTransactionMetricsListener(Tracer tracer, SpanKind clientKind,
                                        long defaultCommitThresholdNanos);
  public void writeTransactionCommitted(TransactionDetails td, long commitAtMillis, long commitTimeNanos);
  public void writeTransactionFailed(TransactionDetails td, long commitAtMillis, long commitTimeNanos, Exception cause);
  // No transactionStarted / transactionRolledBack methods (D3 / D10 reversed).
  // Commit-side slow-query gate per D38: drops fast successful commits when
  // defaultCommitThresholdNanos > 0. Failed commits always bypass the gate.
}

public final class OTelGremlinSpanLifecycleHook implements GremlinSpanLifecycleHook {
  public OTelGremlinSpanLifecycleHook(Tracer tracer, SpanKind clientKind);
  public void onFirstHasNext(QueryDetails details, long startEpochNanos,
                             io.opentelemetry.context.Context parent);
  public void onClose(QueryDetails details, long endEpochNanos);
}

public final class OTelGremlinFinalizationStrategy
    extends AbstractTraversalStrategy<TraversalStrategy.FinalizationStrategy>
    implements TraversalStrategy.FinalizationStrategy {
  public OTelGremlinFinalizationStrategy(GremlinSpanLifecycleHook hook);
  public void apply(Traversal.Admin<?, ?> traversal); // finds YTDBQueryMetricsStep, calls setLifecycleHook
}

// no new classifier class in this track — Track 1 owns `core.GremlinBytecodeClassifier`,
// called directly from `YTDBQueryMetricsStep.close()` to populate `QueryDetails`.
```
