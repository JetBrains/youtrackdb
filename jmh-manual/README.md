# Manual write benchmark

This module runs only by hand. Normal builds compile it but never execute it or publish it.
It uses one embedded instance and fresh disk databases in a system temporary directory.
Trial teardown drops the databases and removes that directory.
Developer-machine results are working checks, not performance measurements.

The comparison uses two versions: the commit before the WAL rewrite on this branch, called the base,
and the finished change. WAL means write-ahead log, which records changes before data pages reach disk.
Use the same workload, heap, JVM flags and effective product settings on both versions.

## Run

Use JDK 21 or later. Build the whole reactor from the repository root:

```bash
./mvnw package -DskipTests
JAR=$(find jmh-manual/target -maxdepth 1 -name 'youtrackdb-jmh-manual-*.jar' ! -name '*sources*' ! -name '*javadoc*' ! -name 'original-*' | head -1)
JAVA_OPTS=(-Xms4g -Xmx4g -XX:+UseG1GC)
for module in java.lang java.lang.reflect java.lang.invoke java.io java.nio java.util java.util.concurrent java.util.concurrent.atomic java.net sun.nio.cs sun.security.x509; do
  JAVA_OPTS+=("--add-opens=java.base/$module=ALL-UNNAMED")
done
JAVA_OPTS+=(--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED)
java "${JAVA_OPTS[@]}" -jar "$JAR" WriteWorkload -t 2 -f 1 -wi 3 -w 10s -i 3 -r 10s -prof gc
```

These module-access flags match the LDBC benchmark launcher.
The heap is pinned at 4 GiB and the garbage collector is G1.
Increase the heap identically on both versions when needed.
The minimum heap is database count times 256 MiB, plus 512 MiB and four large payloads.

The base can queue 256 MiB of records per database.
Allow further working memory for your workload, and leave physical memory for the disk cache.

Always use a fork. Nonforked parameter sweeps share product configuration and are not supported.

For short working checks, replace the iteration options with `-wi 1 -w 1s -i 1 -r 1s`.
For idle-only, also use `-t 1 -p busyDatabases=0 -p busyThreads=0 -p quietThreads=0`.
A busy-only case uses `-t 1 -p databases=1 -p quietThreads=0`.
Keep the temporary filesystem large enough for setup and WAL files.
An interrupted JVM can leave its printed temporary directory behind.

## Parameters and validation

Pass each parameter with `-p name=value`. Only JMH Throughput mode is supported.

| Parameter | Default | Meaning and bounds |
| --- | --- | --- |
| databases | 2 | Positive number of open disk databases, including idle databases |
| busyDatabases | 1 | Busy databases, from 0 through databases, assigned writers round-robin |
| busyThreads | 1 | Nonnegative count of writers that commit without a pause |
| quietThreads | 1 | Nonnegative count of writers on one separate quiet database |
| encryption | false | Enable encryption with a fixed key for throwaway data only |
| recordBytes | 256 | Busy payload bytes, from 1 through 8388608 (8 MiB) |
| quietPaceMicros | 10000 | Intended interval per quiet writer, from 1 through 60000000 microseconds |
| fixedEngineThreads | 6 | Nonnegative bound on engine threads outside the two elastic pools |
| extra | empty | Semicolon-separated `youtrackdb.key=value` settings |
| fileEvents | true | Record file operations with Java Flight Recorder (JFR) |

Set JMH `-t` to the sum of writer counts, or 1 for idle-only.
Every busy database needs at least one writer. Busy writers need at least one busy database.
Quiet writers need a database that no busy writer uses.

Each writer owns a traversal, transaction and fixed record. Quiet payloads are 64 bytes.
Two pre-generated random payloads alternate, with fixed seeds 42 and 43.
The record set does not grow during measurement.

### Dedicated cores

The admission rule is a thread count, not a CPU utilization estimate:

```text
engine thread bound = fixedEngineThreads + general pool pin + I/O pool pin
minimum dedicated cores = busyThreads + quietThreads + engine thread bound
```

The fixed inventory includes two scheduler threads, one WAL flush task and one WAL write task.
It also includes one fuzzy checkpoint worker and one write-cache flush worker.
A fuzzy checkpoint flushes data pages while transactions continue.

This inventory includes lazy workers.
Set `fixedEngineThreads` to the verified inventory for the version under test.
It is a runtime parameter so another engine version needs no benchmark source edit.
JVM service threads, such as cleaners and compilers, do not belong in this engine inventory.

The pool keys are `youtrackdb.executor.pool.maxSize` and `youtrackdb.executor.pool.io.maxSize`.
Both pool pins default to 4, so the default engine bound is 14 threads.
The product can use a smaller actual maximum for larger positive pins.
Admission conservatively counts the full pins, not just workers already created at setup.

Both effective pool pins must be positive. Zero and negative CPU-derived sizes are rejected.
The input-output pool must stay enabled with `youtrackdb.executor.pool.io.enabled=true`. Disabling it delegates to an unbounded JDK pool and is rejected.
Extra settings can override the pool pins, but the same checks apply to their effective values.

One busy plus one quiet writer needs at least 16 dedicated cores.
Eight busy plus one quiet writer needs at least 23.
Rent a server with enough dedicated cores and expose them to the JVM.
The 8-core server type cannot run this benchmark with the default pins.

Setup rejects a larger requested budget and prints the writer count, engine bound,
available processor count and minimum core count.
The recorded setup thread growth is diagnostic only. It also includes JVM service threads.

Warm-up and measurement iteration lengths must be positive multiples of the effective WAL
commit timeout, which must itself be positive.

For example, use `-p 'extra=youtrackdb.storage.wal.maxSize=512;youtrackdb.storage.diskCache.bufferSize=128'`.
Extra settings override pins and can express disk pressure.
Use configuration keys that the product version under test supports.
Overrides cannot move WAL files outside the temporary databases.

Unknown keys remain system properties, with effective values reported as `not available`.
Check that the running product recognizes every key needed for a comparison.
A mismatch between requested and effective values fails setup.
The effective WAL path must be null, meaning the database directory.

## Product settings and warm-up

All settings use system properties before product initialization on both versions.
A WAL segment is one numbered log file. Segment rotation starts the next numbered file.
Both segment-size keys, `maxSegmentSize` and `minSegSize`, pin WAL segments to 120 MiB.
The disk free-space limit is explicitly 960 MiB, not an initialization-dependent derived default.

Fsync is the file synchronization operation that asks the operating system to persist writes.
Other pins are fsync on, disk cache 256 MiB, WAL commit timeout 1000 ms, segment interval 10 minutes,
fuzzy checkpoint interval 300 seconds, keep-single-segment on, and WAL size limit -1.

A fuzzy checkpoint lets older WAL records be reclaimed after data-page flushes.
WAL vacuum reclaims obsolete WAL segments.

WAL statistics, direct memory tracking and memory profiling are off.
The write cache share is 5 percent and its flush interval is 25 ms.
WAL files stay in each database directory.
Extra settings can change these pins, so compare the recorded effective values.
A commit does not wait for its own fsync. The benchmark preserves product durability behaviour.

Warm-up must cover at least one segment rotation and one WAL vacuum per busy database.
Use the printed `DIRECTORY path` to inspect each busy database directory, `bench0`, `bench1`, and so on.
Segment names have the form `<base>.<number>.wal`.
Observe a new higher-numbered segment file to confirm rotation.
Observe the disappearance of an older segment while the database stays open to confirm vacuum.

List these files repeatedly during warm-up, for example with
`find <printed-directory>/bench0 -maxdepth 1 -name '*.wal' -printf '%f\n'`.
Do not count teardown deletion as vacuum.
Extend warm-up until both events occur for every busy database.

A quiet database is not expected to rotate a segment within one measurement.
The short working check does not establish this warm-up condition.

## Timing and schedule boundaries

Busy service time covers one whole write transaction, including commit, not an isolated commit call.
Quiet service time has the same boundary.
Quiet response time runs from the intended start through commit completion.
Quiet schedules advance from intended starts, not completion times.
Overdue transactions catch up without skipping slots inside the timed interval.

JMH also invokes the workload during synchronization before and after the timed interval.
Each worker observes the measurement flag at invocation entry and during quiet waits.
Quiet waits poll at most every 100 microseconds, subject to operating-system scheduling.
The worker anchors a nominal window of the configured iteration length at its first flag observation.

JMH supplies flags, not transition timestamps, so this anchor can lag the actual transition.
The first observation can also lag if a transaction is already running at the transition.
This boundary uncertainty matters for very short iterations and is not transaction latency.

Future intended starts survive the transition, even if the invocation began waiting before it.
Only slots before the observed window are discarded. Their accumulated delay is not charged.
The first retained slot stays on the original schedule grid. If it lies in the future, the writer
waits for it before starting a transaction. A quiet writer stops waiting without a transaction
when it observes the measurement stop flag or reaches the nominal window end.

Membership is checked after waiting, when the transaction starts.
A start needs an active JMH measurement flag and must precede the nominal window end.
Every such committed transaction contributes exactly one role counter and each applicable histogram,
even if it completes after the end. Completion time is not clipped.
Response time cannot begin before the observed window start.

Quiet missed-start counts use intended starts strictly before the nominal window end:
- `lateExecutedStarts`: starts that ran in the window, but whose preceding measured transaction
  ended after their intended start.
- `unstartedStarts`: remaining scheduled slots inside the window whose transactions did not start
  in it, including slots that elapsed while the last transaction was still running.
- `missedStarts`: the sum of those two disjoint counts.
- `scheduledStarts`: all intended slots in the window, including on-time starts.

Operating-system wake-up delay remains in response time, but is not by itself a missed start
when no preceding transaction was running or queued.
Under severe overload, missed starts approach the window length divided by pace.
Unstarted transactions have no latency sample. Read missed starts alongside the percentiles.

## Output reference

Representative records follow. Values are illustrative. Lists can contain more settings and JVM flags.

```text
DIRECTORY path=/tmp/ytdb-write-jmh-example
JVM 21.0.9 args=[-Xms4g, -Xmx4g] maxHeapBytes=4294967296 gc=[G1 Young Generation, G1 Old Generation]
WORKLOAD databases=2 busyDatabases=1 busyThreads=1 quietThreads=1 recordBytes=256 quietPaceMicros=10000 encryption=false fixedEngineThreads=6 engineThreadBound=14 availableProcessors=32 minimumCores=16 setupThreadGrowth=11
CONFIG youtrackdb.executor.pool.maxSize=4
LATENCY MEASUREMENT busy service n=16000 p50_us=60.0 p95_us=80.0 p99_us=100.0 max_us=10000.0
LATENCY MEASUREMENT quiet service n=100 p50_us=80.0 p95_us=120.0 p99_us=160.0 max_us=300.0
LATENCY MEASUREMENT quiet response n=100 p50_us=140.0 p95_us=180.0 p99_us=350.0 max_us=1300.0 missedStarts=0 lateExecutedStarts=0 unstartedStarts=0 scheduledStarts=100
WriteWorkload.write:busyCommits   thrpt  16000.0  ops/s
WriteWorkload.write:quietCommits  thrpt    100.0  ops/s
```

`DIRECTORY` gives the filesystem path, with no numeric unit.
`JVM` records the runtime version, JVM arguments, maximum heap in bytes and garbage collector names.
`WORKLOAD` records database and thread counts, payload bytes, pace in microseconds and encryption state.
Its core budget and growth figures are thread or processor counts, not utilization percentages.

`CONFIG` records effective public settings in their product units, as described above for the pins.
Hidden values are redacted. A key absent from the running product reports `effective=not available`.

JMH secondary scores `busyCommits` and `quietCommits` report committed transactions per second separately.
The displayed JMH unit is `ops/s`, with one operation here meaning one counted transaction.
The primary JMH score counts harness invocations, including idle waits. Do not use it as transaction throughput.

Every `LATENCY` line labels warm-up or measurement, role and sample count `n`.
Latency distributions and schedule counts aggregate all writers of one role for one iteration.
They reset each iteration. They are not trial-wide percentiles.
Median, 95th and 99th percentiles and maximum use microseconds.

Percentiles use all counted transactions, not JMH sample-time sampling.
Bounded histograms round percentiles upward by at most 1/64 of the bucket magnitude.
Maxima are exact. A zero sample count reports `not available`, never zero latency.
Idle-only has zero commits and no latency samples.

Use `-prof gc` to enable the standard JMH garbage collection (GC) profiler.
Its figures describe the whole fork, not a writer role:
- `gc.alloc.rate` is the allocation rate in MiB per second.
- `gc.alloc.rate.norm` is allocated bytes per harness invocation, not per committed transaction.
  Idle waits and the mix of roles affect its denominator.
- `gc.count` is the number of garbage collections in the profiler interval.
- `gc.time` is their accumulated elapsed time in milliseconds.

JMH prints profiler figures, including allocation, only for measurement iterations.
The profiler interval is wider than the collector window and includes fixture work.
For example, it includes the collector report and can include seeding.
Keep profiler figures separate from the collector figures below.

The command also works on a build with `WriteWorkload` but no collector.
That build gives throughput, latency and profiler results.
It cannot supply the startup, process or database figures in the collector records.
Only builds with the collector pay the sampling cost and, by default, the JFR recording cost.
`-p fileEvents=false` removes only the JFR cost.
A difference in throughput, CPU time or allocation can come from these costs.

### Iteration metrics

Keep the JMH fork headers with the console records. Collector records are per fork.
The final JMH table merges results from measured forks, including allocation and throughput.

`METRICS_STARTUP` reports engine direct bytes after all databases open and before seeding.
Each iteration has one `METRICS` record and one `METRICS_DATABASE` record per database.
The collector does not split process totals by writer role. It also reports idle-only iterations.

`phase` distinguishes warm-up from measurement. `iteration` counts across both phases in each fork.

The observer samples only while JMH marks work as timed. It discards reads that cross the stop flag.
It aims for the reported interval in milliseconds. Operating system scheduling can delay samples.

The collector window runs between the start times of the first and last accepted samples.
It excludes setup and teardown and can omit time at the edges of timed work.
`windowNanos` measures its length with a monotonic clock.
One accepted sample can give gauges but cannot give a delta.

A sample is not an atomic snapshot. The collector reads counters and then gauges.
`counterReadMaxNanos` bounds the time to finish reading counters after either endpoint starts.
Process CPU, GC and product deltas are differences of those readings, not clipped to the window.

`processCpuNanos` is processor time in nanoseconds for the whole process.
`gcCount` counts collections and `gcTimeMillis` gives their reported elapsed time in milliseconds.
`gcTimePercent` divides that time by the window length.

`heapUsedObservedMaxBytes` is the largest sampled value of total used Java heap, in bytes.
Usage can be higher between samples. This observed maximum is a lower bound on the real peak.
It cannot prove compliance with a hard memory limit.

Engine direct memory, WAL-owned direct memory and retained WAL segments use bytes.
Each gives the observed maximum and last sample.

The collector tracks registration of the global metrics bean in Java Management Extensions (JMX).
The direct memory gauges require a known, unchanged registration across samples and at reporting.
If the engine restarts inside the JVM during an iteration, JMX notifications can arrive late.
In that case, the maximum and final values can come from different registrations.
The benchmark fixture never restarts the engine during a trial.

`RetainedWalBytesScanEstimate` fields sum logical file sizes, not disk blocks.
The scan reads sizes at different times. Concurrent vacuum and growth can bias it in either direction.

The collector reads named product metrics through JMX.
It reports changes in cumulative counters, not their lifetime totals.
Fields ending in `Nanos` use nanoseconds. Other counter fields count events.

`DataFileFsyncCount` counts force attempts on data files that use asynchronous input and output.
The product counts attempts before the call, including attempts that fail.
The counter excludes ordinary-channel files.
The per-database counters describe waits for WAL queue space or blocks, and WAL vacuum runs.
The removal count includes only runs that removed at least one segment.

The current product does not publish `WalDirectMemoryBytes`, `WalBlockWaitCount` or
`WalBlockWaitTimeNanos`.
The collector reads these metrics where the product version publishes them.

Databases with the same name share counters. The workload gives each database a distinct name.
An engine restart can reset counters. A change of registration or a counter decrease invalidates the delta.

JFR timestamps its own markers for the first and last accepted samples.
It counts only events wholly inside those bounds on numbered WAL files in the benchmark databases.
It reports write bytes and total force duration in nanoseconds.
It uses no duration cutoff and records no file-event stack traces.
Neither a JFR force event nor the product attempt counter proves that data reached durable storage.
Use `-p fileEvents=false` for a comparison without the cost of file event recording.

A zero is a measured value at the scope and resolution of its field.
For example, a short collection can leave the millisecond GC time unchanged.
A zero file event count means the recording has no qualifying event.
`not available` means the collector cannot give a reading. It does not mean zero.

File figures are unavailable if the collector cannot read a usable window.
The collector accepts the OpenJDK JFR 2.1 file format.
It requires a dump with exactly one chunk, a physical block of the recording.

The collector reports `fileEvents=rotated` when data after the first chunk passes the framing check.
Chunk rotation is the usual cause.
A damaged dump can also produce this status.
In both cases, all four file figures are `not available`.

Starting or stopping another recording can rotate chunks in the benchmark fork.
To make size-based rotation less likely, try adding
`-XX:FlightRecorderOptions=maxchunksize=64m` to `JAVA_OPTS` before launch.
Keep this setting the same across compared runs.

A loss report anywhere in a single-chunk dump makes file figures unavailable.
All accepted markers must be present in that chunk.
This check cannot detect silent loss within the chunk if all accepted markers survive.
