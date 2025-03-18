package com.jetbrains.youtrack.db.internal.core.query.live;

import com.jetbrains.youtrack.db.api.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;

public class YTLiveQueryMonitorEmbedded implements LiveQueryMonitor {
  private final int token;
  private final DatabasePoolInternal pool;

  public YTLiveQueryMonitorEmbedded(int token, DatabasePoolInternal pool) {
    this.token = token;
    this.pool = pool;
  }

  @Override
  public void unSubscribe() {
    try (var session = pool.acquire()) {
      LiveQueryHookV2.unsubscribe(token, (DatabaseSessionInternal) session);
    }

  }

  @Override
  public int getMonitorId() {
    return token;
  }
}
