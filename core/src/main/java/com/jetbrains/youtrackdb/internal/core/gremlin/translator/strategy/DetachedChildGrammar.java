package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.NotStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.OrStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WherePredicateStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTraversalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeOtherVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PathStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ProjectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectOneStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepPlaceholder;
import org.apache.tinkerpop.gremlin.structure.PropertyType;

/**
 * Structural gate for children that carry a detached hop or an independent source, including hops
 * hidden inside nested conjunctive checks. A chain admits hops, target predicates, positive-capacity
 * barriers and recursively checked filter/NOT/AND/OR wrappers. Plain terminal dedup, positive limit
 * and single-key property projections preserve existence. Labelled hops are safe only when no child
 * or outer traversal reads their binding. Recognisers still enforce the captured-pattern constraints.
 */
final class DetachedChildGrammar {

  private DetachedChildGrammar() {
  }

  /** A GraphStep would become correlated, and a projected child would lose its payload. */
  static boolean needsCheck(Traversal.Admin<?, ?> child) {
    for (var step : child.getSteps()) {
      if (isHop(step) || step instanceof GraphStep<?, ?> || step instanceof ProjectStep<?, ?>) {
        return true;
      }
      if (step instanceof TraversalParent parent) {
        for (var nested : parent.getLocalChildren()) {
          if (needsCheck(nested)) {
            return true;
          }
        }
        for (var nested : parent.getGlobalChildren()) {
          if (needsCheck(nested)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** Reject a detached label when a top-level consumer might read its dropped binding. */
  static boolean hasOuterLabelReader(Traversal.Admin<?, ?> traversal) {
    if (!hasLabelReader(traversal)) {
      return false;
    }
    for (var step : traversal.getSteps()) {
      if (step instanceof TraversalParent parent) {
        for (var child : parent.getLocalChildren()) {
          if (hasLabelledHop(child)) {
            return true;
          }
        }
        for (var child : parent.getGlobalChildren()) {
          if (hasLabelledHop(child)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean hasLabelledHop(Traversal.Admin<?, ?> child) {
    for (var step : child.getSteps()) {
      if (isHop(step) && GremlinStepLabels.hasUserLabel(step)) {
        return true;
      }
      if (step instanceof TraversalParent parent) {
        for (var nested : parent.getLocalChildren()) {
          if (hasLabelledHop(nested)) {
            return true;
          }
        }
        for (var nested : parent.getGlobalChildren()) {
          if (hasLabelledHop(nested)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  static boolean accepts(Traversal.Admin<?, ?> child) {
    // A label inside a detached child is dropped. A later child reader could otherwise resolve
    // the outer label of the same name instead of the label on the hopped element.
    if (hasLabelledHop(child) && hasLabelReader(child)) {
      return false;
    }
    var steps = child.getSteps();
    if (steps.isEmpty()) {
      return false;
    }
    for (int i = 0; i < steps.size(); i++) {
      var step = (Step<?, ?>) steps.get(i);
      if ((GremlinStepLabels.hasUserLabel(step) && !isHop(step))
          || !acceptsStep(step, i == steps.size() - 1)) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasLabelReader(Traversal.Admin<?, ?> child) {
    for (var step : child.getSteps()) {
      if (step instanceof WherePredicateStep<?> || step instanceof WhereTraversalStep<?>
          || step instanceof SelectOneStep<?, ?> || step instanceof SelectStep<?, ?>
          || step instanceof PathStep<?>) {
        return true;
      }
      if (step instanceof TraversalParent parent) {
        for (var nested : parent.getLocalChildren()) {
          if (hasLabelReader(nested)) {
            return true;
          }
        }
        for (var nested : parent.getGlobalChildren()) {
          if (hasLabelReader(nested)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** The only ignored LIMIT clause is a positive, terminal, zero-offset limit. */
  static boolean terminalPositiveLimit(Traversal.Admin<?, ?> child) {
    var steps = child.getSteps();
    if (steps.isEmpty()) {
      return false;
    }
    return positiveLimit((Step<?, ?>) steps.getLast());
  }

  private static boolean acceptsStep(Step<?, ?> step, boolean terminal) {
    if (isHop(step) || step.getClass() == HasStep.class) {
      return true;
    }
    if (step.getClass() == NoOpBarrierStep.class) {
      return StepStreamCursor.hasCapacity(step);
    }
    if (step.getClass() == TraversalFilterStep.class
        && step instanceof TraversalFilterStep<?> filter) {
      return TraversalFilterStepRecogniser.presenceKey(filter) != null
          || accepts(filter.getFilterTraversal());
    }
    if (step.getClass() == NotStep.class && step instanceof NotStep<?> not) {
      return NotStepRecogniser.hasNotPresenceKey(not) != null || allChildrenAccepted(not);
    }
    if (step.getClass() == AndStep.class && step instanceof AndStep<?> and) {
      return allChildrenAccepted(and);
    }
    if (step.getClass() == OrStep.class && step instanceof OrStep<?> or) {
      return allChildrenAccepted(or);
    }
    // These steps keep empty input empty and nonempty input nonempty only at the end of the
    // detached chain. A later step could read the changed payload or cardinality.
    if (terminal && step.getClass() == DedupGlobalStep.class
        && step instanceof DedupGlobalStep<?> dedup) {
      return dedup.getLocalChildren().isEmpty()
          && (dedup.getScopeKeys() == null || dedup.getScopeKeys().isEmpty());
    }
    if (terminal && positiveLimit(step)) {
      return true;
    }
    if (terminal && step.getClass() == PropertiesStep.class
        && step instanceof PropertiesStep<?> properties) {
      var type = properties.getReturnType();
      return (type == PropertyType.VALUE || type == PropertyType.PROPERTY)
          && properties.getPropertyKeys().length == 1
          && properties.getPropertyKeys()[0] != null
          && !properties.getPropertyKeys()[0].isBlank();
    }
    // where(P.eq(label)) reads an outer label. The recogniser resolves it against the real
    // boundary, and declines when it is absent. Other predicates have no proved detached meaning.
    if (terminal && step.getClass() == WherePredicateStep.class
        && step instanceof WherePredicateStep<?> where) {
      var predicate = where.getPredicate().orElse(null);
      return where.getLocalChildren().isEmpty() && predicate != null
          && predicate.getBiPredicate() == Compare.eq;
    }
    return false;
  }

  private static boolean positiveLimit(Step<?, ?> step) {
    if (!(step instanceof RangeGlobalStepContract<?> range)) {
      return false;
    }
    var low = range.getLowRange();
    var high = range.getHighRange();
    // MAX_VALUE is an unbounded no-op in RangeGlobalStepRecogniser. A negative high may also
    // normalise to unbounded, but it is not a positive limit and must not admit skip(0).
    return low != null && high != null && low == 0 && high > 0;
  }

  private static boolean allChildrenAccepted(TraversalParent parent) {
    var children = parent.getLocalChildren();
    if (children.isEmpty()) {
      return false;
    }
    for (var nested : children) {
      if (!accepts(nested)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isHop(Step<?, ?> step) {
    var type = step.getClass();
    return type == VertexStep.class || type == VertexStepPlaceholder.class
        || type == EdgeVertexStep.class || type == EdgeOtherVertexStep.class;
  }
}
