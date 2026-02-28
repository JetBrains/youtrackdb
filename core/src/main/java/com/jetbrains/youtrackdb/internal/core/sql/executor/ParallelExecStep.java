package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStreamProducer;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.MultipleExecutionStream;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Source step that executes multiple sub-plans and concatenates their result streams.
 *
 * <p>Despite the name "Parallel", execution is currently <b>sequential</b> -- sub-plans
 * are iterated one at a time via a {@link MultipleExecutionStream}. The name reflects
 * the logical parallelism: each sub-plan represents an independent OR branch or
 * subclass of a query that could, in principle, be executed concurrently.
 *
 * <p>Common uses:
 * <ul>
 *   <li>WHERE clause with OR branches, each using a different index</li>
 *   <li>Class hierarchy queries where each subclass has its own index</li>
 *   <li>Multiple input parameters as targets</li>
 * </ul>
 *
 * <pre>
 *  WHERE (a = 1) OR (b = 2)
 *    SubPlan[0]: FetchFromIndex(idx_a, key=1) -&gt; Filter
 *    SubPlan[1]: FetchFromIndex(idx_b, key=2) -&gt; Filter
 *
 *  ParallelExecStep concatenates streams:  SubPlan[0] ++ SubPlan[1]
 *  (followed by DistinctExecutionStep to deduplicate overlapping results)
 * </pre>
 *
 * @see SelectExecutionPlanner#handleClassAsTargetWithIndexedFunction
 * @see SelectExecutionPlanner#handleClassAsTargetWithIndex
 */
public class ParallelExecStep extends AbstractExecutionStep {

  /** The sub-plans to execute (one per OR-branch, subclass, or input param). */
  private final List<InternalExecutionPlan> subExecutionPlans;

  public ParallelExecStep(
      List<InternalExecutionPlan> subExecutionPlans,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.subExecutionPlans = subExecutionPlans;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // Drain predecessor for side effects before executing sub-plans.
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var stepsIter = subExecutionPlans;

    // Adapts the sub-plan list into a producer for MultipleExecutionStream (lazy sequential concatenation).
    var res =
        new ExecutionStreamProducer() {
          private final Iterator<InternalExecutionPlan> iter = stepsIter.iterator();

          @Override
          public ExecutionStream next(CommandContext ctx) {
            var step = iter.next();
            return step.start();
          }

          @Override
          public boolean hasNext(CommandContext ctx) {
            return iter.hasNext();
          }

          @Override
          public void close(CommandContext ctx) {
          }
        };

    return new MultipleExecutionStream(res);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = new StringBuilder();
    var ind = ExecutionStepInternal.getIndent(depth, indent);

    var blockSizes = new int[subExecutionPlans.size()];

    for (var i = 0; i < subExecutionPlans.size(); i++) {
      var currentPlan = subExecutionPlans.get(subExecutionPlans.size() - 1 - i);
      var partial = currentPlan.prettyPrint(0, indent);

      var partials = partial.split("\n", -1);
      blockSizes[subExecutionPlans.size() - 1 - i] = partials.length + 2;
      result.insert(0, "+-------------------------\n");
      for (var j = 0; j < partials.length; j++) {
        var p = partials[partials.length - 1 - j];
        if (!result.isEmpty()) {
          result.insert(0, appendPipe(p) + "\n");
        } else {
          result = new StringBuilder(appendPipe(p));
        }
      }
      result.insert(0, "+-------------------------\n");
    }
    result = new StringBuilder(addArrows(result.toString(), blockSizes));
    result.append(foot(blockSizes));
    result.insert(0, ind);
    result = new StringBuilder(result.toString().replaceAll("\n", "\n" + ind));
    result.insert(0, head(depth, indent) + "\n");
    return result.toString();
  }

  /**
   * Renders ASCII connector lines (arrows, bars, junctions) to the left of each
   * sub-plan block. See {@link CartesianProductStep#addArrows} for the rendering
   * algorithm -- the logic is identical.
   */
  private String addArrows(String input, int[] blockSizes) {
    var result = new StringBuilder();
    var rows = input.split("\n", -1);
    var rowNum = 0;
    for (var block = 0; block < blockSizes.length; block++) {
      var blockSize = blockSizes[block];
      for (var subRow = 0; subRow < blockSize; subRow++) {
        for (var col = 0; col < blockSizes.length * 3; col++) {
          if (isHorizontalRow(col, subRow, block, blockSize)) {
            result.append("-");
          } else if (isPlus(col, subRow, block, blockSize)) {
            result.append("+");
          } else if (isVerticalRow(col, subRow, block, blockSize)) {
            result.append("|");
          } else {
            result.append(" ");
          }
        }
        result.append(rows[rowNum]);
        result.append("\n");
        rowNum++;
      }
    }

    return result.toString();
  }

  /** Returns true if position (col, subRow) should render a horizontal dash "-". */
  private boolean isHorizontalRow(int col, int subRow, int block, int blockSize) {
    if (col < block * 3 + 2) {
      return false;
    }
    return subRow == blockSize / 2;
  }

  /** Returns true if position (col, subRow) should render a "+" junction. */
  private boolean isPlus(int col, int subRow, int block, int blockSize) {
    if (col == block * 3 + 1) {
      return subRow == blockSize / 2;
    }
    return false;
  }

  /** Returns true if position (col, subRow) should render a vertical bar "|". */
  private boolean isVerticalRow(int col, int subRow, int block, int blockSize) {
    if (col == block * 3 + 1) {
      return subRow > blockSize / 2;
    } else {
      return col < block * 3 + 1 && col % 3 == 1;
    }
  }

  private String head(int depth, int indent) {
    var ind = ExecutionStepInternal.getIndent(depth, indent);
    return ind + "+ PARALLEL";
  }

  private String foot(int[] blockSizes) {
    return " V ".repeat(blockSizes.length);
  }

  private String appendPipe(String p) {
    return "| " + p;
  }

  @Override
  public List<ExecutionPlan> getSubExecutionPlans() {
    //noinspection unchecked,rawtypes
    return (List) subExecutionPlans;
  }

  /** Cacheable only if every sub-plan is cacheable. */
  @Override
  public boolean canBeCached() {
    for (var plan : subExecutionPlans) {
      if (!plan.canBeCached()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ParallelExecStep(
        subExecutionPlans.stream().map(x -> x.copy(ctx)).collect(Collectors.toList()),
        ctx,
        profilingEnabled);
  }
}
