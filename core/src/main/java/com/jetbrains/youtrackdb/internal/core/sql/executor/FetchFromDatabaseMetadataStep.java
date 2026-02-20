package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ProduceExecutionStream;

/**
 * Source step for {@code SELECT FROM metadata:DATABASE}.
 *
 * <p>Produces a single result record containing database-level metadata:
 * <ul>
 *   <li>{@code name} -- the database name</li>
 *   <li>{@code user} -- the current user's name (or null)</li>
 *   <li>{@code dateFormat} -- date format pattern</li>
 *   <li>{@code dateTimeFormat} -- date-time format pattern</li>
 *   <li>{@code timezone} -- database timezone</li>
 *   <li>{@code localeCountry} -- locale country code</li>
 *   <li>{@code localeLanguage} -- locale language code</li>
 *   <li>{@code charset} -- character set name</li>
 * </ul>
 *
 * @see SelectExecutionPlanner#handleMetadataAsTarget
 */
public class FetchFromDatabaseMetadataStep extends AbstractExecutionStep {

  public FetchFromDatabaseMetadataStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // Drain predecessor for side effects before producing metadata.
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
        "dateFormat", String.valueOf(db.get(DatabaseSessionEmbedded.ATTRIBUTES.DATEFORMAT)));
    result.setProperty(
        "dateTimeFormat",
        String.valueOf(db.get(DatabaseSessionEmbedded.ATTRIBUTES.DATE_TIME_FORMAT)));
    result.setProperty("timezone",
        String.valueOf(db.get(DatabaseSessionEmbedded.ATTRIBUTES.TIMEZONE)));
    result.setProperty(
        "localeCountry", String.valueOf(db.get(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_COUNTRY)));
    result.setProperty(
        "localeLanguage",
        String.valueOf(db.get(DatabaseSessionEmbedded.ATTRIBUTES.LOCALE_LANGUAGE)));
    result.setProperty("charset",
        String.valueOf(db.get(DatabaseSessionEmbedded.ATTRIBUTES.CHARSET)));
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

  /**
   * Not cacheable: database metadata (user, timezone, etc.) may change between
   * executions and must always be read fresh.
   */
  @Override
  public boolean canBeCached() {
    return false;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchFromDatabaseMetadataStep(ctx, profilingEnabled);
  }
}
