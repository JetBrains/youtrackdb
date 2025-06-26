package com.jetbrains.youtrack.db.internal.core.sql.parser;

import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class IdentifierTest {

  @Test
  public void testBackTickQuoted() {
    var identifier = new SQLIdentifier("foo`bar");

    Assert.assertEquals(identifier.getStringValue(), "foo`bar");
    Assert.assertEquals(identifier.getValue(), "foo\\`bar");
  }
}
