package com.jetbrains.youtrackdb.internal.core.sql.functions.text;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionConfigurableAbstract;
import javax.annotation.Nullable;

public class SQLFunctionConcat extends SQLFunctionConfigurableAbstract {

  public static final String NAME = "concat";
  private StringBuilder sb;

  public SQLFunctionConcat() {
    super(NAME, 1, 2);
  }

  @Nullable
  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      Object[] iParams,
      CommandContext iContext) {
    if (sb == null) {
      sb = new StringBuilder();
    } else {
      if (iParams.length > 1) {
        sb.append(iParams[1]);
      }
    }
    sb.append(iParams[0]);
    return null;
  }

  @Nullable
  @Override
  public Object getResult() {
    return sb != null ? sb.toString() : null;
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "concat(<field>, [<delim>])";
  }

  @Override
  public boolean aggregateResults() {
    return true;
  }
}
