<!-- MANIFEST
dimension: test-structure
prefix: TS
findings: 3
high_water_mark: 0
evidence_base: { certs: 0 }
flags: []
index:
  - id: TS1
    sev: should-fix
    anchor: "TS1"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateStateTest.java:2202"
    cert: n/a
    basis: "AggregateStateTest extends DbTestBase, overrides the lifecycle with its own @Before/@After, and shadows the protected youTrackDB field — every method spins up two databases."
  - id: TS2
    sev: suggestion
    anchor: "TS2"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateCacheEquivalenceTest.java:1763"
    cert: n/a
    basis: "Three near-parallel scenario drivers (runScenario / runWithTarget / runCollapse) plus an assertEquivalent helper used by only some cases; most cases inline the fresh-vs-cached compare, so the equivalence pattern is expressed inconsistently."
  - id: TS3
    sev: suggestion
    anchor: "TS3"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateCacheEquivalenceTest.java:1688"
    cert: n/a
    basis: "@Before enableSchema() reads/saves the cache flag and creates schema; @After restoreFlag() restores it — the method names describe only half of what each does."
-->

## Findings

### TS1 [should-fix] AggregateStateTest runs a dual lifecycle and shadows DbTestBase.youTrackDB

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateStateTest.java`, class declaration (line ~2202) plus `@Before before()` / `@After after()` and the `private YouTrackDBImpl youTrackDB` field.
- **Issue**: `AggregateStateTest extends DbTestBase` but supplies its own `@Before before()` and `@After after()` and its own `db` / `youTrackDB` fields. JUnit 4 runs *both* the superclass `@Before` (`DbTestBase.beforeTest()`, which creates `this.databaseName`, a pool, and the inherited `session`) and the subclass `before()` (which creates a second database named `getClass().getSimpleName()` via `createYTDBManagerAndDb` and opens its own `db`). The test only ever uses `db`; the inherited database, pool, and `session` are created and torn down unused on every one of the 34 methods. Worse, the subclass field `private YouTrackDBImpl youTrackDB` **shadows** the `protected YouTrackDBImpl youTrackDB` in `DbTestBase` (confirmed: `DbTestBase.java:35`), so the two lifecycles operate on two different manager instances that happen to share a field name. A future reader cannot tell from the class body that a whole second database is being stood up by the parent, and any later change that makes the subclass touch the inherited `session` or `youTrackDB` would silently bind to the wrong instance.

  This is isolation-correct today only because surefire runs with `<parallel>classes</parallel>` (core/pom.xml:304) — methods within the class are sequential, so the class-named database is not contended. It is the *clarity* and *fragility* that fail the bar: the test reads as a self-contained fixture while a hidden parallel fixture runs alongside it.
- **Suggestion**: Pick one fixture model. Either (a) drop the custom `before()`/`after()` and use the inherited `DbTestBase` lifecycle plus the inherited `session` (rename local `db` usages to `session`, which is already a `DatabaseSessionEmbedded`), eliminating the second database entirely; or (b) if a hand-rolled manager is genuinely needed, do **not** extend `DbTestBase` — extend nothing and own the full lifecycle, so no parent `@Before` fires and no field is shadowed. Option (a) is the smaller change and matches every sibling test in this package (`AggregateCacheEquivalenceTest`, `ShapeClassifierTest`, `FunctionDeterminismEnumerationTest` all use the inherited `session`).

### TS2 [suggestion] AggregateCacheEquivalenceTest expresses the same equivalence pattern three different ways

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateCacheEquivalenceTest.java`, helpers `runScenario` (line ~1763), `runWithTarget` (line ~1913), `runCollapse` (line ~1950), and `assertEquivalent` (line ~1781).
- **Issue**: The class has three near-identical scenario drivers that each `clearClass()`, seed committed records, toggle the cache flag, run a populating query, apply a mutation, run a second query, and roll back. `assertEquivalent` wraps `runScenario` and emits the fresh-vs-cached `assertEquals`, but only four cases call it (`countEquivalence_createAfterPopulate`, the `sum`/`avg` create cases, `countDistinctServedByK0NoneMatchesEngineRowCount`). The majority of cases call `runWithTarget` twice (once for `false`, once for `true`) and inline their own `assertEquals`, repeating the fresh-vs-cached comparison boilerplate at every call site. The result is that the central invariant of the suite — "cached scalar equals a fresh uncached scalar at the same moment" — is sometimes a one-line helper call and sometimes six lines of hand-rolled compare, so a reader must re-derive the pattern per test and the failure messages drift ("must match fresh", "must drop the contributor", "must recompute to match fresh").
- **Suggestion**: Add a target-aware `assertEquivalentWithTarget(String agg, Function<RID, Consumer<...>> mutationFor)` mirroring `assertEquivalent`, and route the `runWithTarget`-based cases through it. That collapses each two-line `fresh`/`cached` capture plus `assertEquals` into a single call and gives every equivalence case a uniform failure message, leaving only the genuinely structural cases (`runCollapse`, the K0/fallback metric assertions) to spell out their own logic.

### TS3 [suggestion] AggregateCacheEquivalenceTest fixture method names describe only half their work

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateCacheEquivalenceTest.java`, `@Before enableSchema()` (line ~1688) and `@After restoreFlag()` (line ~1696).
- **Issue**: `enableSchema()` both saves the current `QUERY_TX_RESULT_CACHE_ENABLED` value into `previousEnabled` *and* creates the test class/properties; `restoreFlag()` restores that saved value. The names cover only one half each: `enableSchema` does not mention that it captures the cache-flag baseline (the load-bearing half that pairs with `@After`), and the schema-creation half it *is* named for is unrelated to flag handling. A reader scanning for where the global flag is saved/restored will not find it under these names, and the `@Before`/`@After` pairing reads as mismatched.
- **Suggestion**: Rename to reflect both responsibilities, e.g. `@Before setUpClassAndCaptureFlag()` / `@After restoreCacheFlag()`, or split the flag capture/restore into clearly named statements with a one-line comment ("save the global cache flag so per-test toggles can be undone"). Pure naming/comment change; no behavior impact.

## Evidence base
