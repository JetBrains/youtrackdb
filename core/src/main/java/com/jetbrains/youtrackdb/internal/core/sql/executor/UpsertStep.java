package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;

/**
 *
 */
public class UpsertStep extends AbstractExecutionStep {
  private final SQLFromClause commandTarget;
  private final SQLWhereClause initialFilter;

  public UpsertStep(
      SQLFromClause target, SQLWhereClause where, CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);

    this.commandTarget = target;
    this.initialFilter = where;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    var prev = this.prev;
    assert prev != null;

    var upstream = prev.start(ctx);
    if (upstream.hasNext(ctx)) {
      return upstream;
    }

    return ExecutionStream.singleton(createNewRecord(ctx, commandTarget, initialFilter));
  }

  private static Result createNewRecord(
      CommandContext ctx, SQLFromClause commandTarget, SQLWhereClause initialFilter) {
    var session = ctx.getDatabaseSession();
    EntityImpl entity;
    var cls = commandTarget.getSchemaClass(session);
    if (cls != null) {
      entity = (EntityImpl) session.newEntity(cls);
    } else {
      throw new CommandExecutionException(session,
          "Cannot execute UPSERT on target '" + commandTarget + "'");
    }

    var result = new UpdatableResult(ctx.getDatabaseSession(), entity);
    if (initialFilter != null) {
      setContent(result, initialFilter, cls, ctx);
    }
    return result;
  }

  private static void setContent(ResultInternal res, SQLWhereClause initialFilter,
      SchemaClass schemaClass, CommandContext ctx) {
    var flattened = initialFilter.flatten(ctx, schemaClass);
    if (flattened.isEmpty()) {
      return;
    }
    if (flattened.size() > 1) {
      throw new CommandExecutionException(res.getBoundedToSession(),
          "Cannot UPSERT on OR conditions");
    }
    var andCond = flattened.getFirst();
    for (var condition : andCond.getSubBlocks()) {
      condition.transformToUpdateItem().ifPresent(x -> x.applyUpdate(res, ctx));
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces
        + "+ INSERT (upsert, if needed)\n"
        + spaces
        + "  target: "
        + commandTarget
        + "\n"
        + spaces
        + "  content: "
        + initialFilter;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new UpsertStep(commandTarget.copy(), initialFilter.copy(), ctx, profilingEnabled);
  }
}
