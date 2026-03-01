# CLAUDE.md - YouTrackDB Project Guide

## Project Overview

YouTrackDB is a general-purpose object-oriented graph database developed by JetBrains, used internally in production. It implements the Apache TinkerPop API with Gremlin query language support and features O(1) link traversal, schema-less/mixed/full modes, and encryption at rest. The project is a fork of OrientDB, re-architected under the `com.jetbrains.youtrackdb` package namespace.

- **License**: Apache 2.0
- **JDK**: 21+ required
- **Build**: Maven with Maven Wrapper (`./mvnw`)
- **Group ID**: `io.youtrackdb`
- **Version**: `0.5.0-SNAPSHOT` (CI-friendly: `${revision}${sha1}${changelist}`)
- **Issue tracker**: https://youtrack.jetbrains.com/issues/YTDB
- **Repository**: https://github.com/JetBrains/youtrackdb

## Build Commands

```bash
# Full build (skip tests for speed)
./mvnw clean package -DskipTests

# Full build with unit tests
./mvnw clean package

# Run integration tests
./mvnw clean verify -P ci-integration-tests

# Build with Docker images (requires Docker)
./mvnw clean package -P docker-images

# Run a single test class
./mvnw -pl core clean test -Dtest=SomeTestClass

# Run a single test method
./mvnw -pl core clean test -Dtest=SomeTestClass#testMethodName
```

**JVM memory**: `.mvn/jvm.config` sets `-Xmx1024m` for Maven itself. Tests use `-Xms4096m -Xmx4096m` (configurable via `heapSize` property).

**Important**: Tests require numerous `--add-opens` JVM flags for Java module system compatibility. These are configured in each module's `pom.xml` `<argLine>` property - do not remove them.

## Project Documentation

The `docs/` folder contains project documentation including CI/CD pipeline architecture, test quality requirements, and infrastructure setup guides. See `docs/README.md` for the full index.

## Module Structure

| Module | Artifact | Purpose |
|---|---|---|
| `core` | `youtrackdb-core` | Core database engine, public API, Gremlin integration, SQL parser, storage engine |
| `server` | `youtrackdb-server` | Gremlin Server implementation (Docker image) |
| `driver` | `youtrackdb-driver` | Remote Gremlin driver for server connections |
| `console` | `youtrackdb-console` | Gremlin REPL console (Docker image) |
| `lucene` | `youtrackdb-lucene` | **Excluded from build.** Kept as reference code for future reimplementation of Lucene full-text and spatial indexing. |
| `gremlin-annotations` | `youtrackdb-gremlin-annotations` | Annotation processor for Gremlin DSL code generation |
| `tests` | `youtrackdb-tests` | Integration/functional test suite (TestNG) |
| `test-commons` | `youtrackdb-test-commons` | Shared test utilities (JUnit 4, Mockito, AssertJ) |
| `embedded` | `youtrackdb-embedded` | Uber-jar with relocated third-party deps; includes TinkerPop Cucumber feature tests (CI only) |
| `docker-tests` | `youtrackdb-docker-tests` | Docker image tests (Testcontainers) |
| `examples` | `youtrackdb-examples` | Example applications |

## Package Structure

Root package: `com.jetbrains.youtrackdb`

### Public API (`com.jetbrains.youtrackdb.api`)
- `YourTracks` - Factory entry point, creates `YouTrackDB` instances (embedded or remote)
- `YouTrackDB` - Main interface for database lifecycle (create, drop, list, openTraversal, restore)
- `DatabaseType` - Enum: `DISK` or `MEMORY`
- `api/config/GlobalConfiguration` - All configuration parameters
- `api/gremlin/` - Custom Gremlin DSL: `YTDBGraphTraversalSourceDSL`, `YTDBGraphTraversalDSL`
- `api/gremlin/embedded/` - Embedded graph elements: `YTDBVertex`, `YTDBEdge`, schema classes
- `api/exception/` - Public exceptions: `ConcurrentModificationException`, `RecordDuplicatedException`, `RecordNotFoundException`

### Internal (`com.jetbrains.youtrackdb.internal`)
- `internal/common/` - Low-level utilities (collections, concurrency, direct memory, I/O, hashing, serialization)
- `internal/core/engine/` - Storage engine abstraction (SPI via `Engine` interface)
- `internal/core/storage/` - Page-based storage: disk cache, WAL, collections, indices
- `internal/core/gremlin/` - Gremlin graph implementation (`YTDBGraphEmbedded`, `YTDBGraphFactory`)
- `internal/core/sql/parser/` - JavaCC-generated SQL parser (from `core/src/main/grammar/YouTrackDBSql.jjt`)
- `internal/core/tx/` - Transaction management
- `internal/core/index/` - Index implementations (B-tree based)
- `internal/core/metadata/` - Schema and metadata management
- `internal/server/` - Server implementation (`ServerMain`, `YouTrackDBServer`)
- `internal/driver/` - Remote driver (`YouTrackDBRemote`)

## Key Architecture Concepts

### Storage Engine
- **Page-based**: Default 8 KB pages, configurable via `DISK_CACHE_PAGE_SIZE`
- **Two engine types** loaded via Java SPI (`META-INF/services`):
  - `EngineLocalPaginated` (disk) - Full disk storage with WAL and double-write log
  - `EngineMemory` (memory) - In-memory storage using direct memory buffers
- **Two-tier cache**: `ReadCache` (LockFreeReadCache) + `WriteCache` (WOWCache)
- **WAL** (Write-Ahead Logging): `LogSequenceNumber` (segment, position) pairs, atomic operations
- **DurableComponent**: Base class for all crash-recoverable data structures

### Gremlin Integration
- Uses a **custom fork** of Apache TinkerPop (group ID `io.youtrackdb` instead of `org.apache.tinkerpop`)
- Custom DSL classes generated at compile time by `GremlinDslProcessor` annotation processor
- Transaction API: `executeInTx()`, `computeInTx()`, `autoExecuteInTx()` on `YTDBGraphTraversalSourceDSL`

### Record IDs (RID)
- Located at `core/.../internal/core/db/record/record/RID.java`
- Format: `#clusterId:clusterPosition` (e.g., `#23:1`)

### Generated Code
- **SQL Parser**: Generated from `core/src/main/grammar/YouTrackDBSql.jjt` via javacc-maven-plugin. Output goes to `core/.../internal/core/sql/parser/`. Do not edit generated parser files.
- **Gremlin DSL**: Generated by `gremlin-annotations` module's annotation processor

## Code Style

Java code style is defined in `.idea/codeStyles/Project.xml`:

- **Indent**: 2 spaces (Java, XML, JSON, etc.)
- **Continuation indent**: 4 spaces
- **Line width**: 100 characters
- **Braces**: Always required for `if`, `while`, `for`, `do-while` (force braces = always)
- **Imports**: No wildcard imports (threshold set to 999); import order: module imports, static imports, blank line, regular imports
- **Wrapping**: Wrap if long for parameters, extends, throws, method chains, binary/ternary operations
- **Binary operators**: Sign on next line when wrapping
- **Blank lines**: 1 blank line after class header, max 1 blank line in code

### Comments and Documentation
- **Comment non-obvious code**: Add comments to any logic that is not immediately self-evident, so reviewers can easily verify intent without reverse-engineering the code.
- **Test descriptions**: Every test must have a detailed description (in a comment or descriptive method name) explaining what scenario is being tested and what the expected outcome is, so a reviewer can quickly grasp the purpose.
- **Keep comments in sync**: When modifying code, always update the surrounding comments to match the new behavior. Stale or contradictory comments are worse than no comments.

## Testing

### Test Requirements
- **All code changes must have associated tests** that cover the new or modified behavior.
- **All bug fixes must include a regression test** reproducing the bug, unless one already exists.
- Prefer adding tests to **existing test classes** when the change fits their scope. Only create new test classes when there is no suitable existing one.
- **Coverage target**: 85% line coverage and 70% branch coverage for new/changed code (enforced by CI coverage gate).
- **Coverage verification**: Always use the `coverage-gate.py` script (see [Pre-Commit Verification](#pre-commit-verification)) to check coverage instead of computing it by hand. The script contains special-case logic — for example, it excludes Java `assert` statement lines (including multi-line continuations) from both line and branch coverage calculations, because JaCoCo reports phantom uncovered branches and unreachable failure-message lines for asserts. Manual arithmetic will not account for these exclusions and will give incorrect results.

### Unit Tests
- **Core and server**: JUnit 4 with `surefire-junit47` runner
- **Tests module**: TestNG with XML suite files (e.g., `embedded-test-db-from-scratch.xml`)
- **Test utilities**: `test-commons` module provides shared base classes (`TestBuilder`, `TestFactory`, `ConcurrentTestHelper`)

### Integration Tests
- Activated via Maven profile: `./mvnw clean verify -P ci-integration-tests`
- Uses failsafe plugin in `core` and `server` modules

### TinkerPop Cucumber Feature Tests
- Validate full Gremlin compliance by running the TinkerPop Cucumber scenario suite (~1900 scenarios)
- Present in both `core` (`YTDBGraphFeatureTest`) and `embedded` (`EmbeddedGraphFeatureTest`) modules
- **`core`**: runs by default with `mvn test` (always included)
- **`embedded`**: excluded from default `mvn test` (heavyweight); only runs with `-P ci-integration-tests`
- Uses Cucumber-JUnit runner with Guice DI; graph datasets (MODERN, CLASSIC, CREW, GRATEFUL, SINK) are loaded once per JVM via static initializers
- Requires `-Xms4096m -Xmx4096m` heap for the GRATEFUL dataset

### Docker Tests
- Module: `docker-tests`, requires `docker-images` Maven profile
- Uses Testcontainers to run server and console Docker images
- Debug containers: set `-Dytdb.testcontainer.debug.container=true`

### Benchmarks
- JMH benchmarks in `tests/src/main/java/.../benchmarks/`

### Common Test JVM Properties
Tests configure YouTrackDB-specific system properties in `<argLine>`:
- `-Dyoutrackdb.storage.diskCache.bufferSize=4096`
- `-Dyoutrackdb.security.createDefaultUsers=false`
- `-Dyoutrackdb.storage.diskCache.checksumMode=StoreAndThrow`
- `-Dyoutrackdb.memory.directMemory.trackMode=true`

## Git Conventions

### Branches
- **`develop` is the default development branch** for this project, not `main`.
- `main` - Used for delivery of artifacts once all tests on `develop` have passed (auto-merged from develop nightly after integration tests pass)
- Feature branches: `ytdb-NNN-description` or `YTDB-NNN/description`

### Commit Messages
- **Must** contain a YTDB issue prefix: `YTDB-123: Fix description`
- Enforced by `check-commit-prefix.yml` CI workflow
- Git hook `.githooks/prepare-commit-msg` auto-prepends prefix based on branch name
- **Format**:
  ```
  YTDB-123: <imperative summary, under 50 chars>

  <detailed explanation of WHY this change was made — motivation, context,
  trade-offs. Not a restatement of the diff.>
  ```

### Force Pushing
- **Always use `--force-with-lease`** instead of `--force` when force pushing. This prevents accidentally overwriting commits pushed by others since your last fetch.

### Pull Requests
- **No merge commits** (enforced by CI - `block-merge-commits.yml`)
- PR title auto-prefixed with YTDB issue number from branch name
- Target branch: `develop`
- **Must use the PR template** at `.github/pull_request_template.md`. Every PR must include the Motivation section explaining WHY the change was made.

## CI/CD

### Primary Pipeline (`maven-pipeline.yml`)
Runs on `develop` pushes and PRs:
- **Change detection**: Skips CI for non-build-relevant changes (markdown, docs, etc.)
- **Concurrency**: Cancels in-progress builds when new commits arrive on the same PR/branch
- **Test matrix**: JDK 21+25, 2 distributions (temurin, oracle), 3 configurations (Linux x86, Linux arm, Windows x64)
- **Integration tests**: Run on Linux with Ekstazi test selection caching
- **Coverage gate**: Enforces 85% line coverage and 70% branch coverage on new/changed code for all PRs. Uses a unified script (`coverage-gate.py`) that parses git diff + JaCoCo XML and posts a PR comment with per-file coverage tables. Coverage data collected on Linux x86, JDK 21, temurin.
- **Ekstazi exclude files**: Uploaded as the `ekstazi-excludes` artifact (retained 7 days). Contains `/tmp/ekstazi-*.excludes` files listing which integration tests Ekstazi skipped. Use these to diagnose coverage gate failures caused by Ekstazi test selection (see "Investigating Coverage Gate Failures" below).
- **Mutation testing**: PIT mutation testing on changed classes with PIT's own coverage-based test selection, fails below 85% mutation score
- **Deploy**: Publishes `-dev-SNAPSHOT` artifacts to Maven Central on develop pushes
- **CI Status gate**: Consolidates all checks (test-linux, test-windows, coverage-gate, mutation-testing) into a single required status for branch protection
- **Notifications**: Sends Zulip messages on build failure/recovery

### Nightly Integration Tests (`maven-integration-tests-pipeline.yml`)
- Runs at 2 AM UTC, skips if current SHA was already tested successfully
- Tests on Linux (x86+arm) and Windows with JDK 21+25, 2 distributions (temurin, oracle)
- Auto-merges `develop` to `main` (fast-forward only) on success
- Sends Zulip notifications on failure/recovery

### Main Deploy (`maven-main-deploy-pipeline.yml`)
- Triggered on `main` pushes
- Deploys snapshot and timestamped artifacts to Maven Central
- Builds and pushes multi-arch (x64+arm64) Docker images to Docker Hub
- Sends Zulip notifications on failure/recovery

### Guard Workflows
- **check-commit-prefix.yml**: Enforces `YTDB-NNN:` prefix on commit messages
- **block-merge-commits.yml**: Prevents merge commits in PRs
- **pr-title-prefix.yml**: Auto-prefixes PR titles with YTDB issue number from branch name

## Key Entry Points

| Class | Module | Role |
|---|---|---|
| `YourTracks` | core | Factory - creates `YouTrackDB` instances |
| `YouTrackDB` | core | Main interface - database lifecycle |
| `YTDBGraphFactory` | core | Internal factory for embedded graph instances |
| `YTDBGraphEmbedded` | core | Embedded graph implementation |
| `ServerMain` | server | Server entry point (`main` method) |
| `YouTrackDBServer` | server | Server implementation |
| `YouTrackDBRemote` | driver | Remote driver (loaded via reflection from `YourTracks`) |
| `GlobalConfiguration` | core | All configurable parameters |
| `DiskStorage` | core | Disk-based paginated storage implementation |
| `AbstractStorage` | core | Base class for storage implementations |

## Key Dependencies

- Apache TinkerPop Gremlin (custom fork: `io.youtrackdb:gremlin-*` v3.8.1). Version is published as a `-<commitSHA>-SNAPSHOT` (e.g. `3.8.1-fccfc5a-SNAPSHOT`); the commit SHA suffix changes with each fork update - check the `gremlin.version` property in the root `pom.xml` for the current value.
- GraalVM (JavaScript scripting via Gremlin)
- Jackson 2.20.x (JSON serialization)
- SLF4J 2.x + Log4j 2.25.x (logging)
- Guava, fastutil (collections)
- LZ4 (compression)
- BouncyCastle (TLS in server)

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
   - Changes to `embedded` module: run `./mvnw -pl embedded clean test` (smoke test only); use `-P ci-integration-tests` to include Cucumber feature tests
   - Changes to `tests` module: run `./mvnw -pl tests clean test`
   - If in doubt, run the full test suite: `./mvnw clean package`

## Investigating Coverage Gate Failures

When the coverage gate fails on a PR, **always check the Ekstazi exclude files first** before writing new tests. Integration tests use Ekstazi test selection, which may skip tests that would have covered the changed lines.

1. **Download the `ekstazi-excludes` artifact** from the failed CI run (available for 7 days).
2. **Examine the exclude files** (`ekstazi-*.excludes`). Each file lists the integration tests that Ekstazi skipped for that module.
3. **Cross-reference** the uncovered lines (from the coverage gate PR comment) with the excluded tests:
   - If excluded tests would cover the uncovered lines → the coverage gap is an **Ekstazi selection artifact**, not genuinely missing coverage. To fix this, first try invalidating the Ekstazi cache (by deleting the `ekstazi-*` cache entries in GitHub Actions) and re-running the job. If the problem persists, you may need to adjust the Ekstazi dependency configuration.
   - If no existing tests (included or excluded) cover the uncovered lines → the coverage gap is **genuine**. Write new tests targeting the uncovered code paths.
4. **Do not blindly write duplicate tests** for code that is already tested by Ekstazi-excluded integration tests.

## File Modification Rules

- **Always use the `Edit` and `Write` tools** to create or modify files. Do not use shell commands (`cat`, `echo`, `sed`, `awk`, `tee`, or redirection operators `>`, `>>`) to write or modify files.
- **Use the `Read` tool** to read file contents instead of `cat`, `head`, or `tail`.
- **Use `Glob` and `Grep` tools** for file search instead of `find`, `grep`, or `rg` shell commands.
- **Shell utilities in pipelines**: Commands like `grep`, `cat`, `head`, `find`, `sed`, `awk` are permitted when used in shell pipelines (e.g., `git log | grep ...`, `find ... | xargs ...`) where dedicated tools cannot substitute. Prefer dedicated tools for standalone file reads and searches.
- **Temporary files**: Use `/tmp/claude-code-*` for scratch files, intermediate build artifacts, or staging data. This is the only `/tmp` path with read/write/edit permissions.
- Reserve `Bash` exclusively for build commands (`./mvnw`), git operations, `gh` CLI, `docker`, and other tools that genuinely require shell execution.

## Tips for Working with This Codebase

1. **Always use `./mvnw`** (Maven Wrapper) instead of system Maven
2. **The `core` module is massive** - most logic lives here. When searching, start in `core/src/main/java/`
3. **Don't edit files in `core/.../sql/parser/`** - they are generated from `YouTrackDBSql.jjt`
4. **Public API vs Internal**: Only classes in `com.jetbrains.youtrackdb.api` are public API. Everything under `internal` is implementation detail
5. **SPI pattern**: Engines, indexes, collations, SQL functions are loaded via `META-INF/services` (Java ServiceLoader)
6. **Custom TinkerPop fork**: The project uses its own fork of TinkerPop under `io.youtrackdb` group ID - don't confuse with upstream `org.apache.tinkerpop`
7. **Test infrastructure**: Core tests use JUnit 4; the `tests` module uses TestNG. Don't mix them
8. **Docker images**: Built only with `-P docker-images` profile. Server listens on port 8182
9. **The `lucene` module is excluded from the build** - it exists only as reference code for future reimplementation
10. **JaCoCo and Java `assert` statements**: JaCoCo 0.8.8+ filters the synthetic `$assertionsDisabled` branch but **not** the assertion condition's own true/false branch. An inline `assert x != null` always shows 1/2 branches covered because the `false` path (assertion failure) is never taken in normal tests. To get full branch coverage, extract the check into a static helper method (e.g. `MatchAssertions.checkNotNull()`) that can be unit-tested independently for both outcomes, then call it as `assert MatchAssertions.checkNotNull(x, "label")`. The helper method gets 100% branch coverage; only the `assert` call site retains 1 phantom uncovered branch.
11. **Gremlin annotation processor**: Gremlin DSL classes are generated automatically during `core` compilation via the `GremlinDslProcessor` annotation processor (from the `gremlin-annotations` module). Maven resolves the module dependency, so no special build order is needed — just include both modules: `./mvnw -pl gremlin-annotations,core clean package`
