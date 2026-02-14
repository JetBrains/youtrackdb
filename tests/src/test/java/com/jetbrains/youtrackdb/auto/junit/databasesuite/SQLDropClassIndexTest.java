/*
 * JUnit 4 version of SQLDropClassIndexTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDropClassIndexTest.java
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

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLDropClassIndexTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDropClassIndexTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLDropClassIndexTest extends BaseDBTest {

  private static final PropertyType EXPECTED_PROP1_TYPE = PropertyType.DOUBLE;
  private static final PropertyType EXPECTED_PROP2_TYPE = PropertyType.INTEGER;

  @BeforeClass
  public static void setUpClass() throws Exception {
    SQLDropClassIndexTest instance = new SQLDropClassIndexTest();
    instance.beforeClass();
    instance.createTestSchema();
  }

  private void createTestSchema() {
    final Schema schema = session.getMetadata().getSchema();
    if (schema.existsClass("SQLDropClassTestClass")) {
      return;
    }

    final var oClass = schema.createClass("SQLDropClassTestClass");
    oClass.createProperty("prop1", EXPECTED_PROP1_TYPE);
    oClass.createProperty("prop2", EXPECTED_PROP2_TYPE);
  }

  /**
   * Original: testIndexDeletion (line 41)
   */
  @Test
  public void test01_IndexDeletion() throws Exception {
    session.execute(
            "CREATE INDEX SQLDropClassCompositeIndex ON SQLDropClassTestClass (prop1, prop2) UNIQUE")
        .close();

    Assert.assertNotNull(
        session.getSharedContext().getIndexManager().getIndex("SQLDropClassCompositeIndex"));

    session.execute("DROP CLASS SQLDropClassTestClass").close();

    Assert.assertNull(session.getMetadata().getSchema().getClass("SQLDropClassTestClass"));
    Assert.assertNull(
        session.getSharedContext().getIndexManager().getIndex("SQLDropClassCompositeIndex"));

    session.close();
    session = createSessionInstance();

    Assert.assertNull(session.getMetadata().getSchema().getClass("SQLDropClassTestClass"));
    Assert.assertNull(
        session.getSharedContext().getIndexManager().getIndex("SQLDropClassCompositeIndex"));
  }
}
