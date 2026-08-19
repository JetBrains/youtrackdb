# Core unit-test failure inventory

`./mvnw -pl core test` fails with exactly four failures and zero errors. All four are
`YTDBQueryMetricsStrategyTest` scenarios, all four are in scope under the item-0 triage rule, and
no failure falls outside that rule. The ESCALATE gate in item 0 does not fire, and the remaining
steps can be sized against the four known scenarios.

The enumeration claim that item 0 and the R1 correction both rest on is wrong. The
`sequential-tests` surefire execution drives nine `gremlintest` classes and runs zero Cucumber
scenarios; the track cites fourteen classes including the Cucumber runner.
`YTDBGraphFeatureTest` is discovered and its runner is constructed, but the execution's
`<groups>SequentialTest</groups>` filter leaves it with no scenario to execute, so `core`'s
Cucumber suite contributes nothing to a plain `./mvnw -pl core test`. See § What
`sequential-tests` actually drives.

## Run provenance

The orchestrator produced this run; this step read it rather than re-running the suite.

| Field | Value |
|---|---|
| Command | `./mvnw -pl core test`, from the repo root |
| Environment | default in-memory test env (no `-Dyoutrackdb.test.env=ci`) |
| HEAD | `f5737976be` ("Record Phase B base commit for Track 10") |
| Started / finished | 2026-08-01 ~09:42:29 / 09:58:36 +02:00, total 16:07 min |
| Maven exit code | 1 (`MAVEN_EXIT=1`, last line of the log) |
| Log | `/tmp/core-test-track10.log`, 3615 lines |
| Reports | `core/target/surefire-reports/`, 1286 XML files, all written inside the run window |

`f5737976be` differs from the track's recorded base `fd9eb7635d` by two added lines in
`track-10.md` and nothing else, so the run reflects the product and test source at the base
commit.

The report directory was not cleaned before the run (`test`, not `clean test`). Every XML file
carries an mtime inside the run window, so no stale report from the earlier 06:13–06:26 run
survives to pollute the inventory.

## Per-execution totals

`core/pom.xml` binds two surefire executions to the `test` phase. Both ran; the build failed on
the second.

| Execution | Tests run | Failures | Errors | Skipped | Distinct classes | Log line |
|---|---|---|---|---|---|---|
| `default-test` | 17898 | 0 | 0 | 85 | 1190 | 2898 |
| `sequential-tests` | 2220 | 4 | 0 | 13 | 98 | 3596 |

Only two classes run in both executions (`TraverseTest`,
`DirectMemoryOnlyDiskCacheLoadOrAddTest`), each contributing a disjoint method set because the
`SequentialTest` category splits them.

**Counting note.** The `default-test` figure reconciles exactly: its per-class console lines sum to
17898. The `sequential-tests` figure does not, and the reason is the double execution described in
§ What `sequential-tests` actually drives. Its per-class console lines sum to 2232, which matches
the raw `<testcase>` element count across the XML reports; the XML `tests=` attributes, which count
distinct method names, sum to 2109. Surefire's summary reports 2220, between the two. Failures (4),
errors (0), and skipped (13) agree across all three views, so the residual is an artifact of the
aggregate `Tests run` counter and does not touch the failure inventory.

## The failure list

Four failing test methods, one class:
`com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios.YTDBQueryMetricsStrategyTest`
(`core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java`).
The class reports `tests="20" failures="4" errors="0" skipped="0"`.

| Method | Line | Assertion label | Observed |
|---|---|---|---|
| `resetUnderTranslator_keepsPlanAndReIterationYieldsCorrectResults` | 626 | re-iteration after reset() yields the same correct results | actual size 0, expected 4; actual `[]` |
| `byIdLookupSurfacesNullPlan` | 359 | a by-id lookup runs no query, so no plan is captured | expected `null`, was `SelectExecutionPlan@…` |
| `planBackedScanSurfacesNonNullPlanWithoutFetchFromIndexStep` | 294 | an unindexed scan fetches from the class, not an index | `containsStepOfType(…, FetchFromClassExecutionStep.class)` returned false |
| `indexedQuerySurfacesPlanWithFetchFromIndexStep` | 331 | an indexed query uses a FetchFromIndexStep | `containsStepOfType(…, FetchFromIndexStep.class)` returned false |

Two details worth carrying into the later steps.

The reset scenario fails on rows, not on the plan. The assertion at line 620 —
`matchStep.getPlan()` is non-null after `reset()` — passes; the plan survives, and the second
`toList()` returns nothing. The two executions of the class within one run returned different RID
sets (`[#18:0, #19:0, #20:0, #23:0]` and `[#23:0, #23:1, #24:0, #25:0]`) for the first pass and an
empty second pass both times, so the empty re-iteration is deterministic and independent of the
seeded RIDs.

Both scan scenarios fail at the *first* introspection assertion in their sequence. The preceding
`isNotNull` and `isNotEmpty` assertions on `listener.executionPlan` and
`listener.planStepsInCallback` both pass, which confirms the diagnosis in the track's item 3: the
plan is present and non-empty, and only the nested fetch step is invisible.

## Triage against the item-0 rule

The rule: a failure is in scope when it is a `YTDBQueryMetricsStrategyTest` scenario or a direct
consequence of the `capturedExecutionPlan()` / boundary-lifecycle contracts items 1–3 settle.
Everything else is pre-existing branch debt, recorded with a disposition and deferred.

All four failures are `YTDBQueryMetricsStrategyTest` scenarios, so all four are in scope on the
first clause alone. The out-of-scope list is empty: no failure elsewhere in `core` needs a
disposition, and no deferral is required. In-scope failures do not reach beyond the four known
scenarios, so item 0's ESCALATE trigger stays unfired.

The four map onto the planned items exactly as the track assumed:
`resetUnderTranslator_keepsPlanAndReIterationYieldsCorrectResults` to item 1,
`byIdLookupSurfacesNullPlan` to item 2, and the two scan scenarios to item 3.

## What `sequential-tests` actually drives

Item 0 and the R1 correction both state that this execution drives the core Cucumber runner
(`YTDBGraphFeatureTest`) plus "thirteen other `gremlintest` classes". Measured, it drives nine
`gremlintest` classes and executes zero Cucumber scenarios.

The nine, each with its distinct-method count:
`YTDBAddVertexProcessTest` (2), `YTDBHasLabelProcessTest` (18), `YTDBPropertiesProcessTest` (10),
`YTDBPropertiesStructureTest` (7), `YTDBQueryMetricsStrategyTest` (20),
`YTDBTransactionMetricsListenerTest` (13), `YTDBVertexPropertyPropertiesStructureTest` (2),
`YTDBBasicPropertyStructureTest` (13), `YTDBTransactionStructureTest` (38). Total 123 distinct
methods.

Where "fourteen" came from: `core/src/test/.../gremlintest/**` holds fourteen classes annotated
`@Category(SequentialTest.class)`. Five of them contribute no test method of their own — the
abstract `YTDBTemporaryRidConversionTest`, the method-less base `YTDBAbstractGremlinTest`, and the
three runner classes `YTDBProcessTest`, `YTDBStructureTest`, and `YTDBGraphFeatureTest`. The count
is a source-level tally of annotations, not a measurement of a run.

### Why the Cucumber runner contributes nothing

`YTDBGraphFeatureTest` carries `@Category(SequentialTest.class)` and `@RunWith(Cucumber.class)`.
Two facts, both measured:

1. The class is loaded and its runner is constructed. Its `junit:target/cucumber.xml` plugin output
   exists at `core/target/cucumber.xml`, written 09:52:09 — one second before the first
   `sequential-tests` report file (09:52:10), which is when the JUnit core builds every runner.
2. The file is zero bytes, no `TEST-…YTDBGraphFeatureTest.xml` report exists, and neither
   "Cucumber" nor "GraphFeature" appears anywhere in the 3615-line log.

Cucumber's runner builds its child descriptions from feature files, so those descriptions carry no
test class and no `@Category` annotation. JUnit's category filter, which surefire installs from
`<groups>`, therefore rejects every child and the class runs nothing. That mechanism is inferred
from the two measurements above rather than executed in isolation; the measurements themselves
stand on their own.

The consequence for later work: `core`'s ~1900-scenario Cucumber suite is not exercised by
`./mvnw -pl core test` at all. Track 9's Cucumber-green goal and item 4's CI detection hole both
need to account for that, because the check they would rely on is currently inert in this module.
The `embedded` module's `EmbeddedGraphFeatureTest` is the runner that does execute (see
`.claude/docs/testing-details.md`).

### Every `gremlintest` class runs twice

Each of the nine executes twice inside `sequential-tests`, once through a suite runner and once
through surefire's own class discovery. `YTDBGremlinProcessTests` lists five of them for
`YTDBProcessTest`'s suite (`YTDBPropertiesProcessTest`, `YTDBHasLabelProcessTest`,
`YTDBAddVertexProcessTest`, `YTDBQueryMetricsStrategyTest`, `YTDBTransactionMetricsListenerTest`),
and `YTDBStructureSuite` lists the other four for `YTDBStructureTest`. Independently, all nine
match surefire's default `**/*Test.java` include and carry the `SequentialTest` category, so
surefire runs each a second time on its own. Surefire's `JUnitCoreProvider` reports suite children
under their own class names, which is why the runner class names never appear in the log.

The evidence is the XML: every one of the nine reports carries exactly twice as many `<testcase>`
elements as its `tests=` count — `YTDBQueryMetricsStrategyTest` shows 40 elements for `tests="20"`
— and the log holds two separate `Running …YTDBQueryMetricsStrategyTest` lines (3025 and 3275),
each followed by a full 20-test result with the same four failures.

This bears on the acceptance criterion that the 20 scenarios pass "both in isolation and in a
multi-class run": the full-suite run already covers the multi-class path, and the standalone path
is the `GREMLIN_TESTS` recipe below.

## Skipped tests

Surefire reports 85 skips in `default-test` and 13 in `sequential-tests`, spread over 29 report
files. None belongs to `YTDBQueryMetricsStrategyTest`, whose report records `skipped="0"`, so no
in-scope failure is hidden behind a skip.

`git diff origin/develop...HEAD -- 'core/src/**'` adds no `@Ignore`, so the branch has not silenced
anything to reach this state. The one Gremlin-adjacent skip,
`GraphQueryTest.hasIdWithVertex`, carries a bare `@Ignore` that is present on `develop` as well.

## Reproduction

Full suite, as run:

```
./mvnw -pl core test
```

The failing class alone, using the recipe the track records (it has no standalone `-Dtest` entry
point):

```
GREMLIN_TESTS=com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios.YTDBQueryMetricsStrategyTest \
  ./mvnw -pl core -o test -Dtest=YTDBProcessTest -DfailIfNoTests=false
```
