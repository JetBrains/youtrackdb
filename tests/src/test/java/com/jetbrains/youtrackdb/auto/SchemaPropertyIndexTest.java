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
package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class SchemaPropertyIndexTest extends BaseDBTest {
  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    final Schema schema = session.getMetadata().getSlowMutableSchema();
    final var oClass = schema.createClass("PropertyIndexTestClass");
    oClass.createProperty("prop0", PropertyType.LINK);
    oClass.createProperty("prop1", PropertyType.STRING);
    oClass.createProperty("prop2", PropertyType.INTEGER);
    oClass.createProperty("prop3", PropertyType.BOOLEAN);
    oClass.createProperty("prop4", PropertyType.INTEGER);
    oClass.createProperty("prop5", PropertyType.STRING);
  }

  @Override
  @AfterClass
  public void afterClass() throws Exception {
    if (session.isClosed()) {
      session = createSessionInstance();
    }

    session.begin();
    session.execute("delete from PropertyIndexTestClass");
    session.commit();

    session.execute("drop class PropertyIndexTestClass");

    super.afterClass();
  }

  @Test
  public void testCreateUniqueIndex() {
    var schema = session.getMetadata().getSlowMutableSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    final var propOne = oClass.getProperty("prop1");

    propOne.createIndex(SchemaManager.INDEX_TYPE.UNIQUE,
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
    Assert.assertEquals(indexDefinition.getParamCount(), 1);
    Assert.assertEquals(indexDefinition.getProperties().size(), 1);
    Assert.assertTrue(indexDefinition.getProperties().contains("prop1"));
    Assert.assertEquals(indexDefinition.getTypes().length, 1);
    Assert.assertEquals(indexDefinition.getTypes()[0], PropertyTypeInternal.STRING);
  }

  @Test(dependsOnMethods = {"testCreateUniqueIndex"})
  public void createAdditionalSchemas() {
    final Schema schema = session.getMetadata().getSlowMutableSchema();
    final var oClass = schema.getClass("PropertyIndexTestClass");

    oClass.createIndex(
        "propOne0",
        SchemaManager.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop0", "prop1"});
    oClass.createIndex(
        "propOne1",
        SchemaManager.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop1", "prop2"});
    oClass.createIndex(
        "propOne2",
        SchemaManager.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop1", "prop3"});
    oClass.createIndex(
        "propOne3",
        SchemaManager.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop2", "prop3"});
    oClass.createIndex(
        "propOne4",
        SchemaManager.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop2", "prop1"});
  }

  @Test(dependsOnMethods = "createAdditionalSchemas")
  public void testGetIndexes() {
    var schema = session.getMetadata().getSlowMutableSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    oClass.getProperty("prop1");

    var indexes = oClass.getInvolvedIndexesInternal(session, "prop1");
    Assert.assertEquals(indexes.size(), 1);
    Assert.assertNotNull(containsIndex(indexes, "PropertyIndexTestClass.prop1"));
  }

  @Test(dependsOnMethods = "createAdditionalSchemas")
  public void testGetAllIndexes() {
    var schema = session.getMetadata().getSlowMutableSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    var propOne = oClass.getPropertyInternal("prop1");

    final var indexes = propOne.getAllIndexesInternal();
    Assert.assertEquals(indexes.size(), 5);
    Assert.assertNotNull(containsIndex(indexes, "PropertyIndexTestClass.prop1"));
    Assert.assertNotNull(containsIndex(indexes, "propOne0"));
    Assert.assertNotNull(containsIndex(indexes, "propOne1"));
    Assert.assertNotNull(containsIndex(indexes, "propOne2"));
    Assert.assertNotNull(containsIndex(indexes, "propOne4"));
  }

  @Test
  public void testIsIndexedNonIndexedField() {
    var schema = session.getMetadata().getSlowMutableSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    var propThree = oClass.getPropertyInternal("prop3");

    Assert.assertTrue(propThree.getAllIndexes().isEmpty());
  }

  @Test(dependsOnMethods = {"testCreateUniqueIndex"})
  public void testIsIndexedIndexedField() {
    var schema = session.getMetadata().getSlowMutableSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    var propOne = oClass.getPropertyInternal("prop1");
    Assert.assertFalse(propOne.getAllIndexes().isEmpty());
  }

  @Test(dependsOnMethods = {"testIsIndexedIndexedField"})
  public void testIndexingCompositeRIDAndOthers() throws Exception {

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
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("propOne0")

            .size(session),
        prev0 + 1);
    Assert.assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("propOne1")

            .size(session),
        prev1);
  }

  @Test(dependsOnMethods = {"testIndexingCompositeRIDAndOthers"})
  public void testIndexingCompositeRIDAndOthersInTx() throws Exception {
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
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("propOne0")

            .size(session),
        prev0 + 1);
    Assert.assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("propOne1")

            .size(session),
        prev1);
  }

  @Test
  public void testDropIndexes() throws Exception {
    var schema = session.getMetadata().getSlowMutableSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");

    oClass.createIndex(
        "PropertyIndexFirstIndex",
        SchemaManager.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"prop4"});

    oClass.createIndex(
        "PropertyIndexSecondIndex",
        SchemaManager.INDEX_TYPE.UNIQUE.toString(),
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
