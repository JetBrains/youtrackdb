package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Class choices shared by MATCH planning and native property ordering. */
public final class OrderClassResolver {

  private OrderClassResolver() {
  }

  /** Labels take precedence. Multiple vertex labels use their common ancestor. */
  @Nullable public static String labelClass(List<String> names, Function<String, SchemaClass> resolve) {
    if (names == null || names.isEmpty()) {
      return null;
    }
    var current = resolve.apply(names.getFirst());
    if (current == null || !current.isVertexType()) {
      return null;
    }
    for (int i = 1; i < names.size(); i++) {
      var other = resolve.apply(names.get(i));
      if (other == null || !other.isVertexType()) {
        return null;
      }
      current = leastCommonAncestor(current, other);
      if (current == null) {
        return SchemaClass.VERTEX_CLASS_NAME;
      }
    }
    return current.getName();
  }

  @Nullable private static SchemaClass leastCommonAncestor(SchemaClass a, SchemaClass b) {
    if (a.equals(b) || b.isSubClassOf(a)) {
      return a;
    }
    if (a.isSubClassOf(b)) {
      return b;
    }
    SchemaClass best = null;
    for (var ancestor : a.getAllSuperClasses()) {
      if (!ancestor.isVertexType() || !b.isSubClassOf(ancestor)) {
        continue;
      }
      if (best == null || ancestor.isSubClassOf(best)) {
        best = ancestor;
      }
    }
    return best;
  }

  /**
   * Infers an unlabelled hop target. Endpoint steps consume the preceding edge class, never the
   * start vertex class. Ambiguous bidirectional targets retain the hierarchy fallback.
   */
  @Nullable public static String inferHopClass(@Nullable Schema schema, @Nullable String method,
      @Nullable String edgeLabel, @Nullable String previousEdgeClass) {
    return inferHopClass(method, edgeLabel, previousEdgeClass,
        (edge, endpoint) -> linkedClass(schema, edge, endpoint));
  }

  /** The same rule with a caller-supplied schema lookup for planner-internal schema handles. */
  @Nullable public static String inferHopClass(@Nullable String method, @Nullable String edgeLabel,
      @Nullable String previousEdgeClass, BiFunction<String, String, String> linkedClass) {
    if (method == null) {
      return null;
    }
    return switch (method.toLowerCase(Locale.ROOT)) {
      case "oute", "ine", "bothe" -> edgeLabel;
      case "inv" -> linkedClass.apply(previousEdgeClass, "in");
      case "outv" -> linkedClass.apply(previousEdgeClass, "out");
      case "out" -> linkedClass.apply(edgeLabel, "in");
      case "in" -> linkedClass.apply(edgeLabel, "out");
      case "both", "bothv" -> {
        var edge = "bothv".equalsIgnoreCase(method) ? previousEdgeClass : edgeLabel;
        var in = linkedClass.apply(edge, "in");
        var out = linkedClass.apply(edge, "out");
        yield in != null && in.equals(out) ? in : null;
      }
      default -> null;
    };
  }

  @Nullable public static String linkedClass(@Nullable Schema schema, @Nullable String edgeClass,
      String endpoint) {
    var clazz = schema == null || edgeClass == null ? null : schema.getClass(edgeClass);
    var property = clazz == null ? null : clazz.getProperty(endpoint);
    var linked = property == null ? null : property.getLinkedClass();
    return linked == null ? null : linked.getName();
  }
}
