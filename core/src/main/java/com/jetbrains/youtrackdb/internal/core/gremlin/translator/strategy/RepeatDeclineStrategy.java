package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.RepeatStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.RepeatUnrollStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;

/**
 * Keeps {@link GremlinToMatchStrategy} away from any traversal that was written with {@code
 * repeat(...)}. Variable-depth repetition is out of scope for the current translator, and this is
 * the strategy that makes the decline hold even after another strategy has erased the evidence.
 *
 * <h2>Why a separate strategy is needed</h2>
 *
 * <p>TinkerPop's {@code RepeatUnrollStrategy} rewrites {@code repeat(__.out()).times(n)} into n
 * chained {@code VertexStep}s separated by {@code NoOpBarrierStep}s. {@link GremlinStepWalker}
 * treats {@code NoOpBarrierStep} as transparent, so the unrolled form is byte-for-byte the shape a
 * hand-written n-hop chain produces and no recogniser can tell the two apart. The translator folded
 * the unrolled chain into one MATCH pattern, and MATCH enumerates paths rather than merging
 * traversers into bulks the way the barriers do — on the TinkerPop grateful-dead fixture {@code
 * repeat(__.out()).times(8)} has 2,505,037,961,767,380 paths, so the query never returned while
 * native Gremlin answered it in milliseconds. Strategy categories are applied in a fixed order and
 * the unroll is an {@code OptimizationStrategy} while the translator is a {@code
 * ProviderOptimizationStrategy}, so the unroll always runs first and the translator can never see
 * the {@link RepeatStep} itself.
 *
 * <p>A {@code DecorationStrategy} runs before every optimization, which is early enough to see the
 * {@link RepeatStep}, and the decision it records survives into the provider-optimization pass.
 *
 * <h2>What it removes, and what it deliberately leaves alone</h2>
 *
 * <p>The veto removes the <em>translator</em> from the traversal's own strategy list, never {@link
 * RepeatUnrollStrategy}. Dropping the unroll would take the barriers with it and move the
 * non-termination from MATCH into the native pipeline, which is the fallback this decline depends
 * on. Leaving the unroll in place means a declined traversal executes exactly as it does with the
 * translator switched off.
 *
 * <p>The list is replaced through {@code clone().removeStrategies(...)} rather than mutated in
 * place. {@code Traversal.Admin.applyStrategies} iterates the live strategy set, so removing from
 * it mid-pass raises {@link java.util.ConcurrentModificationException}, and the set a traversal
 * source hands out is shared with other traversals. Replacing the traversal's own reference keeps
 * the edit local to this one traversal.
 *
 * <p>Because the replacement lands after the iteration has already captured the old set, the
 * translator is still invoked in this pass; it honours the removal itself by re-reading the
 * traversal's strategy list before translating. That check lives in {@link
 * GremlinToMatchStrategy}.
 *
 * <h2>Cost when the translator is off</h2>
 *
 * <p>With the kill-switch off the strategy list is left exactly as it is, so the traversal compiles
 * and runs identically to a build without this strategy. The only work spent is the recursive scan
 * for a {@link RepeatStep}, which runs before the session and kill-switch are resolved so a
 * traversal without one costs a single step walk and no database interaction.
 */
public final class RepeatDeclineStrategy
    extends AbstractTraversalStrategy<TraversalStrategy.DecorationStrategy>
    implements TraversalStrategy.DecorationStrategy {

  private static final RepeatDeclineStrategy INSTANCE = new RepeatDeclineStrategy();

  private RepeatDeclineStrategy() {
  }

  /** Singleton accessor — the strategy is stateless and cheap to share. */
  public static RepeatDeclineStrategy instance() {
    return INSTANCE;
  }

  @Override
  public void apply(Admin<?, ?> traversal) {
    // Only the root carries the step list the translator would replace, and the recursive scan
    // below already covers every child, so acting once at the root is both sufficient and cheaper
    // than repeating the scan for each child the strategy is applied to.
    if (!traversal.isRoot()) {
      return;
    }
    // Cheapest gate first: a traversal with no repeat is the overwhelmingly common case and must
    // not pay for session resolution, which starts a transaction.
    if (!TraversalHelper.hasStepOfAssignableClassRecursively(RepeatStep.class, traversal)) {
      return;
    }
    var strategies = traversal.getStrategies();
    if (strategies.getStrategy(GremlinToMatchStrategy.class).isEmpty()) {
      return;
    }
    if (GremlinToMatchStrategy.resolveSessionIfEnabled(traversal) == null) {
      return;
    }
    traversal.setStrategies(strategies.clone().removeStrategies(GremlinToMatchStrategy.class));
  }
}
