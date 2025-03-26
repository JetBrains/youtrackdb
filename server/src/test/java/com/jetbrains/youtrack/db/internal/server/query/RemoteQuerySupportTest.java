package com.jetbrains.youtrack.db.internal.server.query;

import static com.jetbrains.youtrack.db.api.config.GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Entity;
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

  public void beforeTest() {
    super.beforeTest();
    session.createClass("Some");
    session.createVertexClass("SomeVertex");
    session.createAbstractClass("AbstractSome");
    oldPageSize = QUERY_REMOTE_RESULTSET_PAGE_SIZE.getValueAsInteger();
    QUERY_REMOTE_RESULTSET_PAGE_SIZE.setValue(10);
  }

  @Test
  public void testQuery() {
    for (var i = 0; i < 150; i++) {
      session.begin();
      var doc = ((EntityImpl) session.newEntity("Some"));
      doc.setProperty("prop", "value");
      session.commit();
    }

    var res = session.query("select from Some");
    for (var i = 0; i < 150; i++) {
      assertTrue(res.hasNext());
      var item = res.next();
      assertEquals("value", item.getProperty("prop"));
    }
  }

  @Test
  public void testCommandSelect() {
    for (var i = 0; i < 150; i++) {
      session.begin();
      var doc = ((EntityImpl) session.newEntity("Some"));
      doc.setProperty("prop", "value");
      session.commit();
    }

    var tx = session.begin();
    var res = tx.execute("select from Some");
    for (var i = 0; i < 150; i++) {
      assertTrue(res.hasNext());
      var item = res.next();
      assertEquals("value", item.getProperty("prop"));
    }
    tx.commit();
  }

  @Test
  public void testCommandInsertWithPageOverflow() {
    for (var i = 0; i < 150; i++) {
      session.begin();
      var doc = ((EntityImpl) session.newVertex("SomeVertex"));
      doc.setProperty("prop", "value");
      session.commit();
    }

    session.begin();
    var res = session.execute("insert into V from select from SomeVertex");
    for (var i = 0; i < 150; i++) {
      assertTrue(res.hasNext());
      var item = res.next();
      assertEquals("value", item.getProperty("prop"));
    }
    session.commit();
  }

  @Test(expected = DatabaseException.class)
  public void testQueryKilledSession() {
    var tx = session.begin();
    for (var i = 0; i < 150; i++) {
      var doc = ((EntityImpl) tx.newEntity("Some"));
      doc.setProperty("prop", "value");
    }
    var res = tx.query("select from Some");

    for (var conn : server.getClientConnectionManager().getConnections()) {
      conn.close();
    }

    for (var i = 0; i < 150; i++) {
      assertTrue(res.hasNext());
      var item = res.next();
      assertEquals("value", item.getProperty("prop"));
    }
    tx.commit();
  }

  @Test
  public void testQueryEmbedded() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("Some"));
    doc.setProperty("prop", "value");
    var emb = ((EntityImpl) session.newEmbeddedEntity());
    emb.setProperty("one", "value");
    doc.setProperty("emb", emb, PropertyType.EMBEDDED);
    session.commit();

    var tx = session.begin();
    var res = tx.query("select emb from Some");

    var item = res.next();
    assertEquals("value", item.getProperty("one"));
    tx.commit();
  }

  @Test
  public void testQueryDoubleEmbedded() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("Some"));
    doc.setProperty("prop", "value");
    var emb1 = ((EntityImpl) session.newEmbeddedEntity());
    emb1.setProperty("two", "value");
    var emb = ((EntityImpl) session.newEmbeddedEntity());
    emb.setProperty("one", "value");
    emb.setProperty("secEmb", emb1, PropertyType.EMBEDDED);

    doc.setProperty("emb", emb, PropertyType.EMBEDDED);
    session.commit();

    var tx = session.begin();
    var res = session.query("select emb from Some");

    var resEmb = res.next();
    assertEquals("value", resEmb.getProperty("one"));
    assertEquals("value", ((Result) resEmb.getProperty("secEmb")).getProperty("two"));
    tx.commit();
  }

  @Test
  public void testQueryEmbeddedList() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("Some"));
    doc.setProperty("prop", "value");
    var emb = ((EntityImpl) session.newEmbeddedEntity());
    emb.setProperty("one", "value");

    List<Entity> list = session.newEmbeddedList();
    list.add(emb);
    doc.setProperty("list", list, PropertyType.EMBEDDEDLIST);
    session.commit();

    var tx = session.begin();
    var res = session.query("select list from Some");

    var item = res.next();
    assertNotNull(item.getProperty("list"));
    assertEquals(1, ((List<Result>) item.getProperty("list")).size());
    assertEquals("value", ((List<Result>) item.getProperty("list")).get(0).getProperty("one"));
    tx.commit();
  }

  @Test
  public void testQueryEmbeddedSet() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("Some"));
    doc.setProperty("prop", "value");
    var emb = ((EntityImpl) session.newEmbeddedEntity());
    emb.setProperty("one", "value");
    Set<EntityImpl> set = session.newEmbeddedSet();
    set.add(emb);
    doc.setProperty("set", set, PropertyType.EMBEDDEDSET);
    session.commit();

    var tx = session.begin();
    var res = session.query("select set from Some");

    var item = res.next();
    assertNotNull(item.getProperty("set"));
    assertEquals(1, ((Set<Result>) item.getProperty("set")).size());
    assertEquals(
        "value", ((Set<Result>) item.getProperty("set")).iterator().next().getProperty("one"));
    tx.commit();
  }


  @Test
  public void testQueryEmbeddedMap() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("Some"));
    doc.setProperty("prop", "value");
    var emb = ((EntityImpl) session.newEmbeddedEntity());
    emb.setProperty("one", "value");
    Map<String, EntityImpl> map = session.newEmbeddedMap();
    map.put("key", emb);
    doc.setProperty("map", map, PropertyType.EMBEDDEDMAP);
    session.commit();

    var tx = session.begin();
    var res = tx.query("select map from Some");

    var item = res.next();
    assertNotNull(item.getProperty("map"));
    assertEquals(1, ((Map<String, Result>) item.getProperty("map")).size());
    assertEquals(
        "value", ((Map<String, Result>) item.getProperty("map")).get("key").getProperty("one"));
    tx.commit();
  }

  @Test
  public void testCommandWithTX() {

    session.begin();

    session.execute("insert into Some set prop = 'value'");

    DBRecord record;

    try (var resultSet = session.execute("insert into Some set prop = 'value'")) {
      record = resultSet.next().asRecord();
    }

    session.commit();

    Assert.assertTrue(record.getIdentity().isPersistent());
  }

  @Test(expected = CommandExecutionException.class)
  public void testBrokenParameter() {
    try {
      var tx = session.begin();
      tx.query("select from Some where prop= ?", new Object()).close();
    } catch (RuntimeException e) {
      Assert.assertFalse(session.isTxActive());
      // should be possible to run a query after without getting the server stuck
      var tx = session.begin();
      tx.query("select from Some where prop= ?", new RecordId(10, 10)).close();
      tx.commit();
      throw e;
    }
  }

  @Test
  public void testScriptWithRidbags() {
    session.execute("create class testScriptWithRidbagsV extends V");
    session.execute("create class testScriptWithRidbagsE extends E");

    session.begin();
    session.execute("create vertex testScriptWithRidbagsV set name = 'a'");
    session.execute("create vertex testScriptWithRidbagsV set name = 'b'");

    session.execute(
        "create edge testScriptWithRidbagsE from (select from testScriptWithRidbagsV where name ="
            + " 'a') TO (select from testScriptWithRidbagsV where name = 'b');");
    session.commit();

    var script = "";
    script += "LET q1 = SELECT * FROM testScriptWithRidbagsV WHERE name = 'a';";
    script += "LET q2 = SELECT * FROM testScriptWithRidbagsV WHERE name = 'b';";
    script += "RETURN [$q1,$q2]";

    session.begin();
    var rs = session.runScript("sql", script);

    rs.stream().count();
    rs.close();
    session.commit();
  }

  @Test
  public void testLetOut() {
    session.execute("create class letVertex extends V");
    session.execute("create class letEdge extends E");

    session.begin();
    session.execute("create vertex letVertex set name = 'a'");
    session.execute("create vertex letVertex set name = 'b'");
    session.execute(
        "create edge letEdge from (select from letVertex where name = 'a') TO (select from"
            + " letVertex where name = 'b');");
    session.commit();

    var rs =
        session.query(
            "select $someNode.in('letEdge') from letVertex LET $someNode =out('letEdge');");
    assertEquals(2, rs.stream().count());
  }

  public void afterTest() {
    super.afterTest();
    QUERY_REMOTE_RESULTSET_PAGE_SIZE.setValue(oldPageSize);
  }
}
