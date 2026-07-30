package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.DefaultGraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Graph;

/**
 * Walker-owned {@link UnionForkHost}: keeps the parent {@link Traversal.Admin} and step cursor
 * private so {@link UnionStepRecogniser} never receives them.
 */
final class UnionForkHostImpl implements UnionForkHost {

  private final Traversal.Admin<?, ?> parent;
  private final StepStreamCursor cursor;
  private final List<?> steps;
  private final WalkerContext ctx;

  UnionForkHostImpl(
      @Nonnull Traversal.Admin<?, ?> parent,
      @Nonnull StepStreamCursor cursor,
      @Nonnull WalkerContext ctx) {
    this.parent = parent;
    this.cursor = cursor;
    this.steps = parent.getSteps();
    this.ctx = ctx;
  }

  @Nonnull
  @Override
  public List<Step<?, ?>> recognisedPrefixSteps() {
    // After UnionStepRecogniser.take(), position is past the union. The prefix is every step
    // strictly before that union index (position - 1).
    int unionIndex = cursor.position() - 1;
    if (unionIndex <= 0) {
      return List.of();
    }
    var prefix = new ArrayList<Step<?, ?>>(unionIndex);
    for (int i = 0; i < unionIndex; i++) {
      prefix.add((Step<?, ?>) steps.get(i));
    }
    return List.copyOf(prefix);
  }

  @Nullable @Override
  @SuppressWarnings({"rawtypes", "unchecked"})
  public GremlinToMatchTranslator.TranslationResult walkFork(
      @Nonnull List<Step<?, ?>> childSuffix) {
    var prefix = recognisedPrefixSteps();
    Graph graph = parent.getGraph().orElse(null);
    Traversal.Admin<?, ?> forked =
        graph != null ? new DefaultGraphTraversal<>(graph) : new DefaultGraphTraversal<>();
    forked.setStrategies(parent.getStrategies());
    for (Step<?, ?> step : prefix) {
      forked.addStep(step.clone());
    }
    for (Step<?, ?> step : childSuffix) {
      forked.addStep(step.clone());
    }
    return GremlinStepWalker.production().walk(forked);
  }

  @Override
  public void stashAcceptedChildren(
      @Nonnull List<MatchPlanInputs> childInputs,
      @Nonnull List<Map<Object, Object>> childInputParameters) {
    ctx.stashUnionChildren(childInputs, childInputParameters);
  }
}
