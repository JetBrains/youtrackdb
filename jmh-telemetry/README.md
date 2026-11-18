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

## Local paired runner

`telemetry_run.py` runs catalog arms for one candidate revision on one local machine.
Use only a jar you trust to execute with your account privileges.
The runner is not a sandbox.
The shipped catalog has no on arm, so this command exits 2 with "not available" before launching Java.

Real-runner use must wait for first-delivery merge and separate execution authorization.
See the [delivery plan](../docs/adr/ytdb-496-opentelemetry-support/_workflow/plan/track-I1.md).

Build the candidate jar once in its own clean checkout with the build command above.
The checkout HEAD must match the requested revision.
The runner copies the jar into private scratch and uses only that copy for all launches.
Do not rebuild between arms or repetitions.
Choose a scratch directory outside `target`.
The fixture keeps its warmed dataset in memory and uses scratch for temporary database directories.
Each fork creates its fixture and warms it before measurement.

The following example uses an existing scratch directory and a new output directory.
Replace the revision with the jar's full `Implementation-Build` revision.

```sh
python3 jmh-telemetry/telemetry_run.py \
  --jar /path/to/candidate/jmh-telemetry/target/telemetry-benchmarks.jar \
  --scratch /path/to/scratch --directory /path/to/new-results \
  --run local_1 --producing-attempt attempt_1 --revision FULL_REVISION \
  --artifact local_results --harness off_fixture_v1 --machine-type local_workstation \
  --java /path/to/jdk21/bin/java --repetitions 2 --warmup 5
```

Identifiers accept only ASCII letters, digits and underscores.
Use Python 3 and JDK 21, with at least two repetitions to alternate arm order.
The runner fixes both heap bounds at 2 GiB and uses G1.
Keep the dataset small enough to fit that heap.

It records available CPUs, CPU model, memory and container limits before measurement.
It also records software versions and setup evidence from the candidate jar.
Candidate listing and setup data remain untrusted evidence, not proof of correct execution.

Each invocation uses three forks, five one-second warmup iterations and ten one-second measurement iterations.
Arms run sequentially, with their order reversed on even repetitions.
Scenario values apply to one exact catalog benchmark at a time.
Only arms present in that benchmark's parameter listing can launch.

For a stabilization rerun, use `--warmup 5 10` in a new output directory.
Pass one uses five warmup iterations, and pass two uses ten.
Every pass repeats both arms and retains separate files for every repetition.
The runner does not infer stabilization or pool passes.
Never reuse an output directory, including after a failure.

The output directory contains raw JMH JSON files and `manifest.json`.
The runner validates the complete inventory before publishing the manifest.
A failed launch, failed fork or interrupted pass exits with a non-success status.
Any partial raw files remain for inspection without a complete manifest.
Failed-launch output is shown on standard error with terminal control characters escaped.
A preflight failure leaves no output directory.
Missing catalog arms produce explicit unavailable evidence and exit 2.
Use the unchanged `telemetry_compare.py` with the same run identifiers, repetitions and pass count.

Later arm registrations must provide matching off fixtures and scenario-specific setup metadata.
The runner accepts setup keys such as `sql.on.scenario` and `sql.on.query`.
It also accepts the existing `sql.scenario` keys when they match the selected arm.
Only the trusted Python catalog can register an arm.
No command-line option or candidate listing can extend it.

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

## Offline telemetry comparison contract

`telemetry_compare.py` validates raw JMH JSON without launching benchmarks.
Its trusted catalog currently contains only off arms.
It returns non-zero with status `not available` until a later feature track registers an on arm.
Candidate data and command-line options cannot register arms.

The offline command accepts trusted run identifiers and a directory of evidence:

```sh
python3 jmh-telemetry/telemetry_compare.py --directory /path/to/evidence \
  --run run1 --producing-attempt attempt1 --revision REVISION --artifact artifact1 \
  --repetitions 2 --passes 1 --validation-attempt validation1 --output /path/to/report.md
```

The output path must not exist.
The directory contains `manifest.json` and exactly the required raw JMH files.
The trusted catalog, repetition count and pass count determine those files.
File names include run, producing attempt, revision, arm, repetition, pass and a benchmark hash.
The manifest cannot change this inventory.

Each raw file is a JMH result list with every catalog row size.
An unavailable arm still needs its required file, containing an empty list.

The manifest records `run`, `producingAttempt`, `revision`, `artifact`, `listing` and `files`.
Listing entries contain `benchmark` and `arm`.
File entries contain `name`, `sha256`, `execution` and `metadata`.
Execution is either `ok` or `unavailable`. Other values are fatal.
Metadata uses the local off envelope fields.

The `setup` object maps each row-size string to its setup fields, including `expectedCount`.
For example, `setup["10000"].expectedCount` describes the 10,000-row fixture.
The setup scenario must match the arm.
All labels, listing entries and metadata remain candidate-reported evidence.

The validator pairs each scenario with its own off arm at the same revision, repetition and pass.
Every parameter except `telemetryScenario`, plus setup and measurement dimensions, must match.
Loss is `100 * (1 - scenario throughput / off throughput)`.
No scores are pooled.

Each repetition must exceed its approved budget with separated intervals before a breach is reported.
Proposed budgets remain unapproved. Diagnostic losses are not budget decisions.
Missing pairs, incompatible setups, invalid uncertainty, noise and disagreement are inconclusive.
Rerun both arms in a new pass.

A slowdown exits zero. Structural, execution and integrity failures exit non-zero.

Run identifiers contain only ASCII letters, digits and underscores.
Input files are limited to 4 MiB, with at most 256 records per raw file.
Objects allow 256 fields, parameters allow 32 fields, and strings allow 4,096 characters.
Nested values allow 12 levels. Inventories and arrays allow 2,048 entries.

Over-limit input is a fatal validation error.
Outcomes precede bounded evidence. If outcomes exceed report capacity, validation fails.

Reports use the dedicated `ytdb-telemetry-comparison` comment marker.
Candidate strings use the bounded renderer shared with the local off route.
Producing attempt and artifact identity remain distinct from the validation attempt.
The offline validator launches no candidate code.
Neither local tool changes workflow mode, nightly history or artifact uploads.
