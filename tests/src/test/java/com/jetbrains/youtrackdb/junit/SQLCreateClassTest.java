package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

class SQLCreateClassTest extends BaseDBJUnit5Test {

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLCreateClassTest#testSimpleCreate
  @Test
  @Order(1)
  void testSimpleCreate() {
    assertFalse(session.getMetadata().getSchema().existsClass("testSimpleCreate"));
    session.execute("create class testSimpleCreate").close();
    assertTrue(session.getMetadata().getSchema().existsClass("testSimpleCreate"));
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLCreateClassTest#testIfNotExists
  @Test
  @Order(2)
  void testIfNotExists() {
    assertFalse(session.getMetadata().getSchema().existsClass("testIfNotExists"));
    session.execute("create class testIfNotExists if not exists").close();
    assertTrue(session.getMetadata().getSchema().existsClass("testIfNotExists"));
    session.execute("create class testIfNotExists if not exists").close();
    assertTrue(session.getMetadata().getSchema().existsClass("testIfNotExists"));
    try {
      session.execute("create class testIfNotExists").close();
      fail();
    } catch (Exception e) {
      // okay
    }
  }
}
