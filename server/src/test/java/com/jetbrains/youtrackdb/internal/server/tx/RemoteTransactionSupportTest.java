package com.jetbrains.youtrackdb.internal.server.tx;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.server.BaseServerMemoryDatabase;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class RemoteTransactionSupportTest extends BaseServerMemoryDatabase {

  private static final String FIELD_VALUE = "VALUE";

  @Override
  public void beforeTest() {
    GlobalConfiguration.CLASS_COLLECTIONS_COUNT.setValue(1);
    super.beforeTest();

    traversal.command("create class SomeTx extends V");
    traversal.command("create class SomeTx2 extends V");
    traversal.command("create class IndexedTx extends V");

    traversal.command("create property IndexedTx.name STRING");
    traversal.command("create index IndexedTx.name on IndexedTx (name) NOTUNIQUE");

    traversal.command("create class UniqueIndexedTx extends V");
    traversal.command("create property UniqueIndexedTx.name STRING");
    traversal.command("create index UniqueIndexedTx.name on UniqueIndexedTx (name) UNIQUE");
  }

  @Ignore
  @Test
  public void testUpdateInTxTransaction() {
    var id = traversal.computeInTx(g -> {
      var vId = g.addV("SomeTx").property("name", "Joe").id().next();
      var updateVertices = g.V(vId).property("name", "Jane").
          V().has("SomeTx", "name", "Jane").property("name", "July").
          V().has("SomeTx", "name", "July").count().next();
      Assert.assertEquals(1L, updateVertices.longValue());
      return vId;
    });

    var v = traversal.computeInTx(g ->
        g.V(id).next()
    );

    Assert.assertEquals("July", v.<String>value("name"));
  }

  @Test
  @Ignore
  public void testRollbackTxTransactionScript() {
//    session.executeSQLScript("""
//        begin;
//        insert into SomeTx set name = 'Jane';
//        commit;
//
//        begin;
//        insert into SomeTx set name = 'Jane';
//        let $res = update SomeTx set name = 'July' where name = 'Jane';
//        select assert(eval("$res[0].count = 2"), 'count is not 2');
//        rollback;
//
//        begin;
//        select assert(eval("count = 1"), 'count is not 1') from(select count(*) as count from SomeTx where name = 'Jane');
//        commit;
//        """);
  }


  @Ignore
  @Test
  public void testQueryUpdateCreatedInTxSQLTransactionScript() {
//    session.executeSQLScript("""
//        begin;
//        insert into SomeTx set name = 'Jane';
//        let $res = update SomeTx set name = 'July' where name = 'Jane';
//        select assert (eval("count = 1"),'count is not 1')from $res;
//        select assert (eval("name = 'July'"),'name is not July')from SomeTx where name = 'July';
//        commit;
//        """);
  }


  @Ignore
  @Test
  public void testQueryDeleteTxSQLTransactionScript() {
//    session.executeSQLScript("""
//        begin;
//        insert into SomeTx set name = 'foo';
//        commit;
//        begin;
//        delete from SomeTx;
//        commit;
//        begin;
//        select assert (eval("count = 0"),'count is not 0')from(select count( *) as count from SomeTx);
//        commit;
//        """);
  }


  @Ignore
  @Test
  public void testDoubleSaveTransactionScript() {
//    session.executeSQLScript("""
//        begin;
//        insert into SomeTx set name = 'foo';
//        select assert (eval("count = 1"),'first case count is not 1')from(select count( *) as count
//        from SomeTx);
//        commit;
//        begin;
//        select assert (eval("count = 1"),'second case count is not 1')from(select count( *) as count
//        from SomeTx);
//        commit;
//        """);
  }

  @Ignore
  @Test
  public void testRefFlushedInTransactionScript() {
//    session.executeSQLScript("""
//        begin;
//        let $entity = insert into SomeTx set name = 'foo';
//        let $ref = $entity[0]. @rid ;
//        insert into SomeTx set name = 'bar', ref = $ref;
//        select assert (eval("count = 2"),'count is not 2')from(select count( *) as count from SomeTx);
//        commit;
//        begin;
//        select assert (eval("$ref = ref"),'incorrect ref value')
//        from(select ref as ref from SomeTx where name = 'bar');
//        commit;
//        """);
  }


  @Ignore
  @Test
  public void testGenerateIdCounterTransactionScript() {
//    session.executeSQLScript("""
//        begin;
//        insert into SomeTx set name = 'Jane';
//        insert into SomeTx set name = 'Jane1';
//        insert into SomeTx set name = 'Jane2';
//        insert into SomeTx set name = 'Jane3';
//        insert into SomeTx set name = 'Jane4';
//        insert into SomeTx set name = 'Jane2';
//        select assert (eval("count = 6"),'count is not 6 inside tx')from(select count( *) as count
//        from SomeTx);
//        commit;
//        begin;
//        select assert (eval("count = 6"),'count is not 6 outside tx')from(select count( *) as count
//        from SomeTx);
//        commit;
//        """);
  }


  @Ignore
  @Test
  public void testProperIndexingOnDoubleInternalBeginScript() {
//    session.executeSQLScript("""
//        begin;
//        insert into IndexedTx set name =:fieldValue;
//        let $entity = insert into SomeTx set name = 'foo';
//        select assert (eval("count = 1"),'case 1: count is not 1 inside tx')from(select count( *)
//        as count from $entity);
//        commit;
//
//        begin;
//        select assert (eval("count = 1"),'case 2: count is not 1 outside tx')from(select count( *)
//        as count from IndexedTx where name = :fieldValue);
//        commit;
//        """, Map.of("fieldValue", FIELD_VALUE));
  }

  @Ignore
  @Test(expected = RecordDuplicatedException.class)
  public void testDuplicateIndexTxScript() {
//    session.executeSQLScript("""
//        begin;
//        insert into UniqueIndexedTx set name = 'a';
//        insert into UniqueIndexedTx set name = 'a';
//        commit;
//        """);
  }
}
