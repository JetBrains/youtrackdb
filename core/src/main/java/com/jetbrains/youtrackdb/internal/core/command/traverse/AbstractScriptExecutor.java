package com.jetbrains.youtrackdb.internal.core.command.traverse;

import com.jetbrains.youtrackdb.internal.core.command.ScriptExecutor;
import com.jetbrains.youtrackdb.internal.core.command.ScriptInterceptor;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractScriptExecutor implements ScriptExecutor {

  protected String language;

  public AbstractScriptExecutor(String language) {
    this.language = language;
  }

  private final List<ScriptInterceptor> interceptors = new ArrayList<>();

  @Override
  public void registerInterceptor(ScriptInterceptor interceptor) {
    interceptors.add(interceptor);
  }

  public void preExecute(DatabaseSessionEmbedded database, String script, Object params) {

    interceptors.forEach(i -> i.preExecute(database, language, script, params));
  }

  @Override
  public void unregisterInterceptor(ScriptInterceptor interceptor) {
    interceptors.remove(interceptor);
  }
}
