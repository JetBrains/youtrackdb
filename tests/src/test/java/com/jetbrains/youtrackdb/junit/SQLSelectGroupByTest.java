package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Tests for SQL SELECT with GROUP BY clause: basic grouping, limit, count,
 * ordering, and null handling.
 *
 */
class SQLSelectGroupByTest extends BaseDBJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();
    generateCompanyData();
  }

  @Test
  @Order(1)
  @Disabled("GROUP BY without aggregate function produces incorrect results")
  void queryGroupByBasic() {
    var result = executeQuery("select location from Account group by location");

    assertTrue(result.size() > 1);
    Set<Object> set = new HashSet<Object>();
    for (var d : result) {
      set.add(d.getProperty("location"));
    }
    assertEquals(set.size(), result.size());
  }

  @Test
  @Order(2)
  void queryGroupByLimit() {
    var result =
        executeQuery("select location from Account group by location limit 2");

    assertEquals(2, result.size());
  }

  @Test
  @Order(3)
  void queryGroupByCount() {
    var result =
        executeQuery("select count(*) from Account group by location");

    assertTrue(result.size() > 1);
  }

  @Test
  @Order(4)
  @Disabled("GROUP BY with ORDER BY produces incorrect results")
  void queryGroupByAndOrderBy() {
    var result =
        executeQuery("select location from Account group by location order by location");

    assertTrue(result.size() > 1);
    String last = null;
    for (var d : result) {
      if (last != null) {
        assertTrue(last.compareTo(d.getProperty("location")) < 0);
      }
      last = d.getProperty("location");
    }

    result = executeQuery(
        "select location from Account group by location order by location desc");

    assertTrue(result.size() > 1);
    last = null;
    for (var d : result) {
      var current = d.getProperty("location");
      if (current != null) {
        if (last != null) {
          assertTrue(last.compareTo((String) current) > 0);
        }
      }
      last = d.getProperty("location");
    }
  }

  @Test
  @Order(5)
  void queryGroupByAndWithNulls() {
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

      assertEquals(3, result.size());

      var foundNullGroup = false;
      for (var d : result) {
        if (d.getProperty("location") == null) {
          assertFalse(foundNullGroup);
          foundNullGroup = true;
        }
      }

      assertTrue(foundNullGroup);
      session.commit();

    } finally {
      session.begin();
      session.execute("delete vertex GroupByTest").close();
      session.commit();

      session.execute("drop class GroupByTest UNSAFE").close();
    }
  }

  @Test
  @Order(6)
  void queryGroupByNoNulls() {
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

      assertEquals(2, result.size());

      for (var d : result) {
        assertNotNull(d.getProperty("location"), "Found null in resultset with groupby");
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
