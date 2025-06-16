package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import java.util.function.Consumer;

public interface YTDBGraphInternal extends YTDBGraph {
  YTDBElementFactory elementFactory();

  boolean isOpen();
}
