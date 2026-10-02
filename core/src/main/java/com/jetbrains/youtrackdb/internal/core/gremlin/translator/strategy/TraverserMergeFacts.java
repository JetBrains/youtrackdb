package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.OrderedHopStage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.PathProcessor;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FlatMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ScalarMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectOneStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy;

/** Resolved from the final traversal, shared by translation and its shape key. */
record TraverserMergeFacts(boolean sackPresent, boolean sackGated, boolean sackSplitter,
    int preOrderSplits, boolean postOrderProjectionSplit,
    List<String> labelsAtOrder, List<String> labelsAtAnyBarrier,
    Map<Step<?, ?>, List<String>> labelsAtBarriers) {

  static TraverserMergeFacts from(Traversal.Admin<?, ?> traversal) {
    // Shape extraction can precede decoration. Installing the supplier does not invoke it.
    traversal.getStrategies().getStrategy(SackStrategy.class)
        .ifPresent(strategy -> strategy.apply(traversal));
    var effects = traversal.getSideEffects();
    boolean present = effects.getSackInitialValue() != null;
    boolean gated = present && effects.getSackMerger() == null;
    boolean splitter = effects.getSackSplitter() != null;
    int preOrderSplits = 0;
    boolean postOrderProjectionSplit = false;
    var labels = new LinkedHashSet<String>();
    List<String> atOrder = List.of();
    var atBarriers = new IdentityHashMap<Step<?, ?>, List<String>>();
    var atAnyBarrier = new LinkedHashSet<String>();
    boolean passedOrder = false;
    for (var step : traversal.getSteps()) {
      // PathProcessor retracts its incoming path. AbstractStep adds this step's labels only
      // afterwards, so labels attached directly to order survive its own keepLabels mask.
      if (step instanceof PathProcessor processor && processor.getKeepLabels() != null) {
        labels.retainAll(processor.getKeepLabels());
      }
      labels.addAll(GremlinStepLabels.userLabels(step));
      if (step instanceof OrderGlobalStep) {
        // Labels on order() are added to native traversers before the ordered barrier emits.
        atOrder = List.copyOf(labels);
        passedOrder = true;
      } else if (!passedOrder && splitsTraverser(step)) {
        preOrderSplits++;
      } else if (passedOrder && (step instanceof SelectOneStep<?, ?>
          || step instanceof SelectStep<?, ?>)) {
        postOrderProjectionSplit = true;
      }
      if (passedOrder && step instanceof NoOpBarrierStep<?>) {
        atBarriers.put(step, List.copyOf(labels));
        atAnyBarrier.addAll(labels);
      }
    }
    return new TraverserMergeFacts(present, gated, splitter, preOrderSplits,
        postOrderProjectionSplit, atOrder, List.copyOf(atAnyBarrier), atBarriers);
  }

  private static boolean splitsTraverser(Step<?, ?> step) {
    // YTDB VertexStep uses FlatMapStep's split path. ScalarMapStep and both Select steps
    // also split after projection. Filters and range do not split.
    return step instanceof FlatMapStep<?, ?> || step instanceof ScalarMapStep<?, ?>
        || step instanceof SelectOneStep<?, ?> || step instanceof SelectStep<?, ?>;
  }

  String sackToken() {
    return (!sackPresent ? "none" : sackGated ? "gated" : "merger")
        + (sackSplitter ? ":split" : ":share");
  }

  OrderedHopStage.SourceMerge sourceMergeFor(RecognitionContext ctx, String sourceAlias) {
    return new OrderedHopStage.SourceMerge(keyFor(ctx, sourceAlias), sackGated, preOrderSplits);
  }

  /** A path survives source merging only when every live label has the same bound element. */
  OrderedHopStage.MergeKey keyFor(RecognitionContext ctx, String sourceAlias) {
    return labelsAtOrder.stream()
        .anyMatch(label -> !sourceAlias.equals(ctx.resolveUserLabel(label)))
            ? OrderedHopStage.MergeKey.ELEMENT_AND_PATH : OrderedHopStage.MergeKey.ELEMENT;
  }

  OrderedHopStage.Barrier barrierFor(
      RecognitionContext ctx, NoOpBarrierStep<?> step, String sourceAlias) {
    // A select can retract earlier labels between order and this barrier. Indexes refer to the
    // labelled-only carrier, never to the unlabelled sorted source column at position zero.
    var aliases = liveAliasColumns(ctx, sourceAlias);
    var indexes = new LinkedHashSet<Integer>();
    for (String label : labelsAtBarriers.getOrDefault(step, labelsAtOrder)) {
      String alias = ctx.resolveUserLabel(label);
      if (alias != null) {
        int index = aliases.subList(1, aliases.size()).indexOf(alias);
        if (index >= 0) {
          indexes.add(index);
        }
      }
    }
    return new OrderedHopStage.Barrier(step.getMaxBarrierSize(),
        indexes.isEmpty() ? OrderedHopStage.MergeKey.ELEMENT
            : OrderedHopStage.MergeKey.ELEMENT_AND_PATH,
        List.copyOf(indexes), sackGated);
  }

  List<String> liveAliasColumns(RecognitionContext ctx, String sourceAlias) {
    var aliases = new ArrayList<String>();
    aliases.add(sourceAlias);
    var carrierLabels = new LinkedHashSet<>(labelsAtOrder);
    // A barrier after order can attach as(...) to the sorted source. Its label is not live at
    // order, but is live when the later hop's barrier compares paths. Carry that source identity.
    carrierLabels.addAll(labelsAtAnyBarrier);
    for (String label : carrierLabels) {
      String alias = ctx.resolveUserLabel(label);
      if (alias == null) {
        throw new IllegalStateException("Live ordered label has no MATCH alias: " + label);
      }
      // Labels on a post-hop barrier refer to the neighbour, which is expanded after MATCH.
      // Its payload is already in the barrier merge key, so it needs no source-row column.
      if (alias.equals(sourceAlias) || labelsAtOrder.contains(label)) {
        if (!aliases.subList(1, aliases.size()).contains(alias)) {
          aliases.add(alias);
        }
      }
    }
    return List.copyOf(aliases);
  }

  String pathToken() {
    return String.join(",", labelsAtOrder);
  }
}
