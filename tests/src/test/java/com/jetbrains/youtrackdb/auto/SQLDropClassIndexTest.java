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
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class SQLDropClassIndexTest extends BaseDBTest {

  private static final PropertyType EXPECTED_PROP1_TYPE = PropertyType.DOUBLE;
  private static final PropertyType EXPECTED_PROP2_TYPE = PropertyType.INTEGER;


  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    graph.autoExecuteInTx(g -> g.createSchemaClass("SQLDropClassTestClass",
        __.createSchemaProperty("prop1", EXPECTED_PROP1_TYPE).
            createSchemaProperty("prop2", EXPECTED_PROP2_TYPE)
    ));
  }

  @Test
  public void testIndexDeletion() throws Exception {
    graph.autoExecuteInTx(g -> g.schemaClass("SQLDropClassTestClass").
        createClassIndex("SQLDropClassCompositeIndex", IndexType.UNIQUE, "prop1", "prop2"));

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    Assert.assertNotNull(schema.getIndex("SQLDropClassCompositeIndex"));

    graph.autoExecuteInTx(g -> g.schemaClass("SQLDropClassTestClass").drop());
    schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    Assert.assertNull(
        schema.getClass("SQLDropClassTestClass"));
    Assert.assertNull(
        schema
            .getIndex("SQLDropClassCompositeIndex"));
    session.close();
    session = createSessionInstance();

    schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    Assert.assertNull(schema.getClass("SQLDropClassTestClass"));
    Assert.assertNull(schema.getIndex("SQLDropClassCompositeIndex"));
  }
}
