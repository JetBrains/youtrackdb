package com.jetbrains.youtrackdb.internal.server.network.protocol.http;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;

public interface ONetworkHttpExecutor {

  String getRemoteAddress();

  void setDatabase(DatabaseSessionEmbedded db);
}
