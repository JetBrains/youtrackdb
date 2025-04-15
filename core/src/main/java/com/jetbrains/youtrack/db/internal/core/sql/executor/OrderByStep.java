package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLOrderBy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 **/
public class OrderByStep extends AbstractExecutionStep {

  private final SQLOrderBy orderBy;
  private final long timeoutMillis;
  private Integer maxResults;

  public OrderByStep(
      SQLOrderBy orderBy, CommandContext ctx, long timeoutMillis, boolean profilingEnabled) {
    this(orderBy, null, ctx, timeoutMillis, profilingEnabled);
  }

  public OrderByStep(
      SQLOrderBy orderBy,
      Integer maxResults,
      CommandContext ctx,
      long timeoutMillis,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.orderBy = orderBy;
    this.maxResults = maxResults;
    if (this.maxResults != null && this.maxResults < 0) {
      this.maxResults = null;
    }
    this.timeoutMillis = timeoutMillis;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    List<Result> results;

    if (prev != null) {
      results = init(prev, ctx);
    } else {
      results = Collections.emptyList();
    }

    return ExecutionStream.resultIterator(results.iterator());
  }

  private List<Result> init(ExecutionStepInternal p, CommandContext ctx) {
    var timeoutBegin = System.currentTimeMillis();
    List<Result> cachedResult = new ArrayList<>();
    final var maxElementsAllowed =
        GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getValueAsLong();
    var sorted = true;
    var lastBatch = p.start(ctx);
    while (lastBatch.hasNext(ctx)) {
      if (timeoutMillis > 0 && timeoutBegin + timeoutMillis < System.currentTimeMillis()) {
        sendTimeout();
      }

      var item = lastBatch.next(ctx);
      cachedResult.add(item);
      if (maxElementsAllowed >= 0 && maxElementsAllowed < cachedResult.size()) {
        throw new CommandExecutionException(ctx.getDatabaseSession(),
            "Limit of allowed entities for in-heap ORDER BY in a single query exceeded ("
                + maxElementsAllowed
                + ") . You can set "
                + GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP.getKey()
                + " to increase this limit");
      }
      sorted = false;
      // compact, only at twice as the buffer, to avoid to do it at each add
      if (this.maxResults != null) {
        var compactThreshold = 2L * maxResults;
        if (compactThreshold < cachedResult.size()) {
          cachedResult.sort((a, b) -> orderBy.compare(a, b, ctx));
          cachedResult = new ArrayList<>(cachedResult.subList(0, maxResults));
          sorted = true;
        }
      }
    }
    lastBatch.close(ctx);
    // compact at each batch, if needed
    if (!sorted && this.maxResults != null && maxResults < cachedResult.size()) {
      cachedResult.sort((a, b) -> orderBy.compare(a, b, ctx));
      cachedResult = new ArrayList<>(cachedResult.subList(0, maxResults));
      sorted = true;
    }
    if (!sorted) {
      cachedResult.sort((a, b) -> orderBy.compare(a, b, ctx));
    }
    return cachedResult;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = ExecutionStepInternal.getIndent(depth, indent) + "+ " + orderBy;
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    result += (maxResults != null ? "\n  (buffer size: " + maxResults + ")" : "");
    return result;
  }
}
