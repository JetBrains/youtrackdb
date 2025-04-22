package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.api.common.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.internal.client.remote.message.LiveQueryPushRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.live.LiveQueryResult;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrack.db.internal.remote.RemoteDatabaseSessionInternal;
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

  /**
   * Return true if the push request require an unregister
   */
  public boolean onEvent(LiveQueryPushRequest pushRequest) {
    try (var session = (RemoteDatabaseSessionInternal) pool.acquire()) {
      if (pushRequest.getStatus() == LiveQueryPushRequest.ERROR) {
        onError(pushRequest.getErrorCode().newException(pushRequest.getErrorMessage(), null),
            session);
        return true;
      } else {
        for (var result : pushRequest.getEvents()) {
          switch (result.getEventType()) {
            case LiveQueryResult.CREATE_EVENT:
              listener.onCreate(session, (RemoteResult) result.getCurrentValue().detach());
              break;
            case LiveQueryResult.UPDATE_EVENT:
              listener.onUpdate(session, (RemoteResult) result.getOldValue(),
                  (RemoteResult) result.getCurrentValue().detach());
              break;
            case LiveQueryResult.DELETE_EVENT:
              listener.onDelete(session, (RemoteResult) result.getCurrentValue().detach());
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
