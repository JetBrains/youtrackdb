package com.jetbrains.youtrack.db.internal.server.plugin.gremlin;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.op.OpProcessorException;
import org.apache.tinkerpop.gremlin.server.op.traversal.TraversalOpProcessor;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.util.function.ThrowingConsumer;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;

public class YTDBTraversalOpProcessor extends TraversalOpProcessor {

  @Override
  protected void beforeResponseGeneration(Context context, RequestMessage requestMessage,
      Iterator itty, Graph graph) {
    if (graph.features().graph().supportsTransactions() && graph.tx().isOpen()) {
      graph.tx().commit();
    }
  }

  @Override
  protected Map<String, Object> generateResultMetaData(Context ctx, RequestMessage msg,
      ResponseStatusCode code, Iterator itty, Settings settings) {
    return OpProcessorUtil.generateResultMetadata(ctx, msg);
  }

  @Override
  public Optional<String> replacedOpProcessorName() {
    return Optional.of(getName());
  }

  @Override
  public ThrowingConsumer<Context> select(Context context) throws OpProcessorException {
    return OpProcessorUtil.executeAfterQueryEndCallback(context, super.select(context));
  }
}
