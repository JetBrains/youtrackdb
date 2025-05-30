package com.jetbrain.youtrack.db.gremlin.internal.traversal.strategy.optimization;

import com.jetbrain.youtrack.db.gremlin.internal.traversal.step.map.YTDBClassCountStep;
import com.jetbrain.youtrack.db.gremlin.internal.traversal.step.sideeffect.YTDBGraphStep;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;

/**
 * This strategy will try to optimize the Count Step when it's possible.
 *
 * @author Enrico Risa
 * @example <pre>
 * g.V().count()                               // is replaced by YTDBClassCountStep
 * g.E().count()                               // is replaced by YTDBClassCountStep
 * g.V().hasLabel('Foo').count()               // is replaced by YTDBClassCountStep
 * g.E().hasLabel('Foo').count()               // is replaced by YTDBClassCountStep
 * <p>
 * </pre>
 */
public class YTDBGraphCountStrategy
    extends AbstractTraversalStrategy<TraversalStrategy.ProviderOptimizationStrategy>
    implements TraversalStrategy.ProviderOptimizationStrategy {

  private static final YTDBGraphCountStrategy INSTANCE = new YTDBGraphCountStrategy();

  private YTDBGraphCountStrategy() {
  }

  @Override
  public void apply(Traversal.Admin<?, ?> traversal) {
    if (!(traversal.getParent() instanceof EmptyStep) || TraversalHelper.onGraphComputer(
        traversal)) {
      return;
    }

    var steps = traversal.getSteps();
    if (steps.size() < 2) {
      return;
    }

    Step<?, ?> startStep = traversal.getStartStep();
    Step<?, ?> endStep = traversal.getEndStep();
    if (steps.size() == 2
        && startStep instanceof YTDBGraphStep<?, ?> step
        && endStep instanceof CountGlobalStep) {

      if (step.getHasContainers().size() == 1) {
        List<HasContainer> hasContainers = step.getHasContainers();
        List<String> classes =
            hasContainers.stream()
                .filter(this::isLabelFilter)
                .map(this::extractLabels)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
        if (!classes.isEmpty()) {
          TraversalHelper.removeAllSteps(traversal);
          traversal.addStep(new YTDBClassCountStep<>(traversal, classes, step.isVertexStep()));
        }
      } else if (step.getHasContainers().isEmpty()) {
        TraversalHelper.removeAllSteps(traversal);
        String baseClass = step.isVertexStep() ? "V" : "E";
        traversal.addStep(
            new YTDBClassCountStep<>(
                traversal, Collections.singletonList(baseClass), step.isVertexStep()));
      }
    }
  }

  protected boolean isLabelFilter(HasContainer f) {
    boolean labelFilter = f.getKey().equals("~label");

    BiPredicate<?, ?> predicate = f.getBiPredicate();

    if (predicate instanceof Compare) {
      return labelFilter && Compare.eq.equals(predicate);
    }
    if (predicate instanceof Contains) {
      return labelFilter && Contains.within.equals(predicate);
    }

    return false;
  }

  protected List<String> extractLabels(HasContainer f) {
    Object value = f.getValue();
    List<String> classLabels = new ArrayList<>();
    if (value instanceof List<?> list) {
      list.forEach(label -> classLabels.add((String) label));
    } else {
      classLabels.add((String) value);
    }
    return classLabels;
  }

  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPrior() {
    return Collections.singleton(YTDBGraphStepStrategy.class);
  }

  public static YTDBGraphCountStrategy instance() {
    return INSTANCE;
  }
}
