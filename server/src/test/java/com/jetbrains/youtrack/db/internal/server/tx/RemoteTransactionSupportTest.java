package com.jetbrains.youtrack.db.internal.server.tx;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrack.db.internal.server.BaseServerMemoryDatabase;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class RemoteTransactionSupportTest extends BaseServerMemoryDatabase {

  private static final String FIELD_VALUE = "VALUE";

  @Override
  public void beforeTest() {
    GlobalConfiguration.CLASS_COLLECTIONS_COUNT.setValue(1);
    super.beforeTest();

    session.executeSQLScript("""
        create class SomeTx;
        create class SomeTx2;
        
        create class IndexedTx;
        create property IndexedTx.name STRING;
        create index IndexedTx.name on IndexedTx (name) NOTUNIQUE;
        
        create class UniqueIndexedTx;
        create property UniqueIndexedTx.name STRING;
        create index UniqueIndexedTx.name on UniqueIndexedTx (name) UNIQUE;
        """);
  }

  @Test
  public void testQueryUpdateUpdatedInTxTransactionScript() {
    var result = session.computeSQLScript("""
        begin;
        let $entity = insert into SomeTx set name ='Joe';
        let $resRid = $entity[0].@rid;
        commit;
        begin;
        update $resRid set name='Jane';
        let $res = update SomeTx set name='July' where name = 'Jane';
        commit;
        return {"count" : $res[0].count, "rid": $resRid};
        """).findFirst();
    var map = result.getEmbeddedMap("value");
    Assert.assertEquals(1L, map.get("count"));

    var name = session.query("select name from ?", map.get("rid"))
        .findFirst(remoteResult -> remoteResult.getString("name"));
    Assert.assertEquals("July", name);
  }

  @Test
  public void testQueryUpdateUpdatedInTxTransactionCommands() {
    session.command("begin");
    var resRid = session.execute("insert into SomeTx set name ='Joe'").findFirst().getIdentity();
    var updatedRids = session.execute("commit").findFirst().getLinkMap("updatedRids");
    resRid = updatedRids.get(resRid.toString()).getIdentity();
    Assert.assertNotNull(resRid);

    session.command("begin");
    session.command("update " + resRid + " set name='Jane'");
    long updateCount =
        session.execute("update SomeTx set name='July' where name = 'Jane'").findFirst()
            .getLong("count");
    Assert.assertEquals(1, updateCount);
    session.command("commit");

    var name = session.query("select name from ?", resRid)
        .findFirst(remoteResult -> remoteResult.getString("name"));
    Assert.assertEquals("July", name);
  }

  @Test
  public void testResetUpdatedInTxTransactionScript() {
    var result = session.computeSQLScript("""
        begin;
        insert into O set name = 'Jane';
        let $entity = insert into SomeTx set name = 'Jane';
        let $resRid = $entity[0].@rid;
        let $res = update SomeTx set name = 'July' where name = 'Jane';
        commit;
        return {"count" : $res[0].count, "rid": $resRid};
        """).findFirst();

    var map = result.getEmbeddedMap("value");
    Assert.assertEquals(1L, map.get("count"));

    var name = session.query("select name from ?", map.get("rid"))
        .findFirst(remoteResult -> remoteResult.getString("name"));
    Assert.assertEquals("July", name);
  }

  @Test
  public void testResetUpdatedInTxTransactionCommands() {
    session.command("begin");
    session.command("insert into O set name = 'Jane'");
    var rid = session.execute("insert into SomeTx set name = 'Jane'").findFirst().getIdentity();
    long count = session.execute("update SomeTx set name = 'July' where name = 'Jane'").findFirst()
        .getLong("count");
    var updatedRids = session.execute("commit").findFirst().getLinkMap("updatedRids");
    rid = updatedRids.get(rid.toString()).getIdentity();
    Assert.assertNotNull(rid);

    Assert.assertEquals(1L, count);

    var name = session.query("select name from ?", rid)
        .findFirst(remoteResult -> remoteResult.getString("name"));
    Assert.assertEquals("July", name);
  }

  @Test
  public void testQueryUpdateCreatedInTxTransactionScript() {
    var result = session.computeSQLScript("""
        begin;
        let $entity = insert into SomeTx set name = 'Jane';
        insert into SomeTx2 set name = 'Jane';
        let $res = update SomeTx set name = 'July' where name = 'Jane';
        commit;
        return {"count" : $res[0].count, "rid": $entity[0].@rid};
        """).findFirst();

    var map = result.getEmbeddedMap("value");
    Assert.assertEquals(1L, map.get("count"));

    var name = session.query("select name from ?", map.get("rid"))
        .findFirst(remoteResult -> remoteResult.getString("name"));
    Assert.assertEquals("July", name);
  }

  @Test
  public void testQueryUpdateCreatedInTxTransactionCommands() {
    session.command("begin");
    var rid = session.execute("insert into SomeTx set name = 'Jane'").findFirst().getIdentity();
    session.command("insert into SomeTx2 set name = 'Jane'");
    long count = session.execute("update SomeTx set name = 'July' where name = 'Jane'").findFirst()
        .getLong("count");
    var updatedRids = session.execute("commit").findFirst().getLinkMap("updatedRids");
    rid = updatedRids.get(rid.toString()).getIdentity();
    Assert.assertNotNull(rid);

    Assert.assertEquals(1L, count);

    var name = session.query("select name from ?", rid)
        .findFirst(remoteResult -> remoteResult.getString("name"));
    Assert.assertEquals("July", name);
  }


  @Test
  public void testRollbackTxTransactionScript() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name = 'Jane';
        commit;
        
        begin;
        insert into SomeTx set name = 'Jane';
        let $res = update SomeTx set name = 'July' where name = 'Jane';
        select assert(eval("$res[0].count = 2"), 'count is not 2');
        rollback;
        
        begin;
        select assert(eval("count = 1"), 'count is not 1') from(select count(*) as count from SomeTx where name = 'Jane');
        commit;
        """);
  }

  @Test
  public void testRollbackTxTransactionCommands() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name = 'Jane';
        commit;
        """);

    session.command("begin");
    session.command("insert into SomeTx set name = 'Jane'");

    long updateCount =
        session.execute("update SomeTx set name = 'July' where name = 'Jane'").findFirst()
            .getLong("count");
    Assert.assertEquals(2, updateCount);

    session.command("rollback");

    long selectCount = session.query("select count( *) as count from SomeTx where name = 'Jane'")
        .findFirst().getLong("count");

    Assert.assertEquals(1, selectCount);
  }

  @Test
  public void testRollbackTxCheckStatusTransactionScript() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name = 'Jane';
        commit;
        
        begin;
        insert into SomeTx set name = 'Jane';
        select assert (eval('count = 2'),'count is not 2')from(select count( *) as count from SomeTx
        where name = 'Jane');
        rollback;
        begin;
        select assert (eval('count = 1'),'count is not 1')from(select count( *) as count from SomeTx
        where name = 'Jane');
        commit;
        """);
  }

  @Test
  public void testRollbackTxCheckStatusTransactionCommands() {
    session.command("begin");
    session.command("insert into SomeTx set name = 'Jane'");
    session.command("commit");

    session.command("begin");
    session.command("insert into SomeTx set name = 'Jane'");
    long selectCount = session.query("select count(*) "
        + "as count from SomeTx  where name = 'Jane'").findFirst().getLong("count");
    Assert.assertEquals(2, selectCount);
    session.command("rollback");
    selectCount = session.query("select count(*) "
        + "as count from SomeTx  where name = 'Jane'").findFirst().getLong("count");
    Assert.assertEquals(1, selectCount);
  }

  @Test
  public void testQueryUpdateCreatedInTxSQLTransactionScript() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name = 'Jane';
        let $res = update SomeTx set name = 'July' where name = 'Jane';
        select assert (eval("count = 1"),'count is not 1')from $res;
        select assert (eval("name = 'July'"),'name is not July')from SomeTx where name = 'July';
        commit;
        """);
  }

  @Test
  public void testQueryUpdateCreatedInTxSQLTransactionCommands() {
    session.command("begin");
    session.command("insert into SomeTx set name = 'Jane'");
    long updateCount = session.execute("update SomeTx set name = 'July' where name = 'Jane'")
        .findFirst().getLong("count");
    Assert.assertEquals(1, updateCount);
    var fetchedName = session.query("select name from SomeTx where name = 'July'").findFirst()
        .getString("name");
    Assert.assertEquals("July", fetchedName);
    session.command("commit");
  }

  @Test
  public void testQueryDeleteTxSQLTransactionScript() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name = 'foo';
        commit;
        begin;
        delete from SomeTx;
        commit;
        begin;
        select assert (eval("count = 0"),'count is not 0')from(select count( *) as count from SomeTx);
        commit;
        """);
  }

  @Test
  public void testQueryDeleteTxSQLTransactionCommands() {
    session.command("begin");
    session.command("insert into SomeTx set name = 'foo'");
    session.command("commit");
    session.command("begin");
    session.command("delete from SomeTx");
    session.command("commit");

    long count = session.query("select count(*) as count from SomeTx").findFirst().getLong("count");
    Assert.assertEquals(0, count);
  }

  @Test
  public void testDoubleSaveTransactionScript() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name = 'foo';
        select assert (eval("count = 1"),'first case count is not 1')from(select count( *) as count
        from SomeTx);
        commit;
        begin;
        select assert (eval("count = 1"),'second case count is not 1')from(select count( *) as count
        from SomeTx);
        commit;
        """);
  }

  @Test
  public void testDoubleSaveTransactionCommands() {
    session.command("begin");
    session.command("insert into SomeTx set name = 'foo'");
    long count = session.execute("select count(*) as count from SomeTx").findFirst()
        .getLong("count");
    Assert.assertEquals(1, count);
    session.command("commit");
    count = session.execute("select count(*) as count from SomeTx").findFirst().getLong("count");
    Assert.assertEquals(1, count);
  }

  @Test
  public void testRefFlushedInTransactionScript() {
    session.executeSQLScript("""
        begin;
        let $entity = insert into SomeTx set name = 'foo';
        let $ref = $entity[0]. @rid ;
        insert into SomeTx set name = 'bar', ref = $ref;
        select assert (eval("count = 2"),'count is not 2')from(select count( *) as count from SomeTx);
        commit;
        begin;
        select assert (eval("$ref = ref"),'incorrect ref value')
        from(select ref as ref from SomeTx where name = 'bar');
        commit;
        """);
  }

  @Test
  public void testRefFlushedInTransactionCommands() {
    session.command("begin");
    var refRid = session.execute("insert into SomeTx set name = 'foo'").findFirst().getIdentity();
    session.command("insert into SomeTx set name = 'bar', ref = " + refRid);
    long count = session.execute("select count(*) as count from SomeTx").findFirst()
        .getLong("count");
    Assert.assertEquals(2, count);

    var updatedRids = session.execute("commit").findFirst().getLinkMap("updatedRids");
    refRid = updatedRids.get(refRid.toString()).getIdentity();
    Assert.assertNotNull(refRid);

    try (var rs = session.query("select ref as ref from SomeTx where name = 'bar'")) {
      var result = rs.next();
      var ref = result.getLink("ref");
      Assert.assertEquals(refRid, ref);
      Assert.assertFalse(rs.hasNext());
    }
  }

  @Test
  public void testDoubleRefFlushedInTransactionScript() {
    session.executeSQLScript("""
        begin;
        
        let $entity = insert into SomeTx set name = 'foo';
        let $ref = $entity[0]. @rid ;
        
        insert into SomeTx set name = 'bar', ref = $ref;
        select assert (eval("count = 2"),'count is not 2')from(select count( *) as count from SomeTx);
        
        let $entity2 = insert into SomeTx set name = 'other';
        let $ref2 = $entity2[0]. @rid ;
        update SomeTx set ref2 = $ref2 where name = 'bar';
        
        select assert (eval("count = 3"),'count is not 3')from(select count( *) as count from SomeTx);
        
        select assert (eval("$ref = ref"),'ref value is wrong inside tx'),assert (eval("$ref2 = ref2"),
        'ref2 value is wrong inside tx')
        from(select ref, ref2 from SomeTx where name = 'bar');
        commit;
        
        begin;
        select assert (eval("$ref = ref"),'ref value is wrong outside tx'),assert (eval("$ref2 = ref2"),
        'ref2 value is wrong outside tx')
        from(select ref, ref2 from SomeTx where name = 'bar');
        commit;
        """);
  }

  @Test
  public void testDoubleRefFlushedInTransactionCommands() {
    session.command("begin");
    var refRid = session.execute("insert into SomeTx set name = 'foo'").findFirst().getIdentity();
    session.command("insert into SomeTx set name = 'bar', ref = " + refRid);

    long count = session.query("select count( *) as count from SomeTx").findFirst()
        .getLong("count");
    Assert.assertEquals(2, count);

    var refRid2 = session.execute("insert into SomeTx set name = 'other'").
        findFirst().getIdentity();
    session.command("update SomeTx set ref2 = " + refRid2 + " where name = 'bar'");

    count = session.query("select count( *) as count from SomeTx").findFirst().getLong("count");
    Assert.assertEquals(3, count);

    try (var rs = session.query("select ref, ref2 from SomeTx where name = 'bar'")) {
      var result = rs.next();
      var ref = result.getLink("ref");
      Assert.assertEquals(refRid, ref);

      var ref2 = result.getLink("ref2");
      Assert.assertEquals(refRid2, ref2);

      Assert.assertFalse(rs.hasNext());
    }

    var updatedRids = session.execute("commit").findFirst().getLinkMap("updatedRids");

    refRid = updatedRids.get(refRid.toString()).getIdentity();
    refRid2 = updatedRids.get(refRid2.toString()).getIdentity();

    try (var rs = session.query("select ref, ref2 from SomeTx where name = 'bar'")) {
      var result = rs.next();
      var ref = result.getLink("ref");
      Assert.assertEquals(refRid, ref);

      var ref2 = result.getLink("ref2");
      Assert.assertEquals(refRid2, ref2);

      Assert.assertFalse(rs.hasNext());
    }
  }


  @Test
  public void testGenerateIdCounterTransactionScript() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name = 'Jane';
        insert into SomeTx set name = 'Jane1';
        insert into SomeTx set name = 'Jane2';
        insert into SomeTx set name = 'Jane3';
        insert into SomeTx set name = 'Jane4';
        insert into SomeTx set name = 'Jane2';
        select assert (eval("count = 6"),'count is not 6 inside tx')from(select count( *) as count
        from SomeTx);
        commit;
        begin;
        select assert (eval("count = 6"),'count is not 6 outside tx')from(select count( *) as count
        from SomeTx);
        commit;
        """);
  }

  @Test
  public void testGraphInTxScript() {
    session.executeSQLScript("""
        create class MyV extends V ;
        create class MyE extends E ;
        begin;
        let $v1 = create vertex MyV;
        let $v2 = create vertex MyV;
        create edge MyE from $v1 to $v2 set some = 'value';
        let $res = select outE('MyE') as edges from MyV where outE ('MyE').size() > 0;
        select assert (eval("$res.size() = 1"),'result size is not 1');
        let $res = select outE('MyE') as edges from MyV where outE ('MyE').size() > 0;
        select assert (eval("edges[0].some = 'value'"),'edge value is not correct')from $res;
        commit;
        """);
  }

  @Test
  public void testGraphInTxCommands() {
    session.command("create class MyV extends V");
    session.command("create class MyE extends E");

    session.command("begin");

    var v1Rid = session.execute("create vertex MyV").findFirst().getIdentity();
    var v2Rid = session.execute("create vertex MyV").findFirst().getIdentity();

    session.command("create edge MyE from ? to ? set some = 'value'", v1Rid, v2Rid);

    var edges = session.query("select outE('MyE') as edges from MyV where outE ('MyE').size() > 0")
        .findFirst().getLinkList("edges");
    var someValue = session.query("select some as some from ?", edges.getFirst()).findFirst()
        .getString("some");
    Assert.assertEquals("value", someValue);

    session.command("commit");
  }

  @Test
  public void testLinkBagsScript() {
    session.executeSQLScript("""
        create property SomeTx.rids LINKBAG;
        begin;
        
        let $v = insert into SomeTx;
        let $vRef = $v[0]. @rid ;
        insert into SomeTx set rids = $vRef;
        
        select assert (eval("rids.size() = 1"),'case 1: rids size is not 1'),
        assert (eval("rids.contains($vRef)"),'case 1: rids does not contain second vertex')
        from(select rids as rids from SomeTx where rids is not null);
        
        insert into SomeTx;
        
        select assert (eval("rids.size() = 1"),'case 2: rids size is not 1'),
        assert (eval("rids.contains($vRef)"),'case 2: rids does not contain second vertex')
        from(select rids as rids from SomeTx where rids is not null);
        
        commit;
        """);
  }

  @Test
  public void testLinkBagsCommands() {
    session.command("create property SomeTx.rids LINKBAG");
    session.command("begin");

    var vRef = session.execute("insert into SomeTx").findFirst().getIdentity();
    session.command("insert into SomeTx set rids = ?", vRef);

    try (var rs = session.query("select rids as rids from SomeTx where rids is not null")) {
      var result = rs.next();
      var rids = result.getLinkList("rids");
      Assert.assertEquals(1, rids.size());
      Assert.assertEquals(vRef, rids.getFirst());

      Assert.assertFalse(rs.hasNext());
    }

    session.command("insert into SomeTx");

    try (var rs = session.query("select rids as rids from SomeTx where rids is not null")) {
      var result = rs.next();
      var rids = result.getLinkList("rids");
      Assert.assertEquals(1, rids.size());
      Assert.assertEquals(vRef, rids.getFirst());

      Assert.assertFalse(rs.hasNext());
    }

    session.command("commit");
  }

  @Test
  public void testProperIndexingOnDoubleInternalBeginScript() {
    session.executeSQLScript("""
        begin;
        insert into IndexedTx set name =:fieldValue;
        let $entity = insert into SomeTx set name = 'foo';
        select assert (eval("count = 1"),'case 1: count is not 1 inside tx')from(select count( *)
        as count from $entity);
        commit;
        
        begin;
        select assert (eval("count = 1"),'case 2: count is not 1 outside tx')from(select count( *)
        as count from IndexedTx where name = :fieldValue);
        commit;
        """, Map.of("fieldValue", FIELD_VALUE));
  }


  @Test
  public void testProperIndexingOnDoubleInternalBeginScriptCommands() {
    session.command("begin");
    session.command("insert into IndexedTx set name =:fieldValue",
        Map.of("fieldValue", FIELD_VALUE));
    var rid = session.execute("insert into SomeTx set name = 'foo'").findFirst().getIdentity();
    long count = session.execute("select count(*) as count from SomeTx").findFirst()
        .getLong("count");
    Assert.assertEquals(1, count);
    var updatedRids = session.execute("commit").findFirst().getLinkMap("updatedRids");
    rid = updatedRids.get(rid.toString()).getIdentity();
    Assert.assertNotNull(rid);

    count = session.query("select count(*) as count from IndexedTx where name = :fieldValue",
            Map.of("fieldValue", FIELD_VALUE)).
        findFirst().getLong("count");
    Assert.assertEquals(1, count);
  }

  @Test(expected = RecordDuplicatedException.class)
  public void testDuplicateIndexTxScript() {
    session.executeSQLScript("""
        begin;
        insert into UniqueIndexedTx set name = 'a';
        insert into UniqueIndexedTx set name = 'a';
        commit;
        """);
  }

  @Test(expected = RecordDuplicatedException.class)
  public void testDuplicateIndexTxCommands() {
    session.command("begin");
    session.command("insert into UniqueIndexedTx set name = 'a'");
    session.command("insert into UniqueIndexedTx set name = 'a'");
    session.command("commit");
  }
}
