<!--
MANIFEST
dimension: code-quality
prefix: CQ
level: medium
evidence_base:
  certs: 0
cert_index: []
flags:
  reference_accuracy_caveat: false   # no finding depends on a symbol search (all types internal)
index:
  - id: CQ1
    sev: suggestion
    anchor: "### CQ1"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/QueryMetricsListener.java:37
    cert: n/a
    basis: diff + full-file read; contract cross-checked against track-1.md D5 + Surprises log
  - id: CQ2
    sev: suggestion
    anchor: "### CQ2"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java:242
    cert: n/a
    basis: diff + full-file read
  - id: CQ3
    sev: suggestion
    anchor: "### CQ3"
    loc: core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/QueryMetricsListener.java:45
    cert: n/a
    basis: diff + full-file read
-->

## Findings

### CQ1 [suggestion] Interface javadoc states cache-hit-replay→null without the cache-enabled precondition

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/QueryMetricsListener.java` (line 34-39)
- **Issue**: The `getExecutionPlan()` contract says the plan "is `null` ... on a cache-hit replay of an identical query within the same transaction." The team's own Step 1 discovery and the revised D5 record that this holds only when `GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED` is on, which is off by default — with the cache off, a repeated identical query re-executes and surfaces a fresh non-null plan. The term "cache-hit replay" is technically self-qualifying (no cache, no hit), but a consumer such as DNQ reading only this javadoc can reasonably conclude "repeat the same query in a transaction → null," which is not the default behavior. CLAUDE.md treats comment accuracy as load-bearing, and the track already flagged that the pre-refinement prose "implied the null-on-replay behavior held unconditionally"; the same imprecision survives in this javadoc.
- **Suggestion**: Add the precondition, e.g. "...and, when the per-transaction result cache is enabled (`QUERY_TX_RESULT_CACHE_ENABLED`, off by default), on a cache-hit replay of an identical query within the same transaction." Keeps the contract aligned with D5.

### CQ2 [suggestion] Test mixes static-imported `assertThat` with fully-qualified `org.assertj.core.api.Assertions.assertThat`

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java` (lines 242-243, 277-278, 303, 328, 371, 395, 411 — the fully-qualified calls)
- **Issue**: The file statically imports `AssertionsForClassTypes.assertThat`, which lacks `Iterable`/`List` and `boolean` overloads, so the new tests reach for `org.assertj.core.api.Assertions.assertThat(...)` fully qualified whenever they assert on a `List` (`planStepsInCallback`) or a `boolean` (`containsFetchFromIndexStep(...)`). The result is two spellings of the same assertion entry point within single test methods, which reads inconsistently.
- **Suggestion**: Replace the `AssertionsForClassTypes.assertThat` static import with `org.assertj.core.api.Assertions.assertThat` (a superset covering Object/String/List/boolean). Every fully-qualified call then collapses to `assertThat(...)`, and the existing assertions keep compiling. Low risk; purely a readability unification.

### CQ3 [suggestion] Awkward mid-sentence javadoc line break ("callback, the")

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/QueryMetricsListener.java` (line 45-47)
- **Issue**: The wrap leaves "callback, the" alone on line 46 before "same window ... on line 47, a cosmetic wart from manual wrapping (Spotless does not reflow javadoc prose). It reads awkwardly.
- **Suggestion**: Rebalance the wrap so line breaks fall at natural clause boundaries, e.g. break after "queryFinished] callback," and keep "the same window the lazily-resolved [#getQuery] accessor has:" together.

## Evidence base
