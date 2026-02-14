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

import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of SchemaIndexTest. Original test class:
 * com.jetbrains.youtrackdb.auto.SchemaIndexTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaIndexTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SchemaIndexTest extends BaseDBTest {

  @BeforeClass
  public static void setUpClass() throws Exception {
    SchemaIndexTest instance = new SchemaIndexTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeMethod (line 16) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaIndexTest.java
   */
  @Override
  @Before
  public void beforeMethod() throws Exception {
    super.beforeMethod();

    final Schema schema = session.getMetadata().getSchema();
    final var superTest = schema.createClass("SchemaSharedIndexSuperTest");
    final var test = schema.createClass("SchemaIndexTest", superTest);
    test.createProperty("prop1", PropertyType.DOUBLE);
    test.createProperty("prop2", PropertyType.DOUBLE);
  }

  /**
   * Original: tearDown (line 26) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaIndexTest.java
   */
  @Override
  @After
  public void afterMethod() throws Exception {
    if (session.getMetadata().getSchema().existsClass("SchemaIndexTest")) {
      session.execute("drop class SchemaIndexTest").close();
    }
    if (session.getMetadata().getSchema().existsClass("SchemaSharedIndexSuperTest")) {
      session.execute("drop class SchemaSharedIndexSuperTest").close();
    }

    super.afterMethod();
  }

  /**
   * Original: testDropClass (line 34) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaIndexTest.java
   */
  @Test
  public void test01_DropClass() throws Exception {
    session
        .execute(
            "CREATE INDEX SchemaSharedIndexCompositeIndex ON SchemaIndexTest (prop1, prop2) UNIQUE")
        .close();
    session.getSharedContext().getIndexManager().reload(session);
    Assert.assertNotNull(
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("SchemaSharedIndexCompositeIndex"));

    session.getMetadata().getSchema().dropClass("SchemaIndexTest");
    session.getSharedContext().getIndexManager().reload(session);

    Assert.assertNull(session.getMetadata().getSchema().getClass("SchemaIndexTest"));
    Assert.assertNotNull(session.getMetadata().getSchema().getClass("SchemaSharedIndexSuperTest"));

    Assert.assertNull(
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("SchemaSharedIndexCompositeIndex"));
  }

  /**
   * Original: testDropSuperClass (line 60) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaIndexTest.java
   */
  @Test
  public void test02_DropSuperClass() throws Exception {
    session
        .execute(
            "CREATE INDEX SchemaSharedIndexCompositeIndex ON SchemaIndexTest (prop1, prop2) UNIQUE")
        .close();

    try {
      session.getMetadata().getSchema().dropClass("SchemaSharedIndexSuperTest");
      Assert.fail();
    } catch (SchemaException e) {
      Assert.assertTrue(
          e.getMessage()
              .startsWith(
                  "Class 'SchemaSharedIndexSuperTest' cannot be dropped because it has sub"
                      + " classes"));
    }

    Assert.assertNotNull(session.getMetadata().getSchema().getClass("SchemaIndexTest"));
    Assert.assertNotNull(session.getMetadata().getSchema().getClass("SchemaSharedIndexSuperTest"));

    Assert.assertNotNull(
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("SchemaSharedIndexCompositeIndex"));
  }

  /**
   * Original: testIndexWithNumberProperties (line 89) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaIndexTest.java
   */
  @Test
  public void test03_IndexWithNumberProperties() {
    var oclass = session.getMetadata().getSchema()
        .createClass("SchemaIndexTest_numberclass");
    oclass.createProperty("1", PropertyType.STRING).setMandatory(false);
    oclass.createProperty("2", PropertyType.STRING).setMandatory(false);
    oclass.createIndex("SchemaIndexTest_numberclass_1_2", SchemaClass.INDEX_TYPE.UNIQUE,
        "1",
        "2");

    session.getMetadata().getSchema().dropClass(oclass.getName());
  }
}
