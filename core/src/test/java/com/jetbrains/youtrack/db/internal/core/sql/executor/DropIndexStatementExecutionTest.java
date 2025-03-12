package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class DropIndexStatementExecutionTest extends BaseMemoryInternalDatabase {

  @Test
  public void testPlain() {
    var indexName = session.getMetadata()
        .getSchema()
        .createClass("testPlain")
        .createProperty("bar", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    session.getMetadata().getIndexManagerInternal().reload(session);
    Assert.assertNotNull(
        (session.getMetadata().getIndexManagerInternal()).getIndex(session, indexName));

    var result = session.command("drop index " + indexName);
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertEquals("drop index", next.getProperty("operation"));
    Assert.assertFalse(result.hasNext());
    result.close();

    session.getMetadata().getIndexManagerInternal().reload(session);
    Assert.assertNull(session.getMetadata().getIndexManagerInternal().getIndex(session, indexName));
  }

  @Test
  public void testAll() {
    var indexName = session.getMetadata()
        .getSchema()
        .createClass("testAll")
        .createProperty("baz", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    session.getMetadata().getIndexManagerInternal().reload(session);
    Assert.assertNotNull(
        session.getMetadata().getIndexManagerInternal().getIndex(session, indexName));

    var result = session.command("drop index *");
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertEquals("drop index", next.getProperty("operation"));
    result.close();
    session.getMetadata().getIndexManagerInternal().reload(session);
    Assert.assertNull(session.getMetadata().getIndexManagerInternal().getIndex(session, indexName));
    Assert.assertTrue(
        session.getMetadata().getIndexManagerInternal().getIndexes(session).isEmpty());
  }

  @Test
  public void testWrongName() {

    var indexName = "nonexistingindex";
    session.getMetadata().getIndexManagerInternal().reload(session);
    Assert.assertNull(session.getMetadata().getIndexManagerInternal().getIndex(session, indexName));

    try {
      session.command("drop index " + indexName).close();
      Assert.fail();
    } catch (CommandExecutionException ex) {
    } catch (Exception e) {
      Assert.fail();
    }
  }

  @Test
  public void testIfExists() {

    var indexName = "nonexistingindex";
    session.getMetadata().getIndexManagerInternal().reload(session);
    Assert.assertNull(session.getMetadata().getIndexManagerInternal().getIndex(session, indexName));

    try {
      session.command("drop index " + indexName + " if exists").close();
    } catch (Exception e) {
      Assert.fail();
    }
  }
}
