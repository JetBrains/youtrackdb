package com.jetbrains.youtrack.db.internal.client.remote.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import java.io.IOException;
import java.util.HashMap;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class RemotePushMessagesTest extends DbTestBase {

  @Mock
  private DatabaseSessionRemote sessionRemote;

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void testSubscribeRequest() throws IOException {
    var channel = new MockChannel();

    var request =
        new SubscribeRequest(new SubscribeLiveQueryRequest("10", new HashMap<>()));
    request.write(null, channel, null);
    channel.close();

    var requestRead = new SubscribeRequest();
    requestRead.read(session, channel, 1);

    assertEquals(request.getPushMessage(), requestRead.getPushMessage());
    assertTrue(requestRead.getPushRequest() instanceof SubscribeLiveQueryRequest);
  }

  @Test
  public void testSubscribeResponse() throws IOException {
    var channel = new MockChannel();

    var response = new SubscribeResponse(new SubscribeLiveQueryResponse(10));
    response.write(null, channel, 1);
    channel.close();

    var responseRead = new SubscribeResponse(new SubscribeLiveQueryResponse());
    responseRead.read(sessionRemote, channel, null);

    assertTrue(responseRead.getResponse() instanceof SubscribeLiveQueryResponse);
    assertEquals(10, ((SubscribeLiveQueryResponse) responseRead.getResponse()).getMonitorId());
  }

  @Test
  public void testUnsubscribeRequest() throws IOException {
    var channel = new MockChannel();
    var request = new UnsubscribeRequest(new UnsubscribeLiveQueryRequest(10));
    request.write(null, channel, null);
    channel.close();
    var readRequest = new UnsubscribeRequest();
    readRequest.read(session, channel, 0);
    assertEquals(
        10, ((UnsubscribeLiveQueryRequest) readRequest.getUnsubscribeRequest()).getMonitorId());
  }
}
