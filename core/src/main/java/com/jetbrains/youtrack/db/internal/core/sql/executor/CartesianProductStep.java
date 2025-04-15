package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 *
 */
public class CartesianProductStep extends AbstractExecutionStep {

  private final List<InternalExecutionPlan> subPlans = new ArrayList<>();

  public CartesianProductStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

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

  private static Result produceResult(DatabaseSessionInternal db, Result[] path) {

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

      var partials = partial.split("\n");
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

  private String addArrows(String input, int[] blockSizes) {
    var result = new StringBuilder();
    var rows = input.split("\n");
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

  private boolean isHorizontalRow(int col, int subRow, int block, int blockSize) {
    if (col < block * 3 + 2) {
      return false;
    }
    return subRow == blockSize / 2;
  }

  private boolean isPlus(int col, int subRow, int block, int blockSize) {
    if (col == block * 3 + 1) {
      return subRow == blockSize / 2;
    }
    return false;
  }

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
}
