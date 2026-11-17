# Telemetry reference fixtures

This module contains opt-in Java Microbenchmark Harness (JMH) off fixtures.
Off installs no telemetry listener, local capture or log hook.
The module skips installation, deployment and Central publishing, including its executable jar.
Neither the normal compare job nor the nightly job builds it.

## Local execution

Build the executable jar from the repository root:

```sh
./mvnw -pl jmh-telemetry -am package -DskipTests
```

Run all three paths and both sizes with the declared JMH defaults:

```sh
java --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.cs=ALL-UNNAMED \
  -jar jmh-telemetry/target/telemetry-benchmarks.jar '.*OffBenchmark.*'
```

For a short launch check, append
`-p rows=1 -f 1 -wi 0 -i 1 -r 100ms -foe true` to that command.
This check is not a performance measurement.
Use `-l` instead of the benchmark expression to list benchmark identities.

Run fixture checks with:

```sh
./mvnw -pl jmh-telemetry -am package \
  -Dtest=com.jetbrains.youtrackdb.benchmarks.telemetry.OffBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false
python3 -m unittest discover -s jmh-telemetry/tests
```

## Workloads and interpretation

The benchmark identities are `OffBenchmark.sql`, `OffBenchmark.translated`
and `OffBenchmark.fallback` in package
`com.jetbrains.youtrackdb.benchmarks.telemetry`.
Each declares `telemetryScenario=off` and `rows=1,10000`.
One row is the tiny query. 10,000 rows form the scan.
Every path reads the whole unindexed Fixture class.
The data contains vertices with ordinal values from zero to rows minus one.

Each call opens a read transaction, consumes every result through the JMH black hole,
closes the result, then rolls back.
Setup rejects an enabled global query-result cache and verifies exact result counts.
The fallback explicitly removes the translation strategy.

Gremlin iteration teardown checks the last traversal consumed by the real entry point.
It also checks the database-local strategy counters across the whole iteration.
A translation decline, swallowed translator failure or accidental fallback translation fails
the run even when a later call takes the expected path.
These checks run outside measurement and install no listener or hook.
The only per-call path bookkeeping is a plain field write retaining the consumed traversal.
SQL has no path-validation fixture. No benchmark uses invocation-level fixtures.
Use the same harness for both comparison arms.

Each thread uses its own in-memory database under an operating-system temporary directory.
The fixture deletes that directory on teardown.
JMH defaults to throughput per second, one thread and three independent forks.
These fixtures do not define or approve performance budgets.
