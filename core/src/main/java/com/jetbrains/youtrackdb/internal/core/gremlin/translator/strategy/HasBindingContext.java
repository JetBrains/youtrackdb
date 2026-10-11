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

  /**
   * A hop resets the property-type gate to {@code V}. The hop target does not inherit the source
   * scan class. PositiveExistsTranslationTest.hopTargetSchemaDoesNotInheritSourceType_forExistsAndNot
   * checks this rule.
   */
  static String afterHopBoundary() {
    return WalkerContext.VERTEX_ROOT_CLASS;
  }

  GremlinPredicateAdapter.PropertyTypeGate gate(RecognitionContext ctx) {
    return GremlinPredicateAdapter.schemaGate(ctx, gateClasses.toArray(String[]::new));
  }

  GremlinPredicateAdapter.PropertyTypeGate gate(
      @Nullable com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema schema) {
    if (schema == null || gateClasses.isEmpty()) {
      return GremlinPredicateAdapter.NO_TYPE_INFO;
    }
    // Resolve classes only when the predicate adapter asks for a type. Reuse the last property's
    // answer across the adapter's repeated guard and prefix checks within this HasStep.
    return new GremlinPredicateAdapter.PropertyTypeGate() {
      private com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass[] classes;
      private String lastKey;
      private java.util.List<String> lastTypes;

      @Override
      public boolean isDeclaredString(String key) {
        return declaredTypeIn(key, List.of("STRING"));
      }

      @Override
      public boolean declaredTypeIn(String key, List<String> types) {
        if (!key.equals(lastKey)) {
          if (classes == null) {
            classes =
                new com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass[gateClasses
                    .size()];
            for (int i = 0; i < classes.length; i++) {
              classes[i] = schema.getClass(gateClasses.get(i));
            }
          }
          var resolved = new java.util.ArrayList<String>(classes.length);
          for (var clazz : classes) {
            var property = clazz == null ? null : clazz.getProperty(key);
            if (property == null || property.getType() == null) {
              lastKey = key;
              lastTypes = List.of();
              return false;
            }
            resolved.add(property.getType().name());
          }
          lastKey = key;
          lastTypes = resolved;
        }
        return !lastTypes.isEmpty() && types.containsAll(lastTypes);
      }
    };
  }

  /** Class rules shared by extraction and the walk without constructing plan-building state. */
  interface VertexClassFacts {
    boolean isVertexClass(String name);

    @Nullable String leastCommonVertexAncestor(List<String> names);
  }

  static VertexClassFacts schemaFacts(
      com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema schema) {
    return new VertexClassFacts() {
      private final java.util.Map<String,
          com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass> classes =
              new java.util.HashMap<>();

      private com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass resolve(
          String name) {
        if (!classes.containsKey(name)) {
          classes.put(name, schema.getClass(name));
        }
        return classes.get(name);
      }

      @Override
      public boolean isVertexClass(String name) {
        var clazz = resolve(name);
        return clazz != null && clazz.isVertexType();
      }

      @Override
      public String leastCommonVertexAncestor(List<String> names) {
        return WalkerContext.leastCommonVertexAncestor(names, this::resolve);
      }
    };
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
