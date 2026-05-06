# Testing Details

Reference material for testing infrastructure beyond the core rules in `CLAUDE.md` § Testing. Load on demand.

## Unit Tests

- **Core and server**: JUnit 4 with `surefire-junit47` runner
- **Tests module**: JUnit 5 with JUnit Platform Suite (`EmbeddedTestSuite`). Tests share a single database instance and execute in a fixed class/method order via `@SelectClasses` and `@Order` annotations
- **Test utilities**: `test-commons` module provides shared base classes (`TestBuilder`, `TestFactory`, `ConcurrentTestHelper`)

## Integration Tests

- Activated via Maven profile: `./mvnw clean verify -P ci-integration-tests`
- Uses failsafe plugin in `core` and `server` modules

## TinkerPop Cucumber Feature Tests

- Validate full Gremlin compliance by running the TinkerPop Cucumber scenario suite (~1900 scenarios)
- Present in both `core` (`YTDBGraphFeatureTest`) and `embedded` (`EmbeddedGraphFeatureTest`) modules
- **`core`**: runs by default with `mvn test` (always included)
- **`embedded`**: runs by default with `mvn test` (always included)
- Uses Cucumber-JUnit runner with Guice DI; graph datasets (MODERN, CLASSIC, CREW, GRATEFUL, SINK) are loaded once per JVM via static initializers
- Requires `-Xms4096m -Xmx4096m` heap for the GRATEFUL dataset

## Docker Tests

- Module: `docker-tests`, requires `docker-images` Maven profile
- Uses Testcontainers to run server and console Docker images
- Debug containers: set `-Dytdb.testcontainer.debug.container=true`

## Benchmarks

- **LDBC SNB benchmarks** in `jmh-ldbc/` — 20 read queries (IS1-IS7, IC1-IC13) using YouTrackDB SQL, with single-threaded and multi-threaded (one thread per available processor) suites. Run via `./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests` (single command) or `./mvnw -pl jmh-ldbc -am compile exec:exec` (two-step). See `jmh-ldbc/README.md` for full documentation.
- Legacy JMH benchmarks in `tests/src/test/java/.../benchmarks/`

## Common Test JVM Properties

Tests configure YouTrackDB-specific system properties in `<argLine>`:
- `-Dyoutrackdb.storage.diskCache.bufferSize=4096`
- `-Dyoutrackdb.security.createDefaultUsers=false`
- `-Dyoutrackdb.storage.diskCache.checksumMode=StoreAndThrow`
- `-Dyoutrackdb.memory.directMemory.trackMode=true`
