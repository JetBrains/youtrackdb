package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.SharedContext;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerRemote;

public class DatabaseSessionRemotePooled extends DatabaseSessionRemote {
  private final DatabasePoolInternal pool;

  public DatabaseSessionRemotePooled(
      DatabasePoolInternal pool, StorageRemote storage,
      SharedContext<IndexManagerRemote> sharedContext) {
    super(storage, sharedContext);
    this.pool = pool;
  }

  @Override
  public void close() {
    if (isClosed()) {
      return;
    }

    internalClose(true);
    pool.release(this);
  }

  @Override
  public DatabaseSessionInternal copy() {
    assertIfNotActive();
    return (DatabaseSessionInternal) pool.acquire();
  }

  public void reuse() {
    activateOnCurrentThread();
    setStatus(DatabaseSession.STATUS.OPEN);
  }

  public void realClose() {
      activateOnCurrentThread();
      super.close();
  }
}
