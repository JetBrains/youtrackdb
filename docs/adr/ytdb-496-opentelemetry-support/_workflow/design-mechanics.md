<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# YTDB-496 OpenTelemetry support — Mechanics

Implementation detail for the four sections listed below. Each section gathers the full pseudo-implementation, mapping tables, and edge cases an implementer needs. The text stands on its own; concepts named here either define themselves locally or appear under the same name in the companion polished view.

## Slow-query threshold gating

The resolver and its rule type reuse the same machinery as `QueryMonitoringModeResolver` — only the `T` parameter on `TagRule<T>` differs (`Long` instead of `QueryMonitoringMode`):

```java
public final class SlowQueryThresholdResolver {
    private final List<TagRule<Long>> rules;           // immutable, compiled once at startup
    private final ConcurrentHashMap<String, Long> cache;

    public long resolve(Optional<String> tag, long defaultNanos) {
        if (tag.isEmpty()) return defaultNanos;
        return cache.computeIfAbsent(tag.get(), t -> resolveUncached(t, defaultNanos));
    }
    // resolveUncached walks rules in order, first-wins; values stored as nanoseconds.

    public static SlowQueryThresholdResolver global() { ... }
}
```

The gate sits inside `OTelQueryMetricsListener.queryFinished`, not in the YTDB fire site. Three reasons: (1) other listeners registered alongside the OTel one (custom host implementations) may have their own gating policy and must continue to see every event; (2) the gate is OTel-specific configuration and lives in the OTel module rather than `core`; (3) `QueryDetails` already carries the inputs the gate needs — `executionTimeNanos` arrives as a method parameter, error state arrives through `QueryDetails.getErrorType()`, and the query tag arrives through `QueryDetails.getQuerySummary()` (same source the OTel sem-conv mapping already reads).

Pseudo-implementation at the top of `queryFinished`:

```java
@Override
public void queryFinished(QueryDetails details, long startedAtMillis, long executionTimeNanos) {
  boolean isError = details.getErrorType().isPresent();
  if (!isError) {
    long thresholdNanos = SlowQueryThresholdResolver.global()
        .resolve(Optional.ofNullable(details.getQuerySummary()), defaultThresholdNanos);
    if (thresholdNanos > 0 && executionTimeNanos < thresholdNanos) {
      return;  // fast successful query — skip span allocation
    }
  }
  // existing span construction (sem-conv attributes, parent context, span.end)
}
```

`QueryDetails.getErrorType(): Optional<String>` is a new default-empty accessor added in Track 1. Both fire sites populate it from the caught exception when `statement.execute(...)` (SQL) or the traversal close (Gremlin) throws — the same exception that drives the `error.type` attribute on the emitted span. The accessor exists on the listener contract so any consumer (OTel or custom) can read error state without an additional callback signature.

The standalone commit span is not threshold-gated in this design. Commit duration is operationally interesting at every length (fast commits show throughput, slow commits show lock or fsync contention), and the per-TX volume of commit spans is bounded by transaction count rather than query count. If commit-span volume becomes a problem in practice, a follow-up adds `OPENTELEMETRY_COMMIT_SLOW_THRESHOLD_MILLIS` against the same shape — single global value plus optional per-tag rules, error bypass, gate inside the listener.

### Edge cases / Gotchas

- Default `100` reflects a typical operator threshold for a "slow query worth investigating"; queries faster than that are noise in most trace viewers. Hosts that want to emit every query set `OPENTELEMETRY_QUERY_SLOW_THRESHOLD_MILLIS=0`; tests that need every span to surface flip the value explicitly.
- A query whose duration equals the threshold emits, because the comparison is strictly `<` (less-than). A 100 ms query against a 100 ms threshold passes the gate.
- The gate evaluates before any work the listener would otherwise do — no `tracer.spanBuilder(...)`, no attribute reads from `QueryDetails` beyond `getErrorType()` and `getQuerySummary()` (both cheap, lazy in the impl). Gated-out queries pay only the resolver lookup (`ConcurrentHashMap.computeIfAbsent` once per distinct tag, O(1) on cache hit) and the listener return.
- Tag-rule cache cardinality blow-up: a misuse-pattern host that emits unique tags per request (e.g., a UUID) grows the `ConcurrentHashMap` without bound. Same caveat as for `QueryMonitoringModeResolver`; documented as host responsibility, no LRU bound in YTDB-496 because typical workloads have dozens of tags.
- A tag that matches no rule resolves to the global default. The cache still records the resolution so future identical tags skip the rule walk.
- Conflicting rules (two rules match the same tag): first-wins by insertion order. Operators order rules from most-specific to most-general. Duplicate exact rules: the first one wins; subsequent rules log WARN at startup and are dropped.
- The global default is captured at OTel listener construction time and stored in the `defaultThresholdNanos` final field. Mid-process changes to the global default require an SDK rebuild (host calls `YouTrackDBOpenTelemetry.setOpenTelemetry(...)` again, or the server restarts). The tag-rule list is also compiled once at startup; mid-process rule changes are not supported in YTDB-496 (same constraint as for mode rules).
- Custom (non-OTel) `QueryMetricsListener` implementations are unaffected. The gate lives only in `OTelQueryMetricsListener`; the listener iteration in `iterateAllQueryListeners()` still fires every registered listener for every query. Custom listeners that want their own threshold semantics can call `SlowQueryThresholdResolver.global()` themselves, or implement their own gating logic entirely.

### References
- D-records: D16 (slow-query threshold inside OTel listener, error bypass, per-tag rules via `TagRule<Long>`)

## Time-based sampling

The resolver reuses the same machinery as `SlowQueryThresholdResolver` — only the semantic of the `Long` value differs (heartbeat interval vs duration threshold):

```java
public final class SampleHeartbeatResolver {
    private final List<TagRule<Long>> rules;
    private final ConcurrentHashMap<String, Long> cache;

    public long resolve(Optional<String> tag, long defaultNanos) {
        if (tag.isEmpty()) return defaultNanos;
        return cache.computeIfAbsent(tag.get(), t -> resolveUncached(t, defaultNanos));
    }
    // resolveUncached walks rules in order, first-wins; values stored as nanoseconds.

    public static SampleHeartbeatResolver global() { ... }
}
```

The heartbeat gate sits at the top of `OTelQueryMetricsListener.queryFinished`, BEFORE the slow-query gate. Composition rule: the heartbeat gate runs first; if it claims the slot, the query emits and the slow-query gate is bypassed (saves one resolver lookup); if it does not claim, the slow-query gate evaluates as before. Error queries bypass both gates and always emit; the heartbeat clock is not advanced by error emissions, so a stream of errors does not suppress the heartbeat sample of successful queries.

Pseudo-implementation:

```java
private final AtomicLong lastHeartbeatNanos = new AtomicLong(0);

@Override
public void queryFinished(QueryDetails details, long startedAtMillis, long executionTimeNanos) {
  boolean isError = details.getErrorType().isPresent();
  if (!isError) {
    Optional<String> tag = Optional.ofNullable(details.getQuerySummary());
    long heartbeatNanos = SampleHeartbeatResolver.global().resolve(tag, defaultHeartbeatNanos);
    if (heartbeatNanos > 0 && tryClaimHeartbeat(heartbeatNanos)) {
      // heartbeat slot claimed — fall through to span emission
    } else {
      long thresholdNanos = SlowQueryThresholdResolver.global().resolve(tag, defaultThresholdNanos);
      if (thresholdNanos > 0 && executionTimeNanos < thresholdNanos) {
        return;  // both gates dropped this query
      }
    }
  }
  // existing span construction (sem-conv attributes, parent context, span.end)
}

private boolean tryClaimHeartbeat(long heartbeatNanos) {
  long now = System.nanoTime();
  long last = lastHeartbeatNanos.get();
  if (now - last < heartbeatNanos) return false;
  return lastHeartbeatNanos.compareAndSet(last, now);
}
```

The `AtomicLong.compareAndSet` resolves the multi-thread race: under load several queries finish within the same heartbeat window and all see `now - last >= heartbeatNanos`, but only the first CAS succeeds; losers fall through to the slow-query gate. The result is exactly one query per heartbeat window claims the heartbeat slot; the rest either pass the slow-query gate, get dropped, or emit on error.

### Edge cases / Gotchas

- The heartbeat clock is process-global per `OTelQueryMetricsListener` instance (one `AtomicLong`), not per-thread. This matches "one sample every N ms of wall-clock" semantics regardless of QPS or thread count. Per-thread heartbeat would emit one sample per thread per N ms, which scales noise with parallelism.
- Per-tag heartbeat intervals share the same clock. A query tagged `findHotpath` and another tagged `batchJob` arriving in the same window both compete for the same `lastHeartbeatNanos` slot; whichever wins the CAS resets the clock for everyone. This is intentional: the heartbeat is a workload-level sample, not a per-tag stream. Operators wanting per-tag streams configure dedicated OTel exporters on the SDK side.
- A query that's slow AND wins the heartbeat CAS emits only once, not twice. The composition order (heartbeat first, slow-query second) skips the slow-query gate after a heartbeat claim.
- Error queries always emit, regardless of heartbeat or slow-query state. The heartbeat clock is NOT advanced by error emissions; an error every N ms does not suppress the heartbeat sample of successful queries.
- Heartbeat-emitted spans look identical to slow-query-emitted spans in the trace viewer; no `sample.reason` attribute distinguishes them in YTDB-496. Operators wanting to tell "this was a heartbeat sample" apart from "this was actually slow" compare span duration against the configured slow-query threshold downstream. Adding an explicit attribute is a future-ticket concern (low cost; would land alongside the structured-attributes extension).
- Mid-process changes to the heartbeat interval require an SDK rebuild — same constraint as the slow-query threshold default. The tag-rule list is also compiled once at startup.
- A heartbeat interval shorter than the resolver's lookup cost (`ConcurrentHashMap.get` on a cached tag, ~50 ns) is wasted — the gate evaluates more often than it can possibly emit. Practical minimum is around 1 ms; sensible production defaults are 100 ms to 10 s.
- Disabling the heartbeat (interval = 0) makes the gate evaluate `if (0 > 0 && …)`, which short-circuits on the first conjunct. Zero overhead for the disabled case beyond the resolver lookup, which itself short-circuits on `tag.isEmpty()` and returns the default immediately.
- Tag-rule cache cardinality blow-up if a misuse-pattern host emits unique tags per request (e.g., a UUID) — same caveat as for the other two resolvers; documented as host responsibility, no LRU bound in YTDB-496.
- Custom (non-OTel) `QueryMetricsListener` implementations are unaffected. The heartbeat gate lives only in `OTelQueryMetricsListener`; the listener iteration in `iterateAllQueryListeners()` still fires every registered listener for every query. Custom listeners that want their own sampling can call `SampleHeartbeatResolver.global()` themselves.

### References
- D-records: D18 (time-based heartbeat sampling alongside slow-query gate, AtomicLong CAS for race-free single-emit per window, per-tag rules via `TagRule<Long>`)

## OpenTelemetry logs integration

The chokepoint is named, finite, and predates this design. `LogManager.installCustomFormatter()` already attaches a `ConsoleHandler` with `AnsiLogFormatter` (line 91-93 of `core/src/main/java/com/jetbrains/youtrackdb/internal/common/log/LogManager.java`); the OTel appender follows the same registration shape against the root JUL logger. The wiring goes through a new package-private `LogManager.installAdditionalHandler(Handler)` accessor that Track 7 adds, so the registration is testable and the OTel module does not reach into JUL directly.

Severity mapping (JUL → OTel sem-conv `severityNumber`):

| JUL Level | OTel `severityNumber` | OTel `severityText` |
|---|---|---|
| `FINEST` / `FINER` | 1 (TRACE) | `TRACE` |
| `FINE` | 5 (DEBUG) | `DEBUG` |
| `CONFIG` / `INFO` | 9 (INFO) | `INFO` |
| `WARNING` | 13 (WARN) | `WARN` |
| `SEVERE` | 17 (ERROR) | `ERROR` |

Pseudo-implementation:

```java
public final class OTelLogAppender extends java.util.logging.Handler {
  private final Logger otelLogger;
  private final int minSeverityNumber;
  private final ThreadLocal<Boolean> reentrant = ThreadLocal.withInitial(() -> false);

  public OTelLogAppender(LoggerProvider provider, Severity minSeverity) {
    this.otelLogger = provider.get("io.youtrackdb");
    this.minSeverityNumber = minSeverity.getSeverityNumber();
  }

  @Override
  public void publish(LogRecord record) {
    if (reentrant.get()) return;
    int severityNumber = mapJulLevelToOtel(record.getLevel());
    if (severityNumber < minSeverityNumber) return;

    reentrant.set(true);
    try {
      LogRecordBuilder builder = otelLogger.logRecordBuilder()
          .setSeverity(Severity.fromSeverityNumber(severityNumber))
          .setSeverityText(record.getLevel().getName())
          .setObservedTimestamp(record.getInstant())
          .setBody(formatMessage(record));

      Throwable thrown = record.getThrown();
      if (thrown != null) {
        builder.setAttribute(AttributeKey.stringKey("exception.type"), thrown.getClass().getName());
        builder.setAttribute(AttributeKey.stringKey("exception.message"),
            thrown.getMessage() == null ? "" : thrown.getMessage());
        builder.setAttribute(AttributeKey.stringKey("exception.stacktrace"),
            stackTraceToString(thrown));
      }
      // hard context: Context.current() picks up the active span automatically.
      builder.emit();
    } catch (RuntimeException | LinkageError | AssertionError t) {
      reportError("OTel log emit failed", t instanceof Exception e ? e : new RuntimeException(t),
          ErrorManager.GENERIC_FAILURE);
    } finally {
      reentrant.set(false);
    }
  }

  @Override public void flush() { /* SDK flushes on shutdown */ }
  @Override public void close() { /* idempotent */ }
}
```

The `setObservedTimestamp` (not `setTimestamp`) follows sem-conv guidance for the "log was observed by the appender" axis. The OTel SDK fills `Timestamp` from the exporter side; the observed timestamp is the appender's wall-clock at publish time.

### Hard-context correlation across threads

Every span the OTel listeners create runs inside a `try (Scope s = span.makeCurrent())` block. While that scope is open on the current thread, `Context.current()` returns a context carrying the span; any log call from that thread between `makeCurrent()` and `s.close()` reaches `OTelLogAppender.publish(...)`, and the `LogRecordBuilder.emit()` call attaches the same span context to the log record. Trace viewers that support log-to-trace correlation (Grafana with the OTel collector, Jaeger with the unified UI) render the log inside the span's timeline.

The correlation is automatic only when the log call originates on the thread that owns the span scope. Logs emitted from a background thread spawned inside the span scope (a thread-pool task, a `CompletableFuture` continuation) lose the correlation unless the caller propagates the context — same caveat as for child-span creation across threads, which OTel covers through `Context.taskWrapping(...)` / `Context.makeCurrent()` on the receiving thread.

### Edge cases / Gotchas

- A host that already wires JUL through SLF4J (`jul-to-slf4j` bridge): the bridge intercepts JUL records before they reach the JUL handler set, so `OTelLogAppender` never sees them. Hosts in this configuration need a parallel SLF4J appender on the OTel side, or they unbridge YTDB's logger. The design does NOT ship an SLF4J appender; the YTDB-496 scope is "log through YTDB's own chokepoint". Custom integrations beyond that are a follow-up ticket.
- High-frequency `DEBUG` / `TRACE` records (a tight loop logging every page read at `FINE`): the JUL handler set still receives them at the configured JUL level, but the severity floor drops them before any OTel allocation. Operators tune `OPENTELEMETRY_LOGS_MIN_SEVERITY` to control OTel-side volume independently from the JUL `level.properties` file the server reads.
- An exception thrown from `OTelLogAppender.publish(...)`: JUL's `Handler` contract requires the appender to not propagate exceptions back to the caller. `publish` catches the union `RuntimeException | LinkageError | AssertionError` (matching the listener-side isolation contract: catch the union that covers OTel-typical failure modes while letting `VirtualMachineError` and `ThreadDeath` propagate) and reports via `Handler.reportError(...)`, so a broken OTel exporter never disrupts a YTDB log call.
- Concurrent `setOpenTelemetry` calls during active logging: when the facade swaps SDKs, the previously-registered `OTelLogAppender` is unregistered via `LogManager.instance().removeAdditionalHandler(...)` before the new one is registered, so each log record reaches exactly one OTel pipeline. The window between unregister and register is small but non-zero; log records in that window are dropped on the OTel side but still reach the JUL handler set (the existing path is untouched).
- Bootstrap-time logs (records emitted before `OPENTELEMETRY_LOGS_ENABLED` is read): these reach only the JUL handlers, which is the desired behavior. Operators who need OTel coverage of bootstrap logs configure `OPENTELEMETRY_LOGS_ENABLED=true` via a JVM property (`-Dyoutrackdb.opentelemetry.logs.enabled=true`) so the flag is set before the first log call.
- Recursive logging from inside the OTel exporter: if the OTel exporter emits a log via `LogManager`, the appender would see its own log and feed it back into OTel, creating an unbounded loop. The thread-local `reentrant` guard above breaks the cycle by short-circuiting `publish(...)` when a `publish(...)` is already on the stack for this thread (same pattern as `GremlinSqlSuppression`).

### References
- D-records: D34 (single-chokepoint log appender registering on `LogManager.instance()`, severity-floor pre-filter, hard-context inheritance from `Context.current()`), D35 (thread-local re-entrance guard against recursive logging from inside the OTel exporter)
- Invariants: Listener exception isolation (the appender follows the same isolation contract; a throw from `publish` is caught and reported via `Handler.reportError`)

## Metrics integration

The bridge surfaces two layers: sem-conv DB metrics with names defined by the OpenTelemetry spec, and YTDB-specific gauges under the `youtrackdb.*` namespace.

**Sem-conv v1.33.0 DB metrics (curated subset):**

| OTel metric name | Stability | Instrument | YTDB source |
|---|---|---|---|
| `db.client.connection.count` | stable | `ObservableLongUpDownCounter` | active session count read from `DatabaseSessionRegistry` |
| `db.client.operation.duration` | stable | `ObservableDoubleHistogram` | query and commit `executionTimeNanos` aggregated across the last collection period |
| `db.client.response.returned_rows` | experimental | `ObservableDoubleHistogram` | row-count distribution from the listener's `QueryDetails.getResultCount()` accessor |

**YTDB-specific (`youtrackdb.*` namespace):**

| OTel metric name | Group | Instrument | Profiler source |
|---|---|---|---|
| `youtrackdb.cache.hit_ratio` | `cache` | `ObservableDoubleGauge` | `Ratio` from `MetricsRegistry` (cache hits over reads) |
| `youtrackdb.cache.page_reads` | `cache` | `ObservableLongCounter` | `MetricsRegistry` page-read counter |
| `youtrackdb.wal.pending_bytes` | `wal` | `ObservableLongGauge` | `MetricsRegistry` WAL backlog gauge |
| `youtrackdb.wal.flush_rate_bps` | `wal` | `ObservableDoubleGauge` | `TimeRate` WAL flush bytes-per-second |
| `youtrackdb.lock.contention_count` | `locks` | `ObservableLongCounter` | `MetricsRegistry` lock-wait counter |
| `youtrackdb.storage.size_bytes` | `storage` | `ObservableLongGauge` (per `database` attribute) | per-`MetricScope.Database` size gauge |
| `youtrackdb.transaction.commit_rate` | `transactions` | `ObservableDoubleGauge` | `TimeRate` commits-per-second |
| `youtrackdb.transaction.rollback_count` | `transactions` | `ObservableLongCounter` | `MetricsRegistry` rollback counter |

Sem-conv stability matters for dashboard authors: `stable` metrics will keep their names and semantics in future spec revisions; `experimental` ones may rename or change attribute shapes between sem-conv versions. The bridge surfaces both, but a downstream dashboard that depends on experimental metrics needs to track the sem-conv changelog between v1.33.0 and whatever version YTDB pins next.

### Async instrument lifecycle

`OTelMetricsBridge.start()` does two things at SDK init:

1. Iterate `Profiler.getMetricsRegistry()` once, build a `Map<String, ObservableInstrument>` keyed by the OTel metric name, register each `ObservableInstrument` against the `SdkMeterProvider`'s `Meter` (`provider.get("io.youtrackdb")`). The callback closure captures the source `Metric` reference, not the value, so each collection cycle re-reads through the registry.
2. Schedule a `ScheduledExecutorService` task at `OPENTELEMETRY_METRICS_PERIOD_MILLIS` interval. The task is a no-op semantically — the SDK's `PeriodicMetricReader` drives the actual export — but it exists to advance YTDB-side time-windowed metrics (`TimeRate`, `Ratio`) that have their own `flushRate` semantics independent of the OTel reader. Without the task, `TimeRate.currentRate()` would stall between exporter polls.

`OTelMetricsBridge.stop()` cancels the scheduled task, calls `unregister()` on each `ObservableInstrument` (drops the SDK-side callback registration), and clears the map. Idempotent: stopping a stopped bridge is a no-op.

`OTelMetricsBridge.refresh()` is a test seam: it iterates the registry once and forces a synchronous read through each registered callback, so unit tests can assert on the exporter side without waiting for the periodic reader's interval.

### Counter group filter

Operators with cardinality constraints opt into subsets via `OPENTELEMETRY_METRICS_INCLUDED_GROUPS`. The bridge classifies each profiler metric into one of six groups at registration time, reading a `group()` annotation on `MetricDefinition` (an enum with values `QUERIES`, `CACHE`, `STORAGE`, `WAL`, `LOCKS`, `TRANSACTIONS`, set per `Metric` at construction time — Track 8 adds the enum and the annotation). When the included-groups set is non-empty, the bridge skips `register()` for metrics whose group is excluded; the corresponding OTel metric simply does not appear at the exporter.

Group classification table (drives both `MetricDefinition.group()` returns and the filter parsing):

| Group | Examples (profiler-side) |
|---|---|
| `queries` | query duration histogram, query throughput, result-row distribution |
| `cache` | page-cache hit ratio, page reads, eviction count |
| `storage` | per-database size, page count, allocated bytes |
| `wal` | pending bytes, flush rate, segment count |
| `locks` | contention count, wait time histogram, deadlock count |
| `transactions` | active count, commit rate, rollback count |

Operators wanting metrics on storage health but not query throughput configure `OPENTELEMETRY_METRICS_INCLUDED_GROUPS=storage,wal,locks`. The default empty value enables every group, matching "metrics ON = everything" semantics; a host that wants nothing at all leaves `OPENTELEMETRY_METRICS_ENABLED=false` rather than emptying the included list.

### Edge cases / Gotchas

- High-cardinality attributes: a `youtrackdb.storage.size_bytes` per-database gauge fans out one OTel data point per `database` attribute value at every collection cycle. Hosts with 10k+ databases see 10k+ data points per period; the exporter side has to absorb that. The bridge does NOT add per-class or per-RID attributes — `MetricScope.Class` profiler entries collapse to one OTel data point per (metric, database) pair. If per-class breakdown is needed downstream, the host configures a dedicated OTel exporter that views the raw profiler dump.
- Bridge thread vs profiler-thread contention: the profiler's own collection threads (the `ScheduledExecutorService` passed to `Profiler` constructor) may be updating a `TimeRate` or `Ratio` while the bridge's callback reads through it. Both sides use lock-free reads on the metric primitives (`Gauge.value()` is a volatile read; `TimeRate.currentRate()` snapshots an `AtomicReference`), so the contention surface is the `AtomicReference` CAS in `TimeRate.advance()`. Worst case: the bridge reads a sample one window behind reality. Acceptable for 10-second collection cadence.
- Exporter back-pressure: OTel `PeriodicMetricReader` blocks the SDK's internal export thread when the configured exporter (OTLP, Prometheus) backs up. The bridge's scheduled task is independent of the reader — it advances time-windowed YTDB metrics regardless of exporter state — so back-pressure on the OTel side does not stall YTDB's in-JVM metric collection.
- SDK swap during active metrics: when `setOpenTelemetry` swaps SDKs, the old `OTelMetricsBridge.stop()` runs before the new one's `start()`, so each `ObservableInstrument` is registered against exactly one `SdkMeterProvider` at a time. The window between stop and start is small; any metric reader poll in that window sees zero data points (the SDK-side callbacks are unregistered).
- Disabled metrics with a host-wired SDK: when `OPENTELEMETRY_ENABLED=true` and `OPENTELEMETRY_METRICS_ENABLED=false`, the bridge is not started even if the host's OTel SDK carries a real `MeterProvider`. The host's other instrumentations continue to emit; only YTDB's bridge stays silent. This is intentional — a host that wants YTDB metrics specifically must opt them in independently of the master switch.
- Profiler not initialized: if the bridge is started before `Profiler.onStartup()` completes (race during very early bootstrap), the `MetricsRegistry` is empty and the bridge registers zero callbacks. A WARN line names the race; the operator's recovery path is to enable the bridge via JVM system property (`-Dyoutrackdb.opentelemetry.metrics.enabled=true`) so the bridge waits for the profiler's startup listener to fire instead of racing it.

### References
- D-records: D36 (OTelMetricsBridge surfaces `Profiler.getMetricsRegistry()` via OTel async instruments at a configurable period, with a scheduled task advancing YTDB-side `TimeRate`/`Ratio` independently of the OTel reader), D37 (group-based opt-in via `OPENTELEMETRY_METRICS_INCLUDED_GROUPS` with six-group taxonomy `queries`/`cache`/`storage`/`wal`/`locks`/`transactions`)
- Invariants: Listener exception isolation (callback exceptions inside `ObservableInstrument` callbacks are caught and reported via OTel's own error handler, never propagating to the profiler), Counter source untouched (the bridge reads; the profiler keeps writing on its own threads independent of OTel state)
