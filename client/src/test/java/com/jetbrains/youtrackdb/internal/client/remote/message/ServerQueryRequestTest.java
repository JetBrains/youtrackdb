package com.jetbrains.youtrackdb.internal.client.remote.message;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class ServerQueryRequestTest extends DbTestBase {

  @Mock
  private RemoteDatabaseSessionInternal remoteSession;

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    MockitoAnnotations.openMocks(this);
    Mockito.when(remoteSession.assertIfNotActive()).thenReturn(true);
  }

  @Test
  public void testWithPositionalParams() throws IOException {
    var params = new Object[]{1, "Foo"};
    var request =
        new ServerQueryRequest(
            "sql",
            "some random statement",
            params,
            ServerQueryRequest.QUERY, 123);

    var channel = new MockChannel();
    request.write(remoteSession, channel, null);

    channel.close();

    var other = new ServerQueryRequest();
    other.read(session, channel, -1);

    Assert.assertEquals(request.getCommand(), other.getCommand());

    Assert.assertFalse(other.isNamedParams());
    Assert.assertArrayEquals(request.getPositionalParameters(), other.getPositionalParameters());

    Assert.assertEquals(request.getOperationType(), other.getOperationType());
    Assert.assertEquals(request.getRecordsPerPage(), other.getRecordsPerPage());
  }

  @Test
  public void testWithNamedParams() throws IOException {
    Map<String, Object> params = new HashMap<>();
    params.put("foo", "bar");
    params.put("baz", 12);
    var request =
        new ServerQueryRequest(
            "sql",
            "some random statement",
            params,
            ServerQueryRequest.QUERY, 123);

    var channel = new MockChannel();
    request.write(remoteSession, channel, null);

    channel.close();

    var other = new ServerQueryRequest();
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
        new ServerQueryRequest(
            "sql",
            "some random statement",
            params,
            ServerQueryRequest.QUERY, 123);

    var channel = new MockChannel();
    request.write(remoteSession, channel, null);

    channel.close();

    var other = new ServerQueryRequest();
    other.read(session, channel, -1);

    Assert.assertEquals(request.getCommand(), other.getCommand());
    Assert.assertTrue(other.isNamedParams());
    Assert.assertEquals(request.getNamedParameters(), other.getNamedParameters());
    Assert.assertEquals(request.getOperationType(), other.getOperationType());
    Assert.assertEquals(request.getRecordsPerPage(), other.getRecordsPerPage());
  }
}
