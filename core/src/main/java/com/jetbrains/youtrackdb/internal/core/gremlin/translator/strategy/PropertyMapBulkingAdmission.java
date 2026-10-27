package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WherePredicateStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ElementMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.RequirementsStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;

/** One admission contract for the walker and both cold and warm translation-cache paths. */
final class PropertyMapBulkingAdmission {

  // LazyBarrierStrategy.MAX_BARRIER_SIZE is protected. Keep this ceiling pinned by native tests.
  private static final int MAX_CAPACITY = 2500;

  private PropertyMapBulkingAdmission() {
  }

  /** Zero preserves the ungrouped boundary. A final vertex map needs an empty labelled path. */
  static int capacity(Traversal.Admin<?, ?> traversal) {
    // Child traversals inherit the root's traverser generator and requirements. Local steps
    // cannot prove that inherited path, sack or ONE_BULK state permits grouping.
    if (!traversal.isRoot()) {
      return 0;
    }
    var steps = traversal.getSteps();
    if (steps.size() < 3
        || !(steps.getFirst() instanceof GraphStep<?, ?> start) || !start.returnsVertex()) {
      return 0;
    }
    var last = steps.getLast();
    if (!(last instanceof PropertyMapStep<?, ?>) && !(last instanceof ElementMapStep<?, ?>)) {
      return 0;
    }
    if (!(steps.get(steps.size() - 2) instanceof NoOpBarrierStep<?> barrier)
        || barrier.getMaxBarrierSize() < 1 || barrier.getMaxBarrierSize() > MAX_CAPACITY) {
      return 0;
    }
    if (last instanceof PropertyMapStep<?, ?> map
        && (map.getPropertyTraversal() != null || map.getValueTraversal() != null
        // The native class has exactly these two child slots. Subclasses may add children.
            || (map.getClass() != PropertyMapStep.class && !map.getLocalChildren().isEmpty()))) {
      return 0;
    }
    // Direct callers may precede decoration. Refuse explicit requirements and sack strategies
    // without applying them, invoking suppliers or changing the original traversal.
    if (traversal.getStrategies().getStrategy(RequirementsStrategy.class).isPresent()
        || traversal.getStrategies().getStrategy(SackStrategy.class).isPresent()
        || traversal.getSideEffects().getSackInitialValue() != null) {
      return 0;
    }
    boolean returnsVertex = true;
    boolean possibleLabelState = false;
    for (int i = 0; i < steps.size(); i++) {
      var step = steps.get(i);
      // Reject unsupported structure before asking steps to materialize requirements.
      // An earlier barrier could change the final window's arrival order and input bulk.
      // Slices, order, branches, child filters and suffixes need their own proof and stay out.
      if (i > 0 && i < steps.size() - 2
          && !(step instanceof HasStep<?>) && !(step instanceof VertexStepContract<?>)
          && !(step instanceof EdgeVertexStep) && !(step instanceof WherePredicateStep<?>)) {
        return 0;
      }
      // Native where retracts the incoming path before AbstractStep adds this step's labels.
      // A null keep set is no proof. Aggregate LABELED_PATH can remain after an empty retraction.
      if (step instanceof WherePredicateStep<?> where) {
        if (!where.getLocalChildren().isEmpty()) {
          return 0;
        }
        if (where.getKeepLabels() != null && where.getKeepLabels().isEmpty()) {
          possibleLabelState = false;
        }
      }
      // Child-free native property maps require only OBJECT. WherePredicateStep is final and,
      // after the child check above, requires only OBJECT and SIDE_EFFECTS. Avoid constructing
      // their generic self-and-child requirement sets. Subclasses retain the full check.
      if (step.getClass() != PropertyMapStep.class && !(step instanceof WherePredicateStep<?>)) {
        var requirements = step.getRequirements();
        if (requirements.contains(TraverserRequirement.PATH)
            || requirements.contains(TraverserRequirement.LABELED_PATH)
            || requirements.contains(TraverserRequirement.SACK)
            || requirements.contains(TraverserRequirement.ONE_BULK)) {
          return 0;
        }
      }
      if (i >= steps.size() - 2 && possibleLabelState) {
        return 0;
      }
      if (!step.getLabels().isEmpty()) {
        if (i >= steps.size() - 2) {
          return 0;
        }
        possibleLabelState = true;
      }
      // Edge-returning hops may occur inside an admitted walk, but the final map must see
      // vertices. Track the type through closing inV/outV steps without loading any records.
      if (step instanceof VertexStepContract<?> hop) {
        returnsVertex = hop.returnsVertex();
      } else if (step instanceof EdgeVertexStep) {
        returnsVertex = true;
      }
    }
    return returnsVertex ? barrier.getMaxBarrierSize() : 0;
  }
}
