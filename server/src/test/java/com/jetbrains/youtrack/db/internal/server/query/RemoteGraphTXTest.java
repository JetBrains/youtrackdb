package com.jetbrains.youtrack.db.internal.server.query;

import com.jetbrains.youtrack.db.internal.server.BaseServerMemoryDatabase;
import java.util.HashMap;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class RemoteGraphTXTest extends BaseServerMemoryDatabase {

  public void beforeTest() {
    super.beforeTest();
    db.createClassIfNotExist("FirstV", "V");
    db.createClassIfNotExist("SecondV", "V");
    db.createClassIfNotExist("TestEdge", "E");
  }

  @Test
  public void itShouldDeleteEdgesInTx() {
    db.begin();
    db.execute("create vertex FirstV set id = '1'").close();
    db.execute("create vertex SecondV set id = '2'").close();
    db.commit();

    db.begin();
    try (var resultSet =
        db.execute(
            "create edge TestEdge  from ( select from FirstV where id = '1') to ( select from"
                + " SecondV where id = '2')")) {
      var result = resultSet.stream().iterator().next();

      Assert.assertTrue(result.isStatefulEdge());
    }
    db.commit();

    db.begin();
    db
        .execute(
            "delete edge TestEdge from (select from FirstV where id = :param1) to (select from"
                + " SecondV where id = :param2)",
            new HashMap() {
              {
                put("param1", "1");
                put("param2", "2");
              }
            })
        .stream()
        .collect(Collectors.toList());
    db.commit();

    db.begin();
    Assert.assertEquals(0, db.query("select from TestEdge").stream().count());
    var results =
        db.query("select bothE().size() as count from V").stream().collect(Collectors.toList());

    for (var result : results) {
      Assert.assertEquals(0, (int) result.getProperty("count"));
    }
    db.commit();
  }
}
