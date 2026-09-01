package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsDefault;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.sql.OrderByNullsUtil;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.javatuples.Pair;

/**
 * Applies {@link GlobalConfiguration#QUERY_ORDER_BY_NULLS_DEFAULT} to native Gremlin {@code
 * order()} steps. TinkerPop's default comparator already matches {@link
 * OrderByNullsDefault#NULLS_SMALLEST}, so this strategy is a no-op in that case. When the effective
 * default is {@link OrderByNullsDefault#NULLS_LARGEST}, it wraps {@link OrderGlobalStep}
 * comparators so null placement follows the same rule as YQL {@code ORDER BY}. Runs after {@link
 * GremlinToMatchStrategy}: a recognized shape loses its {@code OrderGlobalStep} to the boundary
 * splice, so wrapping applies only to the native-decline fallback.
 */
public final class YTDBOrderNullsStrategy
    extends AbstractTraversalStrategy<ProviderOptimizationStrategy>
    implements ProviderOptimizationStrategy {

  private static final YTDBOrderNullsStrategy INSTANCE = new YTDBOrderNullsStrategy();

  private static final Field COMPARATORS_FIELD;
  private static final Field MULTI_COMPARATOR_FIELD;

  static {
    try {
      COMPARATORS_FIELD = OrderGlobalStep.class.getDeclaredField("comparators");
      COMPARATORS_FIELD.setAccessible(true);
      MULTI_COMPARATOR_FIELD = OrderGlobalStep.class.getDeclaredField("multiComparator");
      MULTI_COMPARATOR_FIELD.setAccessible(true);
    } catch (NoSuchFieldException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private YTDBOrderNullsStrategy() {
  }

  public static YTDBOrderNullsStrategy instance() {
    return INSTANCE;
  }

  /**
   * Declares that the Gremlin-to-MATCH translator must run first. On a recognized shape the
   * translator replaces the whole step list, so {@code OrderGlobalStep} instances disappear before
   * this strategy runs; on a decline they remain for native comparator wrapping.
   */
  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPrior() {
    return Set.of(GremlinToMatchStrategy.class);
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
    if (!needsNullsOverride(config)) {
      return;
    }
    TraversalHelper.getStepsOfAssignableClassRecursively(OrderGlobalStep.class, traversal)
        .forEach(step -> wrapComparators(step, config));
  }

  private static boolean needsNullsOverride(ContextConfiguration config) {
    // Read through the resolver that owns the key. A lower-case or malformed stored value is then
    // tolerated here exactly as it is on the YQL path.
    return OrderByNullsUtil.resolveDefault(config) == OrderByNullsDefault.NULLS_LARGEST;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void wrapComparators(OrderGlobalStep<?, ?> step, ContextConfiguration config) {
    try {
      var comparators = (List<Pair<Admin, Comparator>>) COMPARATORS_FIELD.get(step);
      for (var i = 0; i < comparators.size(); i++) {
        var pair = comparators.get(i);
        var comparator = pair.getValue1();
        if (comparator == Order.shuffle) {
          continue;
        }
        var ascending = comparator != Order.desc;
        var nullsFirst = OrderByNullsUtil.resolveNullsFirst(null, ascending, config);
        comparators.set(i, pair.setAt1(wrap(comparator, nullsFirst)));
      }
      MULTI_COMPARATOR_FIELD.set(step, null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Failed to adjust OrderGlobalStep null ordering", e);
    }
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
