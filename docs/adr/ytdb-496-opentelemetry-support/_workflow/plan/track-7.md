<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# Track 7: OpenTelemetry logs pillar — `OTelLogAppender` on `LogManager.instance()` chokepoint

## Purpose / Big Picture

After this track lands, every log record emitted through YTDB's `LogManager.instance()` chokepoint also feeds the OTel `LogRecordBuilder` pipeline without source-side rewrites, hard-context-correlated with the active span at emission time. Trace viewers that support log-to-trace correlation render YTDB logs inside the span timeline. The integration leverages the existing single-handler-registration shape (`installCustomFormatter()` already attaches a `ConsoleHandler` with `AnsiLogFormatter` at line 91-93 of `core/.../common/log/LogManager.java`); a new package-private `installAdditionalHandler(Handler)` accessor adds the OTel appender alongside the existing handler set without touching any log call site.

The OTel logs pillar is gated by two new `GlobalConfiguration` entries — `OPENTELEMETRY_LOGS_ENABLED` (default `false`, master switch) and `OPENTELEMETRY_LOGS_MIN_SEVERITY` (default `INFO`, severity floor that drops below-threshold records before any OTel allocation). When the master switch is `false`, the appender is never registered and the existing JUL handler set is the only sink. When `true` and `OPENTELEMETRY_ENABLED=true`, the SDK builds an `SdkLoggerProvider` alongside the `SdkTracerProvider` from the same exporter endpoint, and `YouTrackDBOpenTelemetry` constructs an `OTelLogAppender` from `provider.get("io.youtrackdb")` registering it on `LogManager.instance()`.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Covers D34 (single-chokepoint log appender, severity-floor pre-filter, hard-context inheritance) and D35 (thread-local re-entrance guard against recursive logging from inside the OTel exporter).

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

YTDB's logger lives at `com.jetbrains.youtrackdb.internal.common.log.LogManager` (extends `SLF4JLogManager` which provides the `log` / `debug` / `info` / `warn` / `error` public methods). `LogManager.instance()` returns a process-global singleton; every `LogManager.instance().log(Level, message, ...)` call in `core`, `embedded`, `server`, and `youtrackdb-opentelemetry` routes through `java.util.logging.Logger` instances, which fan out to the root JUL logger's handler set. `LogManager.installCustomFormatter()` at line 91-93 demonstrates the registration shape: it calls `Logger.getLogger("").addHandler(...)` on the root JUL logger after configuring the `AnsiLogFormatter`. Track 7's `installAdditionalHandler(Handler)` accessor follows the same shape but takes the handler from the caller (the OTel module) rather than constructing a `ConsoleHandler` internally.

Concrete deliverables:

1. New package-private accessors `LogManager.installAdditionalHandler(Handler)` and `LogManager.removeAdditionalHandler(Handler)` in `core/src/main/java/com/jetbrains/youtrackdb/internal/common/log/LogManager.java`. Both delegate to `java.util.logging.Logger.getLogger("").addHandler(...)` / `removeHandler(...)` on the root JUL logger. Idempotent on remove (no exception if the handler was not registered). Used only by `YouTrackDBOpenTelemetry` in the OTel module; package-private visibility prevents external misuse.
2. New `OTelLogAppender extends java.util.logging.Handler` class in `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/logs/`. Constructor takes `LoggerProvider provider` and `Severity minSeverity`; resolves `Logger otelLogger = provider.get("io.youtrackdb")` and `int minSeverityNumber = minSeverity.getSeverityNumber()` once at construction. Overrides `publish(LogRecord record)` with the severity-floor + re-entrance guard + `LogRecordBuilder` emit pattern from design-mechanics.md §"OpenTelemetry logs integration". `flush()` is a no-op (the OTel SDK flushes on its own shutdown). `close()` is idempotent.
3. JUL→OTel severity-number mapping (static helper in `OTelLogAppender` or a separate `JulLevelMap` utility class — Track 7 picks at implementation time): `FINEST` / `FINER` → 1 (TRACE), `FINE` → 5 (DEBUG), `CONFIG` / `INFO` → 9 (INFO), `WARNING` → 13 (WARN), `SEVERE` → 17 (ERROR). The mapping uses `Level.intValue()` bracket comparison so custom `Level` subclasses with intermediate values fall into the nearest lower bracket.
4. Thread-local re-entrance guard (`private final ThreadLocal<Boolean> reentrant = ThreadLocal.withInitial(() -> false)`) at the top of `publish()`. Set to `true` before the `LogRecordBuilder` chain; cleared in `finally`. Breaks the cycle when the OTel exporter itself emits a log via `LogManager`.
5. `YouTrackDBOpenTelemetry.setOpenTelemetry(...)` extension: when the resolved SDK carries a non-noop `LoggerProvider`, construct an `OTelLogAppender` from `LoggerProvider.get("io.youtrackdb")` with `Severity.fromSeverityNumber(<configured min>)` and call `LogManager.instance().installAdditionalHandler(...)`. Track ownership so `shutdown()` calls `removeAdditionalHandler(...)` only when the appender is YTDB-owned. SDK swap during active logging: old appender is removed before new one is registered, so each log record reaches exactly one OTel pipeline.

## Plan of Work

Four edits.

The first edit adds the package-private `installAdditionalHandler(Handler)` and `removeAdditionalHandler(Handler)` accessors to `LogManager`, plus a JUnit test (`LogManagerHandlerRegistrationTest` in the existing `core` test tree) that asserts: a registered handler receives every `LogManager.instance().info("...")` call, an unregistered handler receives nothing, double-register is a no-op (the existing root-logger contract), and `removeAdditionalHandler` for a never-registered handler does not throw.

The second edit adds `OTelLogAppender` to the OTel module, including the severity-mapping helper, the thread-local re-entrance guard, the `try/finally` around the `LogRecordBuilder` emit chain, and the `Handler.reportError(...)` catch for `RuntimeException | LinkageError | AssertionError` (matching the listener-side isolation contract — `VirtualMachineError` and `ThreadDeath` propagate). Test fixture: a JUL `LogRecord` factory plus assertions on the emitted `LogRecordBuilder` state.

The third edit extends `YouTrackDBOpenTelemetry` to build and register the appender from a non-noop `LoggerProvider` after the existing tracer wiring. The setter signature does not change; the appender wiring is conditional on `LoggerProvider` being non-noop (which is itself conditional on `OPENTELEMETRY_LOGS_ENABLED=true` in the SDK builder — see Track 5's `SdkLoggerProvider` build path). `shutdown()` removes the appender via `LogManager.instance().removeAdditionalHandler(...)`. SDK swap during active logging: the previous-SDK appender is removed before the new-SDK appender registers.

The fourth edit adds JUnit tests in the OTel module covering severity floor drop semantics (records below `OPENTELEMETRY_LOGS_MIN_SEVERITY` produce zero `LogRecordBuilder.emit()` calls); hard-context attach (a log call from inside a `try (Scope s = span.makeCurrent())` block produces a `LogRecordBuilder` whose `Context` matches the surrounding span's `traceId`/`spanId`); JUL→OTel level mapping for each entry in the table including a `Level VERBOSE = new Level("VERBOSE", 850)` custom subclass that falls into the `INFO` bracket; re-entrance guard via a mock `LogRecordExporter` that calls `LogManager.instance().info(...)` from inside its own export method, asserting the recursion terminates after exactly one cycle; `Handler.reportError` isolation when the `LogRecordBuilder.emit()` throws (e.g., a misconfigured exporter); a `@Disabled` regression scenario documenting the SLF4J-bridge coverage gap (hosts wiring `jul-to-slf4j` intercept records before they reach the JUL handler set).

Ordering: edit 1 is independent. Edits 2 + 3 + 4 build on edit 1; edit 4 builds on 2 + 3 but can be drafted in parallel with the implementation work.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 7:

- `./mvnw -pl core clean test` passes with `LogManagerHandlerRegistrationTest` green.
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
- `core/src/main/java/com/jetbrains/youtrackdb/internal/common/log/LogManager.java` (new package-private `installAdditionalHandler(Handler)` and `removeAdditionalHandler(Handler)` accessors mirroring the existing `installCustomFormatter()` shape).
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/logs/OTelLogAppender.java` (new class extending `java.util.logging.Handler`).
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/logs/JulLevelMap.java` (new static helper for the JUL→OTel severity mapping; may be folded into `OTelLogAppender` as a private static method if Track 7 picks that layout at implementation time).
- `youtrackdb-opentelemetry/src/main/java/com/jetbrains/youtrackdb/opentelemetry/YouTrackDBOpenTelemetry.java` (extended to build and register the appender from a non-noop `LoggerProvider`; `shutdown()` removes the appender).
- `core/src/test/java/com/jetbrains/youtrackdb/internal/common/log/LogManagerHandlerRegistrationTest.java` (new JUnit test for the `installAdditionalHandler` / `removeAdditionalHandler` contract).
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/logs/OTelLogAppenderTest.java` (new JUnit test covering severity floor, hard-context attach, JUL→OTel mapping, re-entrance guard, `Handler.reportError` isolation, SLF4J-bridge `@Disabled` regression).

Out of scope:
- SLF4J appender for hosts that bridge JUL through `jul-to-slf4j` (follow-up ticket if operational demand surfaces).
- Bootstrap-time logs before `OPENTELEMETRY_LOGS_ENABLED` is resolved (covered by the JVM-property workaround documented in `design.md §"OpenTelemetry logs integration"` Edge cases).
- Cross-thread context propagation for logs from background threads spawned inside a span scope (caller responsibility, same caveat as for child-span creation).
- Track 5's `OPENTELEMETRY_LOGS_ENABLED` and `OPENTELEMETRY_LOGS_MIN_SEVERITY` `GlobalConfiguration` entries (Track 5 owns them; Track 7 consumes them).
- Track 5's `SdkLoggerProvider` autoconfigure build path (Track 5 owns it; Track 7 reads the resolved `LoggerProvider` reference from the facade).

Inter-track dependencies:
- Depends on Track 3 (the OTel facade `YouTrackDBOpenTelemetry` exists with `setOpenTelemetry(...)` / `shutdown()` extension points).
- Depends on Track 5 (`OPENTELEMETRY_LOGS_*` config entries exist; the SDK autoconfigure builds `SdkLoggerProvider` alongside `SdkTracerProvider` when the master switch is on).
- Provides for nothing downstream that is not already owned by other tracks. Track 6 test infrastructure does not need to extend to cover Track 7; tests in the new `OTelLogAppenderTest` class cover the logs pillar end-to-end.
