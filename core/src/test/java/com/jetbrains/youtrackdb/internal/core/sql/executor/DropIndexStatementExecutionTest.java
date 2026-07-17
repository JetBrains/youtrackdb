package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests execution of DROP INDEX SQL statements.
 */
public class DropIndexStatementExecutionTest extends BaseMemoryInternalDatabase {

  @Test
  public void testPlain() {
    var indexName = session.getMetadata()
        .getSchema()
        .createClass("testPlain")
        .createProperty("bar", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    session.getSharedContext().getIndexManager().reload(session);
    Assert.assertNotNull(
        session.getSharedContext().getIndexManager().getIndex(indexName));

    var result = session.execute("drop index " + indexName);
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertEquals("drop index", next.getProperty("operation"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.getSharedContext().getIndexManager().reload(session);
    Assert.assertNull(session.getSharedContext().getIndexManager().getIndex(indexName));
  }

  @Test
  public void testAll() {
    var indexName = session.getMetadata()
        .getSchema()
        .createClass("testAll")
        .createProperty("baz", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    session.getSharedContext().getIndexManager().reload(session);
    Assert.assertNotNull(
        session.getSharedContext().getIndexManager().getIndex(indexName));

    var result = session.execute("drop index *");
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertEquals("drop index", next.getProperty("operation"));
    result.close();
    session.getSharedContext().getIndexManager().reload(session);
    Assert.assertNull(session.getSharedContext().getIndexManager().getIndex(indexName));
    Assert.assertTrue(
        session.getSharedContext().getIndexManager().getIndexes().isEmpty());
  }

  @Test
  public void testWrongName() {

    var indexName = "nonexistingindex";
    session.getSharedContext().getIndexManager().reload(session);
    Assert.assertNull(session.getSharedContext().getIndexManager().getIndex(indexName));

    try {
      session.execute("drop index " + indexName).close();
      Assert.fail();
    } catch (CommandExecutionException ex) {
    } catch (Exception e) {
      Assert.fail();
    }
  }

  @Test
  public void testIfExists() {

    var indexName = "nonexistingindex";
    session.getSharedContext().getIndexManager().reload(session);
    Assert.assertNull(session.getSharedContext().getIndexManager().getIndex(indexName));

    try {
      session.execute("drop index " + indexName + " if exists").close();
    } catch (Exception e) {
      Assert.fail();
    }
  }

  /**
   * OBS-11 straggler pin: the existence check is transaction-aware, so a same-transaction
   * {@code CREATE INDEX} followed by {@code DROP INDEX} of the same name succeeds — the created
   * index lives only in the transaction's overlay, and a committed-only check used to reject the
   * drop with "Index not found".
   */
  @Test
  public void testSameTxCreateThenDropSucceeds() {
    session.getMetadata().getSchema().createClass("testTxCreateDrop")
        .createProperty("bar", PropertyType.STRING);

    session.begin();
    session.execute("create index testTxCreateDrop.bar on testTxCreateDrop (bar) NOTUNIQUE")
        .close();
    session.execute("drop index `testTxCreateDrop.bar`").close();
    session.commit();

    Assert.assertNull("the create-then-drop must net out to nothing",
        session.getSharedContext().getIndexManager().getIndex("testTxCreateDrop.bar"));
  }

  /**
   * OBS-11 straggler pin, the mirror direction: a name already dropped inside this transaction
   * reads as absent, so a second {@code DROP INDEX} of it (without IF EXISTS) fails with the
   * proper "Index not found" instead of silently passing against the still-registered committed
   * entry — and the IF EXISTS variant is a clean no-op.
   */
  @Test
  public void testDropOfTxDroppedNameBehavesAsAbsent() {
    var indexName = session.getMetadata().getSchema().createClass("testTxDropTwice")
        .createProperty("bar", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    session.begin();
    session.execute("drop index `" + indexName + "`").close();
    try {
      session.execute("drop index `" + indexName + "`").close();
      Assert.fail("a tx-dropped name must read as absent to a second plain DROP INDEX");
    } catch (CommandExecutionException expected) {
      // The tx view (committed minus tx-dropped) no longer contains the name. The failed
      // statement aborts the transaction, rolling the first drop back with it.
    }
    Assert.assertNotNull("the aborted transaction must leave the committed index in place",
        session.getSharedContext().getIndexManager().getIndex(indexName));

    // The IF EXISTS variant is a clean no-op on a tx-dropped name and commits fine.
    session.begin();
    session.execute("drop index `" + indexName + "`").close();
    session.execute("drop index `" + indexName + "` if exists").close();
    session.commit();

    Assert.assertNull("the drop must be committed",
        session.getSharedContext().getIndexManager().getIndex(indexName));
  }

  /**
   * OBS-11 straggler pin for {@code DROP INDEX *}: the enumeration is transaction-aware — it
   * includes an index created earlier in the same transaction (cancelling its pending create) and
   * the committed indexes, so nothing survives the commit. A committed-only enumeration used to
   * miss the tx-created index, which was then built at commit despite the DROP *.
   */
  @Test
  public void testAllInTxIncludesTxCreated() {
    session.getMetadata().getSchema().createClass("testTxAll")
        .createProperty("bar", PropertyType.STRING);
    var committedName = session.getMetadata().getSchema().getClass("testTxAll")
        .createProperty("baz", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    session.begin();
    session.execute("create index testTxAll.bar on testTxAll (bar) NOTUNIQUE").close();
    session.execute("drop index *").close();
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    Assert.assertNull("the tx-created index must be cancelled by DROP INDEX *",
        indexManager.getIndex("testTxAll.bar"));
    Assert.assertNull("the committed index must be dropped by DROP INDEX *",
        indexManager.getIndex(committedName));
    Assert.assertTrue("nothing may survive DROP INDEX *",
        indexManager.getIndexes().isEmpty());
  }

  /**
   * OBS-11 straggler pin: an index already dropped in this transaction is excluded from the
   * {@code DROP INDEX *} enumeration, so the composition commits cleanly with a single recorded
   * drop.
   */
  @Test
  public void testAllAfterTxDropCommitsCleanly() {
    var committedName = session.getMetadata().getSchema().createClass("testTxAllAfterDrop")
        .createProperty("bar", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    session.begin();
    session.execute("drop index `" + committedName + "`").close();
    session.execute("drop index *").close();
    session.commit();

    Assert.assertNull("the index must be gone after the composed drops",
        session.getSharedContext().getIndexManager().getIndex(committedName));
  }
}
