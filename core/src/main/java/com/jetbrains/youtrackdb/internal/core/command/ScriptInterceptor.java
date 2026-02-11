package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;

public interface ScriptInterceptor {

  void preExecute(DatabaseSessionEmbedded database, String language, String script,
      Object params);
}
