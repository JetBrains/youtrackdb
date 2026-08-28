package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
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
 * Appends {@code by(T.id, asc)} to every {@link OrderGlobalStep} before {@link
 * GremlinToMatchStrategy} runs. Translated shapes pick up {@code @rid} through {@link
 * ByModulatorTranslator}; declined shapes keep the patched step for native Gremlin execution.
 * Shapes that already spell {@code by("id", asc)} or sort on {@code T.id} / identity skip the
 * append.
 */
public final class YTDBOrderRidTieBreakStrategy
    extends AbstractTraversalStrategy<ProviderOptimizationStrategy>
    implements ProviderOptimizationStrategy {

  private static final YTDBOrderRidTieBreakStrategy INSTANCE = new YTDBOrderRidTieBreakStrategy();

  private YTDBOrderRidTieBreakStrategy() {
  }

  public static YTDBOrderRidTieBreakStrategy instance() {
    return INSTANCE;
  }

  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPost() {
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

  private static void appendRidTieBreak(OrderGlobalStep<?, ?> step) {
    var comparators = step.getComparators();
    if (comparators == null || comparators.isEmpty() || hasExplicitTieBreak(comparators)) {
      return;
    }
    // Same modulator Gremlin builds for order().by(T.id, asc); Admin wiring is required by
    // OrderGlobalStep.modulateBy (see ByModulatorTranslator for the read-side counterpart).
    step.modulateBy(new TokenTraversal(T.id).asAdmin(), Order.asc);
  }

  private static boolean hasExplicitTieBreak(
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
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
