package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;

import java.util.Collections;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.util.function.ThrowingConsumer;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpProcessorUtil {

  private static final Logger logger = LoggerFactory.getLogger(OpProcessorUtil.class);

  @Nullable
  public static Map<String, Object> generateResultMetadata(Context ctx, RequestMessage msg) {
    var graphManager = (YTDBGraphManager) ctx.getGraphManager();
    var commitedRIDs = graphManager.getCommitedRids(msg);

    if (commitedRIDs != null) {
      return Map.of(
          GremlinServerPlugin.RESULT_METADATA_COMMITTED_RIDS_KEY, commitedRIDs
      );
    }

    return Collections.emptyMap();
  }

  public static ThrowingConsumer<Context> executeAfterQueryEndCallback(Context ctx,
      ThrowingConsumer<Context> consumer) {
    return context -> {
      try {
        consumer.accept(ctx);
      } finally {
        try {
          var graphManager = (YTDBGraphManager) ctx.getGraphManager();
          graphManager.afterQueryEnd(ctx.getRequestMessage());
        } catch (Exception ex) {
          logger.error("Error on execution of afterQueryEnd callback", ex);
        }
      }
    };
  }
}
