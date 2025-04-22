package com.jetbrains.youtrack.db.internal.server.query;

import com.jetbrains.youtrack.db.internal.server.BaseServerMemoryDatabase;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

public class RemoteGraphTXTest extends BaseServerMemoryDatabase {

  @Override
  public void beforeTest() {
    super.beforeTest();
    session.executeSQLScript("""
        create class FirstV if not exists extends V;
        create class SecondV if not exists extends V;
        create class TestEdge if not exists extends E;
        """);
  }

  @Test
  public void itShouldDeleteEdgesInTx() {
    session.command("begin");
    session.execute("create vertex FirstV set id = '1'").close();
    session.execute("create vertex SecondV set id = '2'").close();
    session.command("commit");

    try (var resultSet =
        session.computeSQLScript("""
            begin;
            let $res = create edge TestEdge from (select from FirstV where id = '1') to (select from SecondV where id = '2');
            commit;
            return $res;
            """)) {
      var result = resultSet.stream().iterator().next();
      Assert.assertTrue(result.isIdentifiable());
    }

    session
        .computeSQLScript("""
                begin;
                let $res = delete edge TestEdge from (select from FirstV where id = :param1) to (select from SecondV where id = :param2);
                commit;
                return $res;
                """,
            Map.of("param1", "1", "param2", "2"))
        .stream()
        .toList();

    Assert.assertEquals(0, session.query("select from TestEdge").stream().count());
    var results =
        session.query("select bothE().size() as count from V").stream()
            .collect(Collectors.toList());

    for (var result : results) {
      Assert.assertEquals(0, (int) result.getProperty("count"));
    }
  }
}
