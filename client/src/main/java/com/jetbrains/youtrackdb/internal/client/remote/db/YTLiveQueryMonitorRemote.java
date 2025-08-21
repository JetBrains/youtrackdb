package com.jetbrains.youtrackdb.internal.client.remote.db;


import com.jetbrains.youtrackdb.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.DatabasePoolInternal;
import javax.annotation.Nonnull;

/**
 *
 */
public class YTLiveQueryMonitorRemote implements LiveQueryMonitor {

  @Nonnull
  private final DatabasePoolInternal<RemoteDatabaseSession> pool;
  private final int monitorId;

  public YTLiveQueryMonitorRemote(@Nonnull DatabasePoolInternal<RemoteDatabaseSession> pool,
      int monitorId) {
    this.pool = pool;
    this.monitorId = monitorId;
  }

  @Override
  public void unSubscribe() {
    try (var session = (DatabaseSessionRemote) pool.acquire()) {
      session.getCommandOrchestrator().unsubscribeLive(session, this.monitorId);
    }
  }

  @Override
  public int getMonitorId() {
    return monitorId;
  }
}
