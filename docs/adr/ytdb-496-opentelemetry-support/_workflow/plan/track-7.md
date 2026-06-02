<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# Track 7: OpenTelemetry logs pillar — `OTelLogAppender` via `SLF4JLogManager` hook

## Purpose / Big Picture

After this track lands, every log record emitted through YTDB's `LogManager.instance()` chokepoint also feeds the OTel `LogRecordBuilder` pipeline without source-side rewrites, hard-context-correlated with the active span at emission time. Trace viewers that support log-to-trace correlation render YTDB logs inside the span timeline. The integration adds a new `LogAppenderHook` interface in `core/.../common/log/` invoked from inside `SLF4JLogManager.log(...)` at line 98 (immediately before `logEventBuilder.log()`), so the hook fires regardless of which SLF4J binding is on the classpath (server: `slf4j-jdk14`; embedded: typically Logback or `log4j-slf4j-impl`).

The OTel logs pillar is gated by three new `GlobalConfiguration` entries: `OPENTELEMETRY_LOGS_ENABLED` (default `false`, master switch), `OPENTELEMETRY_LOGS_MIN_SEVERITY` (default `INFO`, severity floor that drops below-threshold records before any OTel allocation), and `OPENTELEMETRY_LOGS_INCLUDE_MESSAGE_BODY` (default `false`, body-policy default-deny). The body-policy default ships the SLF4J format string as the OTel `body` so parameter values, raw SQL, record content, and user identifiers do NOT leak into the log pipeline by default; hosts in PII-regulated environments install a host-side OTel `LogRecordProcessor` for redaction at the collector boundary and flip the flag only after that processor is wired. When the master switch is `false`, no hook is installed and the bound SLF4J provider is the only sink. When `true` and `OPENTELEMETRY_ENABLED=true`, the SDK builds an `SdkLoggerProvider` alongside the `SdkTracerProvider` from the same exporter endpoint, and `YouTrackDBOpenTelemetry` constructs an `OTelLogAppender` from `provider.get("io.youtrackdb")` and registers it via `SLF4JLogManager.installAppenderHook(LogAppenderHook)`.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Covers D34 (single-chokepoint log appender invoked inside `SLF4JLogManager.log`, severity-floor pre-filter, hard-context inheritance) and D35 (thread-local re-entrance guard against recursive logging from inside the OTel exporter).

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

YTDB's logger lives at `com.jetbrains.youtrackdb.internal.common.log.LogManager` (extends `SLF4JLogManager` which provides the `log` / `debug` / `info` / `warn` / `error` public methods at `core/src/main/java/com/jetbrains/youtrackdb/internal/common/log/SLF4JLogManager.java:38-103`). `LogManager.instance()` returns a process-global singleton; every emit from `core`, `embedded`, `server`, and `youtrackdb-opentelemetry` routes through `SLF4JLogManager.log(...)` which calls `LoggerFactory.getLogger(...).makeLoggingEventBuilder(level).log()` at line 98. The hook fires immediately before that line so it sees the same formatted message and resolved database name the SLF4J emit would emit, independent of which binding (slf4j-jdk14, Logback, log4j-slf4j-impl, slf4j-simple) is on the classpath.

Concrete deliverables:

1. New `LogAppenderHook` interface in `core/src/main/java/com/jetbrains/youtrackdb/internal/common/log/LogAppenderHook.java` with one method `void onLog(String requesterName, String dbName, org.slf4j.event.Level slf4jLevel, String formatString, String formattedMessage, Throwable thrown, long eventEpochNanos)`. The hook receives both the SLF4J format string and the corresponding formatted message so each appender picks the body shape its body-policy demands: `OTelLogAppender` picks per `OPENTELEMETRY_LOGS_INCLUDE_MESSAGE_BODY` (default `false` ships the format string and keeps parameter values out of the log pipeline). `eventEpochNanos` is the SLF4J `LoggingEvent.getTimeStamp()` value (the original log-call wall-clock captured by the SLF4J facade BEFORE the hook fires) lifted from milliseconds to nanoseconds so the OTel sem-conv `Timestamp` field matches the original log-call time, even for high-frequency `DEBUG`/`TRACE` records where dispatch latency would otherwise drift `Instant.now()` by tens of microseconds. Package-private — only `SLF4JLogManager` and OTel-module subscribers should reference it.
2. New `installAppenderHook(LogAppenderHook)` and `removeAppenderHook(LogAppenderHook)` package-private accessors on `SLF4JLogManager`, plus a `CopyOnWriteArrayList<LogAppenderHook>` field. The hook iteration runs at `SLF4JLogManager.log(...)` line 98 (immediately before `logEventBuilder.log()`) inside a try/catch wrapper that catches `Exception | LinkageError | AssertionError` per the listener-side isolation contract; a throwing hook is reported via `Logger.getLogger("io.youtrackdb.otel.appender")` and the next hook still fires. Before the iteration, a `requesterName`-prefix filter skips records whose logger name matches any entry in a `Set<String> excludedRequesterPrefixes` field. The set is populated at hook install time from the `OPENTELEMETRY_LOGS_LOGGER_EXCLUSIONS` config entry (default `io.opentelemetry.,io.youtrackdb.otel.appender`). The filter is the cross-thread recursive-cycle defense: OTel exporter logs written on the exporter's own thread pool would otherwise route through any `jul-to-slf4j` bridge the host has installed and re-enter `LogManager`, bypassing the per-thread `ThreadLocal` re-entrance guard inside `OTelLogAppender.onLog(...)`.
3. New `OTelLogAppender implements LogAppenderHook` class in `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/logs/`. Constructor takes `LoggerProvider provider` and `Severity minSeverity`; resolves `Logger otelLogger = provider.get("io.youtrackdb")` and `int minSeverityNumber = minSeverity.getSeverityNumber()` once at construction. Implements `onLog(...)` with the severity-floor + re-entrance guard + `LogRecordBuilder` emit pattern from design-mechanics.md §"OpenTelemetry logs integration".
4. slf4j→OTel severity-number mapping (static helper in `OTelLogAppender` or a separate `Slf4jLevelMap` utility class — Track 7 picks at implementation time): `TRACE` → 1, `DEBUG` → 5, `INFO` → 9, `WARN` → 13, `ERROR` → 17. The `OPENTELEMETRY_LOGS_MIN_SEVERITY` config accepts `FATAL` for forward compatibility but treats it identically to `ERROR` (severity number 17) because slf4j `event.Level` carries no `FATAL` constant.
5. Thread-local re-entrance guard (`private final ThreadLocal<Boolean> reentrant = ThreadLocal.withInitial(() -> false)`) at the top of `onLog()`. Set to `true` before the `LogRecordBuilder` chain; cleared in `finally`. Breaks the cycle when the OTel exporter itself emits a log via `LogManager`.
6. `YouTrackDBOpenTelemetry.setOpenTelemetry(...)` extension: when the resolved SDK carries a non-noop `LoggerProvider`, construct an `OTelLogAppender` from `LoggerProvider.get("io.youtrackdb")` with `Severity.fromSeverityNumber(<configured min>)` and call `SLF4JLogManager.installAppenderHook(...)`. Track ownership so `shutdown()` calls `removeAppenderHook(...)` only when the appender is YTDB-owned. SDK swap during active logging: old hook is removed before new one is installed, so each log record reaches exactly one OTel pipeline.

## Plan of Work

Four edits.

The first edit adds the `LogAppenderHook` interface plus the `installAppenderHook(...)` / `removeAppenderHook(...)` accessors, the `excludedRequesterPrefixes` field populated from `OPENTELEMETRY_LOGS_LOGGER_EXCLUSIONS`, and the hook iteration in `SLF4JLogManager.log(...)` at line 98. JUnit test (`SLF4JLogManagerHookTest` in the existing `core` test tree) asserts: a registered hook receives every `LogManager.instance().info("...")` call after the per-logger `isEnabledForLevel(level)` filter passes, an unregistered hook receives nothing, double-register inserts both list entries (additive: multiple hooks coexist as separate subscribers), `removeAppenderHook` for a never-registered hook does not throw, a throwing hook does not break the host's log call (the throw is reported via the fallback channel and SLF4J still emits), and a record whose `requesterName` starts with `io.opentelemetry.` reaches the underlying SLF4J emit but skips every installed hook.

The second edit adds `OTelLogAppender` to the OTel module, including the severity-mapping helper, the thread-local re-entrance guard, the `try/finally` around the `LogRecordBuilder` emit chain, and the catch for `RuntimeException | LinkageError | AssertionError` (matching the listener-side isolation contract — `VirtualMachineError` and `ThreadDeath` propagate) reporting via `Logger.getLogger("io.youtrackdb.otel.appender").log(WARNING, ...)`. Test fixture: synthesized `onLog(...)` invocations plus assertions on the emitted `LogRecordBuilder` state.

The third edit extends `YouTrackDBOpenTelemetry` to build and register the appender from a non-noop `LoggerProvider` after the existing tracer wiring. The setter signature does not change; the appender wiring is conditional on `LoggerProvider` being non-noop (which is itself conditional on `OPENTELEMETRY_LOGS_ENABLED=true` in the SDK builder — see Track 5's `SdkLoggerProvider` build path). `shutdown()` removes the hook via `SLF4JLogManager.removeAppenderHook(...)`. SDK swap during active logging: the previous-SDK hook is removed before the new-SDK hook installs.

The fourth edit adds JUnit tests in the OTel module covering severity floor drop semantics (records below `OPENTELEMETRY_LOGS_MIN_SEVERITY` produce zero `LogRecordBuilder.emit()` calls); hard-context attach (a log call from inside a `try (Scope s = span.makeCurrent())` block produces a `LogRecordBuilder` whose `Context` matches the surrounding span's `traceId`/`spanId`); slf4j→OTel level mapping for each entry in the table; re-entrance guard via a mock `LogRecordExporter` that calls `LogManager.instance().info(...)` from inside its own export method, asserting the recursion terminates after exactly one cycle; fallback-channel isolation when the `LogRecordBuilder.emit()` throws (e.g., a misconfigured exporter); a `@Disabled` regression scenario documenting the SLF4J-NoOp edge case (host binding `slf4j-nop` makes `isEnabledForLevel(...)` return false, so the hook never fires — documented as expected).

Ordering: edit 1 is independent. Edits 2 + 3 + 4 build on edit 1; edit 4 builds on 2 + 3 but can be drafted in parallel with the implementation work.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 7:

- `./mvnw -pl core clean test` passes with `SLF4JLogManagerHookTest` green.
- `./mvnw -pl youtrackdb-opentelemetry clean test` passes with all `OTelLogAppender*` test classes green.
- The OTel module's coverage gate (≥85% line / ≥70% branch) holds on `OTelLogAppender` and the severity-mapping helper.
- A manual smoke test against an OTLP collector (out-of-CI verification): start an embedded YTDB session with `OPENTELEMETRY_ENABLED=true OPENTELEMETRY_LOGS_ENABLED=true` and an OTLP HTTP exporter; emit a `LogManager.instance().info(...)` call from inside a query listener span scope; verify the collector receives a log record carrying the same `traceId` as the span.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

## Interfaces and Dependencies

In scope:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/log/LogAppenderHook.java` (new package-private interface with one method `onLog(...)`).
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/log/SLF4JLogManager.java` (new `installAppenderHook(...)` / `removeAppenderHook(...)` accessors, new `CopyOnWriteArrayList<LogAppenderHook>` field, new hook iteration line in `log(...)` at line 98 with the exception-isolation wrapper).
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/logs/OTelLogAppender.java` (new class implementing `LogAppenderHook`).
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/logs/Slf4jLevelMap.java` (new static helper for the slf4j→OTel severity mapping; may be folded into `OTelLogAppender` as a private static method if Track 7 picks that layout at implementation time).
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/YouTrackDBOpenTelemetry.java` (extended to build and register the appender from a non-noop `LoggerProvider`; `shutdown()` removes the hook).
- `core/src/test/java/com/jetbrains/youtrackdb/internal/common/log/SLF4JLogManagerHookTest.java` (new JUnit test for the `installAppenderHook` / `removeAppenderHook` contract plus the hook iteration's exception isolation).
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/logs/OTelLogAppenderTest.java` (new JUnit test covering severity floor, hard-context attach, slf4j→OTel mapping, re-entrance guard, fallback-channel isolation, SLF4J-NoOp `@Disabled` regression).

Out of scope:
- A separate JUL `Handler` registration path for hosts that prefer JUL output (the SLF4J binding handles per-host output; the hook is the YTDB-side fan-out point regardless of binding).
- Bootstrap-time logs before `OPENTELEMETRY_LOGS_ENABLED` is resolved (covered by the JVM-property workaround documented in `design.md §"OpenTelemetry logs integration"` Edge cases).
- Cross-thread context propagation for logs from background threads spawned inside a span scope (caller responsibility, same caveat as for child-span creation).
- Track 5's `OPENTELEMETRY_LOGS_ENABLED` and `OPENTELEMETRY_LOGS_MIN_SEVERITY` `GlobalConfiguration` entries (Track 5 owns them; Track 7 consumes them).
- Track 5's `SdkLoggerProvider` autoconfigure build path (Track 5 owns it; Track 7 reads the resolved `LoggerProvider` reference from the facade).

Inter-track dependencies:
- Depends on Track 3 (the OTel facade `YouTrackDBOpenTelemetry` exists with `setOpenTelemetry(...)` / `shutdown()` extension points).
- Depends on Track 5 (`OPENTELEMETRY_LOGS_*` config entries exist; the SDK autoconfigure builds `SdkLoggerProvider` alongside `SdkTracerProvider` when the master switch is on).
- Provides for nothing downstream that is not already owned by other tracks. Track 6 test infrastructure does not need to extend to cover Track 7; tests in the new `OTelLogAppenderTest` class cover the logs pillar end-to-end.
