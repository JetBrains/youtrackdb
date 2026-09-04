package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsDefault;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.sql.OrderByNullsUtil;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderLocalStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.javatuples.Pair;

/**
 * Applies {@link GlobalConfiguration#QUERY_ORDER_BY_NULLS_DEFAULT} to native Gremlin {@code
 * order()} steps. TinkerPop's default comparator already matches {@link
 * OrderByNullsDefault#NULLS_SMALLEST}, so this strategy is a no-op in that case. When the effective
 * default is {@link OrderByNullsDefault#NULLS_LARGEST}, it rebuilds each {@link OrderGlobalStep} /
 * {@link OrderLocalStep} with wrapped framework {@link Order#asc} / {@link Order#desc}
 * comparators so null placement matches YQL {@code ORDER BY}.
 *
 * <p>Runs after {@link GremlinToMatchStrategy} and {@link YTDBProductiveOrderByStrategy}: a
 * recognized shape loses its order step to the MATCH splice, and productive rewrite must land
 * before comparator wrapping so missing keys reach the sort as nulls.
 *
 * <p>Only the two framework order constants are wrapped. {@link Order#shuffle} and caller-supplied
 * comparators keep their own null handling. The strategy does not walk nested traversals: the
 * framework already visits each child once.
 */
public final class YTDBOrderNullsStrategy
    extends AbstractTraversalStrategy<ProviderOptimizationStrategy>
    implements ProviderOptimizationStrategy {

  private static final YTDBOrderNullsStrategy INSTANCE = new YTDBOrderNullsStrategy();

  private YTDBOrderNullsStrategy() {
  }

  public static YTDBOrderNullsStrategy instance() {
    return INSTANCE;
  }

  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPrior() {
    return Set.of(GremlinToMatchStrategy.class, YTDBProductiveOrderByStrategy.class);
  }

  @Override
  public void apply(Admin<?, ?> traversal) {
    var session = YTDBStrategyUtil.resolveYtdbSession(traversal);
    if (session == null) {
      return;
    }
    var config = session.getConfiguration();
    if (config == null) {
      return;
    }
    // One read for the whole apply. Every wrap below reuses this value.
    var nullsDefault = OrderByNullsUtil.resolveDefault(config);
    if (nullsDefault != OrderByNullsDefault.NULLS_LARGEST) {
      return;
    }

    for (OrderGlobalStep<?, ?> step : TraversalHelper.getStepsOfAssignableClass(
        OrderGlobalStep.class, traversal)) {
      rebuildGlobal(step, traversal, nullsDefault);
    }
    for (OrderLocalStep<?, ?> step : TraversalHelper.getStepsOfAssignableClass(
        OrderLocalStep.class, traversal)) {
      rebuildLocal(step, traversal, nullsDefault);
    }
  }

  /**
   * Rebuilds a global order step when it holds a framework {@code asc}/{@code desc} comparator.
   * {@code getComparators()} is unmodifiable (and synthesizes identity+asc for a bare {@code
   * order()}), so the pairs are copied onto a fresh step and the original is replaced.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void rebuildGlobal(
      OrderGlobalStep<?, ?> step, Admin<?, ?> traversal, OrderByNullsDefault nullsDefault) {
    var pairs = (List<Pair<Admin, Comparator>>) (List<?>) step.getComparators();
    if (!needsWrap(pairs)) {
      return;
    }
    var replacement = new OrderGlobalStep<>(traversal);
    replacement.setLimit(step.getLimit());
    step.getLabels().forEach(replacement::addLabel);
    for (var pair : pairs) {
      replacement.addComparator(pair.getValue0(), maybeWrap(pair.getValue1(), nullsDefault));
    }
    TraversalHelper.replaceStep((Step) step, replacement, traversal);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void rebuildLocal(
      OrderLocalStep<?, ?> step, Admin<?, ?> traversal, OrderByNullsDefault nullsDefault) {
    var pairs = (List<Pair<Admin, Comparator>>) (List<?>) step.getComparators();
    if (!needsWrap(pairs)) {
      return;
    }
    var replacement = new OrderLocalStep<>(traversal);
    step.getLabels().forEach(replacement::addLabel);
    for (var pair : pairs) {
      replacement.addComparator(pair.getValue0(), maybeWrap(pair.getValue1(), nullsDefault));
    }
    TraversalHelper.replaceStep((Step) step, replacement, traversal);
  }

  @SuppressWarnings("rawtypes")
  private static boolean needsWrap(List<Pair<Admin, Comparator>> pairs) {
    for (var pair : pairs) {
      var comparator = pair.getValue1();
      if (comparator == Order.asc || comparator == Order.desc) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Comparator maybeWrap(Comparator comparator, OrderByNullsDefault nullsDefault) {
    if (comparator == Order.asc) {
      return wrap(comparator, OrderByNullsUtil.composeNullsFirst(null, true, nullsDefault));
    }
    if (comparator == Order.desc) {
      return wrap(comparator, OrderByNullsUtil.composeNullsFirst(null, false, nullsDefault));
    }
    // shuffle and caller-supplied comparators keep their own null handling.
    return comparator;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Comparator wrap(Comparator delegate, boolean nullsFirst) {
    return (a, b) -> {
      if (a == null || b == null) {
        if (a == null && b == null) {
          return 0;
        }
        if (a == null) {
          return nullsFirst ? -1 : 1;
        }
        return nullsFirst ? 1 : -1;
      }
      return delegate.compare(a, b);
    };
  }
}
