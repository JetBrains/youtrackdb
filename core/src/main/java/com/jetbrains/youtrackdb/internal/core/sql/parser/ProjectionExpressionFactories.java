package com.jetbrains.youtrackdb.internal.core.sql.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@link SQLExpression} projection nodes in the same AST shape as {@link YouTrackDBSql}
 * without going through a SQL text round-trip. Shared by {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder}
 * and other MATCH IR front-ends.
 */
public final class ProjectionExpressionFactories {

  /** The MATCH context variable that binds the current row's matched aliases. */
  private static final String MATCHED_VARIABLE = "$matched";

  /** Record-attribute namespace prefix ({@code @rid} / {@code @class}) — a segment starting with
   *  this is a record attribute rather than a plain property. */
  private static final String RECORD_ATTRIBUTE_PREFIX = "@";

  private ProjectionExpressionFactories() {
    // Static helper — no instances.
  }

  /** {@code count(*)} — global aggregate over the match bucket. */
  public static SQLExpression countStar() {
    return functionCall("count", starArgument());
  }

  /** {@code list($currentMatch)} — default {@code group()} value column. */
  public static SQLExpression listCurrentMatch() {
    return functionCall("list", contextVariable("$currentMatch"));
  }

  /** {@code list(alias)} — collects the grouped element bound to {@code alias} into a list. */
  public static SQLExpression listAlias(String alias) {
    if (alias == null || alias.isBlank()) {
      throw new IllegalArgumentException("blank alias");
    }
    return functionCall("list", bareIdentifier(alias));
  }

  /** {@code list(expr)} with a caller-built expression (e.g. {@code alias.name}). */
  public static SQLExpression listExpression(SQLExpression expression) {
    if (expression == null) {
      throw new IllegalArgumentException("null list expression");
    }
    return functionCall("list", expression);
  }

  /**
   * {@code fn(field)} where {@code field} is a bare property name ({@code values("age").mean()}).
   */
  public static SQLExpression propertyAggregate(String functionName, String propertyField) {
    if (functionName == null || functionName.isBlank()) {
      throw new IllegalArgumentException("blank aggregate function name");
    }
    if (propertyField == null || propertyField.isBlank()) {
      throw new IllegalArgumentException("blank property field");
    }
    return functionCall(functionName, bareIdentifier(propertyField));
  }

  /** {@code fn(expr)} with a caller-built field expression (e.g. {@code alias.age}). */
  public static SQLExpression propertyAggregate(String functionName, SQLExpression field) {
    if (functionName == null || functionName.isBlank()) {
      throw new IllegalArgumentException("blank aggregate function name");
    }
    if (field == null) {
      throw new IllegalArgumentException("null field expression");
    }
    return functionCall(functionName, field);
  }

  static SQLExpression functionCall(String functionName, SQLExpression... params) {
    var call = new SQLFunctionCall(-1);
    call.name = new SQLIdentifier(functionName);
    for (var param : params) {
      call.addParam(param);
    }
    var levelZero = new SQLLevelZeroIdentifier(-1);
    levelZero.functionCall = call;
    var baseIdentifier = new SQLBaseIdentifier(-1);
    baseIdentifier.levelZero = levelZero;
    var base = new SQLBaseExpression(-1);
    base.setIdentifier(baseIdentifier);
    var expr = new SQLExpression(-1);
    expr.setMathExpression(base);
    return expr;
  }

  static SQLExpression starArgument() {
    var suffix = new SQLSuffixIdentifier(-1);
    suffix.star = true;
    var baseIdentifier = new SQLBaseIdentifier(-1);
    baseIdentifier.suffix = suffix;
    var base = new SQLBaseExpression(-1);
    base.setIdentifier(baseIdentifier);
    var expr = new SQLExpression(-1);
    expr.setMathExpression(base);
    return expr;
  }

  static SQLExpression bareIdentifier(String name) {
    return new SQLExpression(new SQLIdentifier(name));
  }

  static SQLExpression contextVariable(String name) {
    return bareIdentifier(name);
  }

  // --- RETURN column plumbing (bare alias reference + AS name) ----------------------------------
  // The two nodes a RETURN column is made of. Both embed the caller's name as a literal
  // SQLIdentifier, so a Gremlin-supplied alias or label can never be re-tokenized into extra
  // syntax, and both stay in one place so the RETURN shape cannot drift between front-ends.

  /**
   * {@code alias} as a RETURN item — a bare identifier expression naming a pattern alias, with no
   * property or record-attribute modifier chained onto it (that is {@link #aliasProperty} /
   * {@link #aliasRecordAttribute}).
   *
   * <p>Unlike most factories in this class, this one does not reject a blank name. Its callers supply
   * an internally generated pattern alias that their own guards already established is present, and
   * the Gremlin translator reaches it from inside {@code TraversalStrategy.apply()}, where
   * {@code GremlinToMatchStrategy}'s safety net turns any {@link RuntimeException} into a silent
   * decline to the native pipeline — so a throw here would trade a caller bug for a lost
   * translation with no diagnostic rather than surfacing it.
   */
  public static SQLExpression aliasExpression(String alias) {
    return bareIdentifier(alias);
  }

  /**
   * The {@code AS name} identifier of a RETURN column — the Result key the row is published under,
   * as opposed to the expression that computes it.
   *
   * <p>Does not reject a blank name, for the reason given on {@link #aliasExpression} plus one
   * more: this name is user-facing (a Gremlin {@code as(...)} label reaches the plan through it
   * verbatim), so validating it here would move a rejection decision out of the front-end that owns
   * it.
   */
  public static SQLIdentifier columnAlias(String name) {
    return new SQLIdentifier(name);
  }

  // --- Field access (alias.property / alias.@rid) built as AST, no SQL-text round-trip ----------
  // Same AST shape the parser produces for `SELECT alias.property FROM …`; the property key is a
  // literal SQLIdentifier, so a Gremlin-supplied key can never be re-tokenized into extra syntax.

  /** {@code alias.propertyKey} — a field access on the boundary/alias node. */
  public static SQLExpression aliasProperty(String alias, String propertyKey) {
    requireNonBlank(alias, "alias");
    requireNonBlank(propertyKey, "property key");
    return new SQLExpression(new SQLIdentifier(alias), propertyModifier(propertyKey));
  }

  /** {@code alias.@rid} / {@code alias.@class} — a record-attribute access on the alias node. */
  public static SQLExpression aliasRecordAttribute(String alias, String attribute) {
    requireNonBlank(alias, "alias");
    requireNonBlank(attribute, "record attribute");
    return new SQLExpression(new SQLIdentifier(alias), recordAttributeModifier(attribute));
  }

  /**
   * {@code $matched.alias.seg1.seg2…} — the cross-alias accessor MATCH uses for {@code
   * where(P.eq("label"))} label references. The base is the {@code $matched} context variable; the
   * alias and each following segment chain as modifiers — a record attribute ({@code @rid} /
   * {@code @class}) when the segment starts with {@code @}, a plain property otherwise. Built as AST
   * (the modifier chain is assembled directly), so a Gremlin-supplied alias or segment can never be
   * re-tokenized into extra syntax — the previous form concatenated them into a {@code SELECT}
   * string and reparsed it.
   */
  public static SQLExpression matchedVariable(String alias, String... segments) {
    requireNonBlank(alias, "alias");
    if (segments == null || segments.length == 0) {
      throw new IllegalArgumentException("matched access requires at least one segment");
    }
    var first = segmentModifier(alias);
    var tail = first;
    for (var segment : segments) {
      requireNonBlank(segment, "segment");
      var modifier = segmentModifier(segment);
      tail.next = modifier;
      tail = modifier;
    }
    return new SQLExpression(new SQLIdentifier(MATCHED_VARIABLE), first);
  }

  /**
   * {@code propertyKey.methodName()} — a zero-argument SQL method call chained onto a bare property
   * name, e.g. {@code age.type()}. Built as AST, so a caller-supplied key or method name can never
   * be re-tokenized into extra syntax.
   *
   * <p>This factory lives in the parser package rather than beside its only caller because {@link
   * SQLMethodCall#methodName} and {@link SQLModifier#methodCall} are package-private: a builder in
   * {@code …executor.match.builder} cannot assemble the two nodes at all.
   */
  public static SQLExpression propertyMethodCall(String propertyKey, String methodName) {
    requireNonBlank(propertyKey, "property key");
    requireNonBlank(methodName, "method name");
    var call = new SQLMethodCall(-1);
    call.methodName = new SQLIdentifier(methodName);
    var modifier = new SQLModifier(-1);
    modifier.methodCall = call;
    return new SQLExpression(new SQLIdentifier(propertyKey), modifier);
  }

  private static SQLModifier segmentModifier(String segment) {
    return segment.startsWith(RECORD_ATTRIBUTE_PREFIX)
        ? recordAttributeModifier(segment)
        : propertyModifier(segment);
  }

  // --- ORDER BY items (built as AST) ------------------------------------------------------------

  /** {@code ORDER BY alias.propertyKey ASC|DESC}. */
  public static SQLOrderByItem orderByProperty(
      String alias, String propertyKey, boolean ascending) {
    requireNonBlank(alias, "alias");
    requireNonBlank(propertyKey, "property key");
    return orderByItem(alias, propertyModifier(propertyKey), ascending);
  }

  /** {@code ORDER BY alias.@rid|@class ASC|DESC}. */
  public static SQLOrderByItem orderByRecordAttribute(
      String alias, String attribute, boolean ascending) {
    requireNonBlank(alias, "alias");
    requireNonBlank(attribute, "record attribute");
    return orderByItem(alias, recordAttributeModifier(attribute), ascending);
  }

  /**
   * {@code ORDER BY item1, item2, …} — the clause container around items built by
   * {@link #orderByProperty} / {@link #orderByRecordAttribute}. The list is copied into a fresh
   * mutable list, because {@link SQLOrderBy#setItems} stores the reference and {@link
   * SQLOrderBy#addItem} appends to it: handing the caller's list straight in would let a later
   * planner-side {@code addItem} write back into the caller's collection.
   */
  public static SQLOrderBy orderBy(List<SQLOrderByItem> items) {
    if (items == null || items.isEmpty()) {
      throw new IllegalArgumentException("ORDER BY needs at least one item");
    }
    var orderBy = new SQLOrderBy(-1);
    orderBy.setItems(new ArrayList<>(items));
    return orderBy;
  }

  /** {@code GROUP BY expr1, expr2, …}. */
  public static SQLGroupBy groupBy(SQLExpression... items) {
    if (items == null || items.length == 0) {
      throw new IllegalArgumentException("GROUP BY needs at least one item");
    }
    var groupBy = new SQLGroupBy(-1);
    for (var item : items) {
      if (item == null) {
        throw new IllegalArgumentException("null GROUP BY item");
      }
      groupBy.addItem(item);
    }
    return groupBy;
  }

  // --- LIMIT / SKIP (built as AST) --------------------------------------------------------------

  /** {@code LIMIT value}. */
  public static SQLLimit limit(long value) {
    var limit = new SQLLimit(-1);
    limit.num = integerNode(value);
    return limit;
  }

  /** {@code SKIP value}. */
  public static SQLSkip skip(long value) {
    var skip = new SQLSkip(-1);
    skip.num = integerNode(value);
    return skip;
  }

  private static SQLOrderByItem orderByItem(String alias, SQLModifier modifier, boolean ascending) {
    var item = new SQLOrderByItem();
    item.setAlias(alias);
    item.modifier = modifier;
    item.setType(ascending ? SQLOrderByItem.ASC : SQLOrderByItem.DESC);
    return item;
  }

  private static SQLModifier propertyModifier(String propertyKey) {
    var modifier = new SQLModifier(-1);
    modifier.suffix = new SQLSuffixIdentifier(new SQLIdentifier(propertyKey));
    return modifier;
  }

  private static SQLModifier recordAttributeModifier(String attribute) {
    var attr = new SQLRecordAttribute(-1);
    attr.setName(attribute);
    var modifier = new SQLModifier(-1);
    modifier.suffix = new SQLSuffixIdentifier(attr);
    return modifier;
  }

  private static SQLInteger integerNode(long value) {
    var num = new SQLInteger(-1);
    num.setValue(value);
    return num;
  }

  private static void requireNonBlank(String value, String what) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("blank " + what);
    }
  }
}
