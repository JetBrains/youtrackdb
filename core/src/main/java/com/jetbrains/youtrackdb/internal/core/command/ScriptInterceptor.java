package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;

public interface ScriptInterceptor {

  void preExecute(DatabaseSessionInternal database, String language, String script,
      Object params);
}
