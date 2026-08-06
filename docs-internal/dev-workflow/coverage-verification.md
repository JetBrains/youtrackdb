# Coverage Verification

Read this when you are about to produce a coverage number, or when a coverage result needs
diagnosing. Which gate runs when, the module sets, and the re-run rule are in
`docs-internal/dev-workflow/track-development.md` § Verification integration.

## The run

The **coverage set** is the modules containing changed Java files — neither the compile-gate
set nor the test-gate set. Run these steps in order:

```bash
# 1. Mandatory: mvn clean does NOT remove this directory.
rm -rf .coverage/reports

# 2. Build the coverage set - modules with changed Java files - with -am.
./mvnw -pl <modules with changed Java files> -am clean package -P coverage

# 3. Gate the changed lines against the thresholds.
python3 .github/scripts/coverage-gate.py \
  --line-threshold 85 \
  --branch-threshold 70 \
  --compare-branch origin/develop \
  --coverage-dir .coverage/reports

# 4. Assert the report set (see below).
ls .coverage/reports
```

Step 1 is mandatory because `.coverage/reports` sits at the repository root, outside every
module's `target/`, so `mvn clean` never removes it, and stale reports merge into fresh ones
on a max-covered-wins basis — an invisible upward bias.

## Is the number real?

A skip counts as a pass **only** when the change genuinely has no changed Java files. Check
that with the same diff the script uses — `--diff-filter=ACM` is part of the check:

```bash
git diff origin/develop...HEAD --name-only --diff-filter=ACM -- '*.java'
```

Without the filter the check also lists deleted files, so a deletion-only change looks like
coverage applies while the script, which filters ACM, has nothing to measure — a loop with no
exit.

Empty output — a real pass. Non-empty output together with any of the five outputs below — the
*measurement* failed; investigate it, do not record a green gate. Paths 1 and 2 are skip lines
and print no PASSED line at all; paths 3–5 look like passes. These are the script's exact
stdout strings, to be matched against real output:

1. `No changed Java files found. Skipping coverage gate.` — the only legitimate skip, and only
   if the diff above is empty too. Otherwise the compare branch or the filter is wrong.
2. `No JaCoCo XML files found. Skipping coverage gate.` — the *entire* coverage directory has
   no `**/jacoco.xml`; nothing was measured (markdown report: the one `:warning:` case).
3. `Line coverage: PASSED — no coverable lines in diff` — line total was zero (markdown:
   `## Line Coverage: :white_check_mark: No coverable lines in diff`).
4. `Branch coverage: PASSED — no branches in changed lines` — branch total was zero (markdown:
   `## Branch Coverage: :white_check_mark: No branches in changed lines`).
5. Paths 3 and 4 together, followed by
   `PASSED: All coverage meets thresholds (85% line, 70% branch)` and exit 0 — a wholly
   vacuous green.

Paths 3–5 are also how a missing per-module report surfaces: the script never names a missing
module — it prints `Found N JaCoCo report(s)` and `Checking coverage for M changed file(s)`,
then reports PASSED over whatever those M files matched, including nothing. A PASSED line is
therefore not evidence that the diff was measured; only the report-set assertion is. Hardening
the script against these paths is deferred follow-up work; until then this protocol is the only
guard.

## The report-set assertion (step 4)

Report directories are named by **artifactId**, not by module directory: the root `pom.xml`'s
`coverage` profile writes `.coverage/reports/${project.artifactId}` (unit tests) and
`.coverage/reports/${project.artifactId}-it` (integration tests, so only when the run reaches
those phases). Every artifactId is `youtrackdb-<directory name>`, so `core` reports under
`.coverage/reports/youtrackdb-core`. Derive it rather than guess:

```bash
./mvnw -q -pl <module dir> help:evaluate -Dexpression=project.artifactId -DforceStdout
```

`.coverage/reports` must contain a `youtrackdb-<module>` directory for every coverage-set
module that **has test sources of its own** (`src/test`). The condition is `src/test`-gated
because a module without tests produces no execution data, so the JaCoCo report goal is
skipped and no directory is ever written — as of this writing `driver`, `console` and
`test-commons`; confirm with `ls -d <module>/src/test` instead of trusting that list. Which
reason applies decides whether a missing directory is a defect:

- **Missing, module has no `src/test`** — expected. A module's report covers only its own
  classes, so its changed lines are outside the measurement in CI too; say so when reporting
  the gate rather than presenting the percentage as covering the whole diff. Those lines are
  guarded by the test-gate modules' tests.
- **Missing, module has `src/test`** — a measurement defect: the module was left out of `-pl`,
  its tests were skipped, or the build never reached `prepare-package`, where the report goal
  is bound. Its changed lines are silently dropped from the denominator, so fix the selection
  or the build and re-run — a percentage that clears the thresholds is still wrong.

`Found N JaCoCo report(s)` **above** the expected count is normal, not a symptom: `-am` builds
the upstream modules too and each of those with test sources writes its own report, so a change
confined to `server` yields three. A surplus cannot be stale either, because step 1 emptied the
directory. Only a deficit is a defect.

## Local runs versus CI

CI measures coverage in one leg of `.github/workflows/maven-pipeline.yml` (`test-linux`, x86,
JDK 21): a full-reactor `./mvnw clean package -P docker-images,coverage` with
`-Dmaven.test.failure.ignore=true -Dyoutrackdb.test.env=ci`, whose reports a separate
`coverage-gate` job feeds to this script against `origin/<PR base branch>` on non-draft PRs.
CI therefore counts modules your `-pl` never built (larger denominator — what the report-set
assertion guards), covers disk-storage paths rather than in-memory ones, and reports a number
even when tests fail. CI is authoritative; the local run is a prediction that makes it
unsurprising. Do not substitute the CI command locally — a full-reactor coverage build is
priced in hours.
