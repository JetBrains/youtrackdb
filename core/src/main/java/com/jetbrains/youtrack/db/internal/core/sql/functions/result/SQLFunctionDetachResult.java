package com.jetbrains.youtrack.db.internal.core.sql.functions.result;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionAbstract;

public class SQLFunctionDetachResult extends SQLFunctionAbstract {
  public static final String NAME = "detach";
  public SQLFunctionDetachResult() {
    super(NAME, 0, 0);
  }

  @Override
  public Object execute(Object iThis, Result iCurrentRecord, Object iCurrentResult,
      Object[] iParams, CommandContext iContext) {
    if (iCurrentRecord == null) {
      throw new CommandSQLParsingException(iContext.getDatabaseSession().getDatabaseName(),
          "Method 'detach()' can be invoked only on Result instances, while NULL was found");
    }

    return iCurrentRecord.detach();

  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "detach()";
  }
}
