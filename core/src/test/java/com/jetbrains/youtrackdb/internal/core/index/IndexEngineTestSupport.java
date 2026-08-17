package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;

/** Shared access to an index engine without reflecting on index handle fields. */
public final class IndexEngineTestSupport {

  private IndexEngineTestSupport() {
  }

  public static int externalIdentifier(Index index) {
    return index.getIndexId();
  }

  public static int externalIdentifier(
      DatabaseSessionEmbedded session, String indexName) {
    return externalIdentifier(
        session.getSharedContext().getIndexManager().getIndex(indexName));
  }

  public static BaseIndexEngine engine(
      DatabaseSessionEmbedded session, String indexName) {
    var storage = (AbstractStorage) session.getStorage();
    try {
      return storage.getIndexEngine(externalIdentifier(session, indexName));
    } catch (InvalidIndexEngineIdException exception) {
      throw new AssertionError("Index engine is not registered: " + indexName, exception);
    }
  }

  public static int internalIdentifier(
      DatabaseSessionEmbedded session, String indexName) {
    return engine(session, indexName).getId();
  }
}
