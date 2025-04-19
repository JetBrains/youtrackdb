package com.jetbrains.youtrack.db.internal.server.query;

import static com.jetbrains.youtrack.db.api.config.GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.common.query.BasicResult;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.server.BaseServerMemoryDatabase;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class RemoteQuerySupportTest extends BaseServerMemoryDatabase {

  private int oldPageSize;

  @Override
  public void beforeTest() {
    super.beforeTest();

    session.executeSQLScript("""
        create class Some;
        create class SomeVertex extends V;
        create class AbstractSome abstract;
        """);

    oldPageSize = QUERY_REMOTE_RESULTSET_PAGE_SIZE.getValueAsInteger();
    QUERY_REMOTE_RESULTSET_PAGE_SIZE.setValue(10);
  }

  @Test
  public void testQuery() {
    session.executeSQLScript("""
        begin;
        let $i = 0;
        
        while ($i < 150) {
          insert into Some set prop = "value";
          $i = $i + 1;
        }
        
        commit;
        """);

    var res = session.query("select from Some");
    for (var i = 0; i < 150; i++) {
      assertTrue(res.hasNext());
      var item = res.next();
      assertEquals("value", item.getProperty("prop"));
    }
  }

  @Test
  public void testCommandSelect() {
    session.executeSQLScript("""
        begin;
        let $i = 0;
        
        while ($i < 150) {
          insert into Some set prop = "value";
          $i = $i + 1;
        }
        
        commit;
        """);

    var res = session.query("select from Some");
    for (var i = 0; i < 150; i++) {
      assertTrue(res.hasNext());
      var item = res.next();
      assertEquals("value", item.getProperty("prop"));
    }
  }

  @Test
  public void testCommandInsertWithPageOverflow() {
    session.executeSQLScript("""
        begin;
        let $i = 0;
        
        while ($i < 150) {
          create vertex SomeVertex set prop = "value";
          $i = $i + 1;
        }
        
        commit;
        """);

    session.command("begin");
    var res = session.execute("insert into V from select from SomeVertex");
    for (var i = 0; i < 150; i++) {
      assertTrue(res.hasNext());
      var item = res.next();
      assertEquals("value", item.getProperty("prop"));
    }
    session.command("commit");
  }

  @Test(expected = DatabaseException.class)
  public void testQueryKilledSession() {
    session.executeSQLScript("""
        begin;
        let $i = 0;
        
        while ($i < 150) {
          insert into Some set prop = "value";
          $i = $i + 1;
        }
        
        commit;
        """);

    var res = session.query("select from Some");

    for (var conn : server.getClientConnectionManager().getConnections()) {
      conn.close();
    }

    for (var i = 0; i < 150; i++) {
      assertTrue(res.hasNext());
      var item = res.next();
      assertEquals("value", item.getProperty("prop"));
    }
  }

  @Test
  public void testQueryEmbedded() {
    session.executeSQLScript("""
        begin;
        insert into Some set prop = "value", emb = {"one" : "value"};
        commit;
        """);
    var res = session.query("select emb from Some");

    var item = res.next();
    assertEquals("value", item.getProperty("one"));
  }

  @Test
  public void testQueryDoubleEmbedded() {
    session.executeSQLScript("""
        begin;
        insert into Some set prop = "value", emb = {"one" : "value", secEmb : {"two" : "value"}};
        commit;
        """);

    var res = session.query("select emb from Some");

    var resEmb = res.next();
    assertEquals("value", resEmb.getProperty("one"));
    assertEquals("value", ((BasicResult) resEmb.getProperty("secEmb")).getProperty("two"));
  }

  @Test
  public void testQueryEmbeddedList() {
    session.executeSQLScript("""
        begin;
        insert into Some set prop = "value", list = [{"one" : "value"}]};
        commit;
        """);

    var res = session.query("select list from Some");

    var item = res.next();
    assertNotNull(item.getProperty("list"));
    assertEquals(1, item.<RemoteResult>getEmbeddedList("list").size());
    assertEquals("value",
        item.<RemoteResult>getEmbeddedList("list").getFirst().getProperty("one"));
  }

  @Test
  public void testQueryEmbeddedSet() {
    session.executeSQLScript("""
        create property Some.set EMBEDDEDSET;
        begin;
        insert into Some set prop = "value", set = [{"one" : "value"}];
        commit;
        """);
    var res = session.query("select set from Some");

    var item = res.next();
    assertNotNull(item.getProperty("set"));
    assertEquals(1, item.getEmbeddedSet("set").size());
    assertEquals(
        "value", item.<RemoteResult>getEmbeddedSet("set").
            iterator().next().getProperty("one"));
  }


  @Test
  public void testQueryEmbeddedMap() {
    session.executeSQLScript("""
        begin;
        create property Some.map EMBEDDEDMAP;
        insert into Some set prop = 'value', map = {"key" : {"one" : "value"}};
        commit;
        """);

    var res = session.query("select map from Some");

    var item = res.next();
    assertNotNull(item.getProperty("map"));
    assertEquals(1, item.getEmbeddedMap("map").size());
    assertEquals(
        "value",
        item.<RemoteResult>getEmbeddedMap("map").get("key").getProperty("one"));
  }

  @Test
  public void testCommandWithTX() {
    session.command("begin");
    session.execute("insert into Some set prop = 'value'");
    RemoteResult result;

    try (var resultSet = session.execute("insert into Some set prop = 'value'")) {
      result = resultSet.next();
    }
    session.command("commit");

    Assert.assertTrue(result.getIdentity().isPersistent());
  }

  @Test(expected = CommandExecutionException.class)
  public void testBrokenParameter() {
    try {
      session.query("select from Some where prop= ?", new Object()).close();
    } catch (RuntimeException e) {
      // should be possible to run a query after without getting the server stuck
      session.query("select from Some where prop= ?", new RecordId(10, 10)).close();
      throw e;
    }
  }

  @Test
  public void testScriptWithRidbags() {
    session.execute("create class testScriptWithRidbagsV extends V");
    session.execute("create class testScriptWithRidbagsE extends E");

    session.command("begin");
    session.execute("create vertex testScriptWithRidbagsV set name = 'a'");
    session.execute("create vertex testScriptWithRidbagsV set name = 'b'");

    session.execute(
        "create edge testScriptWithRidbagsE from (select from testScriptWithRidbagsV where name ="
            + " 'a') TO (select from testScriptWithRidbagsV where name = 'b');");
    session.command("commit");

    var script = "";
    script += "LET q1 = SELECT * FROM testScriptWithRidbagsV WHERE name = 'a';";
    script += "LET q2 = SELECT * FROM testScriptWithRidbagsV WHERE name = 'b';";
    script += "RETURN [$q1,$q2]";

    session.command("begin");
    var rs = session.computeScript("sql", script);

    rs.stream().count();
    rs.close();
    session.command("commit");
  }

  @Test
  public void testLetOut() {
    session.execute("create class letVertex extends V");
    session.execute("create class letEdge extends E");

    session.command("begin");
    session.execute("create vertex letVertex set name = 'a'");
    session.execute("create vertex letVertex set name = 'b'");
    session.execute(
        "create edge letEdge from (select from letVertex where name = 'a') TO (select from"
            + " letVertex where name = 'b');");
    session.command("commit");

    var rs =
        session.query(
            "select $someNode.in('letEdge') from letVertex LET $someNode =out('letEdge');");
    assertEquals(2, rs.stream().count());
  }

  @Override
  public void afterTest() {
    super.afterTest();
    QUERY_REMOTE_RESULTSET_PAGE_SIZE.setValue(oldPageSize);
  }
}
