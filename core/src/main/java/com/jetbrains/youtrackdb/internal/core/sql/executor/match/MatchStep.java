package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidFilterDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
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
 *                               MatchEdgeTraverser
 *                                ┌───────────────────┐
 *                                │  implements        │
 *                                │  ExecutionStream   │
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
    assert MatchAssertions.checkNotNull(edge, "edge traversal");
    this.edge = edge;
  }

  /**
   * For each upstream row, flat-maps through the edge traverser to produce all matching
   * downstream rows. Rows that don't match the edge's filter are silently dropped.
   */
  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert MatchAssertions.checkNotNull(prev, "previous step");

    var resultSet = prev.start(ctx);
    return resultSet.flatMap(this::createNextResultSet);
  }

  /**
   * Creates an {@link ExecutionStream} for a single upstream record by instantiating the
   * appropriate {@link MatchEdgeTraverser} subclass.
   */
  public ExecutionStream createNextResultSet(Result lastUpstreamRecord, CommandContext ctx) {
    return createTraverser(lastUpstreamRecord);
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
  public boolean canBeCached() {
    return true;
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
    appendIntersectionDescriptor(result);
    appendPreFilterStats(result);
    return result.toString();
  }

  /**
   * Appends a human-readable description of the intersection descriptor (if present)
   * to the EXPLAIN output. This makes adjacency list intersection optimizations
   * visible in execution plans.
   */
  void appendIntersectionDescriptor(StringBuilder result) {
    appendDescriptor(result, edge.getIntersectionDescriptor());
  }

  private void appendDescriptor(StringBuilder result, RidFilterDescriptor descriptor) {
    if (descriptor instanceof RidFilterDescriptor.DirectRid) {
      result.append(" (intersection: direct-rid)");
    } else if (descriptor instanceof RidFilterDescriptor.EdgeRidLookup lookup) {
      result.append(" (intersection: ")
          .append(lookup.traversalDirection()).append("('")
          .append(lookup.edgeClassName()).append("'))");
    } else if (descriptor instanceof RidFilterDescriptor.IndexLookup indexLookup) {
      result.append(" (intersection: index ")
          .append(indexLookup.indexDescriptor().getIndex().getName());
      // Show selectivity: use cached value (PROFILE) or compute lazily (EXPLAIN)
      double selectivity = edge.getIndexLookupSelectivity();
      if (Double.isNaN(selectivity) && ctx != null) {
        selectivity = indexLookup.indexDescriptor().estimateSelectivity(ctx);
      }
      if (!Double.isNaN(selectivity) && selectivity >= 0) {
        result.append(String.format(" selectivity=%.4f", selectivity));
      }
      result.append(")");
    } else if (descriptor instanceof RidFilterDescriptor.Composite composite) {
      for (var inner : composite.descriptors()) {
        appendDescriptor(result, inner);
      }
    }
  }

  /**
   * Appends pre-filter PROFILE statistics. Gated behind
   * {@code profilingEnabled} (T6) to avoid false "NEVER APPLIED"
   * diagnostics for EXPLAIN-only queries.
   */
  void appendPreFilterStats(StringBuilder result) {
    if (!profilingEnabled || edge.getIntersectionDescriptor() == null) {
      return;
    }
    result.append("\n");
    int applied = edge.getPreFilterAppliedCount();
    int skipped = edge.getPreFilterSkippedCount();
    long probed = edge.getPreFilterTotalProbed();
    long filtered = edge.getPreFilterTotalFiltered();

    if (applied == 0 && probed == 0) {
      // Pre-filter never activated — show diagnostic
      var reason = edge.getLastSkipReason();
      result.append("    pre-filter: NEVER APPLIED");
      if (reason != PreFilterSkipReason.NONE) {
        result.append(" (reason: ").append(reason);
        appendSkipDiagnostic(result, reason);
        result.append(")");
      }
    } else {
      result.append("    pre-filter: applied=").append(applied)
          .append(" skipped=").append(skipped);
      if (edge.getPreFilterRidSetSize() > 0) {
        result.append(" ridSetSize=").append(edge.getPreFilterRidSetSize());
      }
      if (edge.getPreFilterBuildTimeNanos() > 0) {
        result.append(String.format(" buildTime=%.3fms",
            edge.getPreFilterBuildTimeNanos() / 1_000_000.0));
      }
      if (probed > 0) {
        double rate = (double) filtered / probed;
        result.append(String.format(" filterRate=%.1f%%", rate * 100));
      }
    }
  }

  private void appendSkipDiagnostic(
      StringBuilder result, PreFilterSkipReason reason) {
    switch (reason) {
      case CAP_EXCEEDED -> result.append(", cap=")
          .append(TraversalPreFilterHelper.maxRidSetSize());
      case SELECTIVITY_TOO_LOW -> result.append(", threshold=")
          .append(TraversalPreFilterHelper.indexLookupMaxSelectivity());
      case OVERLAP_RATIO_TOO_HIGH -> result.append(", threshold=")
          .append(TraversalPreFilterHelper.edgeLookupMaxRatio());
      default -> {
        /* no extra diagnostic */ }
    }
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new MatchStep(ctx, edge.copy(), profilingEnabled);
  }
}
