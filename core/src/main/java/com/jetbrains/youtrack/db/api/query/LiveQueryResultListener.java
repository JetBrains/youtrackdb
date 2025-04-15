package com.jetbrains.youtrack.db.api.query;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import javax.annotation.Nonnull;

/**
 *
 */
public interface LiveQueryResultListener {

  void onCreate(@Nonnull DatabaseSessionInternal session, @Nonnull Result data);

  void onUpdate(@Nonnull DatabaseSessionInternal session, @Nonnull Result before,
      @Nonnull Result after);

  void onDelete(@Nonnull DatabaseSessionInternal session, @Nonnull Result data);

  void onError(@Nonnull DatabaseSession session, @Nonnull BaseException exception);

  void onEnd(@Nonnull DatabaseSession session);
}
