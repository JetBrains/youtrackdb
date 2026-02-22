package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter.YTDBHasLabelStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.sideeffect.YTDBGraphStep;
import java.util.Collections;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MatchStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.structure.T;

/**
 * Gremlin traversal optimization strategy that folds label-filtering steps from a
 * TinkerPop {@link MatchStep} into the preceding {@link YTDBGraphStep}.
 * <p>
 * ## Motivation
 * <p>
 * In Gremlin, a `match()` step can contain `has()` or `hasLabel()` predicates that
 * filter vertices by their label (schema class). Without this optimization, the
 * graph step would load **all** vertices and the match step would filter them in
 * memory. By moving (folding) these label predicates into the graph step, the storage
 * engine can use index lookups or class-based scans to retrieve only the relevant
 * vertices, drastically reducing I/O.
 * <p>
 * ## Preconditions
 * <p>
 * The optimization applies only when **all** of the following hold:
 *
 * 1. The traversal starts with a {@link YTDBGraphStep} followed by a
 *    {@link MatchStep}.
 * 2. The graph step's existing `HasContainer`s (if any) filter **only** on labels
 *    (`T.label`). This ensures we don't interfere with property-based predicates.
 * <p>
 * ## Transformation
 * <p>
 * <pre>
 * Before:
 *   YTDBGraphStep(hasContainers=[])  →  MatchStep(has(T.label,"Person"), ...)
 *
 * After:
 *   YTDBGraphStep(hasContainers=[T.label="Person"])  →  MatchStep(...)
 * </pre>
 *
 * The strategy walks the first global child of the match step and, for each leading
 * `HasStep` or `YTDBHasLabelStep`, moves its predicates into the graph step's
 * `HasContainer` list. It stops at the first step that is not a has-label step.
 * <p>
 * ## Ordering
 * <p>
 * This strategy must run **after** {@link YTDBGraphStepStrategy} (specified via
 * {@link #applyPrior()}) so that the graph step is already in its optimized form.
 *
 * @see YTDBGraphStepStrategy
 * @see YTDBGraphStep
 */
public final class YTDBGraphMatchStepStrategy
    extends AbstractTraversalStrategy<TraversalStrategy.ProviderOptimizationStrategy>
    implements TraversalStrategy.ProviderOptimizationStrategy {

  private static final YTDBGraphMatchStepStrategy INSTANCE = new YTDBGraphMatchStepStrategy();

  private YTDBGraphMatchStepStrategy() {
  }

  /**
   * Attempts to fold label predicates from a `match()` step into the preceding
   * graph step.
   */
  @Override
  public void apply(final Traversal.Admin<?, ?> traversal) {
    if (traversal.getSteps().size() >= 2) {
      final var startStep = traversal.getStartStep();
      var nextStep = startStep.getNextStep();

      // Only optimize when the traversal starts with YTDBGraphStep → MatchStep
      if (startStep instanceof YTDBGraphStep<?, ?> ytdbGraphStep
          && nextStep instanceof MatchStep<?, ?> matchStep) {

        // Verify that all existing HasContainers on the graph step filter only on labels.
        // If there are property-based predicates, we skip the optimization to avoid
        // accidentally broadening the predicate scope.
        var hasContainers = ytdbGraphStep.getHasContainers();
        var onlyLabels = true;
        for (var hasContainer : hasContainers) {
          if (!hasContainer.getKey().equals(T.label.getAccessor())) {
            onlyLabels = false;
            break;
          }
        }
        if (onlyLabels) {
          // Walk the first global child of the match step and absorb leading
          // Has/HasLabel steps into the graph step:
          //
          //   MatchStep globalChildren[0]:
          //     StartStep → HasStep(T.label="Person") → HasStep(T.label="City") → OtherStep → …
          //                 ^absorb into GraphStep      ^absorb into GraphStep     ^stop here
          //
          // After: YTDBGraphStep now has hasContainers=[T.label="Person", T.label="City"]
          //        and the match sub-traversal starts at OtherStep.
          var globalChildren = matchStep.getGlobalChildren();
          var match = globalChildren.getFirst();
          var currentStep = match.getStartStep().getNextStep();

          var doIterate = true;
          while (doIterate) {

            final boolean replaced;
            if (currentStep instanceof HasStep<?> hasStep) {
              // Fold HasStep predicates (e.g. has(T.label, "Person"))
              for (var hasContainer : hasStep.getHasContainers()) {
                ytdbGraphStep.addHasContainer(hasContainer);
              }
              replaced = true;
            } else if (currentStep instanceof YTDBHasLabelStep<?> hls) {
              // Fold YTDBHasLabelStep predicates into T.label HasContainers
              for (var predicate : hls.getPredicates()) {
                ytdbGraphStep.addHasContainer(new HasContainer(T.label.getAccessor(), predicate));
              }
              replaced = true;
            } else {
              replaced = false;
            }

            if (replaced) {
              // Transfer step labels and remove the step from the match sub-traversal
              currentStep.getLabels().forEach(ytdbGraphStep::addLabel);
              match.removeStep(currentStep);
              currentStep = currentStep.getNextStep();
            } else {
              // Stop at the first non-has-label step
              doIterate = false;
            }
          }
        }
      }
    }
  }

  /**
   * This strategy must run after {@link YTDBGraphStepStrategy} which converts the
   * initial graph step into the YouTrackDB-specific optimized form.
   */
  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPrior() {
    return Collections.singleton(YTDBGraphStepStrategy.class);
  }

  /** Returns the singleton instance of this strategy. */
  public static YTDBGraphMatchStepStrategy instance() {
    return INSTANCE;
  }
}
