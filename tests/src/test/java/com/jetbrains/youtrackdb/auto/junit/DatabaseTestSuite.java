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
 * JUnit 4 test suite that runs all migrated tests in the correct order. The order matches the
 * original TestNG XML configuration:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/embedded-test-db-from-scratch.xml
 * <p>
 * Test classes are added here as they are migrated from TestNG to JUnit 4. The execution order is
 * critical as tests may depend on database state created by previous test classes.
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
