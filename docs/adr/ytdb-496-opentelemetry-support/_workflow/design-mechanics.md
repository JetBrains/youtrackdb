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

The chokepoint is `SLF4JLogManager.log(...)` (`core/src/main/java/com/jetbrains/youtrackdb/internal/common/log/SLF4JLogManager.java:38-103`), the single method every YTDB log call crosses. Track 7 adds a `LogAppenderHook` interface alongside `SLF4JLogManager`, a `CopyOnWriteArrayList<LogAppenderHook>` field on `SLF4JLogManager` itself, an `installAppenderHook(LogAppenderHook)` / `removeAppenderHook(LogAppenderHook)` accessor pair, and one new line inside `log(...)` at the existing emit point (right before `logEventBuilder.log()` on line 98) that iterates the hook list. The hook signature is binding-agnostic: it takes the resolved requester class name, the resolved database name, the slf4j `Level`, the already-formatted message string, and the optional `Throwable`. Hooks fire after the per-logger `isEnabledForLevel(level)` filter SLF4J already applies, so a hook subscribed at `INFO` for a logger configured at `WARN` sees no records below `WARN`. The OTel module ships `OTelLogAppender` as the only built-in implementation; the hook surface is not internal-only because a host that wants OTel-independent log routing can register its own.

Severity mapping (slf4j `event.Level` → OTel sem-conv `severityNumber`):

| slf4j Level | OTel `severityNumber` | OTel `severityText` |
|---|---|---|
| `TRACE` | 1 | `TRACE` |
| `DEBUG` | 5 | `DEBUG` |
| `INFO` | 9 | `INFO` |
| `WARN` | 13 | `WARN` |
| `ERROR` | 17 | `ERROR` |

The mapping has no `FATAL` row because slf4j `event.Level` carries no `FATAL` constant (it stops at `ERROR`). The `OPENTELEMETRY_LOGS_MIN_SEVERITY` config accepts `FATAL` for forward compatibility but treats it identically to `ERROR` (severity number 17).

Pseudo-implementation:

```java
public final class OTelLogAppender implements LogAppenderHook {
  private final Logger otelLogger;
  private final int minSeverityNumber;
  private final ThreadLocal<Boolean> reentrant = ThreadLocal.withInitial(() -> false);
  private static final java.util.logging.Logger FALLBACK =
      java.util.logging.Logger.getLogger("io.youtrackdb.otel.appender");

  public OTelLogAppender(LoggerProvider provider, Severity minSeverity) {
    this.otelLogger = provider.get("io.youtrackdb");
    this.minSeverityNumber = minSeverity.getSeverityNumber();
  }

  @Override
  public void onLog(String requesterName, String dbName,
                    org.slf4j.event.Level level, String message, Throwable thrown) {
    if (reentrant.get()) return;
    int severityNumber = mapSlf4jLevelToOtel(level);
    if (severityNumber < minSeverityNumber) return;

    reentrant.set(true);
    try {
      LogRecordBuilder builder = otelLogger.logRecordBuilder()
          .setSeverity(Severity.fromSeverityNumber(severityNumber))
          .setSeverityText(level.name())
          .setObservedTimestamp(Instant.now())
          .setBody(message);

      if (requesterName != null) {
        builder.setAttribute(AttributeKey.stringKey("code.namespace"), requesterName);
      }
      if (dbName != null) {
        builder.setAttribute(AttributeKey.stringKey("db.namespace"), dbName);
      }
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
      FALLBACK.log(java.util.logging.Level.WARNING, "OTel log emit failed", t);
    } finally {
      reentrant.set(false);
    }
  }
}
```

The `setObservedTimestamp(Instant.now())` follows sem-conv guidance for the "log was observed by the appender" axis. The OTel SDK fills `Timestamp` from the exporter side; the observed timestamp is the hook's wall-clock at invocation time. The fallback `Logger.getLogger("io.youtrackdb.otel.appender")` replaces the `Handler.reportError` channel a JUL Handler would have used. The fallback logger is itself part of the SLF4J→JUL or SLF4J→Logback dispatch chain (via slf4j-jdk14 in server mode), so its output reaches the operator's normal log sink without re-entering the OTel hook (the appender's own logger name is filtered out at install time to keep the cycle impossible).

The `SLF4JLogManager` hook iteration runs after the existing `isEnabledForLevel(level)` filter and before the marker/format work the existing path does. Hooks see the same formatted message SLF4J emits, not the raw format string and varargs; that keeps each hook from re-running `String.format(...)` and gives every hook a consistent view.

### Hard-context correlation across threads

Every span the OTel listeners create runs inside a `try (Scope s = span.makeCurrent())` block. While that scope is open on the current thread, `Context.current()` returns a context carrying the span; any log call from that thread between `makeCurrent()` and `s.close()` reaches `OTelLogAppender.onLog(...)` through the hook iteration, and the `LogRecordBuilder.emit()` call attaches the same span context to the log record. Trace viewers that support log-to-trace correlation (Grafana with the OTel collector, Jaeger with the unified UI) render the log inside the span's timeline.

The correlation is automatic only when the log call originates on the thread that owns the span scope. Logs emitted from a background thread spawned inside the span scope (a thread-pool task, a `CompletableFuture` continuation) lose the correlation unless the caller propagates the context. Same caveat as for child-span creation across threads, which OTel covers through `Context.taskWrapping(...)` / `Context.makeCurrent()` on the receiving thread.

### Edge cases / Gotchas

- A host that binds `slf4j-nop` (no-op SLF4J provider): SLF4J's per-logger `isEnabledForLevel(...)` returns `false` for every level under the NoOp provider, so `SLF4JLogManager.log(...)` short-circuits before the hook iteration and `OTelLogAppender.onLog(...)` is never invoked. This is the documented behavior — a host that explicitly disables logging gets no OTel logs either. Operators who want OTel logs without SLF4J output bind any non-NoOp provider with its level set high enough to suppress local output (e.g., `slf4j-jdk14` with `INFO` floor).
- High-frequency `DEBUG` / `TRACE` records (a tight loop logging every page read at `DEBUG`): SLF4J's per-logger level filter drops them before the hook iteration when the bound provider's level is set higher. When the bound level admits them, the severity floor drops them at the top of `onLog(...)` before any `LogRecordBuilder` allocation. Operators tune `OPENTELEMETRY_LOGS_MIN_SEVERITY` to control OTel-side volume independently from the bound provider's level config.
- An exception thrown from `OTelLogAppender.onLog(...)`: the `SLF4JLogManager` hook iteration wraps each hook call in `try { hook.onLog(...); } catch (Exception | LinkageError | AssertionError t) { /* fallback */ }` (same union as the listener wrappers per the exception-isolation contract). A throwing hook is logged via the same fallback `Logger.getLogger("io.youtrackdb.otel.appender")` channel and the next hook still fires; YTDB's own log call returns normally. `VirtualMachineError` and `ThreadDeath` propagate.
- Concurrent `setOpenTelemetry` calls during active logging: when the facade swaps SDKs, the previously-registered `OTelLogAppender` is removed via `SLF4JLogManager.removeAppenderHook(oldHook)` before the new one is installed, so each log record reaches exactly one OTel pipeline. The window between remove and install is small but non-zero; log records in that window are dropped on the OTel side but still reach the bound SLF4J provider (the existing path is untouched). Multiple `LogAppenderHook` implementations coexist by being separate list entries.
- Bootstrap-time logs (records emitted before `OPENTELEMETRY_LOGS_ENABLED` is read): these reach only the bound SLF4J provider, which is the desired behavior. Operators who need OTel coverage of bootstrap logs configure `OPENTELEMETRY_LOGS_ENABLED=true` via a JVM property (`-Dyoutrackdb.opentelemetry.logs.enabled=true`) so the flag is set before the first log call.
- Recursive logging from inside the OTel exporter: if the OTel exporter emits a log via `LogManager`, the hook would see its own log and feed it back into OTel, creating an unbounded loop. The thread-local `reentrant` guard above breaks the cycle by short-circuiting `onLog(...)` when an `onLog(...)` is already on the stack for this thread (a `ThreadLocal<Boolean>` flag set on entry and cleared on exit via `try/finally`). The fallback `Logger.getLogger("io.youtrackdb.otel.appender")` channel is independently protected by being filtered out at hook install time.
- Hosts already using the OTel `opentelemetry-instrumentation-logback-appender` (or its log4j equivalent) on their SLF4J binding: that path catches every log record SLF4J emits, including YTDB's. Running both side-by-side double-emits each YTDB log record (once via the host's appender, once via YTDB's hook). Operators run one or the other, not both. The hook is the recommended path because it carries the `Context.current()` read at the YTDB invocation site, not at the host's appender callback site (potentially on a different thread).

### References
- D-records: D34 (single-chokepoint `LogAppenderHook` invoked inside `SLF4JLogManager.log`, severity-floor pre-filter, hard-context inheritance from `Context.current()`), D35 (thread-local re-entrance guard against recursive logging from inside the OTel exporter)
- Invariants: Listener exception isolation (the hook follows the same isolation contract; a throw from `onLog` is caught at the iteration site and reported via the fallback `Logger.getLogger("io.youtrackdb.otel.appender")` channel)

## Metrics integration

The bridge surfaces two layers: sem-conv DB metrics with names defined by the OpenTelemetry spec, and YTDB-specific gauges under the `youtrackdb.*` namespace. The CoreMetrics inventory carries some of these today; the rest land in Track 8 alongside the `MetricGroup` enum that drives the included-groups filter. Each row is annotated `(existing)` when its profiler source already lives in `core/.../profiler/metrics/CoreMetrics.java`, or `(new in Track 8)` when Track 8 adds the underlying `MetricDefinition` plus the writer site that populates it.

**Sem-conv v1.33.0 DB metrics (curated subset):**

| OTel metric name | Stability | Instrument | YTDB source |
|---|---|---|---|
| `db.client.connection.count` | stable | `ObservableLongUpDownCounter` | active session count read from `DatabaseSessionRegistry` (new in Track 8) |
| `db.client.operation.duration` | stable | `ObservableDoubleHistogram` | query and commit `executionTimeNanos` aggregated across the last collection period — sourced from the listener fire sites (Track 3 / Track 4), not from `MetricsRegistry`, so no new profiler-side metric is needed |
| `db.client.response.returned_rows` | experimental | `ObservableDoubleHistogram` | row-count distribution from `QueryDetails.getResultCount(): OptionalLong` (Track 1 adds the accessor; Track 4 populates it from the `InstrumentedSqlResultSet` wrapper's per-`next()` row counter, which is correct for both `LocalResultSet` and `CachedResultSetView` inner result-sets per YTDB-820 coordination; Track 3 Gremlin path leaves it empty in v1) |

**YTDB-specific (`youtrackdb.*` namespace):**

| OTel metric name | Group | Instrument | Profiler source |
|---|---|---|---|
| `youtrackdb.cache.hit_ratio` | `cache` | `ObservableDoubleGauge` | `CoreMetrics.CACHE_HIT_RATIO` (existing — `Ratio`, cache hits over reads, 60s window) |
| `youtrackdb.disk.read_rate_bps` | `cache` | `ObservableDoubleGauge` | `CoreMetrics.DISK_READ_RATE` (existing — `TimeRate`, bytes per second, 60s window) |
| `youtrackdb.disk.write_rate_bps` | `cache` | `ObservableDoubleGauge` | `CoreMetrics.DISK_WRITE_RATE` (existing — `TimeRate`, bytes per second, 60s window) |
| `youtrackdb.cache.page_reads_total` | `cache` | `ObservableLongCounter` | `CoreMetrics.PAGE_READ_COUNT` (new in Track 8 — `Counter`, monotonic page-read count populated by the disk-cache page-fault path) |
| `youtrackdb.cache.evictions_total` | `cache` | `ObservableLongCounter` | `CoreMetrics.FILE_EVICTION_RATE` (existing — converted from rate to monotonic counter at OTel-bridge translation time) |
| `youtrackdb.wal.pending_bytes` | `wal` | `ObservableLongGauge` | `CoreMetrics.WAL_PENDING_BYTES` (new in Track 8 — `Gauge<Long>`, in-flight WAL backlog read at the WAL writer's `flushPending()` site) |
| `youtrackdb.wal.flush_rate_bps` | `wal` | `ObservableDoubleGauge` | `CoreMetrics.WAL_FLUSH_RATE` (new in Track 8 — `TimeRate`, WAL flush bytes per second, 60s window) |
| `youtrackdb.lock.contention_count` | `locks` | `ObservableLongCounter` | `CoreMetrics.LOCK_WAIT_COUNT` (new in Track 8 — `Counter`, monotonic wait-event count populated by `ReadWriteLock` wrappers in the storage layer) |
| `youtrackdb.storage.size_bytes` | `storage` | `ObservableLongGauge` (per `database` attribute) | `CoreMetrics.DATABASE_SIZE_BYTES` (new in Track 8 — per-`MetricScope.Database` `Gauge<Long>` read from the storage layer's `getSizeOnDisk()`) |
| `youtrackdb.transaction.commit_rate` | `transactions` | `ObservableDoubleGauge` | `CoreMetrics.TRANSACTION_WRITE_RATE` (existing — `TimeRate`, write commits per second, 60s window) |
| `youtrackdb.transaction.rate` | `transactions` | `ObservableDoubleGauge` | `CoreMetrics.TRANSACTION_RATE` (existing — `TimeRate`, all transactions per second, 60s window) |
| `youtrackdb.transaction.rollback_rate` | `transactions` | `ObservableDoubleGauge` | `CoreMetrics.TRANSACTION_WRITE_ROLLBACK_RATE` (existing — `TimeRate`, write rollbacks per second, 60s window) |
| `youtrackdb.transaction.active_count` | `transactions` | `ObservableLongGauge` | `CoreMetrics.ACTIVE_TX_COUNT` (existing — per-database `Gauge<Integer>`) |
| `youtrackdb.transaction.oldest_age_seconds` | `transactions` | `ObservableLongGauge` | `CoreMetrics.OLDEST_TX_AGE` (existing — per-database `Gauge<Long>`) |

Sem-conv stability matters for dashboard authors: `stable` metrics will keep their names and semantics in future spec revisions; `experimental` ones may rename or change attribute shapes between sem-conv versions. The bridge surfaces both, but a downstream dashboard that depends on experimental metrics needs to track the sem-conv changelog between v1.33.0 and whatever version YTDB pins next.

### MetricsRegistry enumeration and lazy-registration API

`OTelMetricsBridge` cannot rely on call-site knowledge of every `MetricDefinition` — Track 8 adds enough new ones that a hand-maintained mirror list would drift. Two new public-API methods on `MetricsRegistry` close the gap:

```java
public interface MetricVisitor {
  void visit(String fullyQualifiedName, MetricDefinition<?, ?> def, Metric<?> metric);
}

public void forEachMetric(MetricVisitor visitor);  // walks GLOBAL + every DatabaseMetrics group, calls visitor exactly once per metric instance currently registered

public record MetricRegistrationEvent(
    String fullyQualifiedName,
    MetricDefinition<?, ?> def,
    Metric<?> metric,
    @Nullable String databaseName  // null for GLOBAL scope metrics
) {}

public void addRegistrationListener(Consumer<MetricRegistrationEvent> listener);
public void removeRegistrationListener(Consumer<MetricRegistrationEvent> listener);
```

Implementation: `MetricsRegistry` holds a `CopyOnWriteArrayList<Consumer<MetricRegistrationEvent>>` and fires every listener inside `MetricsGroup.init(...)` (the existing `computeIfAbsent` site) when a new metric is created. The fire is synchronous on the registering thread; listeners that need to do heavy work (registering an OTel `ObservableInstrument` is non-trivial) must schedule it onto their own executor. `OTelMetricsBridge` posts to its scheduled executor so the registration thread is not blocked.

`forEachMetric(...)` walks `globalMetrics.mGroup.metrics`, then iterates `perDatabaseMetrics.values()` walking each `DatabaseMetrics.mGroup.metrics`. The walk is a consistent snapshot of `ConcurrentHashMap.entrySet()` — concurrent registrations during the walk may or may not be visible, but every metric registered before `forEachMetric` was called is visited exactly once. The registration listener picks up anything added during or after the walk, so the combination of `forEachMetric` at `start()` plus the listener subscription is a complete enumeration with no race window.

### Async instrument lifecycle

### Async instrument lifecycle

`OTelMetricsBridge.start()` does three things at SDK init:

1. Call `Profiler.getMetricsRegistry().forEachMetric(...)` to enumerate every currently-registered metric, build a `Map<String, ObservableInstrument>` keyed by the OTel metric name, and register each `ObservableInstrument` against the `SdkMeterProvider`'s `Meter` (`provider.get("io.youtrackdb")`). The callback closure captures the source `Metric` reference, not the value, so each collection cycle re-reads through the registry.
2. Subscribe a `Consumer<MetricRegistrationEvent>` via `registry.addRegistrationListener(...)`. Each event posts a task onto the bridge's `ScheduledExecutorService` that does the same name-build + `ObservableInstrument` register dance as step 1 for the newly-added metric. This covers the per-database metrics created lazily when `Profiler.getMetricsRegistry().databaseMetric(def, dbName)` is first invoked for a database opened after `start()` ran.
3. Schedule a periodic task at `OPENTELEMETRY_METRICS_PERIOD_MILLIS`. The SDK's `PeriodicMetricReader` drives the actual export; the bridge's task advances YTDB-side time-windowed metrics (`TimeRate`, `Ratio`) that have their own `flushRate` semantics independent of the OTel reader. Without the task, `TimeRate.currentRate()` would stall between exporter polls.

`OTelMetricsBridge.stop()` calls `registry.removeRegistrationListener(...)`, cancels the scheduled task, calls `unregister()` on each `ObservableInstrument` (drops the SDK-side callback registration), and clears the map. Idempotent: stopping a stopped bridge is a no-op.

`OTelMetricsBridge.refresh()` is a test seam: it forces a synchronous read through each registered callback, so unit tests can assert on the exporter side without waiting for the periodic reader's interval.

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
- Profiler not initialized: if the bridge is started before `Profiler.onStartup()` completes (race during very early bootstrap), the initial `forEachMetric(...)` walk visits zero metrics. The `addRegistrationListener(...)` subscription still fires for every metric `Profiler.onStartup()` subsequently creates, so the bridge ends up fully wired without a separate retry path. No WARN is emitted in this case — the late-arriving registration events are the expected wiring sequence under early bootstrap.
- Late-DB-open registration event: when `databaseMetric(def, dbName)` first runs for a new database, the registration listener fires synchronously on the registering thread. The bridge handler posts the OTel `ObservableInstrument` registration to its own `ScheduledExecutorService` and returns immediately, so the database-open path is not blocked by OTel-side allocation. The first metric reader poll after the registration sees the new instrument; polls before the registration land see no data point for that database (consistent with the database simply not having been registered yet).
- Concurrent enumeration race: a database opened concurrently with `start()`'s `forEachMetric(...)` walk may or may not be visited by the walk — `ConcurrentHashMap.entrySet()` is weakly consistent. Either outcome is correct: the `addRegistrationListener(...)` subscription registered before the walk catches anything the walk missed, so every metric registers exactly once across the combination of walk + listener fires (the listener's idempotence check on `registered.containsKey(name)` handles the rare case where both paths see the same registration event).

### References
- D-records: D36 (OTelMetricsBridge surfaces `Profiler.getMetricsRegistry()` via OTel async instruments at a configurable period, with a scheduled task advancing YTDB-side `TimeRate`/`Ratio` independently of the OTel reader), D37 (group-based opt-in via `OPENTELEMETRY_METRICS_INCLUDED_GROUPS` with six-group taxonomy `queries`/`cache`/`storage`/`wal`/`locks`/`transactions`)
- Invariants: Listener exception isolation (callback exceptions inside `ObservableInstrument` callbacks are caught and reported via OTel's own error handler, never propagating to the profiler), Counter source untouched (the bridge reads; the profiler keeps writing on its own threads independent of OTel state)
