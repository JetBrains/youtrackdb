package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.javatuples.Pair;

/**
 * Positional replacement of the {@code by(...)} modulators of an {@code order()} step, shared by the
 * strategies that install a provider sort key.
 *
 * <h2>Why a rebuild rather than a child replacement</h2>
 *
 * {@code replaceLocalChild} cannot express a positional replacement. It matches a slot by
 * {@code equals}, and every {@code IdentityTraversal} equals every other one, so it rewrites the
 * first equal-looking slot instead of the requested one. There is no positional setter on
 * {@code ComparatorHolder} either, so the step is rebuilt with the slot list the caller wants and
 * swapped in at the same index. The index is found by reference, not through
 * {@code TraversalHelper.stepIndex}, which matches on {@code hashCode} and would find an earlier
 * equal-looking order step.
 *
 * <p>The class holds one copy of that walk on purpose. Two strategies replace modulators now, and a
 * second private copy of the swap is what lets two callers drift into rebuilding the step
 * differently.
 */
final class OrderStepModulators {

  private OrderStepModulators() {
    // Static helper — no instances.
  }

  /**
   * The current modulators of {@code comparators}, as a mutable list a caller can substitute into
   * before handing it back to one of the replace methods.
   */
  @SuppressWarnings("rawtypes")
  static List<Admin> modulatorsOf(
      List<? extends Pair<? extends Admin<?, ?>, ? extends Comparator<?>>> comparators) {
    List<Admin> modulators = new ArrayList<>(comparators.size());
    for (var slot : comparators) {
      modulators.add(slot.getValue0());
    }
    return modulators;
  }

  /**
   * Rebuilds {@code step} with {@code modulators} in its comparator slots, keeping every comparator
   * and every step label, and swaps the rebuilt step in at the same index.
   *
   * <p>A bare {@code order()} keeps its fast path: its comparator field is empty and
   * {@code getComparators} synthesises the single identity slot, so installing the modulator on the
   * step itself replaces that slot with nothing to rebuild.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static void replaceGlobalModulators(OrderGlobalStep step, List<Admin> modulators) {
    var comparators = (List<Pair<Admin, Comparator>>) step.getComparators();
    if (step.getLocalChildren().isEmpty()) {
      step.modulateBy(modulators.getFirst(), comparators.getFirst().getValue1());
      return;
    }
    var replacement = new OrderGlobalStep(step.getTraversal());
    // Carried explicitly: a preceding range fold already pushed its bound onto the old step.
    replacement.setLimit(step.getLimit());
    for (var index = 0; index < comparators.size(); index++) {
      replacement.addComparator(modulators.get(index), comparators.get(index).getValue1());
    }
    swapStep(step, replacement);
  }

  /** Swaps {@code replacement} in at the exact index {@code step} occupies, labels included. */
  private static void swapStep(Step<?, ?> step, Step<?, ?> replacement) {
    var traversal = step.getTraversal();
    var steps = traversal.getSteps();
    var index = 0;
    while (index < steps.size() && steps.get(index) != step) {
      index++;
    }
    TraversalHelper.copyLabels(step, replacement, false);
    traversal.removeStep(index);
    traversal.addStep(index, replacement);
  }
}
