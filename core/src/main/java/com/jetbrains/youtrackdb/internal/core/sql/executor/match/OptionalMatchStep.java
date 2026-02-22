package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;

/**
 * A variant of {@link MatchStep} for edges whose **target node** is marked
 * `optional: true`.
 * <p>
 * Unlike the standard `MatchStep`, which silently drops upstream rows that produce no
 * downstream matches, this step **preserves** every upstream row by using
 * {@link OptionalMatchEdgeTraverser}. When no traversal results are found, the
 * traverser emits a sentinel {@link OptionalMatchEdgeTraverser#EMPTY_OPTIONAL} value.
 * The sentinel is later replaced with `null` by {@link RemoveEmptyOptionalsStep}.
 * <p>
 * This behaviour is analogous to a SQL `LEFT JOIN` — the left (previously matched)
 * side is always preserved.
 *
 * @see OptionalMatchEdgeTraverser
 * @see RemoveEmptyOptionalsStep
 */
public class OptionalMatchStep extends MatchStep {

  public OptionalMatchStep(CommandContext context, EdgeTraversal edge, boolean profilingEnabled) {
    super(context, edge, profilingEnabled);
  }

  /** Always produces an {@link OptionalMatchEdgeTraverser} for optional semantics. */
  @Override
  protected MatchEdgeTraverser createTraverser(Result lastUpstreamRecord) {
    return new OptionalMatchEdgeTraverser(lastUpstreamRecord, edge);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ OPTIONAL MATCH ");
    if (edge.out) {
      result.append(" ---->\n");
    } else {
      result.append("     <----\n");
    }
    result.append(spaces);
    result.append("  ");
    result.append("{").append(edge.edge.out.alias).append("}");
    result.append(edge.edge.item.getMethod());
    result.append("{").append(edge.edge.in.alias).append("}");
    return result.toString();
  }
}
