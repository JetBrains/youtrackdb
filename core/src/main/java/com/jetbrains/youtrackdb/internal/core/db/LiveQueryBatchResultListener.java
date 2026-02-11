package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.core.query.LiveQueryResultListener;

/**
 * Designed to allow live query result listeners to be optimised for batch elaboration. The normal
 * mechanics of the {@link LiveQueryResultListener} is preserved; In addition, at the end of a
 * logical batch of invocations to on*() methods, onBatchEnd() is invoked.
 */
public interface LiveQueryBatchResultListener extends LiveQueryResultListener {

  /**
   * invoked at the end of a logical batch of live query events
   *
   * @param session the instance of the active datatabase connection where the live query operation
   *                 is being performed
   */
  void onBatchEnd(DatabaseSessionEmbedded session);
}
