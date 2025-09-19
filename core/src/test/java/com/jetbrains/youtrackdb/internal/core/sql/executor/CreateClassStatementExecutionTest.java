package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class CreateClassStatementExecutionTest extends DbTestBase {

  @Test
  public void testPlain() {
    var className = "testPlain";
    var result = session.execute("create class " + className);
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var clazz = schema.getClass(className);
    Assert.assertNotNull(clazz);
    Assert.assertFalse(clazz.isAbstract());
    result.close();
  }

  @Test
  public void testAbstract() {
    var className = "testAbstract";
    var result = session.execute("create class " + className + " abstract ");
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var clazz = schema.getClass(className);
    Assert.assertNotNull(clazz);
    Assert.assertTrue(clazz.isAbstract());
    result.close();
  }


  @Test
  public void testIfNotExists() {
    var className = "testIfNotExists";
    var result = session.execute("create class " + className + " if not exists");
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var clazz = schema.getClass(className);
    Assert.assertNotNull(clazz);
    result.close();

    result = session.execute("create class " + className + " if not exists");
    clazz = schema.getClass(className);
    Assert.assertNotNull(clazz);
    result.close();
  }
}
