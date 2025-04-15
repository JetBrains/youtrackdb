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

import java.util.HashSet;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Ignore;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class SQLSelectGroupByTest extends BaseDBTest {

  @Parameters(value = "remote")
  public SQLSelectGroupByTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();
    generateCompanyData();
  }

  @Test(enabled = false)
  public void queryGroupByBasic() {
    var result = executeQuery("select location from Account group by location");

    Assert.assertTrue(result.size() > 1);
    Set<Object> set = new HashSet<Object>();
    for (var d : result) {
      set.add(d.getProperty("location"));
    }
    Assert.assertEquals(result.size(), set.size());
  }

  @Test
  public void queryGroupByLimit() {
    var result =
        executeQuery("select location from Account group by location limit 2");

    Assert.assertEquals(result.size(), 2);
  }

  @Test
  public void queryGroupByCount() {
    var result =
        executeQuery("select count(*) from Account group by location");

    Assert.assertTrue(result.size() > 1);
  }

  @Test
  @Ignore
  public void queryGroupByAndOrderBy() {
    var result =
        executeQuery("select location from Account group by location order by location");

    Assert.assertTrue(result.size() > 1);
    String last = null;
    for (var d : result) {
      if (last != null) {
        Assert.assertTrue(last.compareTo(d.getProperty("location")) < 0);
      }
      last = d.getProperty("location");
    }

    result = executeQuery("select location from Account group by location order by location desc");

    Assert.assertTrue(result.size() > 1);
    last = null;
    for (var d : result) {
      var current = d.getProperty("location");
      if (current != null) {
        if (last != null) {
          Assert.assertTrue(last.compareTo((String) current) > 0);
        }
      }
      last = d.getProperty("location");
    }
  }

  @Test
  public void queryGroupByAndWithNulls() {
    // INSERT WITH NO LOCATION (AS NULL)
    session.execute("create class GroupByTest extends V").close();
    try {
      session.begin();
      session.execute("insert into GroupByTest set testNull = true").close();
      session.execute("insert into GroupByTest set location = 'Rome'").close();
      session.execute("insert into GroupByTest set location = 'Austin'").close();
      session.execute("insert into GroupByTest set location = 'Austin'").close();
      session.commit();

      final var result =
          executeQuery(
              "select location, count(*) from GroupByTest group by location");

      Assert.assertEquals(result.size(), 3);

      var foundNullGroup = false;
      for (var d : result) {
        if (d.getProperty("location") == null) {
          Assert.assertFalse(foundNullGroup);
          foundNullGroup = true;
        }
      }

      Assert.assertTrue(foundNullGroup);
    } finally {
      session.begin();
      session.execute("delete vertex GroupByTest").close();
      session.commit();

      session.execute("drop class GroupByTest UNSAFE").close();
    }
  }

  @Test
  public void queryGroupByNoNulls() {
    session.execute("create class GroupByTest extends V").close();
    try {
      session.begin();
      session.execute("insert into GroupByTest set location = 'Rome'").close();
      session.execute("insert into GroupByTest set location = 'Austin'").close();
      session.execute("insert into GroupByTest set location = 'Austin'").close();
      session.commit();

      final var result = executeQuery(
          "select location, count(*) from GroupByTest group by location");

      Assert.assertEquals(result.size(), 2);

      for (var d : result) {
        Assert.assertNotNull(d.getProperty("location"), "Found null in resultset with groupby");
      }

    } finally {
      session.begin();
      session.execute("delete vertex GroupByTest").close();
      session.commit();

      session.execute("drop class GroupByTest UNSAFE").close();
    }
  }
}
