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
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of SchemaPropertyIndexTest. Original test class:
 * com.jetbrains.youtrackdb.auto.SchemaPropertyIndexTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaPropertyIndexTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SchemaPropertyIndexTest extends BaseDBTest {

  private static SchemaPropertyIndexTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SchemaPropertyIndexTest();
    instance.beforeClass();

    // Original: beforeClass (line 35)
    final Schema schema = instance.session.getMetadata().getSchema();
    final var oClass = schema.createClass("PropertyIndexTestClass");
    oClass.createProperty("prop0", PropertyType.LINK);
    oClass.createProperty("prop1", PropertyType.STRING);
    oClass.createProperty("prop2", PropertyType.INTEGER);
    oClass.createProperty("prop3", PropertyType.BOOLEAN);
    oClass.createProperty("prop4", PropertyType.INTEGER);
    oClass.createProperty("prop5", PropertyType.STRING);
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    // Original: afterClass (line 49)
    if (instance != null && instance.session != null) {
      if (instance.session.isClosed()) {
        instance.session = instance.createSessionInstance();
      }

      instance.session.begin();
      instance.session.execute("delete from PropertyIndexTestClass").close();
      instance.session.commit();

      instance.session.execute("drop class PropertyIndexTestClass").close();

      instance.afterClass();
    }
  }

  /**
   * Original: testCreateUniqueIndex (line 65) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaPropertyIndexTest.java
   */
  @Test
  public void test01_CreateUniqueIndex() {
    var schema = session.getMetadata().getSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    final var propOne = oClass.getProperty("prop1");

    propOne.createIndex(SchemaClass.INDEX_TYPE.UNIQUE,
        Map.of("ignoreNullValues", true));

    final Collection<Index> indexes = oClass.getInvolvedIndexesInternal(session, "prop1");
    IndexDefinition indexDefinition = null;

    for (final var index : indexes) {
      if (index.getName().equals("PropertyIndexTestClass.prop1")) {
        indexDefinition = index.getDefinition();
        break;
      }
    }

    Assert.assertNotNull(indexDefinition);
    Assert.assertEquals(1, indexDefinition.getParamCount());
    Assert.assertEquals(1, indexDefinition.getProperties().size());
    Assert.assertTrue(indexDefinition.getProperties().contains("prop1"));
    Assert.assertEquals(1, indexDefinition.getTypes().length);
    Assert.assertEquals(PropertyTypeInternal.STRING, indexDefinition.getTypes()[0]);
  }

  /**
   * Original: testIsIndexedNonIndexedField (line 150) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaPropertyIndexTest.java Note: Must run
   * before createAdditionalSchemas which creates indexes on prop3
   */
  @Test
  public void test02_IsIndexedNonIndexedField() {
    var schema = session.getMetadata().getSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    var propThree = oClass.getPropertyInternal("prop3");

    Assert.assertTrue(propThree.getAllIndexes().isEmpty());
  }

  /**
   * Original: testIsIndexedIndexedField (line 159) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaPropertyIndexTest.java Depends on:
   * testCreateUniqueIndex
   */
  @Test
  public void test03_IsIndexedIndexedField() {
    var schema = session.getMetadata().getSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    var propOne = oClass.getPropertyInternal("prop1");
    Assert.assertFalse(propOne.getAllIndexes().isEmpty());
  }

  /**
   * Original: createAdditionalSchemas (line 92) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaPropertyIndexTest.java Depends on:
   * testCreateUniqueIndex
   */
  @Test
  public void test04_CreateAdditionalSchemas() {
    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.getClass("PropertyIndexTestClass");

    oClass.createIndex(
        "propOne0",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop0", "prop1"});
    oClass.createIndex(
        "propOne1",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop1", "prop2"});
    oClass.createIndex(
        "propOne2",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop1", "prop3"});
    oClass.createIndex(
        "propOne3",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop2", "prop3"});
    oClass.createIndex(
        "propOne4",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop2", "prop1"});
  }

  /**
   * Original: testGetIndexes (line 124) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaPropertyIndexTest.java Depends on:
   * createAdditionalSchemas
   */
  @Test
  public void test05_GetIndexes() {
    var schema = session.getMetadata().getSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    oClass.getProperty("prop1");

    var indexes = oClass.getInvolvedIndexesInternal(session, "prop1");
    Assert.assertEquals(1, indexes.size());
    Assert.assertNotNull(containsIndex(indexes, "PropertyIndexTestClass.prop1"));
  }

  /**
   * Original: testGetAllIndexes (line 135) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaPropertyIndexTest.java Depends on:
   * createAdditionalSchemas
   */
  @Test
  public void test06_GetAllIndexes() {
    var schema = session.getMetadata().getSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    var propOne = oClass.getPropertyInternal("prop1");

    final var indexes = propOne.getAllIndexesInternal();
    Assert.assertEquals(5, indexes.size());
    Assert.assertNotNull(containsIndex(indexes, "PropertyIndexTestClass.prop1"));
    Assert.assertNotNull(containsIndex(indexes, "propOne0"));
    Assert.assertNotNull(containsIndex(indexes, "propOne1"));
    Assert.assertNotNull(containsIndex(indexes, "propOne2"));
    Assert.assertNotNull(containsIndex(indexes, "propOne4"));
  }

  /**
   * Original: testIndexingCompositeRIDAndOthers (line 167) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaPropertyIndexTest.java Depends on:
   * createAdditionalSchemas
   */
  @Test
  public void test07_IndexingCompositeRIDAndOthers() throws Exception {
    var prev0 =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("propOne0")
            .size(session);
    var prev1 =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("propOne1")
            .size(session);

    session.begin();
    var doc =
        ((EntityImpl) session.newEntity("PropertyIndexTestClass")).properties("prop1",
            "testComposite3");

    ((EntityImpl) session.newEntity("PropertyIndexTestClass")).properties("prop0", doc, "prop1",
        "testComposite1");

    ((EntityImpl) session.newEntity("PropertyIndexTestClass")).properties("prop0", doc);

    session.commit();

    Assert.assertEquals(
        prev0 + 1,
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("propOne0")
            .size(session));
    Assert.assertEquals(
        prev1,
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("propOne1")
            .size(session));
  }

  /**
   * Original: testIndexingCompositeRIDAndOthersInTx (line 215) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaPropertyIndexTest.java Depends on:
   * testIndexingCompositeRIDAndOthers
   */
  @Test
  public void test08_IndexingCompositeRIDAndOthersInTx() throws Exception {
    session.begin();

    var prev0 =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("propOne0")
            .size(session);
    var prev1 =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("propOne1")
            .size(session);

    var doc =
        ((EntityImpl) session.newEntity("PropertyIndexTestClass")).properties("prop1",
            "testComposite34");

    ((EntityImpl) session.newEntity("PropertyIndexTestClass")).properties("prop0", doc, "prop1",
        "testComposite33");

    ((EntityImpl) session.newEntity("PropertyIndexTestClass")).properties("prop0", doc);

    session.commit();

    Assert.assertEquals(
        prev0 + 1,
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("propOne0")
            .size(session));
    Assert.assertEquals(
        prev1,
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("propOne1")
            .size(session));
  }

  /**
   * Original: testDropIndexes (line 263) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaPropertyIndexTest.java
   */
  @Test
  public void test09_DropIndexes() throws Exception {
    var schema = session.getMetadata().getSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");

    oClass.createIndex(
        "PropertyIndexFirstIndex",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop4"});

    oClass.createIndex(
        "PropertyIndexSecondIndex",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop4"});

    var indexes = oClass.getInvolvedIndexes(session, "prop4");
    for (var index : indexes) {
      session.getSharedContext().getIndexManager().dropIndex(session, index);
    }

    Assert.assertNull(
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("PropertyIndexFirstIndex"));
    Assert.assertNull(
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("PropertyIndexSecondIndex"));
  }

  private static Index containsIndex(final Collection<Index> indexes, final String indexName) {
    for (final var index : indexes) {
      if (index.getName().equals(indexName)) {
        return index;
      }
    }
    return null;
  }
}
