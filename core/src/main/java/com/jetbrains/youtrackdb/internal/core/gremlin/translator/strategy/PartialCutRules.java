package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.UnionStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.FilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FoldStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderLocalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.UnfoldStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IdentityStep;
import org.apache.tinkerpop.gremlin.structure.T;

/** Shared root cut rules for discovery and future declared cuts. Indices count consumed steps. */
final class PartialCutRules {

  private final boolean[] folding;
  private final boolean[] orderDependency;
  private final boolean[] union;
  private final boolean[] identifiers;

  PartialCutRules(List<Step> steps) {
    int size = steps.size();
    folding = new boolean[size + 1];
    orderDependency = new boolean[size + 1];
    union = new boolean[size + 1];
    identifiers = new boolean[size + 1];
    var dependencyDelta = new int[size + 2];
    boolean foldOpen = false;
    int producer = 0;
    int propertyProducer = 0;
    for (int i = 0; i < size; i++) {
      var step = steps.get(i);
      folding[i] = foldOpen && step instanceof HasStep<?>;
      if (step instanceof GraphStep<?, ?>) {
        foldOpen = true;
      } else if (!(step instanceof HasStep<?>)) {
        // Cursor-transparent barriers are not transparent to native source folding.
        foldOpen = false;
      }
      union[i + 1] = union[i] || step instanceof UnionStep<?, ?>;
      identifiers[i + 1] = identifiers[i] || identifiers(step);
      if (step instanceof OrderGlobalStep<?, ?> || step instanceof OrderLocalStep<?, ?>) {
        // Native collation walks through filters and earlier sorts to the producer. Scalar
        // property sorts also read the element producer before the property projection.
        int dependency = Math.min(producer, propertyProducer);
        dependencyDelta[dependency + 1]++;
        dependencyDelta[i + 1]--;
      }
      if (!preservesClass(step)) {
        propertyProducer = step instanceof PropertiesStep<?> ? producer : i;
        producer = i;
      }
    }
    int dependencies = 0;
    for (int cut = 0; cut <= size; cut++) {
      dependencies += dependencyDelta[cut];
      orderDependency[cut] = dependencies > 0;
    }
  }

  boolean splitsFold(int cut) {
    return folding[cut];
  }

  boolean splitsOrder(int cut) {
    return orderDependency[cut];
  }

  boolean multiPlan(int cut) {
    return union[cut];
  }

  boolean hasIdentifiers(int cut) {
    return identifiers[cut];
  }

  private static boolean preservesClass(Step<?, ?> step) {
    return step instanceof FilterStep<?> || (step instanceof HasContainerHolder<?, ?>
        && !(step instanceof GraphStep<?, ?>))
        || step instanceof IdentityStep<?> || step instanceof NoOpBarrierStep<?>
        || step instanceof OrderGlobalStep<?, ?> || step instanceof OrderLocalStep<?, ?>
        || step instanceof FoldStep<?, ?> || step instanceof UnfoldStep<?, ?>;
  }

  private static boolean identifiers(Step<?, ?> step) {
    if ((step instanceof GraphStep<?, ?> graph && graph.getIds().length > 0)
        || (step instanceof HasContainerHolder<?, ?> holder
            && holder.getHasContainers().stream()
                .anyMatch(h -> T.id.getAccessor().equals(h.getKey())))) {
      return true;
    }
    return step instanceof TraversalParent parent
        && (parent.getLocalChildren().stream().anyMatch(PartialCutRules::identifiers)
            || parent.getGlobalChildren().stream().anyMatch(PartialCutRules::identifiers));
  }

  private static boolean identifiers(Traversal.Admin<?, ?> traversal) {
    return traversal.getSteps().stream().anyMatch(PartialCutRules::identifiers);
  }
}
