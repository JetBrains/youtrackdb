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
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

class SQLDeleteTest extends BaseDBJUnit5Test {

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLDeleteTest#deleteWithWhereOperator
  @Test
  @Order(1)
  void deleteWithWhereOperator() {
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

    assertEquals(queryCount, count);

    session.begin();
    assertEquals(total - count, session.countClass("Profile"));
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLDeleteTest#deleteInPool
  @Test
  @Order(2)
  void deleteInPool() {
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
    assertEquals(queryCount, count);

    db.begin();
    assertEquals(total - count, db.countClass("Profile"));
    db.rollback();

    db.close();
  }
}
