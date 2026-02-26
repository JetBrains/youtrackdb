package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests execution of DROP CLASS SQL statements.
 */
public class DropClassStatementExecutionTest extends DbTestBase {

  @Test
  public void testPlain() {
    var className = "testPlain";
    Schema schema = session.getMetadata().getSchema();
    schema.createClass(className);

    Assert.assertNotNull(schema.getClass(className));

    var result = session.execute("drop class " + className);
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertEquals("drop class", next.getProperty("operation"));
    Assert.assertFalse(result.hasNext());
    result.close();
    Assert.assertNull(schema.getClass(className));
  }

  @Test
  public void testUnsafe() {

    var className = "testUnsafe";
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    schema.createClass(className, v);

    session.begin();
    session.execute("insert into " + className + " set foo = 'bar'");
    session.commit();
    try {
      session.execute("drop class " + className).close();
      Assert.fail();
    } catch (CommandExecutionException ex1) {
    } catch (Exception ex2) {
      Assert.fail();
    }
    var result = session.execute("drop class " + className + " unsafe");
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertEquals("drop class", next.getProperty("operation"));
    Assert.assertFalse(result.hasNext());
    result.close();
    Assert.assertNull(schema.getClass(className));
  }

  /**
   * DROP CLASS on a non-empty edge class (without UNSAFE) must throw
   * CommandExecutionException mentioning edges.
   */
  @Test
  public void testUnsafeEdgeClass() {
    var className = "testUnsafeEdgeClass";
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    var e = schema.getClass("E");
    schema.createClass(className, e);
    schema.createClass(className + "V", v);

    session.begin();
    session.execute("create vertex " + className + "V set name = 'a'");
    session.execute("create vertex " + className + "V set name = 'b'");
    session.execute(
        "create edge " + className + " from (select from " + className + "V where name = 'a')"
            + " to (select from " + className + "V where name = 'b')");
    session.commit();

    try {
      session.execute("drop class " + className).close();
      Assert.fail("Expected CommandExecutionException for non-empty edge class");
    } catch (CommandExecutionException ex) {
      Assert.assertTrue(ex.getMessage().contains("Edges"));
    }

    // UNSAFE should succeed
    var result = session.execute("drop class " + className + " unsafe");
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertEquals("drop class", next.getProperty("operation"));
    result.close();
    Assert.assertNull(schema.getClass(className));
  }

  @Test
  public void testIfExists() {
    var className = "testIfExists";
    Schema schema = session.getMetadata().getSchema();
    schema.createClass(className);

    Assert.assertNotNull(schema.getClass(className));

    var result = session.execute("drop class " + className + " if exists");
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertEquals("drop class", next.getProperty("operation"));
    Assert.assertFalse(result.hasNext());
    result.close();
    Assert.assertNull(schema.getClass(className));

    result = session.execute("drop class " + className + " if exists");
    result.close();
    Assert.assertNull(schema.getClass(className));
  }

  @Test
  public void testParam() {
    var className = "testParam";
    Schema schema = session.getMetadata().getSchema();
    schema.createClass(className);

    Assert.assertNotNull(schema.getClass(className));

    var result = session.execute("drop class ?", className);
    Assert.assertTrue(result.hasNext());
    var next = result.next();
    Assert.assertEquals("drop class", next.getProperty("operation"));
    Assert.assertFalse(result.hasNext());
    result.close();
    Assert.assertNull(schema.getClass(className));
  }
}
