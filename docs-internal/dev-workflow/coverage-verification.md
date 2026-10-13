# Coverage Verification

Read this when you are about to produce a coverage number, or when a coverage result needs
diagnosing. Which gate runs when, the module sets, and the re-run rule are in
`docs-internal/dev-workflow/track-development.md` § Verification integration.

## Shared coverage rule

Every reactor module follows the same changed-line coverage rule: 85 percent line coverage
and 70 percent branch coverage. The reactor is the set of modules Maven builds together.
This rule includes the copied TinkerPop modules:

- `gremlin-shaded`, `gremlin-language`, `gremlin-core`, `gremlin-util`
- `gremlin-driver`, `gremlin-groovy`, `gremlin-server`, `gremlin-console`
- `gremlin-test`, `tinkergraph-gremlin`, `gremlin-socket-server`

JaCoCo is the Java coverage tool. It records test execution and writes reports for modules
with execution data.
A report covers that module's own classes. Tests in other modules do not count toward
`gremlin-test` coverage. It follows the same rule as every other module.

These modules run unit tests in every pull request build.
Surefire excludes `IT*`, `*IT`, `*ITCase`, and `*IntegrateTest` classes.
Failsafe includes the same four patterns in the integration job.
A developer machine never runs those integration tests.

## The run

The **coverage set** is the modules containing changed Java files.
It is a subset of the test-gate set.
A developer machine runs coverage tests only in these measured modules.
Maven still builds their upstream dependencies from the working tree.

Use `-am` to build upstream modules in the same reactor invocation.
Use `-Dtest` to select a nonempty comma-separated list of fully qualified unit-test class names
from the coverage set. Never pass an empty or blank selector. Surefire treats it as no filter
and restores default test discovery, which can run upstream suites with `-am`.
The filter must match no test class owned by an upstream module outside that set.
`-Dsurefire.failIfNoSpecifiedTests=false` lets those upstream modules build with no matching tests.
Do not use an unfiltered `-am` coverage run.

If no measured module has test classes, do not run the Maven coverage build.
Remove `.coverage/reports` to discard stale data and record each measured module as having
no local coverage data. Do not report a coverage percentage or a green measurement.
The compile gate still builds these modules. The pull request build owns downstream tests.
This case is distinct from an empty coverage set. See the missing-report rules below.
If only some measured modules lack test classes, select tests from the others and record
which modules have no local coverage data.

When the coverage set has test classes, run these steps in order:

```bash
# 1. Mandatory: mvn clean does NOT remove this directory.
rm -rf .coverage/reports

# 2. Build upstream modules, but select tests only from the coverage set.
./mvnw -pl <coverage-set modules> -am clean package -P 'coverage,!docker-images' \
  -Dtest='<fully qualified unit-test classes from the coverage set>' \
  -Dsurefire.failIfNoSpecifiedTests=false

# 3. Gate the changed lines against the thresholds.
python3 .github/scripts/coverage-gate.py \
  --line-threshold 85 \
  --branch-threshold 70 \
  --compare-branch origin/develop \
  --coverage-dir .coverage/reports

# 4. Assert the report set (see below).
ls .coverage/reports
```

Step 1 is mandatory because `.coverage/reports` sits outside every module's `target/`.
`mvn clean` never removes it. Stale reports can raise the measured coverage.

Review the test selection before step 2. Use class names with their full package paths.
Include the unit-test entry points that cover the changed behavior.
Include any required suite runners. Core Gremlin compliance runners need the explicit profile
activation described below. Plain `-Dtest` is not sufficient for them.
Never select integration-test entry points.
Their names follow `IT*`, `*IT`, `*ITCase`, or `*IntegrateTest`.
`-Dtest` overrides Surefire includes and excludes, so a broad wildcard can select them.
For each measured module, confirm that its reports contain the intended tests.
Confirm that no upstream module outside the coverage set ran tests.

A filtered coverage run does not replace the test-gate set suites.
Core Gremlin compliance executions activate automatically only when `-Dtest` is absent.
A filtered run needs explicit profile activation to include them.
The ordinary test-gate run still needs every configured execution.
Do not broaden the test-gate set to compensate for a filtered coverage run.

The `package` phase creates the relocated classes in `gremlin-shaded` before its consumers build.
A separate full-reactor `./mvnw package -DskipTests` remains the compile gate.
It does not make a later `-pl` invocation without `-am` resolve sibling artifacts from `target/`.
That invocation resolves them from the local Maven repository.
The filtered `-am` command needs no snapshot install into `~/.m2`.
Stop at `package`. A local `verify` can run integration tests.

### Verified small-module command

This command measures `gremlin-annotations` and builds its upstream modules:

```bash
rm -rf .coverage/reports
./mvnw -pl gremlin-annotations -am clean package -P 'coverage,!docker-images' \
  -Dtest='com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl.GremlinDslDigestHelperTest,com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl.GremlinDslProcessorTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The two classes are this module's unit-test entry points.
The run produces two Surefire XML reports in `gremlin-annotations/target/surefire-reports`.
It runs 30 tests with no failures, errors, or skips.
No upstream module produces a test report.
JaCoCo writes `.coverage/reports/youtrackdb-gremlin-annotations/jacoco.xml`.
The coverage gate can read that report.

### Core Gremlin compliance runners

Use this route only when `core` belongs to the coverage set.
Explicitly activate `gremlin-compliance-suites` together with `coverage` in step 2.
This keeps the compliance executions active despite the nonempty `-Dtest` selector.
Their classpath includes `gremlin-test`, and they apply no category filter.
The ordinary `default-test` execution excludes that dependency.
The `sequential-tests` category filter prunes wrapped tests, so it cannot replace the
compliance executions.

This command selects the structure-compliance wrapper and builds its upstream modules:

```bash
rm -rf .coverage/reports
./mvnw -pl core -am clean package -P 'coverage,gremlin-compliance-suites,!docker-images' \
  -Dtest='com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.YTDBStructureTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Only `core` runs tests. No upstream module produces a Surefire report.
Each of the five Surefire compliance executions runs the selected wrapper's 920 wrapped tests.
Each reports 363 skips and no failures or errors.
The sequential execution also runs a category-matched subset.
The selector also applies to VMLens. JaCoCo instruments Surefire, not VMLens.
JaCoCo writes `.coverage/reports/youtrackdb-core/jacoco.xml` with execution data.
Continue with the coverage gate and report-set assertion in steps 3 and 4.

`-Dtest` replaces every execution's includes, so each compliance execution repeats the same
selected wrapper rather than running the wrapper named by its execution ID.
Shared XML reports can be overwritten. They are not a count of all repetitions.
Inspect each execution's log summary and the XML reports for the wrapped test classes.
The wrapper's own XML entry can show zero tests even when its leaf tests ran.
Confirm a nonzero, non-skipped wrapped-test count, not just a successful wrapper entry.
When selecting another wrapper, check its execution-specific properties and wrapped-test reports.
Do not use this route for ordinary core unit-test classes or broaden the coverage set.

## Is the number real?

A skip counts as a pass only when the change has no changed Java files.
Check the full diff first. Use the same diff as the script, including `--diff-filter=ACM`:

```bash
git diff origin/develop...HEAD --name-only --diff-filter=ACM -- '*.java'
```

Without the filter the check also lists deleted files, so a deletion-only change looks like
coverage applies while the script, which filters ACM, has nothing to measure — a loop with no
exit.

Empty output is a real pass. No coverage build or module report is required for an empty
coverage set. For a nonempty diff, distinguish missing data from code that has nothing to measure.

Before any no-report skip, the script checks each changed file under `<module>/src/main/java`.
If that module belongs to the reactor and has a `src/test/java` directory, it must have
`<coverage-dir>/<artifactId>/jacoco.xml`. The artifactId is Maven's artifact name.
The script reads module membership and artifact names from POM files, Maven's project descriptors. A missing report exits with status 1. The message names the module,
report path, and changed file. The Markdown report lists the same failures.
This check runs even when the entire coverage directory is absent or empty.
An integration report does not replace the required unit report.
The gate parses every required report directly, even when directory discovery omits it.
An unreadable or malformed required report fails the gate.

A report that exists but does not contain a changed file means that file has no executable
code for this gate. This permits interfaces and JaCoCo-excluded parsers. Source matching uses
the file basename and package suffix. It does not distinguish modules with identical source
paths. Check report contents and the report set rather than relying only on a PASSED line.

Match these stdout strings against the script output:

1. `No changed Java files found. Skipping coverage gate.` is legitimate only if the full
   diff above is empty. Otherwise the compare branch or the filter is wrong.
2. `No JaCoCo XML files found. Skipping coverage gate.` means nothing was measured.
   It can occur only when no changed main Java file requires a module report.
   Record the missing measurement, not a coverage percentage.
3. `Line coverage: PASSED — no coverable lines in diff` means the line total was zero.
   Confirm the required reports exist and inspect why the changed lines have no instructions.
4. `Branch coverage: PASSED — no branches in changed lines` means the branch total was zero.
   This is valid when the changed executable lines have no branches.
5. Paths 3 and 4 together, followed by
   `PASSED: All coverage meets thresholds (85% line, 70% branch)` and exit 0, mean that
   no changed executable code was measured. Record this fact rather than a percentage.

The report-set assertion also checks for stale or surplus reports. The automated presence
check does not replace it.

## The report-set assertion (step 4)

Report directories are named by **artifactId**, not by module directory: the root `pom.xml`'s
`coverage` profile writes `.coverage/reports/${project.artifactId}` (unit tests) and
`.coverage/reports/${project.artifactId}-it` (integration tests, so only when the run reaches
those phases). YouTrackDB modules use `youtrackdb-<directory name>`.
For example, `core` reports under `.coverage/reports/youtrackdb-core`.
The 11 copied modules use the bare directory name. For example, `gremlin-core` reports under
`.coverage/reports/gremlin-core`, and `gremlin-test` reports under `.coverage/reports/gremlin-test`.
Derive it rather than guess:

```bash
./mvnw -q -pl <module dir> help:evaluate -Dexpression=project.artifactId -DforceStdout
```

`.coverage/reports` must contain an `<artifactId>/jacoco.xml` report for every coverage-set
module with changed main Java files and a `src/test/java` directory.
Check with `ls -d <module>/src/test/java`. The directory is the condition, not the number of tests.
A module without test execution data produces no JaCoCo report. Use this distinction:

- **Missing, module has no `src/test/java`** — no report is required by the gate.
  The changed lines have no local measurement. Say so rather than presenting the percentage
  as covering the whole diff. The compile gate still builds the module.
  Rely on the pull request build for downstream tests.
- **Missing, module has `src/test/java` and changed main Java files** — a measurement defect.
  The module was left out of `-pl`, its tests were skipped, no tests produced execution data,
  or the build never reached `prepare-package`, where the report goal runs.
  The gate fails. Fix the selection or the build and re-run.
- **Only test Java files changed** — the presence check requires no production report.
  Still confirm that the selected tests ran. Do not claim a production coverage percentage
  when no changed main code was measured.

The local report set must contain only coverage-set modules.
Upstream modules outside that set build without test execution data after `clean`.
They must produce no JaCoCo report. A surplus means the filter selected extra tests or stale
reports remain. Investigate a surplus as well as a deficit.

## Local runs versus CI

CI measures coverage in one leg of `.github/workflows/maven-pipeline.yml` (`test-linux`, x86,
JDK 21): a full-reactor `./mvnw clean package -P docker-images,coverage` with
`-Dmaven.test.failure.ignore=true -Dyoutrackdb.test.env=ci`, whose reports a separate
`coverage-gate` job feeds to this script against `origin/<PR base branch>` on non-draft PRs.
The pull request build runs the whole unit suite.
It therefore measures modules outside the local coverage set.
It covers disk-storage paths rather than in-memory ones and reports a number even when tests fail.
CI is authoritative. The local run predicts its coverage result for the measured modules.
Do not substitute the CI command locally. A developer machine runs tests only in the test-gate set.
The Maven pipeline skips draft pull requests.
The separate `coverage-gate-tests.yml` workflow runs Python script unit tests on pull requests
when `.github/scripts/**` or that workflow changes, including drafts. It also runs on matching
pushes to `develop` and `main`. The workflow fails if it discovers no tests or any test fails.
These tests validate the gate, not Java coverage data.
For build-relevant changes, the whole Java unit suite runs at the ready-for-review flip.
A manual workflow run also runs the whole unit suite. Markdown-only changes skip the build.
Integration tests follow the rules in `track-development.md`.
