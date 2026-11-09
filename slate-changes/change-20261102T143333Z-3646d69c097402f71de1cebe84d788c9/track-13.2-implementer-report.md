# Track 13.2 implementer report

## Status and split request

Partial implementation at the required size stop. Do not mark Track 13.2 complete.
The completed boundary contains the shared counter lifecycle, goal 8 benchmark and smoke tests,
including the PF-2 overlap proof. Goal 9 is not implemented.

The code adds approximately 527 nonblank lines, including comments and imports
(570 physical inserted lines). The four new files contribute 519 nonblank lines.
The existing component gains 8 lines.
The shared lifecycle, actual-coordinate checks, generated JMH wiring checks, failure handling
and deterministic overlap test consume the limit before record workloads fit.
Adding those workloads and their required tests would exceed about 520 lines substantially.
No unfinished goal 9 entry point is exposed.

Proposed split:
1. Review and retain this coherent shared-state plus goal 8 boundary.
2. Assign a follow-on code scope for the goal 9 writer benchmark, record workload and smoke tests,
   before the runner and measurement work proceeds. The orchestrator must choose the track
   structure because the parent already has five children.

## Reconciliation

All initial checks passed, exit 0.
HEAD was 87bbc32a6ca1dd9f54aa842851df70acd6e8097d.
There were no tracked changes outside slate-changes/.
After git fetch origin develop, origin/develop remained
b4272b120b5bc6b212cf68e0b925c0257c9a115d.

## Files

Under jmh-ldbc/src/main/java/com/jetbrains/youtrackdb/benchmarks/pagelock/:
- IterationCounters.java: boundary snapshots, expected-side validation, JSON iteration records,
  aggregate measured protocol accounting and optional 99 percent validation.
- PageLockBenchmarkState.java: immutable seed preparation, disposable run copy, six controlled
  patterns, actual page-coordinate metadata, one component for each writer and trial cleanup.
- ReaderWriterBenchmark.java: asymmetric group, default three readers and one writer,
  separate read and commit secondary results, one fork. Exceptions escape the benchmark.
- PageWorkloadComponent.java: package-local observer overload for tests to install the page-set
  pause hook on the atomic operation. The ordinary commit passes no observer.

Under jmh-ldbc/src/test/java/com/jetbrains/youtrackdb/benchmarks/pagelock/:
- BenchmarkWorkloadTest.java: five smoke tests covering all six configured page shapes,
  configurable partial sharing, actual coordinates, independent copies, component identities,
  measured-only protocol accounting, expected-side rejection, error propagation, generated
  subgroup ordering and overlapping reservations.

Only this report is added under slate-changes/. No README, POM, core or server source changes.

## Behavior and output contract

The goal 8 run uses exactly one asymmetric group and one fork. Override thread counts with
-tg writers,readers. The generated JMH method order is commit,read. Total -t must equal their sum.
Readers use writer0's component in SHARED and DISJOINT. DIFFERENT uses the reader component.
Additional writers have distinct components. The coordinates in the output identify this
topology rather than implying every writer shares the reader component.
The SHARED default changes every reader page. sharedPages selects an exact smaller overlap.
Each invocation commits a changing value and returns only after success.

PAGELOCK lines contain JSON objects with string values. Records include iteration and phase,
page counts, component relationship, overlap count, thread counts, actual reader and writer
coordinates, physicalBytes at setup and the 17 counter values.
Missing measurements stay the literal "not available". Partial or wrong-side availability
fails setup. Select develop mode with -Dpagelock.expectUnavailable=true in the benchmark JVM.
The data root is pagelock.data. Its default is target/pagelock-data.
Seed keys include generator version, total page count and writer count.
Only boundary hooks sample probes. No probe runs inside either measured benchmark method.
largestExcess retains the 13.1 lifetime-high-water interpretation.

IterationCounters excludes warmup and ZERO_CHANGE from aggregate protocol accounting.
Its share(true) rejects an empty or below-99-percent mutating aggregate on the branch.
Goal 8 does not enforce the goal 9 validity rule. A goal 9 caller must invoke that check at
the end of its run and emit the aggregate.

## Verification commands and results

All final commands below exited 0.

- ./mvnw -pl jmh-ldbc spotless:apply
- ./mvnw -pl jmh-ldbc -am clean package -P 'coverage,!docker-images'
  -Dtest='com.jetbrains.youtrackdb.benchmarks.pagelock.BenchmarkBaseTest,com.jetbrains.youtrackdb.benchmarks.pagelock.BenchmarkWorkloadTest'
  -Dsurefire.failIfNoSpecifiedTests=false
  Result: 9 tests, 0 failures, 0 errors, 0 skipped. The final branch report measured
  BenchmarkBaseTest at 1.772 seconds and BenchmarkWorkloadTest at 0.358 seconds.
  No upstream tests matched this selector. No local integration tests ran.
- ./mvnw -pl jmh-ldbc spotless:check
- ./mvnw package -DskipTests
  Result: all 24 reactor entries SUCCESS.
- python3 .github/scripts/coverage-gate.py --line-threshold 85 --branch-threshold 70
  --compare-branch 87bbc32a6ca1dd9f54aa842851df70acd6e8097d
  --coverage-dir <original-worktree>/.coverage/reports --output-md <action-folder>/coverage.md
  Result: changed-line coverage 90.5 percent (114/126), branch coverage
  90.5 percent (67/74). Exact report set: youtrackdb-jmh-ldbc/jacoco.xml only.
  The gate ran unchanged in a temporary detached worktree at verification snapshot
  17cacaf829d31b57de3d6f10836c11a512ae3cb1. That snapshot contains the staged source tree
  and has the required start boundary as parent. It is not a branch commit.
  This lets the unmodified gate see the complete diff before the final branch commit.
  Existing coverage reports were preserved in the action folder before the first run.
  Generated reports were removed before the final measured run. Prior reports are restored
  during cleanup, so the final .coverage/reports directory is not this measurement's evidence.
- Develop worktree at b4272b120b:
  cd /tmp/ytdb-develop-13.2 &&
  ./mvnw -pl jmh-ldbc -am package
  -Dtest='com.jetbrains.youtrackdb.benchmarks.pagelock.BenchmarkBaseTest,com.jetbrains.youtrackdb.benchmarks.pagelock.BenchmarkWorkloadTest'
  -Dsurefire.failIfNoSpecifiedTests=false -Dpagelock.expectUnavailable=true
  Result: 9 tests, 0 failures, 0 errors, 0 skipped. PF-2 explicitly reports "not available".
  BenchmarkBaseTest took 0.146 seconds and BenchmarkWorkloadTest took 1.208 seconds.
  All eight pagelock source files, including the 13.1 sources, were checked byte-identical.
  No install or deploy was run. The temporary worktree was removed and pruned.
- Short JMH wiring runs on both sides, using the JVM opens from the existing POM:
  .*ReaderWriterBenchmark.* -f 1 -wi 0 -i 1 -r 100ms -tg 2,1
  -p pattern=SHARED -p pagesPerRead=1 -p pagesPerCommit=2 -foe true
  Result: separate commit and read secondary results. Parsed JSON reports two writers and
  one reader. All 17 branch counters are available. All 17 develop counters are the literal
  "not available". Develop opened a copy of the exact branch-generated controlled-page seed
  and committed updates successfully. This is wiring evidence, not performance evidence.
  It does not establish record-seed compatibility for the deferred goal 9 workload.
- git diff 87bbc32a6c -- core server: empty.
- git diff --cached --check: exit 0, no whitespace errors.

Integration searches found no candidate classes. The package-proximity search covered
jmh-ldbc/src/test/java/com/jetbrains/youtrackdb/benchmarks and its pagelock child for IT*.java,
*IT.java, *ITCase.java and *IntegrateTest.java. The reference search used those patterns over
the whole module test root for IterationCounters, PageLockBenchmarkState,
ReaderWriterBenchmark and PageWorkloadComponent. Both sets were empty.

## Failures found and resolved

Initial testing expected an observer exception directly. AtomicOperationsManager wraps it
in StorageException and puts the storage in its error state. The test now checks the cause
and injects failure only after all successful work on that storage. An intermediate overlap
check exposed that error-state reuse. The final PF-2 test pauses both writers after locks,
observes exactly 2 x 34 reserved pins, releases them, verifies both commits PAGE_SET and
verifies reservations return to zero. A shared-component mutation fails the file-id assertion.

One verification command accidentally ran develop mode in the branch working directory.
That run failed expected-side assertions. It changed no source. The corrected command used
the detached develop worktree, and the final branch coverage run also passed afterwards.
No observed red result remains.

## Remaining scope, risks and downstream effects

Goal 9 remains entirely unimplemented: single-record and 256 MiB record workloads, near-limit
writer-only entry point, one/four-writer validation, distinct collection ids and sessions,
string-key settings before engine start, exact small-cache settings, capacity check,
record-page bounds, seed preparation entry point for the full data set and final run validity.
The record workloads and their tests need a new approved implementation action.
The near-limit capacity issue held for 13.3 is unchanged.

Track 13.3 must not begin measurements from this partial boundary. It can reuse PAGELOCK
records and IterationCounters, but needs the deferred goal 9 entry point and final aggregate
output contract first. The runner must preserve failures and use -foe true.
Track 13.4 can reuse the base component and boundary counter accounting. Prepared apply-step
work remains separate. No change to its scope is implied.
Track 13.5 owns all run instructions and documentation, including the asymmetric -tg order
and the additional-writer topology described above. No README section was added here.

GNU cp remains the 13.1 copying dependency. Every run closes its database before deleting
its copy. One setup process owns each seed path. New smoke tests use small controlled-page
files rather than the full record seed. Windows cost has not been measured in this action.
The bounded database smoke methods have 60-second timeouts. CI must confirm their Windows
runtime, given the earlier 61-to-70-second BenchmarkBaseTest observation.

The only planned-scope deviation is the explicitly permitted size stop and split request.
No production behavior changed. No performance conclusion is claimed.

## Fix round 1

The revised Track 13.2 scope is implemented. It covers shared state, goal 8, the PF-2
reservation overlap check and smoke tests. Goal 9 belongs to Track 13.6 and remains untouched.
This scope decision supersedes the earlier split request in this report.

### Review fixes

- PF-1: PageWorkloadComponent.read now starts a read-only atomic operation and deactivates it
  in finally. It still calls executeOptimisticStorageRead with the same optimistic and fallback
  page loaders. It does not enter calculateInsideAtomicOperation or commit a zero-change write.
  The six-case smoke test verifies warm optimistic reads and zero changes to every commit outcome
  counter on the branch. Develop reports those counters as not available.
- PF-2: Every goal 8 PAGELOCK iteration record includes pageSetShare, computed from that
  iteration's PAGE_SET divided by all mutating commit outcomes. ZERO_CHANGE is excluded.
  Develop and iterations with no mutating commit report the literal not available.
  A fallback-only iteration reports 0.0. No new goal 8 validity rejection was added.
- TQ-1: Invalid page coordinates are injected through ReaderWriterBenchmark.commit and must
  propagate as IllegalArgumentException. The observer injection also puts the real storage into
  its error state. A subsequent benchmark commit must propagate StorageException rather than
  return normally and count as throughput.
- TQ-2: The benchmark read entry point runs against opened SHARED, DISJOINT and DIFFERENT
  states with both 1 and 32 pages. Assertions check actual file ids, every actual page index,
  page counts, component relationships and the optimistic path. One bounded test owns all six
  small cases and closes every state in finally.

Files changed in this round:

- jmh-ldbc/src/main/java/com/jetbrains/youtrackdb/benchmarks/pagelock/PageWorkloadComponent.java
- jmh-ldbc/src/main/java/com/jetbrains/youtrackdb/benchmarks/pagelock/IterationCounters.java
- jmh-ldbc/src/main/java/com/jetbrains/youtrackdb/benchmarks/pagelock/PageLockBenchmarkState.java
- jmh-ldbc/src/test/java/com/jetbrains/youtrackdb/benchmarks/pagelock/BenchmarkWorkloadTest.java
- This append-only implementer report.

No production core or server source, POM, README or other change record was modified.
The approximate counted fix size is 109 nonblank changed Java lines, including comments and
imports, with whitespace-only changes ignored: 98 added and 11 removed. The whole track against
87bbc32a6ca1dd9f54aa842851df70acd6e8097d is about 619 counted lines: 616 added and 3 removed.
Excluding comment-only lines gives about 99 for the fix and 592 for the whole track.

### Verification

All final verification commands exited 0. No install or deploy command ran.
The fully qualified test selector used below is:

`com.jetbrains.youtrackdb.benchmarks.pagelock.BenchmarkWorkloadTest,com.jetbrains.youtrackdb.benchmarks.pagelock.BenchmarkBaseTest`

- `./mvnw -pl jmh-ldbc spotless:apply`: exit 0. A final apply also exited 0 and changed no files.
- `./mvnw -pl jmh-ldbc -am package -Dtest=<selector> -Dsurefire.failIfNoSpecifiedTests=false`:
  exit 0. Both classes passed against reactor-built local core.
- Removed .coverage/reports before the measured run, then ran
  `./mvnw -pl jmh-ldbc -am clean package -P 'coverage,!docker-images' -Dtest=<selector> -Dsurefire.failIfNoSpecifiedTests=false`:
  exit 0. BenchmarkBaseTest ran 4 tests and BenchmarkWorkloadTest ran 7 tests. All 11 passed
  without failures, errors or skips. No upstream module ran selected tests.
- Develop compatibility used a temporary detached worktree at b4272b120b. All pagelock main
  and test sources were copied byte-for-byte, with no build-file changes. Ran
  `./mvnw -pl jmh-ldbc -am package -Dtest=<selector> -Dsurefire.failIfNoSpecifiedTests=false -Dpagelock.expectUnavailable=true`:
  exit 0. All 11 tests passed. PF-2 printed not available. Recursive comparisons of both source
  directories returned 0. The worktree was removed and worktree metadata was pruned.
- `./mvnw spotless:check`: exit 0 across the full reactor.
- `./mvnw package -DskipTests`: exit 0, all 24 reactor entries succeeded.
- The unmodified coverage gate ran in a temporary detached verification worktree at snapshot
  b90a67b01833ef55659ab7b8c8752adade5c0c7b. This snapshot captured the source changes before
  the branch commit, with the current HEAD as parent. It is not a branch-history commit.
  Command: `python3 .github/scripts/coverage-gate.py --line-threshold 85 --branch-threshold 70 --compare-branch 87bbc32a6ca1dd9f54aa842851df70acd6e8097d --coverage-dir <original-worktree>/.coverage/reports --output-md <action-folder>/coverage.md`.
  Exit 0. Changed-line coverage is 92.2 percent (142/154). Changed-branch coverage is
  92.2 percent (83/90). The exact report set is youtrackdb-jmh-ldbc/jacoco.xml only.
  Its pagelock package contains the changed production files. The two selected Surefire XML
  reports contain 4 and 7 passing tests. No surplus upstream coverage reports exist.
  The verification worktree was removed and pruned. A later source edit changed only the helper
  comment to describe the expected failed benchmark commit. It changes no executable behavior.
- `git diff 87bbc32a6c -- core server`: empty, scope assertion exit 0.
- `git diff --check`: exit 0.
- Integration package-proximity searches under the benchmark package and reference searches
  across the module test root found no IT*.java, *IT.java, *ITCase.java or *IntegrateTest.java
  candidates for the changed classes. No integration tests ran locally.

### Mutation evidence

A scratch Java runner selected individual existing JUnit methods. Mutated classes were compiled
with javac -proc:none into separate scratch directories and placed before the normal reactor
classpath. JVM options came from the module argLine. Repository source files were not mutated.

- Unmodified read and commit boundary tests each ran one test, exit 0.
- Reader hard-coded to page zero: exit 1, expected 32 samples but received 1.
- Reader uses writers[0] instead of its configured reader: exit 1 on actual file identity.
- Restored the old calculateInsideAtomicOperation read lifecycle: exit 1 on commit.ZERO_CHANGE,
  expected 0 but received 1.
- Benchmark catches IOException or RuntimeException and returns an empty array: exit 1 because
  the invalid-input failure did not escape the benchmark method.
- The mutation verification script exited 0 after checking every expected outcome. These are
  deliberate negative checks, not unresolved failing tests. All scratch files are removed.

### Delivery and limits

The local commit message is `Track 13.2: Fix review findings round 1`. No push, amend or history
rewrite is part of this action. No performance measurement or performance conclusion is claimed.
Windows runtime and actual JMH throughput runs remain unverified. Goal 9 is still out of scope.

The report is appended from its own payload after taking a start copy in the action folder.
The final record check uses cat on the whole report and cmp against the start copy followed by
that payload. The comparison must pass before committing. The start copy, payload, expected
copy, mutation artifacts and scratch logs are deleted after the check passes.
