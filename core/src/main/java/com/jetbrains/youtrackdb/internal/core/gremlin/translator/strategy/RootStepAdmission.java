package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.HashSet;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Step;

/** Conservative root type proof. Children have their own grammar and are not inspected. */
final class RootStepAdmission {

  private static final Set<Class<?>> EXACT = exactClasses();

  private RootStepAdmission() {
  }

  private static Set<Class<?>> exactClasses() {
    var classes = new HashSet<>(GremlinStepWalker.PRODUCTION_RECOGNISERS.keySet());
    classes.addAll(GremlinStepWalker.TRANSPARENT_STEPS);
    classes.add(EdgeHopRecogniser.CONSUMED_CLOSING_STEP);
    classes.addAll(OrderedExpandAccept.CONSUMED_FAMILIES);
    return Set.copyOf(classes);
  }

  static boolean admits(Step<?, ?> step) {
    return EXACT.contains(step.getClass()) || OrderedExpandAccept.consumesFamily(step);
  }

}
