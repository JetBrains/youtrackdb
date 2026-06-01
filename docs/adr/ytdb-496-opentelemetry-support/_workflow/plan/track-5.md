<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
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

3. Server entry: `YouTrackDBServer` today calls registered `ServerLifecycleListener` implementations during startup and shutdown — but only listeners explicitly added via `registerLifecycleListener(...)`. The existing code does not load implementations from `META-INF/services/`, so this track adds a `ServiceLoader.load(ServerLifecycleListener.class)` call inside `YouTrackDBServer.activate()` (CR3 resolution) appending every discovered impl to the existing `lifecycleListeners` list before the existing `for (var l : lifecycleListeners)` iteration runs. The new module ships an `OpenTelemetryServerPlugin implements ServerLifecycleListener` that:
   - In `onAfterActivate()`, reads `OPENTELEMETRY_*` config entries, builds an `OpenTelemetrySdk` via `AutoConfiguredOpenTelemetrySdk.builder().build()`, calls the package-private 2-arg `setOpenTelemetry(sdk, ownedByYtdb=true)` variant so shutdown closes the SDK, then calls `YourTracks.registerGlobalQueryListener(otelQueryListener)` and `YourTracks.registerGlobalTransactionListener(otelTxListener)` to enrol the listeners with the process-global registry (CR9: static methods on `YourTracks`).
   - In `onBeforeDeactivate()`, calls the matching `YourTracks.unregister...` methods, then `YouTrackDBOpenTelemetry.shutdown()`.

The plugin is registered with the server via a `META-INF/services/com.jetbrains.youtrackdb.internal.server.ServerLifecycleListener` entry in the OTel module — the `ServiceLoader.load(...)` call added to `YouTrackDBServer.activate()` is what picks it up.

Concrete deliverables:

1. Five `GlobalConfiguration` entries: `OPENTELEMETRY_ENABLED` (Boolean, default false), `OPENTELEMETRY_EXPORTER_ENDPOINT` (String), `OPENTELEMETRY_EXPORTER_PROTOCOL` (String, default `grpc`), `OPENTELEMETRY_SERVICE_NAME` (String, default `youtrackdb`), and `OPENTELEMETRY_QUERY_MODE_TAG_RULES` (String, default empty — consumed by Track 1's `QueryMonitoringModeResolver` at startup; format documented in design.md §"Query tagging and per-tag rule resolution"). All env-var capable.
2. The `YouTrackDBOpenTelemetry` facade extended with three-step lazy resolution chain (explicit setter → `GlobalOpenTelemetry.get()` → auto-configure) gated by `OPENTELEMETRY_ENABLED`. Internal `ownedByYtdb` flag tracks who created the active SDK. A package-private 2-arg `setOpenTelemetry(OpenTelemetry, boolean ownedByYtdb)` variant lets `OpenTelemetryServerPlugin` signal server-mode ownership; the public single-arg variant always sets `ownedByYtdb=false`.
3. `ServiceLoader.load(ServerLifecycleListener.class)` call inside `YouTrackDBServer.activate()` appending discovered listeners to the existing `lifecycleListeners` list before the iteration that calls `onAfterActivate` (CR3).
4. `OpenTelemetryServerPlugin` implementing `ServerLifecycleListener`, ServiceLoader-registered, register / unregister listeners through `YourTracks` static methods.
5. A `META-INF/services/com.jetbrains.youtrackdb.internal.server.ServerLifecycleListener` file in the OTel module listing the plugin.
6. Idempotence: a setter call after auto-configure already ran closes the YTDB-built SDK and switches to the host's instance with `ownedByYtdb=false`. `shutdown` is safe to call multiple times and only closes the SDK when `ownedByYtdb=true`.
7. An INFO log line on every ownership transition (auto-configure ran, setter override, shutdown) so operators have a breadcrumb trail.

## Plan of Work

Six edits.

The first edit adds the five `GlobalConfiguration` entries. They sit alphabetically with other config entries; the `isEnv=true` flag is set so `YOUTRACKDB_OPENTELEMETRY_ENABLED=true` works in containerized deployments. The fifth entry `OPENTELEMETRY_QUERY_MODE_TAG_RULES` is consumed by Track 1's `QueryMonitoringModeResolver` at startup; Track 5 only adds the config entry definition, not the parsing logic.

The second edit extends `YouTrackDBOpenTelemetry` with the lazy resolution chain. The `tracer()` accessor (called on first listener fire) runs a synchronized resolution: if `openTelemetry` field is already set, return its tracer; otherwise consult the global, then auto-configure from config. The auto-configure path uses `AutoConfiguredOpenTelemetrySdk.builder().addPropertiesSupplier(...)` to inject our config entries as OTel-conventional property names. Sets `ownedByYtdb=true`. The public single-arg `setOpenTelemetry(OpenTelemetry)` method closes the YTDB-built SDK (if any) before installing the new instance, flipping `ownedByYtdb=false`. A package-private 2-arg `setOpenTelemetry(OpenTelemetry, boolean ownedByYtdb)` variant lets `OpenTelemetryServerPlugin` install a YTDB-owned SDK without flipping ownership. Both variants register / re-register OTel listeners through `YourTracks.registerGlobalQueryListener(...)` / `registerGlobalTransactionListener(...)` (CR9) when `OPENTELEMETRY_ENABLED=true`.

The third edit adds `ServiceLoader.load(ServerLifecycleListener.class)` to `YouTrackDBServer.activate()`. The call sits before the existing `for (var listener : lifecycleListeners) listener.onBeforeActivate(...)` loop and appends every discovered impl to the existing `lifecycleListeners` list. This is the single core/server-side edit Track 5 makes outside the OTel module; without it the `META-INF/services` entry in step 5 would be inert (CR3).

The fourth edit implements `OpenTelemetryServerPlugin`. The class lives in `youtrackdb-opentelemetry/.../server/OpenTelemetryServerPlugin.java`. `onAfterActivate` reads config, builds `AutoConfiguredOpenTelemetrySdk` with the OTLP exporter wired to the configured endpoint and protocol, calls the package-private 2-arg `setOpenTelemetry(sdk, ownedByYtdb=true)` variant of the facade. `onBeforeDeactivate` calls `shutdown()` which closes the SDK and unregisters listeners via `YourTracks.unregister...`.

The fifth edit creates the ServiceLoader manifest file pointing to the plugin.

The sixth edit adds INFO log lines on ownership transitions (auto-configure resolved endpoint X; setter override replaced YTDB-owned SDK; shutdown closed YTDB-owned SDK / left host SDK untouched), so operators see exactly which path ran on each transition.

Ordering: edits 1 and 2 are sequential (2 reads config from 1). Edits 3, 4, 5 sequential (5 depends on 4's class). Edit 6 follows edit 2.

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
- `server/src/main/java/com/jetbrains/youtrackdb/internal/server/YouTrackDBServer.java` (add `ServiceLoader.load(ServerLifecycleListener.class)` inside `activate()` per CR3).
- `youtrackdb-opentelemetry/.../YouTrackDBOpenTelemetry.java` (extended with registration side-effects through `YourTracks` static methods plus the package-private 2-arg `setOpenTelemetry` variant).
- `youtrackdb-opentelemetry/.../server/OpenTelemetryServerPlugin.java` (new).
- `youtrackdb-opentelemetry/src/main/resources/META-INF/services/com.jetbrains.youtrackdb.internal.server.ServerLifecycleListener` (new).

Out of scope:
- The listener implementations themselves (Track 3).
- Test suite (Tracks 6a / 6b / 6c).

Inter-track dependencies:
- Depends on Track 1 (registry SPI consumed by `setOpenTelemetry`).
- Depends on Track 3 (`YouTrackDBOpenTelemetry` facade and listener classes).
- Provides for Track 6b: `ServerPluginTest` exercises the server-mode auto-enrolment path; `LifecycleTest` exercises the embedded-mode lifecycle.

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
