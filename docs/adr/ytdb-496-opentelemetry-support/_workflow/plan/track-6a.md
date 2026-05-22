# Track 6a: Test suite — attribute mapping, hierarchy, propagation

## Purpose / Big Picture

After this track lands, the OTel module ships the foundational JUnit 5 test suite that drives a real `OpenTelemetrySdk` backed by `InMemorySpanExporter`. The suite asserts sem-conv attribute mapping for both Gremlin and SQL paths, full span hierarchy (TX as parent of query and commit), and OTel `Context` propagation from a host span into YTDB query spans.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Cover the listener-to-span mapping (sem-conv attributes, span kinds, hierarchy parent/child links) and context propagation in embedded (host span becomes parent). Uses `InMemorySpanExporter` so assertions run against real SDK behavior. Lifecycle, exception isolation, timing-mode uniformity, Gremlin span uniqueness, and `db.query()` regression coverage land in Tracks 6b and 6c.

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
3. `OTelTransactionMetricsListenerTest`: span hierarchy (TX as parent of Query and Commit); read-only TX (no commit child); failed commit (ERROR status, `error.type`); rollback (OK status, no commit child). Uses both Gremlin and SQL queries inside transactions to cover hierarchy for both sources.
4. `ContextPropagationTest`: host code wraps a YTDB query in its own span; assert the YTDB query span's parent ID matches the host span's span ID. One assertion per query source (Gremlin + SQL).

## Plan of Work

Four edits, one per deliverable.

The first edit creates `OTelTestBase` plus `OTelGremlinQueryTest`. Tests follow the pattern: build SDK with InMemorySpanExporter, register, run Gremlin query, find emitted span, assert on attributes. The class covers the full attribute matrix from sem-conv §"Span definition" plus the span-name fallback chain (querySummary → operation+collection → collection → system).

The second edit adds `OTelSqlQueryTest`. Mirrors the Gremlin tests for the SQL path: one method per statement type, asserting that `db.command(...)` and `db.query(...)` emit a span with sanitized text, correct operation name, and correct collection name. Includes MATCH (graph pattern) and DDL (CREATE INDEX) coverage so the test fails loudly if Track 4's classifier misses a statement type.

The third edit adds `OTelTransactionMetricsListenerTest`. Four scenarios per test method: successful write TX (TX + N queries + commit, parent chain correct); read-only TX (TX + queries, no commit); failed commit (TX span ERROR status); user rollback (TX span OK status, no commit child). Each scenario runs once with Gremlin queries, once with SQL queries.

The fourth edit adds `ContextPropagationTest`. The host wraps the YTDB transaction in `tracer.spanBuilder("host-op").startSpan()` and `try-with-resources` on `span.makeCurrent()`. Two assertions: the emitted YTDB Gremlin span has `parentSpanId` equal to the host span's `spanId`; same for an SQL span.

Ordering: edit 1 must come first (everyone depends on `OTelTestBase`). Edits 2-4 are independent.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 6a:

- `./mvnw -pl youtrackdb-opentelemetry clean test -Dtest='OTelGremlinQueryTest,OTelSqlQueryTest,OTelTransactionMetricsListenerTest,ContextPropagationTest'` passes with all four classes green.
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
