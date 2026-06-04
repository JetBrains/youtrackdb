<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# YTDB-496 OpenTelemetry support — Design

## Overview

This design adds a new optional Maven module `youtrackdb-opentelemetry` that wires YTDB into `OpenTelemetry` across all three signal types: distributed tracing (spans from query and transaction listener callbacks), logs (every record emitted through YTDB's `LogManager` chokepoint, hard-context-correlated with the active span at emission time), and metrics (the existing `Profiler.getMetricsRegistry()` counter set surfaced through `OTel` async instruments at a configurable period). The result is that a host running embedded YTDB and an operator running a standalone server both get database telemetry — spans, correlated logs, and counter samples — visible in any `OTel`-compatible viewer. This design assumes familiarity with the existing `QueryMetricsListener` and `TransactionMetricsListener` firing sites in `YTDBQueryMetricsStep` and `FrontendTransactionImpl`, and with the `YTDBTransaction` open / commit / rollback lifecycle. The audience is contributors maintaining the metrics and transaction subsystems in `core`.

Today YTDB has an internal `QueryMetricsListener` SPI that fires only on Gremlin traversal close, plus a `TransactionMetricsListener` that fires on write-transaction commit, but the listeners are per-transaction and the project ships no OTel binding. Native SQL queries (the path used for `db.command(...)`, MATCH, and DDL) currently fire neither listener. The design closes that gap with two load-bearing additions: a global listener registry in `core` so an OTel listener registered once at startup auto-applies to every subsequent transaction; and a new listener fire site at the SQL session-boundary, applied as a thin wrapper on whatever `ResultSet` each `db.query()` / `db.command()` / `db.execute()` call returns. The wrapper class `InstrumentedSqlResultSet` (new, in `internal.core.db`) is installed at six return-sites across the public SQL entry points on `DatabaseSessionEmbedded` (the two `query(...)` overloads, both return branches of `executeInternal(...)`, and the two `computeScript(...)` overloads) as the outermost layer of whatever the site would otherwise return, sitting above any `queryStartedLifecycle(...)` decoration and any [PR #1077](https://github.com/JetBrains/youtrackdb/pull/1077) cache-view construction. The constructor reads a `(start-epoch-nanos, start-nanoTime)` clock pair chosen by the resolved `QueryMonitoringMode` — `LIGHTWEIGHT` from `GranularTicker` (~10 ms granularity, no syscall on the hot path) or `EXACT` from `Instant.now()` plus `System.nanoTime()` (sub-ms, two syscalls); SQL has no tag source, so the mode resolves to the per-TX default. Iteration delegates to the inner result-set with row counting and error capture; `close()` fires the listener with the elapsed-time delta from the same clock pair. Every SQL statement type (SELECT, INSERT, UPDATE, DELETE, MATCH, DDL) and multi-statement scripts flow through this single outer-boundary fire site. Sub-plans called from MATCH steps, sub-query steps, and IF / WHILE control flow construct their own un-instrumented inner `LocalResultSet` instances via the existing 2-arg constructor and never reach the entry-point wrapper, so they do not double-fire. The exact return-sites, layer ordering, and constructor steps live in §"SQL execution layer hook"; the clock-source rules live in §"Span timing capture".

**Coordination with YTDB-820.** The forthcoming transaction-scoped query result cache ([PR #1077 design, YTDB-820 implementation](https://github.com/JetBrains/youtrackdb/pull/1077)) lands first and changes what these entry points return: on cache hit, and on cache miss for cacheable RECORD / MATCH / AGGREGATE shapes, the caller receives a `CachedResultSetView` rather than a `LocalResultSet`. The wrapper sits one layer above both, so a single fire site covers every inner-type the cache can produce: hits, misses for the cacheable shapes (including the aggregate path), the fallback when planner splicing fails, bypasses for non-deterministic queries, the per-query `NOCACHE` hint, nested calls under WHERE evaluation, and the disabled-cache default in v1. The wrapper has zero changes to `LocalResultSet.java`, which keeps YTDB-820's stream-slot substitution machinery untouched. The full matrix and its consequences live in §"SQL execution layer hook → Coordination with YTDB-820 cache contract".

A pair of static-utility classifiers in `core` (`GremlinBytecodeClassifier`, `SqlSyntaxClassifier`), called directly from their respective fire sites, extracts `db.operation.name` and `db.collection.name` so spans carry sem-conv v1.33.0 attributes. A Gremlin traversal whose execution falls back to per-step SQL (Path B in the [Gremlin-to-MATCH translator](https://github.com/JetBrains/youtrackdb/pull/1038) nomenclature) emits a parent-child hierarchy: one Gremlin span at `YTDBQueryMetricsStep.close()` plus one child SQL span at `InstrumentedSqlResultSet.close()`, related via OTel `Context.current()` propagation. Path A (translated Gremlin, post-PR-1038) emits one Gremlin span only because `YTDBMatchPlanStep` opens the underlying `SelectExecutionPlan` directly without going through the SQL entry points.

The TX listener side stays narrow on purpose: only `writeTransactionCommitted` and `writeTransactionFailed` fire, both for write transactions only, and the OTel implementation emits a standalone commit span with no YTDB-side TX-lifetime wrapper. Read-only transactions emit nothing on the TX listener and therefore no commit-side span. This matches the existing YTDB read-only-TX semantics (the TX listener never fires on a read-only close) and keeps mostly-read workloads from paying alloc-and-emit cost per query for an empty container span.

On the query side, a configurable slow-query threshold gates span emission inside `OTelQueryMetricsListener.queryFinished` so a host running heavy read traffic can drop fast successful queries before any tracer allocation. The global default is `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_MILLIS=0` (emit-all) so spans surface immediately after `OPENTELEMETRY_ENABLED=true`; operators on read-heavy workloads opt into a positive value (e.g., `100`) to drop fast successful queries. Per-tag overrides go through `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_TAG_RULES` (same format as the per-tag mode rules), resolved through a parallel `SlowQueryThresholdResolver` consuming `TagRule<Long>` on the same sealed interface. Errors bypass the gate because trace viewers are the primary investigation surface for failures; a 1 ms failing query still emits a span carrying `error.type` and the sanitized query text.

A second optional gate emits a wall-clock heartbeat sample: `OPENTELEMETRY_QUERY_HEARTBEAT_SAMPLE_MILLIS` (default `0` = disabled) sets a process-wide interval, and `OTelQueryMetricsListener.queryFinished` emits one span per interval regardless of query duration. The two gates compose disjunctively (heartbeat picks fast queries for visibility, slow-query catches latency outliers, errors always emit). The heartbeat carries no per-tag override; it samples the workload as a whole. Operators who need per-tag sampling streams configure downstream filtering on the OTel pipeline against `db.query.summary`. The mechanism counters the structural bias of random sampling (1% of queries skews toward fast queries because fast queries are simply more numerous); a wall-clock heartbeat is unbiased over time.

`YTDBTransaction` exposes a builder-style API for listener wiring: `withQueryMonitoringMode(mode)`, `withTrackingId(id)`, `withQueryListener(listener)`, and `withTransactionListener(listener)` are separate fluent methods. `withTrackingId(String)` and `getTrackingId()` already exist on `YTDBTransaction` today (with explicit-when-set / `String.valueOf(getId())`-fallback semantics); what YTDB-496 adds is lifting `getTrackingId(): String` from `YTDBTransaction` only onto the `FrontendTransaction` interface so the SQL fire site and the commit fire site read the same accessor through the interface without downcasting to `YTDBTransaction`. The setter and the storage stay where they are.

Other subsystems restructured to fit: the nested `QueryMetricsListener.QueryDetails` gains three `Optional<String>` accessors (operation, collection, namespace), an `Optional<String>` `getErrorType()` accessor populated from any exception caught around the fire site, and an `OptionalLong getResultCount()` accessor populated by `InstrumentedSqlResultSet` (incremented on each `next()` that returned a result) for the sem-conv `db.client.response.returned_rows` histogram input (SQL path populated for both `LocalResultSet` and `CachedResultSetView` inner result-sets via the wrapper-side counter; Gremlin path leaves it empty in v1), the nested `TransactionMetricsListener.TransactionDetails` gains one (namespace), the existing exception-isolation try/catch in `FrontendTransactionImpl` and `YTDBQueryMetricsStep` widens from `Exception` to the narrower-than-Throwable union `Exception | LinkageError | AssertionError` to cover the OTel-specific failure modes (misconfigured SDK, missing exporter classes, assertion failures) without masking `VirtualMachineError` or `ThreadDeath`, the SQL hook reuses the existing `FrontendTransaction.getId(): long` accessor (no new tracking-id method) and adds three new accessors on `FrontendTransaction` — `getDefaultQueryMonitoringMode(): QueryMonitoringMode` exposing the per-TX fallback used by commit and by queries with no tag-rule match, `resolveQueryMonitoringMode(Optional<String> tag): QueryMonitoringMode` delegating to the process-global `QueryMonitoringModeResolver` for per-query mode selection from the query tag, and `iterateAllQueryListeners(): Iterable<QueryMetricsListener>` exposing the merged global-snapshot + per-TX-list view — and `GlobalConfiguration` gains a family of `OPENTELEMETRY_*` entries that drive the server-mode SDK init plus the tag-rule table (master switch, query-tag rule sets for mode / slow-query / heartbeat, exporter wiring, two log-side entries `OPENTELEMETRY_LOGS_ENABLED` and `OPENTELEMETRY_LOGS_MIN_SEVERITY`, and three metric-side entries `OPENTELEMETRY_METRICS_ENABLED`, `OPENTELEMETRY_METRICS_PERIOD_MILLIS`, and `OPENTELEMETRY_METRICS_INCLUDED_GROUPS`).

In embedded mode the SDK resolution chain has three steps in priority order: host-provided via `YouTrackDBOpenTelemetry.setOpenTelemetry(otel)`, then `GlobalOpenTelemetry.get()` if the host configured the global, then a YTDB-built SDK auto-configured from `OPENTELEMETRY_*` config when neither of the first two yielded a real instance. The flag is never inert; ownership is tracked so `shutdown()` closes only the SDK YTDB created. In server mode YTDB always owns the SDK because the server is a standalone process; an `OpenTelemetrySdk` built from the same config entries wires through a `ServerLifecycleListener`-based plugin.

Alongside the three pillars the PR also ships a quick-start observability stack under `youtrackdb-opentelemetry/examples/docker-compose/` so a first-time operator goes from clone to first span in under five minutes without assembling the Collector / Jaeger / Loki / Prometheus / Grafana wiring from upstream docs. The stack is example-files-only (zero source-code edits in `core`, `server`, or the OTel module), uses pinned image versions, ships three pre-provisioned Grafana dashboards (overview, queries, storage), and wires Jaeger → Loki and Jaeger → Prometheus correlators so clicking a span navigates to the matching logs and metrics filtered by `trace_id` and `service.name`. A smoke script (`scripts/smoke.sh`) runs a minimal embedded query and exits non-zero when any pillar fails to land within 30 seconds, giving the optional CI job a deterministic signal that the example stack and the YTDB-side wiring agree. § "Quick-start observability stack" below covers the full deliverable list and the production-vs-local-dev trade-offs the example deliberately makes.

The rest of this document covers: Core Concepts (vocabulary primer), Class Design, Workflow, sem-conv attribute mapping, context propagation in embedded, Gremlin bytecode classification, SQL execution layer hook, OpenTelemetry logs integration, metrics integration, quick-start observability stack, SDK lifecycle for embedded vs server, listener registration and ordering, and the exception-isolation contract. The deep-mechanism content for the slow-query gate, the heartbeat sampler, the logs appender, the metrics bridge, and the Collector pipeline shape of the quick-start stack lives in the [`design-mechanics.md`](design-mechanics.md) companion file; the five corresponding sections here keep the TL;DR, the configuration entries operators read at configure time, and a Mechanism overview paragraph pointing into the companion.

## Core Concepts

This design introduces eleven load-bearing ideas. Each is named and used without re-definition later; if a downstream section references one, the relevant definition is here. Each entry pairs the new term with what it replaces, so the delta from the baseline is visible at a glance.

**Span.** An OpenTelemetry record covering one unit of work with a start timestamp, an end timestamp, a name, a kind (CLIENT / SERVER / INTERNAL / PRODUCER / CONSUMER), a status (OK / ERROR), and arbitrary key/value attributes. Replaces "nothing in YTDB" (no prior telemetry primitive). → §"Sem-conv attribute mapping" and §"Class Design".

**Trace and Context.** A trace is a tree of spans bound by a shared `traceId`; each child span carries a `parentSpanId`. `Context` is the OTel mechanism for propagating the current span through the call stack so that a span created inside a method automatically attaches as a child of the surrounding span. Replaces "no parent/child relationship between operations". → §"Context propagation in embedded".

**Listener registry (global).** A pair of `CopyOnWriteArrayList`s of `QueryMetricsListener` and `TransactionMetricsListener` instances held in a process-global `GlobalListenerRegistry` in `core` and exposed via static methods on `YourTracks` (the existing `final` utility class). Snapshotted by `FrontendTransactionImpl.beginInternal()` into per-TX fields before `txStartCounter` increments, so both Gremlin and native-SQL transactions share one fire path. Replaces "per-TX `withQueryListener` only", which made the config flag inert. → §"Listener registration and ordering".

**Sem-conv v1.33.0.** OpenTelemetry's stable semantic conventions for database client spans, dictating attribute names (`db.system.name`, `db.query.text`, etc.), their requirement levels (Required / Conditionally Required / Recommended / Opt-In), and the span-name fallback chain. Replaces "no vendor-neutral attribute schema". → §"Sem-conv attribute mapping".

**Query tagging and per-tag mode resolution.** An enum `QueryMonitoringMode` (co-located with `QueryMetricsListener` / `TransactionMetricsListener` in `internal/common/profiler/monitoring/`) selects timing precision per query: `LIGHTWEIGHT` reads from `GranularTicker` at ~10 ms granularity with no syscall on the hot path; `EXACT` reads from `Instant.now()` for the wall-clock start (ns / μs on JDK 21 Linux) and `System.nanoTime()` for the duration delta, paying two syscalls per measurement for sub-millisecond precision. **Each query resolves its mode independently from its tag** through a process-global `QueryMonitoringModeResolver`: rules configured at startup via `OPENTELEMETRY_QUERY_MODE_TAG_RULES` map tag matchers (exact / prefix / regex) to modes, first-wins; when no rule matches, the resolver falls back to the per-TX default set via `YTDBTransaction.withQueryMonitoringMode(...)`; when no per-TX default is set, the fallback is `LIGHTWEIGHT`. Tag source: Gremlin only, via `g.with(YTDBQueryConfigParam.querySummary, "X")`. SQL statements pass `Optional.empty()` for tag and resolve via the per-TX default — the resolver short-circuits on empty tag without walking the rule list. Two Gremlin traversals in the same transaction with different tags can use different modes; the commit fire site has no query tag and therefore uses the TX default. Replaces "always-EXACT timing" implied by the original design and the per-TX-snapshot scheme from earlier iterations of this design. → §"Query tagging and per-tag rule resolution", §"SQL execution layer hook", and §"Gremlin bytecode classification".

**Query source classification.** Two static-helper classifiers in `core` extract `db.operation.name` and `db.collection.name` for the two query sources YTDB supports. The Gremlin classifier dispatches by traversal shape: for translated traversals (Path A, post-[PR #1038](https://github.com/JetBrains/youtrackdb/pull/1038)) it reads `YTDBMatchPlanStep.getClassification()` — a precomputed two-field `Classification(operation, collection)` value (`operation="SELECT"` hardcoded for PR #1038 Phase 1 read patterns; `collection` from the first alias class in the pattern at translation time) which `GremlinToMatchStrategy` populates at boundary-step construction and YTDB-496 Track 1 adds to the boundary step (see §"YTDBMatchPlanStep classification field"); for fallback traversals (Path B, the native TinkerPop pipeline when the translator declined) it walks the TinkerPop `Bytecode` instruction list to resolve the first source step (`V`/`E`/`addV`/`addE`/`drop`) and the first `hasLabel(X)` argument. The SQL classifier reads the parsed `SQLStatement` subclass (SELECT / INSERT / UPDATE / DELETE / MATCH / DDL) and the target class from the FROM / INTO / UPDATE clause. Both return `Optional.empty()` when the query shape doesn't yield clean values. Called directly from the existing fire sites: `YTDBQueryMetricsStep` for Gremlin, and `InstrumentedSqlResultSet` for SQL (a session-boundary wrapper installed at the three `DatabaseSessionEmbedded` SQL entry points as the outermost wrapper of whatever the entry point would otherwise return; the wrapper's constructor captures the start clock and `close()` fires the listener; per §"Coordination with YTDB-820 cache contract" the wrapper covers `LocalResultSet` and `CachedResultSetView` inner types uniformly). No SPI or ServiceLoader; the call sites parse before invoking, so the classifiers piggyback on parsing that runs anyway. Replaces "raw sanitized query string only". → §"Gremlin bytecode classification" (Gremlin rules table) and §"SQL execution layer hook" (SQL rules table and statement-subclass dispatch).

**Slow-query threshold (per-tag).** A wall-clock duration in milliseconds resolved per-query from the same query tag that drives mode selection. Global default `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_MILLIS=0` (emit-all out of the box; operators opt into a positive threshold to drop fast successful queries); per-tag overrides via `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_TAG_RULES` go through a process-global `SlowQueryThresholdResolver` consuming `TagRule<Long>` on the same sealed interface that powers mode resolution. When the resolved threshold is greater than zero, `OTelQueryMetricsListener.queryFinished` returns early before any span allocation if `executionTimeNanos < thresholdNanos` and the query did not throw. Errors always emit regardless. The global default is read at OTel listener construction time; tag-rule resolution is per-query, so a long-running session can hit different thresholds for different tags. Replaces "all-or-nothing emission gated only on listener presence", which over-emitted on mostly-read workloads. → §"Slow-query threshold gating".

**Time-based query sampling (heartbeat).** A single global wall-clock interval in milliseconds. `OPENTELEMETRY_QUERY_HEARTBEAT_SAMPLE_MILLIS=0` (default, disabled); positive values emit one span per `N` ms regardless of duration, picked from whichever successful query finishes first after the interval elapses. The race for the "first query after the interval" slot is resolved by `AtomicLong.compareAndSet` on a per-listener `lastHeartbeatNanos` field, so under load exactly one query per window claims the heartbeat slot. No per-tag override: heartbeat samples the workload as a whole, and per-tag streams belong to downstream OTel pipeline filtering on `db.query.summary` if operators need them. Composes disjunctively with the slow-query gate (a query emits if either gate passes). Replaces "no sampling beyond the slow-query gate", which biased visibility toward latency outliers and left the fast-query workload invisible. → §"Time-based sampling".

**Explicit transaction tracking ID.** A host-provided string identifier passed to a transaction via `YTDBTransaction.withTrackingId(String)` (pre-existing on `YTDBTransaction` today). YTDB-496 lifts `getTrackingId()` from `YTDBTransaction` onto the `FrontendTransaction` interface so both fire sites read the same value through the interface without downcasting; Track 1 also moves the storage from `YTDBTransaction.trackingId` to `FrontendTransactionImpl` (one atomic commit) and rewires `withTrackingId(...)` to call the new package-private setter. Two accessors surface the value:

- `FrontendTransaction.getTrackingId(): String` — returns the explicit value when host set one, else falls back to `String.valueOf(getId())`. Used by non-OTel listeners and YTDB-internal logging that always want a stable string.
- `FrontendTransaction.getExplicitTrackingId(): Optional<String>` — returns `Optional.of(explicit)` when host set one via `withTrackingId(...)`, else `Optional.empty()`. The OTel listener uses this accessor to gate the `db.youtrackdb.transaction.tracking_id` span attribute: `details.getExplicitTrackingId().ifPresent(id -> span.setAttribute(...))`. Hosts that did NOT call the setter pay zero cardinality cost (attribute omitted). Hosts that DID call the setter get cross-system correlation; cardinality is the host's responsibility (a host templating a UUID per request blows cardinality consciously). No separate config flag — the explicit setter call IS the opt-in, by construction.

Both `QueryDetails.getTransactionTrackingId(): String` and `TransactionDetails.getTransactionTrackingId(): String` read through `getTrackingId()` for the always-stable fallback; `QueryDetails.getExplicitTrackingId(): Optional<String>` (new accessor) routes through `getExplicitTrackingId()` for OTel-side gating. → §"Class Design" and §"Listener registration and ordering".

**Log appender chokepoint.** YTDB's process-global `com.jetbrains.youtrackdb.internal.common.log.LogManager` extends `SLF4JLogManager`, whose single `log(...)` method funnels every log call from `core`, `embedded`, `server`, and the new `youtrackdb-opentelemetry` module into one SLF4J emit site at `SLF4JLogManager.log:98` (immediately before `logEventBuilder.log()`). The OTel module installs a `LogAppenderHook` (a new package-private interface in `core/.../common/log/`) on that emit site at SDK init via `SLF4JLogManager.installAppenderHook(LogAppenderHook)`, so every existing YTDB log call also feeds the OTel `LogRecordBuilder` pipeline without source-side changes and independent of which SLF4J binding is on the classpath (server mode runs on `slf4j-jdk14`; embedded hosts may bind Logback, `log4j-slf4j-impl`, or `slf4j-simple` — the hook fires for all of them because it sits below the facade, on the SLF4J side of every binding). The hook reads `Context.current()` at invocation time, so any log emitted inside a query- or transaction-listener span scope carries the active `traceId`/`spanId` automatically (hard-context correlation). Earlier iterations of this design attempted a JUL `Handler` on the root JUL logger, which catches records only when the SLF4J binding routes through JUL. The hook avoids that coupling. **Scope caveat**: the hook captures YTDB's own log calls only (every emit through `LogManager.instance()`). Third-party libraries that call SLF4J directly — Jackson, Netty, Guice, Lucene, the OTel exporter itself — are NOT captured. Operators wanting full coverage of host-process logs install the OTel ecosystem path (`opentelemetry-instrumentation-logback-appender` or its log4j equivalent) on their SLF4J binding alongside YTDB's hook; the recursive-cycle guards (per-thread re-entrance + `OPENTELEMETRY_LOGS_LOGGER_EXCLUSIONS` prefix filter) keep the OTel SDK's own logs from feeding back into YTDB's hook. Replaces "logs not integrated" (prior design scope). → §"OpenTelemetry logs integration".

**Profiler counter bridge.** YTDB's internal `Profiler.getMetricsRegistry()` is a process-global registry of `Metric<T>` instances (concrete shapes: `Gauge<T>`, `Stopwatch`, `TimeRate`, `Ratio`) updated by storage, cache, WAL, and transaction subsystems on their own threads, independent of OTel state. A new `OTelMetricsBridge` reads that registry through OTel async instruments (`ObservableLongCounter`, `ObservableLongGauge`, `ObservableDoubleHistogram`) at a `OPENTELEMETRY_METRICS_PERIOD_MILLIS`-driven cadence, surfacing a curated subset under sem-conv v1.33.0 DB metric names where the spec defines them as stable, plus the `youtrackdb.*` namespace for vendor-specific gauges. The bridge subscribes; it does not duplicate the underlying writer state. Replaces "metrics not integrated" (prior design scope after M34's logs addition). → §"Metrics integration".

## Class Design

```mermaid
classDiagram
    class QueryMetricsListener {
        <<interface>>
        +queryFinished(QueryDetails, long, long) void
    }
    class TransactionMetricsListener {
        <<interface>>
        +writeTransactionCommitted(TransactionDetails, long, long) void
        +writeTransactionFailed(TransactionDetails, long, long, Exception) void
    }
    class QueryDetails["QueryMetricsListener.QueryDetails"] {
        <<interface>>
        +getQuery() String
        +getQuerySummary() String
        +getTransactionTrackingId() String
        +getExplicitTrackingId() Optional~String~
        +getOperationName() Optional~String~
        +getCollectionName() Optional~String~
        +getDatabaseName() Optional~String~
        +getErrorType() Optional~String~
        +getQuerySource() Optional~QuerySource~
        +getParentContext() Optional~Context~
        +getStartedAtEpochNanos() long
        +getResultCount() OptionalLong
        +getWherePresent() Optional~Boolean~
        +getOrderPresent() Optional~Boolean~
        +getLimitPresent() Optional~Boolean~
        +getFromClassCount() Optional~Integer~
        +getStepCount() Optional~Integer~
        +getHasSubtraversal() Optional~Boolean~
        +getInvokedVia() Optional~String~
        +getCustomAttrs() Map~String,Object~
    }
    class QuerySource {
        <<enumeration>>
        GREMLIN
        SQL
    }
    class GremlinSpanLifecycleHook {
        <<interface>>
        +onFirstHasNext(QueryDetails, long startEpochNanos, Context parent) void
        +onClose(QueryDetails, long endEpochNanos) void
    }
    class YTDBQueryMetricsStep {
        +setLifecycleHook(GremlinSpanLifecycleHook) void
    }
    class TransactionDetails["TransactionMetricsListener.TransactionDetails"] {
        <<interface>>
        +getTransactionTrackingId() String
        +getDatabaseName() Optional~String~
        +getCommittedAtEpochNanos() long
    }
    class OTelQueryMetricsListener {
        -Tracer tracer
        -SpanKind clientKind
        -long defaultThresholdNanos
        -long defaultHeartbeatNanos
        -AtomicLong lastHeartbeatNanos
        +queryFinished(QueryDetails, long, long) void
    }
    class OTelTransactionMetricsListener {
        -Tracer tracer
        -SpanKind clientKind
        -long defaultCommitThresholdNanos
        +writeTransactionCommitted(TransactionDetails, long, long) void
        +writeTransactionFailed(TransactionDetails, long, long, Exception) void
    }
    class LogAppenderHook {
        <<interface>>
        +onLog(String requesterName, String dbName, org.slf4j.event.Level level, String formatString, String formattedMessage, Throwable thrown, long eventEpochNanos) void
    }
    class OTelLogAppender {
        -Logger otelLogger
        -int minSeverityNumber
        -boolean includeMessageBody
        -ThreadLocal~Boolean~ reentrant
        +onLog(String requesterName, String dbName, org.slf4j.event.Level level, String formatString, String formattedMessage, Throwable thrown, long eventEpochNanos) void
    }
    class OTelMetricsBridge {
        -Meter meter
        -ScheduledExecutorService scheduler
        -Set~MetricGroup~ includedGroups
        -Map~String,ObservableInstrument~ registered
        +start() void
        +stop() void
        +refresh() void
    }
    class YouTrackDBOpenTelemetry {
        -OpenTelemetry openTelemetry
        -boolean ownedByYtdb
        -boolean serverMode
        -Tracer tracer
        +setOpenTelemetry(OpenTelemetry) void
        ~setOpenTelemetry(OpenTelemetry, boolean ownedByYtdb, boolean serverMode) void
        +shutdown() void
        ~tracer() Tracer
        ~clientKind() SpanKind
    }
    class GremlinBytecodeClassifier {
        <<utility>>
        +classify(Traversal) Classification$
    }
    class YTDBMatchPlanStep["YTDBMatchPlanStep (PR #1038, extended by YTDB-496 Track 1)"] {
        +getClassification() Classification
    }
    class SqlSyntaxClassifier {
        <<utility>>
        +classify(SQLStatement) Classification$
    }
    class SqlSanitizer {
        +sanitize(String rawSql) String
    }
    class Classification {
        +operationName Optional~String~
        +collectionName Optional~String~
        +wherePresent Optional~Boolean~
        +orderPresent Optional~Boolean~
        +limitPresent Optional~Boolean~
        +fromClassCount Optional~Integer~
        +stepCount Optional~Integer~
        +hasSubtraversal Optional~Boolean~
        +customAttrs Map~String,Object~
    }
    class QueryMonitoringModeResolver {
        -List~TagRule~ rules
        -ConcurrentHashMap~String,QueryMonitoringMode~ cache
        +resolve(Optional~String~ tag, QueryMonitoringMode txDefault) QueryMonitoringMode
        +global() QueryMonitoringModeResolver$
    }
    class SlowQueryThresholdResolver {
        -List~TagRule~ rules
        -ConcurrentHashMap~String,Long~ cache
        +resolve(Optional~String~ tag, long defaultNanos) long
        +global() SlowQueryThresholdResolver$
    }
    class TagRule {
        <<sealed interface>>
        +matches(String tag) boolean
        +value() T
    }
    class TagRuleExact["TagRule.Exact"] {
        -String tag
        -T value
    }
    class TagRulePrefix["TagRule.Prefix"] {
        -String prefix
        -T value
    }
    class TagRuleRegex["TagRule.Regex"] {
        -Pattern pattern
        -T value
    }
    class FrontendTransaction {
        <<interface>>
        +getDefaultQueryMonitoringMode() QueryMonitoringMode
        +resolveQueryMonitoringMode(Optional~String~ tag) QueryMonitoringMode
        +resolveSlowQueryThresholdNanos(Optional~String~ tag) long
        +getTrackingId() String
        +getExplicitTrackingId() Optional~String~
        +iterateAllQueryListeners() Iterable~QueryMetricsListener~
    }
    class YTDBTransaction {
        +withQueryMonitoringMode(QueryMonitoringMode mode) YTDBTransaction
        +withTrackingId(String trackingId) YTDBTransaction
        +withQueryListener(QueryMetricsListener listener) YTDBTransaction
        +withTransactionListener(TransactionMetricsListener listener) YTDBTransaction
    }
    class InstrumentedSqlResultSet {
        -ResultSet inner
        -SQLStatement statement
        -String rawSql
        -Object args
        -FrontendTransaction currentTx
        -Iterable~QueryMetricsListener~ listeners
        -QueryMonitoringMode mode
        -io.opentelemetry.context.Context capturedContext
        -long startMillis
        -long startEpochNanos
        -long startNanoTime
        -long rowCount
        -Throwable caughtError
        +wrapIfListening(ResultSet, SQLStatement, String, Object, FrontendTransaction) ResultSet$
        +hasNext() boolean
        +next() Result
        +close() void
    }
    class OpenTelemetryServerPlugin {
        +onAfterActivate() void
        +onBeforeDeactivate() void
    }
    QueryMetricsListener ..> QueryDetails : nests
    TransactionMetricsListener ..> TransactionDetails : nests
    QueryMetricsListener <|.. OTelQueryMetricsListener
    TransactionMetricsListener <|.. OTelTransactionMetricsListener
    OTelQueryMetricsListener --> YouTrackDBOpenTelemetry : tracer()
    OTelTransactionMetricsListener --> YouTrackDBOpenTelemetry : tracer()
    OTelQueryMetricsListener --> QueryDetails : reads operation / collection / namespace
    GremlinBytecodeClassifier --> Classification : returns
    GremlinBytecodeClassifier ..> YTDBMatchPlanStep : reads precomputed Classification for Path A
    SqlSyntaxClassifier --> Classification : returns
    TagRule <|.. TagRuleExact
    TagRule <|.. TagRulePrefix
    TagRule <|.. TagRuleRegex
    QueryMonitoringModeResolver --> TagRule : holds list of
    SlowQueryThresholdResolver --> TagRule : holds list of
    FrontendTransaction --> QueryMonitoringModeResolver : resolveQueryMonitoringMode delegates to global()
    FrontendTransaction --> SlowQueryThresholdResolver : resolveSlowQueryThresholdNanos delegates to global()
    YTDBTransaction --> FrontendTransaction : fluent builder stores tracking-id and mode on
    YouTrackDBOpenTelemetry --> OTelQueryMetricsListener : populates defaultThresholdNanos and defaultHeartbeatNanos from config
    OpenTelemetryServerPlugin --> YouTrackDBOpenTelemetry : setOpenTelemetry(.., ownedByYtdb=true) / shutdown
    LogAppenderHook <|.. OTelLogAppender
    OTelLogAppender --> YouTrackDBOpenTelemetry : loggerProvider()
    OTelMetricsBridge --> YouTrackDBOpenTelemetry : meter()
    InstrumentedSqlResultSet ..> QueryDetails : builds at close()
    InstrumentedSqlResultSet ..> SqlSyntaxClassifier : lazy classify
    InstrumentedSqlResultSet ..> SqlSanitizer : lazy sanitize
    InstrumentedSqlResultSet ..> FrontendTransaction : iterateAllQueryListeners + resolveQueryMonitoringMode
    QueryDetails --> QuerySource : optional source
    YTDBQueryMetricsStep ..> GremlinSpanLifecycleHook : invokes onFirstHasNext / onClose
    YouTrackDBOpenTelemetry --> GremlinSpanLifecycleHook : installs OTel impl on each YTDBQueryMetricsStep
```

The diagram covers the production classes the design introduces. Three interfaces in `core` are extended: `TransactionMetricsListener` and `QueryDetails` gain default methods (among them `QueryDetails.getParentContext(): Optional<Context>`, carrying the `io.opentelemetry.context.Context` the SQL fire site captured at wrapper construction so the listener parents the SQL span explicitly; the accessor is the only OTel type on the `QueryDetails` SPI, and `core` already compiles against `opentelemetry-context` for the wrapper's `capturedContext` field, so it adds no module dependency); `QueryMetricsListener` itself stays unchanged but is consumed by a new impl; `FrontendTransaction` gains `getDefaultQueryMonitoringMode()` (renamed from the earlier `getQueryMonitoringMode()`), `resolveQueryMonitoringMode(Optional<String> tag)`, `resolveSlowQueryThresholdNanos(Optional<String> tag)`, `getTrackingId(): String` (lifted from `YTDBTransaction`; returns the explicit ID set via `YTDBTransaction.withTrackingId(...)` when present, else `String.valueOf(getId())`), and `iterateAllQueryListeners()`. `YTDBTransaction` already exposes the four builder-style methods this design relies on (`withQueryMonitoringMode(mode)`, `withTrackingId(id)`, `withQueryListener(listener)`, `withTransactionListener(listener)`); YTDB-496 changes the `withQueryListener` / `withTransactionListener` storage from single-slot to additive (see §"Listener registration and ordering") but leaves the method signatures unchanged. Each method returns `this` so they chain.

Two new static-utility classes (`GremlinBytecodeClassifier`, `SqlSyntaxClassifier`) and one value record (`Classification`) land in `core` next to the existing parsing infrastructure. Gremlin's classifier dispatches by traversal shape: for translated traversals (Path A, when the step list contains a `YTDBMatchPlanStep` per [PR #1038](https://github.com/JetBrains/youtrackdb/pull/1038)) it reads `YTDBMatchPlanStep.getClassification()` (a lightweight 2-field value record `GremlinToMatchStrategy` populated at translation time — see §"YTDBMatchPlanStep classification field"); for fallback traversals (Path B, when the translator declined) it walks the bytecode the same way `YTDBQueryMetricsStep.produceScript()` already does. The SQL classifier dispatches on the `SQLStatement` AST that the SQL execution entry points (`db.query()` / `db.command()` / `db.execute()`) already produce via `SQLEngine.parse(...)` before the entry point returns. The classifiers are pure functions, called directly from the existing fire sites; no SPI, no ServiceLoader.

`InstrumentedSqlResultSet` lands in `internal.core.db` next to `DatabaseSessionEmbedded` (`LocalResultSetLifecycleDecorator` and `LocalResultSet` sit in the sibling `internal.core.sql.parser` package). It implements `ResultSet`, holds a single `inner: ResultSet` reference plus snapshot state (start clock, listener iterable, classifier inputs), and delegates `hasNext()` / `next()` to `inner` while incrementing a row counter and capturing any thrown exception. `wrapIfListening(...)` is the static factory the three entry points call: when `currentTx.iterateAllQueryListeners()` is empty it returns `inner` directly (zero-overhead fast path); otherwise it allocates the wrapper, captures `Context.current()`, and stores the snapshot. `LocalResultSet.java` itself stays unchanged — the wrapper is the only new file Track 4 ships on the result-set side.

Two process-global resolvers (both in `core/.../profiler/monitoring/`) reuse the same sealed `TagRule<T>` interface: `QueryMonitoringModeResolver` walks a `List<TagRule<QueryMonitoringMode>>` parsed once at startup from `OPENTELEMETRY_QUERY_MODE_TAG_RULES`; `SlowQueryThresholdResolver` walks a `List<TagRule<Long>>` parsed from `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_TAG_RULES`. Both cache resolved `(tag → value)` pairs in a `ConcurrentHashMap` for cheap repeat lookups. The sealed `TagRule<T>` interface has three concrete shapes (`Exact`, `Prefix`, `Regex`) and is generic so the resolvers share the matcher hierarchy. The heartbeat gate has no resolver because it carries no per-tag override (see §"Time-based sampling"). Six classes in the new OTel module implement the integration (`OTelQueryMetricsListener`, `OTelTransactionMetricsListener`, `OTelLogAppender`, `OTelMetricsBridge`, `YouTrackDBOpenTelemetry`, `OpenTelemetryServerPlugin`), keeping the static dependency arrow one-way (`youtrackdb-opentelemetry` → `core`). `SqlSanitizer` moved to `core` alongside `SqlSyntaxClassifier` because both walk the parser-output AST that lives in `core`.

Both OTel listeners take a `SpanKind clientKind` constructor argument that selects between CLIENT and INTERNAL for the query span and the standalone commit span. INTERNAL is used when YTDB runs in-process with the host (embedded), CLIENT when YTDB runs as a standalone server process and the host is a network client. `YouTrackDBOpenTelemetry` resolves `clientKind` from how the SDK was wired: the `OpenTelemetryServerPlugin` invokes the package-private 3-arg variant `setOpenTelemetry(otel, ownedByYtdb=true, serverMode=true)` so CLIENT propagates to both listeners; every embedded entry point (host setter, `GlobalOpenTelemetry.get()` fallback, YTDB auto-configure) defaults `serverMode=false` so INTERNAL applies. The two flags carry separate concerns: `ownedByYtdb` controls whether `shutdown()` closes the SDK; `serverMode` controls the CLIENT/INTERNAL split on emitted spans. See §"Sem-conv attribute mapping" for the sem-conv rule that drives this choice.

The producer copies each `Classification(operationName, collectionName)` value into the `QueryDetails` accessors before the listener fires, plus `QuerySource.GREMLIN` for the Gremlin fire site and `QuerySource.SQL` for the SQL fire site so `queryFinished` reads `details.getQuerySource()` to route between enrich-existing-span and create-new-span paths. No thread-local suppression mechanism is needed for nested SQL spans inside Gremlin traversals: a `GremlinSpanLifecycleHook` installed on each `YTDBQueryMetricsStep` opens the Gremlin span at first `hasNext()` and ends it at `close()` after `queryFinished` returns; inner SQL spans emitted by `InstrumentedSqlResultSet.close()` read `Context.current()` at construction so they become children of the active Gremlin span automatically. The hook interface lives in `core` (Track 1) next to `YTDBQueryMetricsStep`; the OTel-module impl (Track 3) installs itself via a `FinalizationStrategy` that runs after `YTDBQueryMetricsStrategy` and calls `step.setLifecycleHook(...)` on each injected step. See §"Context propagation in embedded" for the four-step lifecycle and parent-child relationship.

`OTelQueryMetricsListener` and `OTelTransactionMetricsListener` are independent — the query listener takes its parent context from `Context.current()` at fire time (the host's active span, when wrapped), not from any TX-side state. The two listeners are wired alongside each other by `YouTrackDBOpenTelemetry` but share no fields or callbacks. Multiple OTel facades coexisting in the same JVM (test fixtures spinning up a fresh SDK per test method) are independent for the same reason.

`OTelQueryMetricsListener` takes two additional constructor arguments alongside the existing `Tracer` and `SpanKind` pair: `long defaultThresholdNanos` populated by `YouTrackDBOpenTelemetry` from `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_MILLIS` (default `0` = emit-all, multiplied to nanoseconds) and `long defaultHeartbeatNanos` populated from `OPENTELEMETRY_QUERY_HEARTBEAT_SAMPLE_MILLIS` (default `0`, disabled). Both are read once at SDK init. The listener also owns an `AtomicLong lastHeartbeatNanos` field initialized to `0` and updated by `compareAndSet` when a query claims the heartbeat slot.

At each fire the listener evaluates two gates. First the heartbeat gate: if `defaultHeartbeatNanos > 0` and `now - lastHeartbeatNanos >= defaultHeartbeatNanos`, the listener CAS-claims the slot and emits. Otherwise the slow-query gate runs: `SlowQueryThresholdResolver.global().resolve(querySummary, defaultThresholdNanos)`; if the threshold is positive and `executionTimeNanos < thresholdNanos`, the listener returns early. On the SQL path the early return skips all span allocation; on the Gremlin path the span is already allocated by the lifecycle hook at first `hasNext()` (parent-child propagation requires it during iteration), so an early return leaves a "skinny" Gremlin span without sem-conv attributes — operators that want to discard these install a `SpanProcessor` filtering on the absence of `db.system.name`. Errors bypass both gates through `QueryDetails.getErrorType()`, populated by both fire sites from the caught exception's class FQN (see §"Slow-query threshold gating" and §"Time-based sampling" for the gate pseudocode and the error-bypass contract).

Class Design is a structural reference section; edge cases for each component live in the mechanism sections this section points to.

### References
- D-records: D1, D2, D5, D8, D9
- Invariants: Span kind by role
- Mechanics: none (single-file design)

## Workflow

The three diagrams below show, in order, a query span attaching to the host's active span, a standalone commit span emitted for a write transaction, and the server-mode SDK boot/shutdown sequence driven by `ServerLifecycleListener` callbacks. The first two capture the synchronous-on-calling-thread property the design relies on for `Context.current()` to resolve to the host's span; the cross-section §"Context propagation in embedded" carries the prose argument.

### Query span lifecycle in embedded

```mermaid
sequenceDiagram
    participant Host as Host app code
    participant TS as host Tracer
    participant G as GraphTraversalSource
    participant Step as YTDBQueryMetricsStep
    participant HOOK as GremlinSpanLifecycleHook (OTel impl)
    participant CLS as GremlinBytecodeClassifier
    participant OQL as OTelQueryMetricsListener
    participant TR as OTel Tracer
    participant EXP as Span exporter

    Host->>TS: spanBuilder("host-op").startSpan()
    Host->>TS: span.makeCurrent()
    Host->>G: g.V().hasLabel("User").toList()
    G->>Step: first hasNext()
    Step->>HOOK: onFirstHasNext(details, startEpochNanos, Context.current())
    HOOK->>TR: spanBuilder(details.getQuerySummary() else "youtrackdb").setParent(parent).setStartTimestamp(startEpochNanos, NS).startSpan()
    HOOK->>TR: scope = span.makeCurrent()
    G->>Step: traversal close
    Step->>CLS: classify(traversal)
    CLS-->>Step: Classification(SELECT, User)
    Step->>OQL: queryFinished(details, startMs, durNs)
    OQL->>TR: Span.fromContext(Context.current()).setAttribute(db.operation.name, db.collection.name)
    Note over OQL,TR: GREMLIN path enriches the hook-opened span.<br/>It does NOT call spanBuilder() or span.end()
    Step->>HOOK: onClose(details, endEpochNanos)
    HOOK->>TR: span.end(endEpochNanos, NS)
    TR-->>EXP: span data
    Host->>TS: hostSpan.end()
    TS-->>EXP: span data
```

The flow shows that the host code's active span becomes the parent of the YTDB query span automatically, because `Context.current()` resolves on the same thread the host called `makeCurrent()` on. The `GremlinSpanLifecycleHook` (the OTel impl installed on each `YTDBQueryMetricsStep`) opens the Gremlin span at the step's first `hasNext()` and ends it at `close()`. The classifier runs in `YTDBQueryMetricsStep` before the listener fires, populating `QueryDetails.getOperationName()` and `getCollectionName()`; `OTelQueryMetricsListener.queryFinished` reads them and sets the `db.operation.name` / `db.collection.name` attributes on that hook-opened span rather than creating a span of its own (see §"Context propagation in embedded").

The SQL path is symmetric. `InstrumentedSqlResultSet`, the session-boundary wrapper installed at each `db.query()` / `db.command()` / `db.execute()` entry point as the final return-statement wrapper (after any PR #1077 cache-view construction and any `queryStartedLifecycle(...)` decoration on bypass paths), plays the role of `YTDBQueryMetricsStep` as the listener fire site: the constructor captures the start clock right after the entry point's `statement.execute(...)` call returns, and `close()` fires the listener. `SqlSyntaxClassifier` replaces `GremlinBytecodeClassifier` for the accessor population. Span name construction, attribute mapping, and parent-context resolution are identical from the listener's point of view, because the listener reads `QueryDetails` accessors that have already been populated by whichever classifier ran. The wrapper holds the inner `ResultSet` reference (`LocalResultSet` today; `LocalResultSet` or `CachedResultSetView` once YTDB-820 lands) without inspecting its concrete type for fire-site purposes. See §"SQL execution layer hook" for the SQL-side anatomy.

### Commit span emission for write transactions

```mermaid
sequenceDiagram
    participant Host as Host code
    participant TX as FrontendTransactionImpl
    participant OTL as OTelTransactionMetricsListener
    participant TR as OTel Tracer

    Host->>TX: tx.commit() (write tx)
    TX->>OTL: writeTransactionCommitted(details, commitMs, commitNs)
    OTL->>TR: spanBuilder(spanName).setSpanKind(clientKind).setStartTimestamp(details.committedAtEpochNanos, NS).setParent(Context.current()).startSpan()
    Note over OTL,TR: spanName = "commit " + dbName when getDatabaseName() is present,<br/>else "commit". Tracking ID surfaces as the db.youtrackdb.transaction.tracking_id<br/>attribute (gated by getExplicitTrackingId().isPresent()), NOT in span name.
    OTL->>TR: span.end(details.committedAtEpochNanos + commitNs, NS)
```

`writeTransactionCommitted` fires only for write transactions per existing YTDB semantics. A read-only transaction's implicit close path emits nothing on the TX listener and therefore no commit span. The participant box represents `FrontendTransactionImpl` because the listener fire happens inside `notifyMetricsListener()` on the committing thread, and `YTDBTransaction.commit()` delegates to it through the Gremlin and native-SQL paths alike. The commit span takes its parent from `Context.current()` (host span if the host wrapped the commit, root otherwise); no YTDB-internal TX wrapper span is created. **Span name is bounded-cardinality** per sem-conv §"Span name SHOULD be of low cardinality": `"commit"` when no database name is available, `"commit " + dbName` when `TransactionDetails.getDatabaseName()` returns a value and `OPENTELEMETRY_COMMIT_SPAN_NAME_INCLUDES_DBNAME` is `true` (the default). Tracking ID is NEVER included in the span name — a monotonic-counter fallback or per-request UUID would blow span-name cardinality the same way it would blow attribute-value cardinality. The tracking ID surfaces only as the `db.youtrackdb.transaction.tracking_id` attribute, and only when the host explicitly called `withTrackingId(...)` (gated by `getExplicitTrackingId().isPresent()` per D17). `writeTransactionFailed` follows the same shape with `error.type` populated and span status set to ERROR.

**Edge case: multi-tenant hosts with thousands of databases.** Default `"commit " + dbName` keeps span-name cardinality bounded for typical deployments (tens to hundreds of databases). Self-hosted multi-tenant SaaS where every tenant owns a separate YouTrackDB database can grow span-name cardinality into the thousands, which strains some trace backends (Tempo, Jaeger) whose default span-name aggregation paths assume low cardinality. Operators in that situation set `OPENTELEMETRY_COMMIT_SPAN_NAME_INCLUDES_DBNAME=false` to drop dbName from the span name; the database identity stays available as the `db.namespace` attribute, where trace backends index it cheaply and filter on it on demand. Default remains `true` because most deployments benefit from per-database span names in the viewer.

### Server-mode SDK lifecycle

```mermaid
sequenceDiagram
    participant Main as ServerMain.create()
    participant Srv as YouTrackDBServer
    participant Plugin as OpenTelemetryServerPlugin
    participant Cfg as GlobalConfiguration
    participant Sdk as AutoConfiguredSdk
    participant Fac as YouTrackDBOpenTelemetry

    Main->>Srv: activate()
    Srv->>Plugin: onAfterActivate()
    Plugin->>Cfg: OPENTELEMETRY_ENABLED?
    alt enabled
        Plugin->>Cfg: read endpoint, protocol, service.name
        Plugin->>Sdk: builder().setEndpoint(...).build()
        Plugin->>Fac: setOpenTelemetry(sdk.openTelemetry, ownedByYtdb=true, serverMode=true)
    end
    Note over Srv: server runs, transactions emit spans
    Main->>Srv: shutdown()
    Srv->>Plugin: onBeforeDeactivate()
    Plugin->>Fac: shutdown()
    Fac->>Sdk: close()
    Note over Sdk: flushes pending spans
```

The plugin is ServiceLoader-discovered. When the new module is not on the classpath, the plugin doesn't load and the server runs with no OTel cost.

Workflow is a sequence-diagram reference section; per-mechanism edge cases live in the sections each diagram points to.

### References
- D-records: D1, D2, D4
- Mechanics: none

## Sem-conv attribute mapping

**TL;DR.** Every emitted query span carries `db.system.name="youtrackdb"` plus a fallback chain of sem-conv attributes filled in to the extent the source allows. Span name follows the v1.33.0 chain: `db.query.summary` → `db.operation.name db.collection.name` → `db.collection.name` → `db.system.name`. Query text comes from the source-appropriate sanitizer: `ValueAnonymizingTypeTranslator` for Gremlin (existing), `SqlSanitizer` for SQL (Track 4).

The full mapping per attribute:

| Attribute | Requirement | Source | Notes |
|---|---|---|---|
| `db.system.name` | Required | constant `"youtrackdb"` | sem-conv §"Notes" allows custom value when not on well-known list |
| `db.namespace` | Conditionally Required | `QueryDetails.getDatabaseName()` (Gremlin: `session.getDatabaseName()` at the `YTDBQueryMetricsStep` fire site; SQL: same at the `InstrumentedSqlResultSet` fire site, populated when the wrapper is constructed) | Track 1 adds the default accessor on both `QueryDetails` and `TransactionDetails`; omitted when the session has no name |
| `db.collection.name` | Conditionally Required | classifier result `.collectionName` | absent for multi-class traversals / anonymous SQL FROM subqueries |
| `db.operation.name` | Conditionally Required | classifier result `.operationName` | one of `SELECT` / `INSERT` / `UPDATE` / `DELETE` / `MATCH` / `CREATE` / `ALTER` / `DROP` |
| `db.query.text` | Recommended | `QueryDetails.getQuery()` | already sanitized: Gremlin via `ValueAnonymizingTypeTranslator`, SQL via `SqlSanitizer` |
| `db.query.summary` | Recommended | `QueryDetails.getQuerySummary()` if set, else `"{operation} {collection}"` if both present | client-provided summary wins |
| `db.response.status_code` | Conditionally Required | YTDB error code if available | currently no canonical YTDB error-code field; omitted in YTDB-496 |
| `error.type` | Conditionally Required (on failure) | exception class FQN | set on the standalone commit span at `writeTransactionFailed`, on the query span when `statement.execute(...)` throws; also exposed via `QueryDetails.getErrorType()` so the slow-query threshold gate (§"Slow-query threshold gating") can bypass on error |
| `server.address` / `server.port` | Recommended | from server config in server mode | embedded mode omits |
| `db.response.returned_rows` | Opt-In | SQL: `QueryDetails.getResultCount()`, populated by `InstrumentedSqlResultSet` incrementing a row counter on each `next()` that returned a result; Gremlin: omitted in YTDB-496 (would require counting traversal results, deferred) | counter lives on the wrapper, so the count is correct for both `LocalResultSet` and `CachedResultSetView` inner result-sets per §"Coordination with YTDB-820 cache contract" |

Span name fallback examples. Gremlin: a query labeled with `g.with(YTDBQueryConfigParam.querySummary, "findActiveUsers")...` produces `findActiveUsers`. An unlabeled `g.V().hasLabel("User").has("active", true).toList()` produces `SELECT User`. SQL: `db.command("SELECT FROM User WHERE active = true")` produces `SELECT User`. `db.command("MATCH {class:User, as:u}-knows->{class:User, as:f} RETURN u, f")` produces `MATCH User`. A shape that defies classification (Gremlin `g.V().union(...).path()` or SQL `SELECT FROM (SELECT FROM ...)`) produces `youtrackdb`.

Gremlin DSL passthrough (`g.yql(...)` / `g.command(...)`) examples per D40:

- `g.yql("SELECT FROM User WHERE name = ?", "Alice")`: outer Gremlin span produces `SELECT User` (extracted from `CallStep.parameters["command"]` via `SqlSyntaxClassifier`), `db.query.text = "SELECT FROM User WHERE name = ?"` (sanitized), `db.youtrackdb.gremlin.dsl_method = "yql"`, `db.youtrackdb.gremlin.statement_count = 1`. Inner SQL span carries identical `db.*` attributes plus `db.youtrackdb.sql.invoked_via = "gremlin_dsl"`. Both attach to `Context.current()` parent-child via D20.
- `g.command("CREATE INDEX User.email UNIQUE")` (DDL): outer Gremlin span produces `CREATE User` (DDL routes through `acquireSession()` + `schemaSession.command(SQLStatement, Map)` → `executeInternal()` per D39). `db.youtrackdb.gremlin.dsl_method = "command"`. Inner SQL span emits with `db.operation.name = "CREATE"`, `db.collection.name = "User"`, sanitized DDL unchanged.
- `g.yql("BEGIN").yql("INSERT INTO Order SET amount = ?", 100).yql("COMMIT")` (chain): outer Gremlin span produces `INSERT Order` (first non-administrative statement), `db.youtrackdb.gremlin.statement_count = 3`, `db.youtrackdb.gremlin.has_transaction_control = true`, `db.youtrackdb.gremlin.statements_summary = "BEGIN; INSERT Order; COMMIT"`. One inner SQL span for INSERT (BEGIN and COMMIT short-circuit in `executeCommand` before reaching `session.execute(SQLStatement, Map)`), plus a standalone commit span via the existing `TransactionMetricsListener` (D3).
- `g.V().has("name", "Alice").yql("UPDATE Order SET status = ?", "shipped")` (mixed shape): outer Gremlin span classified by normal Path B graph-step walk (e.g., `SELECT V`), `db.youtrackdb.gremlin.has_graph_steps = true`, `db.youtrackdb.gremlin.statement_count = 1`, `db.youtrackdb.gremlin.embedded_sql.0 = "UPDATE Order SET status = ?"`. One inner SQL span per traverser invocation of the yql step.
- `g.yql("BEGIN")` standalone: outer Gremlin span produces `BEGIN`, `db.youtrackdb.gremlin.has_transaction_control = true`, no inner SQL span (administrative short-circuit).
- `g.yql("")` empty command: outer Gremlin span produces `youtrackdb` (catch-all), `db.youtrackdb.gremlin.statement_count = 0`, `db.youtrackdb.gremlin.no_op = true`; no inner SQL span (YTDBCommandService short-circuits at `command.isEmpty()`).

Span kinds per role follow sem-conv v1.33.0 §"Span kind", which mandates CLIENT for over-network DB calls and INTERNAL for in-process and in-memory database libraries. YTDB satisfies both definitions in different deployments, so the kind is mode-aware: in embedded mode the query span and the standalone commit span are INTERNAL (YTDB runs in-process with the host); in server mode they are CLIENT (YTDB runs as a separate process the host reaches over the network). No SERVER / PRODUCER / CONSUMER spans are emitted by YTDB, and no YTDB-side INTERNAL wrapper span parents the query or commit spans — both attach directly to `Context.current()`. Track 6a's listener tests (`OTelGremlinQueryTest`, `OTelSqlQueryTest`, `OTelTransactionMetricsListenerTest`) parametrize over `clientKind` so each test exercises both INTERNAL (embedded default) and CLIENT (server-plugin path), asserting the positive cases on `SpanData.getKind()` and the negative case (no SERVER / PRODUCER / CONSUMER spans) against the in-memory exporter.

### YouTrackDB vendor attributes (intro)

**TL;DR.** OpenTelemetry's `db.<system>.*` prefix carries DB-implementation-specific structural fields beyond the standard `db.*` set; YTDB-496 reserves the `db.youtrackdb.*` namespace and ships an initial six-key set populated by the existing classifiers (`GremlinBytecodeClassifier`, `SqlSyntaxClassifier`) during the same parser-output walk that already extracts operation and collection. Values are deliberately constrained to booleans and small integers so trace consumers can group and filter queries by structural shape without paying for high-cardinality storage. Higher-resolution fields (predicate values, sort columns, property keys, per-operator timing) stay out of MVP and ship in a follow-up ticket once production demand surfaces.

Initial attribute set (MVP):

| Attribute | Source | Cardinality | Notes |
|---|---|---|---|
| `db.youtrackdb.where_present` | SQL: presence of WHERE / Gremlin: presence of `has(...)` / `where(...)` step | boolean | filter trace by "queries with predicates" vs unconditional scans |
| `db.youtrackdb.order_present` | SQL: presence of ORDER BY / Gremlin: presence of `order()` step | boolean | filter by "sorted queries" |
| `db.youtrackdb.limit_present` | SQL: presence of LIMIT / Gremlin: presence of `limit(...)` / `range(...)` / `tail(...)` step | boolean | filter by "bounded queries" |
| `db.youtrackdb.from_class_count` | SQL: count of FROM targets / Gremlin: count of top-level `V(...)` / `E(...)` start steps | small int (typically 1-3) | flag multi-target queries |
| `db.youtrackdb.step_count` | Gremlin only: count of top-level bytecode instructions | small int (typically 1-20) | rough complexity proxy; SQL omits |
| `db.youtrackdb.has_subtraversal` | Gremlin only: presence of any `__.*` sub-traversal or `match(...)` pattern | boolean | flag composite Gremlin shapes; SQL omits |
| `db.youtrackdb.gremlin.path` | Gremlin only: `"A"` when the classifier dispatched to the translated boundary step (`YTDBMatchPlanStep` present), `"B"` when the bytecode walk fired (translator declined) | enum (2 values) | distinguishes translated vs fallback traversals; lets dashboards filter Path B parent-plus-child duration double-counts without resorting to `parent_span_id IS NULL` heuristics |
| `db.youtrackdb.transaction.tracking_id` | `FrontendTransaction.getExplicitTrackingId(): Optional<String>` — emits attribute iff `Optional.isPresent()`, i.e. iff the host called `withTrackingId(...)` | bounded by host discipline (the host's chosen ID string; host that templates UUIDs per request blows cardinality consciously) | the default fallback `String.valueOf(getId())` is NEVER emitted as an attribute — the OTel listener reads `getExplicitTrackingId()` and skips the attribute when `Optional.empty()`. No config flag: the explicit setter call IS the opt-in, by construction. |
Cardinality policy. Every attribute in `db.youtrackdb.*` MUST be bounded: boolean, small integer (under ~20 distinct values), enum, or bounded-by-host-discipline (the host's explicit choice carries the cardinality risk consciously). The bound applies to the *attribute value*, not to the *attribute key* (the key set is closed, defined in this table). Trace backends typically index per attribute key per distinct value, so an unbounded value range translates directly to backend storage and query cost. The `db.youtrackdb.transaction.tracking_id` attribute is bounded-by-host-discipline: hosts who set a low-cardinality ID (`"req-batch-2026-06-02"`) get safe correlation; hosts who set a UUID per request blow cardinality and own that choice.

Advisor-foundation namespace (`db.youtrackdb.advisor.*`). A reserved sub-namespace carrying low-cardinality boolean / small-int flags that an advisory dashboard (Phase 2 follow-up per D42) consumes to recommend operator-tunable settings. The flags are populated by the same classifier walk as the structural attributes above and follow the same bounded-cardinality policy. Initial set:

| Attribute | Source | Cardinality | Recommendation downstream |
|---|---|---|---|
| `db.youtrackdb.advisor.full_scan` | SQL: target class has no index covering the WHERE predicate / Gremlin Path B: no index step lands in the bytecode walk | boolean | "Consider adding an index on `<class>.<property>`" |
| `db.youtrackdb.advisor.cross_class_count` | SQL: multi-target FROM without join hints / Gremlin: multiple `V(...)` start steps | small int (0 = single-class baseline, ≥2 = multi-class candidate for split) | "Cross-class query may benefit from splitting into two indexed queries" |

These flags carry zero implementation cost beyond the existing classifier walk (the `Classification` record gains two additional optional fields per D19's future-extension policy). The Phase 2 advisory dashboard reads them through standard span-attribute filtering and synthesizes recommendations; until then, operators with custom dashboards can already filter on these attributes to identify problematic queries.

Out of MVP (high-cardinality, deferred to follow-up):

- specific predicate values (`age > 30`, `name = "Alice"`) — every distinct literal blows up cardinality
- specific ORDER BY columns or `by(...)` keys — user-controlled, schema-dependent
- specific property keys read via `values(...)` or projection — same cardinality concern as ORDER BY keys
- full execution-plan structure (per-operator timing, per-step row counts) — already covered by the § Non-Goals bullet on per-operator timing; the follow-up ticket sources data from `SQLProfiler` and emits span events rather than span attributes to avoid polluting the per-span attribute set

Future-extension policy. New attributes land under `db.youtrackdb.*` if they pass two tests: (a) bounded cardinality per the policy above, and (b) extractable from existing parser output without re-walking the query. Attributes failing either test go into the per-operator span-events follow-up, which can carry higher-cardinality fields without cost-amplifying the per-span attribute set.

Extraction site. The shared `Classification` value record gains additional optional fields, one per attribute in the table above, with defaults representing "not present". `GremlinBytecodeClassifier.classify(Traversal)` (dispatching to Path A via the boundary step's precomputed `Classification` or Path B via bytecode walk per §"Gremlin bytecode classification") and `SqlSyntaxClassifier.classify(SQLStatement)` populate the fields during the same walk that already extracts operation and collection. The OTel listener reads the fields from `QueryDetails` accessors (extended in Track 1 alongside the existing operation / collection / namespace / errorType accessors) and sets them on the emitted span; custom (non-OTel) listeners that ignore the new accessors are unaffected.

### YouTrackDB Gremlin DSL passthrough attributes

When a Gremlin traversal embeds SQL via `g.yql(...)` or `g.command(...)`, the outer Gremlin span carries the SQL classification (operation name, collection name, sanitized text) extracted from `CallStep.parameters["command"]` via `SqlSyntaxClassifier`, in addition to the following YTDB-specific custom attributes (D40). Inner SQL spans emitted by the Track 4 wrap-site carry one additional disambiguation attribute (`db.youtrackdb.sql.invoked_via`) so operators can distinguish wrapper calls from direct SQL.

**Outer Gremlin span custom attributes (set by `GremlinBytecodeClassifier` extension):**

| Attribute | Type | Description | Cardinality |
|---|---|---|---|
| `db.youtrackdb.gremlin.dsl_method` | string | `"yql"` or `"command"` — which DSL alias the caller used (from `CallStep.serviceName`) | low (2 values) |
| `db.youtrackdb.gremlin.statement_count` | int | Number of `CallStep[YTDBCommandService]` in the traversal bytecode | low (typical 1-5) |
| `db.youtrackdb.gremlin.has_graph_steps` | bool | True if the traversal contains non-`CallStep` graph steps (mixed shape) | low |
| `db.youtrackdb.gremlin.has_transaction_control` | bool | True if any embedded SQL is BEGIN / COMMIT / ROLLBACK | low |
| `db.youtrackdb.gremlin.statements_summary` | string | Semicolon-joined per-statement summary, set only when `statement_count > 1` (e.g., `"BEGIN; INSERT Order; COMMIT"`); capped at 5 statements then `"; +N more"`. **Note**: `statement_count` is the `CallStep[YTDBCommandService]` count in the traversal bytecode, NOT the emitted inner-span count — BEGIN / COMMIT / ROLLBACK short-circuit in `executeCommand` before reaching the SQL execution layer and emit no inner SQL span, so operators correlating span counts to `statement_count` subtract administrative statements (or filter on `db.youtrackdb.gremlin.has_transaction_control = true` and account for the gap directly) | medium (chain shapes) |
| `db.youtrackdb.gremlin.embedded_sql.N` | string | For mixed shapes, sanitized SQL of the Nth embedded `CallStep` (N = 0..2 capped, so at most 3 entries per outer span); set when `has_graph_steps = true` | medium-high (host responsibility — sanitized SQL is per-query unique by structure even after literal substitution) |
| `db.youtrackdb.gremlin.no_op` | bool | True when `command.isEmpty()` (YTDBCommandService short-circuit case); outer span uses `youtrackdb` catch-all classification | low |

**Inner SQL span custom attribute (set by `InstrumentedSqlResultSet.close()`):**

| Attribute | Type | Description | Cardinality |
|---|---|---|---|
| `db.youtrackdb.sql.invoked_via` | string | `"gremlin_dsl"` when `Context.current()` includes an outer Gremlin span (detected by `Span.fromContext(...).getAttribute("db.youtrackdb.gremlin.dsl_method") != null` at fire time); `"native"` for direct `db.query(...)` / `db.command(...)` / `db.execute(...)` calls without a Gremlin parent | low (2 values in v1; `"server"` reserved for future binary-protocol path) |

**Classifier algorithm for outer Gremlin span (Path B branch — Path A keeps reading `YTDBMatchPlanStep.getClassification()` per D9 / D20):**

1. Walk bytecode, collect `callSteps: List<CallStep[YTDBCommandService]>` and `graphSteps: List<other Step kinds>`.
2. **Pure passthrough** (`callSteps.size() >= 1 AND graphSteps.isEmpty()` or only terminal aggregates like `toList` / `count`):
   - Parse each `CallStep.parameters["command"]` via `SQLEngine.parse(...)` (cached via `YqlStatementCache`).
   - Pick the first non-administrative statement (skip BEGIN / COMMIT / ROLLBACK); if all are administrative, use the first.
   - Run `SqlSyntaxClassifier.classify(...)` to derive `db.operation.name` and `db.collection.name`.
   - Set `db.query.text` to `SqlSanitizer.sanitize(...)` output of the dominant statement.
   - Populate `db.youtrackdb.gremlin.dsl_method` from `callSteps.first().serviceName`; `statement_count` from `callSteps.size()`; `has_transaction_control` from any administrative statement presence; `statements_summary` when `statement_count > 1`.
3. **Mixed shape** (`callSteps.size() >= 1 AND graphSteps.size() > 0`):
   - Run normal Path B classification on the graph steps (unchanged from D9).
   - Add `db.youtrackdb.gremlin.has_graph_steps = true`, `db.youtrackdb.gremlin.statement_count`, and `db.youtrackdb.gremlin.embedded_sql.N` (N = 0..2, sanitized SQL from the first three `CallStep` entries; further entries dropped to bound cardinality).
4. **Empty command** (`callSteps.first().parameters["command"]` is null or empty string): outer span classified as `youtrackdb` catch-all; `db.youtrackdb.gremlin.no_op = true`; `statement_count = 0`. No inner SQL span (YTDBCommandService short-circuits before `session.execute(...)`).
5. **Pure graph-step traversal** (`callSteps.isEmpty()`): normal Path B classification, no `db.youtrackdb.gremlin.*` attributes set.

**Args handling for parameterized queries (`g.yql("SELECT FROM User WHERE id = ?", 42)`):** the sanitized `db.query.text` reflects the already-parameterized string. Argument values from `CallStep.parameters["args"]` are NOT emitted by default — cardinality and PII risk. Opt-in via global config `OPENTELEMETRY_QUERY_INCLUDE_PARAMETERS=false` (default `false`). When `true`, args attach as `db.operation.parameter.<index>` per sem-conv §"Recommended" namespace. Per-tag-rule control is deferred to follow-up ticket **YTDB-708** (would extend the `TagRule` sealed interface to carry a Boolean payload alongside the existing Long / Mode payloads, so hosts can flip parameter emission per query class — sem-conv §"Recommended" treats `db.operation.parameter.*` as a per-query decision, and the global boolean here is the YTDB-496 MVP, not a contract claim that all queries should be treated alike). Until YTDB-708 lands, hosts that need per-class parameter emission filter on `db.query.summary` in their OTel pipeline downstream.

### Edge cases / Gotchas

- An empty `db.query.text` (e.g., the rare case where `stringStatement` is null on the SQL path and `statement.getOriginalStatement()` also returns null) is acceptable; the attribute is Recommended, not Required.
- Classifiers MUST NOT throw. Any unexpected bytecode or `SQLStatement` subclass returns `Classification(Optional.empty(), Optional.empty())`. Gremlin tests cover at least: `V`-only, `addV`, `addE`, `drop()`, chained `hasLabel("A").hasLabel("B")` (first label wins), and `V().union(...)` (no clean classification). SQL tests cover each statement type plus FROM-with-subquery and multi-target FROM.
- `db.query.summary` cardinality stays low because the classifier output is low-cardinality. A host that sets `querySummary` to a per-request string defeats this; documented as a host responsibility.
- `db.namespace` resolution depends on the database name being readily available from the transaction context. If unavailable, the attribute is omitted, which is allowed by sem-conv ("if available").

### References
- D-records: D5, D6, D8, D9, D16, D19, D40 (GremlinBytecodeClassifier extension for `CallStep[YTDBCommandService]` populates outer Gremlin span with SQL-derived classification + `db.youtrackdb.gremlin.*` custom attributes; inner SQL span carries `db.youtrackdb.sql.invoked_via` via `Context.current()` detection)
- Mechanics: none

## Span timing capture

**TL;DR.** Span emission goes through `TimeUnit.NANOSECONDS` end-to-end so duration carries every nanosecond the fire site measured. The pattern is `setStartTimestamp(startNanos, NANOSECONDS).startSpan()` then `span.end(startNanos + executionTimeNanos, NANOSECONDS)` — zero integer division on the emission path. The listener API exposes the wall-clock start two ways: the legacy `startedAtMillis` parameter for back-compat, plus a new default-method accessor `QueryDetails.getStartedAtEpochNanos(): long` returning epoch-nanoseconds. Under `EXACT` the fire site populates the accessor at full nanosecond precision via `Instant.now()`; the default implementation derives from `startedAtMillis` so listeners that ignore the new accessor still get a sensible value.

The mapping inside `OTelQueryMetricsListener.queryFinished(...)`:

```java
long startNanos = details.getStartedAtEpochNanos();
// SQL fire site populates getParentContext() with the context captured at wrapper
// construction; passing it explicitly makes parent linkage independent of the thread
// close() runs on. The orElse is a defensive fallback for any fire path that omits it.
Context parentContext = details.getParentContext().orElse(Context.current());
Span span = tracer.spanBuilder(name)
    .setSpanKind(clientKind)
    .setStartTimestamp(startNanos, TimeUnit.NANOSECONDS)
    .setParent(parentContext)
    .startSpan();
// set sem-conv attributes (db.system.name, db.query.text, ...)
span.end(startNanos + executionTimeNanos, TimeUnit.NANOSECONDS);
```

Both values come from the same clock pair the fire site captured for the resolved mode at this query. Under `EXACT` the fire site reads `Instant.now()` for the start (full nanosecond field on JDK 21; OS-dependent actual resolution lands at ns / μs on Linux, ~ms on older Windows) and `System.nanoTime()` for the duration delta. Under `LIGHTWEIGHT` it reads `ticker.approximateCurrentTimeMillis() * 1_000_000L` for the start nanos and `ticker.approximateNanoTime()` for the duration delta, both at ~10 ms ticker granularity. The listener sees a consistent ns pair regardless of mode, so the OTel-recorded duration never drifts from the listener-measured duration.

Implicit `now()` would be wrong here. The listener callback fires *after* the operation completes, so `tracer.spanBuilder(...).startSpan()` without an explicit timestamp would record callback-entry time as the span start — losing the relationship between the span and the actual query timing. Passing `setStartTimestamp(...)` and `span.end(endTs)` makes the span match the measured operation.

The standalone commit span for write transactions is built the same way as a query span, with the commit start nanos read from a new `TransactionDetails.getCommittedAtEpochNanos(): long` default-method accessor (back-compat default derives from `commitAtMillis`) and the duration read from `commitTimeNanos`. Read-only transactions do not invoke this listener (existing YTDB semantics, preserved), so no commit span is emitted for them.

### Edge cases / Gotchas

- Span timestamps emit in `TimeUnit.NANOSECONDS` regardless of timing mode; source-clock resolution decides the actual precision a trace viewer renders. Concretely:

  | Mode | Start precision | Duration precision | Start source | Duration source |
  |---|---|---|---|---|
  | `EXACT` | ns / μs (OS-dependent, `Instant.now()`) | ns (`System.nanoTime` delta) | `Instant.now()` epoch-nanos | `System.nanoTime` |
  | `LIGHTWEIGHT` | ~10 ms (ticker) | ~10 ms (ticker delta) | `ticker.approximateCurrentTimeMillis * 1_000_000L` | `ticker.approximateNanoTime` |

  A 1.234567 ms query under `EXACT` records a span with duration ~1234567 ns, no integer-ms rounding on the emission path. The same query under `LIGHTWEIGHT` rounds to a ticker tick (~0 or ~10 ms) because the ticker itself updates at that granularity, not because of any emission-side conversion. Hosts that need sub-ms precision pick `EXACT` per-TX via `withQueryMonitoringMode(EXACT)` or per-tag via `OPENTELEMETRY_QUERY_MODE_TAG_RULES`.
- A clock skew between the fire site and the OTel SDK's exporter does not affect span duration, only the absolute placement on a wall-clock timeline. The exporter normalizes timestamps per the backend protocol.
- An OTel-compatible backend that requires strictly-monotonic timestamps within a single trace sees no violation: every YTDB span is built with `(start, end)` from one fire-site clock read, and `end > start` always holds because `executionTimeNanos > 0` for any completed operation.

### References
- D-records: D8, D14, D15
- Invariants: Timing-mode uniformity (per-query)
- Mechanics: none

## Context propagation in embedded

**TL;DR.** The host application's active span automatically becomes the parent of every YTDB query span. `Context.current()` resolves to the host's span because the listener fires synchronously on the caller's thread per existing YTDB semantics, and transaction operations are pinned to the owner thread via `assertOnOwningThread`. For Gremlin traversals, the OTel module installs a `GremlinSpanLifecycleHook` on `YTDBQueryMetricsStep` (Track 1 ships the interface + setter; Track 3 installs the OTel impl via a finalization-strategy that runs after `YTDBQueryMetricsStrategy`). The hook opens the Gremlin span at first `hasNext()` and ends it at `close()` after `queryFinished` returns. The Gremlin span is therefore `Context.current()` during inner SQL execution; SQL spans emitted by `InstrumentedSqlResultSet.close()` then attach as children of the Gremlin span automatically. `OTelQueryMetricsListener.queryFinished` distinguishes the Gremlin and SQL paths via the new `QueryDetails.getQuerySource(): Optional<QuerySource>` accessor: on `GREMLIN`, it enriches the hook-managed span with sem-conv attributes; on `SQL`, it creates a new span as before. A regression test guards against future threading changes.

The verification:
- `YTDBQueryMetricsStep.close()` calls `listener.queryFinished(...)` directly (no executor wrapping).
- `FrontendTransactionImpl.notifyMetricsListener()` (commit success and failure paths) runs on the committing thread.
- `FrontendTransactionImpl.assertOnOwningThread()` is a `private` method called by `beginInternal`, the commit path (`commitInternalImpl`, reached from `commitInternal` after Track 1 removes the `monitoredCommitInternal` variant per §"Listener registration and ordering"), and every record-CRUD entry point (`getRecord`, `exists`, `loadRecord`, `deleteRecord`, `addRecordOperation`). The `query` / `command` / `execute` / `rollback` dispatch methods on `FrontendTransactionImpl` delegate to `DatabaseSessionEmbedded` and call only `checkIfActive`, but the listener fire paths above run synchronously on the calling thread because no executor or worker pool intervenes between the dispatch call and the listener fire.
- Result: when a host wraps a YTDB transaction inside `tracer.spanBuilder("host-op").startSpan().makeCurrent()`, `Context.current()` inside the YTDB listener returns the host's context with the host span as the active span.

Gremlin span lifecycle, step by step:

1. **First `hasNext()` of `YTDBQueryMetricsStep`** — the step invokes the installed `GremlinSpanLifecycleHook.onFirstHasNext(details, startEpochNanos, parentContext)`. The OTel hook impl derives the span name from `details.getQuerySummary()` (the host's `querySummary` tag, originally `traversal.getConfig(YTDBQueryConfigParam.querySummary)`) when present, else the literal `"youtrackdb"` (the sem-conv span-name fallback chain's catch-all); it then calls `tracer.spanBuilder(spanName).setParent(parentContext).setStartTimestamp(startEpochNanos, NS).startSpan()`, then `scope = span.makeCurrent()`. The span carries no sem-conv attributes yet — only the name is pinned. `updateName(...)` is **NOT** called at `close()`: pinning the name at first-`hasNext()` time means the gate-drop case (the "skinny" span) and the emit case carry comparable names, so a dashboard filter on `name = "youtrackdb"` (catch-all) versus `name = "findActiveUsers"` (tagged) stays deterministic regardless of whether the slow-query or heartbeat gate kept the span around. `Span.fromContext(Context.current())` now resolves to this Gremlin span; the step stores the `(span, scope)` pair internally.
2. **During iteration** — any inner `InstrumentedSqlResultSet` constructor reads `Context.current()` into its `capturedContext` field, binding the Gremlin span as the parent for the SQL span the wrapper will emit at `close()`. The wrapper hands that captured context to the listener explicitly through `QueryDetails.getParentContext()`, not through a thread-local `makeCurrent()`, so the linkage does not depend on which thread `close()` later runs on.
3. **`YTDBQueryMetricsStep.close()`** — the step iterates the listener snapshot and calls `queryFinished(details, startedAtMillis, executionTimeNanos)` on each. `OTelQueryMetricsListener.queryFinished` reads `details.getQuerySource()`: on `GREMLIN`, it reads `Span.fromContext(Context.current())`, sets the sem-conv attributes (`db.system.name`, `db.query.text`, `db.operation.name`, `db.collection.name`, `db.youtrackdb.*`), and sets `error.type` + status `ERROR` when `details.getErrorType().isPresent()`. It does NOT call `spanBuilder()` or `span.end()`. On `SQL`, it creates a new span with `setParent(details.getParentContext())` (the construction-time context the wrapper captured, passed explicitly), as in the workflow diagram.
4. **After listener iteration** — the step invokes `hook.onClose(details, endEpochNanos)`. The OTel hook impl calls `scope.close()` then `span.end(endEpochNanos, NS)`.

End result: Path B fallback shows one Gremlin span with one child SQL span; Path A (translated) shows one Gremlin span with no children; direct SQL shows one SQL span parented to whatever `Context.current()` resolved to at wrapper construction (host span if any, else trace root).

Slow-query and heartbeat gates apply to both paths but with different cost shapes. For SQL the gate runs at the top of `queryFinished` before any `spanBuilder()` call, so dropped queries pay no allocation. For Gremlin the span is already allocated by the hook at first `hasNext()` (parent-child propagation requires it during iteration); a dropped query becomes a "skinny" span that lacks the sem-conv attributes `queryFinished` would have set. Operators that want to discard skinny Gremlin spans entirely install a `SpanProcessor` filtering on the absence of `db.system.name` — skinny spans carry only the pinned name (host `querySummary` tag or `"youtrackdb"` catch-all) and never any `db.*` attribute, so the filter is unambiguous. The cost differential matches the volume differential — SQL is the high-frequency path where alloc-free is load-bearing; Gremlin volume is low enough that per-traversal alloc is acceptable.

The async caveat: if a future refactor moves traversal close (or commit) to a worker pool, `Context.current()` on that worker would not see the host span, and the YTDB span would attach to the root of a new trace. The test suite includes a propagation test that fails loudly if this happens; the failure mode (orphan YTDB spans) is also operator-visible in the trace viewer.

Explicit propagation is not exposed in this design. A host that needs to fan out a YTDB query to a custom executor must propagate the OTel `Context` itself per OTel's standard pattern (`Context.taskWrapping(executor)` or `Context.wrap(runnable)`). YTDB does not bridge that case.

### Edge cases / Gotchas

- A host that never opens an outer span sees the YTDB span as the trace root, with a synthetic `traceId`. This is correct OTel behavior.
- Mid-transaction context changes (host pushes a span between two queries) are observed: subsequent queries attach to the newer span. This is OTel's contract.
- The standalone commit span for write transactions attaches to whatever `Context.current()` resolves to at the moment `writeTransactionCommitted` fires — typically the host's TX-wrapping span, otherwise the trace root. There is no YTDB-side wrapper span to bind queries and the commit together; that grouping is a host responsibility through an outer span if needed.
- **Path B duration overlap for dashboard authors.** Under Gremlin Path B (translator declined, fallback runs per-step SQL), the outer Gremlin span and its child SQL span carry overlapping durations: the parent's duration contains the child's by parent-child semantics. A dashboard summing "total query time" via `sum(span.duration WHERE db.system.name = 'youtrackdb')` double-counts every Path B query (once via the Gremlin parent, once via the SQL child). To make filtering explicit instead of structural, the outer Gremlin span carries `db.youtrackdb.gremlin.path = "A"` or `"B"` (set by `YTDBQueryMetricsStep` from the classifier's Path-A-vs-Path-B dispatch — Path A means a `YTDBMatchPlanStep` was present, Path B means the bytecode walk ran). Dashboards aggregating a unique sum filter `WHERE db.youtrackdb.gremlin.path != "B" OR parent_span_id IS NOT NULL` (count Path A Gremlin spans + every SQL child) instead of relying on `parent_span_id IS NULL` alone. Path A (translated) does not exhibit the overlap because the boundary step emits a single Gremlin span with no SQL children. Direct SQL also emits a single span (no `gremlin.path` attribute). The redundancy on Path B is intentional diagnostic information (signals "this traversal fell back to per-step SQL"); the `gremlin.path` attribute makes the diagnostic directly filterable.

### References
- D-records: D4
- Mechanics: none

## Gremlin bytecode classification

**TL;DR.** Two extraction paths feed one `Classification(operationName, collectionName)` value record. **Path A (translated):** when the step list contains a `YTDBMatchPlanStep` (which the [Gremlin-to-MATCH translator from PR #1038](https://github.com/JetBrains/youtrackdb/pull/1038) injects in place of the original step chain), the dispatcher reads the precomputed `Classification` directly from `YTDBMatchPlanStep.getClassification()`. `GremlinToMatchStrategy` populates this two-field value at boundary-step construction time (before discarding the `MatchPlanInputs` IR); YTDB-496 Track 1 owns the boundary-step extension (see §"YTDBMatchPlanStep classification field"). **Path B (fallback):** when the translator declined and the original step chain survives, the dispatcher walks the TinkerPop `Bytecode` instruction list to identify the start step (`V`/`E`/`addV`/`addE`/`drop`) and the first label-bearing operator (`hasLabel`, `addV(X)` argument, `addE(X)` argument). The dispatcher returns `Optional.empty()` for both fields when the shape doesn't yield clean values; never throws.

**Path A classification rules** — applied by `GremlinToMatchStrategy` once at translation time and stored on the boundary step. The dispatcher only reads the stored value:

| Translator-recognized shape | Operation name | Collection name source |
|---|---|---|
| Single-plan boundary (any RETURN / aggregation / group shape; PR #1038 Tracks 2-9) | `SELECT` | first node's class from `pattern.firstNode().aliasClasses` at translation time, else `Optional.empty()` |
| Union boundary (`MultiPlanMatchStep` per PR #1038 Track 10) | `SELECT` | first child plan's first-node class at translation time, else `Optional.empty()` |

**Path B (bytecode walk) classification rules** — applied when the translator declined and the original step chain is intact:

| First source step | Operation name | Collection name source |
|---|---|---|
| `V()` | `SELECT` | first `hasLabel(X)` argument, else `Optional.empty()` |
| `E()` | `SELECT` | first `hasLabel(X)` argument, else `Optional.empty()` |
| `addV(X)` | `INSERT` | `X` (label argument of addV) |
| `addV()` (no label) | `INSERT` | `Optional.empty()` |
| `addE(X)` | `INSERT` | `X` (label argument of addE) |
| step chain ending with `drop()` | `DELETE` | first `hasLabel(X)` argument before the drop, else `Optional.empty()` |
| anything else | `Optional.empty()` | `Optional.empty()` |

Implementation lives in `core/.../profiler/monitoring/GremlinBytecodeClassifier.java` as a static utility with one public entry point (`Classification classify(Traversal)`) and two private dispatch targets (`classifyFromBoundaryStep(YTDBMatchPlanStep)` for Path A reading the precomputed `Classification`; `classifyFromBytecode(Bytecode)` for Path B). The dispatcher checks `traversal.getSteps().stream().anyMatch(s -> s instanceof YTDBMatchPlanStep)`; when true, Path A; otherwise Path B. `YTDBQueryMetricsStep.close()` calls `classify(traversal)` directly when building the inline `QueryDetails` and stashes the returned `Classification` in two `Optional<String>` fields read back by `QueryDetails.getOperationName()` / `getCollectionName()`. The same `QueryDetails` instance also carries the `errorType` slot populated from any exception caught around the traversal-close path (the `step.next()` / `step.hasNext()` flow), so the slow-query threshold gate in `OTelQueryMetricsListener` can bypass on error. When no listener consults those accessors the work is paid (the call is unconditional inside the fire site), but the cost is one Path-A field read (translated) or one bytecode walk (fallback) reusing the same instruction-list traversal pattern as the existing `produceScript()` sanitization, measured in microseconds and dominated by the listener call itself.

### Strategy ordering: metrics step after translator

TinkerPop runs strategies in fixed category order (`DecorationStrategy → OptimizationStrategy → ProviderOptimizationStrategy → FinalizationStrategy → VerificationStrategy`), then sorts within each category via `applyPrior()` / `applyPost()` declarations. `YTDBQueryMetricsStrategy` is declared as a `FinalizationStrategy`; PR #1038's `GremlinToMatchStrategy` is declared as a `ProviderOptimizationStrategy`. The category boundary alone guarantees the metrics-step injection runs after the translator on every traversal, without any cross-strategy `applyPost` reference: every `ProviderOptimizationStrategy` completes before any `FinalizationStrategy` starts. The injected step lands as the terminal element of whichever step list the traversal ends up with: a single `YTDBMatchPlanStep` (Path A) or the original step chain (Path B, when the translator declined).

Symmetric under both landing orders. PR #1038 first: no coordination is required on its side; the `FinalizationStrategy` category placement carries the guarantee. YTDB-496 first: same; the metrics strategy runs after every present `ProviderOptimizationStrategy` whether or not the translator is among them. The reversed-order scenario where the translator wipes the injected metrics step is structurally impossible — the metrics strategy cannot run before any `ProviderOptimizationStrategy` on the traversal because TinkerPop's pipeline schedules them in disjoint passes.

### `YTDBMatchPlanStep` classification field

Path A dispatch reads a precomputed `Classification(operation, collection)` value (2 String references) stored on each `YTDBMatchPlanStep` instance at boundary-step construction. PR #1038's boundary step does NOT retain the full `MatchPlanInputs` IR — the strategy discards it after `MatchExecutionPlanner` consumes it. YTDB-496 Track 1 owns the boundary-step extension: a `private final Classification classification` field, a constructor parameter, and a public `getClassification(): Classification` accessor. `GremlinToMatchStrategy` (PR #1038's translator strategy) computes the value once at translation time by reading the IR's `pattern.firstNode().aliasClasses` for the collection; `operation` is `"SELECT"` hardcoded because PR #1038 Phase 1 only translates read patterns. The accessor lives in YTDB-496's commit history because (a) it exists exclusively for OTel telemetry classification, and (b) the 2-String memory cost per boundary-step instance is cheap enough to pay on every translated traversal regardless of whether OTel is enabled, vs. retaining the full ~19-field `MatchPlanInputs` record. PR #1038 is merged on `develop` per the YTDB-496 baseline assumption before Track 1 modifies the boundary step.

For `MultiPlanMatchStep` (PR #1038 Track 10's union variant), `GremlinToMatchStrategy` populates the `Classification` from the first child plan's first-node class at translation time, mirroring the single-plan rule. PR #1038 Track 10 is merged on `develop` per the baseline assumption, so `MultiPlanMatchStep` is available and the Path A union classification tests compile.

Timing capture in `YTDBQueryMetricsStep.close()` follows the per-query mode resolution mechanism described in §"Query tagging and per-tag rule resolution": the step reads the query tag from `traversal.getConfig(YTDBQueryConfigParam.querySummary)` and calls `currentTx.resolveQueryMonitoringMode(tag)` to pick the clock source for this traversal. Two Gremlin traversals in the same transaction with different tags can therefore record at different precisions. Under Path B fallback the inner SQL queries spawned by the traversal each emit their own span via `InstrumentedSqlResultSet.close()`; these spans become children of the outer Gremlin span via OTel `Context.current()` propagation (see §"Context propagation in embedded"). Each level resolves its mode independently: the outer Gremlin step reads its mode from the traversal's tag; inner SQL spans read theirs from `Optional.empty()` and resolve to the per-TX default. The two modes may differ, which is acceptable because they measure different things (outer Gremlin step duration vs. inner SQL execution time).

### Edge cases / Gotchas

- `g.V().hasLabel("A").hasLabel("B")`: returns `collectionName = "A"` (first wins). This matches sem-conv guidance to capture a single low-cardinality value rather than concatenating.
- `g.V().union(__.hasLabel("A"), __.hasLabel("B"))`: returns `collectionName = Optional.empty()` because the label is inside a sub-traversal, not a top-level instruction. The classifier does not descend into sub-traversals.
- `g.addV().property("label", "X")`: returns `collectionName = Optional.empty()` because the label is not a positional argument of `addV()`. Properties are not inspected.
- Numeric or non-string `hasLabel` argument (TinkerPop allows it via mutation in untyped code): the classifier checks `instanceof String` and returns `Optional.empty()` for non-String arguments.

### References
- D-records: D9
- Invariants: none specific (the classifier is fail-safe by contract)
- Mechanics: none
- External: [PR #1038 — Gremlin-to-MATCH translator design](https://github.com/JetBrains/youtrackdb/pull/1038) (Path A source; provides `YTDBMatchPlanStep` and `GremlinToMatchStrategy`; YTDB-496 Track 1 extends the boundary step with the lightweight `Classification` field and `getClassification()` accessor)

## Query tagging and per-tag rule resolution

**TL;DR.** Different Gremlin traversals in one transaction can claim different timing precisions when the host attaches identifying strings like `"findActiveUsers"` or `"monthly-scan"` to them. Tag source is Gremlin-only: `g.with(YTDBQueryConfigParam.querySummary, "X")`. SQL statements have no tag source and resolve via the per-TX default. A process-global lookup walks ordered first-wins matchers (exact, prefix, regex) configured at startup via `OPENTELEMETRY_QUERY_MODE_TAG_RULES`, mapping each identifier to a `QueryMonitoringMode` value. Fallback chain: matcher hit → per-TX default (`YTDBTransaction.withQueryMonitoringMode(...)`) → `LIGHTWEIGHT`. The identifier also surfaces as `db.query.summary` for dashboard breakdowns and sampler decisions. Replaces the earlier per-transaction-snapshot timing-mode scheme so one transaction can mix tracker-based 10 ms timing with sub-millisecond precision for hot paths.

The resolver and its rule types live next to the existing listener SPI in `core/.../profiler/monitoring/`:

```java
public final class QueryMonitoringModeResolver {
    private final List<TagRule<QueryMonitoringMode>> rules;          // immutable, compiled once at startup
    private final Map<String, QueryMonitoringMode> cache;            // bounded LRU: Collections.synchronizedMap
                                                                     // over LinkedHashMap(1024, 0.75f, true) with
                                                                     // access-order eviction; INFO-rate-limited
                                                                     // (once per 60 s window) on eviction so
                                                                     // unique-tag-per-request misuse cannot
                                                                     // exhaust the heap

    public QueryMonitoringMode resolve(Optional<String> tag, QueryMonitoringMode txDefault) {
        if (tag.isEmpty()) return txDefault;
        // synchronized(cache) wrapper handles concurrent get-and-put atomically on the access-order LinkedHashMap.
        synchronized (cache) {
            QueryMonitoringMode cached = cache.get(tag.get());
            if (cached != null) return cached;
            QueryMonitoringMode resolved = resolveUncached(tag.get(), txDefault);
            cache.put(tag.get(), resolved);
            return resolved;
        }
    }
    // resolveUncached walks rules in order, first-wins; falls back to txDefault.
}

public sealed interface TagRule<T> {
    boolean matches(String tag);
    T value();

    record Exact<T>(String tag, T value)       implements TagRule<T> { ... }
    record Prefix<T>(String prefix, T value)   implements TagRule<T> { ... }
    record Regex<T>(Pattern pattern, T value)  implements TagRule<T> { ... }
}
```

The sealed `TagRule<T>` is generic so a future per-tag slow-query threshold resolver (`SlowQueryThresholdResolver` consuming `TagRule<Long>`) reuses the same matcher hierarchy without duplicating the rule-parsing code.

Configuration format for `OPENTELEMETRY_QUERY_MODE_TAG_RULES`:

```
OPENTELEMETRY_QUERY_MODE_TAG_RULES=findActiveUsers=EXACT,prefix:expensive-=EXACT,regex:^batch-.*$=EXACT
```

- No prefix → `Exact` match.
- `prefix:X` → `Prefix` match against `tag.startsWith("X")`.
- `regex:X` → `Regex` match against `Pattern.compile("X")`.
- Comma-separated, first match wins (insertion order).
- Whitespace around `=` and `,` trimmed.
- Invalid rule (bad regex, unknown mode, malformed entry) logs WARN at startup and is dropped from the list; the resolver continues with the remaining valid rules.

The cache holds resolved `(tag → mode)` mappings so a long-running workload pays the rule walk once per distinct tag. Tags are documented as **low-cardinality identifiers**: a host that emits a unique tag per request (e.g., a UUID) defeats the cache and would otherwise blow process heap. The cache is therefore a bounded LRU (capacity 1024, `Collections.synchronizedMap` over a `LinkedHashMap(1024, 0.75f, true)` with access-order eviction) so unique-tag-per-request misuse cannot exhaust the heap. Eviction logs INFO once per 60 s window with the count of evictions in that window so operators see the misuse without log flooding; a tag whose resolved mode was previously cached then evicted simply pays the rule walk again on the next access (rule walks are deterministic, so the resolved value stays stable across miss / re-resolve / re-cache).

Resolution call site on `FrontendTransaction`:

```java
default QueryMonitoringMode resolveQueryMonitoringMode(Optional<String> tag) {
    return QueryMonitoringModeResolver.global().resolve(tag, getDefaultQueryMonitoringMode());
}
```

The fire sites call this once per query before reading the clock:

```text
Gremlin path (YTDBQueryMetricsStep.close):
    tag  = traversal.getConfig(YTDBQueryConfigParam.querySummary)
    mode = currentTx.resolveQueryMonitoringMode(tag)
    if LIGHTWEIGHT: ticker reads
    else (EXACT):   System.nanoTime reads

SQL path (InstrumentedSqlResultSet constructor):
    tag  = Optional.empty()                                  // SQL has no tag source
    mode = currentTx.resolveQueryMonitoringMode(tag)         // resolver short-circuits to per-TX default
    if LIGHTWEIGHT: ticker reads
    else (EXACT):   System.nanoTime reads
```

The commit fire site has no query in flight; it uses `currentTx.getDefaultQueryMonitoringMode()` directly.

### Edge cases / Gotchas

- **No tag, no per-TX default** → `LIGHTWEIGHT`. This is the most common path and preserves the existing zero-syscall hot path for hosts that don't engage with monitoring config at all.
- **No tag, per-TX default set** → per-TX default applies. Backwards-compatible with hosts that already call `withQueryMonitoringMode(EXACT)` on every TX they care about.
- **Tag present, no rule matches** → falls back to per-TX default (then to `LIGHTWEIGHT`). A tag that the operator hasn't configured a rule for behaves identically to an untagged query; tagging never raises precision on its own.
- **Rule matches but specifies the same mode as TX default** → idempotent; the cache still records the resolution so future identical tags skip the walk.
- **Mid-TX rule change** → not supported. Rules are compiled once at startup from `OPENTELEMETRY_QUERY_MODE_TAG_RULES`; live reconfiguration is out of scope for YTDB-496. If runtime mutation becomes a requirement later, a snapshot-per-TX (analogous to the listener-registry snapshot) is the cleanest extension point.
- **Conflicting rules (two rules match the same tag)** → first-wins per insertion order. Operators ordering rules from most-specific to most-general is the documented pattern. Two `Exact` rules for the same tag is parsed as the first one only; the second logs WARN and is dropped at startup.
- **Cache cardinality blow-up** → an attacker or buggy host that emits unique tags per request would otherwise grow each cache without bound. The two per-tag resolvers (`QueryMonitoringModeResolver` for mode, `SlowQueryThresholdResolver` for slow-query thresholds) each maintain their own bounded LRU (`Collections.synchronizedMap` over `LinkedHashMap(1024, 0.75f, true)` with access-order eviction, capacity 1024), so the heap footprint is capped at 2 × 1024 entries × ~200 bytes-per-entry ≈ 400 KB even under sustained unique-tag-per-request abuse. The heartbeat gate has no resolver and contributes no cache. Eviction emits one INFO line per 60 s window with the eviction count so operators see the misuse without log flooding; an evicted entry simply pays the rule walk again on its next access (rule walks are deterministic, so the resolved value stays stable across miss / re-resolve / re-cache). The listener layer keeps emitting spans either way; the failure mode the LRU prevents is OOM, not dropped telemetry. Future hardening could consolidate the two caches into one `Map<String, ResolvedRules>` carrying both values per tag — not in YTDB-496.
- **Mode resolution determinism** → identical `(tag, txDefault)` always resolves to the same mode (the resolver is a pure function of immutable state). Both fire sites in one query read the same mode value because they both call `currentTx.resolveQueryMonitoringMode(tag)` with the same tag and the same TX default; the resolver returns a value, not a fresh decision.

### References
- D-records: D8, D14, D15
- Invariants: Timing-mode uniformity (per-query)
- Mechanics: none

## SQL execution layer hook

**TL;DR.** `InstrumentedSqlResultSet` (new, `internal.core.db`) is the SQL fire site for `QueryMetricsListener`. The wrapper sits at every return-site of `DatabaseSessionEmbedded`'s public SQL entry points as the outermost layer above any `queryStartedLifecycle(...)` decoration and any [PR #1077](https://github.com/JetBrains/youtrackdb/pull/1077) cache-view construction.

**Wrap sites on `DatabaseSessionEmbedded`.**

| # | Method | Method line | Return-site line | `inner` at this site |
|---|---|---|---|---|
| 1 | `query(String, Object...)` | 617 | 638 | `queryStartedLifecycle(original)` → `LocalResultSet` or `LocalResultSetLifecycleDecorator` |
| 2 | `query(String, boolean, Map)` | 652 | 679 | same as #1 |
| 3 | `executeInternal(...)` (idempotent branch) | 702 | 742 | same as #1 |
| 4 | `executeInternal(...)` (non-idempotent prefetched branch) | 702 | 738–739 | `new LocalResultSetLifecycleDecorator(prefetched)` (skips `queryStartedLifecycle`) |
| 5 | `computeScript(String, String, Object...)` | 753 | 785 | same as #1 |
| 6 | `computeScript(String, String, Map<String,?>)` | 813 | 847 | same as #1 |

Each site ends with `return InstrumentedSqlResultSet.wrapIfListening(inner, statement, rawSql, args, currentTx)`. Six return-sites across five methods; `executeInternal` contributes two. The PR #1077 cache hooks into the same entry points and can substitute `CachedResultSetView` for the inner type on hit and cacheable-miss paths; the wrapper composes against the public `ResultSet` interface and does not inspect the concrete type.

**Layering, from engine output outward.**

```text
LocalResultSet                                          // engine output
  └─ LocalResultSetLifecycleDecorator?                  // queryStartedLifecycle (sites #1-3, #5-6)
                                                        // or manual decorator (site #4, non-idempotent prefetched)
       └─ CachedResultSetView?                          // PR #1077 cache (hit / cacheable miss)
            └─ InstrumentedSqlResultSet                 // always outermost; returned to the caller
```

**Behavior.** Fast path: `wrapIfListening(...)` snapshots `currentTx.iterateAllQueryListeners()`; when empty (default in production without OTel), it returns `inner` unchanged with zero allocation. Construction: the wrapper reads `currentTx.resolveQueryMonitoringMode(Optional.empty())`. SQL has no tag source, so the resolver short-circuits to the per-TX default (`LIGHTWEIGHT` unless the host called `YTDBTransaction.withQueryMonitoringMode(EXACT)`). The chosen mode picks a `(start-epoch-nanos, start-nanoTime)` clock pair: `LIGHTWEIGHT` from `GranularTicker` (~10 ms granularity, no syscall on the hot path) or `EXACT` from `Instant.now()` plus `System.nanoTime()` (sub-ms, two syscalls). The constructor also stores `Context.current()` for the listener's `setParent(...)` call at `close()`. See §"Span timing capture" for the full clock-source rules. Iteration: `hasNext()` / `next()` delegate to `inner`, increment a row counter, and capture any thrown exception. `tryAdvance` / `forEachRemaining` route through these so `.stream()`, `.toList()`, and the other consumer patterns all see the counter. `close()` computes elapsed time from the same clock pair as the construction, builds `QueryDetails` (lazy classification + sanitization, `errorType`, `resultCount`, `parentContext`), and calls `queryFinished` on every listener in the construction-time snapshot. Listener exceptions are isolated per the existing `Exception | LinkageError | AssertionError` contract.

Two mechanisms keep the listener firing once per outer query. Sub-plans: MATCH steps, sub-query steps, `IF` / `WHILE` flow control, and script-line steps build their own un-instrumented inner `LocalResultSet` via the existing 2-arg constructor; they never traverse the six entry points, so `wrapIfListening` does not see them. Public re-entry: a UDF, sub-query expression, or context-variable resolution that calls `db.query(...)` from inside an outer plan is suppressed by a `ThreadLocal<int[]> sqlEntryDepth` counter on `DatabaseSessionEmbedded`. Each public entry increments the slot on entry and decrements it in `finally`; `wrapIfListening(...)` returns `inner` unchanged when the slot value is `> 1`. Inner execution time accrues to the outer span. Multi-statement scripts emit one span per script call; inner statements are not separately instrumented at this layer.

SQL spans emitted by `InstrumentedSqlResultSet.close()` use the construction-time `Context.current()` snapshot. Under Gremlin Path B fallback (`YTDBGraphQuery.execute(...)` → `transaction.query(...)` → entry-point wrapper) the inner SQL span attaches as a child of the active Gremlin span without any `makeCurrent()` on the wrapper. `LocalResultSet.java` stays unchanged in Track 4 scope, keeping YTDB-820's stream-slot substitution untouched.

Track 4 first evaluates folding the wrap into the `queryStartedLifecycle(original)` near-chokepoint (reached by five of the six sites) plus the one non-idempotent prefetched branch; that would reduce six edited return-sites to two with identical fire semantics. The six-site form above is the fallback if that consolidation hits a blocker (e.g., a path that bypasses `queryStartedLifecycle` for reasons the wrap should not inherit).

**Gremlin DSL service-call paths reach `executeInternal()` automatically (D39).** `g.yql(...)` and `g.command(...)` route through `YTDBCommandService.execute(...)` → `YTDBGraphImplAbstract.executeCommand(...)` which dispatches to one of three internal paths: (a) read/write statements call `session.execute(SQLStatement, Map)` (`DatabaseSessionEmbedded.java` line 697); (b) DDL statements acquire a fresh schema session via `acquireSession()` and call `schemaSession.command(SQLStatement, Map)` (line 4517); (c) transaction control (BEGIN / COMMIT / ROLLBACK) short-circuits in `executeCommand` by calling `tx.readWrite()` / `tx.commit()` / `tx.rollback()` directly and returns `SqlCommandExecutionResult.unit()` without producing a `ResultSet`. Paths (a) and (b) both delegate to `executeInternal()` at line 702 — `execute(SQLStatement, Map)` calls `executeInternal(null, statement, args)` directly; `command(SQLStatement, Map)` calls `execute(SQLStatement, Map).close()` which routes through the same `executeInternal()`. The Track 4 wrap-site at line 702 (covering both return branches) therefore catches every inner SQL execution from Gremlin DSL passthrough without any additional wrap-sites. Path (c) emits no inner SQL span; the outer Gremlin span carries `db.operation.name = "BEGIN" | "COMMIT" | "ROLLBACK"` via the `GremlinBytecodeClassifier` extension (D40), and COMMIT additionally produces a standalone commit span via the existing `TransactionMetricsListener` (D3). Six public methods on `DatabaseSessionEmbedded` funnel through `executeInternal()`: `execute(String, Object...)` line 689, `execute(String, Map)` line 693, `execute(SQLStatement, Map)` line 697, `command(String, Object...)` line 4507, `command(String, Map)` line 4512, `command(SQLStatement, Map)` line 4517. Wrapping `executeInternal()` at both return branches covers all of them — `db.query(...)`, `db.command(...)`, `db.execute(...)`, AND every `g.yql(...)` / `g.command(...)` that hits the SQL execution layer.

Hook anatomy in `InstrumentedSqlResultSet` (constructor captures the start clock and stores state; `close()` fires the listener):

```text
wrapIfListening(inner, statement, rawSql, args, currentTx):
1. listeners = currentTx.iterateAllQueryListeners()  ← snapshot of (global registry + per-TX list)
2. If listeners empty: return inner                  // zero-overhead fast path
3. return new InstrumentedSqlResultSet(inner, statement, rawSql, args, listeners, currentTx)

Constructor(inner, statement, rawSql, args, listeners, currentTx):
1. tag  = Optional.empty()                          ← SQL has no tag source
   mode = currentTx.resolveQueryMonitoringMode(tag) ← resolver short-circuits to per-TX default
2. if LIGHTWEIGHT:
     ticker          = YouTrackDBEnginesManager.instance().getTicker()
     startMillis     = ticker.approximateCurrentTimeMillis()
     startEpochNanos = startMillis * 1_000_000L          // ms-granular value lifted to ns scale
     startNanoTime   = ticker.approximateNanoTime()      // no syscalls
   else (EXACT):
     now              = Instant.now()                    // single syscall, ns / μs field
     startEpochNanos  = now.getEpochSecond() * 1_000_000_000L + now.getNano()
     startMillis      = now.toEpochMilli()               // back-compat for legacy startedAtMillis param
     startNanoTime    = System.nanoTime()                // monotonic delta base
3. capturedContext = io.opentelemetry.context.Context.current()   // bind parent span at construction
4. Store: inner, statement, rawSql, args, currentTx, listeners, mode, capturedContext,
          startMillis, startEpochNanos, startNanoTime, rowCount = 0, caughtError = null

Iteration surface — `hasNext` / `next` are the row-counter and error-capture entry points; every other read pathway (`tryAdvance`, `forEachRemaining`, `stream`, `toList`, `findFirst`, `detach`, the inherited `Spliterator` and `Iterator` surface) routes back through `hasNext` / `next` so the same counting and error-capture logic runs for every consumer pattern (the dominant YTDB consumer uses `.toList()` or `.stream()`, not direct `hasNext` / `next`):

   hasNext / next:
     try {
       result = inner.next(...);              // delegate
       if (result != null) rowCount++;
       return result;
     } catch (Throwable e) { caughtError = e; rethrow; }
     No timing overhead on the hot iteration path beyond a single counter increment.

   tryAdvance(Consumer action):
     if (!hasNext()) return false;
     action.accept(next());                   // routes through our own next() → row count + error capture
     return true;

   forEachRemaining(Consumer action):
     while (hasNext()) action.accept(next()); // same routing

close():
1. elapsedNanos = (mode == LIGHTWEIGHT)
                    ? ticker.approximateNanoTime() - startNanoTime
                    : System.nanoTime() - startNanoTime
2. Build QueryDetails (lazy sanitization + classification from statement;
   trackingId from currentTx; errorType from caughtError.getClass().getName() when present;
   resultCount = OptionalLong.of(rowCount);
   startedAtMillis = startMillis; getStartedAtEpochNanos() returns startEpochNanos;
   parentContext = capturedContext)                          // explicit parent handed to the listener
3. for each listener in the snapshot:
     try { listener.queryFinished(details, startMillis, elapsedNanos); }   // listener builds the SQL span via
     catch (Exception | LinkageError | AssertionError t) {                 // setParent(details.getParentContext());
       log WARN with listener class name                                   // no makeCurrent(), so parent linkage
     }                                                                      // holds on any thread close() runs on
4. inner.close()
```

All three SQL execution entry points produce a parsed `SQLStatement`, then call `statement.execute(this, args, true)` (or the pre-parsed overload at line 697 for `executeInternal()`). Bypass paths (non-deterministic, `NOCACHE`-hinted, re-entrant under WHERE evaluation, splice-failure fallback, and the v1 default of cache disabled) wrap the result through `queryStartedLifecycle(original)` for `activeQueries` tracking and return a `LocalResultSet`; PR #1077's cache-hit and cacheable-miss paths skip `queryStartedLifecycle` and return a `CachedResultSetView` directly. The third entry point — `executeInternal()` — has two return branches: an idempotent path that ends with `queryStartedLifecycle(original)` (same shape as the other two entries) and a non-idempotent path (INSERT / UPDATE / DELETE / DDL / CREATE / DROP) at lines 733-739 that prefetches the result into an `InternalResultSet` and returns a `LocalResultSetLifecycleDecorator(prefetched)` without calling `queryStartedLifecycle`. **Both return branches of `executeInternal()` end with `InstrumentedSqlResultSet.wrapIfListening(inner, statement, rawSql, args, currentTx)` as the final return-statement wrapper** — the non-idempotent prefetched branch wraps the decorator the same way the idempotent branch wraps the lifecycled result. Without the wrap on the non-idempotent branch, every `db.execute(...)` write (INSERT / UPDATE / DELETE / CREATE CLASS / DROP CLASS / ALTER CLASS) would emit zero SQL spans — exactly the operations operators most want to trace. The wrapper receives the `SQLStatement` (for lazy classification + sanitization), the raw SQL text (from `stringStatement` when non-null, else from `statement.getOriginalStatement()`), the args, the listener snapshot from `currentTx.iterateAllQueryListeners()`, and the chosen clock readings. The wrapper does not call `statement.execute(...)` or `executionPlan.start()` itself; it only delegates iteration to whatever inner `ResultSet` the entry point produced.

Multi-statement scripts route through `DatabaseSessionEmbedded.computeScript(...)` — two overloads declared at lines 753 and 813, with returns at lines 785 and 847 respectively. The script-engine path builds a `ScriptExecutionPlan` via `executor.execute(...)` and drives `ScriptLineStep` for each inner statement — inner statements run their own `start()` directly without re-entering the three SQL entry points. The wrap point for scripts is at `computeScript` itself, not at the inner statements: both `computeScript` return statements end with `InstrumentedSqlResultSet.wrapIfListening(scriptResult, /* statement */ null, script, args, currentTx)` so one SQL span emits per script invocation and inner statements accrue to that span's duration. The wrapper's `statement` field carries `null` for scripts (the `SqlSyntaxClassifier` returns `Optional.empty()` for both operation and collection, and the span name falls back to `"SCRIPT"`); `db.query.text` carries the script text from the `script` argument.

The `QueryDetails` impl is lazy: `getQuery()` calls `SqlSanitizer.sanitize(statement)` (a static utility in `core`, since the walk needs the parser-output AST) on first access; `getOperationName()` and `getCollectionName()` call `SqlSyntaxClassifier.classify(statement)` (a static utility in `core`) on first access. Hosts that don't read these accessors pay no sanitization or classification cost; the parsed `SQLStatement` is already available because `SQLEngine.parse(...)` runs unconditionally to execute the query. `getResultCount()` returns the wrapper's row counter eagerly (the counter is already incremented per `next()`; no recomputation).

Timing capture follows the per-query mode resolution model from §"Query tagging and per-tag rule resolution". The wrapper constructor calls `currentTx.resolveQueryMonitoringMode(Optional.empty())` to pick the clock source; SQL has no tag source, so the resolver short-circuits at `tag.isEmpty()` and returns the per-TX default. `LIGHTWEIGHT` reads from `GranularTicker` at 10 ms granularity, with no syscall on the hot path. `EXACT` reads `Instant.now()` for the wall-clock start (single syscall, ns / μs field on JDK 21 Linux) and `System.nanoTime()` for the monotonic duration delta; the wall-clock value populates both the legacy `startedAtMillis` listener parameter (via `Instant.toEpochMilli()`) and the new `getStartedAtEpochNanos()` accessor at full ns precision (see §"Span timing capture"). The commit fire site in `FrontendTransactionImpl.notifyMetricsListener` has no per-statement tag context and reads directly from `currentTx.getDefaultQueryMonitoringMode()`, so the commit clock pair matches whatever default the host set on the transaction. Different statements within one transaction can therefore record at different precisions while the commit timer remains aligned with the TX default. Each fire site resolves its own mode independently from its own tag source: the Gremlin step at `YTDBQueryMetricsStep.close()` reads from `traversal.getConfig(YTDBQueryConfigParam.querySummary)`; the wrapper reads `Optional.empty()` and falls back to the per-TX default. Under Path B fallback both fire sites emit (the SQL span becomes a child of the Gremlin span via `Context.current()`), and the two spans may carry timings captured under different modes. This is acceptable because they measure different things: outer Gremlin step duration vs. inner SQL execution time. Per-fire-site Timing-mode uniformity remains within each individual fire site.

The Gremlin Path B fallback produces a parent-child hierarchy. `YTDBGraphQuery.execute(...)` and `YTDBGraphQuery.explain(...)` route through `transaction.query(...)` (an `EXPLAIN`-prefixed query in the explain case), which exits through the same wrapper-installation path as direct SQL. By that point the OTel module has already opened the outer Gremlin span and called `Scope = span.makeCurrent()` (see §"Context propagation in embedded" for the lifecycle), so the wrapper constructor captures `Context.current()` into its `capturedContext` field and hands that value to the listener at `close()`; the SQL span attaches as a child of the Gremlin span. Path A (translated, post-PR-1038) goes through `YTDBMatchPlanStep` which calls `SelectExecutionPlan.start()` directly without going through the SQL entry points, so the Gremlin span has no SQL children. Direct SQL (no Gremlin traversal) emits a single SQL span parented to whatever `Context.current()` resolved to at wrapper construction (host span if any, else trace root).

### Coordination with YTDB-820 cache contract

Placement of the wrapper as the outermost layer at each entry point is what lets YTDB-820 land without re-wiring SQL telemetry. The result-set store from [PR #1077](https://github.com/JetBrains/youtrackdb/pull/1077) hooks into the same three entry points listed above and, depending on its state, returns either an existing `LocalResultSet` (which goes through `queryStartedLifecycle(...)` on bypass paths) or a new `CachedResultSetView` (returned directly, skipping `queryStartedLifecycle`). The wrapper sits below the cache decision in the entry-point control flow (and one layer above whichever inner type the decision yields), so the fire site is uniform across the full inner-type matrix:

| Path | Inner result-set | Wrapper fires `queryFinished`? |
|---|---|---|
| Cache HIT (RECORD, AGGREGATE, MATCH single-alias, K0_NONE) | `CachedResultSetView` | yes |
| Cache MISS, cacheable RECORD / MATCH shape | `CachedResultSetView` ¹ | yes |
| Cache MISS, AGGREGATE shape | `CachedResultSetView` (cache drives `statement.createExecutionPlan(ctx, false)` directly without constructing `LocalResultSet`) | yes |
| Splice failure fallback (planner emitted an unexpected aggregate plan) | `LocalResultSet` (cache falls back to `statement.execute(...)`) | yes |
| Non-deterministic shape (`sysdate`, `$now`, random, uuid, per-row context variables) | `LocalResultSet` (bypassed pre-cache) | yes |
| `NOCACHE`-hinted query | `LocalResultSet` | yes |
| Re-entrant call under WHERE evaluation (`cacheCodeDepth > 0`) | `LocalResultSet` | yes |
| `youtrackdb.query.txResultCache.enabled=false` (default in v1) | `LocalResultSet` | yes |
| `executeInternal()` non-idempotent branch (INSERT / UPDATE / DELETE / DDL / CREATE / DROP) | `LocalResultSetLifecycleDecorator(InternalResultSet)` (prefetched, no `queryStartedLifecycle`) | yes |
| `computeScript()` script-engine entry (method declarations 753 + 813; returns 785 + 847) | `ResultSet` from `executor.execute(...)` decorated by `queryStartedLifecycle(original)` | yes (one span per script call; inner statements accrue to it) |
| Listener snapshot empty | either inner type returned directly | no — `wrapIfListening` short-circuits |

¹ Under the hood the cache builds a `LocalResultSet` whose stream slot the cache substitutes with an `IdempotentExecutionStream` (per YTDB-820's design). The consumer-facing return value is `CachedResultSetView`; the inner `LocalResultSet` is not directly visible. The wrapper composes against the public `ResultSet` interface regardless.

The wrapper inspects nothing about the inner result-set's concrete type; it only delegates iteration and reads `currentTx.iterateAllQueryListeners()` plus the per-query mode. `LocalResultSet.java` therefore stays unchanged in Track 4 scope, preserving YTDB-820's freedom to substitute streams inside the constructed `LocalResultSet` (per the cache design's stream-slot mechanism) without colliding with telemetry state. Track 4 ships exactly one new file on the result-set side — the wrapper.

The wrapper's row counter feeds `QueryDetails.getResultCount(): OptionalLong`. Cache hits expose the same row count an uncached run would expose because the counter is driven by `next()` calls from the consumer, not by executor internals. Operators see correct `db.client.response.returned_rows` histogram values regardless of cache state.

Cache-outcome attribute (`db.youtrackdb.query.cache.outcome`, values `hit | miss | bypass`): PR #1077 / YTDB-820 is on `develop` per the YTDB-496 baseline. Track 4 wires the attribute when `CachedResultSetView.getCacheOutcome()` exists on `develop` at Track 4 entry — the wrapper type-checks the inner result-set against `CachedResultSetView` and stamps the attribute. If the accessor is absent on `develop` (YTDB-820 shipped without it), the attribute lands in YTDB-707 follow-up and Track 4 ships without the type-check. The wrapper itself does not depend on the accessor's presence.

The `SqlSyntaxClassifier` dispatches on the `SQLStatement` subclass. Track 4 ships full coverage of every concrete `SQLStatement` subclass present in `core/.../parser/` (~65 subclasses) so `db.collection.name` is populated for the YTDB-specific writes operators most want to trace (CREATE VERTEX, DELETE EDGE, TRUNCATE CLASS, etc.) and not just for the SQL-standard SELECT / INSERT / UPDATE / DELETE shapes:

| Statement family | Operation name | Collection name source |
|---|---|---|
| `SQLSelectStatement`, `SQLQueryStatement` | `SELECT` | first FROM target class, else `Optional.empty()` |
| `SQLSelectWithoutTargetStatement` | `SELECT` | `Optional.empty()` (no target) |
| `SQLMatchStatement` | `MATCH` | first pattern node's class, else `Optional.empty()` |
| `SQLInsertStatement` | `INSERT` | INTO target class |
| `SQLUpdateStatement`, `SQLUpdateEdgeStatement` | `UPDATE` | UPDATE target class |
| `SQLDeleteStatement`, `SQLDeleteVertexStatement` | `DELETE` | DELETE target class |
| `SQLDeleteEdgeStatement`, `SQLDeleteEdgeByRidStatement`, `SQLDeleteEdgeFromToStatement`, `SQLDeleteEdgeToStatement`, `SQLDeleteEdgeVToStatement`, `SQLDeleteEdgeWhereStatement` | `DELETE EDGE` | edge class from the statement, else `Optional.empty()` |
| `SQLCreateVertexStatement` | `CREATE VERTEX` | vertex class from the statement |
| `SQLCreateEdgeStatement` | `CREATE EDGE` | edge class from the statement |
| `SQLCreateClassStatement` | `CREATE CLASS` | class name from the statement |
| `SQLCreatePropertyStatement`, `SQLCreatePropertyAttributeStatement` | `CREATE PROPERTY` | property class name |
| `SQLCreateIndexStatement` | `CREATE INDEX` | index target class, else index name |
| `SQLCreateLinkStatement` | `CREATE LINK` | link class name |
| `SQLCreateSequenceStatement` | `CREATE SEQUENCE` | sequence name |
| `SQLCreateFunctionStatement` | `CREATE FUNCTION` | function name |
| `SQLCreateUserStatement`, `SQLCreateSystemUserStatement` | `CREATE USER` | user name |
| `SQLCreateSecurityPolicyStatement` | `CREATE SECURITY POLICY` | policy name |
| `SQLCreateDatabaseStatement` | `CREATE DATABASE` | database name |
| `SQLAlterClassStatement` | `ALTER CLASS` | class name from the statement |
| `SQLAlterPropertyStatement` | `ALTER PROPERTY` | property class name |
| `SQLAlterDatabaseStatement` | `ALTER DATABASE` | database name |
| `SQLAlterSequenceStatement` | `ALTER SEQUENCE` | sequence name |
| `SQLAlterRoleStatement`, `SQLAlterSystemRoleStatement` | `ALTER ROLE` | role name |
| `SQLAlterSecurityPolicyStatement` | `ALTER SECURITY POLICY` | policy name |
| `SQLDropClassStatement` | `DROP CLASS` | class name from the statement |
| `SQLDropPropertyStatement` | `DROP PROPERTY` | property class name |
| `SQLDropIndexStatement` | `DROP INDEX` | index name |
| `SQLDropSequenceStatement` | `DROP SEQUENCE` | sequence name |
| `SQLDropUserStatement` | `DROP USER` | user name |
| `SQLDropDatabaseStatement` | `DROP DATABASE` | database name |
| `SQLTruncateClassStatement` | `TRUNCATE` | class name from the statement |
| `SQLAnalyzeIndexStatement` | `ANALYZE INDEX` | index name |
| `SQLRebuildIndexStatement` | `REBUILD INDEX` | index name |
| `SQLGrantStatement`, `SQLRevokeStatement` | `GRANT` / `REVOKE` | role or user target |
| `SQLBeginStatement`, `SQLCommitStatement`, `SQLRollbackStatement` | `BEGIN` / `COMMIT` / `ROLLBACK` | `Optional.empty()` |
| `SQLIfStatement`, `SQLLetStatement`, `SQLReturnStatement`, `SQLExpressionStatement` | `IF` / `LET` / `RETURN` / `EXPRESSION` | `Optional.empty()` (control flow has no target) |
| `SQLExplainStatement`, `SQLProfileStatement`, `SQLProfileStorageStatement` | `EXPLAIN` / `PROFILE` | inner-statement collection name when reachable, else `Optional.empty()` |
| `SQLOptimizeDatabaseStatement` | `OPTIMIZE DATABASE` | database name |
| `SQLConsoleStatement`, `SQLSleepStatement`, `SQLServerStatement`, `SQLSimpleExecStatement`, `SQLSimpleExecServerStatement`, `SQLparseServerStatement`, `SQLExistsSystemUserStatement` | matches the statement keyword (`CONSOLE`, `SLEEP`, `SERVER`, etc.) | `Optional.empty()` |
| anything else (the abstract `SQLStatement` base or a future subclass not yet in the dispatch) | `Optional.empty()` | `Optional.empty()` (fail-closed; the sanitizer still renders the AST so `db.query.text` stays sanitized) |

The `SqlSanitizer` walks the parsed `SQLStatement` AST and emits a sanitized rendering with every literal node replaced by `?`. The AST is already available because `SQLEngine.parse(...)` ran unconditionally to execute the query, so the sanitizer reuses it instead of re-scanning the raw text. The walk visits `SQLBaseExpression`, `SQLInputParameter`, `SQLNumber`, `SQLBoolean`, `SQLString`, and the date / timestamp literal nodes the parser produces, rendering each as `?`; structural nodes (FROM targets, WHERE operators, GROUP BY / ORDER BY identifiers) render verbatim. Already-parameterized text (`SQLInputParameter` nodes carrying `:name` / `?` placeholders in the input) renders as `?` in the output, idempotently. The AST walk handles edge cases that regex-based sanitizers leak: doubled single quotes (`'It''s'`), JSON strings inside SELECT projections, and regex predicates in `MATCHES` clauses. The parser already classified those as `SQLString` nodes, so the walk renders them as `?` without re-parsing the quoting rules. Sanitization runs inside `QueryDetails.getQuery()` on first access; hosts that never read the accessor pay no walk cost.

### Edge cases / Gotchas

- `stringStatement` can be null when an internal recursive call passes a pre-parsed `SQLStatement`. The wrapper falls back to `statement.getOriginalStatement()` for the raw SQL. If both are null (unusual), the wrapper emits `db.query.text=""` and the span still carries operation / collection.
- DDL statements have no literals to sanitize. `CREATE INDEX User.email UNIQUE` passes through `SqlSanitizer` unchanged.
- A statement with multi-target FROM (`SELECT FROM User, Order WHERE ...`) yields `collectionName = "User"` (first wins) per sem-conv guidance to keep cardinality low. An anonymous FROM subquery yields `Optional.empty()`.
- The transaction tracking ID comes from `currentTx.getTrackingId()` (the lifted accessor on `FrontendTransaction` per §"Core Concepts" Explicit transaction tracking ID), which returns the explicit ID set via `YTDBTransaction.withTrackingId(...)` when present and `String.valueOf(getId())` otherwise. `FrontendTransaction.getId(): long` (line 215) is still the fallback source; the lift onto `FrontendTransaction` is the only API-surface addition for tracking-ID propagation.
- An exception thrown during stream iteration (inside `hasNext` / `next`) propagates to the caller. The wrapper captures the exception class on its way out and surfaces it through `QueryDetails.getErrorType()` when `close()` fires the listener; the span carries status ERROR and `error.type`. If the consumer abandons iteration without calling `close()` (rare; `try-with-resources` is the documented usage), no listener fire happens — the same drop-rate the existing per-traversal Gremlin path already has. Under YTDB-820 cache hit the same contract holds because the consumer-facing `CachedResultSetView` is the object the wrapper delegates `close()` to.
- Row count from `QueryDetails.getResultCount()` is the wrapper's own per-`next()` counter. It is correct for every inner result-set type (LocalResultSet, CachedResultSetView, splice-failure fallback) because the count is driven by what the consumer pulled, not by executor internals or cache state.
- Under LIGHTWEIGHT (default), query durations shorter than the ticker's granularity (~10 ms) round to zero or one tick. Acceptable for trace viewers, which render at millisecond resolution anyway. A host that wants sub-millisecond precision for SQL has one route: call `YTDBTransaction.withQueryMonitoringMode(EXACT)` once on the transaction; every SQL statement in that TX inherits the per-TX default. For Gremlin traversals, per-traversal precision is also possible by tagging via `g.with(YTDBQueryConfigParam.querySummary, "findHotpath")` plus a matching rule in `OPENTELEMETRY_QUERY_MODE_TAG_RULES=findHotpath=EXACT`. SQL statements have no equivalent tag source.
- Every SQL statement has `Optional.empty()` for its query tag and resolves to `currentTx.getDefaultQueryMonitoringMode()`. Callers using the `YTDBTransaction.withQueryMonitoringMode(EXACT)` per-TX API see uniform behavior across every SQL query in the TX. Per-query mode resolution from tag rules applies only to Gremlin traversals; SQL statements always use the per-TX default.
- Tag rules are immutable after process startup; mid-TX changes to the rule table are not supported in YTDB-496 (see §"Query tagging and per-tag rule resolution" Edge cases). Mid-TX changes to the per-TX default via `YTDBTransaction.withQueryMonitoringMode(...)` take effect immediately for the next query in the same TX because the wrapper constructor re-reads `getDefaultQueryMonitoringMode()` per query: no snapshot, no `next begin()` cycle latency.
- Span count per query depends on Gremlin path. Path A (translated, post-PR-1038) emits exactly one Gremlin span and no SQL children: the entry-point re-entry counter from the §TL;DR above suppresses the second wrapper fire when a UDF, sub-query expression, or context-variable resolution re-enters the SQL entry points with `sqlEntryDepth[0] > 1`. The inner work still runs and its duration accrues to the outer Gremlin span; the operator sees one span per outer traversal. Path B (fallback) emits one parent Gremlin span and one child SQL span (the SQL fire here is the primary entry, not re-entry, so the counter does not suppress it); the child carries the sanitized SQL translated from Gremlin and signals to operators that the traversal fell back to per-step SQL rather than benefiting from MATCH planning. Direct SQL emits one SQL span. DDL emits one SQL span. Multi-statement scripts emit one SQL span per script call. The Path B redundancy is intentional diagnostic information rather than noise.
- Multi-statement scripts via `DatabaseSessionEmbedded.computeScript(language, script, args)` (script-engine method declarations at lines 753 and 813; returns at lines 785 and 847) emit exactly one SQL span per script call because Track 4 instruments `computeScript` itself with its own `InstrumentedSqlResultSet.wrapIfListening(...)` call (the script executor builds a `ScriptExecutionPlan` and drives `ScriptLineStep` for each inner statement without re-entering the three SQL entry points, so per-script instrumentation is the correct site). Inner statements accrue to the outer span's duration; the wrapper carries `statement=null` and the classifier returns `Optional.empty()`. The outer span's `db.query.text` carries the full script text. Per-statement breakdown of a script is a future-ticket concern.
- Consumer patterns: every read pathway on the wrapper routes through the wrapper's own `hasNext()` / `next()`. `tryAdvance(Consumer)`, `forEachRemaining(Consumer)`, `stream()`, `toList()`, `findFirst()`, `findFirstOrNull()`, `detach()`, `toDetachedList()`, `detachedStream()`, and every other `BasicResultSet` accessor that drives the `Spliterator` / `Iterator` surface all see the wrapper's row counter and error capture. Without explicit `tryAdvance` / `forEachRemaining` overrides, the dominant YTDB consumer patterns (`.toList()`, `.stream().forEach(...)`) would silently bypass the counter and report `OptionalLong.of(0)` regardless of actual row count. The wrapper overrides both methods to route through its own `next()` / `hasNext()`.
- Cross-thread `close()`: `InstrumentedSqlResultSet.close()` is safe on any thread. The wrapper captures the parent `Context` at construction (`capturedContext`, on the transaction's owner thread) and hands it to the listener through `QueryDetails.getParentContext()`; the listener builds the SQL span with `setParent(capturedContext)`, an explicit parent that neither reads nor mutates any `ThreadLocal`. `close()` therefore performs no `makeCurrent()` and no `Scope.close()`, so OTel's `ThreadLocalContextStorage` emits no no-op-warn and the SQL span's parent linkage is guaranteed even when a host consumes and closes the result-set on a different thread than constructed it (a thread-pool task, a `CompletableFuture` continuation). No `constructionThread` field, no Java `assert`, and no `db.youtrackdb.span.thread_mismatch` attribute are needed: the explicit parent removes the failure mode they would have flagged. Track 6c's `OTelResultSetThreadAffinityTest` asserts the guarantee directly: a wrapper constructed on one thread and closed on another emits a span whose parent is the construction-time context (`Span.fromContext(capturedContext)`), with no warning logged on the closing thread.

### References
- D-records: D8, D9, D15, D39 (Gremlin DSL service-call SQL paths reach `executeInternal()` automatically via `session.execute(SQLStatement, Map)` / `schemaSession.command(SQLStatement, Map)`)
- Invariants: Listener exception isolation, Per-fire-site Timing-mode uniformity (each fire site resolves its own mode from its own tag source; under Path B the two spans may use different modes, which is acceptable)
- Mechanics: none

## Slow-query threshold gating

**TL;DR.** A per-query duration threshold inside `OTelQueryMetricsListener` drops spans for fast successful queries before any allocation. Global default `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_MILLIS=0` (emit-all; operators opt into a positive value such as `100` to drop fast queries on read-heavy workloads); per-tag overrides through `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_TAG_RULES` resolve against a process-global `SlowQueryThresholdResolver` consuming `TagRule<Long>` on the same sealed interface that powers mode resolution. Per-tag overrides apply only to Gremlin traversals (the sole tag source); SQL statements pass `Optional.empty()` and always resolve to the global default. Errors bypass the gate so failure investigations stay visible in trace viewers regardless of duration.

Configuration entries:

- `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_MILLIS` (default `0`) — global default wall-clock minimum duration in milliseconds. `0` (default) emits every query that reaches the listener; operators on read-heavy workloads opt into a positive value to gate emission on `executionTimeNanos < threshold` for successful queries.
- `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_TAG_RULES` (default empty) — per-tag overrides, same format as `OPENTELEMETRY_QUERY_MODE_TAG_RULES`. Comma-separated, first-wins, with `tag=ms` for exact match, `prefix:X=ms` for prefix match, `regex:X=ms` for regex match. Example: `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_TAG_RULES=findActiveUsers=50,prefix:batch-=1000,regex:^report-.*$=500`. A tag whose rule matches uses that millisecond value; an unset or unmatched tag falls back to the global default.

Mechanism overview: the gate sits inside `OTelQueryMetricsListener.queryFinished`, evaluated before any span allocation, reading the per-query threshold from `SlowQueryThresholdResolver.global().resolve(tag, defaultNanos)` where `tag` is the Gremlin `querySummary` (from `g.with(YTDBQueryConfigParam.querySummary, ...)`) for traversals and `Optional.empty()` for SQL statements. The commit fire site is not threshold-gated; commit duration is operationally interesting at every length, and per-TX volume is bounded by transaction count rather than query count.

Pseudo-code, the in-listener-placement rationale, edge cases (cache cardinality, mid-process rule changes, custom-listener interaction), and the follow-up note for commit-side gating live in [`design-mechanics.md §"Slow-query threshold gating"`](design-mechanics.md).

### References
- D-records: D16 (slow-query threshold inside OTel listener, error bypass, per-tag rules via `TagRule<Long>`)
- Invariants: Listener exception isolation (the gate sits inside the try/catch wrapper at the fire site, so an exception in the threshold comparison cannot unwind the transaction)
- Mechanics: [`design-mechanics.md §"Slow-query threshold gating"`](design-mechanics.md)

## Time-based sampling

**TL;DR.** A single global wall-clock heartbeat gate inside `OTelQueryMetricsListener` emits one span every `N` ms regardless of query duration, so operators see a representative sample of the workload even when the slow-query gate filters out everything fast. Toggleable via `OPENTELEMETRY_QUERY_HEARTBEAT_SAMPLE_MILLIS` (default `0` = disabled). No per-tag override: heartbeat samples the workload as a whole, and operators wanting per-tag streams configure downstream OTel pipeline filtering on `db.query.summary`. Composes disjunctively with the slow-query gate: a query emits if either gate passes, so heartbeat picks fast queries while slow-query still catches latency outliers.

Configuration entry:

- `OPENTELEMETRY_QUERY_HEARTBEAT_SAMPLE_MILLIS` (default `0`) — global wall-clock interval in milliseconds between heartbeat emissions. Positive values cause `OTelQueryMetricsListener.queryFinished` to emit a span for the first successful query that arrives more than `N` ms after the last heartbeat emission, then advance the heartbeat clock. `0` disables the heartbeat gate entirely; only the slow-query gate emits.

Mechanism overview: the heartbeat gate runs at the top of `OTelQueryMetricsListener.queryFinished`, BEFORE the slow-query gate, with disjunctive composition (a query emits if either gate passes). The interval comes from a single `defaultHeartbeatNanos` final field populated at SDK init; no resolver lookup, no tag read. An `AtomicLong.compareAndSet` on `lastHeartbeatNanos` resolves the multi-thread race so exactly one query per heartbeat window claims the slot; losers fall through to the slow-query gate.

Pseudo-code, the CAS rationale, and edge cases (error-bypass clock semantics, lookup-cost lower bound, custom-listener interaction) live in [`design-mechanics.md §"Time-based sampling"`](design-mechanics.md).

### References
- D-records: D18 (time-based heartbeat sampling alongside slow-query gate, AtomicLong CAS for race-free single-emit per window, single global interval)
- Invariants: Listener exception isolation (the heartbeat CAS sits inside the same try/catch wrapper at the fire site), Heartbeat-and-threshold disjunctive composition (one successful query emits at most once even when both gates would pass)
- Mechanics: [`design-mechanics.md §"Time-based sampling"`](design-mechanics.md)

## Commit slow-query gating

**TL;DR.** A wall-clock duration threshold inside `OTelTransactionMetricsListener` drops spans for fast successful commits before any allocation, mirroring the query-side slow-query gate. Global default `OPENTELEMETRY_COMMIT_SLOW_THRESHOLD_MILLIS=0` emits every successful commit (backwards-compatible with today's always-emit behavior); positive values gate emission on `executionTimeNanos < threshold` for successful commits only. Failed commits (`writeTransactionFailed`) always bypass the gate so failure investigations stay visible in trace viewers regardless of duration. No per-tag override and no heartbeat variant: commits fire at the transaction boundary with no per-statement tag source, and commit volume is bounded by transaction count rather than query count, so the wall-clock heartbeat the query gate offers does not buy operators visibility they cannot already get from "emit every commit" (the default).

Configuration entries:

- `OPENTELEMETRY_COMMIT_SLOW_THRESHOLD_MILLIS` (default `0`) — global wall-clock minimum duration in milliseconds for successful commit spans. `0` (the default) emits every successful commit, preserving today's behavior. Positive values gate emission on `executionTimeNanos < threshold` for successful commits. Failed commits emit unconditionally.

Mechanism overview: the gate sits inside `OTelTransactionMetricsListener.writeTransactionCommitted`, evaluated before any span allocation. `OTelTransactionMetricsListener` gains a `long defaultCommitThresholdNanos` constructor argument populated by `YouTrackDBOpenTelemetry` from the config entry (multiplied to nanoseconds at SDK init). The check is `if (defaultCommitThresholdNanos > 0 && executionTimeNanos < defaultCommitThresholdNanos) return;` before the span builder runs. `writeTransactionFailed` is unchanged: every failed commit emits and carries `error.type` from the caught cause.

The contrast with the query-side default is intentional. Queries default to `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_MILLIS=0` (emit-all) so operators see traffic immediately on opt-in; read-heavy workloads tune the value upward to drop fast successful queries. Commits stay operationally interesting at any duration (fast commits show throughput; slow commits show lock or fsync contention), so the commit-side default is `0` for symmetric "emit every one" semantics and operators opt into a positive threshold via `OPENTELEMETRY_COMMIT_SLOW_THRESHOLD_MILLIS` only when commit volume becomes the bottleneck.

Pseudo-code, edge cases (default-zero short-circuit, no per-tag rules in v1, future per-database threshold extension, custom-listener interaction), and the rationale for the listener-side placement live in [`design-mechanics.md §"Commit slow-query gating"`](design-mechanics.md).

### References
- D-records: D38 (commit-side slow-query threshold inside OTel listener, error bypass, single global threshold, no per-tag rules in v1)
- Invariants: Listener exception isolation (the gate sits inside the existing try/catch wrapper at the fire site)
- Mechanics: [`design-mechanics.md §"Commit slow-query gating"`](design-mechanics.md)

## OpenTelemetry logs integration

**TL;DR.** YTDB's process-global `LogManager.instance()` extends `SLF4JLogManager`, whose single `log(...)` method is the one site every log call from `core`, `embedded`, `server`, and the OTel module crosses on its way to SLF4J. A new `LogAppenderHook` interface in `core/.../common/log/` is invoked at `SLF4JLogManager.log:98` (immediately before `logEventBuilder.log()`); the OTel module ships `OTelLogAppender` as the only built-in implementation. The hook fires regardless of which SLF4J binding the host has bound (server: `slf4j-jdk14`; embedded: typically Logback or `log4j-slf4j-impl`), so OTel log emission no longer depends on JUL being on the dispatch path. The hook reads `Context.current()` at invocation time, so any log emitted inside a query- or transaction-listener span scope carries the active `traceId`/`spanId` automatically (hard-context correlation). A severity floor (`OPENTELEMETRY_LOGS_MIN_SEVERITY`, default `INFO`) drops below-threshold records before any OTel allocation, and the master `OPENTELEMETRY_LOGS_ENABLED` flag (default `false`) gates the hook so the existing log path is untouched until an operator opts in.

**PII guard for the log body.** The OTel log `body` defaults to the SLF4J format string (`"Query failed: {} with args {}"`), not the formatted message (`"Query failed: SELECT FROM User WHERE id = 42 with args [42]"`). The default matches the trace pillar's `OPENTELEMETRY_QUERY_INCLUDE_PARAMETERS=false` discipline and keeps raw SQL, record content, user identifiers, and exception messages out of the log body slot. A new opt-in `OPENTELEMETRY_LOGS_INCLUDE_MESSAGE_BODY` (default `false`) ships the full formatted message when flipped to `true`; hosts that flip it accept the same PII risk the trace-side `OPENTELEMETRY_QUERY_INCLUDE_PARAMETERS=true` opt-in carries. Hosts in PII-regulated environments who need full message bodies install a host-side OTel `LogRecordProcessor` that redacts at the collector boundary; the YTDB-side flag is a default-deny knob, not a redaction mechanism. The `exception.stacktrace` and `exception.message` attributes are NOT gated by the same flag because the throwable identity is the load-bearing diagnostic signal for failure investigation; redacting those is also the collector-side `LogRecordProcessor`'s job.

Configuration entries:

- `OPENTELEMETRY_LOGS_ENABLED` (default `false`) — master switch for OTel log emission. When `false`, no `LogAppenderHook` is installed and the existing SLF4J-binding output path is the only sink. When `true` and `OPENTELEMETRY_ENABLED=true`, SDK init constructs an `OTelLogAppender` and registers it via `SLF4JLogManager.installAppenderHook(LogAppenderHook)`.
- `OPENTELEMETRY_LOGS_MIN_SEVERITY` (default `INFO`) — minimum severity that reaches the OTel pipeline. Accepts `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL` (case-insensitive). Records below the threshold are dropped at the top of `onLog(...)` before any `LogRecordBuilder` allocation. Independent from the SLF4J binding's own level filter: SLF4J continues to dispatch every record at or above its bound level; the severity floor only filters what OTel sees.
- `OPENTELEMETRY_LOGS_INCLUDE_MESSAGE_BODY` (default `false`) — body-policy default-deny. When `false`, `OTelLogAppender.onLog(...)` sets the OTel `body` slot from the SLF4J format string (`"Query failed: {} with args {}"`), keeping parameter values out of the OTel log pipeline. When `true`, sets `body` from the already-formatted message (parameter values substituted in). Matches the trace pillar's `OPENTELEMETRY_QUERY_INCLUDE_PARAMETERS=false` default. Hosts in PII-regulated environments leave this `false` and install a host-side OTel `LogRecordProcessor` for any redaction needed at the collector boundary; the hook signature carries both strings on every call so the choice is per-record (cost is one boolean check at emit time).
- `OPENTELEMETRY_LOGS_LOGGER_EXCLUSIONS` (default `io.opentelemetry.,io.youtrackdb.otel.appender`) — comma-separated requester-logger-name prefixes whose records bypass the hook iteration. Defends against the cross-thread recursive-logging cycle that the OTel exporter creates when the host has a `jul-to-slf4j` bridge installed: the exporter writes a log on its own thread pool via JUL, the bridge routes it into SLF4J and back into `LogManager`, where the per-thread `ThreadLocal` re-entrance guard in `OTelLogAppender.onLog(...)` does not apply because we are now on a different thread. Filtering by requester prefix on the `SLF4JLogManager` side, before the hook iteration, prevents the cycle without requiring per-binding configuration.

Mechanism overview: `OTelLogAppender` implements `LogAppenderHook` and is registered against `SLF4JLogManager` via `installAppenderHook(LogAppenderHook)`. Each call to `SLF4JLogManager.log(...)` checks the `requesterName`-prefix exclusion list first; records that match are emitted by SLF4J normally but skip every installed hook. Otherwise the manager invokes every installed hook's `onLog(requesterName, dbName, slf4jLevel, formatString, formattedMessage, thrown, eventEpochNanos)` after the per-logger `isEnabledForLevel(level)` filter SLF4J already applies and before `logEventBuilder.log()` fires the SLF4J emit. Each hook receives both the SLF4J format string and the corresponding formatted message (the hook picks the body shape its body-policy demands), the SLF4J `LoggingEvent` timestamp lifted from milliseconds to nanoseconds (the SLF4J facade captures it at the original log-call site, not at hook invocation), the resolved database name, and the optional `Throwable`. The hook maps the slf4j `Level` to an OTel `severityNumber`, applies the severity floor, captures `Context.current()` for trace correlation, picks the body slot per `OPENTELEMETRY_LOGS_INCLUDE_MESSAGE_BODY` (format string when `false` — the default; formatted message when `true`), and emits via `LogRecordBuilder.setSeverity(...).setTimestamp(eventEpochNanos, NS).setObservedTimestamp(...).setBody(body).emit()`. The sem-conv `Timestamp` field carries the original log-call wall-clock; `ObservedTimestamp` carries the hook-invocation wall-clock. A thread-local re-entrance guard catches the same-thread version of the cycle.

slf4j→OTel severity mapping table, full pseudo-implementation, the cross-thread-correlation sub-section, and edge cases (SLF4J-NoOp binding, high-frequency records, hook-exception isolation, SDK swap window, bootstrap-time logs, recursive-logging cycle) live in [`design-mechanics.md §"OpenTelemetry logs integration"`](design-mechanics.md).

### References
- D-records: D34 (single-chokepoint `LogAppenderHook` invoked inside `SLF4JLogManager.log`, severity-floor pre-filter, hard-context inheritance from `Context.current()`), D35 (thread-local re-entrance guard against recursive logging from inside the OTel exporter)
- Invariants: Listener exception isolation (the hook follows the same isolation contract; a throw from `onLog` is caught and reported via a fallback `Logger.getLogger("io.youtrackdb.otel.appender")` warn call)
- Mechanics: [`design-mechanics.md §"OpenTelemetry logs integration"`](design-mechanics.md)

## Metrics integration

**TL;DR.** YTDB ships an internal profiler (`internal.common.profiler.Profiler`) whose `MetricsRegistry` (`CoreMetrics.java`) holds two existing rates worth surfacing to operators today — `DiskCacheHitRatio` (global) and `TransactionRate` / `TransactionWriteRate` / `TransactionWriteRollbackRate` (per-database) — plus the disk-read / disk-write byte rates and the stale-TX-monitor gauges. Track 8 grows the inventory with the operator-facing counters this design also surfaces (page-read count, WAL pending bytes, WAL flush rate, lock-contention count, per-database storage size) and adds a `MetricGroup` enum to every `MetricDefinition`. A new `OTelMetricsBridge` reads `Profiler.getMetricsRegistry()` through OTel async instruments (`ObservableLongCounter`, `ObservableLongGauge`, `ObservableDoubleHistogram`) at a configurable period and surfaces the curated set under the OpenTelemetry sem-conv DB metric names (where v1.33.0 defines one as stable) plus the `youtrackdb.*` namespace for vendor-specific gauges. The bridge consumes two new public-API surfaces on `MetricsRegistry` itself: `forEachMetric(MetricVisitor)` for enumeration at `start()` time and `addRegistrationListener(Consumer<MetricRegistrationEvent>)` so per-database metrics created lazily after `start()` register their OTel callback on first emit instead of being silently dropped. `OPENTELEMETRY_METRICS_ENABLED=false` (default) keeps the bridge unwired so the profiler's existing in-JVM consumers are untouched; positive `OPENTELEMETRY_METRICS_PERIOD_MILLIS` drives the collection cadence; `OPENTELEMETRY_METRICS_INCLUDED_GROUPS` lets operators opt into subsets to bound exporter cardinality.

Configuration entries:

- `OPENTELEMETRY_METRICS_ENABLED` (default `false`) — master switch for OTel metrics emission. When `false`, the bridge is never started and no `ObservableInstrument` callbacks register; the existing `Profiler.getMetricsRegistry()` continues to serve any in-JVM consumers (JMX, custom listeners) unchanged.
- `OPENTELEMETRY_METRICS_PERIOD_MILLIS` (default `10000`) — collection period in milliseconds, fed to the SDK's `PeriodicMetricReader`. Matches OTel's typical 10-second cadence and aligns with the default exporter push interval. Values below `1000` are clamped to `1000` at SDK init to prevent runaway export volume.
- `OPENTELEMETRY_METRICS_INCLUDED_GROUPS` (default empty = all groups enabled when metrics ON) — comma-separated allow-list of metric groups; recognized values are `queries`, `cache`, `storage`, `wal`, `locks`, `transactions`. Empty value means all groups; a non-empty value restricts the bridge to only register `ObservableInstrument` callbacks for counters whose group tag matches. Unrecognized group names log one WARN line and are ignored.

Mechanism overview: `OTelMetricsBridge.start()` calls `Profiler.getMetricsRegistry().forEachMetric(...)` to enumerate every metric registered at SDK init time, builds a `Map<String, ObservableInstrument>` keyed by OTel metric name, registers each callback against the `SdkMeterProvider`'s `Meter`, and subscribes a `Consumer<MetricRegistrationEvent>` via `addRegistrationListener(...)` so per-database metrics created after `start()` (every time a new database opens) auto-register their OTel callback on the registration event. `Meter` flushes each `TimeRate`/`Ratio` rate window internally every `flushRateTicks`, so the bridge reads current rates through `getRate()`/`getRatio()`; it also schedules a `ScheduledExecutorService` task to re-read those windows independently of the OTel `PeriodicMetricReader` cadence (the task reads only, it does not mutate metric state). A `MetricGroup` annotation on `MetricDefinition` (added by Track 8 — an enum with values `QUERIES`, `CACHE`, `STORAGE`, `WAL`, `LOCKS`, `TRANSACTIONS`) drives the `OPENTELEMETRY_METRICS_INCLUDED_GROUPS` filter.

The full sem-conv v1.33.0 / `youtrackdb.*` counter inventory (two tables with stable/experimental markers and existing-vs-new annotations), the `forEachMetric` / `addRegistrationListener` API shape on `MetricsRegistry`, the start/stop/refresh lifecycle, the six-group taxonomy, and edge cases (high-cardinality fan-out, profiler-thread contention, exporter back-pressure, SDK swap window, disabled-with-host-SDK semantics, bootstrap race, late-DB-open registration event) live in [`design-mechanics.md §"Metrics integration"`](design-mechanics.md).

### References
- D-records: D36 (OTelMetricsBridge surfaces `Profiler.getMetricsRegistry()` via OTel async instruments at a configurable period, with a scheduled task re-reading YTDB-side `TimeRate`/`Ratio` rates, flushed internally by `Meter`, independently of the OTel reader), D37 (group-based opt-in via `OPENTELEMETRY_METRICS_INCLUDED_GROUPS` with six-group taxonomy `queries`/`cache`/`storage`/`wal`/`locks`/`transactions`)
- Invariants: Listener exception isolation (callback exceptions inside `ObservableInstrument` callbacks are caught and reported via OTel's own error handler, never propagating to the profiler), Counter source untouched (the bridge reads; the profiler keeps writing on its own threads independent of OTel state)
- Mechanics: [`design-mechanics.md §"Metrics integration"`](design-mechanics.md)

## Quick-start observability stack (operator example)

**TL;DR.** YTDB-496 ships a self-contained docker-compose example under `youtrackdb-opentelemetry/examples/docker-compose/` that brings up the full open-source OTel viewer stack (OTel Collector, Jaeger, Loki, Prometheus, Grafana) with one command, points YTDB at it via a sample `youtrackdb.properties`, and renders three pre-provisioned Grafana dashboards plus trace-to-logs and trace-to-metrics correlators. The example is the load-bearing surface that turns the twenty `OPENTELEMETRY_*` config entries Track 5 ships into a five-minute clone-to-first-span experience. It exists for two operator-facing reasons (verifiable claim that YTDB-OTel works end-to-end across all three pillars; a working open-source path that matches the Jaeger / Tempo / Datadog backends the PR description names) and one engineering reason (a smoke script that exits non-zero when traces / logs / metrics fail to land, giving the optional CI job a deterministic signal).

The stack is **example-files-only**: zero source-code edits in `core`, `server`, or the OTel module. Track 11 contributes a directory tree under `youtrackdb-opentelemetry/examples/docker-compose/` plus an optional, label-gated CI workflow under `.github/workflows/otel-example-smoke.yml`. The directory is excluded from the OTel module's Maven `<resources>` so `mvn package` does not copy the example into the built JAR; the example ships as source-tree files discovered by operators who clone the repo or browse the GitHub UI.

Five services, each on a pinned image version with a healthcheck:

| Service | Image (pinned) | Host port | Role |
|---|---|---|---|
| OTel Collector | `otel/opentelemetry-collector-contrib:0.110.0` | `4317` (gRPC), `4318` (HTTP) | Receives OTLP from YTDB; fans out to Jaeger, Loki, Prometheus exporter |
| Jaeger | `jaegertracing/all-in-one:1.62` | `16686` | Traces UI; accepts OTLP from the Collector internally |
| Loki | `grafana/loki:3.2.0` | `3100` | Logs backend; accepts OTLP HTTP from the Collector |
| Prometheus | `prom/prometheus:v2.55.0` | `9090` | Metrics backend; scrapes the Collector's `/metrics` endpoint at 15-second interval |
| Grafana | `grafana/grafana:11.3.0` | `3000` | UI with provisioned datasources and dashboards |

Three pre-provisioned Grafana dashboards as committed JSON under `grafana/dashboards/`:

- **Overview** — query span throughput, P50 / P95 / P99 latency, error rate, top 10 slowest queries (Jaeger embed sorted by duration), top 10 collections by span count, error-log rate from Loki, severity distribution pie.
- **Queries** — per-operation breakdown (`db.operation.name` SELECT / INSERT / UPDATE / DELETE / MATCH), per-collection breakdown (`db.collection.name`), slow-query gate hit rate, and the six `db.youtrackdb.*` vendor-attribute distributions from D19 (`where_present`, `order_present`, `limit_present` ratio gauges plus `from_class_count`, `step_count`, `has_subtraversal` histograms).
- **Storage** — the six metric groups from Track 8 (`queries`, `cache`, `storage`, `wal`, `locks`, `transactions`): cache hit ratio, page read rate, WAL pending bytes plus flush rate, lock-wait count, database size growth.

Trace correlation: the Jaeger datasource carries a `tracesToLogsV2` correlator pointing at Loki by `trace_id` and a `tracesToMetrics` correlator pointing at Prometheus by `service.name`, so an operator clicking a span lands in the matching Loki query and Prometheus panel without manual query construction.

Operator deliverables alongside the stack:

- `README.md` walks an operator from clone to first span: prerequisites (Docker plus Compose v2), one-command bring-up (`docker compose up -d --wait`), link table to the four UIs, the YTDB-side `youtrackdb.properties` snippet that points YTDB at the local Collector, troubleshooting for three common failure modes (Collector won't start because port `4317` is taken; Grafana shows "no data" because YTDB has not run a query yet; Loki rejects logs because of an OTLP HTTP path mismatch), one-command tear-down.
- `sample-youtrackdb.properties` carries every `OPENTELEMETRY_*` setting Track 5 introduces with the defaults Track 5 ships, plus commented-out lines for the gating entries operators commonly tune (`OPENTELEMETRY_QUERY_SLOW_THRESHOLD_MILLIS`, `OPENTELEMETRY_QUERY_HEARTBEAT_SAMPLE_MILLIS`, `OPENTELEMETRY_METRICS_PERIOD_MILLIS`, `OPENTELEMETRY_LOGS_MIN_SEVERITY`).
- Four shell scripts under `scripts/`: `up.sh` and `down.sh` (thin wrappers around `docker compose up -d --wait` / `docker compose down -v`), `logs.sh` (`docker compose logs -f --tail=100`), and `smoke.sh` running a minimal embedded YTDB query against the stack and exiting non-zero if no spans land in Jaeger within 30 seconds.

Hosted-backend substitution: operators on Honeycomb, Grafana Cloud, or Datadog substitute their exporter endpoint into `OPENTELEMETRY_EXPORTER_ENDPOINT`, drop their auth token into `OPENTELEMETRY_EXPORTER_HEADERS=Authorization=Bearer <token>`, and shut down the local stack (`scripts/down.sh`). The YTDB side does not change. The hosted-backend README is deferred to a follow-up ticket because each backend's auth scheme deserves its own setup notes the local-dev example would clutter.

### Production-vs-local-dev trade-offs

The stack targets local development and smoke testing, not production. Three deliberate omissions an operator productionizing YTDB-OTel must address before deployment:

- **No authentication on the Collector's OTLP receivers**. A production Collector terminates TLS and authenticates the YTDB client. The example binds the receivers to `127.0.0.1` only so the local-only trade-off is safe; operators exposing the Collector beyond localhost MUST add `tls` and `auth` extensions to the Collector config.
- **No retention or storage tuning**. Loki and Prometheus run with default retention (Loki 7 days, Prometheus 15 days) and filesystem storage. Production deployments use object-storage backends (S3 / GCS) and tuned retention; the example's default suits a workstation demo for hours-to-days of investigation.
- **No alerting rules**. The example ships datasources and dashboards but no Grafana alert rules. The "queries with error rate above 1 percent" or "commit P99 above 100 ms" rules an operator wants are workload-dependent and stay out of the example. Track 11's README points at Grafana's alerting docs and links to the metric names the rules would target.

### Component selection: why Jaeger and Loki (not Tempo, not Elasticsearch)

Jaeger over Tempo for traces: simpler single-binary deployment (Tempo splits the ingester, querier, and compactor into three processes for production storage tiers, none of which a local-dev example needs), OTLP receiver native since Jaeger 1.35, built-in UI sufficient for the demo. Operators preferring Tempo substitute one image and the Collector's `otlp/jaeger` exporter; the YTDB side does not change.

Loki over an Elasticsearch-style log backend for logs: works through the Collector's `otlphttp/loki` exporter without an additional Fluent Bit / Fluentd hop, and Grafana's Loki datasource is the same UI surface as the Jaeger and Prometheus datasources (one viewer, three signal types). An Elasticsearch backend would need a separate Kibana for the log UI, breaking the single-Grafana experience the dashboards rely on.

### Edge cases / Gotchas

- **Port `4317` already in use** (most common failure on developer laptops running other OTel-instrumented apps). `scripts/up.sh` checks the port before starting and prints a remediation hint (override via `OTLP_GRPC_HOST_PORT` env var that the compose file consumes for the host-side mapping).
- **Grafana "no data" right after bring-up**. The stack is up but YTDB has not emitted yet because the host process is not running. The README's troubleshooting bullet covers this with the verification sequence: run `scripts/smoke.sh` first, which emits a known query and confirms a span lands in Jaeger before the operator-supplied workload runs.
- **Image version drift over time**. The pinned tags chosen at Track 11 implementation time bind the example to one known-working set. The Collector config schema changes between minor versions; an unattended `latest` tag bump can silently break the example. Dependabot is not configured against this directory by design; a follow-up ticket (`YTDB-OTel-EXAMPLE-VERSIONS`) covers the periodic refresh with an explicit smoke-script gate.
- **No interaction with `mvn package`**. The directory is excluded from the OTel module's Maven `<resources>` so the example does not ship inside the built JAR. Operators get the example by cloning the repo, not by depending on the published artifact.
- **Coexistence with host-managed OTel stack**. A host running its own Collector / viewer combo SHOULD NOT also bring up the example stack; both compete for port `4317` and for `service.name="youtrackdb"`. The README documents the override path (env-var port remap plus `OPENTELEMETRY_SERVICE_NAME` change) and recommends leaving the example stack down in that case.

### Future: advisory dashboards (Phase 2)

The MVP dashboards (overview, queries, storage) are **diagnostic** — they show operator what is happening. A follow-up Phase 2 layer (separate ticket, post-YTDB-496) adds **advisory** dashboards that recommend operator-tunable settings based on observed metric and span-attribute patterns. The current PR ships the foundation for Phase 2 without shipping the dashboards themselves; the foundation is two pre-positioned additions that would otherwise require retrofitting Track 1 and Track 8.

**Foundation in this PR (per D42):**

1. **Four advisor-foundation metrics in Track 8** beyond the five baseline operator-diagnostic entries:
   - `cache.eviction_rate` (mapped from `BUFFER_POOL_EVICTION_RATE`) — paired with `cache.hit_ratio`, lets advisory engine distinguish "buffer too small" (high eviction + low hit ratio) from "cold workload" (low eviction + low hit ratio); recommends `STORAGE_DISK_CACHE_BUCKET_SIZE` adjustment.
   - `query.index_lookup_ratio` (mapped from `INDEX_LOOKUP_VS_FULL_SCAN_RATIO`) — per-database ratio of index lookups to full scans; recommends "add index on `<class>.<property>`" when sustained below threshold.
   - `transaction.rollback_rate` (mapped from `TX_ROLLBACK_RATE`) — pre-existing `TransactionWriteRollbackRate` counter newly surfaced to OTel; recommends transaction-scope review when rollback rate is high relative to commit rate.
   - `connection.pool_saturation` (mapped from `CONNECTION_POOL_SATURATION`) — current-vs-max ratio in `[0.0, 1.0]`; recommends `NETWORK_SOCKET_MAX_CONNECTIONS` bump when sustained above 0.8.

   All four piggyback on writer-sites that already exist in the YTDB profiler (eviction site in `DiskCache`, index-step branch in the SQL execution layer, `TransactionWriteRollbackRate` increment site, `DatabaseSessionRegistry` active-count read). Track 8 surfaces them to OTel for the first time; the in-JVM profiler already exposed them via JMX and dump-environment.

2. **The `db.youtrackdb.advisor.*` attribute namespace in §"Sem-conv attribute mapping" → "YouTrackDB vendor attributes (intro)"** carrying two boolean / small-int flags populated by the existing classifier walk (`full_scan`, `cross_class_count`). Each flag maps to a concrete recommendation operator-side ("add index", "split into two queries"). The flags carry zero implementation cost beyond the existing classifier walk because both classifiers already inspect index availability and result-size heuristics for other reasons.

**What Phase 2 adds (deferred to follow-up ticket, NOT in this PR):**

- **Recommendation dashboard** with Grafana stat panels driven by threshold rules — "Cache hit ratio < 80% for 1h" displays a recommendation text panel "Consider increasing `STORAGE_DISK_CACHE_BUCKET_SIZE` from 1024 to 2048".
- **Tuning guide document** mapping every symptom (Grafana metric / advisor flag combination) to its `GlobalConfiguration` tunable with current default and recommended adjustment magnitude.
- **Workload classification panels** synthesizing read-vs-write ratio, latency profile, and connection patterns into a workload class label (`OLTP-read-heavy`, `OLTP-write-heavy`, `analytics`, `mixed`) which the recommendation rules consume.
- **Alert rules with runbook annotations** pointing at the tuning guide so PagerDuty / Slack alerts carry the same recommendation context the dashboards show.

The Phase 2 deferral is intentional — alert thresholds, recommendation magnitudes, and workload-class boundaries need production data to calibrate. Shipping un-calibrated rules carries a real risk of operators acting on misleading recommendations during the first production rollout. The foundation lands now so Phase 2 is a pure addition on top, not a retrofit.

### References
- D-records: D41 (Quick-start observability stack lands inside YTDB-496 PR as an example-files-only deliverable; rationale below), D42 (advisory framework foundation: four metrics + two attribute flags pre-positioned for Phase 2 advisory dashboards)
- Track: Track 11 (full deliverable list, validation criteria, in-scope file list, follow-up tickets); Track 8 (four advisor-foundation metrics); Track 1 (two advisor attribute flags on `Classification`)
- Plan: [`plan/track-11.md`](plan/track-11.md)
- Mechanics: [`design-mechanics.md §"Quick-start observability stack"`](design-mechanics.md) (Collector pipeline shape, trace-to-logs / trace-to-metrics correlator wiring, smoke-script verification semantics)
- Follow-up tickets: `YTDB-OTel-ADVISORY-DASHBOARDS` (Phase 2 advisory dashboard implementation, tuning guide, alert rules, workload classification — calibration depends on production data)

## SDK lifecycle: embedded vs server

**TL;DR.** Hybrid ownership model. The host owns the `OpenTelemetry` instance when it has wired one (either explicitly through the setter or globally via `GlobalOpenTelemetry.set(...)`). When `OPENTELEMETRY_ENABLED=true` and the host has wired nothing, YTDB auto-configures its own SDK from `OPENTELEMETRY_*` config entries — the same path server mode takes. The facade tracks ownership so `shutdown()` closes only the SDK YTDB created. In server mode YTDB always owns the SDK because the server is a standalone process.

Embedded path — three-step resolution on first listener fire:

1. **Explicit setter wins**: if the host called `YouTrackDBOpenTelemetry.setOpenTelemetry(otel)`, use that instance. Ownership = host. `shutdown()` will not close it.
2. **GlobalOpenTelemetry fallback**: if the host called `GlobalOpenTelemetry.set(otel)` somewhere in its bootstrap (the standard OTel pattern), `GlobalOpenTelemetry.get()` returns the real SDK. Use that instance. Ownership = host. `shutdown()` will not close it.
3. **YTDB auto-configure**: if neither of the above produced a real SDK and `OPENTELEMETRY_ENABLED=true`, the facade builds an `OpenTelemetrySdk` via `AutoConfiguredOpenTelemetrySdk` using the `OPENTELEMETRY_*` config entries (endpoint, protocol, service name). The built SDK carries `SdkTracerProvider`, and (when their respective master switches are on) `SdkLoggerProvider` and `SdkMeterProvider`; the autoconfigure builder reads the same `*_EXPORTER_ENDPOINT` / `*_EXPORTER_PROTOCOL` config to route all three signals through one exporter. When `SdkLoggerProvider` is non-noop, the facade also constructs an `OTelLogAppender` from `provider.get("io.youtrackdb")` and registers it on `LogManager.instance()`; when `SdkMeterProvider` is non-noop, the facade also constructs an `OTelMetricsBridge` from `provider.get("io.youtrackdb")` and starts its scheduled refresh task. Ownership = YTDB. `shutdown()` closes this SDK (flushes all three providers), removes the appender, and stops the metrics bridge.

If `OPENTELEMETRY_ENABLED=false` (default), step 3 is skipped and the facade returns no-op tracer; YTDB emits nothing regardless of any host wiring. The flag is the master switch.

Server path:

1. `ServerMain.create()` builds a `YouTrackDBServer` and calls `activate()`.
2. The server discovers `ServerLifecycleListener` implementations via the `ServiceLoader.load(ServerLifecycleListener.class)` call Track 5 adds to `YouTrackDBServer.activate()` (the existing code only honors explicit `registerLifecycleListener(...)` calls), appends them to the existing `lifecycleListeners` list, and calls `onBeforeActivate` on each.
3. After databases load, the server calls `onAfterActivate` on each lifecycle listener.
4. `OpenTelemetryServerPlugin.onAfterActivate()` reads config: if `OPENTELEMETRY_ENABLED=true`, builds an `AutoConfiguredOpenTelemetrySdk` carrying `SdkTracerProvider` and (when the respective master switches are on) `SdkLoggerProvider` and `SdkMeterProvider` from the same exporter endpoint; calls the package-private 3-arg variant `YouTrackDBOpenTelemetry.setOpenTelemetry(sdk.getOpenTelemetrySdk(), ownedByYtdb=true, serverMode=true)`. The setter installs `OTelLogAppender` on `LogManager.instance()` only when the SDK's `LoggerProvider` is non-noop (logs enabled), and starts `OTelMetricsBridge` only when the SDK's `MeterProvider` is non-noop (metrics enabled). The `serverMode=true` flag causes the facade to select `SpanKind.CLIENT` for the query and commit listeners; the `ownedByYtdb=true` flag causes `shutdown()` to close the SDK on server stop. Embedded entry points (host setter, `GlobalOpenTelemetry.get()` fallback, YTDB auto-configure) default `serverMode=false`, so embedded query and commit spans use `SpanKind.INTERNAL`.
5. Transactions run, spans emit.
6. On shutdown, `server.shutdown()` calls `onBeforeDeactivate` on every plugin. `OpenTelemetryServerPlugin.onBeforeDeactivate()` calls `YouTrackDBOpenTelemetry.shutdown()`, which unregisters listeners, removes `OTelLogAppender` from `LogManager.instance()` when registered, stops `OTelMetricsBridge` when started, and closes the SDK (`OpenTelemetrySdk.close()` flushes pending spans, log records, and the final metrics export cycle).

Idempotence and ownership transitions:
- `setOpenTelemetry` called when YTDB has already auto-configured its own SDK: the facade closes the YTDB-owned SDK first, then installs the host's instance and flips ownership to host.
- `setOpenTelemetry` called twice with host-owned SDKs in a row: the facade does not close anything (host owns lifecycle for both); it just swaps the reference and re-registers listeners against the new tracer.
- `shutdown` called twice is a no-op the second time.
- An exception during SDK shutdown is logged but does not propagate.

### Configuration surface

All twenty knobs are `GlobalConfiguration` entries (env-var capable, created by Track 5); every default keeps YTDB silent on the OTel side, so the operator opts in. `plan/track-5.md` carries the canonical per-entry types; the table below is the operator-facing summary grouped by signal.

| Group | Entry | Default | Role |
|---|---|---|---|
| SDK | `OPENTELEMETRY_ENABLED` | `false` | master switch; nothing wires until `true` |
| SDK | `OPENTELEMETRY_EXPORTER_ENDPOINT` | (none) | OTLP collector URL, e.g. `http://localhost:4317` |
| SDK | `OPENTELEMETRY_EXPORTER_PROTOCOL` | `grpc` | `grpc` or `http/protobuf` |
| SDK | `OPENTELEMETRY_EXPORTER_HEADERS` | empty | comma-separated `key=value`; e.g. `Authorization=Bearer <token>` for hosted backends |
| SDK | `OPENTELEMETRY_EXPORTER_TIMEOUT_MILLIS` | `10000` | OTLP request timeout; bounds exporter-shutdown stall |
| SDK | `OPENTELEMETRY_SERVICE_NAME` | `youtrackdb` | `service.name` resource attribute |
| Query | `OPENTELEMETRY_QUERY_MODE_TAG_RULES` | empty | per-tag LIGHTWEIGHT/EXACT monitoring-mode rules (D15) |
| Query | `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_MILLIS` | `0` | `0` = emit-all; positive drops fast successful queries (D16) |
| Query | `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_TAG_RULES` | empty | per-tag slow-threshold overrides (D16) |
| Query | `OPENTELEMETRY_QUERY_HEARTBEAT_SAMPLE_MILLIS` | `0` | `0` = disabled; positive emits at most one heartbeat span per interval (D18; single global, no per-tag rules) |
| Query | `OPENTELEMETRY_QUERY_INCLUDE_PARAMETERS` | `false` | trace-side PII knob; `true` attaches parameter values as `db.operation.parameter.<index>` (per-tag deferred to YTDB-708) |
| Commit | `OPENTELEMETRY_COMMIT_SPAN_NAME_INCLUDES_DBNAME` | `true` | `false` drops dbName from commit-span names for high-cardinality multi-tenant hosts |
| Commit | `OPENTELEMETRY_COMMIT_SLOW_THRESHOLD_MILLIS` | `0` | `0` = emit every successful commit; positive gates on `executionTimeNanos`; failed commits always emit (D38) |
| Logs | `OPENTELEMETRY_LOGS_ENABLED` | `false` | logs-pillar master switch (D34) |
| Logs | `OPENTELEMETRY_LOGS_MIN_SEVERITY` | `INFO` | severity floor; below-threshold records drop before any OTel allocation |
| Logs | `OPENTELEMETRY_LOGS_INCLUDE_MESSAGE_BODY` | `false` | body-policy default-deny; ships the SLF4J format string, not substituted parameter values |
| Logs | `OPENTELEMETRY_LOGS_LOGGER_EXCLUSIONS` | `io.opentelemetry.,io.youtrackdb.otel.appender` | requester-prefix filter; cross-thread recursive-logging defense |
| Metrics | `OPENTELEMETRY_METRICS_ENABLED` | `false` | metrics-pillar master switch (D36) |
| Metrics | `OPENTELEMETRY_METRICS_PERIOD_MILLIS` | `10000` | collection cadence; clamped to at least `1000` at SDK init |
| Metrics | `OPENTELEMETRY_METRICS_INCLUDED_GROUPS` | empty | empty = all six groups; opt into `queries`/`cache`/`storage`/`wal`/`locks`/`transactions` for cardinality budget (D37) |

The tracking-ID attribute `db.youtrackdb.transaction.tracking_id` has no config flag: it is gated by construction via `QueryDetails.getExplicitTrackingId().isPresent()`, so the host's explicit `withTrackingId(...)` call is the opt-in (M49).

### Edge cases / Gotchas

- A host that sets `OPENTELEMETRY_ENABLED=true` without wiring any OTel sees YTDB open a network connection to the configured endpoint (default `http://localhost:4317`) on first listener fire. The flag is an explicit opt-in so this is acceptable; the auto-configure path logs one INFO line with the resolved endpoint so the operator sees what happened. Hosts that do not want this MUST keep `OPENTELEMETRY_ENABLED=false`.
- Embedded host that calls `YouTrackDB.close()` without calling `YouTrackDBOpenTelemetry.shutdown()`: when ownership is YTDB the SDK keeps running until the JVM exits and OTel's own shutdown hook flushes pending spans. When ownership is host, the SDK was never ours to close.
- Server with `OPENTELEMETRY_ENABLED=false` (default): the plugin's `onAfterActivate` returns immediately. Zero OTel runtime cost. The listeners never register.
- Race on auto-configure: the facade synchronizes the resolution chain so two concurrent first-listener-fires produce exactly one SDK build.
- An invalid endpoint in `OPENTELEMETRY_EXPORTER_ENDPOINT`: the autoconfigure builder fails fast with a clear log message. In server mode the server does not start; in embedded mode the listener fire fails once (the exception is caught by the isolation wrapper), and subsequent fires re-attempt resolution (which fails the same way until the operator fixes config).

### References
- D-records: D2
- Mechanics: none

## Listener registration and ordering

**TL;DR.** A process-global registry in `core` (a pair of `CopyOnWriteArrayList`s for query and transaction listeners, exposed via static methods on `YourTracks`) holds listeners installed before transactions begin. `FrontendTransactionImpl.beginInternal()` snapshots the registry into per-TX fields before `txStartCounter` increments, and the transaction uses that snapshot for its lifetime. `YTDBTransaction.doOpen()` delegates to `beginInternal()`, so Gremlin and native-SQL transactions share the same snapshot site. The per-TX `withQueryListener` / `withTransactionListener` API changes from single-slot to additive (semantic break documented below), so a per-TX listener layered onto the registry snapshot composes instead of overwriting it. The OTel module installs its listeners through this registry.

Ordering rules:
- Within the registry, listeners fire in insertion order.
- For a given transaction, registry-snapshot listeners fire before per-TX listeners added via `withQueryListener`.
- A listener registered after a transaction has begun is not seen by that transaction; it takes effect from the next transaction onward.
- This ordering applies to BOTH fire paths: Gremlin (`YTDBQueryMetricsStep.close()`) and SQL (`InstrumentedSqlResultSet.close()`). The wrapper constructor reads `currentTx.iterateAllQueryListeners()` (Track 1 accessor) once and stores the snapshot, so the listener iteration order is fixed at construction time; subsequent listener registration mid-query does not affect this result-set's emission. The yielded order is registry snapshot first, then per-TX list, so per-TX `withQueryListener` listeners fire for SQL statements as well as Gremlin traversals.

### Per-TX `withQueryListener` / `withTransactionListener` semantic break

Track 1 changes `YTDBTransaction`'s per-TX listener fields from single-slot (`QueryMetricsListener queryMetricsListener`, `TransactionMetricsListener transactionMetricsListener`) to lists (`List<QueryMetricsListener> queryMetricsListeners`, `List<TransactionMetricsListener> transactionMetricsListeners`). Existing call sites:

- `withQueryListener(listener)` previously assigned `this.queryMetricsListener = listener` (overwrite). It now calls `this.queryMetricsListeners.add(listener)` (additive). Chained calls like `tx.withQueryListener(l1).withQueryListener(l2)` previously yielded a single per-TX listener `l2`; they now yield both listeners firing in insertion order.
- Symmetric change for `withTransactionListener(listener)`.
- `clearMonitoringState()` (called from `fireOnCommit` / `fireOnRollback`) clears the list instead of resetting a single slot.
- `isQueryMetricsEnabled()` / `isTransactionMetricsEnabled()` return true when either the per-TX list is non-empty OR the snapshot captured at `beginInternal()` is non-empty. This is the gating-widening callers depend on (see §"YTDBQueryMetricsStrategy gating widening" below).
- `getQueryMetricsListener()` is replaced by `getQueryMetricsListeners(): Iterable<QueryMetricsListener>` returning the per-TX list. The merged view (snapshot + per-TX) is exposed via `FrontendTransaction.iterateAllQueryListeners()`.

This is a deliberate backward-incompatible behavior change in an internal SPI. Hosts that wrote `tx.withQueryListener(custom1).withQueryListener(custom2)` expecting overwrite semantics now see both listeners fire. The change is intentional because the global registry use-case (one or more OTel listeners) composes with per-TX listeners only when both can coexist. The single-slot model was the source of the inert-flag bug the global registry exists to fix; reverting to overwrite semantics on the per-TX path would re-introduce the bug for hosts that combine the two.

### Step-injection broadening for global-only listeners

`YTDBQueryMetricsStep` is added to a Gremlin traversal by `YTDBQueryMetricsStrategy.apply()` only when its check `if (!ytdbTx.isQueryMetricsEnabled()) return;` passes (`core/.../profiler/monitoring/YTDBQueryMetricsStrategy.java:36`). Today `isQueryMetricsEnabled()` reads the single per-TX listener slot, so a host that registers only via the global registry would see the strategy return without injecting the step — no step, no `queryFinished(...)` fire, no Gremlin spans. Track 1 widens `isQueryMetricsEnabled()` to also return true when the global query-listener snapshot captured at `beginInternal()` is non-empty, so registry-installed listeners cause step injection independent of per-TX wiring. The SQL path does not need a corresponding gate widening because each entry point unconditionally calls `InstrumentedSqlResultSet.wrapIfListening(...)`, which consults `currentTx.iterateAllQueryListeners()` (the merged view) and returns the inner result-set unchanged when the snapshot is empty; zero listeners short-circuit at the wrapper boundary.

### `monitoredCommitInternal` signature change

The existing `FrontendTransaction.monitoredCommitInternal(@Nonnull TransactionMetricsListener, @Nonnull QueryMonitoringMode, @Nonnull String trackingId)` (`core/.../tx/FrontendTransaction.java:73`) takes a single listener parameter and `FrontendTransactionImpl.notifyMetricsListener` calls that one listener (`FrontendTransactionImpl.java:712`). Track 1 changes the contract: `monitoredCommitInternal` is replaced by `commitInternal()` (the existing parameterless form already in the interface at line 55) with `notifyMetricsListener` reading the transaction-listener snapshot field captured at `beginInternal()` plus the per-TX `transactionMetricsListeners` list, iterating both inside the existing try/catch wrapper (widened to `Exception | LinkageError | AssertionError` per D11). The mode and trackingId previously passed as `monitoredCommitInternal` parameters become reads of `currentTx.getDefaultQueryMonitoringMode()` and `currentTx.getTrackingId()` at the fire site. `YTDBTransaction.doCommit()` (line 168) is simplified accordingly — the branch around `isTransactionMetricsEnabled()` is removed because `commitInternal()` always handles listener iteration internally (zero listeners short-circuit with no allocation). `monitoredCommitInternal` is removed from the interface.

Registration API additions — static methods on the existing `final` utility class `YourTracks` (the registry is process-global; the `YouTrackDB` interface gets no new methods, keeping `YouTrackDBRemote` and other implementors untouched):

```java
public static void registerGlobalQueryListener(QueryMetricsListener listener);
public static void unregisterGlobalQueryListener(QueryMetricsListener listener);
public static void registerGlobalTransactionListener(TransactionMetricsListener listener);
public static void unregisterGlobalTransactionListener(TransactionMetricsListener listener);
```

`YouTrackDBOpenTelemetry.setOpenTelemetry(otel)` registers a `OTelQueryMetricsListener` and an `OTelTransactionMetricsListener` instance. Subsequent `setOpenTelemetry` calls first `unregister` the previously-registered instances before registering fresh ones tied to the new SDK. `YouTrackDBOpenTelemetry.shutdown()` unregisters them.

### Edge cases / Gotchas

- Concurrent `register` and transaction `doOpen`: the registry uses `CopyOnWriteArrayList`, so a `doOpen` reads a consistent snapshot even if a `register` is in flight. The behavior is "either the listener is in the snapshot or it isn't", deterministic per transaction.
- A listener implementation that holds a reference to the YTDB transaction: the listener outlives the transaction (it lives in the global registry). The listener MUST NOT cache transaction-scoped state; if it needs per-TX state, use `TransactionDetails.getTransactionTrackingId()` as the keying value.
- Duplicate registration of the same listener instance: the registry deduplicates by reference identity. A second `register` of the same instance is a no-op.

### References
- D-records: D1
- Mechanics: none

## Exception isolation contract

**TL;DR.** Every listener callback fires inside a try/catch in the YTDB firing site. A throw from any listener (a bug, a misconfigured OTel SDK, an OOM in span allocation) is logged at WARN and swallowed, so the transaction lifecycle continues.

Isolation sites:
- `YTDBQueryMetricsStep.close()` wraps `listener.queryFinished(...)` in `try { ... } catch (Exception e) { ... }` today (line 148). Track 1 widens the catch to `catch (Exception | LinkageError | AssertionError t)` and changes the call to iterate the per-TX listener snapshot.
- `FrontendTransactionImpl.notifyMetricsListener()` (line 712) wraps the commit success and failure paths in `try { ... } catch (Exception e) { ... }` today (line 730). Track 1 widens to the same multi-catch union and iterates the snapshot.
- `InstrumentedSqlResultSet.close()` wraps the `listener.queryFinished(...)` call for native SQL fires in the same multi-catch union, iterating over the listener snapshot captured at constructor time.

The wrapper catches the union `Exception | LinkageError | AssertionError` (Track 1 widens both existing wrappers from `Exception` to this narrower-than-Throwable set), because the OTel-typical failure modes would otherwise unwind the transaction: a misconfigured SDK throwing `IllegalStateException`, missing exporter classes throwing `NoClassDefFoundError` (a `LinkageError`), or assertion failures in custom listener implementations. The union deliberately excludes `VirtualMachineError`, so a true `OutOfMemoryError` or `StackOverflowError` still propagates per JLS guidance. The JVM is dying anyway in that case, and silencing the fatal condition would mask the problem. `ThreadDeath` is also outside the union. The log entry uses the listener class name to point the operator at the responsible component.

When one listener in the snapshot throws, subsequent listeners in the iteration still fire. This is important because the OTel listener may be installed alongside a custom host listener; a bug in the host listener must not prevent OTel emission, and vice versa.

### Edge cases / Gotchas

- A listener that throws on every call generates one log line per call. At LDBC benchmark loads (~10k qps) this is a log flood. Acceptable in YTDB-496; if it becomes operationally painful, a follow-up adds rate limiting on the log.
- An exception in `OpenTelemetrySdk.close()` during server shutdown is logged but not propagated; the server shutdown completes regardless.

### References
- D-records: D11 (wrapper widened from `Exception` to the union `Exception | LinkageError | AssertionError`)
- Invariants: Listener exception isolation
- Mechanics: none
