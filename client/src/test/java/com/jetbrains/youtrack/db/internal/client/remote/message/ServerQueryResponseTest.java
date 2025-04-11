package com.jetbrains.youtrack.db.internal.client.remote.message;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkFactory;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ServerQueryResponseTest extends DbTestBase {

  @Test
  public void test() throws IOException {

    List<Result> resuls = new ArrayList<>();
    for (var i = 0; i < 10; i++) {
      var item = new ResultInternal(null);
      item.setProperty("name", "foo");
      item.setProperty("counter", i);
      resuls.add(item);
    }
    var response =
        new ServerQueryResponse(
            "query", true, resuls, null, false, new HashMap<>(), true);

    var channel = new MockChannel();
    response.write(null,
        channel,
        ChannelBinaryProtocol.CURRENT_PROTOCOL_VERSION,
        RecordSerializerNetworkFactory.current());

    channel.close();

    var newResponse = new ServerQueryResponse();

    newResponse.read(session, channel, null);
    var responseRs = newResponse.getResult().iterator();

    for (var i = 0; i < 10; i++) {
      Assert.assertTrue(responseRs.hasNext());
      var item = responseRs.next();
      Assert.assertEquals("foo", item.getProperty("name"));
      Assert.assertEquals((Integer) i, item.getProperty("counter"));
    }
    Assert.assertFalse(responseRs.hasNext());
    Assert.assertTrue(newResponse.isReloadMetadata());
    Assert.assertTrue(newResponse.isTxChanges());
  }
}
