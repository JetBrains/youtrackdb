# Registered orphan open benchmark

This standalone tool lives beside core test tools. Its Java probe lives with core storage
benchmarks. Surefire does not run it. Python 3 and JDK 21 or later are required.

## Builds and classpaths

Use two separate checkouts on the same machine. The bootstrap checkout must be at
`6d8762ba90`. The current checkout must contain Track 8. Do not alternate revisions inside one
worktree. Build each checkout serially:

```bash
./mvnw -pl core -am install -DskipTests
./mvnw -pl core dependency:build-classpath -Dmdep.includeScope=test \
  -Dmdep.outputFile=/absolute/external/results/dependencies.txt
```

Use a separate dependency file per build. Before building the other revision, copy every jar
listed in the dependency file into a build-specific external directory and rewrite the classpath
to those absolute copies. Maven installs can overwrite shared snapshot jars in the local repository.
Do not measure against mutable local-repository snapshot paths. Create an absolute classpath file
per build containing that build's `core/target/classes`, followed by the frozen dependency paths,
separated by the platform classpath separator. The driver compiles the same Java probe against
each classpath.
The probe uses reflection for the owner pass, which is absent in the bootstrap build.

Do not overwrite or delete an existing result file. Use a fresh external results directory.
Keep the machine on AC power and prevent sleep. Stop unrelated disk and CPU work.

## Full run

```bash
python3 core/src/test/tools/registered-orphan-benchmark.py \
  --baseline /absolute/bootstrap-checkout \
  --current /absolute/track8-checkout \
  --baseline-cp /absolute/external/results/bootstrap.cp \
  --current-cp /absolute/external/results/track8.cp \
  --output /absolute/external/results/open.json \
  --files 10000 --samples 30 --warmup 3 \
  --disk-test-command './mvnw -pl core clean test -Dtest=IndexEngineFileBaseIdTest -Dyoutrackdb.test.env=ci'
```

The driver creates and deletes only its own outside-checkout temporary directory. Each sample
gets an independent copy of an untouched template, then a fresh JVM physically reopens it.
The driver prepares one template per build, scenario and lane. Template files are read-only.
Only copies are opened, recovered or modified. Copies use ordinary file copies, not hard links.
Templates are removed after their scenario and lane finish. Timed open excludes copying,
JVM startup and manager construction. JVM flags, build hashes, file count, create time,
template preparation seconds, per-sample copy seconds, open time, move time and raw samples
are in JSON. The template id links samples to their preparation. Preparation values repeat
across records that share a template. Component count is at least the requested count,
including genesis components. Extra collections are real configured collections, not empty
files pretending to have owners.

Samples alternate baseline/current and current/baseline. Each build and scenario gets three
discarded warmup samples per lane. Copying reads the template and writes the sample image,
warming the OS cache before open in both builds. The OS cache is uncontrolled. The tool does
not claim a cold cache experiment. Fsync is explicitly enabled. Unit-suite checkpoint and sync
overrides are not used. Clean, crashed and populated-quarantine scenarios use untouched copies
for each sample.
Crash preparation flushes the write cache and halts the JVM without storage close. Upgrade
samples always prepare the fixture with the bootstrap build. Populated quarantine has 100
retained subfolders with one 8 KiB file each.

Each scenario has three independent lanes. The no-orphan lane has only owned components.
A per-storage counter verifies exactly one open-time pass without adding a candidate. The counter
cost is one increment per pass. Reflection reads it after the timed interval. The sentinel lane
adds one orphan and verifies its retirement during open. The bulk lane starts without an orphan,
then measures a second pass over the requested number of new orphan files after timed open.
Bulk time includes content sync and executor persistence and excludes fixture file creation.
The bootstrap has no pass counter or move operation and reports -1 for bulk time.
JSON records identify every lane. Only no-orphan samples feed the acceptance comparisons.

The create time covers database creation, not the later collection fixture generation.
The disk-mode command runs once per build, serially. The example is a targeted disk-mode subset,
not the prohibited full local disk suite.

The ratio is the current median divided by the baseline median. A seeded paired bootstrap with
10,000 resamples reports a two-sided percentile 95 percent confidence interval. Acceptance
requires its upper bound to be at most 1.05 for the no-orphan lane in every scenario. The budget includes Tracks 1–6.
Smoke results never claim acceptance. Full runs require 30 samples, 10,000 files and a disk-mode
command. Inspect the raw samples for drift and outliers before accepting a result.

## Runtime estimate

The full configuration prepares 24 templates and performs 792 copied-fixture reopens.
At about 200 seconds per 10,000-file preparation, template creation takes about 80 minutes.
For planning, allow 5–10 seconds per reopen for copying, JVM startup and open, plus
20–60 seconds for each of the 132 current-build bulk samples to create and move 10,000 files.
The bootstrap has no bulk operation. These assumed costs add about 66–132 minutes and
44–132 minutes respectively. Budget roughly 3–6 hours
plus the two targeted disk-mode commands on the reference Apple M5 Max with an APFS SSD.
This is an estimate, not a measurement or acceptance result. Synced bulk creation and moves
can extend it. Refine these assumptions with recorded preparation and copy times and a timed
sample pair on the measurement machine. Preparation, copying and post-open bulk creation
stay outside the timed open.

## Smoke run

Use the current checkout and current classpath for both build arguments. Pass
`--files 32 --samples 1 --warmup 1 --smoke`. The warmup and measured sample reuse each
template through independent copies. This checks process isolation, crash reopen, populated
quarantine, all three lanes, template copying, probe compilation and result generation.
Both slots must verify the owner pass. It does not compare against the bootstrap or establish
performance acceptance.
