package com.jetbrains.youtrack.db.internal.server.plugin.gremlin;

import java.util.Collections;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;

public class OpProcessorUtil {

  @Nullable
  public static Map<String, Object> generateResultMetadata(Context ctx, RequestMessage msg) {
    var graphManager = (YTDBGraphManager) ctx.getGraphManager();
    var commitedRIDs = graphManager.commitedRIDs(msg);

    if (commitedRIDs != null) {
      return Map.of(
          GremlinServerPlugin.RESULT_METADATA_COMMITTED_RIDS_KEY, commitedRIDs
      );
    }

    return Collections.emptyMap();
  }
}
