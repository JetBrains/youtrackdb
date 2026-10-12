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
  --smoke 3 source-small,limit-late,budget-middle,native-tx-large
```

For JMH, omit `--smoke`. The main method accepts standard JMH runner options:

```bash
bash core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/run-known-endpoint-benchmark.sh \
  -p scenario=source-small -p selector=AUTO -wi 3 -i 5 -f 1
```

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
| Native retained RID versus exact-count source start | `native-sparse`, `native-hub` |
| Native decision with 1 and 4096 pending writes | `native-tx-small`, `native-tx-large` |
| Native exact-count budget over 512 source tombstones | `native-tombstones` |

Each fixture compares AUTO, CURRENT and ALTERNATIVE on the same data.
CURRENT forces SOURCE for detached checks and RID for native patterns.
ALTERNATIVE forces TARGET or SOURCE, respectively. Both alternatives retain eligibility and
security gates. Forced detached TARGET also retains its discovery budget.
Forced native SOURCE uses an unlimited exact-count position allowance, unlike AUTO.
The `native-tombstones` fixture checks this difference. Tests assert the actual path and real work counters.
The indexed fixtures therefore run the index path for all selectors.
The pending-write native fixtures retain RID for all selectors.

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

Work instrumentation runs separately from timing. Session read hooks count all record loads
and source loads. Storage hooks count successfully charged exact-count positions,
including tombstones. Rejected position attempts have a separate counter.
Optimistic retries can repeat an abort without spending a further position.
An iterator hook counts native secured preflight rows. Detached counters report source records, candidates, reverse edges,
target loads, budget switches and charged discovery work. Internal budget counters are accessed
only in these tests. They are not new PROFILE output.

A LinkBag iterator hook counts all adjacency entries visited, including native reverse work
and forward rechecks. The composite work column adds charged discovery, record loads,
count positions, adjacency entries and native decision transaction-operation visits.
It is an experiment metric, not the production
cost model. It overlaps some reverse-load and adjacency work.
Compare the individual counters as well as the composite extra work against the better forced
path. Native `source_records` counts source-load calls, including preflight and retained probe
reloads. Detached `source_records` is the source-start counter. These are not unique RID counts.
The deterministic detached bound applies to charged discovery work: it is at most the
source-path estimate. The class-scan component is exact only without LIMIT. Wrong estimates
and LIMIT do not promise a bound relative to actual query work. Native AUTO charges at most
its RID-comparison position allowance, with rejected attempts reported separately.
Pending native writes must perform zero exact-count positions and zero preflight rows.
The `decision_tx_visits` counter counts actual transaction-operation iterator visits inside native
preparation. Both pending-write fixtures require zero visits for every selector.
A positive witness calls the count adjustment under observation and checks the complete pending
operation count. The counter excludes retained query consumption and fixture setup.

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
thread-local check in every timing scenario, including CURRENT. Native preparation also has
entry and exit checks that CURRENT skips. These hooks add measurement overhead.
No production file changes.
Timings are diagnostic. No timing threshold is asserted. Short smoke results are not stable
performance claims. Use JMH forks and sufficient warmup for performance comparisons.
