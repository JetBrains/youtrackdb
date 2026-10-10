package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepPlaceholder;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.T;

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
    var last = steps.getLast();
    // Match the walker's exact dispatch classes, including the fork's GValue placeholder.
    if ((last.getClass() != VertexStep.class && last.getClass() != VertexStepPlaceholder.class)
        || !(last instanceof VertexStepContract<?> hop)
        || hop.returnsEdge() || hop.getEdgeLabels().length > 1) {
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
    for (int i = 1; i < steps.size() - 1; i++) {
      // No transparent steps: barriers and attached labels do not widen this grammar.
      if (steps.get(i).getClass() != HasStep.class) {
        return null;
      }
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
    if (!hasId) {
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
}
