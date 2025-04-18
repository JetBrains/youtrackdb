package com.jetbrains.youtrack.db.internal.client.remote.db;


import com.jetbrains.youtrack.db.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
import javax.annotation.Nonnull;

/**
 *
 */
public class YTLiveQueryMonitorRemote implements LiveQueryMonitor {

  @Nonnull
  private final DatabasePoolInternal pool;
  private final int monitorId;

  public YTLiveQueryMonitorRemote(@Nonnull DatabasePoolInternal pool, int monitorId) {
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
