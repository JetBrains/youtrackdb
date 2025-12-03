package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 *
 */
public class DistinctExecutionStep extends AbstractExecutionStep {

  private final long maxElementsAllowed;

  public DistinctExecutionStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    DatabaseSession session = ctx == null ? null : ctx.getDatabaseSession();

    maxElementsAllowed =
        session == null
            ? GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsLong()
            : session.getConfiguration()
                .getValueAsLong(GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    var resultSet = prev.start(ctx);
    Set<Result> pastItems = new HashSet<>();
    var pastRids = new RidSet();

    return resultSet.filter((result, context) -> filterMap(result, pastRids, pastItems,
        ctx.getDatabaseSession()));
  }

  @Nullable
  private Result filterMap(Result result, Set<RID> pastRids, Set<Result> pastItems,
      DatabaseSessionInternal session) {
    if (alreadyVisited(result, pastRids, pastItems)) {
      return null;
    } else {
      markAsVisited(result, pastRids, pastItems, session);
      return result;
    }
  }

  private void markAsVisited(Result nextValue, Set<RID> pastRids, Set<Result> pastItems,
      DatabaseSessionInternal session) {
    if (nextValue.isEntity()) {
      var identity = nextValue.asEntityOrNull().getIdentity();
      var collection = identity.getCollectionId();
      var pos = identity.getCollectionPosition();
      if (collection >= 0 && pos >= 0) {
        pastRids.add(identity);
        return;
      }
    }
    pastItems.add(nextValue);
    if (maxElementsAllowed > 0 && maxElementsAllowed < pastItems.size()) {
      pastItems.clear();
      throw new CommandExecutionException(session,
          "Limit of allowed entities for in-heap DISTINCT in a single query exceeded ("
              + maxElementsAllowed
              + ") . You can set "
              + GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getKey()
              + " to increase this limit");
    }
  }

  private static boolean alreadyVisited(Result nextValue, Set<RID> pastRids,
      Set<Result> pastItems) {
    if (nextValue.isEntity()) {
      var identity = nextValue.asEntityOrNull().getIdentity();
      var collection = identity.getCollectionId();
      var pos = identity.getCollectionPosition();
      if (collection >= 0 && pos >= 0) {
        return pastRids.contains(identity);
      }
    }
    return pastItems.contains(nextValue);
  }

  @Override
  public void sendTimeout() {
  }

  @Override
  public void close() {
    if (prev != null) {
      prev.close();
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = ExecutionStepInternal.getIndent(depth, indent) + "+ DISTINCT";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new DistinctExecutionStep(ctx, profilingEnabled);
  }
}
