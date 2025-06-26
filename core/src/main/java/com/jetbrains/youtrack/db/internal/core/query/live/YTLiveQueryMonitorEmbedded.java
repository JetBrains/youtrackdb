package com.jetbrains.youtrack.db.internal.core.query.live;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;

public class YTLiveQueryMonitorEmbedded implements LiveQueryMonitor {

  private final int token;
  private final DatabasePoolInternal<DatabaseSession> pool;

  public YTLiveQueryMonitorEmbedded(int token, DatabasePoolInternal<DatabaseSession> pool) {
    this.token = token;
    this.pool = pool;
  }

  @Override
  public void unSubscribe() {
    try (var session = pool.acquire()) {
      LiveQueryHookV2.unsubscribe(token, (DatabaseSessionEmbedded) session);
    }

  }

  @Override
  public int getMonitorId() {
    return token;
  }
}
