package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Tests for database session utility methods.
 *
 * @since 9/15/14
 */
public class DBMethodsTest extends BaseDBJUnit5Test {

  // Migrated from: com.jetbrains.youtrackdb.auto.DBMethodsTest#testAddCollection
  @Test
  @Order(1)
  void testAddCollection() {
    session.addCollection("addCollectionTestJUnit5");

    assertTrue(session.existsCollection("addCollectionTestJUnit5"));
    assertTrue(session.existsCollection("addcOllectiontEStJUnit5"));
  }
}
