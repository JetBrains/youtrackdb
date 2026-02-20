package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Source step that computes the Cartesian product (cross join) of multiple sub-plans.
 *
 * <p>Each sub-plan produces a set of rows, and this step generates every possible
 * combination by crossing all sets. For N sub-plans producing R1, R2, ..., RN rows,
 * the output is R1 * R2 * ... * RN rows, each containing the merged properties from
 * one row of each sub-plan.
 *
 * <pre>
 *  SubPlan[0] produces: [{a:1}, {a:2}]
 *  SubPlan[1] produces: [{b:X}, {b:Y}]
 *
 *  Cartesian product:
 *    {a:1, b:X}, {a:1, b:Y}, {a:2, b:X}, {a:2, b:Y}
 * </pre>
 *
 * <p>Used internally for multi-target queries that require cross-joining.
 *
 * <pre>
 *  SQL: SELECT a.name, b.city FROM Person a, Address b
 *
 *  Stream composition (nested flatMap):
 *    SubPlan[0].stream() ──flatMap──&gt; SubPlan[1].stream()
 *                                        |
 *                                     ──map──&gt; produceResult()  (merge properties)
 * </pre>
 */
public class CartesianProductStep extends AbstractExecutionStep {

  /** The sub-plans whose result sets are cross-joined. */
  private final List<InternalExecutionPlan> subPlans = new ArrayList<>();

  public CartesianProductStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    // Drain predecessor for side effects before computing the Cartesian product.
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    // Build the Cartesian product lazily using Java streams. The productTuple
    // array is reused across all combinations -- each sub-plan's map() fills
    // its position, and the final map() merges all positions into one result.
    //
    // IMPORTANT: productTuple is a shared mutable array. This is safe because the
    // stream is consumed sequentially -- flatMap() fully exhausts one combination
    // before advancing to the next, so each position is written before being read.
    Stream<Result[]> stream = null;
    var productTuple = new Result[this.subPlans.size()];

    for (var i = 0; i < this.subPlans.size(); i++) {
      var ep = this.subPlans.get(i);
      final var pos = i;
      if (stream == null) {
        var es = ep.start();
        stream =
            es.stream(ctx)
                .map(
                    (value) -> {
                      productTuple[pos] = value;
                      return productTuple;
                    })
                .onClose(() -> es.close(ctx));
      } else {
        stream =
            stream.flatMap(
                (val) -> {
                  var es = ep.start();
                  return es.stream(ctx)
                      .map(
                          (value) -> {
                            val[pos] = value;
                            return val;
                          })
                      .onClose(() -> es.close(ctx));
                });
      }
    }
    assert stream != null;
    var db = ctx.getDatabaseSession();
    var finalStream = stream.map(path -> produceResult(db, path));
    return ExecutionStream.resultIterator(finalStream.iterator())
        .onClose((context) -> finalStream.close());
  }

  private static Result produceResult(DatabaseSessionEmbedded db, Result[] path) {

    var nextRecord = new ResultInternal(db);

    for (var res : path) {
      for (var s : res.getPropertyNames()) {
        nextRecord.setProperty(s, res.getProperty(s));
      }
    }
    return nextRecord;
  }

  public void addSubPlan(InternalExecutionPlan subPlan) {
    this.subPlans.add(subPlan);
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var result = new StringBuilder();
    var ind = ExecutionStepInternal.getIndent(depth, indent);

    var blockSizes = new int[subPlans.size()];

    for (var i = 0; i < subPlans.size(); i++) {
      var currentPlan = subPlans.get(subPlans.size() - 1 - i);
      var partial = currentPlan.prettyPrint(0, indent);

      var partials = partial.split("\n", -1);
      blockSizes[subPlans.size() - 1 - i] = partials.length + 2;
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
   * Renders the tree-style ASCII connector lines (horizontal arrows, vertical bars,
   * and "+" joints) to the left of each sub-plan block. Each sub-plan gets a column
   * of connectors at position {@code block * 3 + 1}, with a horizontal arrow at the
   * block's vertical midpoint linking it to the main pipeline.
   *
   * <pre>
   *     |  +-------------------------
   *  +--+--| sub-plan 0 steps ...
   *     |  +-------------------------
   *     +-------------------------
   *     | sub-plan 1 steps ...
   *     +-------------------------
   *      V  V
   * </pre>
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
    var result = ind + "+ CARTESIAN PRODUCT";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  private String foot(int[] blockSizes) {
    return " V ".repeat(blockSizes.length);
  }

  private String appendPipe(String p) {
    return "| " + p;
  }

  /** Cacheable: sub-plans are deep-copied per execution via {@link #copy}. */
  @Override
  public boolean canBeCached() {
    for (var p : this.subPlans) {
      if (!p.canBeCached()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    var copy = new CartesianProductStep(ctx, profilingEnabled);
    for (var p : this.subPlans) {
      copy.addSubPlan(p.copy(ctx));
    }

    return copy;
  }
}
