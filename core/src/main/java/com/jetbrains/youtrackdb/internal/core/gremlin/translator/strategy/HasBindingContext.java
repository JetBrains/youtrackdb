package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.List;
import javax.annotation.Nullable;

/** The routing decisions made before binding one HasStep's property predicates. */
record HasBindingContext(
    Destination destination, List<String> gateClasses, boolean folded) {

  enum Destination {
    MATCH_VERTEX, MATCH_EDGE, ORDERED_FILTER
  }

  HasBindingContext {
    gateClasses = List.copyOf(gateClasses);
  }

  /** The same gate selection applies during the walk and during pre-walk shape extraction. */
  static HasBindingContext forVertex(
      List<String> labels, @Nullable String boundaryClass, boolean folded,
      Destination destination) {
    if (!labels.isEmpty()) {
      return new HasBindingContext(destination, labels, folded);
    }
    return new HasBindingContext(destination,
        boundaryClass == null || WalkerContext.VERTEX_ROOT_CLASS.equals(boundaryClass)
            ? List.of() : List.of(boundaryClass),
        folded);
  }

  static HasBindingContext forEdge(@Nullable String[] labels) {
    return new HasBindingContext(Destination.MATCH_EDGE,
        labels == null ? List.of() : List.of(labels), false);
  }

  /** Child traversals inherit the enclosing walker's class until a local hasLabel re-types it. */
  static @Nullable String capturedChildBoundary(@Nullable String enclosingBoundary) {
    return enclosingBoundary;
  }

  /**
   * A hop always resets the property-type gate to {@code V}. The hop target must not inherit the
   * source scan class (PositiveExistsTranslationTest.hopTargetSchemaDoesNotInheritSourceType).
   * {@code capturedChild} is unused: both paths reset identically, and call sites keep the flag.
   */
  @SuppressWarnings("unused")
  static String afterHopBoundary(String boundaryClass, boolean capturedChild) {
    return WalkerContext.VERTEX_ROOT_CLASS;
  }

  GremlinPredicateAdapter.PropertyTypeGate gate(RecognitionContext ctx) {
    return GremlinPredicateAdapter.schemaGate(ctx, gateClasses.toArray(String[]::new));
  }

  GremlinPredicateAdapter.PropertyTypeGate gate(
      @Nullable com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema schema) {
    return GremlinPredicateAdapter.schemaGate(schema, gateClasses.toArray(String[]::new));
  }

  /** A binding's source within its HasStep, independent of its current literal value. */
  record Slot(int containerIndex, GremlinPredicateAdapter.SlotRole role) {
  }

  record Contribution(HasBindingContext context, List<Slot> slots) {
    Contribution {
      slots = List.copyOf(slots);
    }
  }
}
