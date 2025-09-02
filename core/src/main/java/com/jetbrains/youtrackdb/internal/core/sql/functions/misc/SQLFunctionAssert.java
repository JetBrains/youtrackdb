package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
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
    var condition = iParams[0];
    var message = iParams.length < 2 ? "" : iParams[1];

    result = switch (condition) {
      case Boolean b -> b;
      case String s -> Boolean.parseBoolean(s);
      case Number number -> number.intValue() > 0;
      case null, default ->
          throw new CommandExecutionException("Unsupported condition type: " + condition);
    };

    assert result : message;

    return result;

  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "assert(<field|value|expression>[, message])";
  }
}
