# AGENTS.md - YouTrackDB Project Guide

## Project Overview

YouTrackDB is a general-purpose object-oriented graph database developed by JetBrains, used internally in production. It implements the Apache TinkerPop API with Gremlin query language support and features O(1) link traversal, schema-less/mixed/full modes, and encryption at rest. The project is a fork of OrientDB, re-architected under the `com.jetbrains.youtrackdb` package namespace.

- **License**: Apache 2.0
- **JDK**: 21+ required
- **Build**: Maven with Maven Wrapper (`./mvnw`)
- **Group ID**: `io.youtrackdb`
- **Version**: `0.5.0-SNAPSHOT` (CI-friendly: `${revision}${sha1}${changelist}`)
- **Issue tracker**: https://youtrack.jetbrains.com/issues/YTDB (YouTrack project code: `YTDB`)
- **Repository**: https://github.com/JetBrains/youtrackdb

## Build Commands

```bash
# Full build (skip tests for speed)
./mvnw clean package -DskipTests

# Full build with unit tests (in-memory storage, default)
./mvnw clean package

# Full build with unit tests on disk storage (as CI does)
./mvnw clean package -Dyoutrackdb.test.env=ci

# Run integration tests (separate from PR pipeline, used by nightly CI)
./mvnw clean verify -P ci-integration-tests

# Build with Docker images (requires Docker)
./mvnw clean package -P docker-images

# Run a single test class
./mvnw -pl core clean test -Dtest=SomeTestClass

# Run a single test method
./mvnw -pl core clean test -Dtest=SomeTestClass#testMethodName
```

**JVM memory**: `.mvn/jvm.config` sets `-Xmx8192m` for Maven itself. Tests use `-Xms4096m -Xmx4096m` (configurable via `heapSize` property).

**Important**: Tests require numerous `--add-opens` JVM flags for Java module system compatibility. These are configured in each module's `pom.xml` `<argLine>` property — do not remove them.

## Project Documentation

The `docs/` folder contains project documentation. See `docs/README.md` for the index.

## Architecture

Storage engine, Gremlin integration, RID format, generated code pipeline, and the table of key entry-point classes (`YourTracks`, `YouTrackDB`, `ServerMain`, `GlobalConfiguration`, `DiskStorage`, `AbstractStorage`, etc.) are documented in `.claude/docs/architecture.md`. Load on demand when the change actually touches storage / Gremlin / parser / generated-code areas.

## Code Style

- **Indent**: 2 spaces (Java, XML, JSON, etc.)
- **Continuation indent**: 4 spaces
- **Line width**: 100 characters
- **Braces**: Always required for `if`, `while`, `for`, `do-while` (force braces = always)
- **Imports**: No wildcard imports (threshold set to 999); import order: static imports first, then regular imports (enforced by Spotless)
- **Wrapping**: Wrap if long for parameters, extends, throws, method chains, binary/ternary operations
- **Binary operators**: Sign on next line when wrapping
- **Blank lines**: 1 blank line after class header, max 1 blank line in code

### Comments and Documentation
- **Comment non-obvious code**: Add comments to any logic that is not immediately self-evident, so reviewers can easily verify intent without reverse-engineering the code.
- **Test descriptions**: Every test must have a detailed description (in a comment or descriptive method name) explaining what scenario is being tested and what the expected outcome is, so a reviewer can quickly grasp the purpose.
- **Keep comments in sync**: When modifying code, always update the surrounding comments to match the new behavior. Stale or contradictory comments are worse than no comments.

### Formatting (Spotless)

Code formatting is enforced by [Spotless](https://github.com/diffplug/spotless) (`com.diffplug.spotless:spotless-maven-plugin`), which runs the `check` goal automatically during the `process-sources` phase of every build. Builds will fail if formatting violations are found.

- **Formatter**: Eclipse formatter configured in `project-config/eclipse-formatter.xml`
- **Ratchet mode**: Only files changed since the `spotless-baseline` git tag are checked — existing code is not reformatted
- **Import order**: Static imports first (`\#`), then regular imports
- **Excludes**: Generated code (`**/internal/core/sql/parser/**`, `**/generated-sources/**`, `**/generated-test-sources/**`)

```bash
# Check formatting (runs automatically during build)
./mvnw spotless:check

# Auto-fix formatting violations
./mvnw spotless:apply

# Check/fix for a single module
./mvnw -pl core spotless:check
./mvnw -pl core spotless:apply
```

**After modifying code, always run `./mvnw -pl {module} spotless:apply`** before committing to ensure formatting compliance. If the build fails with a Spotless error, run `spotless:apply` to auto-fix.

## Testing

### Test Requirements
- **NEVER run multiple test processes simultaneously in the same worktree/directory.** Always wait for one `./mvnw test` or `./mvnw verify` invocation to finish before starting another in the same working directory. Running tests in parallel within the same worktree causes classloading errors, database file locking conflicts, and false test failures. This applies to all test execution — unit tests, integration tests, and coverage runs. Tests in separate worktrees/directories do not conflict.
- **All code changes must have associated tests** that cover the new or modified behavior.
- **All bug fixes must include a regression test** reproducing the bug, unless one already exists.
- Prefer adding tests to **existing test classes** when the change fits their scope. Only create new test classes when there is no suitable existing one.
- **Coverage target**: 85% line coverage and 70% branch coverage for new/changed code (enforced by CI coverage gate).
- **Coverage verification**: Always use the `coverage-gate.py` script (see [Pre-Commit Verification](#pre-commit-verification)) to check coverage instead of computing it by hand. The script contains special-case logic — for example, it excludes Java `assert` statement lines (including multi-line continuations) from both line and branch coverage calculations, because JaCoCo reports phantom uncovered branches and unreachable failure-message lines for asserts. Manual arithmetic will not account for these exclusions and will give incorrect results.

### Test Modules at a Glance
- **Unit tests**: `./mvnw -pl <module> clean test`. Core/server use JUnit 4 (`surefire-junit47` runner); the `tests` module uses JUnit 5 with `EmbeddedTestSuite` (shared DB, fixed class/method order via `@SelectClasses` / `@Order`).
- **Integration tests**: `./mvnw clean verify -P ci-integration-tests` (uses failsafe in `core` and `server`).
- **Test utilities**: `test-commons` provides `TestBuilder`, `TestFactory`, `ConcurrentTestHelper`.

For TinkerPop Cucumber feature-test details (~1900 scenarios), Docker tests, LDBC and legacy JMH benchmarks, and the per-test JVM properties (`bufferSize`, `createDefaultUsers`, `checksumMode`, `directMemory.trackMode`): see `.claude/docs/testing-details.md`.

## Git Conventions

### Branches
- **`develop` is the default development branch** for this project, not `main`.
- `main` - Used for delivery of artifacts once all tests on `develop` have passed (auto-merged from develop nightly after integration tests pass)

### Commit Messages
- The YTDB issue number is carried in the PR title only (auto-prefixed from the branch name by `.github/workflows/pr-title-prefix.yml`). Individual commit subjects do not need it — the squash-merge takes its message from the PR title and description.
- **Format**:
  ```
  [Imperative summary, under 50 chars]

  [Detailed explanation of WHY this change was made — motivation, context,
  trade-offs. Not a restatement of the diff.]
  ```

### Force Pushing
- **Always use `--force-with-lease`** instead of `--force` when force pushing. This prevents accidentally overwriting commits pushed by others since your last fetch.

### Pull Requests
- **No merge commits** (enforced by CI - `block-merge-commits.yml`)
- PR title auto-prefixed with YTDB issue number from branch name
- **Multiple issues**: when a PR addresses several issues, list them all in the title, comma-separated and wrapped in square brackets: `[YTDB-123, YTDB-456] <summary>`.
- Target branch: `develop`
- **1 PR = 1 squashed commit** — all branch commits are squashed on merge
- **Must use the PR template** at `.github/pull_request_template.md`. Every PR must include the Motivation section explaining WHY the change was made.
- **Keep the PR title and description in sync with follow-up commits.** The squashed commit message is built from the PR title and description, not from individual commit messages — update them with every push so the merge commit reflects all changes.
- **Test count gate bypass**: Add `[no-test-number-check]` to the PR title to skip the test count gate. Use this only for intentional test refactorings that restructure or consolidate tests without reducing coverage.

### Rebase Conflict Resolution
- When a rebase produces conflicts in prose-heavy files (e.g., `AGENTS.md` or `docs/adr/**`), re-read each resolved section end-to-end before continuing. Three-way merges on prose-heavy files can yield text that parses but no longer makes sense: half of a procedure from one side spliced to half from the other, rules that contradict themselves across paragraphs, cross-references to sections that no longer exist, threshold tables drifted from the prose that cites them. Tests catch broken code; nothing catches broken prose except a reader.
- The recheck covers the whole document, not just the conflict hunks. A clean three-way resolution at the hunk level can still leave the surrounding section semantically broken — for example, a rule renamed on `develop` but still referenced under its old name in unchanged paragraphs on the branch side. Scan the entire document end-to-end to confirm it still tells a single, consistent story.

## Pre-Commit Verification

**Every task MUST be verified before committing.** Follow this workflow:

1. **Run unit tests** for the affected module(s) before committing:
   ```bash
   # If changes are in core module
   ./mvnw -pl core clean test

   # If changes span multiple modules, test all affected ones
   ./mvnw -pl core,server clean test

   # If unsure which modules are affected, run the full unit test suite
   ./mvnw clean package
   ```

2. **Run related integration tests** if the change touches areas covered by integration tests:
   ```bash
   # Run integration tests for the affected module(s)
   ./mvnw -pl core clean verify -P ci-integration-tests

   # Or run the full integration test suite
   ./mvnw clean verify -P ci-integration-tests
   ```

3. **Check coverage of changed code** by running tests with the `coverage` profile and verifying coverage locally:
   ```bash
   # Run unit tests with coverage collection
   ./mvnw clean package -P coverage

   # Check coverage of changed lines against thresholds (85% line, 70% branch)
   python3 .github/scripts/coverage-gate.py \
     --line-threshold 85 \
     --branch-threshold 70 \
     --compare-branch origin/develop \
     --coverage-dir .coverage/reports
   ```
   If coverage is below the threshold, add or improve tests for uncovered lines before committing.

4. **Do not commit if tests fail.** Fix the failures first, then re-run the tests.

5. **Determining which tests to run:**
   - Changes to `core` module: always run `./mvnw -pl core clean test`
   - Changes to `server` module: run `./mvnw -pl server clean test`
   - Changes to storage, WAL, or index code: also run integration tests (`-P ci-integration-tests`)
   - Changes to Gremlin integration or transaction handling: also run integration tests
   - Changes to `embedded` module: run `./mvnw -pl embedded clean test` (includes Cucumber feature tests)
   - Changes to `tests` module: run `./mvnw -pl tests clean test`
   - If in doubt, run the full test suite: `./mvnw clean package`

## Tips for Working with This Codebase

1. **Always use `./mvnw`** (Maven Wrapper) instead of system Maven
2. **The `core` module is massive** — most logic lives here. When searching, start in `core/src/main/java/`
3. **Don't edit files in `core/.../sql/parser/`** — they are generated from `YouTrackDBSql.jjt`
4. **Public API vs Internal**: Only classes in `com.jetbrains.youtrackdb.api` are public API. Everything under `internal` is implementation detail
5. **SPI pattern**: Engines, indexes, collations, SQL functions are loaded via `META-INF/services` (Java ServiceLoader)
6. **Custom TinkerPop fork**: The project uses its own fork of TinkerPop under `io.youtrackdb` group ID — don't confuse with upstream `org.apache.tinkerpop`
7. **Test infrastructure**: Core tests use JUnit 4; the `tests` module uses JUnit 5 (Jupiter) with JUnit Platform Suite for ordered execution
8. **The `lucene` module is excluded from the build** — it exists only as reference code for future reimplementation

The JaCoCo+`assert` coverage trap and the Gremlin annotation-processor build details are in `.claude/docs/architecture.md`.

## Documentation Sync

### When to Update Documentation

1. **When modifying source code**: Review docs in `docs/` and module `README.md` files to see if any cover the area you changed. Update them if needed.
2. **When adding new features**: If the feature affects public API, configuration, build process, or CI/CD, update the relevant docs.
