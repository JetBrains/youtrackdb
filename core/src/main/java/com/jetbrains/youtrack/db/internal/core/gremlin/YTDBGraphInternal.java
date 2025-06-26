package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;

public interface YTDBGraphInternal extends YTDBGraph {
  boolean isOpen();

  @Override
  YTDBTransaction tx();
}
