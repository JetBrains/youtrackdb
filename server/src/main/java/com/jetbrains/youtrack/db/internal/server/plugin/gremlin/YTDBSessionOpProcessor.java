package com.jetbrains.youtrack.db.internal.server.plugin.gremlin;

import java.util.Iterator;
import java.util.Map;
import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.op.session.SessionOpProcessor;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;

public class YTDBSessionOpProcessor extends SessionOpProcessor {

  @Override
  protected Map<String, Object> generateResultMetaData(Context ctx, RequestMessage msg,
      ResponseStatusCode code, Iterator itty, Settings settings) {
    return OpProcessorUtil.generateResultMetadata(ctx, msg);
  }
}
