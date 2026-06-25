package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBElementImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.structure.Element;

/// Shared polymorphism-aware label matching for the "~label" filter.
///
/// Both [YTDBHasLabelStep] and the by-id branch of `YTDBGraphStep` route through this helper so
/// that `hasLabel(...)` honours the schema hierarchy identically on the class-scan path and the
/// by-id path. The defect this fixes (YTDB-1159): the by-id path used to apply an exact
/// `HasContainer` match that ignored polymorphism, so `g.V(childId).hasLabel("Parent")` failed to
/// match a `Child` even though a `Child` IS-A `Parent`.
public final class YTDBLabelMatcher {

  private YTDBLabelMatcher() {
  }

  /// Tests a single multi-argument `hasLabel(...)` container against an element.
  ///
  /// The predicate list carries OR semantics (a single `hasLabel("A", "B")` matches an element of
  /// either class). When the element is a YouTrackDB element, the concrete schema class name is
  /// tested first; if `polymorphic` is set and no concrete match was found, every superclass name
  /// is tested too. Non-YouTrackDB elements fall back to the TinkerPop `element.label()` string,
  /// preserving the original [YTDBHasLabelStep] behaviour.
  ///
  /// @param element     the element to test; may or may not be a YouTrackDB element
  /// @param predicates  the OR-combined predicates from a single `hasLabel(...)` container
  /// @param polymorphic whether supertype labels should also match
  /// @return true if any predicate matches the concrete class name, or (when polymorphic) any
  /// superclass name
  public static boolean matchesAny(
      Element element, List<P<? super String>> predicates, boolean polymorphic) {
    if (element instanceof YTDBElementImpl ytdbElement) {
      final var entity = (EntityImpl) ytdbElement.getRawEntity();
      // Use the lightweight immutable schema snapshot rather than getSchemaClass(), which resolves
      // the class against the live schema on every call. Both yield the same class for label tests.
      final var schemaClass = entity.getImmutableSchemaClass(entity.getSession());
      if (schemaClass == null) {
        // No schema class means there is nothing to match a label predicate against.
        return false;
      }
      if (test(predicates, schemaClass.getName())) {
        return true;
      }
      if (!polymorphic) {
        return false;
      }

      for (var c : schemaClass.getAllSuperClasses()) {
        if (test(predicates, c.getName())) {
          return true;
        }
      }

      return false;
    } else {
      return test(predicates, element.label());
    }
  }

  private static boolean test(List<P<? super String>> predicates, String className) {
    return predicates.stream().anyMatch(p -> p.test(className));
  }
}
