package com.jetbrains.youtrackdb.junit;

import com.jetbrains.youtrackdb.junit.hooks.HookOnIndexedMapTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * JUnit 5 test suite for the embedded database test suite.
 * Classes are listed in the same order as the TestNG suite XML
 * ({@code embedded-test-db-from-scratch.xml}) to preserve execution order
 * and cross-class data dependencies.
 *
 * <p>As test classes are migrated from TestNG to JUnit 5, they are added here
 * and removed from the TestNG XML suite.
 */
@Suite
@SelectClasses({
    SmokeTest.class,
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
    // Population (part 1)
    EntityTreeTest.class,
    CRUDTest.class,
    CRUDInheritanceTest.class,
    CRUDDocumentPhysicalTest.class,
    // Population (part 2)
    ComplexTypesTest.class,
    CRUDDocumentValidationTest.class,
    DocumentTrackingTest.class,
    DBRecordCreateTest.class,
    // Tx
    TransactionAtomicTest.class,
    FrontendTransactionImplTest.class,
    TransactionConsistencyTest.class,
    // Index (part 1)
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
    // Index (part 2)
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
    // Index (part 3)
    MapIndexTest.class,
    SQLSelectByLinkedSchemaPropertyIndexReuseTest.class,
    LinkListIndexTest.class,
    LinkBagIndexTest.class,
    LinkMapIndexTest.class,
    IndexTxTest.class,
    OrderByIndexReuseTest.class,
    // Index (part 4) + Index Manager
    LinkSetIndexTest.class,
    CompositeIndexWithNullTest.class,
    IndexManagerTest.class
})
@SuiteDisplayName("Paginated Local Test Suite")
public class EmbeddedTestSuite {
}
