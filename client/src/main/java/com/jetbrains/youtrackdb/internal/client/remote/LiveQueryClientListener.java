package com.jetbrains.youtrackdb.internal.client.remote;

import com.jetbrains.youtrackdb.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrackdb.api.remote.query.RemoteResult;
import com.jetbrains.youtrackdb.internal.client.remote.message.LiveQueryPushRequest;
import com.jetbrains.youtrackdb.internal.client.remote.message.live.LiveQueryResult;
import com.jetbrains.youtrackdb.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrackdb.internal.remote.RemoteDatabaseSessionInternal;
import javax.annotation.Nonnull;

public class LiveQueryClientListener {

  @Nonnull
  private final DatabasePoolInternal<RemoteDatabaseSession> pool;
  private final BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult> listener;

  public LiveQueryClientListener(@Nonnull DatabasePoolInternal<RemoteDatabaseSession> pool,
      BasicLiveQueryResultListener<RemoteDatabaseSession, RemoteResult> listener) {
    this.pool = pool;
    this.listener = listener;
  }

  @Nonnull
  public DatabasePoolInternal<RemoteDatabaseSession> getPool() {
    return pool;
  }

  /**
   * Return true if the push request require an unregister
   */
  public boolean onEvent(RemoteDatabaseSessionInternal session, LiveQueryPushRequest pushRequest) {
    if (pushRequest.getStatus() == LiveQueryPushRequest.ERROR) {
      onError(pushRequest.getErrorCode().newException(pushRequest.getErrorMessage(), null),
          session);
      return true;
    } else {
      for (var result : pushRequest.getEvents()) {
        switch (result.eventType()) {
          case LiveQueryResult.CREATE_EVENT:
            listener.onCreate(session, (RemoteResult) result.currentValue().detach());
            break;
          case LiveQueryResult.UPDATE_EVENT:
            listener.onUpdate(session, (RemoteResult) result.oldValue(),
                (RemoteResult) result.currentValue().detach());
            break;
          case LiveQueryResult.DELETE_EVENT:
            listener.onDelete(session, (RemoteResult) result.currentValue().detach());
            break;
        }
      }
      if (pushRequest.getStatus() == LiveQueryPushRequest.END) {
        onEnd(session);
        return true;
      }
    }
    return false;
  }

  public void onError(BaseException e, RemoteDatabaseSessionInternal session) {
    listener.onError(session, e);
  }

  public void onError(BaseException e) {
    try (var session = (RemoteDatabaseSessionInternal) pool.acquire()) {
      onError(e, session);
    }
  }

  public void onEnd(RemoteDatabaseSessionInternal session) {
    listener.onEnd(session);
  }

  public void onEnd() {
    try (var session = (RemoteDatabaseSessionInternal) pool.acquire()) {
      onEnd(session);
    }
    pool.close();
  }
}
