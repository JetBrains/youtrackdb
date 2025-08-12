package com.jetbrains.youtrackdb.internal.client.remote.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrackdb.internal.common.exception.ErrorCode;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 *
 */
public class RemoteErrorMessageTest extends DbTestBase {

  @Mock
  private DatabaseSessionRemote remoteSession;

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void testReadWriteErrorMessage() throws IOException {
    var channel = new MockChannel();
    Map<String, String> messages = new HashMap<>();
    messages.put("one", "two");
    var response =
        new Error37Response(ErrorCode.GENERIC_ERROR, 10, messages, "some".getBytes());
    response.write(null, channel, 0);
    channel.close();
    var readResponse = new Error37Response();
    readResponse.read(remoteSession, channel, null);

    assertEquals(ErrorCode.GENERIC_ERROR, readResponse.getCode());
    assertEquals(10, readResponse.getErrorIdentifier());
    assertNotNull(readResponse.getMessages());
    assertEquals("two", readResponse.getMessages().get("one"));
    assertEquals("some", new String(readResponse.getVerbose()));
  }
}
