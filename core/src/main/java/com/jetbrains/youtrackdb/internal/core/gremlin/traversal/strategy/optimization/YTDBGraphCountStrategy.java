package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.map.YTDBClassCountStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
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
 * <pre>
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

    final var polymorphicSetting = YTDBStrategyUtil.isPolymorphic(traversal);
    if (polymorphicSetting == null) {
      // means we couldn't access the graph from the traversal
      return;
    }

    final var steps = traversal.getSteps();

    if (steps.size() == 2
        && steps.getFirst() instanceof YTDBGraphStep<?, ?> step
        && steps.getLast() instanceof CountGlobalStep) {

      final var hasContainers = step.getHasContainers();
      List<String> classes = List.of();
      boolean polymorphic = polymorphicSetting;

      if (hasContainers.size() == 1 && isLabelFilter(hasContainers.getFirst())) {
        // g.V().hasLabel('Foo').count()
        classes = extractLabels(hasContainers.getFirst());
      } else if (hasContainers.isEmpty() && step.getIds().length == 0) {
        // g.V().count()
        classes = List.of(
            step.isVertexStep() ? SchemaClass.VERTEX_CLASS_NAME : SchemaClass.EDGE_CLASS_NAME);
        polymorphic = true; // should be polymorphic, because we want to see all vertices or edges
      }

      if (!classes.isEmpty()) {
        TraversalHelper.removeAllSteps(traversal);
        traversal.addStep(
            new YTDBClassCountStep<>(traversal, classes, step.isVertexStep(), polymorphic));
      }
    }
  }

  protected static boolean isLabelFilter(HasContainer f) {
    var labelFilter = f.getKey().equals("~label");

    var predicate = f.getBiPredicate();

    if (predicate instanceof Compare) {
      return labelFilter && Compare.eq.equals(predicate);
    }
    if (predicate instanceof Contains) {
      return labelFilter && Contains.within.equals(predicate);
    }

    return false;
  }

  protected static List<String> extractLabels(HasContainer f) {
    var value = f.getValue();
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
