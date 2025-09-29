package com.jetbrains.youtrackdb.internal.driver;

import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.tinkerpop.gremlin.driver.Result;
import org.apache.tinkerpop.gremlin.driver.ResultQueue;
import org.apache.tinkerpop.gremlin.driver.exception.ResponseException;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedElement;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.apache.tinkerpop.gremlin.util.ser.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YTDBGremlinResponseHandler extends SimpleChannelInboundHandler<ResponseMessage> {

  public static final String RESULT_METADATA_COMMITTED_RIDS_KEY = "committedRIDs";

  private static final Logger logger = LoggerFactory.getLogger(YTDBGremlinResponseHandler.class);
  private final ConcurrentMap<UUID, ResultQueue> pending;
  private final ConcurrentMap<UUID, ConcurrentHashMap<RecordIdInternal, Set<ChangeableRecordId>>> changeableRIDs = new ConcurrentHashMap<>();

  public YTDBGremlinResponseHandler(final ConcurrentMap<UUID, ResultQueue> pending) {
    this.pending = pending;
  }

  @Override
  public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
    // occurs when the server shuts down in a disorderly fashion, otherwise in an orderly shutdown the server
    // should fire off a close message which will properly release the driver.
    super.channelInactive(ctx);

    // the channel isn't going to get anymore results as it is closed so release all pending requests
    pending.values().forEach(val -> val.markError(
        new IllegalStateException("Connection to server is no longer active")));
    pending.clear();
    changeableRIDs.clear();
  }

  @Override
  protected void channelRead0(final ChannelHandlerContext channelHandlerContext,
      final ResponseMessage response) {
    final var statusCode = response.getStatus().getCode();
    final var queue = pending.get(response.getRequestId());
    if (statusCode == ResponseStatusCode.SUCCESS
        || statusCode == ResponseStatusCode.PARTIAL_CONTENT) {
      final var data = response.getResult().getData();

      rememberChangeableRid(response, data);
      // this is a "result" from the server which is either the result of a script or a
      // serialized traversal
      if (data instanceof List) {
        // unrolls the collection into individual results to be handled by the queue.
        @SuppressWarnings("unchecked") final var listToUnroll = (List<Object>) data;
        listToUnroll.forEach(item -> queue.add(new Result(item)));
      } else {
        // since this is not a list it can just be added to the queue
        queue.add(new Result(data));
      }
    } else {
      // this is a "success" but represents no results otherwise it is an error
      if (statusCode != ResponseStatusCode.NO_CONTENT) {
        final var attributes = response.getStatus().getAttributes();
        final var stackTrace = attributes.containsKey(Tokens.STATUS_ATTRIBUTE_STACK_TRACE) ?
            (String) attributes.get(Tokens.STATUS_ATTRIBUTE_STACK_TRACE) : null;
        @SuppressWarnings("unchecked") final var exceptions =
            attributes.containsKey(Tokens.STATUS_ATTRIBUTE_EXCEPTIONS) ?
                (List<String>) attributes.get(Tokens.STATUS_ATTRIBUTE_EXCEPTIONS) : null;
        queue.markError(
            new ResponseException(response.getStatus().getCode(), response.getStatus().getMessage(),
                exceptions, stackTrace, cleanStatusAttributes(attributes)));
      }
    }

    // as this is a non-PARTIAL_CONTENT code - the stream is done.
    if (statusCode != ResponseStatusCode.PARTIAL_CONTENT) {
      var attributes = response.getStatus().getAttributes();

      var changeableRIDs = this.changeableRIDs.remove(response.getRequestId());
      @SuppressWarnings("unchecked") var committedRIDs = (Map<RecordIdInternal, RecordIdInternal>) response.getResult()
          .getMeta().get(
              RESULT_METADATA_COMMITTED_RIDS_KEY);
      if (changeableRIDs != null && committedRIDs != null) {
        for (var committedRidEntry : committedRIDs.entrySet()) {
          var elementRids = changeableRIDs.remove(committedRidEntry.getKey());

          if (elementRids != null) {
            var newRidValue = committedRidEntry.getValue();

            for (var rid : elementRids) {
              rid.setCollectionAndPosition(newRidValue.getCollectionId(),
                  newRidValue.getCollectionPosition());
            }
          }
        }
      }

      pending.remove(response.getRequestId()).markComplete(attributes);
    }
  }

  private void rememberChangeableRid(ResponseMessage response, Object data) {

    switch (data) {
      case Traverser<?> traverser -> {
        var underlying = traverser.get();
        rememberChangeableRid(response, underlying);
      }
      case Collection<?> collection -> {
        for (var item : collection) {
          rememberChangeableRid(response, item);
        }
      }
      case Map<?, ?> map -> {
        for (var entry : map.entrySet()) {
          rememberChangeableRid(response, entry.getKey());
          rememberChangeableRid(response, entry.getValue());
        }
      }
      case Iterable<?> iterable -> {
        for (var item : iterable) {
          rememberChangeableRid(response, item);
        }
      }
      case DetachedElement<?> detachedElement -> {
        RecordIdInternal rid;
        if (detachedElement instanceof VertexProperty<?> vertexProperty) {
          var vertexPropertyId = (YTDBVertexPropertyId) vertexProperty.id();
          rid = (RecordIdInternal) vertexPropertyId.rid();
        } else {
          rid = (RecordIdInternal) detachedElement.id();
        }

        rememberChangeableRid(response, rid);
      }
      case RecordIdInternal rid -> {
        if (rid.isNew()) {
          changeableRIDs.compute(response.getRequestId(), (uuid, rids) -> {
            if (rids == null) {
              rids = new ConcurrentHashMap<>();
            }

            rids.compute(rid.copy(), (ridCopy, ridSet) -> {
              if (ridSet == null) {
                ridSet = Collections.newSetFromMap(new IdentityHashMap<>());
              }

              ridSet.add((ChangeableRecordId) rid);
              return ridSet;
            });

            return rids;
          });
        }
      }
      case null, default -> {
      }
    }
  }

  @Override
  public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
    // if this happens enough times (like the client is unable to deserialize a response) the pending
    // messages queue will not clear.  wonder if there is some way to cope with that.  of course, if
    // there are that many failures someone would take notice and hopefully stop the client.
    logger.error("Could not process the response", cause);

    // the channel took an error because of something pretty bad so release all the futures out there
    pending.values().forEach(val -> val.markError(cause));
    pending.clear();
    changeableRIDs.clear();

    // serialization exceptions should not close the channel - that's worth a retry
    if (!YTDBIteratorUtils.anyMatch(
        ExceptionUtils.getThrowableList(cause).iterator(),
        t -> t instanceof SerializationException)) {
      if (ctx.channel().isActive()) {
        ctx.close();
      }
    }
  }

  private static Map<String, Object> cleanStatusAttributes(
      final Map<String, Object> statusAttributes) {
    final Map<String, Object> m = new HashMap<>();
    statusAttributes.forEach((k, v) -> {
      if (!k.equals(Tokens.STATUS_ATTRIBUTE_EXCEPTIONS) && !k.equals(
          Tokens.STATUS_ATTRIBUTE_STACK_TRACE)) {
        m.put(k, v);
      }
    });
    return m;
  }
}
