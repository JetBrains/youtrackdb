<!-- workflow-sha: 5db61a37462f0b28965113f39a81b6fcb1ed1340 -->
# Track 6c: Test suite — Gremlin suppression, db.query regression, coverage gate

## Purpose / Big Picture

After this track lands, the Gremlin-span-uniqueness invariant is verified by `OTelGremlinSuppressionTest`, the pre-Mutation-12 coverage gap on the `db.query(...)` path is closed by `OTelDbQuerySpanTest`, and the OTel module's coverage gate (85% line / 70% branch on changed code) is satisfied. Final track of the OTel test suite.

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

Tests reuse `OTelTestBase` from Track 6a. `OTelGremlinSuppressionTest` asserts the Gremlin span uniqueness invariant by counting spans emitted from various traversal shapes. `OTelDbQuerySpanTest` closes the regression gap that prompted Mutation 12 (the SQL helper used to wire only `executeInternal()`, missing the `query()` call site that backs `db.query(...)`).

Concrete deliverables:

1. `OTelPathSpanShapeTest`: asserts the Path A vs Path B span-shape invariant. Four scenarios: (a) Path A translated Gremlin traversal `g.V().hasLabel("User").has("active", true).toList()` (assuming the recognised step set per PR #1038) emits exactly one Gremlin span with no children; assert exporter contains exactly one span, no SQL child, and the span carries `db.youtrackdb.gremlin.translated=true`. (b) Path B fallback traversal (a Gremlin traversal whose step set falls outside PR #1038's recognised set, forcing the half-measure strategies) emits one Gremlin parent + one SQL child; assert the child span's `parentSpanId` matches the Gremlin span's `spanId`, and the child carries the sanitized SQL translated from Gremlin. (c) Direct `db.query("SELECT FROM User")` emits exactly one SQL span parented to whatever `Context.current()` is at the call site (host span if a wrapper exists, else trace root). (d) Gremlin call that throws mid-traversal still emits the Gremlin parent span with `status=ERROR` and the SQL child span (if reached before the throw) also with error status; assert no Context leakage to a subsequent native `db.query("SELECT FROM User")` on the same thread (the OTel module's Gremlin span lifecycle hook closes its scope via try-with-resources). Covers Track 3's OTel Gremlin span lifecycle hook on `YTDBQueryMetricsStep` and Track 4's `InstrumentedSqlResultSet` Context propagation behavior (capture at construction + `Scope.makeCurrent()` around listener fire).
2. `OTelDbQuerySpanTest`: regression test for the pre-Mutation-12 coverage gap. Runs `db.query("SELECT FROM User WHERE name = ?", "Alice")` and asserts exporter contains exactly one SQL span with sanitized `db.query.text = "SELECT FROM User WHERE name = ?"`, `db.operation.name = "SELECT"`, `db.collection.name = "User"`. Mirrors the equivalent `db.command(...)` assertion in `OTelSqlQueryTest` so any future regression that re-introduces the helper-only-in-`executeInternal()` shape fails loudly.
3. Coverage gate verification: run `./mvnw -pl youtrackdb-opentelemetry clean verify -P coverage` plus `coverage-gate.py`; fix gaps if any.

## Plan of Work

Three edits.

The first edit adds `OTelPathSpanShapeTest`. Four scenarios assert the Path A vs Path B span-shape invariant: Path A translated traversal emits one Gremlin span with no children and `db.youtrackdb.gremlin.translated=true`; Path B fallback emits one Gremlin parent + one SQL child (parent-child via `Context.current()`); direct `db.query(...)` emits one SQL span parented to `Context.current()` outside any Gremlin step; an exception thrown mid-Gremlin still emits the parent (and child if reached) with ERROR status and does not leak Context to a subsequent native `db.query(...)` on the same thread (the OTel Gremlin span lifecycle hook scope closes via try-with-resources). Covers Track 3's OTel Gremlin span lifecycle hook on `YTDBQueryMetricsStep` and Track 4's `InstrumentedSqlResultSet` Context propagation behavior (capture at construction + `Scope.makeCurrent()` around listener fire).

The second edit adds `OTelDbQuerySpanTest`. Single regression test: `db.query("SELECT FROM User WHERE name = ?", "Alice")` produces a SQL span with sanitized `db.query.text`, correct operation name (`SELECT`), and correct collection name (`User`). Fails loudly if a future regression removes the `query()` call site from the SQL helper invocation chain.

The third edit verifies coverage. Run the project coverage gate (`coverage-gate.py`) against the OTel module. If coverage on any changed production class falls below 85% line / 70% branch, add targeted tests (either back into 6a/6b classes or as additions here) until the gate passes.

Ordering: edits 1 and 2 are independent. Edit 3 (coverage gate) runs last so it sees the full suite from 6a + 6b + 6c.

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
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/listener/OTelGremlinSuppressionTest.java`
- `youtrackdb-opentelemetry/src/test/java/com/jetbrains/youtrackdb/opentelemetry/listener/OTelDbQuerySpanTest.java`

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
Invariant "Gremlin span uniqueness" → OTelGremlinSuppressionTest asserts
  one Gremlin traversal emits exactly one span; no SQL children even when
  multi-hop traversals translate to several underlying SQL queries.
Invariant "One-way dependency" → enforced by Maven enforcer rule in Track 2,
  no Track 6c test needed.
```
