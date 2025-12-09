package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class CreateIndexStatementExecutionTest extends BaseMemoryInternalDatabase {

  @Test
  public void testPlain() {
    var className = "testPlain";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);

    Assert.assertNull(
        session.getSharedContext().getIndexManager().getIndex(className + ".name"));
    var result =
        session.execute(
            "create index " + className + ".name on " + className + " (name) notunique");
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertFalse(result.hasNext());
    Assert.assertNotNull(next);
    result.close();
    var idx = session.getSharedContext().getIndexManager()
        .getIndex(className + ".name");
    Assert.assertNotNull(idx);
    Assert.assertFalse(idx.isUnique());
  }

  @Test
  public void testIfNotExists() {
    var className = "testIfNotExists";
    var clazz = session.getMetadata().getSchema().createClass(className);
    clazz.createProperty("name", PropertyType.STRING);

    Assert.assertNull(
        session.getSharedContext().getIndexManager().getIndex(className + ".name"));
    var result =
        session.execute(
            "create index "
                + className
                + ".name IF NOT EXISTS on "
                + className
                + " (name) notunique");
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertFalse(result.hasNext());
    Assert.assertNotNull(next);
    result.close();
    var idx = session.getSharedContext().getIndexManager()
        .getIndex(className + ".name");
    Assert.assertNotNull(idx);
    Assert.assertFalse(idx.isUnique());

    result =
        session.execute(
            "create index "
                + className
                + ".name IF NOT EXISTS on "
                + className
                + " (name) notunique");
    Assert.assertFalse(result.hasNext());
    result.close();
  }
}
