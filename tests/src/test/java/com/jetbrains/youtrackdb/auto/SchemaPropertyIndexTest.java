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

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
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

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("PropertyIndexTestClass",
            __.createSchemaProperty("prop0", PropertyType.LINK),
            __.createSchemaProperty("prop1", PropertyType.STRING),
            __.createSchemaProperty("prop2", PropertyType.INTEGER),
            __.createSchemaProperty("prop3", PropertyType.BOOLEAN),
            __.createSchemaProperty("prop4", PropertyType.INTEGER),
            __.createSchemaProperty("prop5", PropertyType.STRING)
        )
    );
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
    graph.autoExecuteInTx(g ->
        g.schemaClass("PropertyIndexTestClass").schemaClassProperty("prop1").createPropertyIndex(
            YTDBSchemaIndex.IndexType.UNIQUE, true)
    );

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final Collection<Index> indexes = schema.getClass("PropertyIndexTestClass")
        .getInvolvedIndexes("prop1");
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
    graph.autoExecuteInTx(g ->
        g.schemaClass("PropertyIndexTestClass").
            createClassIndex("propOne0", YTDBSchemaIndex.IndexType.UNIQUE, true, "prop0", "prop1").
            createClassIndex("propOne1", YTDBSchemaIndex.IndexType.UNIQUE, true, "prop1", "prop2").
            createClassIndex("propOne2", YTDBSchemaIndex.IndexType.UNIQUE, true, "prop1", "prop3").
            createClassIndex("propOne3", YTDBSchemaIndex.IndexType.UNIQUE, true, "prop2", "prop3").
            createClassIndex("propOne4", YTDBSchemaIndex.IndexType.UNIQUE, true, "prop2", "prop1")
    );
  }

  @Test(dependsOnMethods = "createAdditionalSchemas")
  public void testGetIndexes() {
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    var oClass = schema.getClass("PropertyIndexTestClass");
    oClass.getProperty("prop1");

    var indexes = oClass.getInvolvedIndexes("prop1");
    Assert.assertEquals(indexes.size(), 1);
    Assert.assertNotNull(containsIndex(indexes, "PropertyIndexTestClass.prop1"));
  }

  @Test(dependsOnMethods = "createAdditionalSchemas")
  public void testGetAllIndexes() {
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    var oClass = schema.getClass("PropertyIndexTestClass");
    var propOne = oClass.getProperty("prop1");

    final var indexes = propOne.getIndexes();
    Assert.assertEquals(indexes.size(), 5);
    Assert.assertNotNull(containsIndex(indexes, "PropertyIndexTestClass.prop1"));
    Assert.assertNotNull(containsIndex(indexes, "propOne0"));
    Assert.assertNotNull(containsIndex(indexes, "propOne1"));
    Assert.assertNotNull(containsIndex(indexes, "propOne2"));
    Assert.assertNotNull(containsIndex(indexes, "propOne4"));
  }

  @Test
  public void testIsIndexedNonIndexedField() {
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    var oClass = schema.getClass("PropertyIndexTestClass");
    var propThree = oClass.getProperty("prop3");

    Assert.assertTrue(propThree.getIndexes().isEmpty());
  }

  @Test(dependsOnMethods = {"testCreateUniqueIndex"})
  public void testIsIndexedIndexedField() {
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    var oClass = schema.getClass("PropertyIndexTestClass");
    var propOne = oClass.getProperty("prop1");
    Assert.assertFalse(propOne.getIndexes().isEmpty());
  }

  @Test(dependsOnMethods = {"testIsIndexedIndexedField"})
  public void testIndexingCompositeRIDAndOthers() throws Exception {
    var prev0 =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("propOne0")

            .size(session);
    var prev1 =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
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
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("propOne0")

            .size(session),
        prev0 + 1);
    Assert.assertEquals(
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("propOne1")

            .size(session),
        prev1);
  }

  @Test(dependsOnMethods = {"testIndexingCompositeRIDAndOthers"})
  public void testIndexingCompositeRIDAndOthersInTx() throws Exception {
    session.begin();

    var prev0 =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("propOne0")

            .size(session);
    var prev1 =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
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
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("propOne0")

            .size(session),
        prev0 + 1);
    Assert.assertEquals(
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("propOne1")

            .size(session),
        prev1);
  }

  @Test
  public void testDropIndexes() {
    graph.autoExecuteInTx(g -> g.schemaClass("PropertyIndexTestClass").
        createClassIndex("PropertyIndexFirstIndex", YTDBSchemaIndex.IndexType.UNIQUE, true,
            "prop4").
        createClassIndex("PropertyIndexSecondIndex", YTDBSchemaIndex.IndexType.UNIQUE, true,
            "prop4")
    );

    graph.autoExecuteInTx(g -> g.schemaClass("PropertyIndexTestClass").schemaClassIndexes().drop());

    Assert.assertNull(
        session
            .getMetadata().getFastImmutableSchemaSnapshot()
            .getIndex("PropertyIndexFirstIndex"));
    Assert.assertNull(
        session
            .getMetadata().getFastImmutableSchemaSnapshot()
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
