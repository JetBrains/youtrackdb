package com.jetbrains.youtrackdb.auto;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests for SQL DROP CLASS command.
 */
public class SQLDropClassTest extends BaseDBTest {
  @Test
  public void testSimpleDrop() {
    Assert.assertFalse(session.getMetadata().getSchema().existsClass("testSimpleDrop"));
    session.execute("create class testSimpleDrop").close();
    Assert.assertTrue(session.getMetadata().getSchema().existsClass("testSimpleDrop"));
    session.execute("Drop class testSimpleDrop").close();
    Assert.assertFalse(session.getMetadata().getSchema().existsClass("testSimpleDrop"));
  }

  @Test
  public void testIfExists() {
    Assert.assertFalse(session.getMetadata().getSchema().existsClass("testIfExists"));
    session.execute("create class testIfExists if not exists").close();
    Assert.assertTrue(session.getMetadata().getSchema().existsClass("testIfExists"));
    session.execute("drop class testIfExists if exists").close();
    Assert.assertFalse(session.getMetadata().getSchema().existsClass("testIfExists"));
    session.execute("drop class testIfExists if exists").close();
    Assert.assertFalse(session.getMetadata().getSchema().existsClass("testIfExists"));
  }
}
