package com.jetbrains.youtrackdb.internal.core.sql.query;

import com.jetbrains.youtrackdb.internal.core.command.CommandResultListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import javax.annotation.Nonnull;

/**
 *
 */
public class LocalLiveResultListener implements LiveResultListener, CommandResultListener {

  private final LiveResultListener underlying;

  protected LocalLiveResultListener(LiveResultListener underlying) {
    this.underlying = underlying;
  }

  @Override
  public boolean result(@Nonnull DatabaseSessionInternal session, Object iRecord) {
    return false;
  }

  @Override
  public void end(@Nonnull DatabaseSessionInternal session) {
  }

  @Override
  public Object getResult() {
    return null;
  }

  @Override
  public void onLiveResult(DatabaseSessionInternal db, int iLiveToken, RecordOperation iOp)
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
