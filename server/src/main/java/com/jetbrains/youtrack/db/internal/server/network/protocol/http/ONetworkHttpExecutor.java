package com.jetbrains.youtrack.db.internal.server.network.protocol.http;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;

public interface ONetworkHttpExecutor {

  String getRemoteAddress();

  void setDatabase(DatabaseSessionEmbedded db);
}
