package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.internal.client.remote.message.LiveQueryPushRequest;
import com.jetbrains.youtrack.db.internal.client.remote.message.live.LiveQueryResult;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import javax.annotation.Nonnull;

/**
 *
 */
public class LiveQueryClientListener {

  @Nonnull
  private final DatabasePoolInternal pool;
  private final LiveQueryResultListener listener;

  public LiveQueryClientListener(@Nonnull DatabasePoolInternal pool,
      LiveQueryResultListener listener) {
    this.pool = pool;
    this.listener = listener;
  }

  /**
   * Return true if the push request require an unregister
   */
  public boolean onEvent(LiveQueryPushRequest pushRequest) {
    try (var session = (DatabaseSessionInternal) pool.acquire()) {
      if (pushRequest.getStatus() == LiveQueryPushRequest.ERROR) {
        onError(pushRequest.getErrorCode().newException(pushRequest.getErrorMessage(), null),
            session);
        return true;
      } else {
        for (var result : pushRequest.getEvents()) {
          switch (result.getEventType()) {
            case LiveQueryResult.CREATE_EVENT:
              listener.onCreate(session, result.getCurrentValue().detach());
              break;
            case LiveQueryResult.UPDATE_EVENT:
              listener.onUpdate(session, result.getOldValue(), result.getCurrentValue().detach());
              break;
            case LiveQueryResult.DELETE_EVENT:
              listener.onDelete(session, result.getCurrentValue().detach());
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

  public void onError(BaseException e, DatabaseSessionInternal session) {
    listener.onError(session, e);
  }

  public void onError(BaseException e) {
    try (var session = (DatabaseSessionInternal) pool.acquire()) {
      onError(e, session);
    }
  }

  public void onEnd(DatabaseSessionInternal session) {
    listener.onEnd(session);
  }

  public void onEnd() {
    try (var session = (DatabaseSessionInternal) pool.acquire()) {
      onEnd(session);
    }
    pool.close();
  }
}
