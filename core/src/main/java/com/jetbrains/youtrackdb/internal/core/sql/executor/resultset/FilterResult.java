package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;

public interface FilterResult {

  /**
   * Filter and change a result
   *
   * @param result to check
   * @param ctx    TODO
   * @return a new result or null if the current result need to be skipped
   */
  Result filterMap(Result result, CommandContext ctx);
}
