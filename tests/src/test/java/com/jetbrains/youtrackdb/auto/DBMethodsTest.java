package com.jetbrains.youtrackdb.auto;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests for database session utility methods.
 *
 * @since 9/15/14
 */
@Test
public class DBMethodsTest extends BaseDBTest {
  public void testAddCollection() {
    session.addCollection("addCollectionTest");

    Assert.assertTrue(session.existsCollection("addCollectionTest"));
    Assert.assertTrue(session.existsCollection("addcOllectiontESt"));
  }
}
