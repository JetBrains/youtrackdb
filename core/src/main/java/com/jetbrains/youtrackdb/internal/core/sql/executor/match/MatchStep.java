package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ResultSetEdgeTraverser;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFieldMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMultiMatchPathItem;

/**
 * Execution step that traverses a single edge in the MATCH pattern graph.
 * <p>
 * For each upstream result row (which already contains the previously matched aliases),
 * this step:
 * <p>
 * 1. Creates an appropriate {@link MatchEdgeTraverser} subclass based on the edge type.
 * 2. Executes the traversal, producing zero or more downstream result rows that include
 *    the newly matched alias.
 * <p>
 * ### Traverser selection
 * <p>
 * | Edge AST type                       | Traversal direction | Traverser class                      |
 * |-------------------------------------|---------------------|--------------------------------------|
 * | {@link SQLMultiMatchPathItem}        | —                   | {@link MatchMultiEdgeTraverser}       |
 * | {@link SQLFieldMatchPathItem}        | —                   | {@link MatchFieldTraverser}           |
 * | Any other, forward (`edge.out=true`) | forward             | {@link MatchEdgeTraverser}            |
 * | Any other, reverse (`edge.out=false`)| reverse             | {@link MatchReverseEdgeTraverser}     |
 * <p>
 * ### Pipeline position
 * <p>
 * <pre>
 * For each edge in the schedule:
 *
 *   MatchFirstStep                  MatchStep                     MatchStep
 *   +-------------------+          +---------------------+       +---------------------+
 *   | Scan/Prefetch     |          | For each row:       |       | For each row:       |
 *   | records for       | stream   |  createTraverser()  | stream|  createTraverser()  |
 *   | first alias       | ──of──→  |  traverse edge      | ──of→ |  traverse edge      |
 *   | Wrap as           | rows     |  filter + join      | rows  |  filter + join      |
 *   | {alias: record}   | {a:rec}  |  emit new row       |{a,b}  |  emit new row       |
 *   +-------------------+          +---------------------+       +---------------------+
 *                                         │
 *                           (internally per row)
 *                                         ▼
 *                               ResultSetEdgeTraverser
 *                                ┌───────────────────┐
 *                                │ MatchEdgeTraverser │
 *                                │  .init()           │
 *                                │  .traverseEdge()   │
 *                                │  .filter/join      │
 *                                │  skip nulls        │
 *                                │  set $matched      │
 *                                └───────────────────┘
 * </pre>
 *
 * @see MatchEdgeTraverser
 * @see OptionalMatchStep
 * @see MatchExecutionPlanner
 */
public class MatchStep extends AbstractExecutionStep {

  /** The scheduled edge traversal this step will execute. */
  protected final EdgeTraversal edge;

  /**
   * @param context          the command execution context
   * @param edge             the edge to traverse (includes direction and constraints)
   * @param profilingEnabled whether to collect execution statistics
   */
  public MatchStep(CommandContext context, EdgeTraversal edge, boolean profilingEnabled) {
    super(context, profilingEnabled);
    assert edge != null : "edge traversal must not be null";
    this.edge = edge;
  }

  /**
   * For each upstream row, flat-maps through the edge traverser to produce all matching
   * downstream rows. Rows that don't match the edge's filter are silently dropped.
   */
  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    var resultSet = prev.start(ctx);
    return resultSet.flatMap(this::createNextResultSet);
  }

  /**
   * Wraps the traverser for a single upstream record into an {@link ExecutionStream}
   * via {@link ResultSetEdgeTraverser}.
   */
  public ExecutionStream createNextResultSet(Result lastUpstreamRecord, CommandContext ctx) {
    var trav = createTraverser(lastUpstreamRecord);
    return new ResultSetEdgeTraverser(trav);
  }

  /**
   * Factory method that selects the correct traverser subclass based on the edge's AST
   * type and scheduled direction. Overridden by {@link OptionalMatchStep} to produce
   * {@link OptionalMatchEdgeTraverser} instances.
   */
  protected MatchEdgeTraverser createTraverser(Result lastUpstreamRecord) {
    if (edge.edge.item instanceof SQLMultiMatchPathItem) {
      return new MatchMultiEdgeTraverser(lastUpstreamRecord, edge);
    } else if (edge.edge.item instanceof SQLFieldMatchPathItem) {
      return new MatchFieldTraverser(lastUpstreamRecord, edge);
    } else if (edge.out) {
      // edge.out (boolean) = forward direction; not to confuse with edge.edge.out (PatternNode)
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
