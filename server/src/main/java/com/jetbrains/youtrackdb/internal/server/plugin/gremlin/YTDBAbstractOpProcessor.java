package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;

import static com.codahale.metrics.MetricRegistry.name;
import static org.apache.tinkerpop.gremlin.process.traversal.GraphOp.TX_COMMIT;
import static org.apache.tinkerpop.gremlin.process.traversal.GraphOp.TX_ROLLBACK;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.jetbrains.youtrackdb.api.SessionListener;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.transaction.Transaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.script.Bindings;
import javax.script.SimpleBindings;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.tinkerpop.gremlin.groovy.engine.GremlinExecutor;
import org.apache.tinkerpop.gremlin.groovy.jsr223.TimedInterruptTimeoutException;
import org.apache.tinkerpop.gremlin.jsr223.JavaTranslator;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.Failure;
import org.apache.tinkerpop.gremlin.process.traversal.Operator;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.Scope;
import org.apache.tinkerpop.gremlin.process.traversal.util.BytecodeHelper;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalInterruptedException;
import org.apache.tinkerpop.gremlin.server.Context;
import org.apache.tinkerpop.gremlin.server.GremlinServer;
import org.apache.tinkerpop.gremlin.server.OpProcessor;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.auth.AuthenticatedUser;
import org.apache.tinkerpop.gremlin.server.handler.Frame;
import org.apache.tinkerpop.gremlin.server.handler.StateKey;
import org.apache.tinkerpop.gremlin.server.op.OpProcessorException;
import org.apache.tinkerpop.gremlin.server.op.standard.StandardOpProcessor;
import org.apache.tinkerpop.gremlin.server.util.MetricManager;
import org.apache.tinkerpop.gremlin.server.util.TraverserIterator;
import org.apache.tinkerpop.gremlin.structure.Column;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.structure.util.TemporaryException;
import org.apache.tinkerpop.gremlin.util.ExceptionHelper;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.function.ThrowingConsumer;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.apache.tinkerpop.gremlin.util.ser.MessageTextSerializer;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class YTDBAbstractOpProcessor implements OpProcessor {

  private static final Logger logger = LoggerFactory.getLogger(YTDBAbstractOpProcessor.class);
  private static final AttributeKey<YTDBGraphTraversalSource> currentTraversalSource = AttributeKey.valueOf(
      "ytdb-traversal-source");

  /// Length of time to pause writes in milliseconds when the high watermark is exceeded.
  public static final long WRITE_PAUSE_TIME_MS = 10;

  /// Tracks the rate of pause to writes when the high watermark is exceeded.
  public static final Meter writePausesMeter = MetricManager.INSTANCE.getMeter(
      name(GremlinServer.class, "channels", "write-pauses"));

  /// When set to `true`, transactions are always managed otherwise they can be overridden by the
  /// request.
  protected final boolean manageTransactions;

  private static final Logger auditLogger = LoggerFactory.getLogger(
      GremlinServer.AUDIT_LOGGER_NAME);
  public static final Timer evalOpTimer =
      MetricManager.INSTANCE.getTimer(name(GremlinServer.class, "op", "eval"));

  /// The maximum number of parameters that can be passed on a script evaluation request.
  public static final String CONFIG_MAX_PARAMETERS = "maxParameters";

  /// Default number of parameters allowed on a script evaluation request.
  public static final int DEFAULT_MAX_PARAMETERS = 16;

  public static final Timer traversalOpTimer =
      MetricManager.INSTANCE.getTimer(name(GremlinServer.class, "op", "traversal"));


  /// This may or may not be the full set of invalid binding keys.  It is dependent on the static
  /// imports made to Gremlin Server.  This should get rid of the worst offenders though and provide
  /// a good message back to the calling client.
  ///
  /// Use of `toUpperCase()` on the accessor values of [T] solves an issue where the `ScriptEngine`
  /// ignores private scope on [T] and imports static fields.
  protected static final Set<String> INVALID_BINDINGS_KEYS = new HashSet<>();

  static {
    INVALID_BINDINGS_KEYS.addAll(Arrays.asList(
        T.id.name(), T.key.name(),
        T.label.name(), T.value.name(),
        T.id.getAccessor(), T.key.getAccessor(),
        T.label.getAccessor(), T.value.getAccessor(),
        T.id.getAccessor().toUpperCase(), T.key.getAccessor().toUpperCase(),
        T.label.getAccessor().toUpperCase(), T.value.getAccessor().toUpperCase()));

    for (var enumItem : Column.values()) {
      INVALID_BINDINGS_KEYS.add(enumItem.name());
    }

    for (var enumItem : Order.values()) {
      INVALID_BINDINGS_KEYS.add(enumItem.name());
    }

    for (var enumItem : Operator.values()) {
      INVALID_BINDINGS_KEYS.add(enumItem.name());
    }

    for (var enumItem : Scope.values()) {
      INVALID_BINDINGS_KEYS.add(enumItem.name());
    }

    for (var enumItem : Pop.values()) {
      INVALID_BINDINGS_KEYS.add(enumItem.name());
    }
  }

  static final Settings.ProcessorSettings DEFAULT_SETTINGS = new Settings.ProcessorSettings();

  static {
    DEFAULT_SETTINGS.className = StandardOpProcessor.class.getCanonicalName();
    DEFAULT_SETTINGS.config = new HashMap<>() {{
      put(CONFIG_MAX_PARAMETERS, DEFAULT_MAX_PARAMETERS);
    }};
  }

  protected int maxParameters = DEFAULT_MAX_PARAMETERS;


  protected YTDBAbstractOpProcessor(final boolean manageTransactions) {
    this.manageTransactions = manageTransactions;
  }

  @Override
  public ThrowingConsumer<Context> select(final Context context) throws OpProcessorException {
    final var message = context.getRequestMessage();
    logger.debug("Selecting processor for RequestMessage {}", message);

    return switch (message.getOp()) {
      case Tokens.OPS_EVAL -> {
        yield (ctx) -> {
          try {
            var traversalSource = initTraversalSourceIfAbsent(context);
            validateEvalMessage(message).orElse(getEvalOp(traversalSource)).accept(ctx);
          } finally {
            ctx.getChannelHandlerContext().channel().attr(currentTraversalSource).remove();
          }
        };
      }
      case Tokens.OPS_BYTECODE -> {
        yield (ctx) -> {
          try {
            initTraversalSourceIfAbsent(ctx);
            iterateBytecodeTraversal(ctx);
          } finally {
            ctx.getChannelHandlerContext().channel().attr(currentTraversalSource).remove();
          }
        };
      }
      case Tokens.OPS_INVALID -> {
        final var msgInvalid = String.format(
            "Message could not be parsed.  Check the format of the request. [%s]", message);
        throw new OpProcessorException(msgInvalid, ResponseMessage.build(message).code(
            ResponseStatusCode.REQUEST_ERROR_MALFORMED_REQUEST).statusMessage(msgInvalid).create());
      }
      default -> {
        final var msgDefault = String.format("Message with op code [%s] is not recognized.",
            message.getOp());
        throw new OpProcessorException(msgDefault, ResponseMessage.build(message)
            .code(ResponseStatusCode.REQUEST_ERROR_MALFORMED_REQUEST).statusMessage(msgDefault)
            .create());
      }
    };
  }

  /// Provides an operation for evaluating a Gremlin script.
  public abstract ThrowingConsumer<Context> getEvalOp(YTDBGraphTraversalSource traversalSource);


  /// Provides a generic way of iterating a result set back to the client.
  ///
  /// @param context The Gremlin Server [Context] object containing settings, request message, etc.
  /// @param itty    The result to iterator
  protected void handleIterator(final Context context, final Iterator<?> itty,
      YTDBGraphTraversalSource traversalSource)
      throws InterruptedException {
    final var nettyContext = context.getChannelHandlerContext();
    final var msg = context.getRequestMessage();
    var args = msg.getArgs();
    final var settings = context.getSettings();
    final var serializer = nettyContext.channel().attr(StateKey.SERIALIZER).get();
    final boolean useBinary = nettyContext.channel().attr(StateKey.USE_BINARY).get();

    // used to limit warnings for when netty fills the buffer and hits the high watermark - prevents
    // over-logging of the same message.
    long lastWarningTime = 0;
    var warnCounter = 0;

    // sessionless requests are always transaction managed, but in-session requests are configurable.
    final var manageTransactions =
        this.manageTransactions || (Boolean) args.getOrDefault(Tokens.ARGS_MANAGE_TRANSACTION,
            false);
    if (manageTransactions) {
      var tx = traversalSource.tx();
      if (tx.isOpen()) {
        tx.rollback();
      }
    }

    // we have an empty iterator - happens on stuff like: g.V().iterate()
    if (!itty.hasNext()) {
      final var attributes = generateStatusAttributes(nettyContext,
          itty);

      if (manageTransactions) {
        var tx = traversalSource.tx();
        if (tx.isOpen()) {
          tx.commit();
        }
      }

      // as there is nothing left to iterate if we are transaction managed then we should execute a
      // commit here before we send back a NO_CONTENT which implies success
      context.writeAndFlush(ResponseMessage.build(msg)
          .code(ResponseStatusCode.NO_CONTENT)
          .statusAttributes(attributes)
          .create());

      return;
    }

    // the batch size can be overridden by the request
    final int resultIterationBatchSize = (Integer) msg.optionalArgs(Tokens.ARGS_BATCH_SIZE)
        .orElse(settings.resultIterationBatchSize);
    List<Object> aggregate = new ArrayList<>(resultIterationBatchSize);

    // use an external control to manage the loop as opposed to just checking hasNext() in the while.  this
    // prevent situations where auto transactions create a new transaction after calls to commit() withing
    // the loop on calls to hasNext().
    var hasMore = itty.hasNext();

    while (hasMore) {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }

      // have to check the aggregate size because it is possible that the channel is not writeable (below)
      // so iterating next() if the message is not written and flushed would bump the aggregate size beyond
      // the expected resultIterationBatchSize.  Total serialization time for the response remains in
      // effect so if the client is "slow" it may simply timeout.
      //
      // there is a need to check hasNext() on the iterator because if the channel is not writeable the
      // previous pass through the while loop will have next()'d the iterator and if it is "done" then a
      // NoSuchElementException will raise its head. also need a check to ensure that this iteration doesn't
      // require a forced flush which can be forced by sub-classes.
      //
      // this could be placed inside the isWriteable() portion of the if-then below but it seems better to
      // allow iteration to continue into a batch if that is possible rather than just doing nothing at all
      // while waiting for the client to catch up
      if (aggregate.size() < resultIterationBatchSize && itty.hasNext()) {
        aggregate.add(itty.next());
      }

      // Don't keep executor busy if client has already given up; there is no way to catch up if the channel is
      // not active, and hence we should break the loop.
      if (!nettyContext.channel().isActive()) {
        var tx = traversalSource.tx();
        if (tx.isOpen()) {
          tx.rollback();
        }
        break;
      }

      // send back a page of results if batch size is met or if it's the end of the results being iterated.
      // also check writeability of the channel to prevent OOME for slow clients.
      //
      // clients might decide to close the Netty channel to the server with a CloseWebsocketFrame after errors
      // like CorruptedFrameException. On the server, although the channel gets closed, there might be some
      // executor threads waiting for watermark to clear which will not clear in these cases since client has
      // already given up on these requests. This leads to these executors waiting for the client to consume
      // results till the timeout. checking for isActive() should help prevent that.
      if (nettyContext.channel().isActive() && nettyContext.channel().isWritable()) {
        if (aggregate.size() == resultIterationBatchSize || !itty.hasNext()) {
          final var code =
              itty.hasNext() ? ResponseStatusCode.PARTIAL_CONTENT : ResponseStatusCode.SUCCESS;
          // serialize here because in sessionless requests the serialization must occur in the same
          // thread as the eval.  as eval occurs in the GremlinExecutor there's no way to get back to the
          // thread that processed the eval of the script so, we have to push serialization down into that

          Map<String, Object> metadata = new HashMap<>();
          if (manageTransactions && code == ResponseStatusCode.SUCCESS) {
            commitAndUpdateMetadata(traversalSource, metadata);
          }

          var txWasClosed = !traversalSource.tx().isOpen();
          final var statusAttrb = generateStatusAttributes(nettyContext, itty);
          Frame frame;
          try {
            frame = makeFrame(context, msg, serializer, useBinary, aggregate, code,
                metadata, statusAttrb);
          } catch (Exception ex) {
            // exception is handled in makeFrame() - serialization error gets written back to driver
            // at that point
            break;
          } finally {
            if (txWasClosed) {
              var tx = traversalSource.tx();
              //read-only transactions that can be triggered during response serialization
              if (tx.isOpen()) {
                tx.rollback();
              }
            }
          }

          // track whether there is anything left in the iterator because it needs to be accessed after
          // the transaction could be closed - in that case a call to hasNext() could open a new transaction
          // unintentionally
          hasMore = itty.hasNext();
          try {
            // only need to reset the aggregation list if there's more stuff to write
            if (hasMore) {
              aggregate = new ArrayList<>(resultIterationBatchSize);
            }
          } catch (Exception ex) {
            // a frame may use a Bytebuf which is a countable release - if it does not get written
            // downstream it needs to be released here
            frame.tryRelease();
            throw ex;
          }

          // the flush is called after the commit has potentially occurred.  in this way, if a commit was
          // required then it will be 100% complete before the client receives it. the "frame" at this point
          // should have completely detached objects from the transaction (i.e. serialization has occurred)
          // so a new one should not be opened on the flush down the netty pipeline
          context.writeAndFlush(code, frame);
        }
      } else {
        final var currentTime = System.currentTimeMillis();

        // exponential delay between warnings. don't keep triggering this warning over and over again for the
        // same request. totalPendingWriteBytes is volatile so it is possible that by the time this warning
        // hits the log the low watermark may have been hit
        var interval = (long) Math.pow(2, warnCounter) * 1000;
        if (currentTime - lastWarningTime >= interval) {
          final var ch = context.getChannelHandlerContext().channel();
          logger.warn(
              "Warning {}: Outbound buffer size={}, pausing response writing as writeBufferHighWaterMark exceeded on request {} for channel {} - writing will continue once client has caught up",
              warnCounter,
              ch.unsafe().outboundBuffer().totalPendingWriteBytes(),
              msg.getRequestId(),
              ch.id());

          lastWarningTime = currentTime;
          warnCounter++;
        }

        // since the client is lagging we can hold here for a period of time for the client to catch up.
        // this isn't blocking the IO thread - just a worker.
        TimeUnit.MILLISECONDS.sleep(WRITE_PAUSE_TIME_MS);
        writePausesMeter.mark();
      }
    }
  }

  private static void commitAndUpdateMetadata(YTDBGraphTraversalSource traversalSource,
      Map<String, Object> metadata) {
    var tx = (YTDBTransaction) traversalSource.tx();
    if (tx.isOpen()) {
      var session = tx.getDatabaseSession();

      session.registerListener(new SessionListener() {
        @Override
        public void onAfterTxCommit(Transaction transaction,
            @Nullable Map<RID, RID> ridMapping) {
          metadata.put(GremlinServerPlugin.RESULT_METADATA_COMMITTED_RIDS_KEY, ridMapping);
        }
      });
      traversalSource.tx().commit();
    }
  }


  /**
   * Generates response status meta-data to put on a {@link ResponseMessage}.
   *
   * @param itty a reference to the current {@link Iterator} of results - it is not meant to be
   *             forwarded in this method
   */
  protected static Map<String, Object> generateStatusAttributes(final ChannelHandlerContext ctx,
      final Iterator<?> itty) {
    // only return server metadata on the last message
    if (itty.hasNext()) {
      return Collections.emptyMap();
    }

    final Map<String, Object> metaData = new HashMap<>();
    metaData.put(Tokens.ARGS_HOST, ctx.channel().remoteAddress().toString());

    return metaData;
  }


  protected static Frame makeFrame(final Context ctx, final RequestMessage msg,
      final MessageSerializer<?> serializer, final boolean useBinary, final List<Object> aggregate,
      final ResponseStatusCode code, final Map<String, Object> responseMetaData,
      final Map<String, Object> statusAttributes) throws Exception {
    try {
      final var nettyContext = ctx.getChannelHandlerContext();

      ctx.handleDetachment(aggregate);

      if (useBinary) {
        return new Frame(serializer.serializeResponseAsBinary(ResponseMessage.build(msg)
            .code(code)
            .statusAttributes(statusAttributes)
            .responseMetaData(responseMetaData)
            .result(aggregate).create(), nettyContext.alloc()));
      } else {
        // the expectation is that the GremlinTextRequestDecoder will have placed a MessageTextSerializer
        // instance on the channel.
        final var textSerializer = (MessageTextSerializer<?>) serializer;
        return new Frame(textSerializer.serializeResponseAsString(ResponseMessage.build(msg)
            .code(code)
            .statusAttributes(statusAttributes)
            .responseMetaData(responseMetaData)
            .result(aggregate).create(), nettyContext.alloc()));
      }
    } catch (Exception ex) {
      logger.warn("The result [{}] in the request {} could not be serialized and returned.",
          aggregate, msg.getRequestId(), ex);
      final var errorMessage = String.format("Error during serialization: %s",
          ExceptionHelper.getMessageFromExceptionOrCause(ex));
      final var error = ResponseMessage.build(msg.getRequestId())
          .statusMessage(errorMessage)
          .statusAttributeException(ex)
          .code(ResponseStatusCode.SERVER_ERROR_SERIALIZATION).create();
      ctx.writeAndFlush(error);
      throw ex;
    }
  }


  protected static Map<String, String> validatedAliases(final RequestMessage message)
      throws OpProcessorException {
    final Optional<Map<String, String>> aliases = message.optionalArgs(Tokens.ARGS_ALIASES);
    if (aliases.isEmpty()) {
      final var msg = String.format("A message requires a [%s] argument.", Tokens.ARGS_ALIASES);
      throw new OpProcessorException(msg, ResponseMessage.build(message)
          .code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(msg)
          .create());
    }

    if (aliases.get().size() != 1 || !aliases.get()
        .containsKey(Tokens.VAL_TRAVERSAL_SOURCE_ALIAS)) {
      final var msg = String.format(
          "A message requires the [%s] argument to be a Map containing one alias assignment named '%s'.",
          Tokens.ARGS_ALIASES, Tokens.VAL_TRAVERSAL_SOURCE_ALIAS);
      throw new OpProcessorException(msg, ResponseMessage.build(message)
          .code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(msg)
          .create());
    }

    return aliases.get();
  }

  protected static YTDBGraphTraversalSource doInitTraversalSource(final Context context)
      throws OpProcessorException {
    var msg = context.getRequestMessage();
    var aliases = validatedAliases(msg);
    var settings = (YTDBSettings) context.getSettings();
    var server = settings.server;
    var youTrackDB = server.getYouTrackDB();
    var dbName = aliases.values().iterator().next();

    if (!youTrackDB.exists(dbName)) {
      var errorMsg = "Database " + dbName + " does not exist";
      throw new OpProcessorException(errorMsg, ResponseMessage.build(msg)
          .code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(errorMsg)
          .create());
    }

    var user = context.getChannelHandlerContext().channel().attr(StateKey.AUTHENTICATED_USER).get();
    if (user == null || user.isAnonymous()) {
      throw new OpProcessorException("User is not authenticated", ResponseMessage.build(msg)
          .code(ResponseStatusCode.UNAUTHORIZED)
          .statusMessage("User is not authenticated").create());
    }

    return youTrackDB.openTraversalNoAuthenticate(dbName, user.getName());
  }


  protected Optional<ThrowingConsumer<Context>> validateEvalMessage(final RequestMessage message)
      throws OpProcessorException {
    if (message.optionalArgs(Tokens.ARGS_GREMLIN).isEmpty()) {
      final var msg = String.format("A message with an [%s] op code requires a [%s] argument.",
          Tokens.OPS_EVAL, Tokens.ARGS_GREMLIN);
      throw new OpProcessorException(msg, ResponseMessage.build(message)
          .code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(msg)
          .create());
    }

    if (message.optionalArgs(Tokens.ARGS_BINDINGS).isPresent()) {
      final var bindings = (Map<?, ?>) message.getArgs().get(Tokens.ARGS_BINDINGS);
      if (IteratorUtils.anyMatch(bindings.keySet().iterator(),
          k -> !(k instanceof String))) {
        final var msg = String.format(
            "The [%s] message is using one or more invalid binding keys - they must be of type String and cannot be null",
            Tokens.OPS_EVAL);
        throw new OpProcessorException(msg, ResponseMessage.build(message)
            .code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(msg)
            .create());
      }

      @SuppressWarnings("unchecked") final var badBindings = IteratorUtils.set(
          IteratorUtils.filter(((Map<String, ?>) bindings).keySet().iterator(),
              INVALID_BINDINGS_KEYS::contains));
      if (!badBindings.isEmpty()) {
        final var msg = String.format(
            "The [%s] message supplies one or more invalid parameters key of [%s] - these are reserved names.",
            Tokens.OPS_EVAL, badBindings);
        throw new OpProcessorException(msg, ResponseMessage.build(message)
            .code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(msg)
            .create());
      }

      if (bindings.size() > maxParameters) {
        final var msg = String.format(
            "The [%s] message contains %s bindings which is more than is allowed by the server %s configuration",
            Tokens.OPS_EVAL, bindings.size(), maxParameters);
        throw new OpProcessorException(msg, ResponseMessage.build(message)
            .code(ResponseStatusCode.REQUEST_ERROR_INVALID_REQUEST_ARGUMENTS).statusMessage(msg)
            .create());
      }
    }

    return Optional.empty();
  }

  /// A generalized implementation of the "eval" operation.  It handles script evaluation and
  /// iteration of results so as to write [ResponseMessage] objects down the Netty pipeline. It also
  /// handles script timeouts, iteration timeouts, metrics and building bindings.  Note that result
  /// iteration is delegated to the [#handleIterator(Context, Iterator, YTDBGraphTraversalSource)]
  /// method, so those extending this class could override that method for better control over
  /// result iteration.
  ///
  /// @param ctx                     The current Gremlin Server [Context]. This handler ensures that
  ///                                only a single final response is sent to the client.
  /// @param gremlinExecutorSupplier A function that returns the [GremlinExecutor] to use in
  ///                                executing the script evaluation.
  protected void evalOpInternal(final Context ctx,
      final Supplier<GremlinExecutor> gremlinExecutorSupplier,
      final YTDBGraphTraversalSource traversalSource) {
    final var timerContext = evalOpTimer.time();
    final var msg = ctx.getRequestMessage();
    final var gremlinExecutor = gremlinExecutorSupplier.get();
    final var settings = ctx.getSettings();

    final var args = msg.getArgs();

    final var script = (String) args.get(Tokens.ARGS_GREMLIN);

    final var language = "gremlin-lang";
    final Bindings bindings = new SimpleBindings();
    //noinspection unchecked
    Optional.ofNullable((Map<String, Object>) msg.getArgs().get(Tokens.ARGS_BINDINGS))
        .ifPresent(bindings::putAll);
    bindings.put(Tokens.VAL_TRAVERSAL_SOURCE_ALIAS, traversalSource);

    // timeout override - handle both deprecated and newly named configuration. earlier logic should prevent
    // both configurations from being submitted at the same time
    final var seto = args.containsKey(Tokens.ARGS_EVAL_TIMEOUT) ?
        ((Number) args.get(Tokens.ARGS_EVAL_TIMEOUT)).longValue() : settings.getEvaluationTimeout();

    final var lifeCycle = GremlinExecutor.LifeCycle.build()
        .evaluationTimeoutOverride(seto)
        .afterFailure((b, t) -> {
          var tx = traversalSource.tx();
          if (tx.isOpen()) {
            tx.rollback();
          }
        })
        .afterTimeout((b, t) -> {
          var tx = traversalSource.tx();
          if (tx.isOpen()) {
            tx.rollback();
          }
        })
        .beforeEval(b -> {
          b.putAll(bindings);
        })
        .withResult(o -> {
          final var itty = IteratorUtils.asIterator(o);
          logger.debug("Preparing to iterate results from - {} - in thread [{}]", msg,
              Thread.currentThread().getName());
          if (settings.enableAuditLog) {
            var user = ctx.getChannelHandlerContext().channel().attr(StateKey.AUTHENTICATED_USER)
                .get();
            if (null == user) {    // This is expected when using the AllowAllAuthenticator
              user = AuthenticatedUser.ANONYMOUS_USER;
            }
            var address = ctx.getChannelHandlerContext().channel().remoteAddress().toString();
            if (!address.isEmpty() && address.charAt(0) == '/' && address.length() > 1) {
              address = address.substring(1);
            }
            auditLogger.info("User {} with address {} requested: {}", user.getName(), address,
                script);
          }

          try {
            handleIterator(ctx, itty, traversalSource);
          } catch (Exception ex) {
            var tx = traversalSource.tx();
            if (tx.isOpen()) {
              tx.rollback();
            }

            CloseableIterator.closeIterator(itty);

            // wrap up the exception and rethrow. the error will be written to the client by the evalFuture
            // as it will completeExceptionally in the GremlinExecutor
            throw new RuntimeException(ex);
          }
        }).create();

    try {
      final var evalFuture = gremlinExecutor.eval(script, language, bindings, lifeCycle);

      evalFuture.handle((v, t) -> {
        timerContext.stop();

        if (t != null) {
          // if any exception in the chain is TemporaryException or Failure then we should respond with the
          // right error code so that the client knows to retry
          final var possibleSpecialException = determineIfSpecialException(t);
          if (possibleSpecialException.isPresent()) {
            final var special = possibleSpecialException.get();
            final var specialResponseMsg = ResponseMessage.build(msg).
                statusMessage(special.getMessage()).
                statusAttributeException(special);
            if (special instanceof TemporaryException) {
              specialResponseMsg.code(ResponseStatusCode.SERVER_ERROR_TEMPORARY);
            } else if (special instanceof Failure failure) {
              specialResponseMsg.code(ResponseStatusCode.SERVER_ERROR_FAIL_STEP).
                  statusAttribute(Tokens.STATUS_ATTRIBUTE_FAIL_STEP_MESSAGE, failure.format());
            }
            ctx.writeAndFlush(specialResponseMsg.create());
          } else if (t instanceof OpProcessorException) {
            ctx.writeAndFlush(((OpProcessorException) t).getResponseMessage());
          } else if (t instanceof TimedInterruptTimeoutException) {
            // occurs when the TimedInterruptCustomizerProvider is in play
            final var errorMessage = String.format(
                "A timeout occurred within the script during evaluation of [%s] - consider increasing the limit given to TimedInterruptCustomizerProvider",
                msg);
            logger.warn(errorMessage);
            ctx.writeAndFlush(
                ResponseMessage.build(msg).code(ResponseStatusCode.SERVER_ERROR_TIMEOUT)
                    .statusMessage(
                        "Timeout during script evaluation triggered by TimedInterruptCustomizerProvider")
                    .statusAttributeException(t).create());
          } else if (t instanceof TimeoutException) {
            final var errorMessage = String.format(
                "Script evaluation exceeded the configured threshold for request [%s]", msg);
            logger.warn(errorMessage, t);
            ctx.writeAndFlush(
                ResponseMessage.build(msg).code(ResponseStatusCode.SERVER_ERROR_TIMEOUT)
                    .statusMessage(t.getMessage())
                    .statusAttributeException(t).create());
          } else {
            // try to trap the specific jvm error of "Method code too large!" to re-write it as something nicer,
            // but only re-write if it's the only error because otherwise we might lose some other important
            // information related to the failure. at this point, there hasn't been a scenario that has
            // presented itself where the "Method code too large!" comes with other compilation errors so
            // it seems that this message trumps other compilation errors to some reasonable degree that ends
            // up being favorable for this problem
            if (t instanceof MultipleCompilationErrorsException && t.getMessage()
                .contains("Method too large") &&
                ((MultipleCompilationErrorsException) t).getErrorCollector().getErrorCount() == 1) {
              final var errorMessage = String.format(
                  "The Gremlin statement that was submitted exceeds the maximum compilation size allowed by the JVM, please split it into multiple smaller statements - %s",
                  trimMessage(msg));
              logger.warn(errorMessage);
              ctx.writeAndFlush(
                  ResponseMessage.build(msg).code(ResponseStatusCode.SERVER_ERROR_EVALUATION)
                      .statusMessage(errorMessage)
                      .statusAttributeException(t).create());
            } else {
              final var errorMessage = (t.getMessage() == null) ? t.toString() : t.getMessage();
              logger.warn(String.format("Exception processing a script on request [%s].", msg), t);
              ctx.writeAndFlush(
                  ResponseMessage.build(msg).code(ResponseStatusCode.SERVER_ERROR_EVALUATION)
                      .statusMessage(errorMessage)
                      .statusAttributeException(t).create());
            }
          }
        }

        //noinspection ReturnOfNull
        return null;
      });
    } catch (RejectedExecutionException ree) {
      ctx.writeAndFlush(ResponseMessage.build(msg).code(ResponseStatusCode.TOO_MANY_REQUESTS)
          .statusMessage("Rate limiting").create());
    }
  }


  /// Used to decrease the size of a Gremlin script that triggered a "method too large" exception so
  /// that it doesn't log a massive text string nor return a large error message.
  private static RequestMessage trimMessage(final RequestMessage msg) {
    final var trimmedMsg = RequestMessage.from(msg).create();
    if (trimmedMsg.getArgs().containsKey(Tokens.ARGS_GREMLIN)) {
      trimmedMsg.getArgs().put(Tokens.ARGS_GREMLIN,
          trimmedMsg.getArgs().get(Tokens.ARGS_GREMLIN).toString().substring(0, 1021) + "...");
    }

    return trimmedMsg;
  }

  /// Check if any exception in the chain is [TemporaryException] or [Failure] then respond with the
  /// right error code so that the client knows to retry.
  protected static Optional<Throwable> determineIfSpecialException(final Throwable ex) {
    return Stream.of(ExceptionUtils.getThrowables(ex)).
        filter(i -> i instanceof TemporaryException || i instanceof Failure).findFirst();
  }

  private void iterateBytecodeTraversal(final Context context)
      throws Exception {
    final var msg = context.getRequestMessage();
    final var settings = context.getSettings();
    logger.debug("Traversal request {} for in thread {}", msg.getRequestId(),
        Thread.currentThread().getName());

    // validateTraversalRequest() ensures that this is of type Bytecode
    final var bytecodeObj = msg.getArgs().get(Tokens.ARGS_GREMLIN);
    final var bytecode = (Bytecode) bytecodeObj;

    // timeout override - handle both deprecated and newly named configuration. earlier logic should prevent
    // both configurations from being submitted at the same time
    final var args = msg.getArgs();
    final var seto = args.containsKey(Tokens.ARGS_EVAL_TIMEOUT) ?
        ((Number) args.get(Tokens.ARGS_EVAL_TIMEOUT)).longValue()
        : context.getSettings().getEvaluationTimeout();

    try {
      final var lambdaLanguage = BytecodeHelper.getLambdaLanguage(bytecode);
      if (lambdaLanguage.isPresent()) {
        var errorMsg = "Execution of lambada code is not allowed on server side.";
        throw new OpProcessorException("Traversal contains a lambda that cannot be compiled",
            ResponseMessage.build(msg).code(ResponseStatusCode.SERVER_ERROR_EVALUATION)
                .statusMessage(errorMsg)
                .create());
      }
    } catch (Exception ex) {
      logger.error("Could not deserialize the Traversal instance", ex);
      throw new OpProcessorException("Could not deserialize the Traversal instance",
          ResponseMessage.build(msg).code(ResponseStatusCode.SERVER_ERROR_SERIALIZATION)
              .statusMessage(ex.getMessage())
              .statusAttributeException(ex).create());
    }
    if (settings.enableAuditLog) {
      var user = context.getChannelHandlerContext().channel().attr(StateKey.AUTHENTICATED_USER)
          .get();
      if (null == user) {    // This is expected when using the AllowAllAuthenticator
        user = AuthenticatedUser.ANONYMOUS_USER;
      }
      var address = context.getChannelHandlerContext().channel().remoteAddress().toString();
      if (!address.isEmpty() && address.charAt(0) == '/' && address.length() > 1) {
        address = address.substring(1);
      }
      auditLogger.info("User {} with address {} requested: {}", user.getName(), address, bytecode);
    }

    var traversalSource = getTraversalSource(context);
    // handle bytecode based graph operations like commit/rollback commands
    if (BytecodeHelper.isGraphOperation(bytecode)) {
      handleGraphOperation(bytecode, traversalSource, context);
      return;
    }

    final var timerContext = traversalOpTimer.time();
    final var evalFuture = new FutureTask<Void>(() -> {
      context.setStartedResponse();
      try {
        var traversal = JavaTranslator.of(traversalSource).translate(bytecode);
        var ok = false;
        try {
          // compile the traversal - without it getEndStep() has nothing in it
          traversal.applyStrategies();
          handleIterator(context, new TraverserIterator(traversal), traversalSource);
          ok = true;
        } catch (Exception ex) {
          Throwable t = ex;
          if (ex instanceof UndeclaredThrowableException) {
            t = t.getCause();
          }

          CloseableIterator.closeIterator(traversal);

          // if any exception in the chain is TemporaryException or Failure then we should respond with the
          // right error code so that the client knows to retry
          final var possibleSpecialException = YTDBAbstractOpProcessor.determineIfSpecialException(
              ex);
          if (possibleSpecialException.isPresent()) {
            final var special = possibleSpecialException.get();
            final var specialResponseMsg = ResponseMessage.build(msg).
                statusMessage(special.getMessage()).
                statusAttributeException(special);
            if (special instanceof TemporaryException) {
              specialResponseMsg.code(ResponseStatusCode.SERVER_ERROR_TEMPORARY);
            } else if (special instanceof Failure failure) {
              specialResponseMsg.code(ResponseStatusCode.SERVER_ERROR_FAIL_STEP).
                  statusAttribute(Tokens.STATUS_ATTRIBUTE_FAIL_STEP_MESSAGE, failure.format());
            }
            context.writeAndFlush(specialResponseMsg.create());
          } else if (t instanceof InterruptedException
              || t instanceof TraversalInterruptedException) {

            final var errorMessage = String.format(
                "A timeout occurred during traversal evaluation of [%s] - consider increasing the limit given to evaluationTimeout",
                msg);
            logger.warn(errorMessage);
            context.writeAndFlush(
                ResponseMessage.build(msg).code(ResponseStatusCode.SERVER_ERROR_TIMEOUT)
                    .statusMessage(errorMessage)
                    .statusAttributeException(ex).create());
          } else {
            logger.warn(
                String.format("Exception processing a Traversal on iteration for request [%s].",
                    msg.getRequestId()), ex);
            context.writeAndFlush(ResponseMessage.build(msg).code(ResponseStatusCode.SERVER_ERROR)
                .statusMessage(ex.getMessage())
                .statusAttributeException(ex).create());
          }
        } finally {
          if (!ok) {
            var tx = traversalSource.tx();
            if (tx.isOpen()) {
              tx.rollback();
            }
          }
        }
      } catch (Throwable t) {
        // if any exception in the chain is TemporaryException or Failure then we should respond with the
        // right error code so that the client knows to retry
        final var possibleSpecialException = YTDBAbstractOpProcessor.determineIfSpecialException(t);
        if (possibleSpecialException.isPresent()) {
          final var special = possibleSpecialException.get();
          final var specialResponseMsg = ResponseMessage.build(msg).
              statusMessage(special.getMessage()).
              statusAttributeException(special);
          if (special instanceof TemporaryException) {
            specialResponseMsg.code(ResponseStatusCode.SERVER_ERROR_TEMPORARY);
          } else if (special instanceof Failure failure) {
            specialResponseMsg.code(ResponseStatusCode.SERVER_ERROR_FAIL_STEP).
                statusAttribute(Tokens.STATUS_ATTRIBUTE_FAIL_STEP_MESSAGE, failure.format());
          }
          context.writeAndFlush(specialResponseMsg.create());
        } else {
          logger.warn(String.format("Exception processing a Traversal on request [%s].",
              msg.getRequestId()), t);
          context.writeAndFlush(ResponseMessage.build(msg).code(ResponseStatusCode.SERVER_ERROR)
              .statusMessage(t.getMessage())
              .statusAttributeException(t).create());
          if (t instanceof Error) {
            //Re-throw any errors to be handled by and set as the result of evalFuture
            throw t;
          }
        }
      } finally {
        timerContext.stop();

        // There is a race condition that this query may have finished before the timeoutFuture was created,
        // though this is very unlikely. This is handled in the settor, if this has already been grabbed.
        // If we passed this point and the setter hasn't been called, it will cancel the timeoutFuture inside
        // the setter to compensate.
        final var timeoutFuture = context.getTimeoutExecutor();
        if (null != timeoutFuture) {
          timeoutFuture.cancel(true);
        }
      }

      return null;
    });

    submitToGremlinExecutor(context, traversalSource, seto, evalFuture);
  }

  protected void handleGraphOperation(final Bytecode bytecode,
      final YTDBGraphTraversalSource graphTraversalSource, final Context context) {
    final var msg = context.getRequestMessage();
    if (TX_COMMIT.equals(bytecode) || TX_ROLLBACK.equals(bytecode)) {
      final var commit = TX_COMMIT.equals(bytecode);

      // there is no timeout on a commit/rollback
      submitToGremlinExecutor(context, graphTraversalSource, 0, new FutureTask<>(() -> {
        try {
          var metadata = new HashMap<String, Object>();
          if (commit) {
            commitAndUpdateMetadata(graphTraversalSource, metadata);
          } else {
            var tx = graphTraversalSource.tx();

            if (tx.isOpen()) {
              tx.rollback();
            }
          }

          // write back a no-op for success
          final var attributes = generateStatusAttributes(
              context.getChannelHandlerContext(), Collections.emptyIterator());
          context.writeAndFlush(ResponseMessage.build(msg)
              .code(ResponseStatusCode.NO_CONTENT)
              .responseMetaData(metadata)
              .statusAttributes(attributes)
              .create());

        } catch (Throwable t) {
          // if any exception in the chain is TemporaryException or Failure then we should respond with the
          // right error code so that the client knows to retry
          final var possibleSpecialException = determineIfSpecialException(t);
          if (possibleSpecialException.isPresent()) {
            final var special = possibleSpecialException.get();
            final var specialResponseMsg = ResponseMessage.build(msg).
                statusMessage(special.getMessage()).
                statusAttributeException(special);
            if (special instanceof TemporaryException) {
              specialResponseMsg.code(ResponseStatusCode.SERVER_ERROR_TEMPORARY);
            } else if (special instanceof Failure failure) {
              specialResponseMsg.code(ResponseStatusCode.SERVER_ERROR_FAIL_STEP).
                  statusAttribute(Tokens.STATUS_ATTRIBUTE_FAIL_STEP_MESSAGE, failure.format());
            }
            context.writeAndFlush(specialResponseMsg.create());
          } else {
            logger.warn(String.format(
                "Exception processing a Traversal on request [%s] to %s the transaction.",
                msg.getRequestId(), commit ? "commit" : "rollback"), t);
            context.writeAndFlush(ResponseMessage.build(msg).code(ResponseStatusCode.SERVER_ERROR)
                .statusMessage(t.getMessage())
                .statusAttributeException(t).create());
          }
          if (t instanceof Error) {
            //Re-throw any errors to be handled by and set as the result the FutureTask
            throw t;
          }
        }

        return null;
      }));
    } else {
      throw new IllegalStateException(String.format(
          "Bytecode in request is not a recognized graph operation: %s", bytecode.toString()));
    }
  }

  private void submitToGremlinExecutor(final Context context,
      YTDBGraphTraversalSource traversalSource, final long seto,
      final FutureTask<Void> evalFuture) {
    try {
      final var executionFuture = getGremlinExecutor(context, traversalSource).getExecutorService()
          .submit(evalFuture);
      if (seto > 0) {
        // Schedule a timeout in the thread pool for future execution
        context.setTimeoutExecutor(context.getScheduledExecutorService().schedule(() -> {
          executionFuture.cancel(true);
          if (!context.getStartedResponse()) {
            context.sendTimeoutResponse();
          }
        }, seto, TimeUnit.MILLISECONDS));
      }
    } catch (RejectedExecutionException ree) {
      context.writeAndFlush(ResponseMessage.build(context.getRequestMessage())
          .code(ResponseStatusCode.TOO_MANY_REQUESTS)
          .statusMessage("Rate limiting").create());
    }
  }

  protected GremlinExecutor getGremlinExecutor(final Context context,
      YTDBGraphTraversalSource traversalSource) {
    return context.getGremlinExecutor();
  }

  protected YTDBGraphTraversalSource getTraversalSource(Context context)
      throws OpProcessorException {
    return context.getChannelHandlerContext().channel().attr(currentTraversalSource).get();
  }

  protected YTDBGraphTraversalSource initTraversalSourceIfAbsent(Context context)
      throws OpProcessorException {
    var attr = context.getChannelHandlerContext().channel().attr(currentTraversalSource);
    var traversal = attr.get();
    if (traversal == null) {
      traversal = doInitTraversalSource(context);

    }

    attr.set(traversal);
    return traversal;
  }
}
