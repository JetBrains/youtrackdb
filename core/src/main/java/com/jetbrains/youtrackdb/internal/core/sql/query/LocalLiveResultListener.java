package com.jetbrains.youtrackdb.internal.core.sql.query;

import com.jetbrains.youtrackdb.internal.core.command.CommandResultListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import javax.annotation.Nonnull;

/**
 * A local adapter that bridges a {@link LiveResultListener} to also implement {@link
 * CommandResultListener}.
 */
public class LocalLiveResultListener implements LiveResultListener, CommandResultListener {

  private final LiveResultListener underlying;

  protected LocalLiveResultListener(LiveResultListener underlying) {
    this.underlying = underlying;
  }

  @Override
  public boolean result(@Nonnull DatabaseSessionEmbedded session, Object iRecord) {
    return false;
  }

  @Override
  public void end(@Nonnull DatabaseSessionEmbedded session) {
  }

  @Override
  public Object getResult() {
    return null;
  }

  @Override
  public void onLiveResult(DatabaseSessionEmbedded db, int iLiveToken, RecordOperation iOp)
      throws BaseException {
    underlying.onLiveResult(db, iLiveToken, iOp);
  }

  @Override
  public void onError(int iLiveToken) {
    underlying.onError(iLiveToken);
  }

  @Override
  public void onUnsubscribe(int iLiveToken) {
    underlying.onUnsubscribe(iLiveToken);
  }
}
