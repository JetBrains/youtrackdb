package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.IdentityTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.TokenTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.T;
import org.javatuples.Pair;

/**
 * Appends {@code by(T.id, asc)} to native Gremlin {@code order()} steps so tie groups sort by RID,
 * matching the implicit {@code @rid ASC} tie-break applied in {@link
 * com.jetbrains.youtrackdb.internal.core.sql.OrderByRidTieBreakUtil} during YQL execution. Runs
 * after {@link GremlinToMatchStrategy}: translated shapes lose their {@code OrderGlobalStep}
 * before this strategy runs. Shapes that already spell {@code by("id", asc)} skip the append.
 */
public final class YTDBOrderRidTieBreakStrategy
    extends AbstractTraversalStrategy<ProviderOptimizationStrategy>
    implements ProviderOptimizationStrategy {

  private static final YTDBOrderRidTieBreakStrategy INSTANCE = new YTDBOrderRidTieBreakStrategy();

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

  private YTDBOrderRidTieBreakStrategy() {
  }

  public static YTDBOrderRidTieBreakStrategy instance() {
    return INSTANCE;
  }

  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPrior() {
    return Set.of(GremlinToMatchStrategy.class);
  }

  @Override
  public void apply(Admin<?, ?> traversal) {
    if (YTDBStrategyUtil.resolveYtdbSession(traversal) == null) {
      return;
    }
    TraversalHelper.getStepsOfAssignableClassRecursively(OrderGlobalStep.class, traversal)
        .forEach(YTDBOrderRidTieBreakStrategy::appendRidTieBreak);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void appendRidTieBreak(OrderGlobalStep<?, ?> step) {
    try {
      var comparators = (List<Pair<Admin, Comparator>>) COMPARATORS_FIELD.get(step);
      if (comparators == null || comparators.isEmpty() || hasExplicitTieBreak(comparators)) {
        return;
      }
      comparators.add(new Pair<>(new TokenTraversal(T.id).asAdmin(), Order.asc));
      MULTI_COMPARATOR_FIELD.set(step, null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Failed to append OrderGlobalStep RID tie-break", e);
    }
  }

  private static boolean hasExplicitTieBreak(List<Pair<Admin, Comparator>> comparators) {
    var lastModulator = comparators.getLast().getValue0();
    if (lastModulator instanceof TokenTraversal token && T.id.equals(token.getToken())) {
      return true;
    }
    if (lastModulator instanceof IdentityTraversal) {
      return true;
    }
    return ByModulatorTranslator.keyModulatorPropertyKey(lastModulator)
        .filter("id"::equals)
        .isPresent();
  }
}
