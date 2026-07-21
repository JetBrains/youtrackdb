# Worker Thread Guidelines

Hands-on engineering rules for worker threads — build commands, code style, testing, committing,
and codebase navigation (may already be injected automatically by the slate extension). Planning,
verification-scope, and PR rules live in `docs-internal/agents/orchestrator-guidelines.md`.

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

# Run integration tests for the affected module(s)
./mvnw -pl core clean verify -P ci-integration-tests

# If changes span multiple modules, test all affected ones
./mvnw -pl core,server clean test

# Build with Docker images (requires Docker)
./mvnw clean package -P docker-images

# Run a single test class
./mvnw -pl core clean test -Dtest=SomeTestClass

# Run a single test method
./mvnw -pl core clean test -Dtest=SomeTestClass#testMethodName
```

**JVM memory**: `.mvn/jvm.config` sets `-Xmx8192m` for Maven itself. Tests use `-Xms4096m -Xmx4096m` (configurable via `heapSize` property).

**Important**: Tests require numerous `--add-opens` JVM flags for Java module system compatibility. These are configured in each module's `pom.xml` `<argLine>` property — do not remove them.

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
- **Design decisions**: Design rationale lives in JavaDoc at the scope it governs. Before writing or editing a design-decision section, read `docs-internal/dev-workflow/design-decisions.md` (scoping ladder, entry structure, staleness rule).

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

### Test Authorship

- **All code changes must have associated tests** that cover the new or modified behavior; bug fixes must include a regression test reproducing the bug, unless one already exists.
- Prefer adding tests to **existing test classes** when the change fits their scope; only create new test classes when there is no suitable existing one.
- Coverage target for new/changed code: 85% line / 70% branch — verify with `coverage-gate.py` (see [Coverage Verification](#coverage-verification) below).

### Test Execution

**NEVER run multiple test processes simultaneously in the same worktree/directory.** Always wait for one `./mvnw test` or `./mvnw verify` invocation to finish before starting another in the same working directory. Running tests in parallel within the same worktree causes classloading errors, database file locking conflicts, and false test failures. This applies to all test execution — unit tests, integration tests, and coverage runs. Tests in separate worktrees/directories do not conflict. The rule extends to any concurrent Maven invocations in the same worktree — wait for a running build to complete before starting another build or test run there.

### Test Modules at a Glance
- **Unit tests**: `./mvnw -pl <module> clean test`. Core/server use JUnit 4 (`surefire-junit47` runner); the `tests` module uses JUnit 5 with `EmbeddedTestSuite` (shared DB, fixed class/method order via `@SelectClasses` / `@Order`).
- **Integration tests**: `./mvnw clean verify -P ci-integration-tests` (uses failsafe in `core` and `server`).
- **Test utilities**: `test-commons` provides `TestBuilder`, `TestFactory`, `ConcurrentTestHelper`.

For TinkerPop Cucumber feature-test details (~1900 scenarios), Docker tests, LDBC and legacy JMH benchmarks, and the per-test JVM properties (`bufferSize`, `createDefaultUsers`, `checksumMode`, `directMemory.trackMode`): see `.claude/docs/testing-details.md`.

### Coverage Verification

Always use the `coverage-gate.py` script (invocation below) to check coverage instead of computing it by hand. The script contains special-case logic — for example, it excludes Java `assert` statement lines (including multi-line continuations) from both line and branch coverage calculations, because JaCoCo reports phantom uncovered branches and unreachable failure-message lines for asserts. Manual arithmetic will not account for these exclusions and will give incorrect results.

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

## Committing

- **Never commit if tests fail.** Fix the failures first, then re-run the tests.
- The YTDB issue number is carried in the PR title only (auto-prefixed from the branch name by `.github/workflows/pr-title-prefix.yml`). Individual commit subjects do not need it — the squash-merge takes its message from the PR title and description.
- **Format**:
  ```
  [Imperative summary, under 50 chars]

  [Detailed explanation of WHY this change was made — motivation, context,
  trade-offs. Not a restatement of the diff.]
  ```

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
