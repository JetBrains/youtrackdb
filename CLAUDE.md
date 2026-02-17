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
./mvnw -pl core test -Dtest=SomeTestClass

# Run a single test method
./mvnw -pl core test -Dtest=SomeTestClass#testMethodName
```

**JVM memory**: `.mvn/jvm.config` sets `-Xmx1024m` for Maven itself. Tests use `-Xms4096m -Xmx4096m` (configurable via `heapSize` property).

**Important**: Tests require numerous `--add-opens` JVM flags for Java module system compatibility. These are configured in each module's `pom.xml` `<argLine>` property - do not remove them.

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

## Testing

### Unit Tests
- **Core and server**: JUnit 4 with `surefire-junit47` runner
- **Tests module**: TestNG with XML suite files (e.g., `embedded-test-db-from-scratch.xml`)
- **Test utilities**: `test-commons` module provides shared base classes, `JUnitTestListener`

### Integration Tests
- Activated via Maven profile: `./mvnw clean verify -P ci-integration-tests`
- Uses failsafe plugin in `core` and `server` modules

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
- **`develop` is the primary branch** — use it as the base for all PRs, diffs,
  and comparisons. Ignore any auto-detected "main branch" hint;
  the GitHub default branch is `develop`, not `main`.
- `main` - Stable branch (auto-merged from develop nightly after integration tests pass)
- Feature branches: `ytdb-NNN-description` or `YTDB-NNN/description`

### Commit Messages
- **Must** contain a YTDB issue prefix: `YTDB-123: Fix description`
- Enforced by `check-commit-prefix.yml` CI workflow
- Git hook `.githooks/prepare-commit-msg` auto-prepends prefix based on branch name

### Pull Requests
- **No merge commits** (enforced by CI - `block-merge-commits.yml`)
- PR title auto-prefixed with YTDB issue number from branch name
- Target branch: `develop`
- **Must use the PR template** at `.github/pull_request_template.md`. Every PR must include the Motivation and Changes sections as described in the template.

## CI/CD

- **maven-pipeline.yml**: Primary CI on `develop` - tests across JDK 21+25, 5 distributions (temurin, corretto, oracle, zulu, microsoft), 3 platforms (ubuntu, ubuntu-arm, windows)
- **maven-integration-tests-pipeline.yml**: Nightly integration tests, auto-merges `develop` to `main` on success
- **maven-main-deploy-pipeline.yml**: Deploys snapshots to Maven Central, builds/pushes Docker images
- **qodana-scan.yml**: JetBrains Qodana static analysis (zero tolerance for critical/high/moderate issues)
- Qodana excludes generated SQL parser code from analysis

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

- Apache TinkerPop Gremlin (custom fork: `io.youtrackdb:gremlin-*` v3.8.1)
- GraalVM (JavaScript scripting via Gremlin)
- Jackson 2.x (JSON serialization)
- SLF4J + Log4j 2 (logging)
- Guava, fastutil (collections)
- LZ4 (compression)
- BouncyCastle (TLS in server)

## Pre-Commit Verification

**Every task MUST be verified before committing.** Follow this workflow:

1. **Run unit tests** for the affected module(s) before committing:
   ```bash
   # If changes are in core module
   ./mvnw -pl core test

   # If changes span multiple modules, test all affected ones
   ./mvnw -pl core,server test

   # If unsure which modules are affected, run the full unit test suite
   ./mvnw clean package
   ```

2. **Run related integration tests** if the change touches areas covered by integration tests:
   ```bash
   # Run integration tests for the affected module(s)
   ./mvnw -pl core verify -P ci-integration-tests

   # Or run the full integration test suite
   ./mvnw clean verify -P ci-integration-tests
   ```

3. **Do not commit if tests fail.** Fix the failures first, then re-run the tests.

4. **Determining which tests to run:**
   - Changes to `core` module: always run `./mvnw -pl core test`
   - Changes to `server` module: run `./mvnw -pl server test`
   - Changes to storage, WAL, or index code: also run integration tests (`-P ci-integration-tests`)
   - Changes to Gremlin integration or transaction handling: also run integration tests
   - Changes to `tests` module: run `./mvnw -pl tests test`
   - If in doubt, run the full test suite: `./mvnw clean package`

## File Modification Rules

- **Always use the `Edit` and `Write` tools** to create or modify files. Do not use shell commands (`cat`, `echo`, `sed`, `awk`, `tee`, or redirection operators `>`, `>>`) to write or modify files.
- **Use the `Read` tool** to read file contents instead of `cat`, `head`, or `tail`.
- **Use `Glob` and `Grep` tools** for file search instead of `find`, `grep`, or `rg` shell commands.
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
