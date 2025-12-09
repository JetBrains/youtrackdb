package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ProduceExecutionStream;

/**
 * Returns an Result containing metadata regarding the database
 */
public class FetchFromDatabaseMetadataStep extends AbstractExecutionStep {

  public FetchFromDatabaseMetadataStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    return new ProduceExecutionStream(FetchFromDatabaseMetadataStep::produce).limit(1);
  }

  private static Result produce(CommandContext ctx) {
    var db = ctx.getDatabaseSession();
    var result = new ResultInternal(db);

    result.setProperty("name", db.getDatabaseName());
    result.setProperty("user",
        db.getCurrentUser() == null ? null : db.getCurrentUser().getName(db));
    result.setProperty(
        "dateFormat", String.valueOf(db.get(DatabaseSession.ATTRIBUTES.DATEFORMAT)));
    result.setProperty(
        "dateTimeFormat", String.valueOf(db.get(DatabaseSession.ATTRIBUTES.DATE_TIME_FORMAT)));
    result.setProperty("timezone", String.valueOf(db.get(DatabaseSession.ATTRIBUTES.TIMEZONE)));
    result.setProperty(
        "localeCountry", String.valueOf(db.get(DatabaseSession.ATTRIBUTES.LOCALE_COUNTRY)));
    result.setProperty(
        "localeLanguage", String.valueOf(db.get(DatabaseSession.ATTRIBUTES.LOCALE_LANGUAGE)));
    result.setProperty("charset", String.valueOf(db.get(DatabaseSession.ATTRIBUTES.CHARSET)));
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ FETCH DATABASE METADATA";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchFromDatabaseMetadataStep(ctx, profilingEnabled);
  }
}
