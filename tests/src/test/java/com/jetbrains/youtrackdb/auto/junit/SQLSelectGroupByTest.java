/*
 * JUnit 4 version of SQLSelectGroupByTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectGroupByTest.java
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

import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLSelectGroupByTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectGroupByTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLSelectGroupByTest extends BaseDBTest {

  private static SQLSelectGroupByTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLSelectGroupByTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 27) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectGroupByTest.java
   */
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();
    generateCompanyData();
  }

  /**
   * Original: queryGroupByBasic (line 35) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectGroupByTest.java Note: Original test
   * was disabled (enabled = false)
   */
  @Test
  @Ignore("Original test was disabled (enabled = false)")
  public void test01_QueryGroupByBasic() {
    var result = executeQuery("select location from Account group by location");

    Assert.assertTrue(result.size() > 1);
    Set<Object> set = new HashSet<Object>();
    for (var d : result) {
      set.add(d.getProperty("location"));
    }
    Assert.assertEquals(result.size(), set.size());
  }

  /**
   * Original: queryGroupByLimit (line 47) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectGroupByTest.java
   */
  @Test
  public void test02_QueryGroupByLimit() {
    var result =
        executeQuery("select location from Account group by location limit 2");

    Assert.assertEquals(result.size(), 2);
  }

  /**
   * Original: queryGroupByCount (line 55) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectGroupByTest.java
   */
  @Test
  public void test03_QueryGroupByCount() {
    var result =
        executeQuery("select count(*) from Account group by location");

    Assert.assertTrue(result.size() > 1);
  }

  /**
   * Original: queryGroupByAndOrderBy (line 64) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectGroupByTest.java Note: Original test
   * was @Ignore
   */
  @Test
  @Ignore("Original test was @Ignore")
  public void test04_QueryGroupByAndOrderBy() {
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

  /**
   * Original: queryGroupByAndWithNulls (line 93) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectGroupByTest.java
   */
  @Test
  public void test05_QueryGroupByAndWithNulls() {
    // INSERT WITH NO LOCATION (AS NULL)
    session.execute("create class GroupByTest extends V").close();
    try {
      session.begin();
      session.execute("insert into GroupByTest set testNull = true").close();
      session.execute("insert into GroupByTest set location = 'Rome'").close();
      session.execute("insert into GroupByTest set location = 'Austin'").close();
      session.execute("insert into GroupByTest set location = 'Austin'").close();
      session.commit();

      session.begin();
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
      session.commit();

    } finally {
      session.begin();
      session.execute("delete vertex GroupByTest").close();
      session.commit();

      session.execute("drop class GroupByTest UNSAFE").close();
    }
  }

  /**
   * Original: queryGroupByNoNulls (line 132) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectGroupByTest.java
   */
  @Test
  public void test06_QueryGroupByNoNulls() {
    session.execute("create class GroupByTest extends V").close();
    try {
      session.begin();
      session.execute("insert into GroupByTest set location = 'Rome'").close();
      session.execute("insert into GroupByTest set location = 'Austin'").close();
      session.execute("insert into GroupByTest set location = 'Austin'").close();
      session.commit();

      session.begin();
      final var result = executeQuery(
          "select location, count(*) from GroupByTest group by location");

      Assert.assertEquals(result.size(), 2);

      for (var d : result) {
        Assert.assertNotNull(d.getProperty("location"), "Found null in resultset with groupby");
      }
      session.commit();

    } finally {
      session.begin();
      session.execute("delete vertex GroupByTest").close();
      session.commit();

      session.execute("drop class GroupByTest UNSAFE").close();
    }
  }
}
