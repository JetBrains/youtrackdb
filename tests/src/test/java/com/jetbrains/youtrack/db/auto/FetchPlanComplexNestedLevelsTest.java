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

import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class FetchPlanComplexNestedLevelsTest extends BaseDBTest {

  @Parameters(value = "remote")
  public FetchPlanComplexNestedLevelsTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    final SchemaClass personTest = database.getMetadata().getSchema().getClass("PersonTest");
    if (personTest == null) {
      database
          .getMetadata()
          .getSchema()
          .createClass("PersonTest", database.getMetadata().getSchema().getClass("V"));
    } else if (personTest.getSuperClass(database) == null) {
      personTest.setSuperClass(database, database.getMetadata().getSchema().getClass("V"));
    }

    final SchemaClass followTest = database.getMetadata().getSchema().getClass("FollowTest");
    if (followTest == null) {
      database
          .getMetadata()
          .getSchema()
          .createClass("FollowTest", database.getMetadata().getSchema().getClass("E"));
    } else if (followTest.getSuperClass(database) == null) {
      followTest.setSuperClass(database, database.getMetadata().getSchema().getClass("E"));
    }

    database.begin();
    database.command("create vertex PersonTest set name = 'A'").close();
    database.command("create vertex PersonTest set name = 'B'").close();
    database.command("create vertex PersonTest set name = 'C'").close();

    database
        .command(
            "create edge FollowTest from (select from PersonTest where name = 'A') to (select from"
                + " PersonTest where name = 'B')")
        .close();
    database
        .command(
            "create edge FollowTest from (select from PersonTest where name = 'B') to (select from"
                + " PersonTest where name = 'C')")
        .close();
    database.commit();
  }

  @Test
  public void queryAll2() {
    final List<EntityImpl> result =
        executeQuery(
            "select @this.toJSON('fetchPlan:*:2') as json from (select from PersonTest where"
                + " name='A')");

    Assert.assertEquals(result.size(), 1);
    String json = result.get(0).rawField("json");

    Assert.assertNotNull(json);

    final EntityImpl parsed = new EntityImpl();
    parsed.fromJSON(json);

    Assert.assertNotNull(parsed.rawField("out_FollowTest.in.out_FollowTest"));
  }

  @Test
  public void queryOutWildcard2() {
    final List<EntityImpl> result =
        executeQuery("select @this.toJSON('fetchPlan:out_*:2') as json from (select from PersonTest"
            + " where name='A')");

    Assert.assertEquals(result.size(), 1);
    String json = result.get(0).rawField("json");

    Assert.assertNotNull(json);

    final EntityImpl parsed = new EntityImpl();
    parsed.fromJSON(json);

    Assert.assertNotNull(parsed.rawField("out_FollowTest.in.out_FollowTest"));
  }

  @Test
  public void queryOutOneLevelOnly() {
    final List<EntityImpl> result =
        executeQuery(
            "select @this.toJSON('fetchPlan:[0]out_*:0') as json from (select from PersonTest"
                + " where name='A')");

    Assert.assertEquals(result.size(), 1);
    String json = result.get(0).field("json");

    Assert.assertNotNull(json);

    int pos = json.indexOf("\"in\":\"");
    Assert.assertTrue(pos > -1);

    Assert.assertEquals(json.charAt(pos) + 1, '#');
  }

  @Test
  public void startZeroGetOutStar2() {
    final List<EntityImpl> result =
        executeQuery(
            "select @this.toJSON('fetchPlan:[0]out_*:2') as json from (select from PersonTest"
                + " where name='A')");

    Assert.assertEquals(result.size(), 1);
    String json = result.get(0).field("json");

    Assert.assertNotNull(json);

    int pos = json.indexOf("\"in\":{");
    Assert.assertTrue(pos > -1);
  }

  @Test
  public void start2GetOutStar2() {
    final List<EntityImpl> result =
        executeQuery(
            "select @this.toJSON('fetchPlan:[2]out_*:2') as json from (select from PersonTest"
                + " where name='A')");

    Assert.assertEquals(result.size(), 1);
    String json = result.get(0).field("json");

    Assert.assertNotNull(json);

    int pos = json.indexOf("\"in\":\"");
    Assert.assertFalse(pos > -1);
  }
}
