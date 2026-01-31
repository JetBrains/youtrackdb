/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.auto.junit.hooks.HookOnIndexedMapTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * JUnit 4 test suite that runs all migrated tests in the correct order.
 *
 * <h2>Suite Dependency Notice</h2>
 * <p>
 * <b>IMPORTANT:</b> Tests in this suite are interdependent and share database state. The execution
 * order is critical as tests depend on schema and data created by previous test classes.
 * </p>
 *
 * <h3>Execution Order</h3>
 * <p>
 * The order matches the original TestNG XML configuration:
 * {@code tests/src/test/java/com/jetbrains/youtrackdb/auto/embedded-test-db-from-scratch.xml}
 * </p>
 *
 * <h3>Test Groups and Dependencies</h3>
 * <ul>
 *   <li><b>DbCreation group:</b> Creates the database and basic infrastructure</li>
 *   <li><b>Schema group:</b> Creates schema classes (Country, City, Address, Account, Company,
 *       Profile, etc.) that subsequent tests depend on</li>
 *   <li><b>Security group:</b> Sets up security and user roles</li>
 *   <li><b>Population group:</b> Populates the database with test data</li>
 *   <li><b>Tx/Index/Query groups:</b> Test various database features using the established
 *       schema and data</li>
 *   <li><b>End group:</b> Cleanup and final verification</li>
 * </ul>
 *
 * <h3>Running Tests</h3>
 * <ul>
 *   <li><b>Recommended:</b> Run this suite class to execute all tests in the correct order</li>
 *   <li><b>Individual tests:</b> Each test class has a {@code @BeforeClass} method that initializes
 *       the database and schema, allowing individual execution. However, some tests may still
 *       depend on data created by earlier tests in the suite.</li>
 * </ul>
 *
 * <h3>Adding New Tests</h3>
 * <p>
 * When adding new test classes:
 * </p>
 * <ol>
 *   <li>Extend {@link BaseDBTest} (or {@link BaseTest} for non-schema tests)</li>
 *   <li>Add a {@code @BeforeClass} method that calls {@code beforeClass()}</li>
 *   <li>Add the class to this suite in the appropriate position</li>
 *   <li>Document any dependencies on schema or data from other tests</li>
 * </ol>
 *
 * @see BaseTest
 * @see BaseDBTest
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    // DbCreation group
    DbCreationTest.class,
    DbListenerTest.class,
    DBMethodsTest.class,
    AlterDatabaseTest.class,
    DbCopyTest.class,
    // Schema group
    SchemaTest.class,
    AbstractClassTest.class,
    DefaultValuesTrivialTest.class,
    // Security group
    SecurityTest.class,
    // Hook group
    HookTxTest.class,
    HookOnIndexedMapTest.class,
    // Population group
    EntityTreeTest.class,
    CRUDTest.class,
    CRUDInheritanceTest.class,
    CRUDDocumentPhysicalTest.class,
    ComplexTypesTest.class,
    CRUDDocumentValidationTest.class,
    DBRecordMetadataTest.class,
    DocumentTrackingTest.class,
    DBRecordCreateTest.class,
    // Tx group
    TransactionAtomicTest.class,
    FrontendTransactionImplTest.class,
    TransactionConsistencyTest.class,
    // Index group
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
    // Query group
    WrongQueryTest.class,
    BetweenConversionTest.class,
    PreparedStatementTest.class,
    QueryLocalCacheIntegrationTest.class,
    PolymorphicQueryTest.class,
    // Parsing group
    JSONTest.class,
    // Graph group
    GraphDatabaseTest.class,
    // GEO group
    GEOTest.class,
    // Index Manager group
    IndexManagerTest.class,
    // Binary group
    BinaryTest.class,
    // sql-commands group
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
    // misc group
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
    // End group
    DbClosedTest.class,
    // Add more test classes here as they are migrated, maintaining the order from embedded-test-db-from-scratch.xml
})
public class DatabaseTestSuite {
  // This class is just a holder for the above annotations
}
