<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# Track 6a: Test suite — attribute mapping, hierarchy, propagation, tag resolution

## Purpose / Big Picture

After this track lands, the OTel module ships the foundational JUnit 5 test suite that drives a real `OpenTelemetrySdk` backed by `InMemorySpanExporter`. The suite asserts sem-conv attribute mapping for both Gremlin and SQL paths, standalone commit-span shape for write transactions (no TX-lifetime parent span per D3 reversed), OTel `Context` propagation from a host span into YTDB query spans, and per-query mode resolution from the query tag (rule walk, fallback chain, cache behavior; SQL has no tag source per D15 so SQL queries pass `Optional.empty()` and resolve via the per-TX default).

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Cover the listener-to-span mapping (sem-conv attributes, span kinds, parent/child links between query spans and standalone commit spans) and context propagation in embedded (host span becomes parent). Uses `InMemorySpanExporter` so assertions run against real SDK behavior. Lifecycle, exception isolation, timing-mode uniformity, Path A vs Path B span shape, Gremlin DSL passthrough attribute population, and `db.query()` regression coverage land in Tracks 6b and 6c.

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
- Base test class `OTelTestBase` providing setUp/tearDown that creates a fresh in-memory `OpenTelemetrySdk`, registers it with the facade, and unregisters in tearDown. Track 6a owns this base; Tracks 6b and 6c extend it.
- An embedded YTDB instance per test class (or per test if isolation cost is acceptable). The standing pattern in existing tests is `YourTracks.instance(<dir>)` followed by `ytdb.create(<name>, DatabaseType.MEMORY, <credential>)` and `ytdb.open(<name>, "admin", <password>)`; see `core/src/test/java/.../storage/memory/MemoryStorageDropTest.java` for the canonical setup.

Concrete deliverables:

1. `OTelTestBase` + `OTelGremlinQueryTest`: Gremlin path attribute mapping per sem-conv; covers all four attribute rows from the requirement matrix (Required, Conditionally Required, Recommended, Opt-In) for Gremlin traversals. `OTelTestBase` provides the SDK setup/teardown reused across this track and Tracks 6b/6c.
2. `OTelSqlQueryTest`: SQL path attribute mapping; one test method per statement type (SELECT, INSERT, UPDATE, DELETE, MATCH, CREATE INDEX as DDL). Asserts sanitized `db.query.text`, correct `db.operation.name`, correct `db.collection.name`.
3. `OTelTransactionMetricsListenerTest`: standalone commit-span shape per D3 reversed (no TX-lifetime parent span). Scenarios: successful write TX emits N query spans + 1 standalone commit span with kind = `clientKind`; read-only TX emits N query spans only, no commit-side span (read-only TXs emit nothing on the TX listener per existing YTDB semantics); failed commit emits a standalone commit span with status ERROR and `error.type` populated from the cause class FQN; user rollback emits no commit-side span (`writeTransactionFailed` fires only on transaction-failure paths, not on host-initiated rollback). Each scenario runs once with Gremlin queries, once with SQL queries.
4. `ContextPropagationTest`: host code wraps a YTDB query in its own span; assert the YTDB query span's parent ID matches the host span's span ID. One assertion per query source (Gremlin + SQL).
5. `QueryModeResolutionTest`: per-query mode resolution from Gremlin tag (D15). Scenarios: (a) exact-match rule `findActiveUsers=EXACT` matches a tagged Gremlin traversal `g.with(querySummary, "findActiveUsers")`; (b) prefix-match rule `prefix:expensive-=EXACT` matches `expensive-batch-job` tag; (c) regex-match rule `regex:^batch-.*$=EXACT` matches `batch-import` tag; (d) first-wins ordering when multiple rules could match — first declared wins; (e) fallback chain: untagged query falls back to per-TX default; per-TX default fallback to LIGHTWEIGHT when not set; (f) cache hit behavior — second resolution of same tag returns same mode without re-walking rules (assert via instrumented test resolver); (g) SQL empty-tag short-circuit — any SQL statement (`db.query`, `db.command`, MATCH SQL, DDL) passes `Optional.empty()` to the resolver and resolves to per-TX default (or LIGHTWEIGHT when not set), independent of any per-tag rule; assert tag rules have Gremlin-only effective scope; (h) invalid rule config — malformed rule entry → WARN at startup + skip + resolver continues with valid rules. Uses Track 1's `QueryMonitoringModeResolver` directly for some scenarios (unit-level) and an embedded YTDB session for end-to-end scenarios.
6. `OTelGremlinStrategyOrderingTest`: regression test pinning the TinkerPop strategy-category invariant the design relies on (D20). Asserts: (a) `YTDBQueryMetricsStrategy.class` is declared as `TraversalStrategy.FinalizationStrategy` (reflection on the class hierarchy); (b) `GremlinToMatchStrategy.class` is declared as `TraversalStrategy.ProviderOptimizationStrategy`; (c) for a recognized traversal (e.g., `g.V().hasLabel("Person").has("age", 30)`), `traversal.applyStrategies()` ends with a step list whose terminal step is `YTDBQueryMetricsStep` and whose head is `YTDBMatchPlanStep`; (d) for a declined traversal (e.g., one containing an unrecognized step), `applyStrategies()` ends with `YTDBQueryMetricsStep` as the terminal of the original step chain (no `YTDBMatchPlanStep`). Regression value: refactoring either strategy's declared category, or the metrics-step injection position, breaks this test before reaching production traces.
7. `OTelHeartbeatSamplerTest`: heartbeat gate behaviour (D18). Scenarios: (a) disabled-by-default no-emit (interval `0`, no spans from heartbeat path); (b) enabled-emit-at-interval (interval `100` ms, one span per window); (c) CAS-race exactly-one-per-window under concurrent queries (multi-threaded fixture firing N queries in the same window, assert exactly one emission); (d) error-bypass without clock advancement (error queries emit without claiming the heartbeat slot, so the next successful query still gets the heartbeat sample); (e) composition with slow-query gate when both would pass — emit exactly once via the heartbeat slot, slow-query gate skipped.

## Plan of Work

Five edits, one per deliverable.

The first edit creates `OTelTestBase` plus `OTelGremlinQueryTest`. Tests follow the pattern: build SDK with InMemorySpanExporter, register, run Gremlin query, find emitted span, assert on attributes. The class covers the full attribute matrix from sem-conv §"Span definition" plus the span-name fallback chain (querySummary → operation+collection → collection → system).

The second edit adds `OTelSqlQueryTest`. Mirrors the Gremlin tests for the SQL path: one method per statement type, asserting that `db.command(...)` and `db.query(...)` emit a span with sanitized text, correct operation name, and correct collection name. Includes MATCH (graph pattern) and DDL (CREATE INDEX) coverage so the test fails loudly if Track 4's classifier misses a statement type.

The third edit adds `OTelTransactionMetricsListenerTest`. Four scenarios per test method: successful write TX (N query spans + 1 standalone commit span with kind=`clientKind`, no YTDB-side TX-lifetime parent span per D3 reversed); read-only TX (N query spans only, no commit-side span — read-only TXs emit nothing on the TX listener per existing YTDB semantics preserved); failed commit (standalone commit span with status ERROR and `error.type` populated from the cause class FQN); user rollback (no commit-side span emitted — `writeTransactionFailed` fires only on transaction-failure paths, not on host-initiated rollback). Each scenario runs once with Gremlin queries, once with SQL queries.

The fourth edit adds `ContextPropagationTest`. The host wraps the YTDB transaction in `tracer.spanBuilder("host-op").startSpan()` and `try-with-resources` on `span.makeCurrent()`. Two assertions: the emitted YTDB Gremlin span has `parentSpanId` equal to the host span's `spanId`; same for an SQL span.

The fifth edit adds `QueryModeResolutionTest`. Mixes unit-level scenarios (instantiate `QueryMonitoringModeResolver` directly with a known rule list, call `resolve(tag, txDefault)` and assert returned mode) and end-to-end scenarios (configure `OPENTELEMETRY_QUERY_MODE_TAG_RULES` via `GlobalConfiguration`, run a tagged + untagged pair of queries through an embedded YTDB session, assert span durations / clock-source markers reflect resolver decisions). Covers exact / prefix / regex matchers, first-wins ordering, fallback chain, cache behavior, SQL empty-tag short-circuit to per-TX default (D15 — SQL has no tag source; per-tag rules have Gremlin-only effective scope), malformed-rule-config WARN+skip behavior.

Ordering: edit 1 must come first (everyone depends on `OTelTestBase`). Edits 2-7 are independent.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 6a:

- `./mvnw -pl youtrackdb-opentelemetry clean test -Dtest='OTelGremlinQueryTest,OTelSqlQueryTest,OTelTransactionMetricsListenerTest,ContextPropagationTest,QueryModeResolutionTest,OTelGremlinStrategyOrderingTest,OTelHeartbeatSamplerTest'` passes with all seven classes green.
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
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/listener/QueryModeResolutionTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/listener/OTelGremlinStrategyOrderingTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/listener/OTelHeartbeatSamplerTest.java`

Out of scope:
- Any production code (Tracks 1-5 own that).
- Lifecycle, exception-isolation, timing-mode-uniformity, Gremlin-suppression, and `db.query` regression tests (Tracks 6b and 6c own those).
- Performance benchmarks for OTel overhead (would be a follow-up if needed).

Inter-track dependencies:
- Depends on Track 3 (listener implementations).
- Depends on Track 4 (SQL hook + sanitizer + SQL classifier).
- Depends on Track 5 (config + lifecycle wiring).
- Provides for Tracks 6b and 6c: `OTelTestBase` and the canonical setUp/tearDown pattern.

Test scenarios that map directly to invariants in the implementation plan:

```text
Invariant "Span kind by role" → OTelGremlinQueryTest, OTelSqlQueryTest, and
  OTelTransactionMetricsListenerTest all assert on SpanData.getKind().
Invariant "db.system.name = youtrackdb" → OTelGremlinQueryTest and
  OTelSqlQueryTest attribute assertions.
```
