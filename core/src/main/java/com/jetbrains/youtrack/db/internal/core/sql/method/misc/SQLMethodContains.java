package com.jetbrains.youtrack.db.internal.core.sql.method.misc;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;

public class SQLMethodContains extends AbstractSQLMethod {

  public static final String NAME = "contains";

  public SQLMethodContains() {
    super(NAME, 1);
  }

  @Override
  public Object execute(Object iThis, Result iCurrentRecord, CommandContext iContext,
      Object ioResult, Object[] iParams) {
    if (iParams == null && iParams.length != 1) {
      return false;
    }

    return MultiValue.contains(iThis, iParams[0]);
  }


  @Override
  public String getSyntax() {
    return "object.contains(<item>)";
  }
}
