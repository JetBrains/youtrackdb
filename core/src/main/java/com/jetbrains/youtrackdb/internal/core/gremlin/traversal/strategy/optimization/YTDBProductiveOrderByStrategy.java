package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import java.util.List;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.DefaultGraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.AbstractLambdaTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ConstantTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CoalesceStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.ReducingBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;

/**
 * Makes the by-modulator of a GLOBAL-scope {@code order()} step productive, so a record that does
 * not carry the ordered property is kept and ordered as a null key, exactly as YQL {@code ORDER BY}
 * orders it.
 *
 * <p>Portable TinkerPop treats a by-modulator as a filter: {@link OrderGlobalStep#processAllStarts}
 * projects each traverser through the modulator and drops the traverser when the projection yields
 * nothing. The drop therefore happens BEFORE any comparison, which is why this strategy rewrites
 * the projection instead of the comparator — no comparator can see a traverser that never reached
 * the sort. Each modulator is wrapped in {@code coalesce(modulator, constant(null))}, the same
 * rewrite upstream {@code ProductiveByStrategy} performs.
 *
 * <p>Upstream {@code ProductiveByStrategy} is deliberately NOT registered instead: it selects every
 * {@code ByModulating} {@code TraversalParent}, so it would also make {@code order(Scope.local)},
 * {@code select}, {@code dedup}, {@code group} and {@code path} productive. This strategy is
 * filtered to {@link OrderGlobalStep}, which is exactly the global-scope order step. The resulting
 * divergence is intentional: {@code order().by(k)} keeps a record missing {@code k} while {@code
 * fold().order(Scope.local).by(k)} still drops the entry.
 *
 * <p>The strategy is registered UNCONDITIONALLY and reads {@link
 * GlobalConfiguration#QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY} (or the per-traversal {@code
 * orderIncludesMissingKey} option) inside {@link #apply}. {@code TraversalStrategies.GlobalCache}
 * holds one strategy list per graph class for the whole process and that list is populated from a
 * static initializer, so registering conditionally would freeze the decision at first class load
 * and no later configuration write could reach it.
 *
 * <p>Runs after {@link GremlinToMatchStrategy}: a recognized shape loses its {@code
 * OrderGlobalStep} to the boundary splice, so this rewrite applies to the native-execution
 * fallback only. The translated path carries the same semantics through its own presence policy.
 */
public final class YTDBProductiveOrderByStrategy
    extends AbstractTraversalStrategy<ProviderOptimizationStrategy>
    implements ProviderOptimizationStrategy {

  private static final YTDBProductiveOrderByStrategy INSTANCE =
      new YTDBProductiveOrderByStrategy();

  private YTDBProductiveOrderByStrategy() {
  }

  public static YTDBProductiveOrderByStrategy instance() {
    return INSTANCE;
  }

  /**
   * Declares that the Gremlin-to-MATCH translator must run first. On a recognized shape the
   * translator replaces the whole step list and no {@code OrderGlobalStep} survives for this
   * strategy to rewrite; on a decline the step remains and the rewrite applies.
   */
  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPrior() {
    return Set.of(GremlinToMatchStrategy.class);
  }

  @Override
  public void apply(Admin<?, ?> traversal) {
    // The setting is read here rather than at registration time, because the strategy list is
    // built once per graph class for the whole process.
    var includesMissingKey = YTDBStrategyUtil.orderIncludesMissingKey(traversal);
    if (!Boolean.TRUE.equals(includesMissingKey)) {
      // Either the traversal has no YTDB graph attached, or the deployment opted out and portable
      // TinkerPop filtering stands.
      return;
    }

    // Not recursive: the strategy engine applies every strategy to each child traversal in turn,
    // so a nested order() is reached when its own parent traversal is visited.
    for (OrderGlobalStep<?, ?> step : TraversalHelper
        .getStepsOfAssignableClass(OrderGlobalStep.class, traversal)) {
      // The list is copied first: makeProductive can hand the child back to the step, which
      // rebuilds the step's own comparator list.
      for (var child : List.copyOf(step.getLocalChildren())) {
        makeProductive(step, child);
      }
    }
  }

  /**
   * Rewrites one by-modulator of {@code step} so an unproductive projection yields {@code null}
   * instead of nothing.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void makeProductive(TraversalParent step, Admin<?, ?> child) {
    if (child instanceof ValueTraversal<?, ?> valueTraversal) {
      // by("age"): the property lookup is a lambda traversal, which cannot hold steps. Upstream's
      // bypass hook is the only way in — the lambda delegates to the bypass when one is set.
      if (valueTraversal.getBypassTraversal() != null) {
        // Already redirected, by this strategy on an earlier pass or by another rewrite. Leaving
        // it alone keeps the rewrite idempotent and never nests two coalesce steps.
        return;
      }
      var bypass = new DefaultGraphTraversal<>();
      bypass.addStep(
          new CoalesceStep<>(bypass, child.clone(), new ConstantTraversal<>(null)));
      bypass.setParent(step);
      ((ValueTraversal<Object, Object>) valueTraversal).setBypassTraversal(bypass);
      return;
    }

    if (child instanceof AbstractLambdaTraversal) {
      // by(), by(T.id) and by(T.label) are lambda traversals that always produce a value, so they
      // are productive already. They also hold no steps, so the coalesce rewrite below cannot be
      // applied to them at all.
      return;
    }

    if (child.getEndStep() instanceof ReducingBarrierStep) {
      // by(count()), by(sum()) and friends always emit a value, even over no input.
      return;
    }

    if (isCoalesceOverNull(child)) {
      // Already productive, so wrapping again would only add a redundant nesting level.
      return;
    }

    // by(__.values("age")) and every other real traversal: move the body into a nested traversal
    // and leave a coalesce over it in place.
    Admin<Object, Object> body = new DefaultGraphTraversal<>();
    TraversalHelper.removeToTraversal((Step) child.getStartStep(), EmptyStep.instance(), body);
    var mutableChild = (Admin<Object, Object>) child;
    mutableChild.addStep(
        new CoalesceStep<>(mutableChild, body, new ConstantTraversal<>(null)));
    // Hand the child back to its parent so the parent can refresh whatever it caches about it —
    // OrderGlobalStep drops its memoized MultiComparator here.
    step.replaceLocalChild(mutableChild, mutableChild);
  }

  /** Whether {@code child} is already a lone {@code coalesce(body, constant(null))}. */
  private static boolean isCoalesceOverNull(Admin<?, ?> child) {
    if (child.getSteps().size() != 1 || !(child.getStartStep() instanceof CoalesceStep<?, ?> c)) {
      return false;
    }
    var alternatives = c.getLocalChildren();
    return !alternatives.isEmpty()
        && alternatives.getLast() instanceof ConstantTraversal<?, ?> constant
        && constant.next() == null;
  }
}
