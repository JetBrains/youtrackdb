# Track 6c: Test suite — Gremlin suppression, db.query regression, coverage gate

## Purpose / Big Picture

After this track lands, the Gremlin-span-uniqueness invariant is verified by `OTelGremlinSuppressionTest`, the pre-Mutation-12 coverage gap on the `db.query(...)` path is closed by `OTelDbQuerySpanTest`, and the OTel module's coverage gate (85% line / 70% branch on changed code) is satisfied. Final track of the OTel test suite.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Cover the Gremlin span uniqueness invariant (one traversal emits exactly one span, verifying `GremlinSqlSuppression` behavior) and the `db.query()` SQL span coverage (regression test for the pre-mutation gap). Verifies coverage gate.

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

1. `OTelGremlinSuppressionTest`: asserts the Gremlin span uniqueness invariant. Three scenarios: (a) single-step traversal `g.V().hasLabel("User").toList()` emits exactly one span (the Gremlin one); assert exporter contains exactly one span with `db.query.text` matching the traversal — no nested SQL span. (b) multi-hop traversal `g.V().out("knows").out("knows").toList()` still emits exactly one span (the suppression counter wraps every underlying `YTDBGraphStep` invocation). (c) Gremlin call that throws mid-traversal does not leak the suppression state — a subsequent native `db.query("SELECT FROM User")` on the same thread emits its SQL span normally (counter cleanup via `AutoCloseable.close()` runs even on exception). Covers Track 4's `GremlinSqlSuppression` utility and the `YTDBGraphQuery.execute` activation site.
2. `OTelDbQuerySpanTest`: regression test for the pre-Mutation-12 coverage gap. Runs `db.query("SELECT FROM User WHERE name = ?", "Alice")` and asserts exporter contains exactly one SQL span with sanitized `db.query.text = "SELECT FROM User WHERE name = ?"`, `db.operation.name = "SELECT"`, `db.collection.name = "User"`. Mirrors the equivalent `db.command(...)` assertion in `OTelSqlQueryTest` so any future regression that re-introduces the helper-only-in-`executeInternal()` shape fails loudly.
3. Coverage gate verification: run `./mvnw -pl youtrackdb-opentelemetry clean verify -P coverage` plus `coverage-gate.py`; fix gaps if any.

## Plan of Work

Three edits.

The first edit adds `OTelGremlinSuppressionTest`. Three scenarios assert the Gremlin span uniqueness invariant: single-step `g.V().hasLabel("User").toList()` emits exactly one span; multi-hop `g.V().out("knows").out("knows").toList()` also emits exactly one span; an exception thrown mid-Gremlin does not leak the suppression counter to a subsequent native `db.query(...)` on the same thread. Covers Track 4's `GremlinSqlSuppression` utility and the `YTDBGraphQuery.execute` activation site.

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
