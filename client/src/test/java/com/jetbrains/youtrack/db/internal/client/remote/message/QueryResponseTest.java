package com.jetbrains.youtrack.db.internal.client.remote.message;


import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


public class QueryResponseTest extends DbTestBase {

  @Mock
  private RemoteDatabaseSessionInternal remoteSession;

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void test() throws IOException {

    List<Result> resuls = new ArrayList<>();
    for (var i = 0; i < 10; i++) {
      var item = new ResultInternal(session);
      item.setProperty("name", "foo");
      item.setProperty("counter", i);
      resuls.add(item);
    }
    var response =
        new QueryResponse("query", resuls, false);

    var channel = new MockChannel();
    response.write(null,
        channel,
        ChannelBinaryProtocol.CURRENT_PROTOCOL_VERSION
    );

    channel.close();

    var newResponse = new QueryResponse();

    newResponse.read(remoteSession, channel, null);
    var responseRs = newResponse.getResult().iterator();

    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(responseRs.hasNext());
      var item = responseRs.next();
      Assert.assertEquals("foo", item.getProperty("name"));
      Assert.assertEquals((Integer) i, item.getProperty("counter"));
    }
    Assert.assertFalse(responseRs.hasNext());
  }
}
