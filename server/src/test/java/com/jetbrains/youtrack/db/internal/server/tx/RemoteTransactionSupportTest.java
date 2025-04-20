package com.jetbrains.youtrack.db.internal.server.tx;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrack.db.internal.server.BaseServerMemoryDatabase;

import java.util.Map;
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
    session.executeSQLScript("""
        begin;
        let $entity = insert into SomeTx set name ='Joe';
        commit;
        begin;
        update $entity set name='Jane';
        let $res = update SomeTx set name='July' where name = 'Jane';
        commit;
        select assert($res.count = 1);
        select assert(name = 'July') from $entity;
        """);
  }

  @Test
  public void testResetUpdatedInTxTransaction() {
    session.executeSQLScript("""
        begin;
        insert into O set name ='Jane';
        let $ entity = insert into SomeTx set name ='Jane';
        let $res = update SomeTx set name='July' where name = 'Jane';
        select assert ($res.count = 1);
        select assert(name = 'July') from $entity;
        commit;
        """);
  }

  @Test
  public void testQueryUpdateCreatedInTxTransaction() {
    session.executeSQLScript("""
        begin;
        let $entity = insert into SomeTx set name ='Jane';
        insert into SomeTx2 set name ='Jane';
        let $res = update SomeTx set name='July' where name = 'Jane';
        select assert($res.count = 1);
        select assert(name = 'July') from $entity;
        commit;
        """);
  }

  @Test
  public void testRollbackTxTransaction() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name ='Jane';
        commit;
        
        begin;
        insert into SomeTx set name ='Jane';
        let $res = update SomeTx set name='July' where name = 'Jane';
        assert($res.count = 2);
        rollback;
        
        select assert(count = 1) from (select count(*) as count from SomeTx where name='Jane');
        """);
  }

  @Test
  public void testRollbackTxCheckStatusTransaction() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name ='Jane';
        commit;
        
        begin;
        insert into SomeTx set name ='Jane';
        select assert(count = 2) from select count(*) as count from SomeTx where name='Jane';
        rollback;
        
        select assert(count = 1) from select count(*) as count from SomeTx where name='Jane';
        """);
  }

  @Test
  public void testQueryUpdateCreatedInTxSQLTransaction() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name ='Jane';
        select assert(count = 1) from update SomeTx set name='July' where name = 'Jane';
        select assert(name = 'July') from SomeTx where name = 'July';
        commit;
        """);
  }

  @Test
  public void testQueryDeleteTxSQLTransaction() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name ='foo';
        commit;
        begin;
        delete from SomeTx;
        commit;
        select assert(count = 0) from select count(*) as count from SomeTx;
        """);
  }

  @Test
  public void testDoubleSaveTransaction() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name ='foo';
        select assert(count = 1) from select count(*) as count from SomeTx;
        commit;
        select assert(count = 1) from select count(*) as count from SomeTx;
        """);
  }

  @Test
  public void testRefFlushedInTransaction() {
    session.executeSQLScript("""
        begin;
        let $entity = insert into SomeTx set name ='foo';
        insert into SomeTx set name ='bar', ref=$entity[0];
        select assert(count = 2) from (select count(*) as count from SomeTx);
        commit;
        select assert($entity[0] = ref) from (select ref from SomeTx where name='bar');
        """);
  }

  @Test
  public void testDoubleRefFlushedInTransaction() {
    session.executeSQLScript("""
        begin;
        let $ref = insert into SomeTx set name ='foo';
        insert into SomeTx set name ='bar', ref=$ref[0];
        select assert(count = 2) from (select count(*) as count from SomeTx);
        let $ref2 = insert into SomeTx set name ='other'
        update SomeTx set ref2=$ref2[0] where name='bar';
        
        select assert(count = 3) from (select count(*) as count from SomeTx);
        select assert($ref[0], ref), assert($ref2[0], ref2) from (select ref from SomeTx where name='bar');
        
        commit;
        
        select assert($ref[0], ref), assert($ref2[0], ref2) from (select ref from SomeTx where name='bar');
        """);
  }

  @Test
  public void testGenerateIdCounterTransaction() {
    session.executeSQLScript("""
        begin;
        insert into SomeTx set name ='Jane';
        insert into SomeTx set name ='Jane1';
        insert into SomeTx set name ='Jane2';
        insert into SomeTx set name ='Jane3';
        insert into SomeTx set name ='Jane4';
        insert into SomeTx set name ='Jane2';
        select assert(count = 6) from select count(*) as count from SomeTx;
        commit;
        select assert(count = 6) from select count(*) as count from SomeTx;
        """);
  }

  @Test
  public void testGraphInTx() {
    session.executeSQLScript("""
        create class MyV extends V;
        create class MyE extends E;
        begin;
        let $v1 = create vertex MyV;
        let $v2 = create vertex MyV;
        create edge MyE from $v1 to $v2 set some='value';
        let $res = select outE('MyE') as edges from MyV where outE('MyE').size() > 0;
        select assert($res.size() = 1);
        select assert(edges[0].some = 'value') from $res;
        commit;
        """);
  }

  @Test
  public void testLinkBags() {
    session.executeSQLScript("""
        create property SomeTx.rids LINKBAG;
        begin;
        
        let $v2 = insert into SomeTx;
        let $v1 = insert into SomeTx set rids=$v2;
        
        select assert(rids.size() = 1), assert(rids.contains($v2)) \
         from select rids from SomeTx where rids is not null;
        
        insert into SomeTx;
        
        select assert(rids.size() = 1), assert(rids.contains($v2)) \
         from select rids from SomeTx where rids is not null;
        
        commit;
        """);
  }

  @Test
  public void testProperIndexingOnDoubleInternalBegin() {
    session.executeSQLScript("""
        begin;
        insert into IndexedTx set name =:fieldValue;
        let $entity = insert into SomeTx set name = 'foo';
        select assert(count = 1) from select count(*) as count from $entity;
        commit;
        select assert(count = 1) from select count(*) as count from IndexedTx where name = :fieldValue;
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
