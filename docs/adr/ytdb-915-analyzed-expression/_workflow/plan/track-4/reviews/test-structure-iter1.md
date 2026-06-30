<!--MANIFEST
dimension: test-structure
prefix: TS
iteration: 1
verdict: PASS
findings_total: 3
blockers: 0
should_fix: 0
suggestions: 3
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - { id: TS1, sev: suggestion, anchor: "TS1", loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:578", cert: n/a, basis: "diff+file read" }
  - { id: TS2, sev: suggestion, anchor: "TS2", loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:380", cert: n/a, basis: "diff+file read" }
  - { id: TS3, sev: suggestion, anchor: "TS3", loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java:506", cert: n/a, basis: "diff+file read" }
-->

## Findings

### TS1 [suggestion] `comparisonAllOperatorsParity` bundles two distinct scenarios (unequal-operands sweep + equal-operands boundary sweep) into one method

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java`, method `comparisonAllOperatorsParity` (line 578)
- **Issue**: The method runs eleven `assertComparisonParity` calls across two separately-constructed rows. The first row (`a=3, b=5`) sweeps all six operators plus both `!=` spellings on unequal operands; the second row (`a=5, b=5`) re-runs four operators to pin the equality boundary. On a failure the JUnit report points at one method name covering eleven assertions over two fixtures, so the failing fragment is only identifiable from the per-assertion message string. This is readability-at-failure-time, not an isolation problem — the test is fully self-contained and order-independent.
- **Suggestion**: Optional. Either split into `comparisonAllOperatorsUnequalOperands` and `comparisonOperatorsEqualityBoundary`, or leave as-is — the per-assertion `"comparison parity for: " + sql` message already names the failing fragment, so the maintenance cost is low. Given that the bundling is deliberate (the inline comment at the second row states the intent), this is a borderline call; flagging for the author's judgment, not a required change.

### TS2 [suggestion] `context()` allocates a fresh `BasicCommandContext` per call; several tests call it implicitly inside the parity helpers and again directly, yielding two contexts in one test

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java`, method `context` (line 380)
- **Issue**: `context()` returns `new BasicCommandContext(session)` each call. The parity helpers (`assertValueParity`, `assertComparisonParity`, `assertNotParity`) each create their own context internally, so a test that calls a helper twice (e.g. `comparisonNotEqualSessionThreading`, `comparisonAllOperatorsParity`) silently creates several short-lived contexts over the same session. This is harmless — each context is independent and the session is the shared anchor — but a reader tracing `$current` seeding in `visitFuncCall` could wonder whether context identity matters across calls. It does not, because every fragment here re-seeds from a fresh context.
- **Suggestion**: Optional. A one-line comment on `context()` noting "fresh context per call is intentional; tests assert no cross-fragment context state" would close the question for a future reader. No structural change needed.

### TS3 [suggestion] DB-backed tests repeat the create-class / begin / insert / commit / begin / query / commit scaffold inline; a small fixture helper would make each test's distinct part stand out

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprEvaluatorTest.java`, method `comparisonCollationCaseInsensitiveMatch` (line 506) and the six other DB-backed tests
- **Issue**: Seven tests (`comparisonCollationCaseInsensitiveMatch`, `comparisonNotEqualSessionThreading`, `comparisonLongColumnVsIntegerLiteral`, `collateGuardOnEntityWithAbsentProperty`, `collateResolvedFromRightOperand`, `identifiableAdapterEvaluatesThroughCollationPath`, `identifiableAdapterReturnsFalseOnNonMatch`) repeat the same five-to-seven-line scaffold: `CREATE class`, optional `CREATE PROPERTY ... COLLATE`, `begin/INSERT/commit`, then `begin / query / next / assert / commit`. The schema definition and the one asserted fragment — the parts that differ — are interleaved with boilerplate, so the reader re-parses the same setup shape seven times to find the one line that varies.
- **Suggestion**: Optional. A helper like `Result insertAndFetchSingle(String classDdl, String propertyDdl, String insertDml)` (returning the single queried row, with the transaction lifecycle owned by the helper) would let each test read as "this schema, this fragment, this expectation." The current inline form is correct and isolated; this is a clarity-density suggestion only, and the author may reasonably prefer the explicit inline form for a parity suite where the exact DDL/DML matters to the scenario.

## Evidence base

<!-- This dimension is evidence-trail-exempt per the reviewer's Output routing: reason (a) — no refutation or certificate phase to persist. No #### C<n> certificate entries are written. -->
