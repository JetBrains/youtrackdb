package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class DropIndexStatementExecutionTest extends BaseMemoryInternalDatabase {

  @Test
  public void testPlain() {
    var indexName = session.getMetadata()
        .getSlowMutableSchema()
        .createClass("testPlain")
        .createProperty("bar", PropertyType.STRING)
        .createIndex(SchemaManager.INDEX_TYPE.NOTUNIQUE);

    session.getSharedContext().getIndexManager().reload(session);
    Assert.assertNotNull(
        (session.getSharedContext().getIndexManager()).getIndex(indexName));

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
        .getSlowMutableSchema()
        .createClass("testAll")
        .createProperty("baz", PropertyType.STRING)
        .createIndex(SchemaManager.INDEX_TYPE.NOTUNIQUE);

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
}
