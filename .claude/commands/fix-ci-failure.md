Fix a CI test failure from a GitHub PR or workflow run.

The user will provide a URL to a failing check run, PR checks page, or workflow run.
Use `$ARGUMENTS` as the URL if provided.

## Workflow

### Step 1: Identify the failure

1. Use `gh` CLI to fetch PR and check run details. Prefer `gh api` and `gh pr` over WebFetch for GitHub URLs.
2. If no URL is provided, detect the current branch and find its open PR:
   ```bash
   gh pr list --head {branch} --json number,title,url,statusCheckRollup
   ```
3. List non-passing checks to find failures:
   ```bash
   gh pr view {number} --json statusCheckRollup --jq '.statusCheckRollup[] | select(.conclusion != "SUCCESS")'
   ```
4. Get the failing check annotations to find the exact test name and error message:
   ```bash
   gh api "repos/{owner}/{repo}/check-runs/{check_run_id}/annotations"
   ```
5. For workflow run failures, get failed job logs:
   ```bash
   gh run view --job {job_id} --log-failed
   ```
6. **Check PR comments for gate details**: CI gates (coverage gate, test count gate, mutation testing) post detailed PR comments with tables explaining exactly what failed. Always fetch these:
   ```bash
   gh api repos/{owner}/{repo}/issues/{pr_number}/comments --jq '.[] | select(.body | contains("gate")) | .body'
   ```

### Step 2: Classify the failure type

Not all CI failures are test failures. Identify the category before diving into code:

- **Test failure**: A test assertion or error in a specific test class — go to Step 3.
- **CI gate failure** (coverage gate, test count gate, mutation testing): A policy check failed, not a test. These require understanding the gate's logic and why the PR's changes triggered it — go to Step 2a.
- **Build failure**: Compilation error, dependency resolution, etc. — read the build logs.
- **Infrastructure failure**: Timeout, runner issue, flaky network — may just need a re-run.

### Step 2a: Diagnose CI gate failures

CI gates enforce policies on PRs. When a gate fails:

1. **Read the gate's PR comment** — it contains the specific data (uncovered lines, test count drops, surviving mutants).
2. **Understand the gate's threshold** — check CLAUDE.md and the gate script for exact rules.
3. **Determine if the failure is expected or a bug**:
   - **Test Count Gate**: Compare baseline vs current counts per module. A large drop in one module usually means tests were unintentionally excluded by build config changes (profile removal, surefire exclusions, module restructuring). Trace WHY those tests stopped running — don't just bypass the gate with `[no-test-number-check]` unless the user explicitly confirms the drop is intentional.
   - **Coverage Gate**: Check which lines are uncovered and whether existing tests should cover them.
   - **Mutation Testing**: Check which mutants survived and whether they represent real coverage gaps.
4. **The fix is often in build configuration, not in test code**: When a PR changes Maven profiles, CI pipeline config, or surefire/failsafe plugin settings, tests may silently stop running. Look for:
   - `<excludes>` blocks in surefire/failsafe that were previously overridden by a profile the PR removed
   - Profile-gated test inclusion (e.g., `combine.self="override"` patterns that clear exclusions)
   - CI workflow changes that remove Maven profiles from the `mvn` command line

### Step 3: Locate and understand the failing test

1. Find the test file using `Glob` (e.g., `**/{TestClassName}.java`).
2. Read the full test file to understand the test logic, especially the failing assertion.
3. Read the error message carefully — identify whether this is:
   - **Timing/flakiness issue**: off-by-one timestamps, race conditions, thread scheduling
   - **Logic bug**: wrong assertion, incorrect expected value
   - **Environment issue**: path separators, locale, OS-specific behavior
   - **Actual regression**: code change broke the test legitimately

### Step 4: Trace the root cause

1. Read the production code exercised by the failing test.
2. Understand the full data flow from the code under test to the assertion.
3. Determine whether the fix should be in the test or in the production code:
   - If the test asserts a contract that the production code violates → fix the production code
   - If the test has an overly strict assertion that doesn't match the documented/intended behavior → fix the test
4. **For build config issues**: Trace the chain — which Maven profile was removed? What did it activate? Which module's `pom.xml` depended on it? Read the module's `pom.xml` fully to find exclusions, profile overrides, and plugin configurations.
5. Document your root cause analysis before making changes.

### Step 5: Apply the fix

1. Make the minimal change that fixes the root cause.
2. Add or update comments explaining **why** the fix is correct (especially for tolerance values, timing adjustments, or non-obvious logic).
3. Update any method/class Javadoc that describes behavior affected by the fix.
4. Update CLAUDE.md if the fix changes documented behavior (e.g., which tests run by default, which profiles are needed).
5. Run `./mvnw -pl {module} spotless:apply` to fix formatting.

### Step 6: Verify locally

1. Run the failing test to confirm the fix:
   ```bash
   ./mvnw -pl {module} clean test -Dtest={TestClass}
   ```
2. For gate failures, run the full module test suite to verify counts:
   ```bash
   ./mvnw -pl {module} clean test
   ```
3. If the test requires a suite runner (e.g., GremlinProcessRunner, graph provider), note that it cannot be run standalone — verify the code change is correct by inspection and rely on CI.
4. Run `./mvnw -pl {module} spotless:check` to ensure formatting compliance.
5. **Run mutation testing** on the changed code to verify that fixes produce a mutation score ≥ 85%. Copy the Arcmutate license if not already present, then run PIT using the `mutation-testing` profile with the `pit.git.from` property to scope mutations to only changed lines (matching CI behavior). **Do NOT run PIT without the profile** — without the `+GIT` feature, PIT mutates the entire target class, which can take 30+ minutes for large classes (e.g., `AtomicOperationsManager`) vs seconds when scoped to the diff.

   First, determine the fork point commit between the current branch and the base branch. If a PR already exists, fetch its base branch; otherwise default to `origin/develop`:
   ```bash
   # If PR exists, get the actual base branch
   BASE_BRANCH=$(gh pr view --json baseRefName --jq .baseRefName 2>/dev/null)
   BASE_BRANCH="origin/${BASE_BRANCH:-develop}"
   # Find the fork point commit (merge-base) — this is more reliable than a branch name
   # because it pinpoints the exact commit where the current branch diverged
   FORK_POINT=$(git merge-base HEAD "${BASE_BRANCH}")
   ```
   Then run PIT:
   ```bash
   cp /home/andrii0lomakin/Projects/ytdb/arcmutate-licence.txt arcmutate-licence.txt
   ./mvnw -pl {module} clean test-compile org.pitest:pitest-maven:mutationCoverage \
     -P mutation-testing -Dpit.git.from=${FORK_POINT}
   ```
   If you need to target only specific classes (e.g., to speed up the run further), add `-Dpitest.targetClasses=com.jetbrains.youtrackdb.some.ClassName` — but always keep `-P mutation-testing -Dpit.git.from=...` to use the GIT-scoped mutation filter.
   Review the PIT report (in `{module}/target/pit-reports/`) for surviving mutants. If the mutation score is below 85%, add or strengthen tests to kill surviving mutants before proceeding.

### Step 7: Code review

Run the `code-reviewer` agent to review the changes. Address any feedback. Repeat until the reviewer is satisfied with no critical issues.

### Step 8: Summarize and wait for approval

Present to the user:
- **Problem**: The exact failure (test name, error message, CI link)
- **Root cause**: Why it failed (detailed technical explanation)
- **Fix**: What was changed and why
- **Decision rationale**: Why the fix is in the test vs production code vs build config

**Do NOT commit or push until the user approves.**

### Step 9: Commit and PR (only after approval)

1. Commit following the project's git conventions (YTDB-NNN prefix, imperative summary).
2. Push and create a PR targeting `develop` with:
   - Summary bullets explaining the fix
   - Motivation section with a link to the failing CI run
   - Test plan checklist

## Important Rules

- **Always use `gh` CLI** for GitHub API calls, not WebFetch.
- **Read before editing**: Always read the full test file and relevant production code before proposing a fix.
- **Minimal changes**: Fix the root cause, don't refactor surrounding code.
- **Explain tolerances**: If adding numeric tolerances (timing, precision), justify the exact value in a comment.
- **Check for similar issues**: After fixing one assertion, scan the test for other assertions that might have the same vulnerability.
- **Don't blindly relax assertions**: Understand why the assertion fails before loosening it. A failing assertion might indicate a real bug.
- **Don't blindly bypass CI gates**: When a gate like test-count-gate fails, investigate WHY tests disappeared before suggesting `[no-test-number-check]` or similar bypasses. The gate may be catching a real problem (tests silently excluded by build config changes).
- **Build config changes have test side effects**: When a PR modifies Maven profiles, CI workflows, or plugin configurations, always check whether any module's test execution depends on the removed/changed config. Look for `<excludes>` with profile overrides, failsafe configurations gated by profiles, and CI workflow `mvn` command-line profile flags.
- **Respect the project's pre-commit verification workflow**: Run tests, check formatting, verify coverage as described in CLAUDE.md.

## YouTrackDB-Specific Knowledge

### CI Gates
- **Test Count Gate**: Compares per-module test counts against baseline in git notes (`refs/notes/test-counts`). Fails if any module drops >5%. PR comment contains the per-module comparison table. Bypass with `[no-test-number-check]` in PR title (only for intentional restructuring).
- **Coverage Gate**: 85% line / 70% branch on changed code. Uses `coverage-gate.py`. PR comment has per-file tables.
- **Mutation Testing**: 85% mutation score on changed code via PIT/Arcmutate. Survived mutants are posted as **GitHub check run annotations** on a separate check run named `"pitest"` (created by `pitest-github-maven-plugin`), NOT on the "Mutation Testing (New Code)" job itself. Fetch them with:
  ```bash
  # Find the "pitest" check run ID (NOT the "Mutation Testing" job)
  gh api repos/JetBrains/youtrackdb/commits/{head_sha}/check-runs --jq '.check_runs[] | select(.name == "pitest") | .id'
  # Fetch annotations with surviving mutant details
  gh api "repos/JetBrains/youtrackdb/check-runs/{pitest_check_run_id}/annotations" --paginate
  ```
  Each annotation includes the file path, line number, and mutant description — use these to pinpoint exactly which lines need stronger test coverage. Note: the "Mutation Testing (New Code)" job check run only has generic "Process completed with exit code 1" annotations — always use the `"pitest"` check run for per-line mutant details.
  To run mutation testing locally, copy the Arcmutate license first: `cp /home/andrii0lomakin/Projects/ytdb/arcmutate-licence.txt {project-root}/arcmutate-licence.txt`.

### Common Patterns
- **Profile-gated tests**: Some modules exclude heavyweight tests by default and use `ci-integration-tests` profile with `<excludes combine.self="override"/>` to include them. If CI stops activating that profile, those tests silently disappear.
- **Embedded module Cucumber tests**: `EmbeddedGraphFeatureTest` runs ~1900 TinkerPop Gremlin scenarios. Requires 4GB heap. Previously gated by `ci-integration-tests` profile, now runs by default.
- **Surefire vs Failsafe**: Unit tests use surefire (`test` phase), integration tests use failsafe (`verify` phase with `-P ci-integration-tests`). Test count gate counts surefire results.

### Useful Commands
```bash
# Check PR gate comments
gh api repos/JetBrains/youtrackdb/issues/{pr}/comments --jq '.[] | select(.body | contains("gate")) | .body'

# Run single module tests
./mvnw -pl {module} clean test -Dtest={TestClass}

# Run with disk storage (as CI does)
./mvnw -pl {module} clean test -Dyoutrackdb.test.env=ci

# Run mutation testing (scoped to changed lines only, matching CI)
# Determine fork point commit between current branch and base branch
BASE_BRANCH=$(gh pr view --json baseRefName --jq .baseRefName 2>/dev/null)
BASE_BRANCH="origin/${BASE_BRANCH:-develop}"
FORK_POINT=$(git merge-base HEAD "${BASE_BRANCH}")
cp /home/andrii0lomakin/Projects/ytdb/arcmutate-licence.txt arcmutate-licence.txt
./mvnw -pl {module} clean test-compile org.pitest:pitest-maven:mutationCoverage \
  -P mutation-testing -Dpit.git.from=${FORK_POINT}
```
