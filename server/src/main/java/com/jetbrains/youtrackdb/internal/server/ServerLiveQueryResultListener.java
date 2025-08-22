package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.client.remote.message.LiveQueryPushRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.live.LiveQueryResult;
import com.jetbrains.youtrackdb.internal.common.exception.ErrorCode;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.LiveQueryBatchResultListener;
import com.jetbrains.youtrackdb.internal.core.db.SharedContext;
import com.jetbrains.youtrackdb.internal.core.exception.CoreException;
import com.jetbrains.youtrackdb.internal.core.exception.LiveQueryInterruptedException;
import com.jetbrains.youtrackdb.internal.server.network.protocol.binary.NetworkProtocolBinary;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;

class ServerLiveQueryResultListener implements LiveQueryBatchResultListener {

  private final NetworkProtocolBinary protocol;
  private final SharedContext sharedContext;
  private int monitorId;

  List<LiveQueryResult> toSend = new ArrayList<>();

  public ServerLiveQueryResultListener(
      NetworkProtocolBinary protocol, SharedContext sharedContext) {
    this.protocol = protocol;
    this.sharedContext = sharedContext;
  }

  public void setMonitorId(int monitorId) {
    this.monitorId = monitorId;
  }

  private synchronized void addEvent(LiveQueryResult event) {
    toSend.add(event);
  }

  @Override
  public void onCreate(@Nonnull DatabaseSession session, @Nonnull Result data) {
    addEvent(new LiveQueryResult(LiveQueryResult.CREATE_EVENT, data, null));
  }

  @Override
  public void onUpdate(@Nonnull DatabaseSession session, @Nonnull Result before,
      @Nonnull Result after) {
    addEvent(new LiveQueryResult(LiveQueryResult.UPDATE_EVENT, after, before));
  }

  @Override
  public void onDelete(@Nonnull DatabaseSession session, @Nonnull Result data) {
    addEvent(new LiveQueryResult(LiveQueryResult.DELETE_EVENT, data, null));
  }

  @Override
  public void onError(@Nonnull DatabaseSession session, @Nonnull BaseException exception) {
    try {
      // TODO: resolve error identifier
      var errorIdentifier = 0;
      var code = ErrorCode.GENERIC_ERROR;
      if (exception instanceof CoreException) {
        code = ((CoreException) exception).getErrorCode();
      }
      protocol.push((DatabaseSessionEmbedded) session,
          new LiveQueryPushRequest(monitorId, errorIdentifier, code, exception.getMessage()));
    } catch (IOException e) {
      throw BaseException.wrapException(
          new LiveQueryInterruptedException(session, "Live query interrupted by socket close"), e,
          session);
    }
  }

  @Override
  public void onEnd(@Nonnull DatabaseSession session) {
    try {
      protocol.push((DatabaseSessionEmbedded) session,
          new LiveQueryPushRequest(monitorId, LiveQueryPushRequest.END, Collections.emptyList()));
    } catch (IOException e) {
      throw BaseException.wrapException(
          new LiveQueryInterruptedException(session, "Live query interrupted by socket close"), e,
          session);
    }
  }

  @Override
  public void onBatchEnd(DatabaseSession session) {
    sendEvents(session);
  }

  private synchronized void sendEvents(DatabaseSession session) {
    if (toSend.isEmpty()) {
      return;
    }
    var events = toSend;
    toSend = new ArrayList<>();

    try {
      protocol.push((DatabaseSessionEmbedded) session,
          new LiveQueryPushRequest(monitorId, LiveQueryPushRequest.HAS_MORE, events));
    } catch (IOException e) {
      sharedContext.getLiveQueryOpsV2().getSubscribers().remove(monitorId);
      throw BaseException.wrapException(
          new LiveQueryInterruptedException(session, "Live query interrupted by socket close"), e,
          session);
    }
  }
}
