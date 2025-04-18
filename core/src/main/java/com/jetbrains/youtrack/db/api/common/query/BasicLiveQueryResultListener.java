package com.jetbrains.youtrack.db.api.common.query;

import com.jetbrains.youtrack.db.api.common.BasicDatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import javax.annotation.Nonnull;

public interface BasicLiveQueryResultListener<S extends BasicDatabaseSession<R, ?>, R extends BasicResult> {
  void onCreate(@Nonnull S session, @Nonnull R data);

  void onUpdate(@Nonnull S session, @Nonnull R before,  @Nonnull R after);

  void onDelete(@Nonnull S session, @Nonnull R data);

  void onError(@Nonnull S session, @Nonnull BaseException exception);

  void onEnd(@Nonnull S session);
}
