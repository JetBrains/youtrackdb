package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import java.util.Map;

/**
 *
 */
public interface ScriptExecutor {

  ResultSet execute(DatabaseSessionEmbedded database, String script, Object... params);

  ResultSet execute(DatabaseSessionEmbedded database, String script, Map params);

  Object executeFunction(
      CommandContext context, final String functionName, final Map<Object, Object> iArgs);

  void registerInterceptor(ScriptInterceptor interceptor);

  void unregisterInterceptor(ScriptInterceptor interceptor);

  default void close(String iDatabaseName) {
  }

  default void closeAll() {
  }
}
