# ADR: TestNG to JUnit 5 Migration — Implementation Plan

## Status

Accepted

## Context

The `tests` module uses TestNG 7.11.0 while all other modules (`core`, `server`, `embedded`,
`docker-tests`, `gremlin-annotations`, `examples`) use JUnit 4 (with a few JUnit 5 files
already present in `core`). This mix of test frameworks creates:

- Dependency management complexity (two test runners, two listener systems)
- Cognitive overhead for developers switching between modules
- Inability to share test infrastructure (e.g., JUnit-based `test-commons` utilities
  cannot be directly used with TestNG lifecycle)
- Surefire plugin configuration fragmentation (surefire-junit47 vs surefire-testng)

The target is JUnit 5 (Jupiter) for the `tests` module. JUnit 4 → JUnit 5 migration of
other modules is out of scope for this plan.

## Ekstazi Compatibility Assessment

**Ekstazi 5.3.0 does NOT support JUnit 5** ([gliga/ekstazi#12](https://github.com/gliga/ekstazi/issues/12),
open since 2019, unresolved). It only supports JUnit 3/4 by instrumenting test classes
that use JUnit 4 annotations.

**Impact on this migration: NONE.** The `tests` module migration is safe because:

1. Ekstazi is used exclusively for **integration tests** via `maven-failsafe-plugin` in the
   `ekstazi` Maven profile (root `pom.xml`, lines 1088–1150).
2. The `tests` module has **no `maven-failsafe-plugin` configuration** — only surefire with
   TestNG. The `ekstazi` profile skips surefire entirely (`<skipTests>true</skipTests>`).
3. Modules that use Ekstazi for integration tests (`core`, `server`, `embedded`,
   `gremlin-annotations`) all use **JUnit 4**, which is completely unaffected by this migration.
4. The `tests` module's TestNG tests were never part of the Ekstazi pipeline.

**Warning for future work**: If `core`/`server` integration tests are ever migrated from
JUnit 4 to JUnit 5, Ekstazi will break and an alternative test-selection tool must be
adopted first. This is out of scope for the current plan.

## Key Constraints

1. **Test ordering is critical**: TestNG tests share a single database instance across the
   entire suite. One test class generates schema and data that subsequent classes depend on.
   The suite XML files (`embedded-test-db-from-scratch.xml`) define strict class ordering.
   Within classes, `dependsOnMethods` (227 occurrences across 23 files) enforces method
   execution order.

2. **Shared mutable state**: `BaseTest.youTrackDB` is a static field shared across all test
   classes in a suite run. `@BeforeSuite` / `@AfterSuite` manage its lifecycle.

3. **Suite listener**: `TestNGTestListener` (ISuiteListener) performs direct memory leak
   detection after suite completion — must be preserved.

4. **All tests must pass at every step**: Each step produces a commit where the full test
   suite passes.

5. **TestNG removal happens only at the final step**.

6. **One step = one agent session = one commit**.

## Architecture Decisions

### JUnit 5 Test Ordering Strategy

JUnit 5 does not natively support cross-class ordering via XML suites or
`dependsOnMethods`. We will use:

- **`@TestMethodOrder(MethodOrderer.OrderAnnotation.class)`** + `@Order(N)` on methods
  to replace `dependsOnMethods` within a class
- **`@TestClassOrder(ClassOrderer.OrderAnnotation.class)`** + `@Order(N)` on `@Nested`
  classes or a custom `ClassOrderer` to enforce cross-class ordering
- **A single top-level test suite class** (`EmbeddedTestSuite`) with `@Suite` annotation
  and `@SelectClasses` listing all test classes in the exact order from the XML suite file
- **JUnit Platform Suite** (`junit-platform-suite`) dependency for suite orchestration

### Lifecycle Mapping

| TestNG | JUnit 5 Equivalent |
|--------|---------------------|
| `@BeforeSuite` | Static initializer in suite entry point or `@BeforeAll` in a shared base with `@TestInstance(PER_CLASS)`, or a custom `Extension` |
| `@AfterSuite` | `CloseableResource` registered in the root `ExtensionContext.Store` (fires after all tests) |
| `@BeforeClass` | `@BeforeAll` (static) or `@BeforeAll` with `@TestInstance(PER_CLASS)` |
| `@AfterClass` | `@AfterAll` |
| `@BeforeMethod` | `@BeforeEach` |
| `@AfterMethod` | `@AfterEach` |
| `@Test(dependsOnMethods = ...)` | `@Order(N)` with `@TestMethodOrder(OrderAnnotation.class)` |
| `@Test(expectedExceptions = X.class)` | `assertThrows(X.class, () -> ...)` |
| `@Test(expectedExceptionsMessageRegExp = ...)` | `assertThrows` + `assertThat(ex.getMessage()).matches(...)` |
| `@Test(enabled = false)` | `@Disabled("reason")` |
| `@Test(groups = ...)` | `@Tag("...")` |
| `@Parameters({"param"})` | `@ParameterizedTest` + `@ValueSource` or config via Extension |
| `ISuiteListener` | Custom `Extension` implementing `BeforeAllCallback` / `AfterAllCallback` + `CloseableResource` |

### Module Structure During Migration

During migration, both TestNG and JUnit 5 tests coexist in the `tests` module:

- TestNG tests remain in `src/test/java/com/jetbrains/youtrackdb/auto/`
- JUnit 5 tests are created in `src/test/java/com/jetbrains/youtrackdb/junit/`
- Both surefire-testng and junit-platform-suite providers are configured
- The TestNG suite XML is progressively shrunk as classes are migrated

At the end, TestNG classes and the `auto/` package are deleted.

## Implementation Steps

---

### Step 0: Infrastructure — Add JUnit 5 Dependencies and Dual-Runner Setup

**Goal**: Set up the `tests` module to run both TestNG and JUnit 5 tests simultaneously.

**Changes**:

1. Add JUnit 5 BOM (`junit-bom`) to root `pom.xml` `<dependencyManagement>`
2. Add dependencies to `tests/pom.xml`:
   - `org.junit.jupiter:junit-jupiter` (test scope)
   - `org.junit.platform:junit-platform-suite` (test scope)
   - `org.junit.platform:junit-platform-suite-api` (test scope)
3. Configure `maven-surefire-plugin` in `tests/pom.xml` to run both:
   - Keep existing `test-embedded` execution with `surefire-testng` for TestNG suite
   - Add new `test-junit5` execution with default JUnit Platform provider
4. Create the JUnit 5 base infrastructure in `src/test/java/com/jetbrains/youtrackdb/junit/`:
   - `BaseJUnit5Test.java` — mirrors `BaseTest` lifecycle with JUnit 5 annotations
   - `BaseDBJUnit5Test.java` — mirrors `BaseDBTest` with schema setup
   - `SuiteLifecycleExtension.java` — JUnit 5 Extension replacing `@BeforeSuite` /
     `@AfterSuite` (manages `YouTrackDBImpl` static instance lifecycle using
     `ExtensionContext.Store.CloseableResource` on the root context)
   - `MemoryLeakDetectionExtension.java` — replaces `TestNGTestListener`, checks for
     direct memory leaks after all tests complete
5. Create a trivial JUnit 5 smoke test (`SmokeTest.java`) that opens/closes a database
   to verify the dual-runner setup works
6. Run full test suite (`./mvnw -pl tests clean test`) — both TestNG and JUnit 5 tests pass

**Files touched**: `pom.xml`, `tests/pom.xml`, 4-5 new files in `junit/` package

---

### Steps 1–15: Migrate Test Classes Group by Group

Each step migrates one group from `embedded-test-db-from-scratch.xml`, preserving the
exact class order. The JUnit 5 suite class is updated to include newly migrated classes.

For each class being migrated:

1. Create a new JUnit 5 class in `com.jetbrains.youtrackdb.junit.*` mirroring the original
2. Convert all annotations per the mapping table above
3. Replace `dependsOnMethods` chains with `@Order(N)` annotations
4. Replace `expectedExceptions` with `assertThrows`
5. Replace `Assert.assertEquals(actual, expected)` (TestNG order) with
   `assertEquals(expected, actual)` (JUnit order) or AssertJ equivalents
6. Replace `enabled = false` with `@Disabled`
7. Replace `@Parameters` with configuration-based approach
8. **Add a traceability comment** to every migrated test method referencing the original
   TestNG source, so code reviewers can quickly compare implementations (see format below)
9. Keep the original TestNG class untouched (it still runs in the TestNG suite)
10. Remove the migrated class from `embedded-test-db-from-scratch.xml`
11. Add the new JUnit 5 class to the JUnit 5 suite
12. Run full test suite — both TestNG (remaining) and JUnit 5 (migrated) pass

### Traceability Comment Convention

Every migrated test method **must** include a comment referencing the original TestNG
method it was migrated from. This allows code reviewers to quickly locate and compare
the original implementation. Format:

```java
// Migrated from: com.jetbrains.youtrackdb.auto.CRUDTest#testCreate
@Test
@Order(1)
void testCreate() {
  // ...
}
```

For methods where the signature or name changed during migration, include the original
method name:

```java
// Migrated from: com.jetbrains.youtrackdb.auto.CRUDDocumentValidationTest#validationMandatory
// Original used: @Test(expectedExceptions = ValidationException.class)
@Test
@Order(2)
void validationMandatory() {
  assertThrows(ValidationException.class, () -> {
    // ...
  });
}
```

These comments are **temporary** — they will be removed in Step 16 when the original
TestNG classes are deleted (since the originals will no longer exist to compare against).

**Important**: The JUnit 5 suite must run as a single ordered sequence. Use
`@Suite` + `@SelectClasses(...)` with classes listed in dependency order.

---

#### Step 1: DbCreation Group (5 classes)

Migrate in order:
1. `DbCreationTest` (extends BaseTest, 9 dependsOnMethods)
2. `DbListenerTest`
3. `DBMethodsTest`
4. `AlterDatabaseTest`
5. `DbCopyTest` (has 1 disabled test)

**Note**: `DbCreationTest` extends `BaseTest` directly (not `BaseDBTest`) and has
the longest dependency chain. This is the foundational group — it creates the database.

---

#### Step 2: Schema Group (3 classes)

Migrate in order:
1. `SchemaTest` (3 dependsOnMethods)
2. `AbstractClassTest`
3. `DefaultValuesTrivialTest`

---

#### Step 3: Security + Hook Groups (3 classes)

Migrate in order:
1. `SecurityTest`
2. `HookTxTest` (2 dependsOnMethods, 5 `enabled = false` callback methods)
3. `HookOnIndexedMapTest` (in `hooks/` subpackage)

**Note**: `HookTxTest` has methods annotated with `@Test(enabled = false)` that are
actually hook callbacks, not tests. In JUnit 5, these should not have `@Test` annotation
at all.

---

#### Step 4: Population Group — Part 1 (4 classes)

Migrate in order:
1. `EntityTreeTest` (7 dependsOnMethods)
2. `CRUDTest` (33 dependsOnMethods — heaviest non-index class)
3. `CRUDInheritanceTest` (5 dependsOnMethods)
4. `CRUDDocumentPhysicalTest` (12 dependsOnMethods)

**Note**: `CRUDTest` has the second-highest `dependsOnMethods` count (33). Careful
ordering with `@Order` is essential.

---

#### Step 5: Population Group — Part 2 (4 classes)

Migrate in order:
1. `ComplexTypesTest`
2. `CRUDDocumentValidationTest` (13 dependsOnMethods, 8 expectedExceptions,
   2 expectedExceptionsMessageRegExp)
3. `DocumentTrackingTest`
4. `DBRecordCreateTest`

**Note**: `CRUDDocumentValidationTest` is the most annotation-heavy class. All
`expectedExceptions` must become `assertThrows`. The `expectedExceptionsMessageRegExp`
patterns must become regex assertions on the caught exception message.

---

#### Step 6: Transaction Group (3 classes)

Migrate in order:
1. `TransactionAtomicTest`
2. `FrontendTransactionImplTest` (5 dependsOnMethods)
3. `TransactionConsistencyTest`

---

#### Step 7: Index Group — Part 1 (10 classes)

Migrate in order:
1. `DateIndexTest`
2. `IndexTest` (25 dependsOnMethods, 4 disabled tests)
3. `ByteArrayKeyTest` (1 dependsOnMethods)
4. `ClassIndexManagerTest`
5. `IndexConcurrentCommitTest`
6. `SQLSelectIndexReuseTest` (extends AbstractIndexReuseTest)
7. `SQLCreateIndexTest`
8. `SQLDropIndexTest` (2 dependsOnMethods)
9. `SQLDropClassIndexTest`
10. `SQLDropSchemaPropertyIndexTest`

**Note**: `AbstractIndexReuseTest` is an abstract base class used by
`SQLSelectIndexReuseTest` and `OrderByIndexReuseTest`. Migrate the abstract class
in this step alongside its first concrete subclass.

---

#### Step 8: Index Group — Part 2 (10 classes)

Migrate in order:
1. `SchemaIndexTest`
2. `ClassIndexTest` (45 dependsOnMethods — highest in codebase)
3. `SchemaPropertyIndexTest` (6 dependsOnMethods)
4. `CollectionIndexTest`
5. `IndexTxAwareOneValueGetValuesTest` (extends `IndexTxAwareBaseTest`)
6. `IndexTxAwareMultiValueGetValuesTest`
7. `IndexTxAwareMultiValueGetTest`
8. `IndexTxAwareOneValueGetTest`
9. `IndexTxAwareMultiValueGetEntriesTest`
10. `IndexTxAwareOneValueGetEntriesTest`

**Note**: `IndexTxAwareBaseTest` is an abstract base class for 6 concrete IndexTxAware*
test classes. Migrate it alongside the first concrete subclass.

`ClassIndexTest` (45 `dependsOnMethods`) is the most complex file in the suite.
Multi-method dependency arrays must be carefully translated to `@Order` values.

---

#### Step 9: Index Group — Part 3 (7 classes)

Migrate in order:
1. `MapIndexTest`
2. `SQLSelectByLinkedSchemaPropertyIndexReuseTest`
3. `LinkListIndexTest`
4. `LinkBagIndexTest`
5. `LinkMapIndexTest`
6. `IndexTxTest`
7. `OrderByIndexReuseTest` (extends `AbstractIndexReuseTest`)

---

#### Step 10: Index Group — Part 4 + Index Manager (3 classes)

Migrate in order:
1. `LinkSetIndexTest`
2. `CompositeIndexWithNullTest`
3. `IndexManagerTest` (35 dependsOnMethods)

---

#### Step 11: Query + Parsing + Graph + GEO + Binary Groups (8 classes)

Migrate in order:
1. `WrongQueryTest`
2. `BetweenConversionTest`
3. `PreparedStatementTest`
4. `QueryLocalCacheIntegrationTest`
5. `PolymorphicQueryTest`
6. `JSONTest`
7. `GraphDatabaseTest` (1 dependsOnMethods)
8. `GEOTest` (4 dependsOnMethods)

---

#### Step 12: SQL Commands Group — Part 1 (8 classes)

Migrate in order:
1. `SQLCommandsTest` (4 dependsOnMethods)
2. `SQLCreateClassTest`
3. `SQLDropClassTest`
4. `SQLInsertTest` (1 expectedExceptions, 2 disabled tests)
5. `SQLSelectTest` (extends `AbstractSelectTest`, 1 disabled test)
6. `SQLMetadataTest`
7. `SQLSelectProjectionsTest`
8. `SQLSelectGroupByTest` (1 disabled test)

**Note**: `AbstractSelectTest` is an abstract base class. Migrate alongside `SQLSelectTest`.

---

#### Step 13: SQL Commands Group — Part 2 (7 classes)

Migrate in order:
1. `SQLFunctionsTest` (1 expectedExceptions)
2. `SQLUpdateTest` (6 dependsOnMethods, 1 disabled test)
3. `SQLDeleteTest`
4. `SQLCreateVertexTest`
5. `SQLDeleteEdgeTest`
6. `SQLBatchTest` (1 disabled test)
7. `SQLCombinationFunctionTests`

---

#### Step 14: Misc Group (13 classes)

Migrate in order:
1. `TruncateClassTest`
2. `DateTest`
3. `SQLCreateLinkTest`
4. `MultipleDBTest`
5. `ConcurrentUpdatesTest` (1 disabled test)
6. `ConcurrentQueriesTest`
7. `ConcurrentCommandAndOpenTest`
8. `CollateTest`
9. `EmbeddedLinkBagTest` (extends `LinkBagTest`)
10. `BTreeBasedLinkBagTest` (extends `LinkBagTest`)
11. `StringsTest`
12. `DBSequenceTest`
13. `SQLDBSequenceTest`

**Note**: `LinkBagTest` is an abstract base class for `EmbeddedLinkBagTest` and
`BTreeBasedLinkBagTest`. Migrate alongside the first concrete subclass.

---

#### Step 15: End Group + Import/Export (4 classes)

Migrate in order:
1. `BinaryTest` (3 dependsOnMethods)
2. `DbImportExportTest` (2 dependsOnMethods, `@Parameters`, disabled — currently
   commented out in suite XML)
3. `DbImportStreamExportTest` (2 dependsOnMethods, `@Parameters`, `groups`)
4. `DbImportExportLinkBagTest` (2 dependsOnMethods, `@Parameters`, `groups`)
5. `DbClosedTest` (End group — must remain last)

**Note**: Import/export tests use `@Parameters({"testPath"})` which maps to TestNG
suite parameter. In JUnit 5, use system property or `@RegisterExtension` to provide
the `testPath` value.

`DbClosedTest` must always be the **last** class in the suite as it closes/drops
the database.

---

### Step 16: Remove TestNG — Final Cleanup

**Goal**: Remove all TestNG infrastructure and the original test classes.

**Changes**:

1. Delete the entire `src/test/java/com/jetbrains/youtrackdb/auto/` package (all
   original TestNG classes)
2. Delete `TestNGTestListener.java`
3. Delete TestNG suite XML files (`embedded-test-db-from-scratch.xml`,
   `remote-test-db-from-scratch.xml`)
4. Remove `org.testng:testng` dependency from `tests/pom.xml`
5. Remove `surefire-testng` plugin dependency from `tests/pom.xml`
6. Remove the `test-embedded` TestNG execution from surefire configuration
7. **Remove all `// Migrated from:` traceability comments** from JUnit 5 test methods —
   the original TestNG classes no longer exist so the references are now stale
8. Move JUnit 5 classes from `com.jetbrains.youtrackdb.junit` to
   `com.jetbrains.youtrackdb.auto` (original package) — or keep them in `junit`
   package if preferred
9. Clean up any TestNG import remnants
10. Run full test suite — only JUnit 5 tests remain and all pass

**Files deleted**: ~120 TestNG test classes, 2 XML suite files, 1 listener
**Files modified**: `tests/pom.xml`

---

### Step 17: Post-Migration Cleanup

**Goal**: Clean up and optimize the JUnit 5 test infrastructure.

**Changes**:

1. Review and simplify `@Order` annotations — where `dependsOnMethods` was used only
   for setup (not genuine data dependencies), consider extracting setup to `@BeforeAll`
   / `@BeforeEach` to make tests independent
2. Replace `org.testng.Assert` usages in `BaseDBTest` utility methods (e.g.,
   `generateGraphData()`) with JUnit 5 / AssertJ assertions
3. Review disabled tests (`@Disabled`) — create YTDB issues for each or remove if
   they test obsolete functionality
4. **Update `CLAUDE.md`** to reflect that the `tests` module now uses JUnit 5. Specific
   sections that reference TestNG and must be updated:
   - **Module Structure table**: `tests` row says "TestNG with XML suite files"
   - **Unit Tests section**: mentions "Tests module: TestNG with XML suite files
     (e.g., `embedded-test-db-from-scratch.xml`)"
   - **Tips for Working with This Codebase**, item 7: "Core tests use JUnit 4;
     the `tests` module uses TestNG. Don't mix them"
   - Any other TestNG mentions — search for "TestNG" / "testng" globally in the file
5. Update `tests/pom.xml` to remove any leftover TestNG comments or configuration
6. Run full test suite — all JUnit 5 tests pass

---

## Suite Execution Configuration (JUnit 5)

The final JUnit 5 suite class should look like:

```java
@Suite
@SelectClasses({
  // DbCreation
  DbCreationTest.class,
  DbListenerTest.class,
  DBMethodsTest.class,
  AlterDatabaseTest.class,
  DbCopyTest.class,
  // Schema
  SchemaTest.class,
  AbstractClassTest.class,
  DefaultValuesTrivialTest.class,
  // Security
  SecurityTest.class,
  // Hook
  HookTxTest.class,
  HookOnIndexedMapTest.class,
  // Population
  EntityTreeTest.class,
  CRUDTest.class,
  CRUDInheritanceTest.class,
  CRUDDocumentPhysicalTest.class,
  ComplexTypesTest.class,
  CRUDDocumentValidationTest.class,
  DocumentTrackingTest.class,
  DBRecordCreateTest.class,
  // Tx
  TransactionAtomicTest.class,
  FrontendTransactionImplTest.class,
  TransactionConsistencyTest.class,
  // Index (all 33 classes in order)
  DateIndexTest.class,
  IndexTest.class,
  ByteArrayKeyTest.class,
  ClassIndexManagerTest.class,
  IndexConcurrentCommitTest.class,
  SQLSelectIndexReuseTest.class,
  SQLCreateIndexTest.class,
  SQLDropIndexTest.class,
  SQLDropClassIndexTest.class,
  SQLDropSchemaPropertyIndexTest.class,
  SchemaIndexTest.class,
  ClassIndexTest.class,
  SchemaPropertyIndexTest.class,
  CollectionIndexTest.class,
  IndexTxAwareOneValueGetValuesTest.class,
  IndexTxAwareMultiValueGetValuesTest.class,
  IndexTxAwareMultiValueGetTest.class,
  IndexTxAwareOneValueGetTest.class,
  IndexTxAwareMultiValueGetEntriesTest.class,
  IndexTxAwareOneValueGetEntriesTest.class,
  MapIndexTest.class,
  SQLSelectByLinkedSchemaPropertyIndexReuseTest.class,
  LinkListIndexTest.class,
  LinkBagIndexTest.class,
  LinkMapIndexTest.class,
  IndexTxTest.class,
  OrderByIndexReuseTest.class,
  LinkSetIndexTest.class,
  CompositeIndexWithNullTest.class,
  IndexManagerTest.class,
  // Query
  WrongQueryTest.class,
  BetweenConversionTest.class,
  PreparedStatementTest.class,
  QueryLocalCacheIntegrationTest.class,
  PolymorphicQueryTest.class,
  // Parsing
  JSONTest.class,
  // Graph
  GraphDatabaseTest.class,
  // GEO
  GEOTest.class,
  // Binary
  BinaryTest.class,
  // sql-commands
  SQLCommandsTest.class,
  SQLCreateClassTest.class,
  SQLDropClassTest.class,
  SQLInsertTest.class,
  SQLSelectTest.class,
  SQLMetadataTest.class,
  SQLSelectProjectionsTest.class,
  SQLSelectGroupByTest.class,
  SQLFunctionsTest.class,
  SQLUpdateTest.class,
  SQLDeleteTest.class,
  SQLCreateVertexTest.class,
  SQLDeleteEdgeTest.class,
  SQLBatchTest.class,
  SQLCombinationFunctionTests.class,
  // misc
  TruncateClassTest.class,
  DateTest.class,
  SQLCreateLinkTest.class,
  MultipleDBTest.class,
  ConcurrentUpdatesTest.class,
  ConcurrentQueriesTest.class,
  ConcurrentCommandAndOpenTest.class,
  CollateTest.class,
  EmbeddedLinkBagTest.class,
  BTreeBasedLinkBagTest.class,
  StringsTest.class,
  DBSequenceTest.class,
  SQLDBSequenceTest.class,
  // Import/Export (if re-enabled)
  DbImportExportTest.class,
  DbImportStreamExportTest.class,
  DbImportExportLinkBagTest.class,
  // End - must be last
  DbClosedTest.class
})
@SuiteDisplayName("Paginated Local Test Suite")
public class EmbeddedTestSuite {
}
```

## Risk Mitigation

1. **Assertion parameter order**: TestNG uses `Assert.assertEquals(actual, expected)`;
   JUnit 5 uses `assertEquals(expected, actual)`. This is the #1 source of subtle bugs
   during migration — error messages will be swapped if not caught. Each class migration
   must carefully reverse all assertion argument pairs.

2. **Static state leaks**: The shared `YouTrackDBImpl` instance must be initialized
   exactly once (like `@BeforeSuite`) and torn down once (like `@AfterSuite`). JUnit 5's
   `ExtensionContext.Store.CloseableResource` pattern on the root context ensures this.

3. **Test method ordering**: JUnit 5 `@Order` with `OrderAnnotation` is deterministic
   but only within a single class. Cross-class ordering requires `@Suite` + `@SelectClasses`.
   The suite must list classes in the same order as the TestNG XML.

4. **Disabled tests**: 23 tests are `@Test(enabled = false)`. These become `@Disabled`
   in JUnit 5 and should be tracked for future resolution.

5. **Import/Export parameterization**: Three test classes use `@Parameters` to receive
   `testPath` from the suite XML. In JUnit 5, resolve via `System.getProperty("testPath")`
   in `@BeforeAll` or a custom Extension.

## Progress Tracking

| Step | Description | Classes | Status | Commit SHA | Agent Session | Notes |
|------|-------------|---------|--------|------------|---------------|-------|
| 0 | Infrastructure setup | 0 (5 new) | COMPLETED | ada669584b | 2026-03-12 | JUnit 5 deps, dual runner, base classes, extensions |
| 1 | DbCreation group | 5 | COMPLETED | cb1b5bc662 | 2026-03-12 | DbCreationTest, DbListenerTest, DBMethodsTest, AlterDatabaseTest, DbCopyTest |
| 2 | Schema group | 3 | COMPLETED | 807838cd9e | 2026-03-12 | SchemaTest, AbstractClassTest, DefaultValuesTrivialTest |
| 3 | Security + Hook groups | 3 | COMPLETED | 9d43402916 | 2026-03-12 | SecurityTest, HookTxTest, HookOnIndexedMapTest; HookOnIndexedMapTest @Disabled (original never ran — JUnit4 @Test in TestNG suite) |
| 4 | Population part 1 | 4 | COMPLETED | 2b27886d68 | 2026-03-12 | EntityTreeTest, CRUDTest, CRUDInheritanceTest, CRUDDocumentPhysicalTest; originals kept in TestNG XML (classloader isolation prevents DB sharing between providers) |
| 5 | Population part 2 | 4 | COMPLETED | d37f7e6ec1 | 2026-03-12 | ComplexTypesTest, CRUDDocumentValidationTest, DocumentTrackingTest, DBRecordCreateTest |
| 6 | Transaction group | 3 | COMPLETED | 7f1e75bb95 | 2026-03-12 | TransactionAtomicTest, FrontendTransactionImplTest, TransactionConsistencyTest |
| 7 | Index part 1 | 10 | NOT STARTED | — | — | DateIndexTest → SQLDropSchemaPropertyIndexTest + AbstractIndexReuseTest |
| 8 | Index part 2 | 10 | NOT STARTED | — | — | SchemaIndexTest → IndexTxAwareOneValueGetEntriesTest + IndexTxAwareBaseTest |
| 9 | Index part 3 | 7 | NOT STARTED | — | — | MapIndexTest → OrderByIndexReuseTest |
| 10 | Index part 4 + IndexManager | 3 | NOT STARTED | — | — | LinkSetIndexTest, CompositeIndexWithNullTest, IndexManagerTest |
| 11 | Query + Parsing + Graph + GEO + Binary | 8 | NOT STARTED | — | — | WrongQueryTest → GEOTest |
| 12 | SQL commands part 1 | 8 | NOT STARTED | — | — | SQLCommandsTest → SQLSelectGroupByTest + AbstractSelectTest |
| 13 | SQL commands part 2 | 7 | NOT STARTED | — | — | SQLFunctionsTest → SQLCombinationFunctionTests |
| 14 | Misc group | 13 | NOT STARTED | — | — | TruncateClassTest → SQLDBSequenceTest + LinkBagTest (abstract) |
| 15 | End + Import/Export | 5 | NOT STARTED | — | — | BinaryTest, DbImport*Tests, DbClosedTest |
| 16 | Remove TestNG | ~120 deleted | NOT STARTED | — | — | Delete auto/ package, remove TestNG deps |
| 17 | Post-migration cleanup | — | NOT STARTED | — | — | Simplify @Order, review @Disabled, update CLAUDE.md |

**Total test classes to migrate**: ~95 concrete + ~5 abstract base classes
**Total steps**: 18 (Step 0 through Step 17)
**Estimated commits**: 18
