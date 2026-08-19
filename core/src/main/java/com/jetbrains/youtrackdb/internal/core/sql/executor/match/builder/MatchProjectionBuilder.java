package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import java.util.List;
import java.util.Locale;

/**
 * Fluent, IR-first builders for the MATCH statement tail consumed by {@link
 * MatchExecutionPlanner} — {@code RETURN} projection items and their {@code AS} names, plus the
 * {@code ORDER BY} and {@code GROUP BY} clause containers built over them. Gremlin aggregate
 * terminators and GQL projection wiring should prefer these factories over parsing SQL text
 * fragments or hand-assembling the nodes, so the two front-ends cannot drift apart on the shape
 * they hand the planner.
 */
public final class MatchProjectionBuilder {

  /** Context variable for the candidate row in a group bucket ({@code list($currentMatch)}). */
  public static final String CURRENT_MATCH_VARIABLE = "$currentMatch";

  private MatchProjectionBuilder() {
    // Static helper — no instances.
  }

  /** {@code count(*)}. */
  public static SQLExpression countStar() {
    return ProjectionExpressionFactories.countStar();
  }

  /** {@code list($currentMatch)}. */
  public static SQLExpression listCurrentMatch() {
    return ProjectionExpressionFactories.listCurrentMatch();
  }

  /** {@code list(alias)} — collects the grouped element under {@code alias}. */
  public static SQLExpression listAlias(String alias) {
    return ProjectionExpressionFactories.listAlias(alias);
  }

  /**
   * {@code list(expr)} — collects a caller-built expression rather than a whole element, for
   * {@code values(key).group()}, which buckets the projected values.
   */
  public static SQLExpression listExpression(SQLExpression expression) {
    return ProjectionExpressionFactories.listExpression(expression);
  }

  /**
   * {@code fn(property)} for a bare property name emitted by {@code values("age").mean()} and
   * similar shapes.
   */
  public static SQLExpression propertyAggregate(String functionName, String propertyField) {
    return ProjectionExpressionFactories.propertyAggregate(
        functionName.toLowerCase(Locale.ROOT), propertyField);
  }

  /** {@code fn(fieldExpr)} when the field is already a resolved {@link SQLExpression}. */
  public static SQLExpression propertyAggregate(String functionName, SQLExpression field) {
    return ProjectionExpressionFactories.propertyAggregate(
        functionName.toLowerCase(Locale.ROOT), field);
  }

  /**
   * {@code alias} as a RETURN item — the bare identifier expression that projects a pattern alias
   * (the whole element), with no property or record-attribute modifier.
   */
  public static SQLExpression aliasColumn(String alias) {
    return ProjectionExpressionFactories.aliasExpression(alias);
  }

  /** The {@code AS name} identifier a RETURN column publishes its Result key under. */
  public static SQLIdentifier columnAlias(String name) {
    return ProjectionExpressionFactories.columnAlias(name);
  }

  /** {@code ORDER BY item1, item2, …} over pre-built order items. */
  public static SQLOrderBy orderBy(List<SQLOrderByItem> items) {
    return ProjectionExpressionFactories.orderBy(items);
  }

  /** {@code GROUP BY expr1, expr2, …}. */
  public static SQLGroupBy groupBy(SQLExpression... items) {
    return ProjectionExpressionFactories.groupBy(items);
  }
}
