package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;


import static io.dropwizard.metrics5.MetricRegistry.name;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.tinkerpop.gremlin.groovy.engine.GremlinExecutor;
import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.server.GremlinServer;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.op.OpProcessorException;
import org.apache.tinkerpop.gremlin.server.util.MetricManager;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.function.ThrowingConsumer;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class YTDBSessionOpProcessor extends YTDBAbstractOpProcessor {

  private static final Logger logger = LoggerFactory.getLogger(YTDBSessionOpProcessor.class);
  public static final String OP_PROCESSOR_NAME = "session";

  /// Script engines are evaluated in a per session context where imports/scripts are isolated per
  /// session.
  private final static ConcurrentHashMap<String, YTDBGremlinSession> sessions = new ConcurrentHashMap<>();

  static {
    MetricManager.INSTANCE.getGauge(sessions::size, name(GremlinServer.class, "sessions"));
  }

  /// Configuration setting for how long a session will be available before it times out.
  public static final String CONFIG_SESSION_TIMEOUT = "sessionTimeout";

  /// Configuration setting for how long to wait in milliseconds for each configured graph to close
  /// any open transactions when the session is killed.
  public static final String CONFIG_PER_GRAPH_CLOSE_TIMEOUT = "perGraphCloseTimeout";

  /// Configuration setting that behaves as an override to the global script engine setting of the
  /// same name that is provided to the [GroovyCompilerGremlinPlugin].
  public static final String CONFIG_GLOBAL_FUNCTION_CACHE_ENABLED = "globalFunctionCacheEnabled";

  /// Default timeout for a session is eight hours.
  public static final long DEFAULT_SESSION_TIMEOUT = 28800000;

  /// Default amount of time to wait in milliseconds for each configured graph to close any open
  /// transactions when the session is killed.
  public static final long DEFAULT_PER_GRAPH_CLOSE_TIMEOUT = 10000;

  static final Settings.ProcessorSettings DEFAULT_SETTINGS = new Settings.ProcessorSettings();

  static {
    DEFAULT_SETTINGS.className = YTDBSessionOpProcessor.class.getCanonicalName();
    DEFAULT_SETTINGS.config = new HashMap<>(Map.of(
        CONFIG_SESSION_TIMEOUT, DEFAULT_SESSION_TIMEOUT,
        CONFIG_PER_GRAPH_CLOSE_TIMEOUT, DEFAULT_PER_GRAPH_CLOSE_TIMEOUT,
        CONFIG_MAX_PARAMETERS, DEFAULT_MAX_PARAMETERS,
        CONFIG_GLOBAL_FUNCTION_CACHE_ENABLED, true));
  }

  public YTDBSessionOpProcessor() {
    super(false);
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
  public void init(final Settings settings) {
    this.maxParameters = (int) settings.optionalProcessor(YTDBSessionOpProcessor.class)
        .orElse(DEFAULT_SETTINGS).config.
        getOrDefault(CONFIG_MAX_PARAMETERS, DEFAULT_MAX_PARAMETERS);
  }

  @Override
  public ThrowingConsumer<Context> select(Context context) throws OpProcessorException {
    var requestMessage = context.getRequestMessage();
    if (requestMessage.getOp().equals(Tokens.OPS_CLOSE)) {
      // this must be an in-session request
      if (requestMessage.optionalArgs(Tokens.ARGS_SESSION).isEmpty()) {
        final var msg = String.format("A message with an [%s] op code requires a [%s] argument",
            Tokens.OPS_CLOSE, Tokens.ARGS_SESSION);
        throw new OpProcessorException(msg,
            ResponseMessage.build(requestMessage).
                code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(msg)
                .create());
      }

      return rhc -> {
        // send back a confirmation of the close
        rhc.writeAndFlush(ResponseMessage.build(requestMessage)
            .code(ResponseStatusCode.NO_CONTENT)
            .create());
      };
    }

    return super.select(context);
  }

  @Override
  public ThrowingConsumer<Context> getEvalOp(YTDBGraphTraversalSource traversalSource) {
    return ctx -> this.evalOp(ctx, traversalSource);
  }

  @Override
  protected Optional<ThrowingConsumer<Context>> validateEvalMessage(final RequestMessage message)
      throws OpProcessorException {
    super.validateEvalMessage(message);

    if (message.optionalArgs(Tokens.ARGS_SESSION).isEmpty()) {
      final var msg = String.format("A message with an [%s] op code requires a [%s] argument",
          Tokens.OPS_EVAL, Tokens.ARGS_SESSION);
      throw new OpProcessorException(msg, ResponseMessage.build(message)
          .code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(msg)
          .create());
    }

    return Optional.empty();
  }

  @Override
  public void close() {
    sessions.values().forEach(session -> session.manualKill(false));
  }

  private void evalOp(final Context context, YTDBGraphTraversalSource traversalSource)
      throws OpProcessorException {
    final var msg = context.getRequestMessage();
    final var session = getSession(context, msg, traversalSource);

    // check if the session is still accepting requests - if not block further requests
    if (!session.acceptingRequests()) {
      final var sessionClosedMessage = String.format(
          "Session %s is no longer accepting requests as it has been closed",
          session.getSessionId());
      final var response = ResponseMessage.build(msg)
          .code(ResponseStatusCode.SERVER_ERROR)
          .statusMessage(sessionClosedMessage).create();
      throw new OpProcessorException(sessionClosedMessage, response);
    }

    // check if the session is bound to this channel, thus one client per session
    if (!session.isBoundTo(context.getChannelHandlerContext().channel())) {
      final var sessionClosedMessage = String.format(
          "Session %s is not bound to the connecting client",
          session.getSessionId());
      final var response = ResponseMessage.build(msg)
          .code(ResponseStatusCode.SERVER_ERROR)
          .statusMessage(sessionClosedMessage).create();
      throw new OpProcessorException(sessionClosedMessage, response);
    }

    evalOpInternal(context, session::getGremlinExecutor, traversalSource);
  }

  /**
   * Examines the {@link RequestMessage} and extracts the session token. The session is then either
   * found or a new one is created.
   */
  private static YTDBGremlinSession getSession(final Context context, final RequestMessage msg,
      YTDBGraphTraversalSource traversalSource) {
    final var sessionId = (String) msg.getArgs().get(Tokens.ARGS_SESSION);
    logger.debug("In-session request {} for eval for session {} in thread {}",
        msg.getRequestId(), sessionId, Thread.currentThread().getName());

    var session = sessions.computeIfAbsent(sessionId,
        k -> new YTDBGremlinSession(k, context, traversalSource, sessions)
    );

    session.touch();
    return session;
  }

  @Override
  protected GremlinExecutor getGremlinExecutor(Context context,
      YTDBGraphTraversalSource traversalSource) {
    var session = getSession(context, context.getRequestMessage(), traversalSource);
    return session.getGremlinExecutor();
  }

  @Override
  protected YTDBGraphTraversalSource getTraversalSource(Context context)
      throws OpProcessorException {

    var msg = context.getRequestMessage();
    final var sessionId = (String) msg.getArgs().get(Tokens.ARGS_SESSION);
    var session = sessions.get(sessionId);
    if (session == null) {
      throw new OpProcessorException(String.format("Session %s not found", sessionId),
          ResponseMessage.build(msg).code(ResponseStatusCode.SERVER_ERROR).create());
    }

    return session.getGraphTraversalSource();
  }

  @Override
  protected YTDBGraphTraversalSource initTraversalSourceIfAbsent(Context context)
      throws OpProcessorException {
    try {
      var msg = context.getRequestMessage();
      final var sessionId = (String) msg.getArgs().get(Tokens.ARGS_SESSION);

      var session = sessions.computeIfAbsent(sessionId,
          k -> {
            try {
              return new YTDBGremlinSession(k, context,
                  doInitTraversalSource(context), sessions);
            } catch (OpProcessorException e) {
              throw new RuntimeException("Error initializing of server session ", e);
            }
          }
      );

      session.touch();
      return session.getGraphTraversalSource();
    } catch (Exception e) {
      if (e.getCause() instanceof OpProcessorException processorException) {
        throw processorException;
      }

      throw e;
    }
  }
}
