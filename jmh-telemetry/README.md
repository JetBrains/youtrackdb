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
The accepted off comparison budget is 1% throughput loss.

## Local off comparison

Use Python 3 and the same compatible harness for both revisions.
Keep the working tree clean and build the jar at each revision.
A harness label declares the fixture implementation and configuration you have checked.
It is not proof of compatibility.
Use the same label only after checking both fixtures.
A base without the matching fixture makes the comparison inconclusive.

Keep one runner outside both checkouts, including when the base predates this script.
Copy it from the candidate before capture, then change directory to the measured checkout.
The clean-tree check applies to that checkout, not to the external script.

```sh
cp jmh-telemetry/off_compare.py /tmp/telemetry-off-compare.py
cd /path/to/clean/base-checkout
```

Capture one reference per revision with the runner below.
Replace the output path and machine label for your machine.
Create the scratch directory outside the checkout first.
Use the Java module-opening options from Local execution when your JDK needs them.

```sh
python3 /tmp/telemetry-off-compare.py run \
  --output /tmp/base-off.json --harness off-fixture-v1 \
  --machine-type local-workstation --scratch /tmp \
  java -Xms2g -Xmx2g -XX:+UseG1GC \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.cs=ALL-UNNAMED \
  -jar jmh-telemetry/target/telemetry-benchmarks.jar '.*OffBenchmark.*' \
  -wi 5 -w 1s -i 10 -r 1s
```

Repeat from the candidate checkout with output `/tmp/candidate-off.json`.
If the base has no benchmark jar, record the absence without a Java command:

```sh
python3 /tmp/telemetry-off-compare.py run --record-absence \
  --output /tmp/base-off.json --harness off-fixture-v1 \
  --machine-type local-workstation --scratch /tmp
```

The explicit absence route records an inconclusive comparison without requiring complete evidence.
Normal capture requires a matching fixture, complete hardware and setup evidence,
and a jar revision that matches the checkout.
If any requirement fails, the runner exits non-zero without writing an envelope.

Keep heap, garbage collector and JDK settings fixed.
For accepted reference measurements, use JDK 21.
Lengthen warmup if needed, then repeat both revisions as a new measurement pass.
Do not mix passes.

```sh
python3 /tmp/telemetry-off-compare.py compare \
  --base /tmp/base-off.json --candidate /tmp/candidate-off.json
```

For several repetitions, list files in repetition order after each option.
The comparator pairs full benchmark name, every parameter, mode, threads, forks and unit.
It pairs repetitions by their list index and never pools scores.
Mixed revisions within either side make every combined result inconclusive.
Both sides must declare the same machine type.
Use the fixture checks above before collecting references.

## Metadata and report interpretation

The runner records processor affinity, processor model, physical memory and container limits
before it launches candidate Java code.
The runner resolves process cgroups and visible ancestors for version 1 and version 2.
An explicit unlimited limit, or a version 2 root without limit interfaces, records `unlimited`.
A disabled version 2 controller adds no local limit, but ancestor limits still apply.
Other missing non-root interfaces, hidden ancestors and unreadable probes remain unavailable.
Normal capture rejects unavailable evidence before writing an envelope.

The declared machine label stays separate from this hardware evidence.

Each envelope retains the jar manifest revision, checkout revision, harness label and raw JMH fields.
The comparator validates the stored jar and checkout revisions, not just the cached match flag.
A missing revision or a mismatch makes comparison inconclusive.

Each result also retains query text, generated data, expected count, path, scenario,
transaction boundaries and cache settings.
JMH supplies parameters, threads, mode, unit, JDK build, JVM options and harness version.
The code revision identifies the database software version.
The runner also retains dependency versions from the jar's Maven metadata.
The jar reports setup through a separate Java entry point outside measured calls.
Fixture bytecode hashes guard against changed workloads, including changes to call boundaries.
A hash difference makes the setup incompatible.
No metadata work runs inside measured calls, and no invocation fixture is added.

All rendered input is bounded, escaped and labelled candidate-reported, including base evidence.
Rendered values use code spans to suppress mentions and automatic links.
Long values show a truncation marker and a short hash of the full value.
Reports stop below 60,000 characters and show a truncation notice.
Software lists and fixture lists also have explicit limits.
Truncated values remain complete in the JSON file.
Neither a label nor candidate-reported metadata proves hardware allocation or fixture correctness.

The report requires positive finite scores and consistent finite confidence intervals.
Relative score error must not exceed 10% on either side.
Missing or invalid uncertainty never becomes zero.
Overlapping intervals, missing metadata, incompatible setups and missing pairs are inconclusive.
An inconclusive result is not a pass and does not mean no regression.
Rerun with complete evidence and enough measurement time.

A budget breach requires loss above 1%, separated intervals and agreement across every repetition.
It reports a point-estimate breach, not proof that the full loss interval exceeds 1%.
Disagreement makes the combined result inconclusive.
Statistical outcomes exit zero, including breaches and inconclusive results.

Malformed files, duplicate identities and failed Java runs exit non-zero.
Keep the JSON evidence beside the readable report.
This route compares only off revisions and does not establish an on-arm result.
