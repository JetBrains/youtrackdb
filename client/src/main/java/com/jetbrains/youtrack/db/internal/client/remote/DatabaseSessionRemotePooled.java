package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrack.db.internal.core.db.PooledSession;

public class DatabaseSessionRemotePooled extends DatabaseSessionRemote implements PooledSession {

  private final DatabasePoolInternal<DatabaseSessionRemote> pool;

  public DatabaseSessionRemotePooled(
      DatabasePoolInternal<DatabaseSessionRemote> pool, RemoteCommandsOrchestratorImpl storage) {
    super(storage);
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
  public boolean isBackendClosed() {
    return getCommandOrchestrator().isClosed(this);
  }

  @Override
  public void reuse() {
    activateOnCurrentThread();
    this.status = STATUS.OPEN;
  }

  @Override
  public void realClose() {
    activateOnCurrentThread();
    super.close();
  }
}
