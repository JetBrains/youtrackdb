package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFieldMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;

/**
 * Edge traverser for **field access** path items in a MATCH pattern.
 * <p>
 * This traverser handles the `{...}.fieldName{...}` syntax, where instead of calling
 * a graph traversal method (`out()`, `in()`, etc.), the pattern accesses a named
 * property or expression on the current record. The AST representation is
 * {@link SQLFieldMatchPathItem}.
 * <p>
 * ### Example
 * <p>
 * ```sql
 * MATCH {class: Person, as: p}.address{as: addr}
 * ```
 * <p>
 * Here, `.address` is a field access — the traverser evaluates the expression `address`
 * on the current record and treats the result as the next matched node.
 *
 * @see MatchEdgeTraverser
 * @see SQLFieldMatchPathItem
 */
public class MatchFieldTraverser extends MatchEdgeTraverser {

  public MatchFieldTraverser(Result lastUpstreamRecord, EdgeTraversal edge) {
    super(lastUpstreamRecord, edge);
  }

  public MatchFieldTraverser(Result lastUpstreamRecord, SQLMatchPathItem item) {
    super(lastUpstreamRecord, item);
  }

  /**
   * Evaluates the field expression from the {@link SQLFieldMatchPathItem} against the
   * starting point record, instead of calling a traversal method. The `$current`
   * context variable is temporarily set so expressions can reference it.
   */
  @Override
  protected ExecutionStream traversePatternEdge(
      Result startingPoint, CommandContext iCommandContext) {
    var prevCurrent = iCommandContext.getSystemVariable(CommandContext.VAR_CURRENT);
    iCommandContext.setSystemVariable(CommandContext.VAR_CURRENT, startingPoint);
    Object qR;
    try {
      // Evaluate the field/expression (e.g. "address", "name.toLowerCase()")
      qR = ((SQLFieldMatchPathItem) this.item).getExp().execute(startingPoint, iCommandContext);
    } finally {
      iCommandContext.setSystemVariable(CommandContext.VAR_CURRENT, prevCurrent);
    }

    return toExecutionStream(qR, iCommandContext.getDatabaseSession());
  }
}
