---
name: fix-ci-failure
description: "Diagnose and fix a CI test failure from a GitHub PR or workflow run. Includes root cause analysis, dimensional code review, and PR creation."
argument-hint: "[github-url]"
user-invocable: true
---

Fix a CI test failure from a GitHub PR or workflow run.

The user will provide a URL to a failing check run, PR checks page, or workflow run.
Use `$ARGUMENTS` as the URL if provided.

## Workflow

### Step 0: Create progress tracker (MANDATORY — do this FIRST)

Before any investigation, create tasks for every workflow step using TaskCreate.
These tasks are your checklist — mark each `in_progress` when starting and
`completed` when done. **Do NOT skip tasks. Complete them in order.**

Create these tasks:
1. Identify the failure
2. Classify the failure type
3. Locate and understand the failing test
4. Trace the root cause
5. Apply the fix
6. Verify locally
7. Dimensional code review + test quality review
8. Summarize and wait for approval
9. Commit and PR (only after approval)

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
6. **Check PR comments for gate details**: CI gates (coverage gate, test count gate) post detailed PR comments with tables explaining exactly what failed. Always fetch these:
   ```bash
   gh api repos/{owner}/{repo}/issues/{pr_number}/comments --jq '.[] | select(.body | contains("gate")) | .body'
   ```

### Step 2: Classify the failure type

Not all CI failures are test failures. Identify the category before diving into code:

- **Test failure**: A test assertion or error in a specific test class — go to Step 3.
- **CI gate failure** (coverage gate, test count gate): A policy check failed, not a test. These require understanding the gate's logic and why the PR's changes triggered it — go to Step 2a.
- **Build failure**: Compilation error, dependency resolution, etc. — read the build logs.
- **Infrastructure failure**: Timeout, runner issue, flaky network — may just need a re-run.

### Step 2a: Diagnose CI gate failures

CI gates enforce policies on PRs. When a gate fails:

1. **Read the gate's PR comment** — it contains the specific data (uncovered lines, test count drops, surviving mutants).
2. **Understand the gate's threshold** — check CLAUDE.md and the gate script for exact rules.
3. **Determine if the failure is expected or a bug**:
   - **Test Count Gate**: Compare baseline vs current counts per module. A large drop in one module usually means tests were unintentionally excluded by build config changes (profile removal, surefire exclusions, module restructuring). Trace WHY those tests stopped running — don't just bypass the gate with `[no-test-number-check]` unless the user explicitly confirms the drop is intentional.
   - **Coverage Gate**: Check which lines are uncovered and whether existing tests should cover them.
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
5. **Add Java `assert` statements generously** in production code touched by the fix or exercised by new/changed tests. These must have **zero production overhead** (disabled by default in production JVMs). Good candidates:
   - Preconditions/postconditions at method entry/exit (e.g., `assert index >= 0 : "negative index"`)
   - Invariant checks after state mutations (e.g., `assert size == backingArray.length`)
   - Null checks on values that should never be null by contract
   - Range checks on computed offsets, positions, and sizes
   - Consistency checks between redundant data structures
   - State checks in methods that assume a specific lifecycle stage (e.g., `assert !closed`)
   Do NOT add assertions that duplicate existing validation, have side effects, or are tautological.
   If the assertion condition is complex, extract it to a package-private helper method to get full JaCoCo coverage (see CLAUDE.md tip #10).
6. **Improve testability of internal classes** when needed to write effective tests or add meaningful assertions. You may refactor classes under `com.jetbrains.youtrackdb.internal` (e.g., extract methods, increase visibility from private to package-private, add package-private accessors for state verification) but **never modify the public API** under `com.jetbrains.youtrackdb.api`. Any testability change must not alter the class's external behavior.
7. Run `./mvnw -pl {module} spotless:apply` to fix formatting.

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

### Step 7: Dimensional code review + test quality review (MANDATORY GATE)

**BLOCKING: Do NOT proceed to Step 8 (summarize) until this step is fully
complete. Skipping this step is never acceptable — it exists to catch bugs
in the fix itself before presenting to the user.**

**If you changed any code or added/fixed/changed any tests**, run a
dimensional code review and test quality review. This step is mandatory
whenever code or tests were modified.

1. **Triage — Categorize changes and select relevant agents**

   Before launching agents, perform a quick triage pass over the diff to determine which review dimensions are actually relevant. This avoids wasting time on agents that have nothing meaningful to review.

   **1a: Categorize each changed file**

   Scan the diff and assign one or more categories to every changed file (production, test, and other):

   | Category | Signals |
   |---|---|
   | **storage-engine** | Files in `storage/`, `cache/`, `wal/`, `StorageComponent` subclasses, page read/write logic, `DiskStorage`, `WriteCache`, `ReadCache`, `LogSequenceNumber`, double-write log |
   | **concurrency** | `synchronized`, `Lock`, `Atomic*`, `volatile`, `StampedLock`, `ReentrantLock`, thread pools, `ConcurrentHashMap`, `CompletableFuture`, shared mutable state, `@GuardedBy`, `ConcurrentTestHelper`, `CountDownLatch`, `CyclicBarrier` |
   | **crash-durability** | WAL operations, crash simulation, durable `StorageComponent` recovery, page corruption handling, transaction atomicity under failure, `LogSequenceNumber` manipulation, double-write log, Java `assert` statements in production code |
   | **index-data-structures** | Files in `index/`, B-tree, hash index, `SBTree`, `CellBTree`, histogram, `IndexEngine` |
   | **network-server** | Files in `server/`, `driver/`, Gremlin Server, protocol handling, TLS/SSL, authentication, session management |
   | **sql-query** | Files in `sql/` (excluding `parser/`), query execution, command handlers |
   | **gremlin** | Files in `gremlin/`, traversal steps, `YTDBGraph*` classes, TinkerPop integration |
   | **public-api** | Files in `com.jetbrains.youtrackdb.api`, `YourTracks`, `YouTrackDB` interface |
   | **serialization** | Record serializers, binary format, property map encoding/decoding |
   | **configuration** | `GlobalConfiguration`, config parameters, system properties |
   | **tests-only** | Changes exclusively in test files with no production code changes |
   | **build-config** | `pom.xml`, CI workflows, Maven profiles, Docker configs |
   | **docs-only** | Markdown, documentation, comments-only changes |

   A file can belong to multiple categories.

   **1b: Select code review agents based on categories**

   | Agent | Launch when ANY of these categories are present |
   |---|---|
   | **review-code-quality** | Always launched (unless `docs-only` is the ONLY category) |
   | **review-bugs-concurrency** | `concurrency`, `storage-engine`, `index-data-structures`, `network-server`, `serialization`, `gremlin`, `sql-query` |
   | **review-crash-safety** | `crash-durability` |
   | **review-security** | `network-server`, `public-api`, `sql-query`, `serialization`, `configuration`, OR when new dependencies are added in `pom.xml` |
   | **review-performance** | `storage-engine`, `index-data-structures`, `concurrency`, `serialization`, `sql-query`, `gremlin` |

   **1c: Select test quality agents based on categories**

   | Agent | When to launch |
   |---|---|
   | **review-test-behavior** | Always (unless `docs-only` or `build-config` are the ONLY categories) |
   | **review-test-completeness** | Always (unless `docs-only` or `build-config` are the ONLY categories) |
   | **review-test-structure** | Any test files are changed |
   | **review-test-concurrency** | `concurrency`, OR production code touches shared mutable state / threading primitives even if no concurrency tests exist yet |
   | **review-test-crash-safety** | `crash-durability` |

   **1d: Log your triage decision**

   Before launching agents, output a brief triage summary:
   ```
   ### Triage Summary
   - **Categories detected**: storage-engine, concurrency
   - **Code review agents selected**: review-code-quality, review-bugs-concurrency, review-performance
   - **Code review agents skipped**: review-crash-safety (no crash-durability category), review-security (no network/API/SQL/config/dependency changes)
   - **Test quality agents selected**: review-test-behavior, review-test-completeness, review-test-structure, review-test-concurrency
   - **Test quality agents skipped**: review-test-crash-safety (no crash-durability category)
   ```

   **1e: Edge cases**
   - If **all categories are `docs-only`**: Skip all agents. Report that only documentation changed and no code review is needed.
   - If **all categories are `build-config`**: Launch `review-code-quality` (to check for misconfigurations) and `review-security` (to check for dependency changes).
   - If **all categories are `tests-only`**: Launch `review-code-quality`, `review-bugs-concurrency` for code agents, and `review-test-behavior`, `review-test-completeness`, `review-test-structure` for test agents.
   - If **in doubt** about whether an agent is relevant: **launch it**. False positives are better than false negatives.

2. **Launch selected review agents in parallel** (fresh sub-agents):

   Each agent receives the same context:
   ```
   ## Review Target
   CI fix: {brief description of the fix}
   Reviewing: changes on current branch vs develop

   ## Changed Files
   {git diff develop...HEAD --name-only}

   ## Skip These Files (generated code)
   - core/.../sql/parser/*, generated-sources/*, Gremlin DSL

   ## Diff
   {git diff develop...HEAD}
   ```

   Set `subagent_type` to the agent name and `model` to `opus` for each.

3. **Synthesize findings**: After all selected agents complete, deduplicate
   across dimensions. Prioritize: blocker > should-fix > suggestion.

4. Address all **blocker** and **should-fix** findings:
   - Fix code quality, bug, safety, security, and performance issues
   - Add missing behavior assertions flagged by the test quality reviewer
   - Add corner case tests for gaps identified
   - Add recommended `assert` statements in production code (zero-overhead)
   - Fix any test isolation, readability, or precision issues

5. After applying fixes, run `./mvnw -pl {module} spotless:apply` and
   re-run the affected tests to confirm they pass.

6. **Re-run only the agent(s) with open findings** on the updated changes.

7. **Repeat steps 4-6** until no blocker/should-fix findings remain.
   - A maximum of 3 iterations. If after 3 rounds there are still
     critical issues, present the remaining findings to the user and ask
     for guidance.

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
- **NEVER run multiple test processes simultaneously**: Always wait for one `./mvnw test` or `./mvnw verify` invocation to finish before starting another. Running tests in parallel across separate Maven processes causes classloading errors, database file locking conflicts, and false test failures. This includes any combination of unit tests, integration tests, and coverage runs.
- **Add `assert` statements generously**: When fixing or testing production code, add Java `assert` statements for invariants, preconditions, postconditions, and consistency checks. These cost nothing in production (assertions disabled by default) but catch bugs during development and testing. Do not add assertions that duplicate existing checks or have side effects.
- **Internal classes may be refactored for testability**: Classes under `com.jetbrains.youtrackdb.internal` can be modified to improve testability (e.g., extract methods, widen visibility to package-private, add state-inspection accessors). **Never modify the public API** under `com.jetbrains.youtrackdb.api`.
- **Dimensional review is mandatory**: After making any code or test changes, triage the diff to select relevant code review and test quality agents, launch selected agents in parallel, synthesize findings, and iterate until no blocker/should-fix findings remain. Do not skip this step. Do not launch agents for irrelevant dimensions — use the category-to-agent mapping to decide.

## YouTrackDB-Specific Knowledge

### CI Gates
- **Test Count Gate**: Compares per-module test counts against baseline in git notes (`refs/notes/test-counts`). Fails if any module drops >5%. PR comment contains the per-module comparison table. Bypass with `[no-test-number-check]` in PR title (only for intentional restructuring).
- **Coverage Gate**: 85% line / 70% branch on changed code. Uses `coverage-gate.py`. PR comment has per-file tables.

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
```
