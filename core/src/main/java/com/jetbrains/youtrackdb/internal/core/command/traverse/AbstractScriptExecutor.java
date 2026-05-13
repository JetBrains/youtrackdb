package com.jetbrains.youtrackdb.internal.core.command.traverse;

import com.jetbrains.youtrackdb.internal.core.command.ScriptExecutor;

public abstract class AbstractScriptExecutor implements ScriptExecutor {

  protected String language;

  public AbstractScriptExecutor(String language) {
    this.language = language;
  }
}
