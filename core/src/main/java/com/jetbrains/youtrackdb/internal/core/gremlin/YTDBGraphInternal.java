package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand.SqlCommandExecutionResult;
import java.util.Map;
import java.util.function.Consumer;

public interface YTDBGraphInternal extends YTDBGraph {
  boolean isOpen();

  @Override
  YTDBTransaction tx();

  void executeSchemaCode(Consumer<DatabaseSessionEmbedded> code);

  SqlCommandExecutionResult executeCommand(String sqlCommand, Map<?, ?> params);
}
