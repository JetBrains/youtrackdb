/*
 * JUnit 4 version of SQLDropIndexTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDropIndexTest.java
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

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLDropIndexTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDropIndexTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLDropIndexTest extends BaseDBTest {

  private static final PropertyType EXPECTED_PROP1_TYPE = PropertyType.DOUBLE;
  private static final PropertyType EXPECTED_PROP2_TYPE = PropertyType.INTEGER;

  @BeforeClass
  public static void setUpClass() throws Exception {
    SQLDropIndexTest instance = new SQLDropIndexTest();
    instance.beforeClass();
    instance.createTestSchema();
  }

  private void createTestSchema() {
    final Schema schema = session.getMetadata().getSchema();
    if (schema.existsClass("SQLDropIndexTestClass")) {
      return;
    }

    final var oClass = schema.createClass("SQLDropIndexTestClass");
    oClass.createProperty("prop1", EXPECTED_PROP1_TYPE);
    oClass.createProperty("prop2", EXPECTED_PROP2_TYPE);
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    SQLDropIndexTest instance = new SQLDropIndexTest();
    instance.beforeClass();
    if (instance.session.isClosed()) {
      instance.session = instance.createSessionInstance();
    }

    instance.session.begin();
    instance.session.execute("delete from SQLDropIndexTestClass").close();
    instance.session.commit();
    instance.session.execute("drop class SQLDropIndexTestClass").close();

    instance.afterClass();
  }

  /**
   * Original: testOldSyntax (line 56)
   */
  @Test
  public void test01_OldSyntax() throws Exception {
    session.execute("CREATE INDEX SQLDropIndexTestClass.prop1 UNIQUE").close();

    var index =
        session.getMetadata().getSchema().getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexTestClass.prop1");
    Assert.assertNotNull(index);

    session.execute("DROP INDEX SQLDropIndexTestClass.prop1").close();

    index =
        session.getMetadata().getSchema().getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexTestClass.prop1");
    Assert.assertNull(index);
  }

  /**
   * Original: testDropCompositeIndex (line 79) - depends on testOldSyntax
   */
  @Test
  public void test02_DropCompositeIndex() throws Exception {
    session.execute(
            "CREATE INDEX SQLDropIndexCompositeIndex ON SQLDropIndexTestClass (prop1, prop2) UNIQUE")
        .close();

    var index =
        session.getMetadata().getSchema().getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexCompositeIndex");
    Assert.assertNotNull(index);

    session.execute("DROP INDEX SQLDropIndexCompositeIndex").close();

    index =
        session.getMetadata().getSchema().getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexCompositeIndex");
    Assert.assertNull(index);
  }

  /**
   * Original: testDropIndexWorkedCorrectly (line 106) - depends on testDropCompositeIndex
   */
  @Test
  public void test03_DropIndexWorkedCorrectly() {
    var index =
        session.getMetadata().getSchema().getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexTestClass.prop1");
    Assert.assertNull(index);
    index =
        session.getMetadata().getSchema().getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexWithoutClass");
    Assert.assertNull(index);
    index =
        session.getMetadata().getSchema().getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexCompositeIndex");
    Assert.assertNull(index);
  }
}
