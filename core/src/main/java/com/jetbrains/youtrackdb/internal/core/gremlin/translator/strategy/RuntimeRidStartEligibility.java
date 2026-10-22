package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda.RecordIdSortKeyTraversal;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Operator;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.DefaultGraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.AbstractLambdaTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ConstantTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStepPlaceholder;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CoalesceStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepPlaceholder;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy;
import org.apache.tinkerpop.gremlin.structure.PropertyType;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.util.function.ConstantSupplier;

/** Structural first-family check on the traversal presented to the translator after strategies. */
final class RuntimeRidStartEligibility {

  private RuntimeRidStartEligibility() {
  }

  /**
   * The RID owner is the start step when {@code idContainer} is null, otherwise that exact
   * container. These invocation-local provenance references must never enter a shared template.
   * The RID is an immutable snapshot, not the traversal's live identity.
   */
  record EligibleStart(GraphStep<?, ?> startStep, @Nullable HasContainer idContainer,
      RecordId rid) {
  }

  /**
   * {@code rootWalk} must be false for synthesized union and predicate walks, even if their
   * temporary traversal has no parent. It is independent of the walker's predicate-fold latch.
   * Eligibility is necessary, not sufficient: the walker still checks labels and hop validity.
   * This check neither applies strategies nor consults schema or cache state.
   */
  static @Nullable EligibleStart evaluate(Traversal.Admin<?, ?> traversal, boolean rootWalk) {
    if (!rootWalk || !traversal.isRoot()) {
      return null;
    }
    var steps = traversal.getSteps();
    if (steps.size() < 2 || steps.getFirst().getClass() != GraphStep.class) {
      return null;
    }
    var start = (GraphStep<?, ?>) steps.getFirst();
    if (!start.returnsVertex()) {
      return null;
    }
    var ids = start.getIds();
    if (ids.length > 1) {
      return null;
    }
    Object rawId = ids.length == 1 ? ids[0] : null;
    boolean hasId = ids.length == 1;
    boolean hasLabel = false;
    HasContainer idContainer = null;
    int i = 1;
    // Only prefix filters qualify. Never skip a barrier or inspect filters beyond the hop.
    for (; i < steps.size() && steps.get(i).getClass() == HasStep.class; i++) {
      var containers = ((HasStep<?>) steps.get(i)).getHasContainers();
      if (containers.isEmpty()) {
        return null;
      }
      for (var container : containers) {
        var predicate = container.getPredicate();
        if (predicate == null || predicate.getBiPredicate() != Compare.eq) {
          return null;
        }
        if (T.id.getAccessor().equals(container.getKey())) {
          if (hasId) {
            return null;
          }
          hasId = true;
          rawId = predicate.getValue();
          idContainer = container;
        } else if (T.label.getAccessor().equals(container.getKey())) {
          if (hasLabel || !(predicate.getValue() instanceof String label) || label.isBlank()) {
            return null;
          }
          hasLabel = true;
        } else {
          return null;
        }
      }
    }
    if (!hasId || i >= steps.size() || !allowedHopAndSuffix(traversal, steps, i)) {
      return null;
    }
    // Keep literal recogniser conversion rules, notably declining eq over a collection.
    var rids = StartStepRecogniser.toRecordIds(new Object[] {rawId});
    if (rids == null || rids.size() != 1) {
      return null;
    }
    // Only ChangeableRecordId needs copy() to read its atomic backing value once. Other RID
    // coordinates are immutable, so snapshot them without copying unrelated contextual metadata.
    // Check this snapshot, not the live identity, to keep the decision and binding consistent.
    var rid = rids.getFirst();
    var snapshot = new RecordId(rid instanceof ChangeableRecordId ? rid.copy() : rid);
    if (!snapshot.isPersistent()) {
      return null;
    }
    return new EligibleStart(start, idContainer, snapshot);
  }

  private static boolean allowedHopAndSuffix(Traversal.Admin<?, ?> root,
      java.util.List<Step> steps, int index) {
    var step = steps.get(index);
    // Match exact walker dispatch classes, including the fork's GValue placeholder.
    if ((step.getClass() != VertexStep.class && step.getClass() != VertexStepPlaceholder.class)
        || !(step instanceof VertexStepContract<?> hop)) {
      return false;
    }
    int remaining = steps.size() - index - 1;
    if (remaining == 0) {
      // Preserve the baseline's unlabeled bare vertex hop.
      return !hop.returnsEdge();
    }
    if (remaining != 1 || hop.getEdgeLabels().length == 0) {
      return false;
    }
    var terminal = steps.getLast();
    // AdjacentToIncidentStrategy and written outE/inE/bothE count have this same form.
    if (terminal.getClass() == CountGlobalStep.class) {
      var count = (CountGlobalStep<?>) terminal;
      // Seed and reducer have public setters that the translator does not read.
      return count.getSeedSupplier() instanceof ConstantSupplier<?> seed
          && Long.valueOf(0L).equals(seed.get()) && Operator.sumLong.equals(count.getBiOperator());
    }
    if (hop.returnsEdge()) {
      return false;
    }
    if (terminal.getClass() == PropertiesStep.class) {
      var properties = (PropertiesStep<?>) terminal;
      return properties.getReturnType() == PropertyType.VALUE
          && properties.getPropertyKeys().length == 1;
    }
    if (terminal.getClass() == RangeGlobalStep.class
        || terminal.getClass() == RangeGlobalStepPlaceholder.class) {
      var range = (RangeGlobalStepContract<?>) terminal;
      var low = range.getLowRange();
      var high = range.getHighRange();
      return low != null && high != null && low >= 0 && high >= low && high < Long.MAX_VALUE;
    }
    return terminal.getClass() == OrderGlobalStep.class
        && allowedOrder(root, (OrderGlobalStep<?, ?>) terminal);
  }

  /** A lambda child must expose only the behavior read by the translator, without a bypass. */
  private static boolean plainLambda(Traversal.Admin<?, ?> child, Class<?> exactClass) {
    return child.getClass() == exactClass
        && ((AbstractLambdaTraversal<?, ?>) child).getBypassTraversal() == null;
  }

  private static boolean allowedPropertyKey(Traversal.Admin<?, ?> root,
      ValueTraversal<?, ?> value) {
    var keys = root.getStrategies().getStrategy(ProductiveByStrategy.class)
        .map(ProductiveByStrategy::getProductiveKeys).orElse(null);
    // Match WalkerContext.byModulatorIsProductive. Native order reads the bypass instead.
    boolean wrapped = keys != null && (keys.isEmpty() || !keys.contains(value.getPropertyKey()));
    var bypass = value.getBypassTraversal();
    if (!wrapped || bypass == null) {
      return !wrapped && plainLambda(value, ValueTraversal.class);
    }
    if (bypass.getClass() != DefaultGraphTraversal.class
        || bypass.getSteps().size() != 1
        || bypass.getStartStep().getClass() != CoalesceStep.class) {
      return false;
    }
    var children = ((CoalesceStep<?, ?>) bypass.getStartStep()).getLocalChildren();
    // ProductiveByStrategy builds plain property and constant-null lambda children.
    return children.size() == 2 && plainLambda(children.getFirst(), ValueTraversal.class)
        && java.util.Objects.equals(
            ((ValueTraversal<?, ?>) children.getFirst()).getPropertyKey(), value.getPropertyKey())
        && plainLambda(children.getLast(), ConstantTraversal.class)
        && children.getLast().next() == null;
  }

  private static boolean allowedOrder(Traversal.Admin<?, ?> root, OrderGlobalStep<?, ?> order) {
    // The translator resolves dropping missing keys from the strategy, not the step flag.
    if (order.isFilteringUnproductiveTraversers()
        != YTDBStrategyUtil.hasStandardOrderSemanticsStrategy(root)) {
      return false;
    }
    var keys = order.getComparators();
    if (keys.size() < 1 || keys.size() > 2) {
      return false;
    }
    var property = keys.getFirst();
    // Translation reads the property key and strategy configuration, not the bypass.
    if (property.getValue0().getClass() != ValueTraversal.class
        || !allowedPropertyKey(root, (ValueTraversal<?, ?>) property.getValue0())
        || (!Order.asc.equals(property.getValue1()) && !Order.desc.equals(property.getValue1()))) {
      return false;
    }
    return keys.size() == 1
        || (plainLambda(keys.get(1).getValue0(), RecordIdSortKeyTraversal.class)
            && keys.get(1).getValue1() == property.getValue1());
  }
}
