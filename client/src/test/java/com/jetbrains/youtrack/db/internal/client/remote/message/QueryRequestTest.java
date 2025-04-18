package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class QueryRequestTest extends DbTestBase {

  @Test
  public void testWithPositionalParams() throws IOException {
    var params = new Object[]{1, "Foo"};
    var request =
        new QueryRequest(
            "sql",
            "select from Foo where a = ?",
            params,
            QueryRequest.QUERY, 123);

    var channel = new MockChannel();
    request.write(null, channel, null);

    channel.close();

    var other = new QueryRequest();
    other.read(session, channel, -1);

    Assert.assertEquals(request.getCommand(), other.getCommand());

    Assert.assertFalse(other.isNamedParams());
    Assert.assertArrayEquals(request.getPositionalParameters(),
        other.getPositionalParameters());

    Assert.assertEquals(request.getOperationType(), other.getOperationType());
    Assert.assertEquals(request.getRecordsPerPage(), other.getRecordsPerPage());
  }

  @Test
  public void testWithNamedParams() throws IOException {
    Map<String, Object> params = new HashMap<>();
    params.put("foo", "bar");
    params.put("baz", 12);
    var request =
        new QueryRequest(
            "sql",
            "select from Foo where a = ?",
            params,
            QueryRequest.QUERY,
            123);

    var channel = new MockChannel();
    request.write(null, channel, null);

    channel.close();

    var other = new QueryRequest();
    other.read(session, channel, -1);

    Assert.assertEquals(request.getCommand(), other.getCommand());
    Assert.assertTrue(other.isNamedParams());
    Assert.assertEquals(request.getNamedParameters(), other.getNamedParameters());
    Assert.assertEquals(request.getOperationType(), other.getOperationType());
    Assert.assertEquals(request.getRecordsPerPage(), other.getRecordsPerPage());
  }

  @Test
  public void testWithNoParams() throws IOException {
    Map<String, Object> params = null;
    var request =
        new QueryRequest(
            "sql",
            "select from Foo where a = ?",
            params,
            QueryRequest.QUERY,
            123);

    var channel = new MockChannel();
    request.write(null, channel, null);

    channel.close();

    var other = new QueryRequest();
    other.read(session, channel, -1);

    Assert.assertEquals(request.getCommand(), other.getCommand());
    Assert.assertTrue(other.isNamedParams());
    Assert.assertTrue(other.getNamedParameters().isEmpty());
    Assert.assertEquals(request.getOperationType(), other.getOperationType());
    Assert.assertEquals(request.getRecordsPerPage(), other.getRecordsPerPage());
  }
}
