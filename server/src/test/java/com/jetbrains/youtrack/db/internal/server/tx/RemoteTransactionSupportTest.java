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
  public void testQueryUpdateUpdatedInTxTransaction() {
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
  public void testResetUpdatedInTxTransaction() {
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
  public void testQueryUpdateCreatedInTxTransaction() {
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
  public void testRollbackTxTransaction() {
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
  public void testRollbackTxCheckStatusTransaction() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name = 'Jane';
        commit;
        
        begin;
        insert into SomeTx set name = 'Jane';
        select assert (eval('count = 2'), 'count is not 2') from (select count(*) as count from SomeTx where name = 'Jane');
        rollback;
        begin;
        select assert (eval('count = 1'), 'count is not 1') from (select count( *)as count from SomeTx where name = 'Jane');
        commit;
        """);
  }

  @Test
  public void testQueryUpdateCreatedInTxSQLTransaction() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name = 'Jane';
        let $res = update SomeTx set name = 'July' where name = 'Jane';
        select assert(eval("count = 1"), 'count is not 1') from $res;
        select assert(eval("name = 'July'"), 'name is not July') from SomeTx where name = 'July';
        commit;
        """);
  }

  @Test
  public void testQueryDeleteTxSQLTransaction() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name = 'foo';
        commit;
        begin;
        delete from SomeTx;
        commit;
        begin;
        select assert (eval("count = 0"), 'count is not 0') from (select count(*) as count from SomeTx);
        commit;
        """);
  }

  @Test
  public void testDoubleSaveTransaction() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name = 'foo';
        select assert(eval("count = 1"), 'first case count is not 1') from(select count(*) as count from SomeTx);
        commit;
        begin;
        select assert(eval("count = 1"), 'second case count is not 1') from(select count(*) as count from SomeTx);
        commit;
        """);
  }

  @Test
  public void testRefFlushedInTransaction() {
    session.executeSQLScript("""
        begin;
        let $entity = insert into SomeTx set name = 'foo';
        let $ref = $entity[0].@rid;
        insert into SomeTx set name = 'bar', ref = $ref;
        select assert(eval("count = 2"), 'count is not 2') from(select count(*) as count from SomeTx);
        commit;
        begin;
        select assert(eval("$ref = ref"), 'incorrect ref value') from(select ref as ref from SomeTx where name = 'bar');
        commit;
        """);
  }

  @Test
  public void testDoubleRefFlushedInTransaction() {
    session.executeSQLScript("""
        begin;
        
        let $entity = insert into SomeTx set name = 'foo';
        let $ref = $entity[0].@rid;
        
        insert into SomeTx set name = 'bar', ref = $ref;
        select assert(eval("count = 2"), 'count is not 2') from(select count(*) as count from SomeTx);
        
        let $entity2 = insert into SomeTx set name = 'other';
        let $ref2 = $entity2[0].@rid;
        update SomeTx set ref2 = $ref2 where name = 'bar';
        
        select assert(eval("count = 3"), 'count is not 3') from(select count(*) as count from SomeTx);
        
        select assert(eval("$ref = ref"), 'ref value is wrong inside tx'), assert(eval("$ref2 = ref2"), 'ref2 value is wrong inside tx')
         from(select ref, ref2 from SomeTx where name = 'bar');
        commit;
        
        begin;
        select assert (eval("$ref = ref"), 'ref value is wrong outside tx'),assert(eval("$ref2 = ref2"), 'ref2 value is wrong outside tx')
         from(select ref, ref2 from SomeTx where name = 'bar');
        commit;
        """);
  }

  @Test
  public void testGenerateIdCounterTransaction() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name = 'Jane';
        insert into SomeTx set name = 'Jane1';
        insert into SomeTx set name = 'Jane2';
        insert into SomeTx set name = 'Jane3';
        insert into SomeTx set name = 'Jane4';
        insert into SomeTx set name = 'Jane2';
        select assert(eval("count = 6"), 'count is not 6 inside tx') from (select count( *)as count from SomeTx);
        commit;
        begin;
        select assert(eval("count = 6"), 'count is not 6 outside tx') from (select count( *)as count from SomeTx);
        commit;
        """);
  }

  @Test
  public void testGraphInTx() {
    session.executeSQLScript("""
        create class MyV extends V ;
        create class MyE extends E ;
        begin;
        let $v1 = create vertex MyV;
        let $v2 = create vertex MyV;
        create edge MyE from $v1 to $v2 set some = 'value';
        let $res = select outE('MyE') as edges from MyV where outE ('MyE').size() > 0;
        select assert(eval("$res.size() = 1"), 'result size is not 1');
        let $res = select outE('MyE') as edges from MyV where outE ('MyE').size() > 0;
        select assert(eval("edges[0].some = 'value'"), 'edge value is not correct') from $res;
        commit;
        """);
  }

  @Test
  public void testLinkBags() {
    session.executeSQLScript("""
        create property SomeTx.rids LINKBAG;
        begin;
        
        let $v = insert into SomeTx;
        let $vRef = $v[0].@rid;
        insert into SomeTx set rids = $vRef;
        
        select assert(eval("rids.size() = 1"), 'case 1: rids size is not 1'),assert(eval("rids.contains($vRef)"), 'case 1: rids does not contain second vertex')
         from (select rids as rids from SomeTx where rids is not null);
        
        insert into SomeTx;
        
        select assert(eval("rids.size() = 1"), 'case 2: rids size is not 1'),assert(eval("rids.contains($vRef)"), 'case 2: rids does not contain second vertex')
         from (select rids as rids from SomeTx where rids is not null);
        
        commit;
        """);
  }

  @Test
  public void testProperIndexingOnDoubleInternalBegin() {
    session.executeSQLScript("""
        begin;
        insert into IndexedTx set name =:fieldValue;
        let $entity = insert into SomeTx set name = 'foo';
        select assert(eval("count = 1"), 'case 1: count is not 1 inside tx') from (select count(*) as count from $entity);
        commit;
        
        begin;
        select assert(eval("count = 1"), 'case 1: count is not 1 inside tx') from (select count(*) as count from IndexedTx where name = :fieldValue);
        commit;
        """, Map.of("fieldValue", FIELD_VALUE));
  }

  @Test(expected = RecordDuplicatedException.class)
  public void testDuplicateIndexTx() {
    session.executeSQLScript("""
        begin;
        insert into UniqueIndexedTx set name = 'a';
        insert into UniqueIndexedTx set name = 'a';
        commit;
        """);
  }
}
