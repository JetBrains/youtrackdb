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
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.Arrays;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test
public class SQLDropSchemaPropertyIndexTest extends BaseDBTest {
  private static final PropertyTypeInternal EXPECTED_PROP1_TYPE = PropertyTypeInternal.DOUBLE;
  private static final PropertyTypeInternal EXPECTED_PROP2_TYPE = PropertyTypeInternal.INTEGER;

  @Override
  @BeforeMethod
  public void beforeMethod() throws Exception {
    super.beforeMethod();

    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.createClass("DropPropertyIndexTestClass");
    oClass.createProperty("prop1", EXPECTED_PROP1_TYPE.getPublicPropertyType());
    oClass.createProperty("prop2", EXPECTED_PROP2_TYPE.getPublicPropertyType());
  }

  @Override
  @AfterMethod
  public void afterMethod() throws Exception {
    session.execute("drop class DropPropertyIndexTestClass").close();

    super.afterMethod();
  }

  @Test
  public void testForcePropertyEnabled() throws Exception {
    session
        .execute(
            "CREATE INDEX DropPropertyIndexCompositeIndex ON DropPropertyIndexTestClass (prop2,"
                + " prop1) UNIQUE")
        .close();

    var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");
    Assert.assertNotNull(index);

    session.execute("DROP PROPERTY DropPropertyIndexTestClass.prop1 FORCE").close();

    index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");

    Assert.assertNull(index);
  }

  @Test
  public void testForcePropertyEnabledBrokenCase() throws Exception {
    session
        .execute(
            "CREATE INDEX DropPropertyIndexCompositeIndex ON DropPropertyIndexTestClass (prop2,"
                + " prop1) UNIQUE")
        .close();

    var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");
    Assert.assertNotNull(index);

    session.execute("DROP PROPERTY DropPropertyIndextestclasS.prop1 FORCE").close();

    index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");

    Assert.assertNull(index);
  }

  @Test
  public void testForcePropertyDisabled() throws Exception {
    session
        .execute(
            "CREATE INDEX DropPropertyIndexCompositeIndex ON DropPropertyIndexTestClass (prop1,"
                + " prop2) UNIQUE")
        .close();

    var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");
    Assert.assertNotNull(index);

    try {
      session.execute("DROP PROPERTY DropPropertyIndexTestClass.prop1").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(
          e.getMessage()
              .contains(
                  "Property used in indexes (DropPropertyIndexCompositeIndex). Please drop these"
                      + " indexes before removing property or use FORCE parameter."));
    }

    index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), Arrays.asList("prop1", "prop2"));
    Assert.assertEquals(
        indexDefinition.getTypes(), new PropertyTypeInternal[]{EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE});
    Assert.assertEquals(index.getType(), "UNIQUE");
  }

  @Test
  public void testForcePropertyDisabledBrokenCase() throws Exception {
    session
        .execute(
            "CREATE INDEX DropPropertyIndexCompositeIndex ON DropPropertyIndexTestClass (prop1,"
                + " prop2) UNIQUE")
        .close();

    try {
      session.execute("DROP PROPERTY DropPropertyIndextestclass.prop1").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(
          e.getMessage()
              .contains(
                  "Property used in indexes (DropPropertyIndexCompositeIndex). Please drop these"
                      + " indexes before removing property or use FORCE parameter."));
    }

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), Arrays.asList("prop1", "prop2"));
    Assert.assertEquals(
        indexDefinition.getTypes(), new PropertyTypeInternal[]{EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE});
    Assert.assertEquals(index.getType(), "UNIQUE");
  }
}
