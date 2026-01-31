/*
 * JUnit 4 version of SQLDropSchemaPropertyIndexTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDropSchemaPropertyIndexTest.java
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

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import java.util.Arrays;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLDropSchemaPropertyIndexTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDropSchemaPropertyIndexTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLDropSchemaPropertyIndexTest extends BaseDBTest {

  private static final PropertyTypeInternal EXPECTED_PROP1_TYPE = PropertyTypeInternal.DOUBLE;
  private static final PropertyTypeInternal EXPECTED_PROP2_TYPE = PropertyTypeInternal.INTEGER;

  @BeforeClass
  public static void setUpClass() throws Exception {
    SQLDropSchemaPropertyIndexTest instance = new SQLDropSchemaPropertyIndexTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeMethod (line 33)
   */
  @Override
  @Before
  public void beforeMethod() throws Exception {
    super.beforeMethod();

    final Schema schema = session.getMetadata().getSchema();
    if (!schema.existsClass("DropPropertyIndexTestClass")) {
      final var oClass = schema.createClass("DropPropertyIndexTestClass");
      oClass.createProperty("prop1", EXPECTED_PROP1_TYPE.getPublicPropertyType());
      oClass.createProperty("prop2", EXPECTED_PROP2_TYPE.getPublicPropertyType());
    }
  }

  /**
   * Original: afterMethod (line 44)
   */
  @Override
  @After
  public void afterMethod() throws Exception {
    if (session.getMetadata().getSchema().existsClass("DropPropertyIndexTestClass")) {
      session.execute("drop class DropPropertyIndexTestClass").close();
    }
    super.afterMethod();
  }

  /**
   * Original: testForcePropertyEnabled (line 52)
   */
  @Test
  public void test01_ForcePropertyEnabled() throws Exception {
    session.execute(
            "CREATE INDEX DropPropertyIndexCompositeIndex ON DropPropertyIndexTestClass (prop2, prop1) UNIQUE")
        .close();

    var index =
        session.getMetadata().getSchema().getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");
    Assert.assertNotNull(index);

    session.execute("DROP PROPERTY DropPropertyIndexTestClass.prop1 FORCE").close();

    index =
        session.getMetadata().getSchema().getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");

    Assert.assertNull(index);
  }

  /**
   * Original: testForcePropertyEnabledBrokenCase (line 80)
   */
  @Test
  public void test02_ForcePropertyEnabledBrokenCase() throws Exception {
    session.execute(
            "CREATE INDEX DropPropertyIndexCompositeIndex ON DropPropertyIndexTestClass (prop2, prop1) UNIQUE")
        .close();

    var index =
        session.getMetadata().getSchema().getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");
    Assert.assertNotNull(index);

    session.execute("DROP PROPERTY DropPropertyIndextestclasS.prop1 FORCE").close();

    index =
        session.getMetadata().getSchema().getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");

    Assert.assertNull(index);
  }

  /**
   * Original: testForcePropertyDisabled (line 108)
   */
  @Test
  public void test03_ForcePropertyDisabled() throws Exception {
    session.execute(
            "CREATE INDEX DropPropertyIndexCompositeIndex ON DropPropertyIndexTestClass (prop1, prop2) UNIQUE")
        .close();

    var index =
        session.getMetadata().getSchema().getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");
    Assert.assertNotNull(index);

    try {
      session.execute("DROP PROPERTY DropPropertyIndexTestClass.prop1").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(
          e.getMessage().contains(
              "Property used in indexes (DropPropertyIndexCompositeIndex). Please drop these"
                  + " indexes before removing property or use FORCE parameter."));
    }

    index =
        session.getMetadata().getSchema().getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(Arrays.asList("prop1", "prop2"), indexDefinition.getProperties());
    Assert.assertArrayEquals(
        new PropertyTypeInternal[]{EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE},
        indexDefinition.getTypes());
    Assert.assertEquals("UNIQUE", index.getType());
  }

  /**
   * Original: testForcePropertyDisabledBrokenCase (line 153)
   */
  @Test
  public void test04_ForcePropertyDisabledBrokenCase() throws Exception {
    session.execute(
            "CREATE INDEX DropPropertyIndexCompositeIndex ON DropPropertyIndexTestClass (prop1, prop2) UNIQUE")
        .close();

    try {
      session.execute("DROP PROPERTY DropPropertyIndextestclass.prop1").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(
          e.getMessage().contains(
              "Property used in indexes (DropPropertyIndexCompositeIndex). Please drop these"
                  + " indexes before removing property or use FORCE parameter."));
    }

    final var index =
        session.getMetadata().getSchema().getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(Arrays.asList("prop1", "prop2"), indexDefinition.getProperties());
    Assert.assertArrayEquals(
        new PropertyTypeInternal[]{EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE},
        indexDefinition.getTypes());
    Assert.assertEquals("UNIQUE", index.getType());
  }
}
