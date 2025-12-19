package com.jetbrains.youtrackdb.internal.core.sql.functions.sequence;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItem;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionConfigurableAbstract;
import javax.annotation.Nullable;

/**
 * Returns a sequence by name.
 */
public class SQLFunctionSequence extends SQLFunctionConfigurableAbstract {

  public static final String NAME = "sequence";

  public SQLFunctionSequence() {
    super(NAME, 1, 1);
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      Object[] iParams,
      CommandContext context) {
    final String seqName;
    if (configuredParameters != null
        && configuredParameters.length > 0
        && configuredParameters[0] instanceof SQLFilterItem) // old stuff
    {
      seqName =
          (String)
              ((SQLFilterItem) configuredParameters[0])
                  .getValue(iCurrentRecord, iCurrentResult, context);
    } else {
      seqName = "" + iParams[0];
    }

    var result = context.getDatabaseSession()
        .getMetadata()
        .getSequenceLibrary()
        .getSequence(seqName);
    if (result == null) {
      throw new CommandExecutionException(context.getDatabaseSession(),
          "Sequence not found: " + seqName);
    }
    return result;
  }

  @Nullable
  @Override
  public Object getResult() {
    return null;
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "sequence(<name>)";
  }

  @Override
  public boolean aggregateResults() {
    return false;
  }
}
