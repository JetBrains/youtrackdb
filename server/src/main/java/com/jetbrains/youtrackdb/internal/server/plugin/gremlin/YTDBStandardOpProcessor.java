package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.util.Optional;
import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.op.OpProcessorException;
import org.apache.tinkerpop.gremlin.util.function.ThrowingConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class YTDBStandardOpProcessor extends YTDBAbstractOpProcessor {

  private static final Logger logger = LoggerFactory.getLogger(YTDBStandardOpProcessor.class);
  public static final String OP_PROCESSOR_NAME = "";

  public YTDBStandardOpProcessor() {
    super(true);
  }

  @Override
  public void init(final Settings settings) {
    this.maxParameters = (int) settings.optionalProcessor(YTDBStandardOpProcessor.class)
        .orElse(DEFAULT_SETTINGS).config.
        getOrDefault(CONFIG_MAX_PARAMETERS, DEFAULT_MAX_PARAMETERS);
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
  public ThrowingConsumer<Context> getEvalOp(YTDBGraphTraversalSource traversalSource) {
    return this::evalOp;
  }

  @Override
  public void close() {
    // do nothing = no resources to release
  }

  private void evalOp(final Context context) throws OpProcessorException {
    if (logger.isDebugEnabled()) {
      final var msg = context.getRequestMessage();
      logger.debug("Sessionless request {} for eval in thread {}", msg.getRequestId(),
          Thread.currentThread().getName());
    }

    var traversalSource = getTraversalSource(context);
    evalOpInternal(context, context::getGremlinExecutor, traversalSource);
  }
}
