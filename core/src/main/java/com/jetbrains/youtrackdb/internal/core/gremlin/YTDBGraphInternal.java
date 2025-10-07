package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

public interface YTDBGraphInternal extends YTDBGraph {
  @Override
  YTDBTransaction tx();

  void executeSchemaCode(Consumer<DatabaseSessionEmbedded> code);

  @Nullable
  <R> R computeSchemaCode(Function<DatabaseSessionEmbedded, R> code);
}
