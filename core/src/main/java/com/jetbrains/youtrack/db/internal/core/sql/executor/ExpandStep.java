package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Iterator;
import java.util.Map;

/**
 * Expands a result-set. The pre-requisite is that the input element contains only one field (no
 * matter the name)
 */
public class ExpandStep extends AbstractExecutionStep {

  public ExpandStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new CommandExecutionException("Cannot expand without a target");
    }
    ExecutionStream resultSet = prev.start(ctx);
    return resultSet.flatMap(ExpandStep::nextResults);
  }

  private static ExecutionStream nextResults(Result nextAggregateItem, CommandContext ctx) {
    if (nextAggregateItem.getPropertyNames().isEmpty()) {
      return ExecutionStream.empty();
    }
    if (nextAggregateItem.getPropertyNames().size() > 1) {
      throw new IllegalStateException("Invalid EXPAND on record " + nextAggregateItem);
    }

    String propName = nextAggregateItem.getPropertyNames().iterator().next();
    Object projValue = nextAggregateItem.getProperty(propName);
    if (projValue == null) {
      return ExecutionStream.empty();
    }
    if (projValue instanceof Identifiable) {
      DBRecord rec;
      try {
        rec = ((Identifiable) projValue).getRecord();
      } catch (RecordNotFoundException rnf) {
        return ExecutionStream.empty();
      }

      ResultInternal res = new ResultInternal(ctx.getDatabase(), rec);
      return ExecutionStream.singleton(res);
    } else if (projValue instanceof Result) {
      return ExecutionStream.singleton((Result) projValue);
    } else if (projValue instanceof Iterator) {
      //noinspection unchecked
      return ExecutionStream.iterator((Iterator<Object>) projValue);
    } else if (projValue instanceof Iterable) {
      //noinspection unchecked
      return ExecutionStream.iterator(((Iterable<Object>) projValue).iterator());
    } else if (projValue instanceof Map) {
      return ExecutionStream.iterator(((Map) projValue).entrySet().iterator());
    } else {
      return ExecutionStream.empty();
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    String spaces = ExecutionStepInternal.getIndent(depth, indent);
    String result = spaces + "+ EXPAND";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }
}
