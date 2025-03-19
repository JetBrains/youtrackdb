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

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class SQLDropClassIndexTest extends BaseDBTest {

  private static final PropertyType EXPECTED_PROP1_TYPE = PropertyType.DOUBLE;
  private static final PropertyType EXPECTED_PROP2_TYPE = PropertyType.INTEGER;

  @Parameters(value = "remote")
  public SQLDropClassIndexTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.createClass("SQLDropClassTestClass");
    oClass.createProperty("prop1", EXPECTED_PROP1_TYPE);
    oClass.createProperty("prop2", EXPECTED_PROP2_TYPE);
  }

  @Test
  public void testIndexDeletion() throws Exception {
    session
        .execute(
            "CREATE INDEX SQLDropClassCompositeIndex ON SQLDropClassTestClass (prop1, prop2)"
                + " UNIQUE")
        .close();

    Assert.assertNotNull(
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "SQLDropClassCompositeIndex"));

    session.execute("DROP CLASS SQLDropClassTestClass").close();

    Assert.assertNull(session.getMetadata().getSchema().getClass("SQLDropClassTestClass"));
    Assert.assertNull(
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "SQLDropClassCompositeIndex"));
    session.close();
    session = createSessionInstance();
    Assert.assertNull(session.getMetadata().getSchema().getClass("SQLDropClassTestClass"));
    Assert.assertNull(
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "SQLDropClassCompositeIndex"));
  }
}
