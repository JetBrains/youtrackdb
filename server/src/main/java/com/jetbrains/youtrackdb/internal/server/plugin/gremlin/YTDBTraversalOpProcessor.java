package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.util.Optional;
import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.util.function.ThrowingConsumer;

public class YTDBTraversalOpProcessor extends YTDBAbstractOpProcessor {

  public static final String OP_PROCESSOR_NAME = "traversal";

  public YTDBTraversalOpProcessor() {
    super(true);
  }

  @Override
  public String getName() {
    return OP_PROCESSOR_NAME;
  }

  @Override
  public Optional<String> replacedOpProcessorName() {
    return Optional.of(OP_PROCESSOR_NAME);
  }

  @Override
  public void close() throws Exception {
    // do nothing = no resources to release
  }

  @Override
  public ThrowingConsumer<Context> getEvalOp(YTDBGraphTraversalSource traversalSource) {
    throw new UnsupportedOperationException(
        "Evaluation is not supported for " + OP_PROCESSOR_NAME + " processor");
  }
}
