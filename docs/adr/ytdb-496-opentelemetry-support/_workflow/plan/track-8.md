<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# Track 8: OpenTelemetry metrics pillar — `OTelMetricsBridge` over `Profiler.getMetricsRegistry()`

## Purpose / Big Picture

After this track lands, YTDB's internal `Profiler.getMetricsRegistry()` is surfaced through OTel async instruments at a configurable period without duplicating the counter model. A curated subset of profiler metrics maps to sem-conv v1.33.0 DB metric names where the spec defines them as stable (`db.client.connection.count`, `db.client.operation.duration`, `db.client.response.returned_rows`); the rest map to the `youtrackdb.*` namespace as vendor-specific gauges (cache hit ratio, page reads, WAL pending bytes, lock contention count, storage size, transaction commit rate). `Meter` flushes each `TimeRate` / `Ratio` rate window internally every `flushRateTicks`, so the bridge reads current rates via `TimeRate.getRate()` / `Ratio.getRatio()`; a `ScheduledExecutorService` task at `OPENTELEMETRY_METRICS_PERIOD_MILLIS` re-reads the windows independently of OTel `PeriodicMetricReader` cadence so reads stay fresh between exporter polls (it only reads, never mutates metric state). A six-group taxonomy (`queries`/`cache`/`storage`/`wal`/`locks`/`transactions`) lets operators opt into subsets via `OPENTELEMETRY_METRICS_INCLUDED_GROUPS` for cardinality budget control.

The OTel metrics pillar is gated by three new `GlobalConfiguration` entries — `OPENTELEMETRY_METRICS_ENABLED` (default `false`, master switch), `OPENTELEMETRY_METRICS_PERIOD_MILLIS` (default `10000`, clamped to `>= 1000` at SDK init), `OPENTELEMETRY_METRICS_INCLUDED_GROUPS` (default empty = all groups enabled when metrics ON). When the master switch is `false`, the bridge is never started and no `ObservableInstrument` callbacks register; the existing `Profiler.getMetricsRegistry()` continues to serve in-JVM consumers (JMX, custom listeners) unchanged. When `true` and `OPENTELEMETRY_ENABLED=true`, the SDK builds an `SdkMeterProvider` alongside `SdkTracerProvider` + `SdkLoggerProvider` from the same exporter endpoint, and `YouTrackDBOpenTelemetry` constructs an `OTelMetricsBridge` from `provider.get("io.youtrackdb")` calling its `start()` method.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Covers D36 (`OTelMetricsBridge` surfaces `Profiler.getMetricsRegistry()` via async instruments; scheduled task advances YTDB-side time-windows independently of OTel `PeriodicMetricReader`) and D37 (group-based opt-in via `OPENTELEMETRY_METRICS_INCLUDED_GROUPS`, six-group taxonomy).

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

YTDB's profiler infrastructure lives at `com.jetbrains.youtrackdb.internal.common.profiler.Profiler` (implements `YouTrackDBStartupListener` and `YouTrackDBShutdownListener`). `Profiler.getMetricsRegistry()` returns the process-global `MetricsRegistry` of `Metric<T>` instances; concrete metric shapes are `Gauge<T>`, `Stopwatch`, `TimeRate`, and `Ratio` (all in `core/.../profiler/metrics/`). Each metric carries a `MetricScope` (`Global`, `Database`, or `Class` — a sealed interface). The profiler runs independently of OTel and is updated by storage / cache / WAL / transaction subsystems on their own threads via the existing call sites that already exist for in-JVM consumers (JMX, dump-environment text output).

The bridge subscribes to that registry through OTel async instruments rather than duplicating the metric model. `ObservableLongCounter`, `ObservableLongGauge`, and `ObservableDoubleHistogram` callbacks capture a closure over the source `Metric` reference; each OTel collection cycle re-reads through the registry. Track 8 adds: (a) a `MetricGroup` enum classifying each metric into one of six structural subsystems, plus a `MetricDefinition.group(): MetricGroup` accessor with a default sentinel fallback for metrics added without an explicit annotation; (b) two new public-API surfaces on `MetricsRegistry` — `forEachMetric(MetricVisitor)` for enumeration at bridge `start()` time and `addRegistrationListener(Consumer<MetricRegistrationEvent>)` so per-database metrics created lazily after `start()` (when a new database opens) register their OTel callback on the registration event; (c) five new `MetricDefinition` entries in `CoreMetrics` (`PAGE_READ_COUNT`, `WAL_PENDING_BYTES`, `WAL_FLUSH_RATE`, `LOCK_WAIT_COUNT`, `DATABASE_SIZE_BYTES`) that the curated inventory references but the existing CoreMetrics does not carry — with the writer-site additions on the disk-cache page-fault path, the WAL writer's `flushPending()`, the storage-layer `ReadWriteLock` wrappers, and the storage layer's `getSizeOnDisk()` per database.

Concrete deliverables:

1. New `MetricGroup` enum in `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/metrics/MetricGroup.java` with values `QUERIES`, `CACHE`, `STORAGE`, `WAL`, `LOCKS`, `TRANSACTIONS`, and a sentinel value (default `OTHER`) for metrics without an explicit annotation. The sentinel is treated as `included` so omission never silently drops counters; documented in the enum's Javadoc and named in `MetricDefinition.group()`'s Javadoc.
2. New `MetricDefinition.group(): MetricGroup` accessor on the existing parser-output base class returning the metric's classification. Default implementation returns `MetricGroup.OTHER` so existing call sites that do not explicitly classify a metric still resolve cleanly. Existing `MetricsRegistry` entries (in `core`, `embedded`, `server`) gain explicit `group()` annotations matching the inventory tables in design-mechanics.md §"Metrics integration" → "Counter inventory and sem-conv mapping".
3. New `OTelMetricsBridge` class in `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/metrics/`. Private fields: `Meter meter`, `ScheduledExecutorService scheduler`, `Set<MetricGroup> includedGroups`, `Map<String, ObservableInstrument> registered`, `Consumer<MetricRegistrationEvent> registrationListener`. Constructor takes `Meter meter`, `Set<MetricGroup> includedGroups`, and `long periodMillis`. Public methods: `start()` calls `registry.forEachMetric(...)` to enumerate currently-registered metrics + subscribes via `registry.addRegistrationListener(...)` for lazy per-DB metric registration + schedules the time-window-advance task; `stop()` removes the registration listener, cancels the task, and unregisters instruments (idempotent); `refresh()` is a test seam forcing synchronous read through each callback.

3a. New `MetricVisitor` interface, `forEachMetric(MetricVisitor)` method, `MetricRegistrationEvent` record, and `addRegistrationListener(Consumer<MetricRegistrationEvent>)` / `removeRegistrationListener(Consumer<MetricRegistrationEvent>)` accessors on `MetricsRegistry`. The listener list is a `CopyOnWriteArrayList<Consumer<MetricRegistrationEvent>>` fired synchronously inside `MetricsGroup.init(...)` (the existing `computeIfAbsent` site) when a new metric is created. The fire is on the registering thread; listeners that do non-trivial work (like the OTel bridge's `ObservableInstrument` registration) post the work onto their own executor so the registration thread is not blocked. `forEachMetric` walks `globalMetrics.mGroup.metrics` then iterates `perDatabaseMetrics.values()`. Listed in Track 8 because the OTel bridge is the only known consumer; if a second consumer appears later the accessors stay package-public (no API change).

3b. Nine new `MetricDefinition` entries in `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/metrics/CoreMetrics.java`. Five baseline operator-diagnostic entries: `PAGE_READ_COUNT` (`Counter`, monotonic, populated by the disk-cache page-fault path), `WAL_PENDING_BYTES` (`Gauge<Long>`, read at the WAL writer's `flushPending()` site), `WAL_FLUSH_RATE` (`TimeRate`, bytes per second, 60s window), `LOCK_WAIT_COUNT` (`Counter`, monotonic, populated by the `ReadWriteLock` wrappers in the storage layer), and `DATABASE_SIZE_BYTES` (`MetricScope.Database` `Gauge<Long>`, read from the storage layer's `getSizeOnDisk()`). Four additional advisor-foundation entries enabling future Phase 2 advisory dashboards (D42): `BUFFER_POOL_EVICTION_RATE` (`TimeRate`, evictions per second, 60s window; reads from existing disk-cache eviction site so cache hit ratio's "is buffer undersized?" signal becomes a comparable rate metric on its own dashboard panel), `INDEX_LOOKUP_VS_FULL_SCAN_RATIO` (`MetricScope.Database` `Ratio`, populated by a new `metric.recordHit(boolean)` call at every `IndexLookupStep.execute()` / fallback-to-full-scan branch in the SQL execution layer; ratio shape matches existing `DiskCacheHitRatio` precedent), `TX_ROLLBACK_RATE` (`MetricScope.Database` `TimeRate`, rollbacks per second, 60s window; populated by the existing `TransactionWriteRollbackRate` counter — surfaced now to OTel where it was previously profiler-internal only), `CONNECTION_POOL_SATURATION` (`Gauge<Double>` returning current/max ratio in [0.0, 1.0], read from `DatabaseSessionRegistry.getActiveCount()` divided by `NETWORK_SOCKET_MAX_CONNECTIONS` config value at sample time). Each entry lands alongside its writer-site instrumentation (the writers are minimal additions — a single `metric.increment()` / `metric.set(value)` / `metric.recordHit(boolean)` call at the existing instrumentation surface for that subsystem). Track 8's JUnit tests assert each new metric's writer site fires under the expected workload (`PageReadCountWriterTest`, `WalPendingBytesWriterTest`, `BufferPoolEvictionRateWriterTest`, `IndexLookupRatioWriterTest`, `TxRollbackRateWriterTest`, `ConnectionPoolSaturationWriterTest`, etc. — nine small tests).
4. Curated counter inventory wiring (in `OTelMetricsBridge.start()`): three sem-conv entries (`db.client.connection.count` as `ObservableLongUpDownCounter` reading active sessions from `DatabaseSessionRegistry`; `db.client.operation.duration` as `ObservableDoubleHistogram` aggregating query + commit `executionTimeNanos` across the period; `db.client.response.returned_rows` as `ObservableDoubleHistogram` reading from `QueryDetails.getResultCount()`, consumed via the Track 1 accessor) plus 16 `youtrackdb.*` entries per design-mechanics.md §"Metrics integration" → "Counter inventory and sem-conv mapping": `cache.hit_ratio`, `disk.read_rate_bps`, `disk.write_rate_bps`, `cache.page_reads_total`, `cache.evictions_total`, `wal.pending_bytes`, `wal.flush_rate_bps`, `lock.contention_count`, `storage.size_bytes`, `transaction.commit_rate`, `transaction.rate`, `transaction.rollback_rate`, `transaction.active_count`, `transaction.oldest_age_seconds`, plus four advisor-foundation entries (D42): `cache.eviction_rate` (mapped from `BUFFER_POOL_EVICTION_RATE`), `query.index_lookup_ratio` (mapped from `INDEX_LOOKUP_VS_FULL_SCAN_RATIO`), `connection.pool_saturation` (mapped from `CONNECTION_POOL_SATURATION`; the `transaction.rollback_rate` slot doubles as the advisor signal mapped from `TX_ROLLBACK_RATE` so no separate sem-conv key is needed). Each reads through a specific `Metric<T>` from the registry. Total = 19 instruments (3 sem-conv + 16 `youtrackdb.*`).
5. Group filter wiring: `OTelMetricsBridge.start()` reads `OPENTELEMETRY_METRICS_INCLUDED_GROUPS` from `GlobalConfiguration`, parses comma-separated values (trim, uppercase, ignore unrecognized with one WARN line per unrecognized name), skips `register()` for metrics whose `MetricDefinition.group()` is excluded. Empty config value enables every group.
6. `YouTrackDBOpenTelemetry.setOpenTelemetry(...)` extension: when the resolved SDK carries a non-noop `MeterProvider`, construct `OTelMetricsBridge` from `MeterProvider.get("io.youtrackdb")` with the resolved included-groups set and the resolved period (clamped to `>= 1000` ms), call `start()`. Track ownership so `shutdown()` calls `stop()` only when the bridge is YTDB-owned. SDK swap during active metrics: old bridge `stop()` runs before new bridge `start()`, so each `ObservableInstrument` is registered against exactly one `SdkMeterProvider` at a time.

## Plan of Work

Six edits.

The first edit adds `MetricGroup` enum + `MetricDefinition.group()` accessor with sentinel default. JUnit test (`MetricGroupTest` in the existing `core` test tree) asserts the enum's six concrete values plus the sentinel, the default accessor returning the sentinel, and a custom `Metric` subclass overriding `group()` returning a non-sentinel value.

The second edit annotates existing `MetricsRegistry` entries across `core`, `embedded`, and `server` with their `MetricGroup`. Each existing metric construction call site (`new Gauge<>(...)`, `new TimeRate(...)`, etc.) gains an explicit `group()` override matching the inventory tables in design-mechanics.md. Metrics not in the curated table keep the sentinel default. Coverage check: any `MetricsRegistry` entry classified as anything other than `OTHER` appears in the design-mechanics.md inventory table; any inventory-table entry has a matching annotated `MetricsRegistry` entry. Track 8's JUnit tests assert this matchup.

The third edit adds `OTelMetricsBridge` class with `start()` / `stop()` / `refresh()` methods, the `ObservableInstrument` map, and the scheduled-task wiring. The scheduled task is a no-op semantically (the SDK `PeriodicMetricReader` drives the actual export), but it re-reads each `TimeRate` / `Ratio` in the registry via `getRate()` / `getRatio()` so the value the exporter sees stays fresh between polls (the window flush itself is internal to `Meter`). `stop()` cancels the task via `ScheduledFuture.cancel(false)` and iterates the instrument map calling `unregister()` on each entry.

The fourth edit wires the curated counter inventory (three sem-conv entries + eight `youtrackdb.*` entries) inside `OTelMetricsBridge.start()`. Each registration reads a specific `Metric<T>` reference from `Profiler.getMetricsRegistry()` and builds the corresponding `Observable*` callback. If a metric is missing from the registry at `start()` time (e.g., not yet constructed because the profiler has not finished `onStartup()`), the bridge logs WARN and registers zero callbacks for that metric — recovery is to call `start()` again after the profiler initializes (typically via the SDK swap path).

The fifth edit adds `YouTrackDBOpenTelemetry` extension building the bridge from `MeterProvider`, starting it when `OPENTELEMETRY_METRICS_ENABLED=true` and the SDK's `MeterProvider` is non-noop. Plus `shutdown()` calling `bridge.stop()` when the bridge is YTDB-owned. SDK swap during active metrics: previous-SDK bridge `stop()` is called before new-SDK bridge constructor + `start()`.

The sixth edit adds JUnit tests in the OTel module covering curated counter registration (all 19 entries — 3 sem-conv + 16 `youtrackdb.*` including the four D42 advisor-foundation entries — are registered when the master switch is on and the included-groups set is empty; assert each `ObservableInstrument` is non-null in `bridge.refresh()`); scheduled-task re-read behavior for `TimeRate.getRate()` (assert reads stay fresh between exporter polls via a mock OTel `MetricReader` with a longer poll interval than `OPENTELEMETRY_METRICS_PERIOD_MILLIS`); group filter via empty-set-includes-everything, single-group filter, multi-group filter, unrecognized-name WARN behavior, sentinel-group default (a metric annotated `OTHER` is included regardless of the configured set); SDK-swap window exactly-one-`MeterProvider` invariant; profiler-not-initialized race (bridge `start()` before `Profiler.onStartup()` registers zero callbacks and logs WARN; recovery via JVM system property).

Ordering: edits 1 + 2 are independent of the OTel module. Edits 3 + 4 build on edits 1 + 2. Edits 5 + 6 build on edits 3 + 4 and can be drafted in parallel.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 8:

- `./mvnw -pl core clean test` passes with `MetricGroupTest` green and the existing profiler tests still green (annotation-only changes do not alter behavior).
- `./mvnw -pl youtrackdb-opentelemetry clean test` passes with all `OTelMetricsBridge*` test classes green.
- The OTel module's coverage gate (≥85% line / ≥70% branch) holds on `OTelMetricsBridge`.
- A manual smoke test against an OTLP collector (out-of-CI verification): start an embedded YTDB session with `OPENTELEMETRY_ENABLED=true OPENTELEMETRY_METRICS_ENABLED=true OPENTELEMETRY_METRICS_PERIOD_MILLIS=2000`; run a workload that touches cache, WAL, and queries; verify the collector receives non-zero data points for each of the eleven curated metrics at the configured 2-second cadence.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

## Interfaces and Dependencies

In scope:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/metrics/MetricGroup.java` (new enum: `QUERIES`, `CACHE`, `STORAGE`, `WAL`, `LOCKS`, `TRANSACTIONS`, `OTHER` sentinel).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/metrics/MetricDefinition.java` (extended with new `group(): MetricGroup` default accessor returning `OTHER`).
- Existing `MetricsRegistry` construction sites in `core`, `embedded`, `server` (annotated with their explicit `MetricGroup`; sites not in the curated inventory keep the sentinel default).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/metrics/MetricsRegistry.java` (new public-API methods: `MetricVisitor` nested interface, `forEachMetric(MetricVisitor)`, `MetricRegistrationEvent` record, `addRegistrationListener(...)` / `removeRegistrationListener(...)`; `CopyOnWriteArrayList<Consumer<MetricRegistrationEvent>>` field; listener fire inside `MetricsGroup.init(...)`).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/metrics/CoreMetrics.java` (nine new `MetricDefinition` entries: five operator-diagnostic — `PAGE_READ_COUNT`, `WAL_PENDING_BYTES`, `WAL_FLUSH_RATE`, `LOCK_WAIT_COUNT`, `DATABASE_SIZE_BYTES` — plus four advisor-foundation per D42 — `BUFFER_POOL_EVICTION_RATE`, `INDEX_LOOKUP_VS_FULL_SCAN_RATIO`, `TX_ROLLBACK_RATE`, `CONNECTION_POOL_SATURATION` — with their writer-site call additions in the disk-cache / WAL / lock-wrapper / storage / SQL-execution-layer / session-registry layers).
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/metrics/OTelMetricsBridge.java` (new class with `start()` / `stop()` / `refresh()` methods, the `ObservableInstrument` map, and the scheduled-task wiring).
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/YouTrackDBOpenTelemetry.java` (extended to build and start the bridge from a non-noop `MeterProvider`; `shutdown()` stops the bridge).
- `core/src/test/java/com/jetbrains/youtrackdb/internal/common/profiler/metrics/MetricGroupTest.java` (new JUnit test for the enum + accessor + sentinel default).
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/metrics/OTelMetricsBridgeTest.java` (new JUnit test covering registration, scheduled-task advance, group filter, SDK-swap window, profiler-not-initialized race).

Out of scope:
- Per-class or per-RID attributes on metrics (`MetricScope.Class` profiler entries collapse to one OTel data point per `(metric, database)` pair; per-class breakdown is a follow-up via a dedicated raw-profiler-dump exporter).
- A seventh `MetricGroup` value (taxonomy is intentionally fixed at six plus the sentinel; adding a seventh requires a coordinated rollout — config schema, operator docs, dashboard templates).
- SLF4J / Prometheus / StatsD bridge alternatives (the OTel `PeriodicMetricReader` + exporter combination covers every backend operators care about for YTDB-496; non-OTel bridges are follow-up tickets).
- Track 5's `OPENTELEMETRY_METRICS_ENABLED`, `OPENTELEMETRY_METRICS_PERIOD_MILLIS`, `OPENTELEMETRY_METRICS_INCLUDED_GROUPS` `GlobalConfiguration` entries (Track 5 owns them; Track 8 consumes them).
- Track 5's `SdkMeterProvider` autoconfigure build path (Track 5 owns it; Track 8 reads the resolved `MeterProvider` reference from the facade).

Inter-track dependencies:
- Depends on Track 3 (the OTel facade `YouTrackDBOpenTelemetry` exists with `setOpenTelemetry(...)` / `shutdown()` extension points).
- Depends on Track 5 (`OPENTELEMETRY_METRICS_*` config entries exist; the SDK autoconfigure builds `SdkMeterProvider` alongside `SdkTracerProvider` + `SdkLoggerProvider` when the master switch is on).
- May extend Track 1 if `QueryDetails.getResultCount()` is not already an accessor by Track 1 completion (Track 1 adds ten `QueryDetails` accessors per its current scope bullet; if `getResultCount()` is missing, Track 8 adds it as part of edit 4).
- Provides for nothing downstream that is not already owned by other tracks. Track 6 test infrastructure does not need to extend to cover Track 8; tests in the new `OTelMetricsBridgeTest` class cover the metrics pillar end-to-end.
