package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Resolves the collation each {@code ORDER BY} item compares text with, from the schema declaration
 * of the ordered property.
 *
 * <h2>Once per plan build, never per comparison</h2>
 *
 * A comparison receives two records, and under a polymorphic target those two records can belong to
 * different subclasses. Reading the declaration off the record would then answer two collations
 * inside one sort, and a comparator that changes rule between calls is not transitive, which is what
 * a sort requires. So the planner resolves the declaration once, from the target of the query, and
 * writes it onto the item.
 *
 * <h2>Only a plain property carries a declaration</h2>
 *
 * A plain property is what the schema can speak about. {@code ORDER BY name} names one property of
 * the target class; {@code ORDER BY a.name} names one property of the class bound to the MATCH alias
 * {@code a}. Everything else — a record attribute, a method call, an index or range accessor, a
 * chain of two or more segments — takes the default collation, because no single declared property
 * governs its value.
 *
 * <h2>Disagreement falls back to the default collation</h2>
 *
 * A polymorphic query returns rows of the target class and of every subclass. When two of those
 * classes declare different collations for one property name, no single rule governs the column, so
 * the item takes the default collation rather than one of the two answers.
 */
public final class OrderByCollationResolver {

  private OrderByCollationResolver() {
    // Static helper — no instances.
  }

  /**
   * Resolves every item of a plain {@code SELECT} clause against one target class. An item that is
   * not a bare property name of that class is reset to the default collation.
   */
  public static void resolveOnTargetClass(
      @Nullable SQLOrderBy orderBy, @Nullable SchemaClass targetClass) {
    resolveOnTargetClass(orderBy, targetClass, null);
  }

  /**
   * Resolves a plain {@code SELECT} clause while treating explicit projection aliases as output
   * names, not source properties. An order item matching such an alias uses the default collation.
   */
  public static void resolveOnTargetClass(
      @Nullable SQLOrderBy orderBy,
      @Nullable SchemaClass targetClass,
      @Nullable SQLProjection projection) {
    if (orderBy == null || orderBy.getItems() == null) {
      return;
    }
    for (var item : orderBy.getItems()) {
      var propertyName = targetPropertyName(item);
      var declaredCollate =
          projectionAliasShadowsProperty(projection, item, propertyName)
              ? null
              : declaredCollation(targetClass, propertyName);
      item.setDeclaredCollate(declaredCollate);
    }
  }

  private static boolean projectionAliasShadowsProperty(
      @Nullable SQLProjection projection,
      SQLOrderByItem orderByItem,
      @Nullable String propertyName) {
    var projectionItem = projectionItemForAlias(projection, orderByItem);
    return projectionItem != null
        && !isBarePropertyExpression(projectionItem.getExpression(), propertyName);
  }

  @Nullable
  private static SQLProjectionItem projectionItemForAlias(
      @Nullable SQLProjection projection, SQLOrderByItem orderByItem) {
    if (projection == null || orderByItem.getModifier() != null || orderByItem.getAlias() == null) {
      return null;
    }
    for (var projectionItem : projection.getItems()) {
      if (projectionItem.getAlias() != null
          && orderByItem.getAlias().equals(projectionItem.getAlias().getStringValue())) {
        return projectionItem;
      }
    }
    return null;
  }

  private static boolean isBarePropertyExpression(
      @Nullable SQLExpression expression, @Nullable String propertyName) {
    if (propertyName == null
        || expression == null
        || !(expression.mathExpression instanceof SQLBaseExpression base)
        || base.getModifier() != null
        || base.getIdentifier() == null
        || base.getIdentifier().getSuffix() == null
        || base.getIdentifier().getSuffix().getIdentifier() == null) {
      return false;
    }
    return propertyName.equals(
        base.getIdentifier().getSuffix().getIdentifier().getStringValue());
  }

  /**
   * Resolves every item of a MATCH clause, where an item names an alias and one property of the
   * class bound to that alias. The alias-to-class mapping comes from the MATCH planner, which is the
   * only place that knows it.
   */
  public static void resolveOnAliasClasses(
      @Nullable SQLOrderBy orderBy, Function<String, SchemaClass> aliasClassResolver) {
    resolveOnAliasClasses(orderBy, aliasClassResolver, null);
  }

  /** Resolves MATCH ordering while treating explicit RETURN aliases as output names. */
  public static void resolveOnAliasClasses(
      @Nullable SQLOrderBy orderBy,
      Function<String, SchemaClass> aliasClassResolver,
      @Nullable SQLProjection projection) {
    if (orderBy == null || orderBy.getItems() == null) {
      return;
    }
    for (var item : orderBy.getItems()) {
      var propertyName = aliasPropertyName(item);
      var alias = item.getAlias();
      var aliasClass =
          propertyName == null || alias == null ? null : aliasClassResolver.apply(alias);
      item.setDeclaredCollate(
          projectionItemForAlias(projection, item) != null
              ? null
              : declaredCollation(aliasClass, propertyName));
    }
  }

  /**
   * The collation {@code propertyName} carries throughout the hierarchy rooted at {@code
   * schemaClass}, or {@code null} for the default collation. Every class of the hierarchy that has
   * the property contributes its declaration. A class that has no such property contributes its
   * schema-less values, so those values use the default collation and can disagree with a declared
   * non-default collation.
   */
  @Nullable
  public static Collate declaredCollation(
      @Nullable SchemaClass schemaClass, @Nullable String propertyName) {
    if (schemaClass == null || propertyName == null) {
      return null;
    }
    var hierarchy = new ArrayList<SchemaClass>();
    hierarchy.add(schemaClass);
    hierarchy.addAll(schemaClass.getAllSubclasses());
    return declaredCollation(hierarchy, propertyName);
  }

  /**
   * The single non-default collation {@code propertyName} carries across {@code classes}, or {@code
   * null} when it carries none, when the one it carries is the default, or when two of the classes
   * disagree.
   *
   * <p>Public because the native Gremlin path needs the same answer over a different class set: it
   * sorts an element stream whose class is not pinned at strategy time, so it asks over the whole
   * vertex and edge hierarchy instead of over one target.
   */
  @Nullable
  public static Collate declaredCollation(
      Collection<SchemaClass> classes, String propertyName) {
    Collate agreed = null;
    for (var schemaClass : classes) {
      var property = schemaClass.getProperty(propertyName);
      if (property == null) {
        continue;
      }
      var collate = property.getCollate();
      if (collate == null || DefaultCollate.NAME.equals(collate.getName())) {
        // A declared default is a disagreement with a declared non-default one, and agreement on
        // the default is the same as no declaration at all. Both end at the default collation.
        return null;
      }
      if (agreed == null) {
        agreed = collate;
      } else if (!agreed.getName().equals(collate.getName())) {
        return null;
      }
    }
    return agreed;
  }

  /**
   * The property name of a plain {@code SELECT} item — {@code ORDER BY name} — or {@code null} when
   * the item is anything else. The alias of such an item <em>is</em> the property name, because the
   * item carries no modifier to chain onto it.
   */
  @Nullable
  private static String targetPropertyName(SQLOrderByItem item) {
    if (item.getRecordAttr() != null || item.getRid() != null || item.getModifier() != null) {
      return null;
    }
    return item.getAlias();
  }

  /**
   * The property name of a MATCH item — {@code ORDER BY a.name} — or {@code null} when the item is
   * anything else. The alias names the pattern node and the single modifier segment names the
   * property.
   */
  @Nullable
  private static String aliasPropertyName(SQLOrderByItem item) {
    if (item.getRecordAttr() != null || item.getRid() != null || item.getAlias() == null) {
      return null;
    }
    return plainPropertySegment(item.getModifier());
  }

  /**
   * The property name of a one-segment plain property modifier, or {@code null} for every other
   * modifier shape: a record attribute, a method call, a bracket accessor, or a chain of two or more
   * segments, none of which is governed by a single declared property.
   */
  @Nullable
  private static String plainPropertySegment(@Nullable SQLModifier modifier) {
    if (modifier == null
        || modifier.next != null
        || modifier.squareBrackets
        || modifier.methodCall != null
        || modifier.suffix == null
        || modifier.suffix.identifier == null) {
      return null;
    }
    return modifier.suffix.identifier.getStringValue();
  }
}
