# Track 5: Configuration parameters and lifecycle integration

## Purpose / Big Picture

After this track lands, setting `youtrackdb.opentelemetry.enabled=true` (system property or env var) and providing an `OpenTelemetry` instance (host-set in embedded mode or YTDB-built in server mode) causes the OTel listeners to auto-register with the global registry, and shutdown cleanly closes the SDK.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Add `GlobalConfiguration` entries, wire embedded mode (host-provided `OpenTelemetry` via setter and `GlobalOpenTelemetry.get()` fallback), and add a `ServerLifecycleListener`-based plugin that initializes/closes the SDK in server mode based on the config flag. After this track the OTel module auto-enrols when enabled.

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

The track wires three lifecycle paths together:

1. `GlobalConfiguration` (in `core/.../api/config/GlobalConfiguration.java`) carries every YTDB config entry as an enum value. The pattern for adding entries (one of four constructor overloads, system-prop key, description, type, default, optional `isEnv=true` flag) is well-established. Reading happens via `getValueAsBoolean()` / `getValueAsString()` etc.

2. Embedded entry: the host application creates a `YouTrackDB` instance via `YourTracks.instance(...)`. There is no plugin SPI, so the host wires OTel by calling `YouTrackDBOpenTelemetry.setOpenTelemetry(otel)` directly before opening transactions. When `OPENTELEMETRY_ENABLED=true` and the host has not wired anything (neither setter call nor `GlobalOpenTelemetry.set(...)`), the facade auto-configures its own SDK from the `OPENTELEMETRY_*` config entries on first listener fire. The facade tracks an `ownedByYtdb` boolean so `shutdown()` closes only the SDK YTDB created.

3. Server entry: `YouTrackDBServer` calls registered `ServerLifecycleListener` implementations during startup and shutdown. The new module ships an `OpenTelemetryServerPlugin implements ServerLifecycleListener` that:
   - In `onAfterActivate()`, reads `OPENTELEMETRY_*` config entries, builds an `OpenTelemetrySdk` via `AutoConfiguredOpenTelemetrySdk.builder().build()`, calls the internal `setOpenTelemetry(sdk, ownedByYtdb=true)` variant so shutdown closes the SDK, registers the OTel listeners with the global registry.
   - In `onBeforeDeactivate()`, unregisters the listeners, calls `YouTrackDBOpenTelemetry.shutdown()`.

The plugin is registered with the server via a `META-INF/services/` entry for `ServerLifecycleListener` (the standard ServiceLoader pattern documented in CLAUDE.md SPI section).

Concrete deliverables:

1. Four `GlobalConfiguration` entries: `OPENTELEMETRY_ENABLED` (Boolean, default false), `OPENTELEMETRY_EXPORTER_ENDPOINT` (String), `OPENTELEMETRY_EXPORTER_PROTOCOL` (String, default `grpc`), `OPENTELEMETRY_SERVICE_NAME` (String, default `youtrackdb`). All env-var capable.
2. The `YouTrackDBOpenTelemetry` facade extended with three-step lazy resolution chain (explicit setter → `GlobalOpenTelemetry.get()` → auto-configure) gated by `OPENTELEMETRY_ENABLED`. Internal `ownedByYtdb` flag tracks who created the active SDK.
3. `OpenTelemetryServerPlugin` implementing `ServerLifecycleListener`, ServiceLoader-registered.
4. A `META-INF/services/com.jetbrains.youtrackdb.internal.server.ServerLifecycleListener` file in the OTel module listing the plugin.
5. Idempotence: a setter call after auto-configure already ran closes the YTDB-built SDK and switches to the host's instance with `ownedByYtdb=false`. `shutdown` is safe to call multiple times and only closes the SDK when `ownedByYtdb=true`.
6. An INFO log line on every ownership transition (auto-configure ran, setter override, shutdown) so operators have a breadcrumb trail.

## Plan of Work

Five edits.

The first edit adds the four `GlobalConfiguration` entries. They sit alphabetically with other config entries; the `isEnv=true` flag is set so `YOUTRACKDB_OPENTELEMETRY_ENABLED=true` works in containerized deployments.

The second edit extends `YouTrackDBOpenTelemetry` with the lazy resolution chain. The `tracer()` accessor (called on first listener fire) runs a synchronized resolution: if `openTelemetry` field is already set, return its tracer; otherwise consult the global, then auto-configure from config. The auto-configure path uses `AutoConfiguredOpenTelemetrySdk.builder().addPropertiesSupplier(...)` to inject our config entries as OTel-conventional property names. Sets `ownedByYtdb=true`. The `setOpenTelemetry(otel)` public method closes the YTDB-built SDK (if any) before installing the new instance, flipping `ownedByYtdb=false`. Both methods register / re-register OTel listeners with the global registry from Track 1 when `OPENTELEMETRY_ENABLED=true`.

The third edit implements `OpenTelemetryServerPlugin`. The class lives in `youtrackdb-opentelemetry/.../server/OpenTelemetryServerPlugin.java`. `onAfterActivate` reads config, builds `AutoConfiguredOpenTelemetrySdk` with the OTLP exporter wired to the configured endpoint and protocol, calls the internal `setOpenTelemetry(sdk, ownedByYtdb=true)` variant of the facade. `onBeforeDeactivate` calls `shutdown()` which closes the SDK and unregisters listeners.

The fourth edit creates the ServiceLoader manifest file pointing to the plugin.

The fifth edit adds INFO log lines on ownership transitions (auto-configure resolved endpoint X; setter override replaced YTDB-owned SDK; shutdown closed YTDB-owned SDK / left host SDK untouched), so operators see exactly which path ran on each transition.

Ordering: edits 1 and 2 are sequential (2 reads config from 1). Edits 3-4 are sequential and depend on edit 2's facade shape. Edit 5 follows edit 2.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 5:

- Setting `-Dyoutrackdb.opentelemetry.enabled=true` and calling `YouTrackDBOpenTelemetry.setOpenTelemetry(otel)` in embedded mode causes subsequent transactions to emit OTel spans against the host's SDK. Ownership = host; `shutdown()` does not close it.
- Setting the same flag with no setter call but with `GlobalOpenTelemetry.set(...)` invoked by the host: subsequent transactions emit against the host's global SDK. Ownership = host.
- Setting the same flag with neither setter nor GlobalOpenTelemetry: on first listener fire YTDB auto-configures an SDK from `OPENTELEMETRY_EXPORTER_ENDPOINT` / `_PROTOCOL` / `_SERVICE_NAME`. Spans flow to that endpoint. Ownership = YTDB; `shutdown()` closes it. One INFO log line records the auto-configure path with the resolved endpoint.
- Calling `setOpenTelemetry(otel)` after auto-configure already ran: facade closes the YTDB-built SDK, installs the host's instance, flips ownership to host. One INFO log line records the transition.
- Starting `YouTrackDBServer` with `OPENTELEMETRY_ENABLED=true` causes the plugin to build an SDK using the configured endpoint and to start emitting spans. Ownership = YTDB; `shutdown()` closes it.
- Shutting down the server (or calling `YouTrackDBOpenTelemetry.shutdown()` directly) closes only the YTDB-owned SDK, flushes pending spans, and unregisters listeners. Subsequent transactions emit no spans. Calling shutdown when ownership = host leaves the host's SDK alone but still unregisters listeners.
- With `OPENTELEMETRY_ENABLED=false` (default), no OTel code paths run, no auto-configure happens, and the listeners never register, regardless of `setOpenTelemetry` calls.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

## Interfaces and Dependencies

In scope:
- `core/src/main/java/com/jetbrains/youtrackdb/api/config/GlobalConfiguration.java` (four new enum entries).
- `youtrackdb-opentelemetry/.../YouTrackDBOpenTelemetry.java` (extended with registration side-effects).
- `youtrackdb-opentelemetry/.../server/OpenTelemetryServerPlugin.java` (new).
- `youtrackdb-opentelemetry/src/main/resources/META-INF/services/com.jetbrains.youtrackdb.internal.server.ServerLifecycleListener` (new).

Out of scope:
- The listener implementations themselves (Track 3).
- Test suite (Track 6).

Inter-track dependencies:
- Depends on Track 1 (registry SPI consumed by `setOpenTelemetry`).
- Depends on Track 3 (`YouTrackDBOpenTelemetry` facade and listener classes).
- Provides for Track 6: the auto-enrolment paths the test suite exercises.

Configuration entries introduced:

```java
OPENTELEMETRY_ENABLED("youtrackdb.opentelemetry.enabled",
    "Enable OpenTelemetry span emission for queries and transactions",
    Boolean.class, false, false, false, true /* isEnv */),

OPENTELEMETRY_EXPORTER_ENDPOINT("youtrackdb.opentelemetry.exporter.endpoint",
    "OTLP exporter endpoint (gRPC or HTTP, depending on protocol)",
    String.class, "http://localhost:4317", false, false, true),

OPENTELEMETRY_EXPORTER_PROTOCOL("youtrackdb.opentelemetry.exporter.protocol",
    "OTLP exporter protocol: grpc or http/protobuf",
    String.class, "grpc", false, false, true),

OPENTELEMETRY_SERVICE_NAME("youtrackdb.opentelemetry.service.name",
    "OTel service.name resource attribute used in server mode",
    String.class, "youtrackdb", false, false, true),
```
