<!--MANIFEST
dimension: test-structure
iteration: 1
target: track-1 cumulative diff (f1cf786..HEAD)
verdict: changes-requested
counts: {blocker: 0, should-fix: 2, suggestion: 2}
evidence_base: {certs: 0}
flags: [evidence-trail-exempt]
index:
  - {id: TS1, sev: should-fix, anchor: "TS1", loc: "core/src/test/.../cache/TxResultCacheInvariantsTest.java + TxResultCacheWiringTest.java + QueryResultCacheTest.java + tx/TransactionMutationVersionTest.java (class level)", cert: n/a, basis: "core/pom.xml:304-307,316-330 parallel surefire excludes only @Category(SequentialTest); these classes mutate the process-global QUERY_TX_RESULT_CACHE_ENABLED / MAX_ENTRIES / K0_NONE_INVALIDATION_THRESHOLD and carry no category"}
  - {id: TS2, sev: should-fix, anchor: "TS2", loc: "TxResultCacheWiringTest.java:215-230 flagOff_cacheFieldStaysNullAndBehaviourUnchanged; TxResultCacheInvariantsTest.java:631-642 flagOff_noCacheAllocatedAndBehaviourUnchanged", cert: n/a, basis: "assertNull(getQueryResultCache()) collides with a concurrent sibling class holding the same global flag true; FrontendTransactionImpl.java:1412 reads the flag JVM-wide"}
  - {id: TS3, sev: suggestion, anchor: "TS3", loc: "CachedResultSetViewTest.java:46-48 + DeltaBuilderTest.java:46 + others (class Javadoc)", cert: n/a, basis: "first-paragraph Javadoc sentences run 4-6 lines; structural readability nit"}
  - {id: TS4, sev: suggestion, anchor: "TS4", loc: "TxResultCacheInvariantsTest.java:60-67 enableSchema()", cert: n/a, basis: "@Before method name describes neither what it does (creates class+property) nor matches its flag-capture body"}
-->

## Findings

### TS1 [should-fix] GlobalConfiguration-mutating cache tests are not `@Category(SequentialTest)` while the core suite runs classes in parallel

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/TxResultCacheInvariantsTest.java`, `TxResultCacheWiringTest.java`, `QueryResultCacheTest.java`, and `core/src/test/java/com/jetbrains/youtrackdb/internal/core/tx/TransactionMutationVersionTest.java` (whole-class).

**Issue**: `core/pom.xml:304-307` runs the default core test execution with `<parallel>classes</parallel>` and `<threadCountClasses>4</threadCountClasses>`, scheduling four test classes concurrently in one JVM. The `sequential-tests` execution (`core/pom.xml:316-330`) is the carve-out for classes that must run alone, and its own comment names the criterion: "tests that mutate GlobalConfiguration, use shared static instances, manipulate engine singletons". All four new classes mutate process-global `GlobalConfiguration` entries — `QUERY_TX_RESULT_CACHE_ENABLED` (all three cache classes), `QUERY_TX_RESULT_CACHE_MAX_ENTRIES` (set to `1` in `QueryResultCacheTest` and in `TxResultCacheInvariantsTest.i9_*`), and `QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD` — yet none carries `@Category(SequentialTest)`.

The flag is not session-scoped: `FrontendTransactionImpl.getQueryResultCache()` (`FrontendTransactionImpl.java:1412`) reads `QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean()` as a JVM-wide static at the first cache access of *any* transaction in the process. While one of these classes holds the flag `true`, any sibling test class in the parallel pool that issues a `SELECT`/`MATCH` inside a transaction silently routes through the new cache code path instead of the plain uncached path it was written against. Likewise `MAX_ENTRIES=1` set process-wide shrinks any concurrent transaction's cache to a single entry. This is order- and schedule-dependent behaviour that the suite's parallel scheduler can surface as a flaky failure in an unrelated class, and it is the exact category the pom isolates.

The convention is not enforced with perfect consistency in the existing suite — `PrefilterMetricsDefinitionTest` and a handful of storage tests mutate config without the category — so this is should-fix rather than blocker. But the cache `enabled` flag is unusually load-bearing: flipping it changes the behaviour of every `query()` in the JVM, and three sibling classes flip the same flag, so the blast radius is larger than a single hot-path ratio.

**Suggestion**: Add `@Category(com.jetbrains.youtrackdb.internal.SequentialTest.class)` to all four classes (the same annotation the 34 existing GlobalConfiguration-mutating sequential tests use). The per-test save/restore in `@Before`/`@After` is correct and should stay — it protects against intra-class leakage — but it cannot protect a *concurrently executing* sibling class, which only the category move fixes.

### TS2 [should-fix] `flagOff_*` assertions can be falsified by a concurrent sibling holding the flag true

**File**: `TxResultCacheWiringTest.java:215-230` (`flagOff_cacheFieldStaysNullAndBehaviourUnchanged`); `TxResultCacheInvariantsTest.java:631-642` (`flagOff_noCacheAllocatedAndBehaviourUnchanged`).

**Issue**: Both tests set the flag false and then assert `assertNull("no cache is allocated when the flag is off", tx().getQueryResultCache())`. Because the flag is a JVM-global (see TS1), the window between this test's `setValue(false)` and its `getQueryResultCache()` call can overlap a *different* cache test class running on another surefire thread that has set the flag `true`. When that overlap happens, `getQueryResultCache()` reads `true`, allocates a cache, and the `assertNull` fails — in a test whose own logic is correct. This is the sharpest concrete instance of TS1: an assertion that hard-codes the off-state of a shared global while sibling classes drive it on.

**Suggestion**: Resolved transitively by the TS1 category move (sequential execution removes the overlap). No change to the assertion itself is needed once the classes run alone; the assertion is the right thing to check, it just cannot hold under concurrent flag mutation.

### TS3 [suggestion] Class-level Javadoc opening sentences are dense multi-line runs

**File**: `CachedResultSetViewTest.java:36-47`, `DeltaBuilderTest.java:33-45`, `TxResultCacheInvariantsTest.java:28-52`, `QueryResultCacheTest.java:17-27` (class Javadoc).

**Issue**: The opening sentence of several class Javadocs spans four to six lines before the first period (for example `CachedResultSetViewTest`'s first sentence packs the view's responsibility, the I10/I9 invariants, the unit-test strategy, and the synthetic-fixture note into one breath). The content is accurate and the per-method Javadoc is exemplary; this is purely a skim-readability nit at the class header, where a reviewer wants a one-line "what this class proves" before the detail.

**Suggestion**: Lead each class Javadoc with a single short sentence stating the contract under test, then let the existing detail follow as separate sentences. Optional; the documentation quality is otherwise a strength of this track.

### TS4 [suggestion] `@Before` hook name does not describe its body

**File**: `TxResultCacheInvariantsTest.java:60-67` (`enableSchema`); compare `TxResultCacheWiringTest.java:43-50` (`enableCacheAndSchema`).

**Issue**: The `@Before` method is named `enableSchema()` but its body both captures the prior flag value (for `@After` restore) and creates the test class plus its property. The name describes neither half accurately — it does not enable a schema, it creates one and snapshots a flag. `TxResultCacheWiringTest` has the same structure under the slightly better name `enableCacheAndSchema` (though that one does not enable the cache either). A reader scanning the fixture has to read the body to learn what the setup does.

**Suggestion**: Rename to something like `createSchemaAndCaptureFlag()` (and `enableCacheAndSchema` → `createSchemaAndCaptureFlag()` for parity). Trivial.

## Evidence base

(none — this dimension is evidence-trail-exempt: no refutation or certificate phase to persist)
