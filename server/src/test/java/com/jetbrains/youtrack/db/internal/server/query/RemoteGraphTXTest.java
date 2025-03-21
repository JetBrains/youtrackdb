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
    session.createClassIfNotExist("FirstV", "V");
    session.createClassIfNotExist("SecondV", "V");
    session.createClassIfNotExist("TestEdge", "E");
  }

  @Test
  public void itShouldDeleteEdgesInTx() {
    session.begin();
    session.execute("create vertex FirstV set id = '1'").close();
    session.execute("create vertex SecondV set id = '2'").close();
    session.commit();

    session.begin();
    try (var resultSet =
        session.execute(
            "create edge TestEdge  from ( select from FirstV where id = '1') to ( select from"
                + " SecondV where id = '2')")) {
      var result = resultSet.stream().iterator().next();

      Assert.assertTrue(result.isStatefulEdge());
    }
    session.commit();

    session.begin();
    session
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
    session.commit();

    session.begin();
    Assert.assertEquals(0, session.query("select from TestEdge").stream().count());
    var results =
        session.query("select bothE().size() as count from V").stream()
            .collect(Collectors.toList());

    for (var result : results) {
      Assert.assertEquals(0, (int) result.getProperty("count"));
    }
    session.commit();
  }
}
