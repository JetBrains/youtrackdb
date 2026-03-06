---
source_files:
  - .github/scripts/coverage-gate.py
  - .github/scripts/mutation-gate.py
  - .github/workflows/maven-pipeline.yml
  - pom.xml
related_docs:
  - docs/dev/ci-cd-diagram.md
  - CLAUDE.md
---

# Test Quality Requirements

This document describes the quality gates and guidelines for tests in the YouTrackDB project. All requirements are enforced automatically by the CI pipeline on every pull request.

## Code Coverage

### Thresholds

Coverage is measured for **new and changed code only** (not the entire codebase). Line and branch coverage are enforced independently with different thresholds.

| Metric | Threshold |
|---|---|
| Line Coverage | 85% |
| Branch Coverage | 70% |

These thresholds apply to all pull requests regardless of author.

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

Mutation testing is performed by [PIT (Pitest)](https://pitest.org/) with [Arcmutate](https://docs.arcmutate.com/) extensions on **new and changed production code only**. The build fails if the mutation score is below **85%**.

A mutation score of 85% means that at least 85% of the mutations introduced into new code are detected ("killed") by the test suite.

### What PIT Does

1. Uses Arcmutate's `GIT_MIXED` mode to automatically scope mutations to changed lines and code exercised by modified tests — no manual class targeting needed.
2. Introduces code mutations using the `STRONGER` + `EXTENDED` mutator groups (e.g., changing `>` to `>=`, removing method calls, negating conditions, removing stream operations, swapping parameters).
3. Runs relevant tests against each mutation.
4. Reports how many mutations were killed (detected by tests) vs. survived (undetected).

When the mutation score is below the threshold:
- A **summary PR comment** is posted listing survived and no-coverage mutations by class, method, and line number.
- **Inline PR annotations** are posted on the Files Changed tab via the `pitest-github-maven-plugin`, highlighting exactly which lines have survived mutations.

### Integration Test Exclusion

PIT runs only with unit tests. All integration tests (`*IT` and `*IntegrationTest` classes) are excluded from mutation analysis via the `mutation-testing` Maven profile. Integration tests are too slow for the mutation testing feedback loop and are already validated separately by the CI integration test pipeline.

### Configuration

PIT is configured via the `mutation-testing` Maven profile in the root `pom.xml`:

| Setting | Value | Rationale |
|---|---|---|
| `mutationThreshold` | 85 | Minimum mutation kill rate |
| `threads` | 1 | Database tests are not thread-safe |
| `timeoutConstant` | 10000 ms | Tolerant timeout for database operations |
| `timeoutFactor` | 1.5 | Multiplier for slow tests |
| `failWhenNoMutations` | false | Skip gracefully if no production code changed |
| `mutators` | `STRONGER`, `EXTENDED` | Arcmutate recommended mutator groups |
| `features` | `+GIT_MIXED`, `+gitci`, `+CLASSLIMIT(150)` | Git-based scoping, GitHub CI output, per-class mutation cap |

Generated code (SQL parser, GQL parser) is excluded from mutation analysis.

### Arcmutate Extensions

The project uses commercial [Arcmutate](https://docs.arcmutate.com/) extensions for PIT:

- **`com.arcmutate:base`** — provides the `EXTENDED` mutator group with additional operators (stream operations, varargs, parameter swaps, etc.)
- **`com.arcmutate:pitest-git-plugin`** — `GIT_MIXED` mode that scopes mutations to changed lines and code paths exercised by modified tests
- **`com.arcmutate:pitest-github-maven-plugin`** — posts inline annotations on PR file diffs for survived mutations

The Arcmutate licence file (`arcmutate-licence.txt`) is **not** committed to the repository (it is gitignored). In CI, the licence content is written from the `ARCMUTATE_LICENCE` GitHub Actions secret. For local development, place the licence file manually at the project root.

### Running Locally

```bash
# Run PIT on a specific module (GIT_MIXED compares against HEAD~1 by default)
./mvnw -pl core test-compile org.pitest:pitest-maven:mutationCoverage \
  -P mutation-testing

# Compare against a specific branch instead of HEAD~1
./mvnw -pl core test-compile org.pitest:pitest-maven:mutationCoverage \
  -P mutation-testing \
  -Dpit.git.from=origin/develop

# HTML report is generated in target/pit-reports/
```

## Writing Tests

### General Guidelines

1. **Every change and bug fix must be covered by tests.** Both new features and bug fixes require test coverage demonstrating the expected behavior.

2. **Prefer existing test classes.** Before creating a new test class, check if an appropriate test class already exists in the module. Add new test methods to existing classes when they cover the same component or feature. Only create new test classes when no suitable existing class exists.

3. **Place tests in the related module.** Tests for `core` code go in `core/src/test/java`, tests for `server` code go in `server/src/test/java`, etc.

4. **Target 85% line / 70% branch coverage.** Aim for at least 85% line coverage and 70% branch coverage on new code. These are the thresholds enforced by the CI coverage gate on all pull requests.

### Test Types and Naming

| Type | Naming Convention | Runner | Plugin |
|---|---|---|---|
| Unit tests | `*Test.java` | JUnit 4 | maven-surefire-plugin |
| Integration tests | `*IT.java`, `*IntegrationTest.java` | JUnit 4 | maven-failsafe-plugin |
| Functional tests | Located in `tests` module | TestNG | maven-surefire-plugin |
| Cucumber feature tests | `*FeatureTest.java` | Cucumber-JUnit | maven-surefire-plugin |

**Note on Cucumber feature tests**: These run the TinkerPop Gremlin compliance suite (~1900 scenarios). In the `core` module they run by default; in the `embedded` module they are excluded from the default build and only run with `-P ci-integration-tests`.

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
| Line coverage | coverage-gate.py | 85% | New/changed lines only |
| Branch coverage | coverage-gate.py | 70% | New/changed lines only |
| Mutation score | PIT + Arcmutate | 85% | Changed lines + code exercised by modified tests |

All gates must pass for a PR to be mergeable.
