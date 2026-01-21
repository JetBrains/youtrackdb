/*
 * JUnit 4 version of SQLDeleteTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDeleteTest.java
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

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLDeleteTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDeleteTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLDeleteTest extends BaseDBTest {

  private static SQLDeleteTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLDeleteTest();
    instance.beforeClass();
  }

  /**
   * Original: deleteWithWhereOperator (line 24) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDeleteTest.java
   */
  @Test
  public void test01_DeleteWithWhereOperator() {
    session.begin();
    session.execute("insert into Profile (sex, salary) values ('female', 2100)").close();
    session.commit();

    final var total = session.countClass("Profile");

    session.begin();
    var resultset =
        session.query("select from Profile where sex = 'female' and salary = 2100");
    var queryCount = resultset.stream().count();
    resultset.close();
    session.commit();

    session.begin();
    var result =
        session.execute("delete from Profile where sex = 'female' and salary = 2100");
    session.commit();
    long count = result.next().getProperty("count");

    Assert.assertEquals(count, queryCount);

    Assert.assertEquals(session.countClass("Profile"), total - count);
  }

  /**
   * Original: deleteInPool (line 49) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDeleteTest.java
   */
  @Test
  public void test02_DeleteInPool() {
    var db = acquireSession();

    final var total = db.countClass("Profile");

    db.begin();
    var resultset =
        db.query("select from Profile where sex = 'male' and salary > 120 and salary <= 133");

    var queryCount = resultset.stream().count();
    resultset.close();
    db.commit();

    db.begin();
    var records =
        db.execute("delete from Profile where sex = 'male' and salary > 120 and salary <= 133");

    long count = records.next().getProperty("count");
    db.commit();
    Assert.assertEquals(count, queryCount);

    Assert.assertEquals(db.countClass("Profile"), total - count);

    db.close();
  }
}
