package com.jetbrains.youtrack.db.internal.client.remote.message;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 *
 */
public class LiveQueryMessagesTests extends DbTestBase {

  @Test
  public void testRequestWriteRead() throws IOException {
    Map<String, Object> params = new HashMap<>();
    params.put("par", "value");
    var request = new SubscribeLiveQueryRequest("select from Some", params);
    var channel = new MockChannel();
    request.write(null, channel, null);
    channel.close();
    var requestRead = new SubscribeLiveQueryRequest();
    requestRead.read(session, channel, -1);
    assertEquals("select from Some", requestRead.getQuery());
    assertEquals(requestRead.getParams(), params);
  }

  @Test
  public void testSubscribeResponseWriteRead() throws IOException {
    var response = new SubscribeLiveQueryResponse(20);
    var channel = new MockChannel();
    response.write(null, channel, 0);
    channel.close();
    var responseRead = new SubscribeLiveQueryResponse();
    responseRead.read(session, channel, null);
    assertEquals(20, responseRead.getMonitorId());
  }
}
