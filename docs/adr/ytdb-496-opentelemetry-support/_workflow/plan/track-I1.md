# Track I1: Benchmark references and optional telemetry comparison

## Purpose and decision

I1 supplies compatible reference fixtures before query execution changes.
It also plans an optional telemetry comparison that reports uncertainty and slowdown without certifying normal pull request completion.

OpenTelemetry (OTel) collects application measurements, traces and logs.
Java Microbenchmark Harness (JMH) measures the benchmark operations.
A fixture defines the data, operation, expected count and configuration.
An arm is one configuration of a fixture.
The off arm installs no telemetry listener, local capture or log hook.

The user decision date is **2026-11-16**.
That decision narrows I1 to two parts in pull request (PR) #1108.
Part 1 spans Tracks 108, 112 and 113.
Part 2 spans Tracks 109, 114 and 115.

This record applies the decision sentence by sentence without editing the accepted source documents.
Only the conflicting parts named below change.
All other accepted requirements remain binding on their owners.

## Progress and evidence

- Part 1, Track 108: delivered off fixtures in the separate `jmh-telemetry` module.
- Part 1, Track 112: delivered setup metadata and local off-against-off comparison through `jmh-telemetry/off_compare.py`.
- Part 1, Track 113: this detailed record and its sentence replacements.
- Part 2, Track 109: trusted on-against-off validation and rendering.
- Part 2, Track 114: paired execution through trusted tooling.
- Part 2, Track 115: opt-in manual workflow mode and completion isolation.

The manual telemetry mode uses its own concurrency group and a fixed eight-vCPU runner.
Trusted develop tooling checks availability before benchmark-runner allocation.
The shipped catalog has no on arm and returns non-success as `not available`.
Telemetry validation has no signing, attestation or pull-request-write permissions.
The signing job excludes telemetry, and the completion command also rejects that mode.

The delivered facts below are checked against these repository files:

- `jmh-telemetry/README.md` describes local execution, fixtures, metadata and interpretation.
- `jmh-telemetry/pom.xml` defines the separate executable benchmark module and skips installation, deployment and Central publishing.
- `jmh-telemetry/src/main/java/com/jetbrains/youtrackdb/benchmarks/telemetry/OffBenchmark.java` defines the measured calls and path checks.
- `jmh-telemetry/off_compare.py` captures evidence, pairs off revisions and renders the local report.

## Plan of work

### Part 1: Off fixtures, setup records and this record

Track 108 supplies direct Structured Query Language (SQL), translated Gremlin and fallback Gremlin paths.
Gremlin is the graph traversal language.
Each path reads deterministic, unindexed vertices at sizes of one and 10,000 rows.
The tiny query and scan use the same schema and operation.
Each thread owns an in-memory database.

Every measured call opens a read transaction, consumes and closes the result, then rolls back.
Setup checks result counts and rejects an enabled global query-result cache.
Gremlin checks validate the consumed traversal and iteration-wide translation counters outside measurement.
No invocation-level fixture or telemetry hook performs that validation.

The fixture declares `telemetryScenario=off`.
Its JMH defaults are throughput per second, one thread and three independent forks.
Its default warmup is two two-second iterations.
Its default measurement is three two-second iterations.
Those launch defaults are not the accepted reference-measurement schedule.

Reference runs explicitly select five one-second warmup iterations and ten one-second measurement iterations per fork.
They use Java Development Kit (JDK) 21 with fixed heap and garbage collector settings.
The heap is memory managed by the Java virtual machine (JVM).
The garbage collector reclaims unused heap memory.

Neither the normal compare job nor the nightly job builds this module.
The root reactor includes it, so default-job exclusion does not mean exclusion from every repository build.
Only planned telemetry mode builds it through trusted workflow tooling.
Local builds and off comparisons remain available.
Reactor selection checks live in `jmh-telemetry/tests/test_selection.py`.
Workflow isolation checks live in `.github/scripts/mandatory-benchmark-test.py`.
The workflow check reads both workflows as PyYAML values and as source text.
Comments in these workflows must not name telemetry tools or Maven.
Normal Maven commands must match an exact list in the test.
The check does not evaluate bash, the GitHub expression engine or the GitHub YAML parser.
It does not evaluate other files or programs.
Names and commands that these systems build or change when the workflow runs are outside the check.

R1: Shell code can join separate strings to form a forbidden path.
R2: A workflow expression can use `format()` to form a forbidden path.
R3: Another repository script can call telemetry tooling without naming it in the workflow.

Track 112 supplies a local base-off against candidate-off route.
The base is the reference revision. The candidate is the revision under review.
Use one compatible harness and fixed Java settings for both revisions.
A harness label declares compatibility but does not prove it.
A base without the matching fixture makes the comparison inconclusive.

Run the copied runner outside both checkouts and keep each measured checkout clean.
Keep scratch output outside the checkout.
Alternate measurement order across repetitions on the same machine.
If warmup has not stabilized, repeat both revisions as a new pass and do not mix passes.

The runner captures processor availability, model, physical memory and container limits before candidate Java starts.
A central processing unit (CPU) executes program instructions.
A virtual CPU (vCPU) is a processor allocation available to the job.
A machine label is distinct from actual hardware evidence.

The result envelope retains raw JMH data, jar and checkout revisions, dependency versions and the harness label.
Setup evidence includes query, generated data, expected count, path, scenario, transaction boundaries and cache settings.
Fixture bytecode hashes guard the workload identity.
The runner reads setup through a separate Java entry point outside measured calls.

The comparator pairs full benchmark name, every parameter, threads, forks, measurement mode and score unit.
It pairs repetitions by list index and never pools scores.
Both sides must declare the same machine type and compatible setup.
It validates jar and checkout revisions instead of trusting a cached match flag.

Missing evidence, incompatible fixtures and mixed revisions make the comparison inconclusive.
Explicit fixture-absence capture records absence rather than inventing measurements.
Rendered evidence is bounded, escaped and labelled candidate-reported.
Raw JavaScript Object Notation (JSON) files retain the complete values.

The local comparator currently accepts only off throughput at one thread with at least three forks.
It fixes the scenario to off and the throughput-loss budget to 1%.
Track 109 must generalize scenario pairing and approved budget selection for on arms.
It must not present the current local tool as an on-against-off implementation.

### Part 1 acceptance

- Cover tiny queries and scans through all three paths before later query execution changes.
- Verify exact counts and selected paths outside measurement. Measure full calls, consumption and close.
- Keep paired data, transactions and cache settings identical. Never substitute another workload.
- Keep the separate module outside both default jobs and retain default-selection tests.
- Retain complete result identity, setup metadata, hardware evidence and software versions.
- Support local compatible off comparisons before on arms exist. Report missing fixtures as inconclusive.
- Require positive finite scores and valid, consistent finite confidence intervals.
- Never replace invalid or missing uncertainty with zero. Require relative score error no greater than 10% on either side.
- Require loss above 1%, non-overlapping intervals and a breach in every repetition before reporting an off-budget breach.
- Treat disagreement, overlap, missing pairs and excessive noise as inconclusive, not success.
- Report statistical outcomes without failing the process. Keep malformed files, duplicate identities and failed Java runs fatal.
- Document reproduction and interpretation. Retain fixture, selection, metadata and comparison tests.
- Record both parts, the decision date, exact sentence replacements and unchanged binding sentences here.

### Part 2: Planned Track 109

Add an opt-in mode to the existing manually dispatched compare workflow.
Keep normal comparison as the default and preserve its reusable contract.
Follow the optional Gremlin-arm selection pattern without coupling telemetry to the translator switch.
Keep existing Gremlin both-arm behavior under the user waiver.
Give telemetry a separate concurrency group that cannot cancel a pending normal run.

Use trusted tooling from the development branch, `develop`.
Keep a closed catalog with one benchmark and one arm per entry.
Use one named JMH scenario parameter with the fixed off value `off`.
Keep execution-path selection separate from that parameter and the translator switch.

Before runner allocation, consult only the trusted catalog.
Inside the benchmark job, obtain the candidate benchmark listing as untrusted presence evidence.
Run an arm only on benchmarks that declare it.
The benchmark job has no signing rights.
Pass values through validated environment variables, never shell interpolation.

Telemetry mode compares only candidate-on against matched candidate-off at the same revision.
It does not build or measure the base revision.
Revision comparison remains in the local off route.
Require the compare job's fixed eight-vCPU machine type and reject conflicting machine-size selections.
Record actual hardware before measurement where possible.
Run paired arms sequentially on one machine and alternate order across repetitions.

Use at least three independent JVM forks per invocation.
Use five one-second warmup iterations and ten one-second measurement iterations per fork.
Lengthen unstable warmup and rerun both arms as a new pass.
Pair repetitions by index within one pass only.

Use one identity contract across parsing, trusted validation and rendering.
Retain full benchmark name, every parameter, threads, mode, score unit, revision, setup, arm, repetition and run attempt.
Preserve fork settings and distinguish repeated measurement passes.

Store raw JSON and readable reports in attempt-scoped workflow artifacts with 90-day retention.
An artifact is a retained output file.
Names identify run, producing attempt, revision, arm and repetition without overwrite.
Validation records the artifact identity and distinguishes producing and later validation attempts.

Trusted tooling derives required files from the catalog, repetition count and run identity.
The candidate manifest only cross-checks that expected set.
Reject missing and duplicate required files.
Write no results to Simple Storage Service (S3) and grant no new S3 write access.

Change only the declared scenario dimension when finding the off partner.
Keep every other fixture and measurement dimension equal.
Throughput loss is 100 multiplied by one minus scenario throughput divided by off throughput.
Do not average paths or thread counts together.
Validate positive finite scores, nonnegative uncertainty and consistent finite interval bounds.
A finite number is neither infinity nor an undefined numeric value.

For each repetition, require loss above its approved budget, non-overlapping intervals and relative score error at most 10% per side.
Relative score error is interval half-width divided by score, expressed as a percentage.
Every repetition must breach before the combined report declares a breach.
Disagreement is inconclusive. Never pool repetitions to bypass this rule.

This is a point-estimate breach, not proof that the entire loss interval exceeds the budget.
Keep proposed budgets unapproved and diagnostics separate from budget decisions.

I7, I8, I9 and I22a register measurable OTel on arms when their features exist.
I6 registers no on arm.
I22a completes the off and OTel-enabled measurement matrix.
Local-capture and combined arms belong to the second delivery through I10, I12 and I22b.
Keep them separate from OTel arms in the catalog.
Later owners register both trusted catalog entries and candidate parameter values with matching off fixtures.

If the trusted catalog has no on arm, return non-success as `not available` before allocating a runner.
A catalog arm missing from candidate evidence is inconclusive.
A missing matching off partner is also inconclusive.
Record these evidence outcomes without demanding scores for unavailable pairs.
They do not excuse missing required files or other integrity failures.
Never substitute off-against-off and report successful on-against-off execution.

Validate and render telemetry in a separate job without signing, attestation or PR-write permissions.
Exclude telemetry at signing-job level.
The completion command must also refuse telemetry mode.
Include mode in the completion predicate.
Telemetry must never create, sign, attest or comment normal signed PR completion.

Escape and bound all rendered candidate strings, including benchmark names, parameter values and metadata.
Label metadata candidate-reported and give telemetry comments their own marker.

### Part 2 acceptance

- Preserve normal mode, its completion contract and default runtime. Leave nightly, history and service configuration unchanged.
- Test benchmark-specific catalog selection, candidate listing evidence and missing arms separately from an unavailable catalog.
- Test full identity, correct denominators, approved budgets, invalid numbers, missing evidence and compatible setup.
- Test repetition pairing within one pass, alternating order, disagreement and full-pair stabilization reruns.
- Test fixed-machine rejection and telemetry concurrency isolation.
- Test trusted file expectations, manifest cross-checks, producing-attempt identity and distinct output names.
- Test escaped, bounded candidate strings and the dedicated telemetry comment marker.
- Test report-only slowdown and fatal execution, structural validation and artifact-integrity failures.
- Test separate validation-job permissions, signing-job exclusion, completion refusal and the mode-aware predicate.
- Prove telemetry cannot certify normal PR completion even with otherwise valid normal completion inputs.
- Retain raw JSON and reports. Warn on inconclusive evidence and recommend a rerun rather than reporting success.
- Use offline tests, local data and mocked service responses before merge. A mock supplies controlled service behavior.
- Document local use, later arm registration and the merge boundary.
- Keep I1 in PR #1108. Real-runner use must wait for first-delivery merge and separate execution authorization.

## Sentence classification

Locations below refer to the accepted files in this `_workflow` directory at base commit `05c31d13b3`.
An ID gives the file, starting line and sentence number on that line.
Sentence boundaries follow terminal punctuation and whitespace outside backticked text.
A continued sentence can end before another sentence starts on the same line.
Table records are included as records rather than split into cell sentences.
Quotes preserve the accepted words and punctuation, with source line wrapping changed to spaces.

REPLACED rows substitute only the quoted sentence or table record.
PARTLY REPLACED rows replace only the conflicting part and retain the named controls.
STAYS BINDING rows leave the complete quoted sentence unchanged.
SECOND DELIVERY identifies accepted work of later owners, not new I1 implementation scope.

The affected inventory below covers workflow reuse, identity, nightly policy, runner selection, fixture placement and I1 ownership.
It also includes the report-and-alert sentence and both complete correction sentences.
No row replaces an entire paragraph.

### Slowdown design: Module placement and matched baseline

| ID | Class | Owner | Replaced part | Kept part | Exact accepted sentence |
|---|---|---|---|---|---|
| `design.md:2005.s1` | PARTLY REPLACED | I1 | Put scenarios in the separate `jmh-telemetry` benchmark module, not `jmh-ldbc`. Neither default job builds it. Only telemetry mode builds it through trusted tooling. Local builds remain available. | JMH scenarios measure instrumentation cost against a matched OpenTelemetry-off baseline. | Java Microbenchmark Harness (JMH) scenarios in the existing `jmh-ldbc` benchmark module measure instrumentation cost against a matched OpenTelemetry-off baseline. |
| `design.md:2006.s2` | REPLACED | I1 | The separate module is named `jmh-telemetry` for telemetry benchmarks. The LDBC naming definition does not apply to it. | None. | The module is named for the Linked Data Benchmark Council (LDBC). |

### Slowdown design: Workflow, identity and nightly policy

| ID | Class | Owner | Replaced part | Kept part | Exact accepted sentence |
|---|---|---|---|---|---|
| `design.md:2045.s1` | PARTLY REPLACED | I1 | Use only the optional telemetry mode of the existing manual compare workflow. | Reuse the compare workflow. | Use the existing comparison and nightly workflows, `ldbc-jmh-compare.yml` and `ldbc-jmh-nightly.yml`. |
| `design.md:2046.s2` | PARTLY REPLACED | I1 | Coordinate the telemetry parser, validator, renderer and artifacts, not nightly discovery or persistence. | Parsing, rendering and comparison must agree. | Later benchmark code work must extend parsing, rendering, comparison, nightly suite discovery and `jmh-to-influxdb.sh` persistence together. |
| `design.md:2047.s2` | PARTLY REPLACED | I1 | Preserve full identity in outputs and reports, not new history. | Full benchmark identity, every parameter, thread count, mode and score unit. | Preserve full benchmark identity, all parameters, thread count, mode and score unit through stored history and reports. |
| `design.md:2055.s1` | REPLACED | I1 | Leave nightly unchanged. These thresholds are context, not I1 work. | None. | Preserve the nominal nightly thresholds of 10% run-over-run and 5% rolling-average loss. |
| `design.md:2056.s1` | REPLACED | I1 | Leave nightly history selection unchanged. | None. | Exclude the current run from history and compare only with preceding completed runs. |
| `design.md:2057.s1` | REPLACED | I1 | Keep this observation as change-record context only. It assigns no I1 work. | None. | The current nightly workflow pushes the result before the alerter runs. |
| `design.md:2057.s2` | REPLACED | I1 | Keep the self-comparison observation as context only. Do not fix it or create another issue. | None. | Its script does not exclude that run, so the run-over-run alert compares the run with itself and the average includes it. |
| `design.md:2059.s2` | REPLACED | I1 | Remove the correction goal. Do not change nightly or create another issue. | None. | Correct that script in later benchmark code work. |
| `design.md:2062.s2` | PARTLY REPLACED | I1 | Produce a visible report only. Add no nightly alert. | Budget breaches do not fail the build. | Budget breaches produce a visible report and nightly alert without failing the build. |

### Slowdown design: Machine selection and unchanged controls

| ID | Class | Owner | Replaced part | Kept part | Exact accepted sentence |
|---|---|---|---|---|---|
| `design.md:2071.s3` | PARTLY REPLACED | I1 | Use the compare job's fixed eight-vCPU machine type in telemetry mode. | Isolated Linux x86-64 execution. | Use an isolated Linux x86-64 runner with the comparison workflow's requested 8-vCPU profile. |
| `design.md:2073.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Verify actual CPU availability. |
| `design.md:2074.s1` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Use JDK 21, a fixed heap and collector, a warmed resident dataset and the same allocation for each pair. |
| `design.md:2075.s2` | REPLACED | I1 | Do not add a nightly telemetry job or verify a substitute nightly allocation. | None. | Add a matching nightly telemetry job or document an independently verified equivalent allocation. |
| `design.md:2076.s2` | REPLACED | I1 | Remove nightly history comparison work. Do not use nightly history as a reference for this mode. | None. | Existing bare-metal nightly results are not automatically comparable with this profile. |
| `design.md:2077.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Record actual hardware and VM settings. |
| `design.md:2077.s3` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Run paired arms sequentially with alternating order. |
| `design.md:2049.s1` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Pair scenario and off arms explicitly while retaining every other identity key. |
| `design.md:2049.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Validate score errors rather than coercing missing or malformed values to zero. |
| `design.md:2050.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Missing, invalid or noisy evidence is inconclusive, never a pass. |
| `design.md:2051.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Land compatible off fixtures first or use one trusted compatible harness for both revisions. |
| `design.md:2052.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | A base without a matching fixture makes the off comparison inconclusive. |
| `design.md:2053.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Never substitute another workload. |
| `design.md:2059.s3` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | The PR comparison keeps its existing 5% base-versus-candidate threshold for identical arms. |
| `design.md:2062.s1` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | The OTel performance gate is reporting-only in continuous integration (CI). |
| `design.md:2063.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | The confidence filter reports a point-estimate budget breach with non-overlapping score intervals. |
| `design.md:2064.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | It does not establish that the loss exceeds the budget at the reported confidence level. |
| `design.md:2065.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Inconclusive evidence gets a warning and rerun recommendation. |
| `design.md:2066.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Existing execution and artifact-integrity failures retain their normal CI failure behavior. |

### Benchmark mechanics: Placement, machine, reports and nightly policy

| ID | Class | Owner | Replaced part | Kept part | Exact accepted sentence |
|---|---|---|---|---|---|
| `design-mechanics.md:1255.s1` | REPLACED | I1 | Put off fixtures in separate module jmh-telemetry. Neither default job builds it. Only telemetry mode builds it through trusted tooling. Local builds remain available. | None. | Add benchmark fixtures to the existing `jmh-ldbc` benchmark module in later implementation tracks. |
| `design-mechanics.md:1256.s1` | REPLACED | I1 | The separate module is named `jmh-telemetry` for telemetry benchmarks. The LDBC naming definition does not apply to it. | None. | The module is named for the Linked Data Benchmark Council (LDBC). |
| `design-mechanics.md:1340.s1` | PARTLY REPLACED | I1 | Use the compare job's fixed eight-vCPU Linux x86-64 machine type. | Verify actual CPU availability. | Use the comparison workflow's requested 8-vCPU Linux x86-64 profile and verify actual CPU availability. |
| `design-mechanics.md:1341.s3` | REPLACED | I1 | Do not add a nightly telemetry job or verify a substitute nightly allocation. | None. | Add a matching telemetry job to the nightly workflow or document its independently verified equivalent allocation. |
| `design-mechanics.md:1343.s1` | REPLACED | I1 | Remove nightly history comparison work. Keep runner profiles distinct within each comparison. | None. | Existing bare-metal nightly results are not automatically comparable with this profile. |
| `design-mechanics.md:1380.s1` | PARTLY REPLACED | I1 | Use the local off runner and planned optional telemetry mode. Do not change the normal comparison contract. | Paired comparisons remain required. | Extend `jmh-ldbc/jmh-compare.py` for paired comparisons. |
| `design-mechanics.md:1397.s1` | PARTLY REPLACED | I1 | Wire telemetry reports into the opt-in manual compare mode only. | Compare report integration. | Wire reports into `.github/workflows/ldbc-jmh-compare.yml` and the nightly workflow `ldbc-jmh-nightly.yml` in later benchmark code work. |
| `design-mechanics.md:1398.s2` | PARTLY REPLACED | I1 | Coordinate telemetry parsing, trusted validation, rendering and artifacts. Add no nightly consumer or persistence changes. | Consumers must agree on results. | Extend parsing, rendering, nightly suite discovery and `jmh-to-influxdb.sh` persistence together with `jmh-regression-alert.py`. |
| `design-mechanics.md:1400.s1` | PARTLY REPLACED | I1 | Retain full identity in attempt-scoped outputs and reports, not new stored history. | Full benchmark identity, every scenario parameter, thread count, mode and score unit. | Preserve full benchmark identity, all scenario parameters, thread count, mode and score unit throughout stored history. |
| `design-mechanics.md:1401.s2` | REPLACED | I1 | Keep arms, repetitions, passes and producing attempts distinct in workflow artifacts. Add no S3 result writes or write access. | None. | Prevent different arms from sharing an InfluxDB series key. |
| `design-mechanics.md:1403.s1` | REPLACED | I1 | Leave nightly unchanged. These nominal thresholds are context only. | None. | Preserve the nominal 10% run-over-run and 5% rolling-average alert thresholds. |
| `design-mechanics.md:1403.s2` | REPLACED | I1 | Leave nightly history selection unchanged. | None. | Explicitly exclude the current run from history. |
| `design-mechanics.md:1404.s2` | REPLACED | I1 | Do not add previous-run or rolling-average selection work to I1. | None. | Compare only with the previous completed run and an average of preceding completed runs. |
| `design-mechanics.md:1405.s2` | REPLACED | I1 | Keep this publication-order observation as context only. It assigns no I1 work. | None. | The current nightly workflow pushes results before the alerter runs. |
| `design-mechanics.md:1406.s2` | REPLACED | I1 | Keep the self-comparison consequence as context only. Do not fix it or create another issue. | None. | Its script does not exclude that run, so the run-over-run check compares the run with itself and the rolling average includes it. |
| `design-mechanics.md:1407.s2` | REPLACED | I1 | Remove the complete correction obligation. Do not change nightly or create another issue. | None. | The script correction belongs to later benchmark code work. |

### Benchmark mechanics: Unchanged controls

| ID | Class | Owner | Replaced part | Kept part | Exact accepted sentence |
|---|---|---|---|---|---|
| `design-mechanics.md:1344.s1` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Use JDK 21. |
| `design-mechanics.md:1344.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Pin heap size and garbage collector across each comparison. |
| `design-mechanics.md:1344.s3` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Fit the warmed dataset in memory. |
| `design-mechanics.md:1345.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Record CPU model, actual available CPUs, memory, JDK build, JVM options, SDK version, revisions and all scenario parameters. |
| `design-mechanics.md:1347.s1` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Run arms sequentially on the same runner with alternating order. |
| `design-mechanics.md:1347.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Use at least 3 independent JVM forks per arm, 5 one-second warmup iterations and 10 one-second measurement iterations per fork. |
| `design-mechanics.md:1349.s1` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | If warmup has not stabilized, lengthen it and rerun both arms. |
| `design-mechanics.md:1349.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Never compare different runner profiles or merge data from different configurations into one score. |
| `design-mechanics.md:1352.s1` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Throughput loss is `100 * (1 - Qscenario / Qoff)`, where Q is operations per second. |
| `design-mechanics.md:1352.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Candidate-off uses base-off as its denominator. |
| `design-mechanics.md:1353.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | All enabled scenarios use their own candidate-off fixture. |
| `design-mechanics.md:1354.s1` | STAYS BINDING | I1, I7, I8, I9, I10, I12, I22a, I22b | None. | Entire sentence, unchanged. Local-capture work remains second delivery. | The accepted alert budgets are 1% for off, 10% for LIGHTWEIGHT mostly rejected, 10% for local-capture-only mostly rejected with OTel off, 35% for LIGHTWEIGHT emit-all SQL and Path A, 40% for LIGHTWEIGHT emit-all Path B, 30% for single-layer EXACT scans, 45% for layered EXACT scans, 15% for LIGHTWEIGHT concurrent tags and 50% for logs. |
| `design-mechanics.md:1380.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Key results by full benchmark identity, all parameters, thread count, mode and score unit. |
| `design-mechanics.md:1381.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Explicitly pair scenario and off arms while retaining every other key. |
| `design-mechanics.md:1382.s3` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Reuse the non-overlap and relative-error filters only after replacing error coercion with explicit validation and inconclusive results. |
| `design-mechanics.md:1386.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Flag a budget breach only when the point-estimate loss exceeds the budget, the score intervals do not overlap and each relative score error is at most 10%. |
| `design-mechanics.md:1389.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Overlap, missing, malformed or non-finite errors, non-positive scores, missing pairs and excessive noise make the result inconclusive. |
| `design-mechanics.md:1391.s1` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | They must not become zero error or pass. |
| `design-mechanics.md:1391.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | The 10% noise ceiling may leave a 1% off comparison inconclusive. |
| `design-mechanics.md:1408.s2` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | The PR comparison keeps its 5% base-versus-candidate threshold for identical arms. |

### Implementation plan: I1 scope and ownership

The scope line contains two sentences.
The first stays binding. Only the second is replaced.
The ownership table record is quoted as a whole, with its cell separators escaped for this table.

| ID | Class | Owner | Replaced part | Kept part | Exact accepted sentence or record |
|---|---|---|---|---|---|
| `implementation-plan.md:156.s1` | STAYS BINDING | I1 | None. | Entire sentence, unchanged. | Establish compatible off fixtures before execution-path changes. |
| `implementation-plan.md:156.s2` | PARTLY REPLACED | I1 | Assign off fixtures and optional same-revision on-against-off comparison. Add no history handling or nightly fix. | Validated comparisons and complete report identity. | Add validated comparisons, complete report identity, history handling and the accepted nightly self-comparison fix. |
| `implementation-plan.md:539.s1` | PARTLY REPLACED | I22a | I1 provides no history handling. Result identity stays in report outputs and attempt-scoped workflow artifacts. Any later history work needs a separate decision. | Matched off denominators, confidence rules and full result identity remain binding. | Keep matched off denominators, confidence rules, full result identity and history handling. |
| `implementation-plan.md:1495.s1` | PARTLY REPLACED | I1 | Assign off fixtures, comparison validation, result identity and the optional compare mode to I1. Remove history and nightly work. | Fixtures, validation, identity and compare tooling. | \| Benchmark fixtures, comparison validation, report identity, workflows, history and nightly self-comparison \| I1 \| |
| `implementation-plan.md:1497.s1` | SECOND DELIVERY | I10, I12, I22b | None. | Entire record remains accepted for its named second-delivery owners. | \| Accepted local-only and combined measurements and associated validation \| I10, I12, I22b \| |

## Invariants and scope boundary

The normal comparison threshold remains 5% for identical base and candidate arms.
The off-fixture budget remains 1% and is distinct from that normal-job threshold.
Neither number silently replaces the other.
Proposed budgets remain unapproved.

Identity changes only its output destination, not its dimensions.
Retain full benchmark name, all parameters, thread count, mode and score unit.
Also retain revision, setup, arm, forks, repetition, measurement pass, run and producing attempt.
Validation retains artifact identity and any separate validation attempt.
A scenario may pair only with its own off fixture while every other key stays equal.

Actual CPU availability, fixed Java settings, alternating order, explicit off pairing and uncertainty validation remain binding.
Compatible references remain required before later execution changes.
The retained rows name the exact sentences and locations instead of replacing their paragraphs.

This track changes documentation only.
It changes no code, workflow, script, build, nightly storage, exclusion list, InfluxDB service, dashboard or alert rule.
Nightly self-comparison is outside I1 and requires no new issue.
Tracks 109, 114 and 115 implement Part 2.
No pointer is added to `implementation-plan.md` because this standalone record supplies the full sentence inventory.

## Validation and recovery

Check every quote against its named source location, including continued sentences.
Check the new record with the repository writing checker.
Keep verbatim quotes exact even if the checker advises simpler wording.
No Maven verification applies to this documentation-only track.
If a future amendment changes one rule, amend only that sentence's classification and preserve its binding neighbors.

## Base commit

`05c31d13b3033e477b36717052e8d9aa26d42852`
