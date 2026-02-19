# Test Quality Requirements

This document describes the quality gates and guidelines for tests in the YouTrackDB project. All requirements are enforced automatically by the CI pipeline on every pull request.

## Code Coverage

### Thresholds

Coverage is measured for **new and changed code only** (not the entire codebase). Both line and branch coverage are enforced independently.

| Condition | Line Coverage | Branch Coverage |
|---|---|---|
| Commits co-authored with Claude Code | 85% | 85% |
| All other commits | 70% | 70% |

Co-authorship is detected by scanning commit messages for `Co-Authored-By:.*Claude`.

### How It Works

1. **JaCoCo** collects coverage data during unit tests (surefire) and integration tests (failsafe) via the `coverage` Maven profile.
2. A unified script (`.github/scripts/coverage-gate.py`) parses `git diff` to identify changed lines, reads JaCoCo XML reports for line-level coverage data (`mi`/`ci` for instructions, `mb`/`cb` for branches), and enforces both line and branch coverage thresholds. When multiple reports cover the same file, coverage is merged by taking the max of covered values per line.

Coverage reports are stored as XML in `.coverage/reports/` per module:
- `<module>/jacoco.xml` — unit test coverage
- `<module>-it/jacoco.xml` — integration test coverage

Both reports are merged during analysis so that a line covered by either unit or integration tests counts as covered.

### Running Locally

```bash
# Run unit tests with coverage
./mvnw clean package -P coverage

# Run integration tests with coverage
./mvnw verify -P coverage

# Check coverage reports
ls .coverage/reports/*/jacoco.xml
```

## Mutation Testing

### Threshold

Mutation testing is performed by [PIT (Pitest)](https://pitest.org/) on **new and changed production code only**. The build fails if the mutation score is below **85%**.

A mutation score of 85% means that at least 85% of the mutations introduced into new code are detected ("killed") by the test suite.

### What PIT Does

1. Identifies changed production Java classes via `git diff` against the base branch.
2. Introduces small code mutations (e.g., changing `>` to `>=`, removing method calls, negating conditions).
3. Runs relevant tests against each mutation.
4. Reports how many mutations were killed (detected by tests) vs. survived (undetected).

### Integration Test Selection with Ekstazi

PIT uses both unit tests and integration tests to kill mutations. To avoid running irrelevant integration tests, the CI restores the [Ekstazi](https://github.com/gliga/ekstazi) cache from the base branch and runs Ekstazi's selection algorithm. Only integration tests affected by the PR's changes are included in PIT's test pool.

- **Unit tests**: all unit tests matching `com.jetbrains.youtrackdb.*` are available to PIT.
- **Integration tests**: only Ekstazi-selected `*IT` and `*IntegrationTest` classes participate.
- On a fresh run (no Ekstazi cache), all integration tests are included (safe default).

### Configuration

PIT is configured via the `mutation-testing` Maven profile in the root `pom.xml`:

| Setting | Value | Rationale |
|---|---|---|
| `mutationThreshold` | 85 | Minimum mutation kill rate |
| `threads` | 1 | Database tests are not thread-safe |
| `timeoutConstant` | 10000 ms | Tolerant timeout for database operations |
| `timeoutFactor` | 1.5 | Multiplier for slow tests |
| `failWhenNoMutations` | false | Skip gracefully if no production code changed |

Generated code (SQL parser, GQL parser) is excluded from mutation analysis.

### Running Locally

```bash
# Run PIT on a specific module for specific classes
./mvnw -pl core test-compile org.pitest:pitest-maven:mutationCoverage \
  -P mutation-testing \
  -DtargetClasses=com.jetbrains.youtrackdb.internal.core.SomeClass

# HTML report is generated in target/pit-reports/
```

## Writing Tests

### General Guidelines

1. **Every change and bug fix must be covered by tests.** Both new features and bug fixes require test coverage demonstrating the expected behavior.

2. **Prefer existing test classes.** Before creating a new test class, check if an appropriate test class already exists in the module. Add new test methods to existing classes when they cover the same component or feature. Only create new test classes when no suitable existing class exists.

3. **Place tests in the related module.** Tests for `core` code go in `core/src/test/java`, tests for `server` code go in `server/src/test/java`, etc.

4. **Target 85% coverage.** Aim for at least 85% line and branch coverage on new code. This is the threshold enforced for Claude Code co-authored commits and the recommended standard for all contributions.

### Test Types and Naming

| Type | Naming Convention | Runner | Plugin |
|---|---|---|---|
| Unit tests | `*Test.java` | JUnit 4 | maven-surefire-plugin |
| Integration tests | `*IT.java`, `*IntegrationTest.java` | JUnit 4 | maven-failsafe-plugin |
| Functional tests | Located in `tests` module | TestNG | maven-surefire-plugin |

### What Makes a Good Test

- **Focused**: each test method verifies one behavior or scenario.
- **Independent**: tests must not depend on execution order or shared mutable state between test methods.
- **Deterministic**: tests must produce the same result on every run, regardless of environment.
- **Meaningful assertions**: assert the expected outcome, not implementation details. Prefer specific assertions (`assertEquals`, `assertThat`) over generic ones (`assertNotNull`).
- **Covers edge cases**: test boundary values, error conditions, empty inputs, and concurrent scenarios where applicable.
- **Covers branches**: when code has conditional logic (`if`/`else`, `switch`, ternary), ensure tests exercise all paths — this is what branch coverage measures.

### What the CI Checks

On every pull request, the CI pipeline enforces:

| Gate | Tool | Threshold | Scope |
|---|---|---|---|
| Line coverage | coverage-gate.py | 70% or 85% | New/changed lines only |
| Branch coverage | coverage-gate.py | 70% or 85% | New/changed lines only |
| Mutation score | PIT | 85% | New/changed production classes only |
| Static analysis | Qodana | 0 new issues | Full codebase (baseline) |

All gates must pass for a PR to be mergeable.
