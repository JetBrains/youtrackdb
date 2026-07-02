<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: TS1, sev: suggestion, loc: YTDBQueryMetricsStrategyTest.java:4, anchor: "### TS1 ", cert: n/a, basis: "two assertThat entry points interleave within single tests; reader must track which import is in play"}
evidence_base: {section: "## Evidence base", certs: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

The two isolation axes this dimension was spawned to check are clean.

- **Global-config restore (`cacheHitReplaySurfacesNullPlan`).** The test reads
  `QUERY_TX_RESULT_CACHE_ENABLED` into `cacheWasEnabled`, flips it on, and restores
  the captured value in a `finally`. A failing assertion inside the `try` still
  runs the restore. The suite is `@Category(SequentialTest.class)` and runs
  single-threaded, so no sibling test reads the JVM-global flag while it is
  toggled. Correct.
- **Schema/data left behind (`indexedQuerySurfacesPlanWithFetchFromIndexStep`).**
  The `IndexedThing` vertex class, its `code` index, and the added vertex do not
  leak. `AbstractGremlinTest.setup()` calls `GraphProvider.clear(config)` before
  opening the graph and `tearDown()` calls `clear(graph, config)`; the project's
  `YTDBGraphProvider.clear` drops the database. Every test therefore runs against
  a freshly dropped-and-reloaded `MODERN` graph, so the schema class created in a
  separate `acquireSession()` (which is itself try-with-resources closed) cannot
  reach a sibling test. Transaction open/commit are balanced in all new tests.

### TS1 [suggestion] Two `assertThat` entry points interleave within a single test

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java`, import at line 4; call sites at lines 257, 292, 318, 343, 371, 386, 395, 410, 426.

**Issue**: The file statically imports `org.assertj.core.api.AssertionsForClassTypes.assertThat` (line 4), whose overload set does not cover `boolean` or `List`. The new tests therefore fall back to the fully-qualified `org.assertj.core.api.Assertions.assertThat(...)` for those assertions, so a single test body mixes the short `assertThat(...)` and the fully-qualified form on adjacent lines (for example lines 254 and 257 in `planBackedScanSurfacesNonNullPlanWithoutFetchFromIndexStep`). A reader has to notice the two forms resolve to different classes and figure out why one call is qualified and the next is not — friction with no upside, since both come from AssertJ.

**Suggestion**: Change the single static import at line 4 from
`org.assertj.core.api.AssertionsForClassTypes.assertThat` to
`org.assertj.core.api.Assertions.assertThat`. `Assertions` is the umbrella
entry point and carries `assertThat(Object)`, `assertThat(boolean)`,
`assertThat(List)`, and `assertThat(String)` overloads, so every call site can
use the short `assertThat(...)` form and the fully-qualified references can be
deleted. This is a pure readability cleanup; the assertions themselves are
unchanged. (Reference-accuracy caveat: mcp-steroid was unreachable, so the
AssertJ overload coverage above is from the library's known API surface, not a
PSI resolution against the classpath.)

## Evidence base
