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
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class SQLDropIndexTest extends BaseDBTest {

  private static final PropertyType EXPECTED_PROP1_TYPE = PropertyType.DOUBLE;
  private static final PropertyType EXPECTED_PROP2_TYPE = PropertyType.INTEGER;

  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    graph.autoExecuteInTx(g -> g.createSchemaClass("SQLDropIndexTestClass",
        __.createSchemaProperty("prop1", EXPECTED_PROP1_TYPE),
        __.createSchemaProperty("prop2", EXPECTED_PROP2_TYPE)
    ));
  }

  @Override
  @AfterClass
  public void afterClass() throws Exception {
    if (session.isClosed()) {
      session = createSessionInstance();
    }

    graph.autoExecuteInTx(g -> g.V().hasLabel("SQLDropIndexTestClass").drop());
    graph.autoExecuteInTx(g -> g.schemaClass("SQLDropIndexTestClass").drop());

    super.afterClass();
  }

  @Test
  public void testOldSyntax() throws Exception {
    graph.autoExecuteInTx(g ->
        g.schemaClass("SQLDropIndexTestClass").schemaClassProperty("prop1").createPropertyIndex(
            IndexType.UNIQUE)
    );

    var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("SQLDropIndexTestClass")
            .getClassIndex("SQLDropIndexTestClass.prop1");
    Assert.assertNotNull(index);

    graph.autoExecuteInTx(g -> g.schemaIndex("SQLDropIndexTestClass.prop1").drop());

    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("SQLDropIndexTestClass")
            .getClassIndex("SQLDropIndexTestClass.prop1");
    Assert.assertNull(index);
  }

  @Test(dependsOnMethods = "testOldSyntax")
  public void testDropCompositeIndex() throws Exception {
    graph.autoExecuteInTx(g ->
        g.schemaIndex("SQLDropIndexTestClass")
            .createClassIndex("SQLDropIndexCompositeIndex", IndexType.UNIQUE, "prop1", "prop2")
    );

    var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("SQLDropIndexTestClass")
            .getClassIndex("SQLDropIndexCompositeIndex");
    Assert.assertNotNull(index);

    graph.autoExecuteInTx(g ->
        g.schemaIndex("SQLDropIndexCompositeIndex").drop()
    );

    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("SQLDropIndexTestClass")
            .getClassIndex("SQLDropIndexCompositeIndex");
    Assert.assertNull(index);
  }

  @Test(dependsOnMethods = "testDropCompositeIndex")
  public void testDropIndexWorkedCorrectly() {
    var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("SQLDropIndexTestClass")
            .getClassIndex("SQLDropIndexTestClass.prop1");
    Assert.assertNull(index);
    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("SQLDropIndexTestClass")
            .getClassIndex("SQLDropIndexWithoutClass");
    Assert.assertNull(index);
    index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("SQLDropIndexTestClass")
            .getClassIndex("SQLDropIndexCompositeIndex");
    Assert.assertNull(index);
  }
}
