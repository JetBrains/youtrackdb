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
package com.jetbrains.youtrackdb.auto;

import org.testng.Assert;
import org.testng.annotations.Test;

public class SQLDeleteTest extends BaseDBTest {

  @Test
  public void deleteWithWhereOperator() {
    session.begin();
    session.execute("insert into Profile (sex, salary) values ('female', 2100)").close();
    session.commit();

    session.begin();
    final var total = session.countClass("Profile");
    session.rollback();

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

    session.begin();
    Assert.assertEquals(session.countClass("Profile"), total - count);
    session.rollback();
  }

  @Test
  public void deleteInPool() {
    var db = acquireSession();

    db.begin();
    final var total = db.countClass("Profile");
    db.rollback();

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

    db.begin();
    Assert.assertEquals(db.countClass("Profile"), total - count);
    db.rollback();

    db.close();
  }
}
