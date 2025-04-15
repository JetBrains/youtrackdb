package com.jetbrains.youtrack.db.internal.server.http;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests HTTP "class" command.
 */
public class HttpClassTest extends BaseHttpDatabaseTest {

  @Test
  public void testExistentClass() throws Exception {
    var response = get("class/" + getDatabaseName() + "/OUser").getResponse();
    Assert.assertEquals(response.getReasonPhrase(), 200, response.getCode());
  }

  @Test
  public void testNonExistentClass() throws Exception {
    var response =
        get("class/" + getDatabaseName() + "/NonExistentCLass").getResponse();
    Assert.assertEquals(response.getReasonPhrase(), 404, response.getCode());
  }

  @Test
  public void testCreateClass() throws Exception {
    var response = post("class/" + getDatabaseName() + "/NewClass").getResponse();
    Assert.assertEquals(response.getReasonPhrase(), 201, response.getCode());
  }

  @Test
  public void testDropClass() throws Exception {
    var response =
        post("class/" + getDatabaseName() + "/NewClassToDrop").getResponse();
    Assert.assertEquals(response.getReasonPhrase(), 201, response.getCode());

    var response1 =
        delete("class/" + getDatabaseName() + "/NewClassToDrop").getResponse();
    Assert.assertEquals(response1.getReasonPhrase(), 204, response1.getCode());
  }

  @Override
  public String getDatabaseName() {
    return "httpclass";
  }
}
