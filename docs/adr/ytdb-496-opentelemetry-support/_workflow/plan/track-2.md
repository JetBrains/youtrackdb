# Track 2: `youtrackdb-opentelemetry` Maven module skeleton

## Purpose / Big Picture

After this track lands, a new Maven module `youtrackdb-opentelemetry` exists in the reactor with the OTel SDK as a regular `compile`-scope dependency, parent inheritance wired, Spotless and Surefire configured, and a build-time check ensuring `core` and `server` carry no transitive OTel imports.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Create the new module under the root reactor with parent inheritance, OTel BOM-driven dependencies, Spotless config, and an empty package layout ready for the listener implementations. Adds a Maven dependency-arrow check so `core` never gains an OTel import.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

## Context and Orientation

The repository root carries a `pom.xml` that declares `youtrackdb-parent` and lists every module: `test-commons`, `gremlin-annotations`, `core`, `server`, `tests`, `driver`, `examples`, `console`, `embedded`, `docker-tests`, `jmh-ldbc`. Each child module's `pom.xml` declares the parent and inherits the project's standard `argLine`, Spotless config (under `project-config/eclipse-formatter.xml`), and dependency management for shared libraries (`slf4j`, `jackson`, `guice`, `log4j`).

OpenTelemetry Java SDK ships a BOM at `io.opentelemetry:opentelemetry-bom:<version>` that pins compatible artifact versions. The implementation uses `opentelemetry-api`, `opentelemetry-sdk`, `opentelemetry-sdk-extension-autoconfigure` (server mode), and the OTLP exporters. Tests use `opentelemetry-sdk-testing`.

Concrete deliverables:

1. A new directory `youtrackdb-opentelemetry/` at the repo root with `pom.xml`, `src/main/java/`, `src/test/java/`, and an empty package `com/jetbrains/youtrackdb/opentelemetry/` ready for Track 3 code.
2. Root `pom.xml` updated to include `<module>youtrackdb-opentelemetry</module>` in the reactor.
3. Module `pom.xml` declares parent `youtrackdb-parent`, lists OTel dependencies (compile-scope for runtime artifacts, test-scope for `opentelemetry-sdk-testing`), depends on `core` for the listener SPI and on `server` (`provided` scope, optional) for the lifecycle hook.
4. Spotless configuration aligned with the rest of the project. The existing baseline tag (`spotless-baseline`) covers this module by default since it ratchets on changed files.
5. A static check enforcing dependency direction: a Maven Enforcer rule (`bannedDependencies`) on `core` and `server` that fails the build if any OTel artifact appears in the resolved dependency graph.

## Plan of Work

Three edits, each independently shippable.

The first edit creates the directory skeleton and writes the module `pom.xml`. The `dependencyManagement` section imports `opentelemetry-bom`. Dependencies declared without versions inherit from the BOM. The module's `groupId` is `io.youtrackdb`, `artifactId` is `youtrackdb-opentelemetry`, version `${revision}${sha1}${changelist}` per project convention.

The second edit appends `<module>youtrackdb-opentelemetry</module>` to the root reactor pom. After this step a full `./mvnw clean package -DskipTests` builds the new (empty) module and the rest of the reactor without complaint.

The third edit adds the `maven-enforcer-plugin` `bannedDependencies` rule to `core/pom.xml` and `server/pom.xml`, listing `io.opentelemetry:*` in the excludes. Running `./mvnw -pl core,server enforcer:enforce` verifies the dependency-direction invariant holds. A small integration test (`OpenTelemetryDependencyDirectionTest` under `tests` module) could repeat the check programmatically, optional for this track.

Ordering: edits 1 and 2 are dependent (the reactor must list the module before child Spotless config matches). Edit 3 is independent and could swap with edit 2.

## Concrete Steps

<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes

## Validation and Acceptance

After Track 2:

- `./mvnw clean package -DskipTests` succeeds with the new module participating in the reactor.
- `./mvnw -pl youtrackdb-opentelemetry clean test` succeeds (no production code yet; the module is empty).
- `./mvnw -pl core,server enforcer:enforce` fails if any OTel dependency is introduced into `core` or `server`. Confirmed by a manual experiment: temporarily add `opentelemetry-api` to `core/pom.xml`, run enforcer, confirm failure, revert.
- Spotless runs cleanly on the new module's empty Java sources.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes

## Interfaces and Dependencies

In scope:
- New directory `youtrackdb-opentelemetry/` with `pom.xml`, source folders, empty package.
- Root `pom.xml`, `<modules>` section.
- `core/pom.xml` and `server/pom.xml`, Enforcer rule for banned dependencies.

Out of scope:
- All Java code (Track 3 fills the empty package).
- Any test code beyond a sanity test verifying the module compiles (optional).
- Documentation under `docs/` (the design.md inside `_workflow/` is the only durable doc artifact in this phase).

Inter-track dependencies:
- Depends on nothing; runs in parallel with Track 1 in principle, but the plan keeps a linear order for simplicity.
- Provides for Track 3: the Maven module to put code into.
- Provides for Track 4: the module that will own the SQL sanitizer and SQL classifier.
- Provides for Track 5: the module that will own the `YouTrackDBOpenTelemetry` facade.
- Provides for Tracks 6a / 6b / 6c: the module that will own the test suite.

Key dependency versions (pinned via OTel BOM, current stable as of early 2026):

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-bom</artifactId>
      <version>${otel.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-api</artifactId></dependency>
  <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-sdk</artifactId></dependency>
  <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-sdk-extension-autoconfigure</artifactId></dependency>
  <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-exporter-otlp</artifactId></dependency>
  <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-sdk-testing</artifactId><scope>test</scope></dependency>
  <dependency><groupId>io.youtrackdb</groupId><artifactId>youtrackdb-core</artifactId><version>${project.version}</version></dependency>
  <dependency><groupId>io.youtrackdb</groupId><artifactId>youtrackdb-server</artifactId><version>${project.version}</version><scope>provided</scope><optional>true</optional></dependency>
</dependencies>
```

The `youtrackdb-server` declaration uses `<scope>provided</scope><optional>true</optional>` so the server artifact is available on this module's compile and test classpath (the `OpenTelemetryServerPlugin` source needs server APIs at compile time, and Track 6b's `ServerPluginTest` boots a real `YouTrackDBServer` at test time) without forcing downstream consumers of `youtrackdb-opentelemetry` to pull `youtrackdb-server` in transitively. No separate `<scope>test</scope>` entry is required for `ServerPluginTest`; `provided` already covers it.
