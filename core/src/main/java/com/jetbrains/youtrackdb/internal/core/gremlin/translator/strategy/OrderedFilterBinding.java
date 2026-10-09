package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.OrderedExpandSliceListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.OrderedHopStage;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.T;

/** Rebuilds ordered-filter stages once per boundary, never on each neighbour lookup. */
final class OrderedFilterBinding {

  private OrderedFilterBinding() {
  }

  /** A cached shaping carries structural keys and labels, but no property literal or native wrapper. */
  static ResultShaping unbound(ResultShaping shaping) {
    if (!hasOrderedExpand(shaping)) {
      return shaping;
    }
    var ops = new ArrayList<ListShapingOp>();
    for (var op : shaping.listShapingOps()) {
      if (!(op instanceof OrderedExpandSliceListShapingOp expand)) {
        ops.add(op);
        continue;
      }
      var stages = new ArrayList<OrderedHopStage>();
      for (var stage : expand.stages()) {
        if (stage instanceof OrderedHopStage.Filter filter) {
          var containers = new ArrayList<HasContainer>();
          for (var container : filter.containers()) {
            // Label values are structural and already part of the shape key. Property literals
            // must never be retained in the shared template, including compiled regex patterns.
            containers.add(T.label.getAccessor().equals(container.getKey()) ? container
                : new HasContainer(container.getKey(), P.eq(null)));
          }
          stages.add(new OrderedHopStage.Filter(containers, filter.polymorphic()));
        } else {
          stages.add(stage);
        }
      }
      ops.add(new OrderedExpandSliceListShapingOp(stages));
    }
    return shaping.withListShapingOps(ops);
  }

  /** A fresh build gets new safe predicates and keeps unsafe predicates on its original path. */
  static ResultShaping fresh(ResultShaping shaping) {
    if (!hasOrderedExpand(shaping)) {
      return shaping;
    }
    var operands = new ArrayList<NativeHasOperands>();
    for (var op : shaping.listShapingOps()) {
      if (op instanceof OrderedExpandSliceListShapingOp expand) {
        for (var stage : expand.stages()) {
          if (stage instanceof OrderedHopStage.Filter filter) {
            operands.add(NativeHasOperands.capture(filter.containers()));
          }
        }
      }
    }
    return bind(shaping, operands);
  }

  /** Return null on a stage/operand disagreement, so the caller walks instead of splicing. */
  @Nullable static ResultShaping fromExtraction(
      ResultShaping shaping, GremlinShapeExtractor.Extraction extraction) {
    int orderedFilters = 0;
    for (var contribution : extraction.hasContributions()) {
      if (contribution.context().destination() == HasBindingContext.Destination.ORDERED_FILTER) {
        orderedFilters++;
      }
    }
    if (orderedFilters != extraction.nativeOperands().size()
        || (extraction.orderedExpandAbsent() && hasOrderedExpand(shaping))) {
      return null;
    }
    return bind(shaping, extraction.nativeOperands());
  }

  static boolean hasOrderedExpand(ResultShaping shaping) {
    for (var op : shaping.listShapingOps()) {
      if (op instanceof OrderedExpandSliceListShapingOp) {
        return true;
      }
    }
    return false;
  }

  @Nullable private static ResultShaping bind(ResultShaping shaping,
      List<NativeHasOperands> operands) {
    if (!hasOrderedExpand(shaping)) {
      return operands.isEmpty() ? shaping : null;
    }
    var ops = new ArrayList<ListShapingOp>();
    int index = 0;
    for (var op : shaping.listShapingOps()) {
      if (!(op instanceof OrderedExpandSliceListShapingOp expand)) {
        ops.add(op);
        continue;
      }
      var stages = new ArrayList<OrderedHopStage>();
      for (var stage : expand.stages()) {
        if (!(stage instanceof OrderedHopStage.Filter filter)) {
          stages.add(stage);
          continue;
        }
        if (index >= operands.size()) {
          return null;
        }
        var rebuilt = operands.get(index++).rebuild();
        if (rebuilt.size() != filter.containers().size()) {
          return null;
        }
        for (int i = 0; i < rebuilt.size(); i++) {
          if (!rebuilt.get(i).getKey().equals(filter.containers().get(i).getKey())) {
            return null;
          }
        }
        stages.add(new OrderedHopStage.Filter(rebuilt, filter.polymorphic()));
      }
      ops.add(new OrderedExpandSliceListShapingOp(stages));
    }
    return index == operands.size() ? shaping.withListShapingOps(ops) : null;
  }
}
