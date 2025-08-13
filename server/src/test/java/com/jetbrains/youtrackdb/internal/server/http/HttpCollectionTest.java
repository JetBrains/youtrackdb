package com.jetbrains.youtrackdb.internal.server.http;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests HTTP "collection" command.
 */
public class HttpCollectionTest extends BaseHttpDatabaseTest {

  @Test
  public void testExistentClass() throws Exception {
    Assert.assertEquals(
        get("collection/" + getDatabaseName() + "/OUser").getResponse().getCode(), 200);
  }

  @Test
  public void testNonExistentClass() throws Exception {
    Assert.assertEquals(
        get("collection/" + getDatabaseName() + "/NonExistentCLass").getResponse().getCode(), 404);
  }

  @Override
  public String getDatabaseName() {
    return "httpcollection";
  }
}
