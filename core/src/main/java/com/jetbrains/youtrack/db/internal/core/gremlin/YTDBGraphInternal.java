package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

public interface YTDBGraphInternal extends YTDBGraph {
  boolean isOpen();

  @Override
  YTDBTransaction tx();

  void executeSchemaCode(Consumer<DatabaseSessionEmbedded> code);

  @Nullable
  <R> R computeSchemaCode(Function<DatabaseSessionEmbedded, R> code);
}
