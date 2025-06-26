package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 *
 */
public class GetValueFromIndexEntryStep extends AbstractExecutionStep {

  private final IntArrayList filterCollectionIds;

  /**
   * @param ctx              the execution context
   * @param filterCollectionIds only extract values from these collections. Pass null if no filtering is
   *                         needed
   * @param profilingEnabled enable profiling
   */
  public GetValueFromIndexEntryStep(
      CommandContext ctx, IntArrayList filterCollectionIds, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.filterCollectionIds = filterCollectionIds;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {

    if (prev == null) {
      throw new IllegalStateException("filter step requires a previous step");
    }
    var resultSet = prev.start(ctx);
    return resultSet.filter(this::filterMap);
  }

  @Nullable
  private Result filterMap(Result result, CommandContext ctx) {
    var finalVal = result.getProperty("rid");
    if (filterCollectionIds != null) {
      if (!(finalVal instanceof Identifiable)) {
        return null;
      }
      var rid = ((Identifiable) finalVal).getIdentity();
      var found = false;
      for (int filterCollectionId : filterCollectionIds) {
        if (rid.getCollectionId() < 0 || filterCollectionId == rid.getCollectionId()) {
          found = true;
          break;
        }
      }
      if (!found) {
        return null;
      }
    }
    if (finalVal instanceof Identifiable) {
      return new ResultInternal(ctx.getDatabaseSession(), (Identifiable) finalVal);

    } else if (finalVal instanceof Result) {
      return (Result) finalVal;
    }
    return null;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ EXTRACT VALUE FROM INDEX ENTRY";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    if (filterCollectionIds != null) {
      result += "\n";
      result += spaces;
      result += "  filtering collections [";
      result +=
          filterCollectionIds
              .intStream()
              .boxed()
              .map(String::valueOf)
              .collect(Collectors.joining(","));
      result += "]";
    }
    return result;
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new GetValueFromIndexEntryStep(ctx, this.filterCollectionIds, this.profilingEnabled);
  }
}
