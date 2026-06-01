<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# Track 6b: Test suite — lifecycle and invariants

## Purpose / Big Picture

After this track lands, the OTel module's lifecycle (init/shutdown) is verified end-to-end in both embedded and server modes, and the exception-isolation and timing-mode-uniformity invariants from the implementation plan are exercised by assertions against real SDK behavior. Builds on the `OTelTestBase` infrastructure Track 6a established.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Cover server-mode lifecycle (init/close), the exception-isolation invariant, and the timing-mode uniformity invariant. Gremlin span uniqueness and `db.query()` regression coverage land in Track 6c.

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

Tests reuse `OTelTestBase` from Track 6a (same SDK setup/teardown). `ServerPluginTest` boots a real `YouTrackDBServer` via `ServerMain.create()` with a temp config directory and asserts span emission against the auto-configured SDK. `OTelTimingModeTest` injects a `Ticker` test double via `YouTrackDBEnginesManager` to make LIGHTWEIGHT-mode granularity observable.

Concrete deliverables:

1. `LifecycleTest`: `setOpenTelemetry` registers listeners; `shutdown` unregisters and flushes; idempotent shutdown.
2. `ServerPluginTest`: boots `YouTrackDBServer` with `OPENTELEMETRY_ENABLED=true`, runs a query, asserts span emission. Closes server, asserts SDK shutdown.
3. `ExceptionIsolationTest`: installs a listener that throws on every callback; runs queries and commits; asserts the transaction completes normally and the exporter received no spans (or only those from other registered listeners).
4. `OTelTimingModeTest`: asserts the per-fire-site Timing-mode uniformity invariant. Three scenarios: (a) TX with default `LIGHTWEIGHT` mode runs one Gremlin traversal and one SQL query, neither tagged; assert that the durations on both spans come from `GranularTicker` (test by setting a `Ticker` test double with a known fixed tick value via `YouTrackDBEnginesManager` and asserting durations are integer multiples of the tick). (b) Same TX with `withQueryMonitoringMode(EXACT)` runs the same untagged pair; assert both span durations are NOT clamped to the tick granularity (per-TX default applies). (c) TX with default `LIGHTWEIGHT` plus tag rule `OPENTELEMETRY_QUERY_MODE_TAG_RULES=findHotpath=EXACT` runs a tagged Gremlin traversal (`g.with(YTDBQueryConfigParam.querySummary, "findHotpath").V()...`) and an untagged SQL query in the same TX; assert the Gremlin span has sub-tick precision (resolver matched EXACT) while the SQL span is tick-clamped (SQL passes `Optional.empty()` and resolver falls back to TX default LIGHTWEIGHT — per-tag rules apply only to Gremlin). Covers Track 1's `getDefaultQueryMonitoringMode()` and `resolveQueryMonitoringMode(Optional<String>)` accessors plus the `QueryMonitoringModeResolver` rule walk, and Track 4's mode-routing in the `InstrumentedSqlResultSet` wrapper.

## Plan of Work

Four edits, one per deliverable.

The first edit adds `LifecycleTest`. Tests `setOpenTelemetry` side effects on the registry, replace-on-reset semantics, double-shutdown safety.

The second edit adds `ServerPluginTest`. Boots `YouTrackDBServer` via `ServerMain.create()` with a temp config directory and `OPENTELEMETRY_ENABLED=true`, runs a Gremlin query and an SQL query against the server, asserts span emission for both. Then shuts down via `server.shutdown()`. The test needs to wire the in-memory exporter into the auto-configured SDK; the cleanest way is via OTel autoconfigure SPI (`AutoConfigurationCustomizerProvider`) or by setting `OTEL_TRACES_EXPORTER=none` and re-registering an in-memory exporter manually.

The third edit adds `ExceptionIsolationTest`. Registers a deliberately-throwing listener alongside the OTel listeners; asserts the transaction completes; asserts the OTel exporter still received the expected spans (because the OTel listeners are isolated from the bad one's exceptions). Exercises both Gremlin and SQL paths.

The fourth edit adds `OTelTimingModeTest`. Asserts the per-fire-site Timing-mode uniformity invariant by injecting a `Ticker` test double via `YouTrackDBEnginesManager` with a known fixed tick value (e.g., 10 ms granularity). Scenario A runs one Gremlin traversal + one SQL query inside a default TX (LIGHTWEIGHT mode), neither tagged; asserts both span durations are integer multiples of the tick value. Scenario B repeats with `g.tx().withQueryMonitoringMode(EXACT)` and no tags; asserts durations are NOT clamped to tick granularity (some sub-tick value appears). Scenario C runs a TX with default LIGHTWEIGHT plus configured rule `findHotpath=EXACT`, executes one tagged Gremlin traversal (`g.with(YTDBQueryConfigParam.querySummary, "findHotpath").V()...`) and one untagged SQL query in the same TX; asserts the Gremlin span has sub-tick precision while the SQL span is tick-clamped (SQL passes `Optional.empty()` and per-tag rules apply only to Gremlin). Covers Track 1's `getDefaultQueryMonitoringMode()` / `resolveQueryMonitoringMode(Optional<String>)` accessors plus the `QueryMonitoringModeResolver`, and Track 4's mode-routing in the `InstrumentedSqlResultSet` wrapper.

Ordering: edits are independent; recommended order is Lifecycle → ServerPlugin → ExceptionIsolation → TimingMode.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 6b:

- `./mvnw -pl youtrackdb-opentelemetry clean test -Dtest='LifecycleTest,ServerPluginTest,ExceptionIsolationTest,OTelTimingModeTest'` passes with all four classes green.
- `ServerPluginTest` runs against a real `YouTrackDBServer` instance (not mocked).
- `OTelTimingModeTest` deterministically reproduces both LIGHTWEIGHT clamping and EXACT precision via the injected `Ticker` test double.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

## Interfaces and Dependencies

In scope:
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/LifecycleTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/server/ServerPluginTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/ExceptionIsolationTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/OTelTimingModeTest.java`

Out of scope:
- Any production code (Tracks 1-5 own that).
- Attribute mapping, hierarchy, propagation (Track 6a).
- Gremlin span uniqueness and `db.query` regression (Track 6c).

Inter-track dependencies:
- Depends on Track 6a: reuses `OTelTestBase`.
- Provides for Track 6c: confirms the test infrastructure is stable before invariant-focused regression coverage runs.

Test scenarios that map directly to invariants in the implementation plan:

```text
Invariant "TX span boundedness" → ExceptionIsolationTest covers exception paths;
  OTelTransactionMetricsListenerTest (Track 6a) covers the four exit paths.
Invariant "Listener exception isolation" → ExceptionIsolationTest.
Invariant "Timing-mode uniformity (per-query)" → OTelTimingModeTest asserts
  both fire sites for one query resolve from the same tag to the same mode;
  different queries in the same TX can use different modes when their tags
  resolve to different rules. QueryModeResolutionTest in Track 6a covers
  the resolver mechanism in isolation.
```
