package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;

public interface YTDBGraphInternal extends YTDBGraph {
  @Override
  DatabaseSessionEmbedded getUnderlyingDatabaseSession();

  YTDBElementFactory elementFactory();
}
