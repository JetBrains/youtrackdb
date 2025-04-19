package com.jetbrains.youtrack.db.internal.core.sql.functions.misc;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionAbstract;
import javax.annotation.Nullable;

public class SQLFunctionAssert extends SQLFunctionAbstract {

  public static final String NAME = "assert";

  public SQLFunctionAssert() {
    super(NAME, 1, 2);
  }

  @Nullable
  @Override
  public Object execute(Object iThis, Result iCurrentRecord, Object iCurrentResult,
      Object[] iParams, CommandContext iContext) {

    boolean result;

    try {
      var condition = iParams[0];
      var message = iParams.length < 2 ? "" : iParams[1];

      if (condition instanceof Boolean) {
        result = (Boolean) condition;
      } else if (condition instanceof String) {
        result = Boolean.parseBoolean(condition.toString());
      } else if (condition instanceof Number) {
        result = ((Number) condition).intValue() > 0;
      } else {
        throw new AssertionError("Unsupported condition type: " + condition);
      }

      if (!result) {
        throw new AssertionError(message);
      }

      return null;

    } catch (Exception e) {
      LogManager.instance().error(this, "Error during if execution", e);

      return null;
    }
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "assert(<field|value|expression>[, message])";
  }
}
