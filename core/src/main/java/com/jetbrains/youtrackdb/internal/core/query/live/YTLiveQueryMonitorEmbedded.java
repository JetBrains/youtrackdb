package com.jetbrains.youtrackdb.internal.core.query.live;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.common.query.LiveQueryMonitor;
import com.jetbrains.youtrackdb.internal.core.db.DatabasePoolInternal;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;

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
