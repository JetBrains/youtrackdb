package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ResultSetEdgeTraverser;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFieldMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMultiMatchPathItem;

/**
 *
 */
public class MatchStep extends AbstractExecutionStep {

  protected final EdgeTraversal edge;

  public MatchStep(CommandContext context, EdgeTraversal edge, boolean profilingEnabled) {
    super(context, profilingEnabled);
    this.edge = edge;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    var resultSet = prev.start(ctx);
    return resultSet.flatMap(this::createNextResultSet);
  }

  public ExecutionStream createNextResultSet(Result lastUpstreamRecord, CommandContext ctx) {
    var trav = createTraverser(lastUpstreamRecord);
    return new ResultSetEdgeTraverser(trav);
  }

  protected MatchEdgeTraverser createTraverser(Result lastUpstreamRecord) {
    if (edge.edge.item instanceof SQLMultiMatchPathItem) {
      return new MatchMultiEdgeTraverser(lastUpstreamRecord, edge);
    } else if (edge.edge.item instanceof SQLFieldMatchPathItem) {
      return new MatchFieldTraverser(lastUpstreamRecord, edge);
    } else if (edge.out) {
      return new MatchEdgeTraverser(lastUpstreamRecord, edge);
    } else {
      return new MatchReverseEdgeTraverser(lastUpstreamRecord, edge);
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ MATCH ");
    if (edge.out) {
      result.append("     ---->\n");
    } else {
      result.append("     <----\n");
    }
    result.append(spaces);
    result.append("  ");
    result.append("{").append(edge.edge.out.alias).append("}");
    if (edge.edge.item instanceof SQLFieldMatchPathItem) {
      result.append(".");
      result.append(((SQLFieldMatchPathItem) edge.edge.item).getField());
    } else {
      result.append(edge.edge.item.getMethod());
    }
    result.append("{").append(edge.edge.in.alias).append("}");
    return result.toString();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new MatchStep(ctx, edge.copy(), profilingEnabled);
  }
}
