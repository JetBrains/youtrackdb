<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# Track 6c: Test suite — Gremlin suppression, db.query regression, coverage gate

## Purpose / Big Picture

After this track lands, the Path A vs Path B span-shape invariant is verified by `OTelPathSpanShapeTest`, the pre-Mutation-12 coverage gap on the `db.query(...)` path is closed by `OTelDbQuerySpanTest`, and the OTel module's coverage gate (85% line / 70% branch on changed code) is satisfied. Final track of the OTel test suite.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Cover the Path A vs Path B span-shape invariant (Path A = one Gremlin span, no children; Path B = one Gremlin parent + one SQL child via `Context.current()` propagation; direct SQL = one span) and the `db.query()` SQL span coverage (regression test for the pre-mutation gap). Verifies coverage gate.

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

Tests reuse `OTelTestBase` from Track 6a. `OTelPathSpanShapeTest` asserts the Path A vs Path B span-shape invariant by counting spans emitted from various traversal shapes (Path A = one Gremlin span, no children; Path B = one Gremlin parent + one SQL child via `Context.current()` propagation; direct SQL = one span). `OTelDbQuerySpanTest` closes the regression gap that prompted Mutation 12 (the SQL helper used to wire only `executeInternal()`, missing the `query()` call site that backs `db.query(...)`).

Concrete deliverables:

1. `OTelPathSpanShapeTest`: asserts the Path A vs Path B span-shape invariant. Four scenarios: (a) Path A translated Gremlin traversal `g.V().hasLabel("User").has("active", true).toList()` (assuming the recognised step set per PR #1038) emits exactly one Gremlin span with no children; assert exporter contains exactly one span, no SQL child, and the outer Gremlin span carries `db.youtrackdb.gremlin.path = "A"`. (b) Path B fallback traversal (a Gremlin traversal whose step set falls outside PR #1038's recognised set, forcing the half-measure strategies) emits one Gremlin parent + one SQL child; assert the parent carries `db.youtrackdb.gremlin.path = "B"`, the child span's `parentSpanId` matches the Gremlin span's `spanId`, and the child carries the sanitized SQL translated from Gremlin. (c) Direct `db.query("SELECT FROM User")` emits exactly one SQL span parented to whatever `Context.current()` is at the call site (host span if a wrapper exists, else trace root). (d) Gremlin call that throws mid-traversal still emits the Gremlin parent span with `status=ERROR` and the SQL child span (if reached before the throw) also with error status; assert no Context leakage to a subsequent native `db.query("SELECT FROM User")` on the same thread (the OTel module's Gremlin span lifecycle hook closes its scope via try-with-resources). Covers Track 3's OTel Gremlin span lifecycle hook on `YTDBQueryMetricsStep` and Track 4's `InstrumentedSqlResultSet` Context propagation behavior (capture at construction + `Scope.makeCurrent()` around listener fire).
1a. `OTelResultSetThreadAffinityTest` (new in M55 against the design §"SQL execution layer hook" Cross-thread `close()` edge case): asserts the wrapper's thread-affinity invariant AND the `db.youtrackdb.span.thread_mismatch` attribute contract. Two scenarios. (i) Same-thread close: open `db.query(...)`, iterate to completion, close on the same thread; assert the wrapper's construction-thread assert passes (with `-ea` enabled on the test JVM — the project's default), assert the emitted SQL span's parent matches the construction-time `Context.current()`, AND assert `db.youtrackdb.span.thread_mismatch` is absent from the span attributes. (ii) Cross-thread close: open `db.query(...)` on thread A, hand the result-set to an `ExecutorService`, close on thread B; with `-ea` enabled assert `AssertionError` fires from `InstrumentedSqlResultSet.close()` carrying the documented message "InstrumentedSqlResultSet.close() called on a different thread than construction; parent-child span linkage is best-effort under this path"; with `-da` (assertions disabled, production-mode), assert the wrapper still emits the SQL span (no exception unwinds), assert the span carries `db.youtrackdb.span.thread_mismatch = true`, assert `Span.fromContext(capturedContext)` matches the construction-thread parent (best-effort linkage holds for the parent reference), and assert the test's mock `Scope` captured a no-op-warn on thread B per OTel `ThreadLocalContextStorage` semantics.
2. `OTelDbQuerySpanTest`: regression test for the pre-Mutation-12 coverage gap. Runs `db.query("SELECT FROM User WHERE name = ?", "Alice")` and asserts exporter contains exactly one SQL span with sanitized `db.query.text = "SELECT FROM User WHERE name = ?"`, `db.operation.name = "SELECT"`, `db.collection.name = "User"`. Mirrors the equivalent `db.command(...)` assertion in `OTelSqlQueryTest` so any future regression that re-introduces the helper-only-in-`executeInternal()` shape fails loudly.
3. `OTelGremlinDslPassthroughTest`: end-to-end coverage for D39 / D40. Eight scenarios covering Gremlin DSL service-call paths:
   - `singleStatement_yqlSelect_setsFullSqlClassification`: `g.yql("SELECT FROM User WHERE name = ?", "Alice").toList()` emits exactly two spans, parent-child via `Context.current()`. Outer Gremlin span carries `db.operation.name = "SELECT"`, `db.collection.name = "User"`, sanitized `db.query.text`, plus `db.youtrackdb.gremlin.dsl_method = "yql"` and `statement_count = 1`. Inner SQL span carries identical `db.*` attributes plus `db.youtrackdb.sql.invoked_via = "gremlin_dsl"`.
   - `singleStatement_commandDdl_setsCreateClassification`: `g.command("CREATE INDEX User.email UNIQUE")` emits two spans; outer Gremlin span has `db.operation.name = "CREATE"`, `db.collection.name = "User"`, `db.youtrackdb.gremlin.dsl_method = "command"`. Inner SQL span emits via the DDL route through `schemaSession.command(SQLStatement, Map)` → `executeInternal()`.
   - `chain_withTransactionControl_picksDominantStatement`: `g.yql("BEGIN").yql("INSERT INTO Order SET amount = ?", 100).yql("COMMIT")` emits one outer Gremlin span (`db.operation.name = "INSERT"`, `db.collection.name = "Order"`, `statement_count = 3`, `has_transaction_control = true`, `statements_summary = "BEGIN; INSERT Order; COMMIT"`), one inner SQL span for INSERT, and one standalone commit span via `TransactionMetricsListener`. BEGIN and COMMIT emit no inner SQL spans (short-circuit in `executeCommand`).
   - `mixed_graphAndYql_classifiesByGraphSteps`: `g.V().has("name", "Alice").yql("UPDATE Order SET status = ?", "shipped").toList()` emits an outer Gremlin span classified by Path B graph-step walk (NOT by the UPDATE; classification reflects the dominant graph walk) plus `has_graph_steps = true`, `embedded_sql.0 = "UPDATE Order SET status = ?"`. Inner SQL span per traverser invocation carries the UPDATE classification.
   - `administrativeOnly_yqlBegin_marksTransactionControl`: `g.yql("BEGIN")` standalone emits exactly one outer Gremlin span with `db.operation.name = "BEGIN"`, no `db.collection.name`, `db.youtrackdb.gremlin.has_transaction_control = true`. No inner SQL span. No standalone commit span (BEGIN does not commit).
   - `emptyCommand_setsNoOp`: `g.yql("")` emits one outer Gremlin span with `db.operation.name = "youtrackdb"` (catch-all), `statement_count = 0`, `db.youtrackdb.gremlin.no_op = true`. No inner SQL span (YTDBCommandService short-circuits at `command.isEmpty()`).
   - `innerSqlSpan_carriesInvokedViaGremlinDsl_vs_native`: `g.yql("SELECT ...")` inner SQL span has `db.youtrackdb.sql.invoked_via = "gremlin_dsl"`; a direct `db.query("SELECT ...")` (no wrapping Gremlin span) has `"native"`. Asserts the `Context.current()`-based detection in the wrapper works correctly.
   - `parameterizedYql_sanitizesText_omitsArgs_byDefault`: `g.yql("SELECT FROM User WHERE id = ?", 42)` outer + inner spans both have `db.query.text` without `42` (sanitized). Args NOT emitted by default (`OPENTELEMETRY_QUERY_INCLUDE_PARAMETERS=false`). With flag flipped to `true`, args emit as `db.operation.parameter.0 = "42"`.
4. Coverage gate verification: run `./mvnw -pl youtrackdb-opentelemetry clean verify -P coverage` plus `coverage-gate.py`; fix gaps if any.

## Plan of Work

Four edits.

The first edit adds `OTelPathSpanShapeTest`. Four scenarios assert the Path A vs Path B span-shape invariant: Path A translated traversal emits one Gremlin span with no children carrying `db.youtrackdb.gremlin.path = "A"`; Path B fallback emits one Gremlin parent (`db.youtrackdb.gremlin.path = "B"`) + one SQL child (parent-child via `Context.current()`); direct `db.query(...)` emits one SQL span parented to `Context.current()` outside any Gremlin step with the `gremlin.path` attribute absent; an exception thrown mid-Gremlin still emits the parent (and child if reached) with ERROR status and does not leak Context to a subsequent native `db.query(...)` on the same thread (the OTel Gremlin span lifecycle hook scope closes via try-with-resources). Covers Track 3's OTel Gremlin span lifecycle hook on `YTDBQueryMetricsStep` and Track 4's `InstrumentedSqlResultSet` Context propagation behavior (capture at construction + `Scope.makeCurrent()` around listener fire).

The second edit adds `OTelDbQuerySpanTest`. Single regression test: `db.query("SELECT FROM User WHERE name = ?", "Alice")` produces a SQL span with sanitized `db.query.text`, correct operation name (`SELECT`), and correct collection name (`User`). Fails loudly if a future regression removes the `query()` call site from the SQL helper invocation chain.

The third edit adds `OTelGremlinDslPassthroughTest` covering D39 + D40 end-to-end. Eight scenarios spanning the Gremlin DSL service-call paths: pure passthrough SELECT, DDL via schema session, chain with transaction control, mixed graph+yql shape, administrative-only BEGIN, empty-command no-op, `invoked_via` disambiguation, and parameterized-args sanitization. Asserts the D40 `GremlinBytecodeClassifier` extension correctly extracts SQL classification from `CallStep.parameters["command"]` and populates the `db.youtrackdb.gremlin.*` custom attributes; asserts the Track 4 wrapper correctly sets `db.youtrackdb.sql.invoked_via` via `Context.current()` detection.

The fourth edit verifies coverage. Run the project coverage gate (`coverage-gate.py`) against the OTel module. If coverage on any changed production class falls below 85% line / 70% branch, add targeted tests (either back into 6a/6b classes or as additions here) until the gate passes.

Ordering: edits 1, 2, 3 are independent. Edit 4 (coverage gate) runs last so it sees the full suite from 6a + 6b + 6c.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 6c:

- `./mvnw -pl youtrackdb-opentelemetry clean test` passes with all classes from Tracks 6a + 6b + 6c green.
- `./mvnw -pl youtrackdb-opentelemetry clean verify -P coverage` reports ≥85% line and ≥70% branch coverage on the OTel module's production code (per CLAUDE.md gate).
- `python3 .github/scripts/coverage-gate.py --line-threshold 85 --branch-threshold 70 --compare-branch origin/develop --coverage-dir .coverage/reports` exits 0.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

## Interfaces and Dependencies

In scope:
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/listener/OTelPathSpanShapeTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/listener/OTelDbQuerySpanTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/listener/OTelGremlinDslPassthroughTest.java`

Out of scope:
- Any production code (Tracks 1-5 own that).
- Attribute mapping, hierarchy, propagation (Track 6a).
- Lifecycle and other invariants (Track 6b).
- LDBC tests with OTel enabled (separate ticket if span volume becomes a benchmark concern).

Inter-track dependencies:
- Depends on Track 6b: confirms the lifecycle and invariant tests are stable before the final invariant + regression layer runs.
- Provides for nothing downstream.

Test scenarios that map directly to invariants in the implementation plan:

```text
Invariant "Path A vs Path B span counts" → OTelPathSpanShapeTest asserts
  Path A emits one Gremlin span with no children (translated traversals);
  Path B emits one Gremlin parent + one SQL child via Context.current()
  propagation; direct SQL emits one span.
Invariant "One-way dependency" → enforced by Maven enforcer rule in Track 2,
  no Track 6c test needed.
```
