# Known-endpoint access-path performance experiments

`KnownEndpointAccessBenchmark` uses core's JMH test annotation processor.
It has no JUnit test methods. Its name matches none of Surefire's default patterns
(`Test*`, `*Test`, `*Tests`, `*TestCase`) or VMLens's `*MTTest` pattern.
Generated JMH `*_jmhTest` classes match a default name pattern but have no JUnit test
methods or JUnit suite runner. The JUnit provider does not execute them.
No build-file change or production configuration key enables the benchmark.

Run from the repository root, with one Maven invocation at a time:

```bash
./mvnw -pl core spotless:apply \
  '-DspotlessFiles=.*/match/KnownEndpoint(AccessBenchmark|BenchmarkProbe)\.java'
./mvnw -pl core -am -amd test-compile
bash core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/run-known-endpoint-benchmark.sh \
  --smoke 3
```

The script builds the test dependency classpath and retains all core `argLine` flags.
It prepends reactor classes, so installed sibling snapshots cannot replace those classes.
It sets no private temporary directory. Smoke mode validates all fixtures and paths,
warms each selector once, and reports median nanoseconds over the requested repetitions.
An optional third argument selects comma-separated scenarios:

```bash
bash core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/run-known-endpoint-benchmark.sh \
  --smoke 3 source-small,limit-late,budget-middle
```

For JMH, omit `--smoke`. The main method accepts standard JMH runner options:

```bash
bash core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/run-known-endpoint-benchmark.sh \
  -p scenario=source-small -p selector=AUTO -p translation=ON,OFF -wi 3 -i 5 -f 1
```

## Translation modes

The `translation` parameter accepts `MATCH`, `ON` and `OFF`.
`MATCH` runs the direct SQL planner experiment and its original assertions.
`ON` constructs a Gremlin traversal and enables the session setting
`QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED`.
`OFF` disables that setting and executes native Gremlin steps with no MATCH boundary.
Smoke mode runs all three modes. Each Gremlin trial proves ON and OFF row parity on the same data.
It also checks that OFF has no known-endpoint choice and identical work for all selectors.

AUTO, CURRENT and ALTERNATIVE select MATCH access paths only.
In OFF mode all three selectors are identical controls, not different Gremlin access paths.
CURRENT selects the retained MATCH path. It does not remove the wrapper or its planning cost.

Gremlin planning constructs a traversal and applies strategies without execution.
RID-bearing shapes bypass the translation and plan caches.
Execution and first-row measurements clone an already compiled traversal outside the timer.
Both include Gremlin row projection and stream cleanup.
Gremlin decision isolates the same private guarded MATCH preparation method in ON mode.
OFF has no such decision. Its decision method returns immediately and is not a latency comparison.
`GKE` smoke rows report full-consumption work, except for the `caller-one` fixture.
The rows include translation mode, path, returned rows, source reads, record loads,
adjacency entries, edge loads and discovery budget.

## Experiment matrix

Each fixture asserts its actual class count, subclass count, target degree, RID-list size,
extra-check count and pending transaction entry count. Indexed fixtures assert the actual
index plan and matching record count. Middle fixtures assert the actual reverse bag size.
Stale-statistics fixtures replace only the execution context's source-count estimate and
assert that the estimate differs from the database count.

| Variable or risk | Scenarios |
| --- | --- |
| Source count, 512 and 4096 | `source-small`, `source-large` |
| Target in-degree, 2 and 64 | `degree-low`, `degree-high` |
| Relevant source and edge schema breadth, 1 and 16 | `schema-narrow`, `schema-wide` |
| Indexed source selection, 1 and 128 records | `index-selective`, `index-broad` |
| Middle in-degree, 2 and 64 | `middle-low`, `middle-high` |
| Source-type share, 34/34 versus 2/34 at equal target degree | `type-all`, `type-rare` |
| Visible RID lists, 9 and 128 targets | `rids-short`, `rids-long` |
| One versus four detached checks | `checks-one`, `checks-many` |
| LIMIT 1 matches at the beginning and end of scan order | `limit-early`, `limit-late` |
| One-row-at-a-time caller without LIMIT | `caller-one`, plus every fixture's validation |
| Reverse-only duplicate adjacency with unchanged real edge count | `stale-middle-adjacency` |
| Real uneven degree, 130 edges concentrated on one of 65 middles | `uneven-middle` |
| Real uneven degree, 1040 edges concentrated on one of 520 middles | `budget-uneven-middle` |
| Canonical edge filter with accepted and rejected edges | `edge-filter` |
| Canonical edge filter over the real uneven-degree budget fixture | `budget-edge-filter` |
| Source count underestimated fourfold or overestimated fourfold | `stale-low`, `stale-high` |
| Middle-walk and RID-preparation budget exhaustion | `budget-middle`, `budget-list` |

Each fixture compares AUTO, CURRENT and ALTERNATIVE on the same data.
CURRENT forces SOURCE for detached checks.
ALTERNATIVE forces TARGET while retaining eligibility, security gates and the discovery budget.
Tests assert the actual path and real work counters.
The indexed fixtures run the index path for all selectors.

## Measurement boundaries and interpretation

Planning includes SQL parsing and uncached MATCH planning. Execution uses a copied plan
and excludes parsing and copying. Smoke first-row latency ends when the first row arrives.
Smoke execution latency ends when consumption finishes. Both exclude cleanup, and both close resources afterwards.
JMH measures the complete method call, which includes stream cleanup.
The decision measurement invokes the private guarded preparation method through reflection.
It excludes branch startup and includes decision-branch cleanup. CURRENT skips that method.
The decision-to-CURRENT-execution ratio measures preparation against the retained path's query work.
Building the second planning alternative remains part of planning cost.
The `caller-one` CSV work row counts only the first row. Its execution timing still measures
full consumption, while its first-row column measures the lazy call. `WITNESS` lines record
the changed dimensions alongside each CSV row.

Work instrumentation runs separately from timing.
Session read hooks count all record loads and source loads.
Detached counters report source records, candidates, reverse edges, target loads, budget switches and charged discovery work.
Only these tests access internal budget counters. PROFILE does not publish those counters.

A LinkBag iterator hook counts all adjacency entries visited, including reverse discovery and forward rechecks.
The composite work column adds charged discovery, record loads and adjacency entries.
It is an experiment metric, not the production cost model.
It overlaps some reverse-load and adjacency work.
Compare the individual counters as well as the composite extra work against the better forced path.
The `source_records` field counts secured source-start records, not unique record identifiers.
The deterministic bound applies to charged discovery work: it is at most the source-path estimate.
The class-scan component is exact only without LIMIT.
Wrong estimates and LIMIT do not promise a bound relative to actual query work.

The `stale-middle-adjacency` and `budget-middle` fixtures insert repeated real edge/source pairs
only into the middle's reverse bag. They leave the forward result and real edge count unchanged.
These test-only stale-adjacency fixtures exercise incorrect statistics and discovery exhaustion.
The genuine uneven-degree fixtures use committed parallel edge records, not duplicate bag entries.
They assert one nonempty middle, zero degree for all other middles, and matching forward and
reverse edge totals. The schema average is two in both fixtures, below actual degrees 130 and 1040.

The edge-filter fixtures use `.outE('BenchLink'){where:(weight=1)}.inV()`.
The small fixture rejects two of four edges and returns source positions zero and two.
The budget fixture also checks a local middle predicate and falls back before emitting candidates.
The `edge_record_loads` column counts BenchLink record loads, including forward rechecks.
Separate `DECISION_WORK` rows count only preparation loads and compare them with reverse entries.
Both edge-filter fixtures assert charged discovery stays within its budget.
A test-only Byte Buddy agent observes real sessions without replacing their identities.
Its thread-local scope enables counters only for untimed work experiments. It injects the
wrong source estimate for stale-statistics execution. The installed method advice has a
thread-local check in every timing scenario, including CURRENT.
These hooks add measurement overhead.
Timings are diagnostic. No timing threshold is asserted. Short smoke results are not stable
performance claims. Use JMH forks and sufficient warmup for performance comparisons.
