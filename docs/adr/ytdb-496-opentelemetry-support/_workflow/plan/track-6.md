# Track 6: Test suite using `opentelemetry-sdk-testing`

## Purpose / Big Picture

After this track lands, the OTel module ships a JUnit 5 test suite that drives a real `OpenTelemetrySdk` backed by `InMemorySpanExporter`, asserts on sem-conv attribute mapping, span hierarchy, span kinds, context propagation, lifecycle correctness, and exception isolation. Coverage gate satisfied.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Cover the listener-to-span mapping (sem-conv attributes, span kinds, hierarchy parent/child links), context propagation in embedded (host span becomes parent), server-mode lifecycle (init/close), and the exception-isolation invariant. Uses `InMemorySpanExporter` so assertions run against real SDK behavior.

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

`io.opentelemetry:opentelemetry-sdk-testing` ships `InMemorySpanExporter` (collects all spans into a List the test can assert on) and helpers for building an `OpenTelemetrySdk` with a chosen exporter. The pattern: build the SDK with `SdkTracerProviderBuilder.addSpanProcessor(SimpleSpanProcessor.create(exporter))`, call `YouTrackDBOpenTelemetry.setOpenTelemetry(sdk)`, run the YTDB scenario, read `exporter.getFinishedSpanItems()`, assert.

The tests live in `youtrackdb-opentelemetry/src/test/java/`. JUnit 5 is the test framework (the new module is greenfield; project convention is JUnit 4 in `core`/`server` and JUnit 5 in `tests` and any new module).

Test infrastructure pieces:
- Base test class `OTelTestBase` providing setUp/tearDown that creates a fresh in-memory `OpenTelemetrySdk`, registers it with the facade, and unregisters in tearDown.
- An embedded YTDB instance per test class (or per test if isolation cost is acceptable). The standing pattern in existing tests is `YourTracks.instance(<dir>)` followed by `ytdb.create(<name>, DatabaseType.MEMORY, <credential>)` and `ytdb.open(<name>, "admin", <password>)`; see `core/src/test/java/.../storage/memory/MemoryStorageDropTest.java` for the canonical setup.

Concrete deliverables:

1. `OTelGremlinQueryTest`: Gremlin path attribute mapping per sem-conv; covers all four attribute rows from the requirement matrix (Required, Conditionally Required, Recommended, Opt-In) for Gremlin traversals.
2. `OTelSqlQueryTest`: SQL path attribute mapping; one test method per statement type (SELECT, INSERT, UPDATE, DELETE, MATCH, CREATE INDEX as DDL). Asserts sanitized `db.query.text`, correct `db.operation.name`, correct `db.collection.name`.
3. `OTelTransactionMetricsListenerTest`: span hierarchy (TX as parent of Query and Commit); read-only TX (no commit child); failed commit (ERROR status, `error.type`); rollback (OK status, no commit child). Uses both Gremlin and SQL queries inside transactions to cover hierarchy for both sources.
4. `ContextPropagationTest`: host code wraps a YTDB query in its own span; assert the YTDB query span's parent ID matches the host span's span ID. One assertion per query source (Gremlin + SQL).
5. `LifecycleTest`: `setOpenTelemetry` registers listeners; `shutdown` unregisters and flushes; idempotent shutdown.
6. `ServerPluginTest`: boots `YouTrackDBServer` with `OPENTELEMETRY_ENABLED=true`, runs a query, asserts span emission. Closes server, asserts SDK shutdown.
7. `ExceptionIsolationTest`: installs a listener that throws on every callback; runs queries and commits; asserts the transaction completes normally and the exporter received no spans (or only those from other registered listeners).
8. `OTelTimingModeTest`: asserts the timing-mode uniformity invariant. Two scenarios: (a) TX with default `LIGHTWEIGHT` mode runs one Gremlin query and one SQL query; assert that the durations on both spans come from `GranularTicker` (test by setting a `Ticker` test double with a known fixed tick value via `YouTrackDBEnginesManager` and asserting durations are integer multiples of the tick). (b) TX with `withQueryMonitoringMode(EXACT)` runs the same; assert durations are NOT clamped to the tick granularity. Covers Track 1's `getQueryMonitoringMode()` accessor wiring and Track 4's mode-routing inside `executeInternal()`.

## Plan of Work

Eight edits, one per test class. Each class is independently runnable.

The first edit creates `OTelTestBase` plus `OTelGremlinQueryTest`. Tests follow the pattern: build SDK with InMemorySpanExporter, register, run Gremlin query, find emitted span, assert on attributes. The class covers the full attribute matrix from sem-conv §"Span definition" plus the span-name fallback chain (querySummary → operation+collection → collection → system).

The second edit adds `OTelSqlQueryTest`. Mirrors the Gremlin tests for the SQL path: one method per statement type, asserting that `db.command(...)` and `db.query(...)` emit a span with sanitized text, correct operation name, and correct collection name. Includes MATCH (graph pattern) and DDL (CREATE INDEX) coverage so the test fails loudly if Track 4's classifier misses a statement type.

The third edit adds `OTelTransactionMetricsListenerTest`. Four scenarios per test method: successful write TX (TX + N queries + commit, parent chain correct); read-only TX (TX + queries, no commit); failed commit (TX span ERROR status); user rollback (TX span OK status, no commit child). Each scenario runs once with Gremlin queries, once with SQL queries.

The fourth edit adds `ContextPropagationTest`. The host wraps the YTDB transaction in `tracer.spanBuilder("host-op").startSpan()` and `try-with-resources` on `span.makeCurrent()`. Two assertions: the emitted YTDB Gremlin span has `parentSpanId` equal to the host span's `spanId`; same for an SQL span.

The fifth edit adds `LifecycleTest`. Tests `setOpenTelemetry` side effects on the registry, replace-on-reset semantics, double-shutdown safety.

The sixth edit adds `ServerPluginTest`. Boots `YouTrackDBServer` via `ServerMain.create()` with a temp config directory and `OPENTELEMETRY_ENABLED=true`, runs a Gremlin query and an SQL query against the server, asserts span emission for both. Then shuts down via `server.shutdown()`. The test needs to wire the in-memory exporter into the auto-configured SDK; the cleanest way is via OTel autoconfigure SPI (`AutoConfigurationCustomizerProvider`) or by setting `OTEL_TRACES_EXPORTER=none` and re-registering an in-memory exporter manually.

The seventh edit adds `ExceptionIsolationTest`. Registers a deliberately-throwing listener alongside the OTel listeners; asserts the transaction completes; asserts the OTel exporter still received the expected spans (because the OTel listeners are isolated from the bad one's exceptions). Exercises both Gremlin and SQL paths.

The eighth edit adds `OTelTimingModeTest`. Asserts the Timing-mode uniformity invariant by injecting a `Ticker` test double via `YouTrackDBEnginesManager` with a known fixed tick value (e.g., 10 ms granularity). Scenario A runs one Gremlin query + one SQL query inside a default TX (LIGHTWEIGHT mode); asserts both span durations are integer multiples of the tick value. Scenario B repeats with `g.tx().withQueryMonitoringMode(EXACT)`; asserts durations are NOT clamped to tick granularity (some sub-tick value appears). Covers Track 1's `getQueryMonitoringMode()` accessor and Track 4's mode-routing inside `executeInternal()`.

Ordering: edit 1 must come first (everyone depends on `OTelTestBase`). Edits 2-8 are independent.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 6:

- `./mvnw -pl youtrackdb-opentelemetry clean test` passes with all seven test classes green.
- `./mvnw -pl youtrackdb-opentelemetry clean verify -P coverage` reports ≥85% line and ≥70% branch coverage on the OTel module's production code (per CLAUDE.md gate).
- `python3 .github/scripts/coverage-gate.py --line-threshold 85 --branch-threshold 70 --compare-branch origin/develop --coverage-dir .coverage/reports` exits 0.
- Each test method has a descriptive name explaining the scenario it covers and the expected outcome (per CLAUDE.md test guideline).
- No flaky tests: every test runs deterministically with `SimpleSpanProcessor` (synchronous export) and no real network I/O.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

## Interfaces and Dependencies

In scope:
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/OTelTestBase.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/listener/OTelGremlinQueryTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/listener/OTelSqlQueryTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/listener/OTelTransactionMetricsListenerTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/ContextPropagationTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/LifecycleTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/server/ServerPluginTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/ExceptionIsolationTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/OTelTimingModeTest.java`

Out of scope:
- Any production code (Tracks 1-5 own that).
- Performance benchmarks for OTel overhead (would be a follow-up if needed; the existing `QueryMetricsOverheadBenchmark` in `core` already measures baseline listener overhead and does not need changes).
- LDBC tests with OTel enabled (separate ticket if span volume becomes a benchmark concern).

Inter-track dependencies:
- Depends on Track 3 (listener implementations).
- Depends on Track 4 (SQL hook + sanitizer + SQL classifier).
- Depends on Track 5 (config + lifecycle wiring).
- Provides for nothing downstream.

Test scenarios that map directly to invariants in the implementation plan:

```text
Invariant "TX span boundedness" → OTelTransactionMetricsListenerTest covers all
  four exit paths (commit success, commit failure, user rollback, read-only close).
Invariant "Listener exception isolation" → ExceptionIsolationTest.
Invariant "Span kind by role" → OTelGremlinQueryTest, OTelSqlQueryTest, and
  OTelTransactionMetricsListenerTest all assert on SpanData.getKind().
Invariant "db.system.name = youtrackdb" → OTelGremlinQueryTest and
  OTelSqlQueryTest attribute assertions.
Invariant "Timing-mode uniformity" → OTelTimingModeTest asserts both
  Gremlin and SQL fire sites in the same TX honor the snapshotted mode.
Invariant "One-way dependency" → enforced by Maven enforcer rule in Track 2,
  no Track 6 test needed.
```
