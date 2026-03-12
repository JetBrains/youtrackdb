package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

class SQLDropClassTest extends BaseDBJUnit5Test {

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLDropClassTest#testSimpleDrop
  @Test
  @Order(1)
  void testSimpleDrop() {
    assertFalse(session.getMetadata().getSchema().existsClass("testSimpleDrop"));
    session.execute("create class testSimpleDrop").close();
    assertTrue(session.getMetadata().getSchema().existsClass("testSimpleDrop"));
    session.execute("Drop class testSimpleDrop").close();
    assertFalse(session.getMetadata().getSchema().existsClass("testSimpleDrop"));
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLDropClassTest#testIfExists
  @Test
  @Order(2)
  void testIfExists() {
    assertFalse(session.getMetadata().getSchema().existsClass("testIfExists"));
    session.execute("create class testIfExists if not exists").close();
    assertTrue(session.getMetadata().getSchema().existsClass("testIfExists"));
    session.execute("drop class testIfExists if exists").close();
    assertFalse(session.getMetadata().getSchema().existsClass("testIfExists"));
    session.execute("drop class testIfExists if exists").close();
    assertFalse(session.getMetadata().getSchema().existsClass("testIfExists"));
  }
}
